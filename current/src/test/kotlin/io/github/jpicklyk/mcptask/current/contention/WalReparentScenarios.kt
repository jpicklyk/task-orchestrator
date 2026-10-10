package io.github.jpicklyk.mcptask.current.contention

import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reparent scenarios of item 36c719db (carry-in P15 R11): the subtree restamp after a reparent must commit in the same
 * unit as the item's own move, so no reader or racing writer ever sees the subtree half-restamped, and a fault inside
 * the restamp rolls the whole reparent back. Fixture tree, built per iteration:
 *
 * ```
 * R (depth 0)            Q (depth 0)
 * `- X (1, WORK)         `- Q1 (1)
 *    `- D (2, p11-optional)
 *       `- E (3)
 * ```
 * Moving X under Q1 is depth +1 and root R -> Q for X, D and E. Expected values are hand-derived from that move.
 */
internal object WalReparentScenarios {
    private const val READS = 200

    private class Tree(
        val r: WorkItem,
        val x: WorkItem,
        val d: WorkItem,
        val e: WorkItem,
        val q: WorkItem,
        val q1: WorkItem
    )

    private suspend fun tree(
        drv: P11Driver,
        tag: String
    ): Tree {
        val r = drv.item("$tag r", Role.QUEUE)
        // X is already in WORK so starting D cascades nothing onto X: the only writer of X is the reparent.
        val x = drv.item("$tag x", Role.WORK, parent = r)
        val d = drv.item("$tag d", Role.QUEUE, type = "p11-optional", parent = x)
        val e = drv.item("$tag e", Role.QUEUE, parent = d)
        val q = drv.item("$tag q", Role.QUEUE)
        val q1 = drv.item("$tag q1", Role.QUEUE, parent = q)
        return Tree(r, x, d, e, q, q1)
    }

    private suspend fun reparent(
        drv: P11Driver,
        item: WorkItem,
        newParent: WorkItem
    ): JsonObject =
        drv.rig.call(
            ManageItemsTool(),
            "operation" to JsonPrimitive("update"),
            "items" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("parentId", newParent.id.toString())
                        }
                    )
                }
        )

    private suspend fun createUnder(
        drv: P11Driver,
        parent: WorkItem,
        title: String
    ): JsonObject =
        drv.rig.call(
            ManageItemsTool(),
            "operation" to JsonPrimitive("create"),
            "items" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", title)
                            put("parentId", parent.id.toString())
                        }
                    )
                }
        )

    private fun JsonObject.data(): JsonObject = this["data"]?.jsonObject ?: fail("no data: $this")

    /** The stored root_id blob as SQLite's hex() prints it: the UUID's 16 big-endian bytes. */
    private fun hexOf(id: UUID): String = id.toString().replace("-", "").uppercase()

    /** title -> (depth, hex(root_id)) for [titles], read in ONE statement, so it is one committed snapshot. */
    private fun snapshot(
        jdbcUrl: String,
        titles: List<String>
    ): Map<String, Pair<Int, String?>> =
        DriverManager.getConnection(jdbcUrl).use { c ->
            val marks = titles.joinToString(",") { "?" }
            c.prepareStatement("SELECT title, depth, hex(root_id) FROM work_items WHERE title IN ($marks)").use { ps ->
                titles.forEachIndexed { i, t -> ps.setString(i + 1, t) }
                ps.executeQuery().use { rs ->
                    val out = linkedMapOf<String, Pair<Int, String?>>()
                    while (rs.next()) out[rs.getString(1)] = rs.getInt(2) to rs.getString(3)
                    out
                }
            }
        }

    /**
     * R1: racer 0 moves X under Q1, racer 1 creates a child under D, racer 2 starts D, racer 3 reads the subtree [READS]
     * times. Every snapshot is either wholly before the move (X, D, E at 1, 2, 3 under R) or wholly after it (2, 3, 4
     * under Q), and a created child always sits one level below D under D's root. Move first: the child is created at
     * depth 4 under Q. Create first: it is created at depth 3 under R, then the move restamps it. Either way the end
     * state is the same and D's start applies.
     */
    suspend fun r1ReparentVersusAdvanceAndCreate(fx: WalFixture) {
        val drv = fx.d
        val tag = "R1 ${fx.next()}"
        val t = tree(drv, tag)
        val child = "$tag child"
        val subtree = listOf(t.x.title, t.d.title, t.e.title)
        val rHex = hexOf(t.r.id)
        val qHex = hexOf(t.q.id)
        val before = mapOf(t.x.title to (1 to rHex), t.d.title to (2 to rHex), t.e.title to (3 to rHex))
        val after = mapOf(t.x.title to (2 to qHex), t.d.title to (3 to qHex), t.e.title to (4 to qHex))
        val mark = fx.rig.maxSeq()

        val results: List<Any> =
            fx.go(4) { i ->
                val racer = fx.driverFor(i)
                when (i) {
                    0 -> reparent(racer, t.x, t.q1)
                    1 -> createUnder(racer, t.d, child)
                    2 -> racer.advance(t.d, "start")
                    else -> {
                        var sawBefore = 0
                        var sawAfter = 0
                        repeat(READS) {
                            val snap = snapshot(drv.jdbcUrl, subtree + child)
                            val core = snap.filterKeys { it != child }
                            when (core) {
                                before -> sawBefore++
                                after -> sawAfter++
                                else -> fail("half-restamped subtree observed: $core (before=$before after=$after)")
                            }
                            snap[child]?.let { c ->
                                val dRow = snap.getValue(t.d.title)
                                assertEquals(dRow.first + 1 to dRow.second, c, "the new child sits under D's placement: $snap")
                            }
                        }
                        sawBefore to sawAfter
                    }
                }
            }

        val moved = (results[0] as JsonObject).data()
        assertEquals(1, moved["updated"]!!.jsonPrimitive.int, "the reparent applies: ${results[0]}")
        val created =
            (results[1] as JsonObject)
                .data()["items"]!!
                .jsonArray
                .single()
                .jsonObject
        val createdDepth = created["depth"]!!.jsonPrimitive.int
        assertTrue(createdDepth == 3 || createdDepth == 4, "created under D before (3) or after (4) the move: $created")
        fx.ordering(index0First = createdDepth == 4, detail = results)
        val started = results[2] as JsonObject
        assertEquals(true, started.flag("applied"), "$started")
        assertEquals("work", started.text("newRole"), "$started")

        assertEquals(after, snapshot(drv.jdbcUrl, subtree), "the whole subtree is restamped")
        assertEquals(4 to qHex, snapshot(drv.jdbcUrl, listOf(child))[child], "the new child is restamped with it")
        assertEquals(t.q1.id, drv.reload(t.x).parentId)
        assertEquals(Role.WORK, drv.role(t.d))
        assertEquals(2, fx.rig.rowsAfter(mark).count { it.type == "item.reparented" }, "one LEFT and one ENTERED row")
    }

    /**
     * R2: a fault raised while the restamp updates E (the deepest descendant, written only by the restamp statement)
     * rolls the whole reparent back: X, D and E keep parent_id, depth and root_id, and no item.updated or
     * item.reparented row is recorded. With the fault removed the same call restamps everything (control).
     */
    suspend fun r2RestampFaultRollsBack(fx: WalFixture) {
        val drv = fx.d
        val n = fx.next()
        val t = tree(drv, "R2 $n")
        val subtree = listOf(t.x, t.d, t.e)
        val placement = { w: WorkItem -> Triple(w.parentId, w.depth, w.rootId) }
        val before = subtree.map { placement(drv.reload(it)) }
        val trigger = "r2_restamp_fault_$n"
        rawExec(
            drv.jdbcUrl,
            "CREATE TRIGGER $trigger BEFORE UPDATE ON work_items WHEN OLD.title = '${t.e.title}' " +
                "BEGIN SELECT RAISE(ABORT, 'injected restamp fault'); END"
        )
        val mark = fx.rig.maxSeq()

        val faulted = reparent(drv, t.x, t.q1)

        assertEquals(0, faulted.data()["updated"]!!.jsonPrimitive.int, "the faulted reparent applies nothing: $faulted")
        assertEquals(1, faulted.data()["failed"]!!.jsonPrimitive.int, "$faulted")
        assertEquals(before, subtree.map { placement(drv.reload(it)) }, "X, D and E keep parent_id, depth and root_id")
        val rows = fx.rig.rowsAfter(mark)
        assertEquals(
            emptyList(),
            rows.filter { it.type == "item.updated" || it.type == "item.reparented" },
            "no event row survives the rollback"
        )

        rawExec(drv.jdbcUrl, "DROP TRIGGER $trigger")
        val healthy = reparent(drv, t.x, t.q1)
        assertEquals(1, healthy.data()["updated"]!!.jsonPrimitive.int, "control: $healthy")
        assertEquals(
            listOf(Triple(t.q1.id, 2, t.q.id), Triple(t.x.id, 3, t.q.id), Triple(t.d.id, 4, t.q.id)),
            subtree.map { placement(drv.reload(it)) },
            "control: the whole subtree is restamped"
        )
    }
}

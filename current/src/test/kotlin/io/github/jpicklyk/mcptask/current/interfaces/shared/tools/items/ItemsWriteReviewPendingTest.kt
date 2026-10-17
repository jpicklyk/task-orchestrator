package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the review-pending cascade fence through `items_write` (item 3de8da24, test-author seat): scenarios S1 to S12 of the
 * frozen test-plan, all EXISTING-SURFACE, through the real dispatcher (`complete_tree` in the orchestration profile, `delete` and
 * `update` in the knowledge profile).
 *
 * Oracles: current/docs/api-reference.md "Cascades" (terminal bullet: a parent in review is never moved; Suppressed `blocked: gate`
 * clause: a terminal cascade into a review parent is always suppressed, whatever its origin, `reason: review-pending`, no
 * `missing`; Not reported: no children or a non-terminal sibling), the `items_write` Cascade paragraph (`{itemId, from, to, applied,
 * reason?}` with reason `gate_blocked`), the `complete_tree` section (`includeRoot` default true), and the frozen P1 7cb8a3df
 * task-scope decisions D2, D4 and D5.
 *
 * Every suppression scenario is paired with a WORK-parent control through the same call shape that does apply the cascade.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteReviewPendingTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))

    private suspend fun IwKit.complete(vararg fields: Pair<String, JsonElement>): Dispatched =
        callIn(ToolProfile.ORCHESTRATION, twComplete(*fields), actor = IW_ACTOR)

    private suspend fun IwKit.role(id: UUID): Role = assertNotNull(item(id), "item $id").role!!

    private suspend fun IwKit.parentOf(id: UUID): UUID? = assertNotNull(item(id), "item $id").parentId

    private fun JsonObject.entry(id: UUID): JsonObject = treeItems().single { it.str("itemId") == id.toString() }

    private fun JsonObject.order(): List<String> = treeItems().map { it.str("itemId") }

    /** The ids of the entries, leaving the root's own last entry (if [rootLast]) out: sibling order is unspecified without a blocking edge. */
    private fun JsonObject.descendantSet(rootLast: Boolean = false): Set<String> =
        order().let { if (rootLast) it.dropLast(1) else it }.toSet()

    private fun JsonObject.counts(): List<String> =
        getValue("summary").jsonObject.let { s -> listOf("total", "applied", "skipped", "failed").map { s.str(it) } }

    private fun JsonObject.cascadeEntries(): List<JsonObject> = getValue("cascades").jsonArray.map { it.jsonObject }

    private fun JsonObject.cascadeList(): List<JsonObject> = getValue("cascade").jsonArray.map { it.jsonObject }

    private suspend fun IwKit.dep(
        from: UUID,
        to: UUID
    ) {
        rig.raw.dependencyRepository().create(Dependency(fromItemId = from, toItemId = to, type = DependencyType.BLOCKS))
    }

    /** The suppressed review-pending cascade entry of a complete_tree entry: exact keys, exact values, no `missing`. */
    private fun assertReviewPending(
        entry: JsonObject,
        parent: UUID
    ) {
        val c = entry.cascadeEntries().single()
        assertEquals(setOf("itemId", "previousRole", "targetRole", "applied", "blocked", "reason"), c.keys, "cascade: $c")
        assertEquals(parent.toString(), c.str("itemId"))
        assertEquals("review", c.str("previousRole"))
        assertEquals("terminal", c.str("targetRole"))
        assertEquals(JsonPrimitive(false), c.getValue("applied"))
        assertEquals("gate", c.str("blocked"))
        assertEquals("review-pending", c.str("reason"))
        assertFalse("missing" in c, "review-pending carries no missing: $c")
    }

    /** The applied cascade entry of a complete_tree entry (the WORK-parent control). */
    private fun assertCascadeApplied(
        entry: JsonObject,
        parent: UUID
    ) {
        val c = entry.cascadeEntries().single()
        assertEquals(parent.toString(), c.str("itemId"))
        assertEquals("work", c.str("previousRole"))
        assertEquals("terminal", c.str("targetRole"))
        assertEquals(JsonPrimitive(true), c.getValue("applied"))
        assertFalse("blocked" in c || "reason" in c, "an applied cascade carries neither blocked nor reason: $c")
    }

    private fun assertDeleteSuppressed(
        result: JsonObject,
        parent: UUID
    ) {
        val c = result.cascadeList().single()
        assertEquals(setOf("itemId", "from", "to", "applied", "reason"), c.keys, "cascade: $c")
        assertEquals(parent.toString(), c.str("itemId"))
        assertEquals("review", c.str("from"))
        assertEquals("terminal", c.str("to"))
        assertEquals(JsonPrimitive(false), c.getValue("applied"))
        assertEquals("gate_blocked", c.str("reason"))
    }

    private fun assertWriteCascadeApplied(
        result: JsonObject,
        parent: UUID
    ) {
        val c = result.cascadeList().single()
        assertEquals(setOf("itemId", "from", "to", "applied"), c.keys, "an applied cascade carries no reason: $c")
        assertEquals(parent.toString(), c.str("itemId"))
        assertEquals("work", c.str("from"))
        assertEquals("terminal", c.str("to"))
        assertEquals(JsonPrimitive(true), c.getValue("applied"))
    }

    private suspend fun IwKit.reviewTree(): Triple<WorkItem, WorkItem, WorkItem> {
        val p = rig.seed("P", role = Role.REVIEW)
        val a = rig.seed("A", p, Role.QUEUE)
        val b = rig.seed("B", p, Role.QUEUE)
        return Triple(p, a, b)
    }

    // ------------------------------------------------------------------ complete_tree

    @Test
    fun `S1 complete_tree into a review root with includeRoot false completes the children and suppresses the root cascade`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val ok =
                k
                    .complete("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(false))
                    .envelope.body
                    .okResult()
            assertEquals(setOf(a.id, b.id).map { it.toString() }.toSet(), ok.descendantSet(), "both children, root left out")
            for (id in listOf(a.id, b.id)) {
                val e = ok.entry(id)
                assertEquals("applied", e.str("outcome"), "entry: $e")
                assertEquals("queue", e.str("previousRole"))
                assertEquals("terminal", e.str("newRole"))
            }
            val last = UUID.fromString(ok.order().last())
            val first = UUID.fromString(ok.order().first())
            assertFalse("cascades" in ok.entry(first), "only the last child's entry carries the cascade: ${ok.entry(first)}")
            assertReviewPending(ok.entry(last), p.id)
            assertEquals(listOf("2", "2", "0", "0"), ok.counts())
            assertEquals(listOf(Role.TERMINAL, Role.TERMINAL, Role.REVIEW), listOf(a.id, b.id, p.id).map { k.role(it) })
        }

    @Test
    fun `S2 control - the same call over a WORK root applies the root cascade`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.WORK)
            val a = k.rig.seed("A", p, Role.QUEUE)
            val b = k.rig.seed("B", p, Role.QUEUE)
            val ok =
                k
                    .complete("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(false))
                    .envelope.body
                    .okResult()
            assertEquals(setOf(a.id, b.id).map { it.toString() }.toSet(), ok.descendantSet())
            assertCascadeApplied(ok.entry(UUID.fromString(ok.order().last())), p.id)
            assertEquals(Role.TERMINAL, k.role(p.id))
        }

    @Test
    fun `S3 includeRoot true - the review root entry itself is applied review to terminal after the suppressed cascade`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val ok =
                k
                    .complete("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(true))
                    .envelope.body
                    .okResult()
            assertEquals(p.id.toString(), ok.order().last(), "the root last")
            assertEquals(setOf(a.id, b.id).map { it.toString() }.toSet(), ok.descendantSet(rootLast = true))
            assertReviewPending(ok.entry(UUID.fromString(ok.order()[1])), p.id)
            val root = ok.entry(p.id)
            assertEquals("applied", root.str("outcome"), "entry: $root")
            assertEquals("review", root.str("previousRole"))
            assertEquals("terminal", root.str("newRole"))
            assertEquals(listOf("3", "3", "0", "0"), ok.counts())
            assertEquals(Role.TERMINAL, k.role(p.id))
        }

    @Test
    fun `S4 includeRoot omitted defaults to true and behaves as S3`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val ok =
                k
                    .complete("rootId" to jstr(p.id.toString()))
                    .envelope.body
                    .okResult()
            assertEquals(p.id.toString(), ok.order().last())
            assertEquals(setOf(a.id, b.id).map { it.toString() }.toSet(), ok.descendantSet(rootLast = true))
            assertReviewPending(ok.entry(UUID.fromString(ok.order()[1])), p.id)
            assertEquals("applied", ok.entry(p.id).str("outcome"))
            assertEquals("review", ok.entry(p.id).str("previousRole"))
            assertEquals("terminal", ok.entry(p.id).str("newRole"))
            assertEquals(listOf("3", "3", "0", "0"), ok.counts())
            assertEquals(Role.TERMINAL, k.role(p.id))
        }

    @Test
    fun `S5 cancel origin is suppressed the same way, and the WORK-parent control applies`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val ok =
                k
                    .complete(
                        "rootId" to jstr(p.id.toString()),
                        "trigger" to jstr("cancel"),
                        "includeRoot" to JsonPrimitive(false)
                    ).envelope.body
                    .okResult()
            assertEquals("cancel", ok.str("trigger"))
            assertEquals(setOf(a.id, b.id).map { it.toString() }.toSet(), ok.descendantSet())
            assertReviewPending(ok.entry(UUID.fromString(ok.order().last())), p.id)
            assertEquals(Role.REVIEW, k.role(p.id), "cancel origin does not move a review parent either")
            assertEquals(listOf(Role.TERMINAL, Role.TERMINAL), listOf(a.id, b.id).map { k.role(it) })

            val k2 = kit()
            val wp = k2.rig.seed("WP", role = Role.WORK)
            k2.rig.seed("WA", wp, Role.QUEUE)
            k2.rig.seed("WB", wp, Role.QUEUE)
            val control =
                k2
                    .complete(
                        "rootId" to jstr(wp.id.toString()),
                        "trigger" to jstr("cancel"),
                        "includeRoot" to JsonPrimitive(false)
                    ).envelope.body
                    .okResult()
            assertCascadeApplied(control.entry(UUID.fromString(control.order().last())), wp.id)
            assertEquals(Role.TERMINAL, k2.role(wp.id))
        }

    @Test
    fun `S6 the itemIds form suppresses identically, and the WORK-parent control applies`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val ok =
                k
                    .complete("itemIds" to twIds(a.id, b.id))
                    .envelope.body
                    .okResult()
            assertNull(ok["id"], "no rootId was given")
            assertEquals(listOf(a.id, b.id).map { it.toString() }, ok.order())
            assertReviewPending(ok.entry(b.id), p.id)
            assertEquals(listOf("2", "2", "0", "0"), ok.counts())
            assertEquals(Role.REVIEW, k.role(p.id))

            val k2 = kit()
            val wp = k2.rig.seed("WP", role = Role.WORK)
            val wa = k2.rig.seed("WA", wp, Role.QUEUE)
            val wb = k2.rig.seed("WB", wp, Role.QUEUE)
            val control =
                k2
                    .complete("itemIds" to twIds(wa.id, wb.id))
                    .envelope.body
                    .okResult()
            assertCascadeApplied(control.entry(wb.id), wp.id)
            assertEquals(Role.TERMINAL, k2.role(wp.id))
        }

    // ------------------------------------------------------------------ items_write delete / reparent

    @Test
    fun `S7 deleting the last non-terminal child of a review parent reports a gate_blocked cascade, parent stays in review`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.REVIEW)
            k.rig.seed("T", p, Role.TERMINAL)
            val n = k.rig.seed("N", p, Role.QUEUE)
            val ok =
                k
                    .call(deleteOp(n.id))
                    .envelope.body
                    .okResult()
            assertEquals("1", ok.getValue("deleted").toString())
            assertDeleteSuppressed(ok, p.id)
            assertNull(k.item(n.id), "the child is deleted")
            assertEquals(Role.REVIEW, k.role(p.id))
        }

    @Test
    fun `S8 control - the same delete under a WORK parent applies the cascade`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.WORK)
            k.rig.seed("T", p, Role.TERMINAL)
            val n = k.rig.seed("N", p, Role.QUEUE)
            val ok =
                k
                    .call(deleteOp(n.id))
                    .envelope.body
                    .okResult()
            assertWriteCascadeApplied(ok, p.id)
            assertEquals(Role.TERMINAL, k.role(p.id))
        }

    @Test
    fun `S9 reparenting the last non-terminal child away from a review parent reports the same summary`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.REVIEW)
            k.rig.seed("T", p, Role.TERMINAL)
            val n = k.rig.seed("N", p, Role.QUEUE)
            val q = k.rig.seed("Q", role = Role.QUEUE)
            val ok =
                k
                    .call(updateOp(n.id, "parentId" to jstr(q.id.toString())))
                    .envelope.body
                    .okResult()
            assertDeleteSuppressed(ok, p.id)
            assertEquals(Role.REVIEW, k.role(p.id))
            assertEquals(q.id, k.parentOf(n.id), "the child moved under Q")
        }

    @Test
    fun `S10 control - the same reparent away from a WORK parent applies the cascade`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.WORK)
            k.rig.seed("T", p, Role.TERMINAL)
            val n = k.rig.seed("N", p, Role.QUEUE)
            val q = k.rig.seed("Q", role = Role.QUEUE)
            val ok =
                k
                    .call(updateOp(n.id, "parentId" to jstr(q.id.toString())))
                    .envelope.body
                    .okResult()
            assertWriteCascadeApplied(ok, p.id)
            assertEquals(Role.TERMINAL, k.role(p.id))
        }

    @Test
    fun `S11 deleting the sole child of a review parent reports no cascade, with a control through the S7 shape`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.REVIEW)
            val only = k.rig.seed("Only", p, Role.QUEUE)
            val ok =
                k
                    .call(deleteOp(only.id))
                    .envelope.body
                    .okResult()
            assertFalse("cascade" in ok, "a parent left with no children is not reported: $ok")
            assertEquals(Role.REVIEW, k.role(p.id))

            val k2 = kit()
            val p2 = k2.rig.seed("P", role = Role.REVIEW)
            k2.rig.seed("T", p2, Role.TERMINAL)
            val n2 = k2.rig.seed("N", p2, Role.QUEUE)
            assertTrue(
                "cascade" in
                    k2
                        .call(deleteOp(n2.id))
                        .envelope.body
                        .okResult(),
                "control: the S7 shape does report"
            )
        }

    @Test
    fun `S12 deleting one of two non-terminal children reports no cascade, with a control through the S7 shape`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.REVIEW)
            val a = k.rig.seed("A", p, Role.QUEUE)
            k.rig.seed("B", p, Role.QUEUE)
            val ok =
                k
                    .call(deleteOp(a.id))
                    .envelope.body
                    .okResult()
            assertFalse("cascade" in ok, "a non-terminal sibling remains, so nothing is reported: $ok")
            assertEquals(Role.REVIEW, k.role(p.id))

            val k2 = kit()
            val p2 = k2.rig.seed("P", role = Role.REVIEW)
            k2.rig.seed("T", p2, Role.TERMINAL)
            val n2 = k2.rig.seed("N", p2, Role.QUEUE)
            assertTrue(
                "cascade" in
                    k2
                        .call(deleteOp(n2.id))
                        .envelope.body
                        .okResult(),
                "control: the S7 shape does report"
            )
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe spellings do not leak between the two surfaces`(): Unit =
        runBlocking {
            val k = kit()
            val (p, _, _) = k.reviewTree()
            val tree =
                k
                    .complete("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(false))
                    .envelope.body
                    .okResult()
            val treeCascade = tree.entry(UUID.fromString(tree.order().last())).getValue("cascades").toString()
            assertTrue("review-pending" in treeCascade, "control: the reason is present: $treeCascade")
            assertFalse("gate_blocked" in treeCascade, "complete_tree never spells the reason gate_blocked: $treeCascade")

            val k2 = kit()
            val p2 = k2.rig.seed("P", role = Role.REVIEW)
            k2.rig.seed("T", p2, Role.TERMINAL)
            val n2 = k2.rig.seed("N", p2, Role.QUEUE)
            val del =
                k2
                    .call(deleteOp(n2.id))
                    .envelope.body
                    .okResult()
                    .cascadeList()
                    .single()
            assertEquals("gate_blocked", del.str("reason"), "control: the reason is present: $del")
            assertFalse("blocked" in del, "items_write carries no blocked member: $del")
            assertFalse("review-pending" in del.toString(), "items_write never spells review-pending: $del")
        }

    @Test
    fun `probe replaying the S1 call skips the children as already_terminal with no cascades and leaves the root in review`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            val args = arrayOf("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(false))
            k
                .complete(*args)
                .envelope.body
                .okResult()
            val again =
                k
                    .complete(*args)
                    .envelope.body
                    .okResult()
            for (id in listOf(a.id, b.id)) {
                val e = again.entry(id)
                assertEquals("skipped", e.str("outcome"), "entry: $e")
                assertEquals("already_terminal", e.str("reason"))
                assertFalse("cascades" in e, "no cascade on a skip: $e")
            }
            assertEquals(listOf("2", "0", "2", "0"), again.counts())
            assertEquals(Role.REVIEW, k.role(p.id))
        }

    @Test
    fun `probe replaying the delete is not_found and leaves the parent unchanged`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.rig.seed("P", role = Role.REVIEW)
            k.rig.seed("T", p, Role.TERMINAL)
            val n = k.rig.seed("N", p, Role.QUEUE)
            k
                .call(deleteOp(n.id))
                .envelope.body
                .okResult()
            val before = k.item(p.id)
            assertEquals(
                "not_found",
                k
                    .call(deleteOp(n.id))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertEquals(before, k.item(p.id), "the parent is untouched by the replay")
        }

    @Test
    fun `probe a blocking edge between the children reverses the order and the cascade stays on the last entry`(): Unit =
        runBlocking {
            val k = kit()
            val (p, a, b) = k.reviewTree()
            k.dep(b.id, a.id)
            val ok =
                k
                    .complete("rootId" to jstr(p.id.toString()), "includeRoot" to JsonPrimitive(false))
                    .envelope.body
                    .okResult()
            assertEquals(listOf(b.id, a.id).map { it.toString() }, ok.order(), "B blocks A, so B runs first")
            assertFalse("cascades" in ok.entry(b.id), "the earlier entry has no cascade: ${ok.entry(b.id)}")
            assertReviewPending(ok.entry(a.id), p.id)
            assertEquals(Role.REVIEW, k.role(p.id))
        }
}

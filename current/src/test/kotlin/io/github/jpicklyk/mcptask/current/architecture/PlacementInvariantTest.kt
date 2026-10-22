package io.github.jpicklyk.mcptask.current.architecture

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureWriteTestApp
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.createTreeOp
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.treeItemId
import io.github.jpicklyk.mcptask.current.test.updateOp
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Placement invariant (item 947f0230, task-scope AC1, test-plan S14; plan section 8 "PlacementInvariantTest"):
 * random sequences of create / reparent / move-to-root / delete driven across the service, `manage_items`, the REST item
 * routes and `items_write` `create_tree`; after EVERY step each stored row satisfies the placement invariant and
 * `StartupIntegrity.reportPlacementDrift` reports nothing.
 *
 * Oracle (independent of the code): plan 3.5 / task-scope AC1 - a root has depth 0 and rootId = its own id; every other row
 * has depth = parent.depth + 1 and rootId = parent.rootId. The expected values are recomputed from a model of the
 * parent links kept by the test itself (never read back from the rows under test), and the row set must equal the model's
 * (a delete removes exactly the subtree). Every item is created by one of the surfaces, so no fixture is seeded with a
 * hand-set depth or root.
 *
 * Probes: the three surfaces are interleaved on one database; a reparent targets any non-descendant (including a deeper
 * node, which changes the depth delta, and the root level); a delete is recursive so a whole subtree disappears; the
 * concurrent test races creates under a parent against reparents of that parent (AR-19: placement is derived from the
 * committed parent row under the writer lock).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PlacementInvariantTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    /** One way to write items. [create] returns every item it created with its parent (a tree creates two). */
    private interface Surface {
        val label: String

        suspend fun create(
            parent: UUID?,
            title: String
        ): List<Pair<UUID, UUID?>>

        suspend fun reparent(
            id: UUID,
            newParent: UUID?
        )

        suspend fun delete(id: UUID)
    }

    private inner class ServiceSurface(
        private val rig: EventLogRig
    ) : Surface {
        override val label = "service"

        override suspend fun create(
            parent: UUID?,
            title: String
        ): List<Pair<UUID, UUID?>> {
            val item =
                rig.ctx.itemCommandService
                    .create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = parent, title = title))
                    .orFail()
            return listOf(item.id to parent)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) {
            val current = rig.raw.workItemRepository().getById(id)!!
            val change = if (newParent == null) ParentChange.MoveToRoot else ParentChange.MoveUnder(newParent)
            rig.ctx.itemCommandService
                .patch(patchOf(current, parent = change), caller = null)
                .orFail()
        }

        override suspend fun delete(id: UUID) {
            rig.ctx.itemCommandService
                .delete(id, recursive = true, caller = null)
                .orFail()
        }
    }

    private inner class McpSurface(
        private val rig: EventLogRig
    ) : Surface {
        override val label = "mcp"

        override suspend fun create(
            parent: UUID?,
            title: String
        ): List<Pair<UUID, UUID?>> {
            val result = rig.itemsWrite(createOp(title, parent))
            return listOf(result.okId(0) to parent)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) {
            val result =
                rig.itemsWrite(
                    if (newParent ==
                        null
                    ) {
                        updateOp(id, "clear" to strings("parentId"))
                    } else {
                        updateOp(
                            id,
                            "parentId" to JsonPrimitive(newParent.toString())
                        )
                    }
                )
            result.okResult(0)
        }

        override suspend fun delete(id: UUID) {
            val result = rig.itemsWrite(deleteOp(id, recursive = true))
            result.okResult(0)
        }
    }

    private inner class TreeSurface(
        private val rig: EventLogRig
    ) : Surface {
        override val label = "tree"

        override suspend fun create(
            parent: UUID?,
            title: String
        ): List<Pair<UUID, UUID?>> {
            val params = mutableListOf<Pair<String, JsonElement>>()
            params += "root" to
                buildJsonObject {
                    put("type", "test-item")
                    put("title", title)
                }
            if (parent != null) params += "parentId" to JsonPrimitive(parent.toString())
            params +=
                "children" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", "c")
                            put("type", "test-item")
                            put("title", "$title child")
                        }
                    )
                }
            val result = rig.itemsWrite(createTreeOp(*params.toTypedArray())).okResult(0)
            val root = result.treeItemId(0)
            val child = result.treeItemId(1)
            return listOf(root to parent, child to root)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) = error("create_tree does not reparent")

        override suspend fun delete(id: UUID) = error("create_tree does not delete")
    }

    /** The test's own model of the forest: item id to parent id. Expected placement is recomputed from it. */
    private class Model {
        val parents = linkedMapOf<UUID, UUID?>()

        fun depthOf(id: UUID): Int {
            var depth = 0
            var cursor = parents.getValue(id)
            while (cursor != null) {
                depth++
                cursor = parents.getValue(cursor)
            }
            return depth
        }

        fun rootOf(id: UUID): UUID {
            var cursor = id
            while (true) cursor = parents.getValue(cursor) ?: return cursor
        }

        fun subtreeOf(id: UUID): Set<UUID> {
            val out = linkedSetOf(id)
            var grew = true
            while (grew) {
                grew = false
                for ((child, parent) in parents) if (parent in out && out.add(child)) grew = true
            }
            return out
        }
    }

    private suspend fun assertInvariant(
        rig: EventLogRig,
        model: Model,
        step: String
    ) {
        val repo = rig.raw.workItemRepository()
        for ((id, parent) in model.parents) {
            val row = repo.getById(id) ?: error("$step: item $id missing from the store")
            assertEquals(parent, row.parentId, "$step: parent of $id")
            assertEquals(model.depthOf(id), row.depth, "$step: depth of $id")
            assertEquals(model.rootOf(id), row.rootId, "$step: rootId of $id (a root's rootId is its own id)")
            assertEquals(Role.QUEUE, row.role, "$step: every item written through these surfaces is created in queue")
        }
        assertEquals(
            model.parents.size,
            rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items"),
            "$step: the store holds exactly the modelled rows (a delete removes exactly its subtree)"
        )
        val warns = captureLogs { withConn(db.jdbcUrl) { StartupIntegrity.reportPlacementDrift(it) } }.at(Level.WARN)
        assertEquals(emptyList(), warns, "$step: reportPlacementDrift must report nothing")
    }

    private fun randomSequence(
        seed: Long,
        dir: Path
    ): Unit =
        testApplication {
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val rig = EventLogRig.build(db.db, dir)
            val surfaces: List<Surface> = listOf(ServiceSurface(rig), McpSurface(rig))
            val tree = TreeSurface(rig)
            val model = Model()
            val rng = Random(seed)
            var counter = 0

            repeat(45) { n ->
                val ids = model.parents.keys.toList()
                val roll = rng.nextInt(100)
                val surface = surfaces[rng.nextInt(surfaces.size)]
                val step: String
                when {
                    ids.isEmpty() || roll < 40 && ids.size < 24 -> {
                        val parent = if (ids.isEmpty() || rng.nextInt(5) == 0) null else ids[rng.nextInt(ids.size)]
                        val useTree = rng.nextInt(4) == 0
                        val chosen = if (useTree) tree else surface
                        step = "seed=$seed step=$n create via ${chosen.label} under ${parent ?: "root"}"
                        for ((id, p) in chosen.create(parent, "item-${counter++}")) model.parents[id] = p
                    }
                    roll < 80 -> {
                        val x = ids[rng.nextInt(ids.size)]
                        val inside = model.subtreeOf(x)
                        val targets = (ids - inside) + listOf<UUID?>(null)
                        val candidates = targets.filter { it != model.parents[x] }
                        if (candidates.isEmpty()) {
                            step = "seed=$seed step=$n (no reparent target)"
                        } else {
                            val target = candidates[rng.nextInt(candidates.size)]
                            step = "seed=$seed step=$n reparent $x via ${surface.label} to ${target ?: "root"}"
                            surface.reparent(x, target)
                            model.parents[x] = target
                        }
                    }
                    else -> {
                        val x = ids[rng.nextInt(ids.size)]
                        step = "seed=$seed step=$n delete subtree of $x via ${surface.label}"
                        surface.delete(x)
                        model.subtreeOf(x).forEach { model.parents.remove(it) }
                    }
                }
                assertInvariant(rig, model, step)
            }
            assertTrue(model.parents.isNotEmpty(), "fixture: the sequence ends with rows left to check")
        }

    @Test
    fun `S14 random create reparent and delete steps across service items_write and tree keep every row placed (seed 11)`(
        @TempDir dir: Path
    ): Unit = randomSequence(11L, dir)

    @Test
    fun `S14 random create reparent and delete steps across service items_write and tree keep every row placed (seed 202)`(
        @TempDir dir: Path
    ): Unit = randomSequence(202L, dir)

    @Test
    fun `S14 random create reparent and delete steps across service items_write and tree keep every row placed (seed 3033)`(
        @TempDir dir: Path
    ): Unit = randomSequence(3033L, dir)

    @Test
    fun `AR-19 creates racing reparents of their parent always land on the committed placement`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val svc = rig.ctx.itemCommandService
            val r1 = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = null, title = "R1")).orFail()
            val r2 = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = null, title = "R2")).orFail()
            val mid = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = r1.id, title = "mid")).orFail()
            val p = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = mid.id, title = "P")).orFail()
            val seeded = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = p.id, title = "seeded child")).orFail()

            val created =
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val creates =
                            (1..12).map { i ->
                                async {
                                    svc
                                        .create(
                                            ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = p.id, title = "racing child $i")
                                        ).orFail()
                                }
                            }
                        val moves =
                            (1..6).map { i ->
                                async {
                                    val current = rig.raw.workItemRepository().getById(p.id)!!
                                    val target = if (i % 2 == 1) ParentChange.MoveUnder(r2.id) else ParentChange.MoveUnder(mid.id)
                                    svc.patch(patchOf(current, parent = target), caller = null).orFail()
                                }
                            }
                        moves.awaitAll()
                        creates.awaitAll()
                    }
                }

            assertEquals(12, created.size, "every racing create committed")
            val repo = rig.raw.workItemRepository()
            val everyone = created.map { it.id } + listOf(seeded.id, p.id, mid.id, r1.id, r2.id)
            for (id in everyone) {
                val row = repo.getById(id)!!
                val parent = row.parentId?.let { repo.getById(it)!! }
                if (parent == null) {
                    assertEquals(0, row.depth, "root ${row.title} sits at depth 0")
                    assertEquals(row.id, row.rootId, "root ${row.title} is its own root")
                } else {
                    assertEquals(parent.depth + 1, row.depth, "depth of ${row.title} follows its committed parent")
                    assertEquals(parent.rootId, row.rootId, "rootId of ${row.title} follows its committed parent")
                }
            }
            val warns = captureLogs { withConn(db.jdbcUrl) { StartupIntegrity.reportPlacementDrift(it) } }.at(Level.WARN)
            assertEquals(emptyList(), warns, "no placement drift after the race")
        }

    @Test
    fun `control the invariant check and the drift report both fail on a drifted row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val svc = rig.ctx.itemCommandService
            val root = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = null, title = "control root")).orFail()
            val child = svc.create(ItemCreateCommand(type = TEST_ITEM_TYPE, parentId = root.id, title = "control child")).orFail()
            val model = Model()
            model.parents[root.id] = null
            model.parents[child.id] = root.id
            assertInvariant(rig, model, "healthy rows")

            rawExec(db.jdbcUrl, "UPDATE work_items SET depth = 7 WHERE title = 'control child'")
            assertFailsWith<AssertionError>(
                "a drifted depth must fail the invariant check"
            ) { assertInvariant(rig, model, "drifted depth") }
            val warns = captureLogs { withConn(db.jdbcUrl) { StartupIntegrity.reportPlacementDrift(it) } }.at(Level.WARN)
            assertTrue(warns.isNotEmpty(), "a drifted depth must be reported by reportPlacementDrift")

            rawExec(db.jdbcUrl, "UPDATE work_items SET depth = 1, root_id = parent_id WHERE title = 'control child'")
            assertInvariant(rig, model, "restored rows")
            rawExec(db.jdbcUrl, "UPDATE work_items SET root_id = id WHERE title = 'control child'")
            assertFailsWith<AssertionError>("a drifted root must fail the invariant check") { assertInvariant(rig, model, "drifted root") }
        }
}

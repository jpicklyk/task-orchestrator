package io.github.jpicklyk.mcptask.current.architecture

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureWriteTestApp
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * routes and `create_work_tree`; after EVERY step each stored row satisfies the placement invariant and
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
                    .create(ItemCreateCommand(parentId = parent, title = title))
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
                .patch(patchOf(current, parent = change))
                .orFail()
        }

        override suspend fun delete(id: UUID) {
            rig.ctx.itemCommandService
                .delete(id, recursive = true)
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
            val result =
                rig.callOk(
                    ManageItemsTool(),
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("title", title)
                                    if (parent != null) put("parentId", parent.toString())
                                }
                            )
                        }
                )
            val data = result["data"]!!.jsonObject
            assertEquals(1, data["created"]!!.jsonPrimitive.int, "mcp create must create the item: $result")
            val id =
                UUID.fromString(
                    data["items"]!!
                        .jsonArray[0]
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                )
            return listOf(id to parent)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) {
            val result =
                rig.callOk(
                    ManageItemsTool(),
                    "operation" to JsonPrimitive("update"),
                    "items" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", id.toString())
                                    if (newParent == null) put("parentId", JsonNull) else put("parentId", newParent.toString())
                                }
                            )
                        }
                )
            assertEquals(1, result["data"]!!.jsonObject["updated"]!!.jsonPrimitive.int, "mcp reparent must apply: $result")
        }

        override suspend fun delete(id: UUID) {
            val result =
                rig.callOk(
                    ManageItemsTool(),
                    "operation" to JsonPrimitive("delete"),
                    "itemIds" to buildJsonArray { add(JsonPrimitive(id.toString())) },
                    "recursive" to JsonPrimitive(true)
                )
            assertEquals(0, result["data"]!!.jsonObject["failed"]!!.jsonPrimitive.int, "mcp delete must apply: $result")
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
            params += "root" to buildJsonObject { put("title", title) }
            if (parent != null) params += "parentId" to JsonPrimitive(parent.toString())
            params +=
                "children" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", "c")
                            put("title", "$title child")
                        }
                    )
                }
            val result = rig.callOk(CreateWorkTreeTool(), *params.toTypedArray())
            val data = result["data"]!!.jsonObject
            val root = UUID.fromString(data["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val child =
                UUID.fromString(
                    data["children"]!!
                        .jsonArray[0]
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                )
            return listOf(root to parent, child to root)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) = error("create_work_tree does not reparent")

        override suspend fun delete(id: UUID) = error("create_work_tree does not delete")
    }

    private inner class RestSurface(
        private val rig: EventLogRig,
        private val client: HttpClient
    ) : Surface {
        override val label = "rest"

        override suspend fun create(
            parent: UUID?,
            title: String
        ): List<Pair<UUID, UUID?>> {
            val body = if (parent == null) """{"title":"$title"}""" else """{"title":"$title","parentId":"$parent"}"""
            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.Created, response.status, "rest create must apply: $text")
            val id = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""").find(text)!!.groupValues[1]
            return listOf(UUID.fromString(id) to parent)
        }

        override suspend fun reparent(
            id: UUID,
            newParent: UUID?
        ) {
            val current = rig.raw.workItemRepository().getById(id)!!
            val response =
                client.patch("/api/v1/items/$id") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-${current.modifiedAt.toEpochMilli()}\"")
                    contentType(ContentType.Application.Json)
                    setBody(if (newParent == null) """{"parentId":null}""" else """{"parentId":"$newParent"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, "rest reparent must apply: ${response.bodyAsText()}")
        }

        override suspend fun delete(id: UUID) {
            val response =
                client.delete("/api/v1/items/$id?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertTrue(response.status.value in 200..299, "rest delete must apply: ${response.status} ${response.bodyAsText()}")
        }
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
            val surfaces: List<Surface> = listOf(ServiceSurface(rig), McpSurface(rig), RestSurface(rig, client))
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
    fun `S14 random create reparent and delete steps across service mcp rest and tree keep every row placed (seed 11)`(
        @TempDir dir: Path
    ): Unit = randomSequence(11L, dir)

    @Test
    fun `S14 random create reparent and delete steps across service mcp rest and tree keep every row placed (seed 202)`(
        @TempDir dir: Path
    ): Unit = randomSequence(202L, dir)

    @Test
    fun `S14 random create reparent and delete steps across service mcp rest and tree keep every row placed (seed 3033)`(
        @TempDir dir: Path
    ): Unit = randomSequence(3033L, dir)

    @Test
    fun `AR-19 creates racing reparents of their parent always land on the committed placement`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val svc = rig.ctx.itemCommandService
            val r1 = svc.create(ItemCreateCommand(parentId = null, title = "R1")).orFail()
            val r2 = svc.create(ItemCreateCommand(parentId = null, title = "R2")).orFail()
            val mid = svc.create(ItemCreateCommand(parentId = r1.id, title = "mid")).orFail()
            val p = svc.create(ItemCreateCommand(parentId = mid.id, title = "P")).orFail()
            val seeded = svc.create(ItemCreateCommand(parentId = p.id, title = "seeded child")).orFail()

            val created =
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val creates =
                            (1..12).map { i ->
                                async { svc.create(ItemCreateCommand(parentId = p.id, title = "racing child $i")).orFail() }
                            }
                        val moves =
                            (1..6).map { i ->
                                async {
                                    val current = rig.raw.workItemRepository().getById(p.id)!!
                                    val target = if (i % 2 == 1) ParentChange.MoveUnder(r2.id) else ParentChange.MoveUnder(mid.id)
                                    svc.patch(patchOf(current, parent = target)).orFail()
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
            val root = svc.create(ItemCreateCommand(parentId = null, title = "control root")).orFail()
            val child = svc.create(ItemCreateCommand(parentId = root.id, title = "control child")).orFail()
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

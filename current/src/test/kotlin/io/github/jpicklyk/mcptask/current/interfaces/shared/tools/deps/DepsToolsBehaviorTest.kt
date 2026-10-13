package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.DepsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 54f3a1ce, Q12) for the parts of `deps_read` / `deps_write` the parity harness cannot
 * express: event attribution (S23), idempotent replay (S24), cursor paging (S25), the graph visit cap (S26), and the removal of
 * the 3.x surface (S28), plus the registration shape declared by the item.
 *
 * Oracles: S23 task-scope item 3 and the ToolDispatcher order block (a verified access principal replaces the body actor; with no
 * access seam the body actor is lifted); S24 envelope idempotency (same key and payload replays the stored element result,
 * flagged `replayed`, executing once; same key and another payload is `idempotency_mismatch`); S25 envelope paging (`truncated`
 * with `next`, a cursor returns the rest, an exact `total`, a bad cursor is `invalid_cursor` 400); S26 task-scope graph (visit cap
 * 1000 sets `page.capped`, `total` is then a lower bound; below the cap no `capped` key); S28 plan section 4.4 (the 3.x tools and
 * routes are gone).
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DepsToolsBehaviorTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val registry = ToolRegistry(buildToolSpecs())

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun McpResult.data(): JsonObject = structured.getValue("data").jsonObject

    private fun McpResult.results(): List<JsonObject> = data().getValue("results").jsonArray.map { it.jsonObject }

    private fun McpResult.items(): List<JsonObject> = data().getValue("items").jsonArray.map { it.jsonObject }

    private fun McpResult.page(): JsonObject = data().getValue("page").jsonObject

    private fun McpResult.errorCode(): String =
        structured
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private suspend fun write(
        rig: EventLogRig,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, null, "deps_write", obj(body))

    private suspend fun read(
        rig: EventLogRig,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, null, "deps_read", obj(body))

    private fun createOp(
        from: UUID,
        to: UUID,
        extra: String = ""
    ) = """{"op":"create","from":"$from","to":"$to"$extra}"""

    private val agent = """"actor":{"id":"agent-1","kind":"subagent""""

    // ------------------------------------------------------------------ registration shape (declarations)

    @Test
    fun `deps_read and deps_write are registered with the declared members, capabilities and error sets`() {
        val names = registry.tools.map { it.name }
        assertTrue(names.indexOf("deps_read") >= 0 && names.indexOf("deps_write") >= 0, "both tools registered: $names")
        assertTrue(names.indexOf("deps_read") < names.indexOf("deps_write"), "registration order read then write")

        val read = registry["deps_read"] as SelectToolSpec
        assertEquals(listOf("list", "graph"), read.selects.map { it.name })
        assertTrue(read.selects.all { it.capability == ApiCapability.READ }, "reads need READ")

        val write = registry["deps_write"] as OpsToolSpec<*>
        assertEquals(listOf("create", "delete", "linear", "fan_out", "fan_in"), write.ops.map { it.name })
        assertTrue(write.ops.all { it.capability == ApiCapability.MANAGE_DEPENDENCIES }, "every write op needs MANAGE_DEPENDENCIES")
        assertEquals(listOf("delete"), write.ops.filter { it.destructive }.map { it.name }, "only delete is destructive")

        val declared = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN)
        assertEquals(declared, read.selects.single { it.name == "list" }.errors)
        assertEquals(declared, read.selects.single { it.name == "graph" }.errors)
        assertEquals(declared, write.ops.single { it.name == "delete" }.errors)
    }

    // ------------------------------------------------------------------ S23 attribution

    @Test
    fun `S23 a write on the default path records the lifted actor as the principal of dependency added and removed`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val b = rig.seed("b", root)

            val created = write(rig, """{$agent},"ops":[${createOp(a.id, b.id)}]}""")
            assertFalse(created.isError, created.text)
            val added = rig.rows().single { it.type == "dependency.added" }
            assertEquals("agent-1", added.principalId)

            val deleted = write(rig, """{$agent},"ops":[{"op":"delete","from":"${a.id}","to":"${b.id}"}]}""")
            assertFalse(deleted.isError, deleted.text)
            val removed = rig.rows().single { it.type == "dependency.removed" }
            assertEquals("agent-1", removed.principalId)
        }

    @Test
    fun `S23 control - a write without any actor leaves the principal null`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val b = rig.seed("b", root)

            val created = write(rig, """{"ops":[${createOp(a.id, b.id)}]}""")
            assertFalse(created.isError, created.text)
            assertNull(rig.rows().single { it.type == "dependency.added" }.principalId, "no actor, no principal")
        }

    @Test
    fun `S23 a verified access principal replaces the body actor on the event row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val b = rig.seed("b", root)
            val access = parityAccess(DepsParityScenarios.admin)
            val expected = assertNotNull(access.principal).claim.id

            val result =
                callMcp(
                    rig.ctx,
                    registry,
                    { access },
                    "deps_write",
                    obj("""{"actor":{"id":"intruder","kind":"subagent"},"ops":[${createOp(a.id, b.id)}]}""")
                )
            assertFalse(result.isError, result.text)
            val principal = rig.rows().single { it.type == "dependency.added" }.principalId
            assertEquals(expected, principal)
            assertNotEquals("intruder", principal)
        }

    // ------------------------------------------------------------------ S24 idempotency

    @Test
    fun `S24 the same idempotency key and payload is replayed and stores one row and one event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val b = rig.seed("b", root)
            val body = """{$agent},"ops":[${createOp(a.id, b.id, ""","idempotencyKey":"k-1"""")}]}"""

            val first = write(rig, body)
            assertFalse(first.isError, first.text)
            assertNull(first.results().single()["replayed"], "the first call executes")

            val second = write(rig, body)
            assertFalse(second.isError, second.text)
            assertEquals(
                "true",
                second
                    .results()
                    .single()
                    .getValue("replayed")
                    .jsonPrimitive.content
            )
            assertEquals(
                first.results().single().getValue("id"),
                second.results().single().getValue("id"),
                "the replay returns the stored result"
            )

            assertEquals(
                1,
                rig.raw
                    .dependencyRepository()
                    .findByItemId(a.id)
                    .size,
                "one stored row"
            )
            assertEquals(1, rig.rows().count { it.type == "dependency.added" }, "one event")
        }

    @Test
    fun `S24 the same key with another payload is idempotency_mismatch and stores nothing more`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val b = rig.seed("b", root)
            val c = rig.seed("c", root)
            write(rig, """{$agent},"ops":[${createOp(a.id, b.id, ""","idempotencyKey":"k-1"""")}]}""")

            val other = write(rig, """{$agent},"ops":[${createOp(a.id, c.id, ""","idempotencyKey":"k-1"""")}]}""")
            val element = other.results().single()
            assertEquals(
                "idempotency_mismatch",
                element
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            )
            assertEquals(
                1,
                rig.raw
                    .dependencyRepository()
                    .findByItemId(a.id)
                    .size,
                "still only the first row"
            )
        }

    // ------------------------------------------------------------------ S25 paging

    private fun McpResult.total(): Int = page().getValue("total").jsonPrimitive.int

    private fun McpResult.truncated(): Boolean = page().getValue("truncated").jsonPrimitive.boolean

    @Test
    fun `S25 list of three edges with limit 2 is truncated with a cursor, and the cursor returns the rest`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val a = rig.seed("a", root)
            val others = listOf(rig.seed("b", root), rig.seed("c", root), rig.seed("d", root))
            val store = rig.raw.dependencyRepository()
            val stored = others.map { store.create(Dependency(fromItemId = a.id, toItemId = it.id)) }

            val first = read(rig, """{"select":"list","item":"${a.id}","limit":2}""")
            assertFalse(first.isError, first.text)
            assertEquals(2, first.items().size, "first page: ${first.structured}")
            assertEquals(3, first.total(), "first page: ${first.structured}")
            assertTrue(first.truncated(), "first page: ${first.structured}")
            val next =
                first
                    .page()
                    .getValue("next")
                    .jsonPrimitive.content

            val second = read(rig, """{"select":"list","item":"${a.id}","limit":2,"cursor":"$next"}""")
            assertFalse(second.isError, second.text)
            assertEquals(1, second.items().size, "second page: ${second.structured}")
            assertEquals(3, second.total(), "total is exact on every page: ${second.structured}")
            // envelope section 2 (amendment U22): truncated is offset + items.length < total and next is present exactly when
            // truncated, on every page; this last page (offset 2, one item, total 3) is not truncated
            assertFalse(second.truncated(), "second page: ${second.structured}")
            assertEquals(second.truncated(), second.page()["next"] != null, "second page: ${second.structured}")

            val seen = (first.items() + second.items()).map { it.getValue("id").jsonPrimitive.content }
            assertEquals(stored.map { it.id.toString() }.toSet(), seen.toSet(), "the two pages cover every edge")
            assertEquals(3, seen.size, "and no edge twice")
        }

    @Test
    fun `S25 an undecodable cursor is invalid_cursor`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val a = rig.seed("a")
            val result = read(rig, """{"select":"list","item":"${a.id}","cursor":"%%%"}""")
            assertTrue(result.isError)
            assertEquals("invalid_cursor", result.errorCode())
        }

    // ------------------------------------------------------------------ S26 graph cap

    @Test
    fun `S26 a graph over more nodes than the visit cap is capped with a lower-bound total`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val nodes = (0 until 1100).map { rig.seed("n$it", root) }
            val store = rig.raw.dependencyRepository()
            nodes
                .zipWithNext { from, to -> Dependency(fromItemId = from.id, toItemId = to.id) }
                .chunked(300)
                .forEach { store.createBatch(it) }

            val result = read(rig, """{"select":"graph","item":"${nodes.first().id}"}""")
            assertFalse(result.isError, result.text)
            val page = result.page()
            assertTrue(page.getValue("capped").jsonPrimitive.boolean, "the visit cap was hit: $page")
            assertTrue(page.getValue("total").jsonPrimitive.int >= 1000, "total is a lower bound of at least the cap: $page")
            assertTrue(page.getValue("truncated").jsonPrimitive.boolean, "50 of at least 1000 nodes fit one page")
        }

    @Test
    fun `S26 control - a graph below the visit cap has no capped flag`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val nodes = (0 until 5).map { rig.seed("n$it", root) }
            val store = rig.raw.dependencyRepository()
            store.createBatch(nodes.zipWithNext { from, to -> Dependency(fromItemId = from.id, toItemId = to.id) })

            val result = read(rig, """{"select":"graph","item":"${nodes.first().id}"}""")
            assertFalse(result.isError, result.text)
            assertEquals(
                5,
                result
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int
            )
            assertNull(result.page()["capped"])
        }

    // ------------------------------------------------------------------ S28 removal of the 3.x surface

    @Test
    fun `S28 the 3_x dependency tools are not registered anywhere`() {
        val registered = registry.tools.map { it.name }
        assertFalse("manage_dependencies" in registered)
        assertFalse("query_dependencies" in registered)
        val legacy = ToolRegistry(buildToolSpecs()).tools.map { it.name }
        assertFalse("manage_dependencies" in legacy, "legacy tool list: $legacy")
        assertFalse("query_dependencies" in legacy, "legacy tool list: $legacy")
    }

    @Test
    fun `S28 the 3_x dependency REST routes are gone while the ops endpoint serves the new tools`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val a = rig.seed("a")
            val admin = DepsParityScenarios.admin
            testApplication {
                application { installParityRestApp(rig, parityAuthConfig(admin), registry) }

                fun io.ktor.client.request.HttpRequestBuilder.auth() {
                    header(HttpHeaders.Authorization, "Bearer ${admin.raw}")
                }
                val control =
                    client.post("/api/v1/ops/deps_read") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"select":"list","item":"${a.id}"}""")
                    }
                assertEquals(200, control.status.value, "control: the ops endpoint serves deps_read")
                assertEquals(404, client.get("/api/v1/items/${a.id}/dependencies") { auth() }.status.value)
                assertEquals(404, client.get("/api/v1/items/${a.id}/backlinks") { auth() }.status.value)
                assertEquals(
                    404,
                    client
                        .post("/api/v1/dependencies") {
                            auth()
                            contentType(ContentType.Application.Json)
                            setBody("""{"fromItemId":"${a.id}","toItemId":"${UUID.randomUUID()}"}""")
                        }.status.value
                )
                assertEquals(404, client.delete("/api/v1/dependencies/${UUID.randomUUID()}") { auth() }.status.value)
            }
        }
}

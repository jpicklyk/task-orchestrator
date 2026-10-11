package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `items_write` slice of [ProductionParity] (item 5462835d): one HAPPY and one SCOPE_FORBIDDEN scenario per op (create,
 * update, delete) plus OTHER scenarios for failures whose status and shape the envelope spec fixes. The expectations are literals
 * from the envelope spec: a failed element of a non-atomic batch is a 207 `partial_failure` whose element carries the error; a
 * forbidden element carries `{scope, required}`.
 */
internal object ItemsWriteParity {
    val r1: UUID = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    val c1: UUID = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    val r2: UUID = UUID.fromString("b2000001-0000-4000-8000-000000000002")

    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
    val writer = ParityToken("iw-parity-writer", caps)
    val rootScoped = ParityToken("iw-parity-root", caps, rootIds = setOf(r1))
    val tagScoped = ParityToken("iw-parity-tag", caps, tagsInclude = setOf("alpha"))
    val readOnly = ParityToken("iw-parity-read", setOf(ApiCapability.READ))

    /** R1 (alpha) > C1 (alpha,shared); R2 (beta). */
    val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(c1, "Alpha child", parent = r1, tags = "alpha,shared")
        item(r2, "Beta root", tags = "beta")
    }

    private fun update(
        id: UUID,
        title: String
    ) = """{"op":"update","id":"$id","title":"$title"}"""

    private fun delete(id: UUID) = """{"op":"delete","id":"$id"}"""

    private fun create(
        title: String,
        extra: String = ""
    ) = """{"op":"create","title":"$title","type":"test-item"$extra}"""

    private fun ops(vararg elements: String) = """{"ops":[${elements.joinToString(",")}]}"""

    private fun scenario(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList(),
        expectDbChanged: Boolean? = null
    ) = ParityScenario(
        name,
        "items_write",
        member,
        kind,
        token,
        arguments,
        expectStatus,
        expectCode,
        expectContains,
        expectAbsent,
        expectDbChanged,
        seedTree
    )

    private val forbiddenRoot = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"")

    /** The create HAPPY scenario: its body and rows carry a generated id, so [ItemsWriteParityTest] compares it with ids normalized. */
    val createHappy =
        scenario(
            "items_write create happy",
            "create",
            ParityKind.HAPPY,
            writer,
            ops(create("Created", ""","tags":["alpha"]""")),
            200,
            expectContains = listOf("\"ok\":true", "\"summary\":{\"ok\":1,\"failed\":0}"),
            expectDbChanged = true
        )

    val updateHappy =
        scenario(
            "items_write update happy",
            "update",
            ParityKind.HAPPY,
            writer,
            ops(update(c1, "Renamed child")),
            200,
            expectContains = listOf("\"id\":\"$c1\""),
            expectDbChanged = true
        )

    val deleteHappy =
        scenario(
            "items_write delete happy",
            "delete",
            ParityKind.HAPPY,
            writer,
            ops(delete(c1)),
            200,
            expectContains = listOf("\"id\":\"$c1\"", "\"deleted\":1"),
            expectDbChanged = true
        )

    /** Scenarios whose writes carry generated ids or transport-dependent event hosts; run by the test's own runner. */
    val writing: List<ParityScenario> = listOf(createHappy, updateHappy, deleteHappy)

    /** Scenarios that leave the database unchanged: the shared harness compares them as is. */
    val nonWriting: List<ParityScenario> =
        listOf(
            scenario(
                "items_write create scope-forbidden root create under a root scope",
                "create",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(create("NewRoot")),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write update scope-forbidden out-of-root item",
                "update",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(update(r2, "hacked")),
                207,
                "partial_failure",
                forbiddenRoot,
                listOf("hacked"),
                expectDbChanged = false
            ),
            scenario(
                "items_write delete scope-forbidden out-of-root item",
                "delete",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(delete(r2)),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write update scope-forbidden reparent onto out-of-scope parent",
                "update",
                ParityKind.OTHER,
                rootScoped,
                ops("""{"op":"update","id":"$c1","parentId":"$r2"}"""),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write create scope-forbidden under out-of-scope parent",
                "create",
                ParityKind.OTHER,
                rootScoped,
                ops(create("Under", ""","parentId":"$r2"""")),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write update scope-forbidden by tag",
                "update",
                ParityKind.OTHER,
                tagScoped,
                ops(update(r2, "hacked")),
                207,
                "partial_failure",
                listOf("\"code\":\"forbidden\"", "\"scope\":\"tag\"", "\"required\":\"alpha\""),
                listOf("hacked"),
                expectDbChanged = false
            ),
            scenario(
                "items_write create scope-forbidden tag-scoped root create without a listed tag",
                "create",
                ParityKind.OTHER,
                tagScoped,
                ops(create("NoTag")),
                207,
                "partial_failure",
                listOf("\"scope\":\"tag\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write capability forbidden",
                "update",
                ParityKind.OTHER,
                readOnly,
                ops(update(c1, "x")),
                207,
                "partial_failure",
                listOf("\"scope\":\"capability\"", "\"required\":\"write-items\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write atomic forbidden element fails the call with its index",
                "update",
                ParityKind.OTHER,
                rootScoped,
                """{"ops":[${update(c1, "inside")},${update(r2, "outside")}],"atomic":true}""",
                403,
                "forbidden",
                listOf("\"code\":\"forbidden\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write not_found",
                "delete",
                ParityKind.OTHER,
                writer,
                ops(delete(UUID.fromString("c3000000-0000-4000-8000-0000000000ee"))),
                207,
                "partial_failure",
                listOf("\"code\":\"not_found\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write delete without recursive",
                "delete",
                ParityKind.OTHER,
                writer,
                ops(delete(r1)),
                207,
                "partial_failure",
                listOf("\"code\":\"invalid_request\"", "\"field\":\"recursive\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write stale expectedVersion",
                "update",
                ParityKind.OTHER,
                writer,
                ops("""{"op":"update","id":"$c1","title":"x","expectedVersion":99}"""),
                207,
                "partial_failure",
                listOf("\"code\":\"version_conflict\"", "\"kind\":\"item\""),
                expectDbChanged = false
            )
        )

    val scenarios: List<ParityScenario> = writing + nonWriting
}

@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteParityTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val harness = ParityHarness(registry)

    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}")
    private val reqId = Regex("\"reqId\":\"[^\"]*\"")

    private fun norm(text: String) = uuid.replace(reqId.replace(text, "\"reqId\":\"<REQ>\""), "<UUID>")

    private fun normSnapshot(snapshot: Map<String, List<Map<String, Any?>>>) =
        snapshot.mapValues { (table, rows) ->
            val normalized = rows.map { norm(it.toString()) }
            if (table == "events") normalized else normalized.sorted()
        }

    @Test
    fun `S6 every non-writing items_write scenario agrees across MCP and REST in body, status and database`() {
        for (s in ItemsWriteParity.nonWriting) harness.run(s)
    }

    @Test
    fun `S6 control - the catalog holds a happy and a scope-forbidden scenario for each op`() {
        val byMember = ItemsWriteParity.scenarios.groupBy { it.member }
        assertEquals(setOf("create", "update", "delete"), byMember.keys)
        for ((member, list) in byMember) {
            assertTrue(list.any { it.kind == ParityKind.HAPPY }, "$member has a HAPPY scenario")
            assertTrue(
                list.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$member has a restricted-scope SCOPE_FORBIDDEN scenario"
            )
        }
    }

    @Test
    fun `S6 create happy agrees across MCP and REST once generated ids are normalized`() = runWriting(ItemsWriteParity.createHappy)

    @Test
    fun `S6 update happy agrees across MCP and REST`() = runWriting(ItemsWriteParity.updateHappy)

    @Test
    fun `S6 delete happy agrees across MCP and REST`() = runWriting(ItemsWriteParity.deleteHappy)

    /**
     * The shared harness cannot compare a write that emits event rows: the event `host` is the MCP client name on one leg and the
     * HTTP User-Agent on the other, and a create's id is generated per leg. This runs the same two legs by hand with the REST
     * User-Agent set to the MCP client name and every UUID replaced by a marker, then asserts the scenario's literal expectations.
     */
    private fun runWriting(s: ParityScenario) {
        val restDb = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val mcpDb = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val restDir = Files.createTempDirectory("iw-parity-rest-")
        val mcpDir = Files.createTempDirectory("iw-parity-mcp-")
        try {
            val restRig = EventLogRig.build(restDb, restDir, clock = PARITY_CLOCK)
            val mcpRig = EventLogRig.build(mcpDb, mcpDir, clock = PARITY_CLOCK)
            runBlocking {
                s.seed(ParitySeeder(restDb.repositoryProvider().workItemRepository()))
                s.seed(ParitySeeder(mcpDb.repositoryProvider().workItemRepository()))
            }
            val before = ParityHarness.snapshot(restDb.jdbcUrl)
            assertEquals(before, ParityHarness.snapshot(mcpDb.jdbcUrl), "${s.name}: identical seeds")

            var restStatus = 0
            var restBody = ""
            testApplication {
                application { installParityRestApp(restRig, parityAuthConfig(s.token), registry) }
                val response =
                    client.post("/api/v1/ops/items_write") {
                        header(HttpHeaders.Authorization, "Bearer ${s.token.raw}")
                        header(HttpHeaders.UserAgent, "parity-client")
                        contentType(ContentType.Application.Json)
                        setBody(s.arguments)
                    }
                restStatus = response.status.value
                restBody = response.bodyAsText()
            }
            val mcp =
                runBlocking {
                    callMcp(mcpRig.ctx, registry, { parityAccess(s.token) }, "items_write", Json.parseToJsonElement(s.arguments).jsonObject)
                }

            assertEquals(s.expectStatus, restStatus, "${s.name}: REST status")
            assertFalse(mcp.isError, "${s.name}: ${mcp.text}")
            assertEquals(norm(mcp.structured.toString()), norm(restBody), "${s.name}: body parity with ids and reqId normalized")
            for (needle in s.expectContains) assertTrue(needle in restBody, "${s.name}: body must contain $needle but was $restBody")
            val restAfter = ParityHarness.snapshot(restDb.jdbcUrl)
            val mcpAfter = ParityHarness.snapshot(mcpDb.jdbcUrl)
            assertNotEquals(before, restAfter, "${s.name}: control - the REST leg wrote")
            assertNotEquals(before, mcpAfter, "${s.name}: control - the MCP leg wrote")
            assertEquals(normSnapshot(restAfter), normSnapshot(mcpAfter), "${s.name}: database parity with generated ids normalized")
        } finally {
            runCatching { restDb.close() }
            runCatching { mcpDb.close() }
            runCatching { restDir.toFile().deleteRecursively() }
            runCatching { mcpDir.toFile().deleteRecursively() }
        }
    }
}

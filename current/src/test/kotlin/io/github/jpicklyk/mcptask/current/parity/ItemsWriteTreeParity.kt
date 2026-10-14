package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
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
 * The `create_tree` and `complete_tree` slice of [ProductionParity] (item c820b3d0): one HAPPY and one SCOPE_FORBIDDEN scenario
 * per op plus OTHER scenarios for failures whose status and shape the envelope spec fixes. The expectations are literals from the
 * envelope spec and the task-scope: a failed element of a non-atomic batch is a 207 `partial_failure` whose element carries the
 * error; a forbidden element carries `{scope, required}`; an atomic batch fails the call with the failing element's code.
 */
internal object ItemsWriteTreeParity {
    val r1: UUID = UUID.fromString("e1000001-0000-4000-8000-000000000001")
    val c1: UUID = UUID.fromString("e1000002-0000-4000-8000-0000000000c1")
    val c2: UUID = UUID.fromString("e1000003-0000-4000-8000-0000000000c2")
    val r2: UUID = UUID.fromString("f2000001-0000-4000-8000-000000000002")
    private val edge: UUID = UUID.fromString("e1000004-0000-4000-8000-0000000000d1")

    private val allCaps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS, ApiCapability.ADVANCE)
    val writer = ParityToken("tree-parity-writer", allCaps)
    val rootScoped = ParityToken("tree-parity-root", allCaps, rootIds = setOf(r1))
    val tagScoped = ParityToken("tree-parity-tag", allCaps, tagsInclude = setOf("alpha"))
    val noAdvance = ParityToken("tree-parity-no-advance", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS))
    val readOnly = ParityToken("tree-parity-read", setOf(ApiCapability.READ))

    /** R1 (alpha) > C1, C2 (alpha) with C1 blocks C2; R2 (beta). */
    val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Tree alpha root", tags = "alpha")
        item(c1, "Tree alpha one", parent = r1, tags = "alpha")
        item(c2, "Tree alpha two", parent = r1, tags = "alpha")
        dependency(edge, c1, c2)
        item(r2, "Tree beta root", tags = "beta")
    }

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun tree(
        rootExtra: String = "",
        parentId: UUID? = null,
        children: String =
            """[{"ref":"a","title":"Child A","type":"test-item"},{"ref":"b","title":"Child B","type":"test-item","parentRef":"a"}]""",
        deps: String = """[{"from":"a","to":"b"}]""",
        notes: String = """[{"itemRef":"a","key":"k1","role":"work","body":"hello"}]"""
    ) = """{"op":"create_tree","root":{"title":"Tree root","type":"test-item"$rootExtra},""" +
        (if (parentId != null) """"parentId":"$parentId",""" else "") +
        """"children":$children,"deps":$deps,"notes":$notes}"""

    private fun complete(fields: String) = """{"op":"complete_tree",$fields}"""

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

    private val forbiddenRoot = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\"")

    /** HAPPY create_tree: generated ids and event hosts differ per leg, so [ItemsWriteTreeParityTest] runs it with ids normalized. */
    val createTreeHappy =
        scenario(
            "items_write create_tree happy",
            "create_tree",
            ParityKind.HAPPY,
            writer,
            ops(tree()),
            200,
            expectContains = listOf("\"ok\":true", "\"summary\":{\"ok\":1,\"failed\":0}", "\"fromRef\":\"a\"", "\"toRef\":\"b\""),
            expectDbChanged = true
        )

    /** HAPPY complete_tree: writes transition and event rows, run by the test's own runner like the create happy. */
    val completeTreeHappy =
        scenario(
            "items_write complete_tree happy",
            "complete_tree",
            ParityKind.HAPPY,
            writer,
            ops(complete(""""itemIds":["$c1","$c2"]""")),
            200,
            expectContains = listOf("\"ok\":true", "\"outcome\":\"applied\"", "\"trigger\":\"complete\""),
            expectDbChanged = true
        )

    val writing: List<ParityScenario> = listOf(createTreeHappy, completeTreeHappy)

    /** Scenarios that leave the database unchanged: the shared harness compares them as is. */
    val nonWriting: List<ParityScenario> =
        listOf(
            scenario(
                "items_write create_tree scope-forbidden under an out-of-scope parent",
                "create_tree",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(tree(parentId = r2)),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree scope-forbidden top-level root under a root scope",
                "create_tree",
                ParityKind.OTHER,
                rootScoped,
                ops(tree()),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree scope-forbidden attach to an out-of-scope item",
                "create_tree",
                ParityKind.OTHER,
                rootScoped,
                ops("""{"op":"create_tree","root":{"id":"$r2"},"children":[{"ref":"a","title":"Child A","type":"test-item"}]}"""),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree scope-forbidden tag-scoped root create (R15: forbidden root, even with a tag)",
                "create_tree",
                ParityKind.OTHER,
                tagScoped,
                ops(tree()),
                207,
                "partial_failure",
                listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree capability forbidden",
                "create_tree",
                ParityKind.OTHER,
                readOnly,
                ops(tree()),
                207,
                "partial_failure",
                listOf("\"scope\":\"capability\"", "\"required\":\"write-items\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree unknown parentRef",
                "create_tree",
                ParityKind.OTHER,
                writer,
                ops(
                    tree(
                        children =
                            """[{"ref":"a","title":"A","type":"test-item"},""" +
                                """{"ref":"b","title":"B","type":"test-item","parentRef":"x"}]""",
                        deps = "[]",
                        notes = "[]"
                    )
                ),
                207,
                "partial_failure",
                listOf("\"code\":\"invalid_request\"", "/children/1/parentRef"),
                expectDbChanged = false
            ),
            scenario(
                "items_write create_tree atomic dependency cycle fails the call with its index",
                "create_tree",
                ParityKind.OTHER,
                writer,
                ops(
                    tree(deps = """[{"from":"a","to":"b"},{"from":"b","to":"a"}]""", notes = "[]"),
                    top = ""","atomic":true"""
                ),
                409,
                "cycle_detected",
                listOf("\"code\":\"cycle_detected\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree scope-forbidden rootId outside the root scope",
                "complete_tree",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                ops(complete(""""rootId":"$r2"""")),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree scope-forbidden itemIds with one foreign entry",
                "complete_tree",
                ParityKind.OTHER,
                rootScoped,
                ops(complete(""""itemIds":["$c1","$r2"]""")),
                207,
                "partial_failure",
                forbiddenRoot,
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree scope-forbidden by tag",
                "complete_tree",
                ParityKind.OTHER,
                tagScoped,
                ops(complete(""""itemIds":["$c1","$r2"]""")),
                207,
                "partial_failure",
                listOf("\"scope\":\"tag\"", "\"required\":\"alpha\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree capability forbidden without advance",
                "complete_tree",
                ParityKind.OTHER,
                noAdvance,
                ops(complete(""""itemIds":["$c1"]""")),
                207,
                "partial_failure",
                listOf("\"scope\":\"capability\"", "\"required\":\"advance\""),
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree both rootId and itemIds",
                "complete_tree",
                ParityKind.OTHER,
                writer,
                ops(complete(""""rootId":"$r1","itemIds":["$c1"]""")),
                207,
                "partial_failure",
                listOf("\"code\":\"invalid_request\"", "/rootId"),
                expectDbChanged = false
            ),
            scenario(
                "items_write complete_tree unknown rootId",
                "complete_tree",
                ParityKind.OTHER,
                writer,
                ops(complete(""""rootId":"c3000000-0000-4000-8000-0000000000ee"""")),
                207,
                "partial_failure",
                listOf("\"code\":\"not_found\""),
                expectDbChanged = false
            )
        )

    val scenarios: List<ParityScenario> = writing + nonWriting
}

@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteTreeParityTest {
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
    fun `S7 every non-writing tree scenario agrees across MCP and REST in body, status and database`() {
        for (s in ItemsWriteTreeParity.nonWriting) harness.run(s)
    }

    @Test
    fun `S7 control - the catalog holds a happy and a restricted-scope forbidden scenario for create_tree and complete_tree`() {
        val byMember = ItemsWriteTreeParity.scenarios.groupBy { it.member }
        assertEquals(setOf("create_tree", "complete_tree"), byMember.keys)
        for ((member, list) in byMember) {
            assertTrue(list.any { it.kind == ParityKind.HAPPY }, "$member has a HAPPY scenario")
            assertTrue(
                list.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$member has a restricted-scope SCOPE_FORBIDDEN scenario"
            )
        }
    }

    @Test
    fun `S7 create_tree happy agrees across MCP and REST once generated ids are normalized`() =
        runWriting(ItemsWriteTreeParity.createTreeHappy)

    @Test
    fun `S7 complete_tree happy agrees across MCP and REST`() = runWriting(ItemsWriteTreeParity.completeTreeHappy)

    /**
     * The shared harness cannot compare a write that emits event rows (the event `host` is the MCP client name on one leg and
     * the HTTP User-Agent on the other, and a tree's ids are generated per leg). This runs the same two legs by hand with the
     * REST User-Agent set to the MCP client name and every UUID replaced by a marker, then asserts the scenario's literals.
     */
    private fun runWriting(s: ParityScenario) {
        val restDb = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val mcpDb = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val restDir = Files.createTempDirectory("iw-tree-parity-rest-")
        val mcpDir = Files.createTempDirectory("iw-tree-parity-mcp-")
        try {
            val restRig = EventLogRig.build(restDb, restDir, clock = PARITY_CLOCK)
            val mcpRig = EventLogRig.build(mcpDb, mcpDir, clock = PARITY_CLOCK)
            runBlocking {
                for (db in listOf(restDb, mcpDb)) {
                    val provider = db.repositoryProvider()
                    s.seed(ParitySeeder(provider.workItemRepository(), provider.dependencyRepository()))
                }
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
            for (needle in s.expectAbsent) assertFalse(needle in restBody, "${s.name}: body must not contain $needle but was $restBody")
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

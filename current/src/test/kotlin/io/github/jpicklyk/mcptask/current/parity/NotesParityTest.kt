package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Note
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A note the parity runner seeds into both legs through the undecorated repository, with a fixed id so bodies can be byte-equal. */
internal data class SeedNote(
    val id: UUID,
    val itemId: UUID,
    val key: String,
    val role: String,
    val body: String
)

/** A parity scenario plus the notes it needs to exist beforehand (the stock `ParitySeeder` seeds work items only). */
internal data class NotesParityCase(
    val scenario: ParityScenario,
    val notes: List<SeedNote> = emptyList()
)

/**
 * The production parity catalog for `notes_read` and `notes_write` (S21): one HAPPY and one SCOPE_FORBIDDEN scenario per
 * select/op plus the neighbouring outcomes. The `expect*` fields are literals taken from the envelope spec (200 ok, 207
 * partial_failure, 403 forbidden, 404 not_found, details `{scope, required}` / `{kind, id}` / `{expected, actual}`) and from
 * task-scope 1.1 to 1.4, never from a run. `ProductionParity` registers [scenarios].
 */
internal object NotesParityCatalog {
    val r1: UUID = UUID.fromString("a1111111-1111-4111-8111-111111111111")
    val c1: UUID = UUID.fromString("a2222222-2222-4222-8222-222222222222")
    val r2: UUID = UUID.fromString("a3333333-3333-4333-8333-333333333333")
    val note1: UUID = UUID.fromString("b1111111-1111-4111-8111-111111111111")
    val note2: UUID = UUID.fromString("b2222222-2222-4222-8222-222222222222")
    val note3: UUID = UUID.fromString("b3333333-3333-4333-8333-333333333333")

    val reader = ParityToken("np-reader", setOf(ApiCapability.READ))
    val writer = ParityToken("np-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES))
    val rootReader = ParityToken("np-root-reader", setOf(ApiCapability.READ), rootIds = setOf(r1))
    val rootWriter = ParityToken("np-root-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES), rootIds = setOf(r1))
    val tagReader = ParityToken("np-tag-reader", setOf(ApiCapability.READ), tagsInclude = setOf("alpha"))

    /** R1 "Alpha root" (tag alpha) > C1 "Alpha child" (tags alpha,shared); R2 "Beta root" (tag beta). */
    private val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(c1, "Alpha child", parent = r1, tags = "alpha,shared")
        item(r2, "Beta root", tags = "beta")
    }

    private val alphaNote = SeedNote(note1, c1, "spec", "queue", "alpha spec")
    private val betaNote = SeedNote(note2, r2, "spec", "queue", "beta spec")
    private val alphaSecond = SeedNote(note3, c1, "zeta", "work", "second")

    private fun get(
        item: UUID,
        key: String,
        extra: String = ""
    ) = """{"select":"get","itemId":"$item","key":"$key"$extra}"""

    private fun list(
        item: UUID,
        extra: String = ""
    ) = """{"select":"list","itemId":"$item"$extra}"""

    private fun upsert(
        item: UUID,
        key: String,
        body: String,
        extra: String = ""
    ) = """{"op":"upsert","itemId":"$item","key":"$key","role":"queue","body":"$body"$extra}"""

    private fun delete(
        item: UUID,
        key: String,
        extra: String = ""
    ) = """{"op":"delete","itemId":"$item","key":"$key"$extra}"""

    private fun case(
        name: String,
        tool: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        notes: List<SeedNote> = listOf(alphaNote, betaNote),
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList(),
        expectDbChanged: Boolean? = null
    ) = NotesParityCase(
        ParityScenario(
            name,
            tool,
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
        ),
        notes
    )

    private const val READ_TOOL = "notes_read"
    private const val WRITE_TOOL = "notes_write"

    val happy =
        listOf(
            case(
                "notes_read get happy",
                READ_TOOL,
                "get",
                ParityKind.HAPPY,
                reader,
                get(c1, "spec"),
                200,
                expectContains = listOf("\"body\":\"alpha spec\"", "\"version\":1", "\"key\":\"spec\"", "\"id\":\"$note1\""),
                expectDbChanged = false
            ),
            case(
                "notes_read list happy",
                READ_TOOL,
                "list",
                ParityKind.HAPPY,
                reader,
                list(c1),
                200,
                expectContains = listOf("\"key\":\"spec\"", "\"bodyLength\":10", "\"id\":\"${note1.toString().take(8)}\""),
                expectAbsent = listOf("alpha spec", "\"body\":"),
                expectDbChanged = false
            ),
            case(
                "notes_write upsert happy",
                WRITE_TOOL,
                "upsert",
                ParityKind.HAPPY,
                writer,
                """{"ops":[${upsert(c1, "spec", "revised")}]}""",
                200,
                expectContains = listOf("\"version\":2", "\"id\":\"$note1\""),
                expectDbChanged = true
            ),
            case(
                "notes_write delete happy",
                WRITE_TOOL,
                "delete",
                ParityKind.HAPPY,
                writer,
                """{"ops":[${delete(c1, "spec")}]}""",
                200,
                expectContains = listOf("\"id\":\"$note1\""),
                expectDbChanged = true
            )
        )

    val scopeForbidden =
        listOf(
            case(
                "notes_read get scope root",
                READ_TOOL,
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                rootReader,
                get(r2, "spec"),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectAbsent = listOf("beta spec")
            ),
            case(
                "notes_read get scope tag",
                READ_TOOL,
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                tagReader,
                get(r2, "spec"),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"tag\"", "\"required\":\"alpha\""),
                expectAbsent = listOf("beta spec")
            ),
            case(
                "notes_read list scope root",
                READ_TOOL,
                "list",
                ParityKind.SCOPE_FORBIDDEN,
                rootReader,
                list(r2),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectAbsent = listOf("\"key\":\"spec\"")
            ),
            case(
                "notes_write upsert scope root",
                WRITE_TOOL,
                "upsert",
                ParityKind.SCOPE_FORBIDDEN,
                rootWriter,
                """{"ops":[${upsert(r2, "spec", "hijack")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectDbChanged = false
            ),
            case(
                "notes_write delete scope root",
                WRITE_TOOL,
                "delete",
                ParityKind.SCOPE_FORBIDDEN,
                rootWriter,
                """{"ops":[${delete(r2, "spec")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectDbChanged = false
            )
        )

    val other =
        listOf(
            case(
                "notes_read get missing note",
                READ_TOOL,
                "get",
                ParityKind.OTHER,
                reader,
                get(c1, "nope"),
                404,
                expectCode = "not_found",
                expectContains = listOf("\"kind\":\"note\"", "\"id\":\"$c1/nope\""),
                expectDbChanged = false
            ),
            case(
                "notes_read get unchanged by ifVersion",
                READ_TOOL,
                "get",
                ParityKind.OTHER,
                reader,
                get(c1, "spec", ""","ifVersion":1"""),
                200,
                expectContains = listOf("\"unchanged\":true", "\"version\":1"),
                expectAbsent = listOf("alpha spec"),
                expectDbChanged = false
            ),
            case(
                "notes_read list truncated page",
                READ_TOOL,
                "list",
                ParityKind.OTHER,
                reader,
                list(c1, ""","limit":1"""),
                200,
                notes = listOf(alphaNote, alphaSecond, betaNote),
                expectContains = listOf("\"total\":2", "\"truncated\":true", "\"next\":", "\"key\":\"spec\""),
                expectAbsent = listOf("zeta"),
                expectDbChanged = false
            ),
            case(
                "notes_write upsert capability forbidden",
                WRITE_TOOL,
                "upsert",
                ParityKind.OTHER,
                reader,
                """{"ops":[${upsert(c1, "spec", "nope")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"capability\"", "\"required\":\"write-notes\""),
                expectDbChanged = false
            ),
            case(
                "notes_write upsert version conflict",
                WRITE_TOOL,
                "upsert",
                ParityKind.OTHER,
                writer,
                """{"ops":[${upsert(c1, "spec", "stale", ""","expectedVersion":7""")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"version_conflict\"", "\"expected\":7", "\"actual\":1", "\"id\":\"$note1\""),
                expectDbChanged = false
            ),
            case(
                "notes_write delete missing note",
                WRITE_TOOL,
                "delete",
                ParityKind.OTHER,
                writer,
                """{"ops":[${delete(c1, "nope")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"not_found\"", "\"kind\":\"note\""),
                expectDbChanged = false
            ),
            case(
                "notes_write atomic in and out of scope",
                WRITE_TOOL,
                "upsert",
                ParityKind.OTHER,
                rootWriter,
                """{"ops":[${upsert(c1, "extra", "in scope")},${upsert(r2, "spec", "out of scope")}],"atomic":true}""",
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"index\":1", "\"scope\":\"root\""),
                expectDbChanged = false
            )
        )

    val cases: List<NotesParityCase> = happy + scopeForbidden + other

    val scenarios: List<ParityScenario> get() = cases.map { it.scenario }

    val exemptions: List<ScopeExempt> = emptyList()
}

/**
 * The parity harness for scenarios that need notes to exist beforehand. It is the stock [ParityHarness] procedure (two
 * identically seeded databases, one reached through a real MCP client/server pair and one through `POST /api/v1/ops/{tool}`
 * with bearer auth, both under the scenario token) with one extra seeding step for [NotesParityCase.notes]: the stock
 * `ParitySeeder` has no note hook and the shared harness is not edited by this item.
 */
internal class NotesParityRunner(
    private val registry: ToolRegistry
) {
    private class Leg(
        val db: SqliteTestDatabase,
        val dir: Path,
        val rig: EventLogRig
    ) : AutoCloseable {
        override fun close() {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun openLeg(case: NotesParityCase): Leg {
        val db = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val dir = Files.createTempDirectory("notes-parity-leg-")
        try {
            val rig = EventLogRig.build(db, dir, clock = PARITY_CLOCK)
            runBlocking {
                case.scenario.seed(ParitySeeder(db.repositoryProvider().workItemRepository()))
                val notes = db.repositoryProvider().noteRepository()
                for (n in case.notes) {
                    notes.upsert(Note(id = n.id, itemId = n.itemId, key = n.key, role = n.role, body = n.body, modifiedAt = PARITY_NOW))
                }
            }
            return Leg(db, dir, rig)
        } catch (e: Throwable) {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
            throw e
        }
    }

    private class RestResult(
        val status: Int,
        val body: String,
        val reqIdHeader: String?
    )

    private fun runRest(
        leg: Leg,
        s: ParityScenario
    ): RestResult {
        var result: RestResult? = null
        testApplication {
            application { installParityRestApp(leg.rig, parityAuthConfig(s.token), registry) }
            val response =
                client.post("/api/v1/ops/${s.tool}") {
                    header(HttpHeaders.Authorization, "Bearer ${s.token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(s.arguments)
                }
            result = RestResult(response.status.value, response.bodyAsText(), response.headers["X-Req-Id"])
        }
        return checkNotNull(result) { "REST leg produced no response" }
    }

    /**
     * Event rows record the caller's `host`, which is transport-specific by design (REST: the HTTP client's user agent, MCP: the
     * client's `clientInfo`), so it differs between the two legs of every real write; all other event columns are compared.
     */
    private fun withoutTransportHost(snapshot: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
        snapshot.mapValues { (table, rows) -> if (table == "events") rows.map { it - "host" } else rows }

    private val modifiedAt = Regex("\"modifiedAt\":\"([^\"]*)\"")

    private fun normalize(
        body: String,
        reqId: String
    ): String {
        val marker = "\"reqId\":\"$reqId\""
        assertTrue(marker in body, "body carries its reqId marker: $body")
        // Note rows are stamped with the wall clock by the repository (not the injected clock), so a leg's modifiedAt differs
        // by milliseconds; the value must still be an ISO-8601 instant.
        for (stamp in modifiedAt.findAll(body)) {
            Instant.parse(stamp.groupValues[1])
        }
        return body.replace(marker, "\"reqId\":\"<REQ>\"").replace(modifiedAt, "\"modifiedAt\":\"<T>\"")
    }

    fun run(case: NotesParityCase): ParityOutcome {
        val s = case.scenario
        val restLeg = openLeg(case)
        val mcpLeg =
            try {
                openLeg(case)
            } catch (e: Throwable) {
                restLeg.close()
                throw e
            }
        try {
            val before = ParityHarness.snapshot(restLeg.db.jdbcUrl)
            assertEquals(
                before,
                ParityHarness.snapshot(mcpLeg.db.jdbcUrl),
                "${s.name}: the two legs must start from identical seeded databases"
            )
            assertTrue(before.getValue("notes").size == case.notes.size, "${s.name}: every seeded note is in the snapshot")

            val rest = runRest(restLeg, s)
            val mcp =
                runBlocking {
                    callMcp(
                        mcpLeg.rig.composition.toolContext,
                        registry,
                        { parityAccess(s.token) },
                        s.tool,
                        Json.parseToJsonElement(s.arguments).jsonObject
                    )
                }

            val restBody = Json.parseToJsonElement(rest.body).jsonObject
            val restReqId =
                assertNotNull(
                    restBody["meta"]
                        ?.jsonObject
                        ?.get("reqId")
                        ?.jsonPrimitive
                        ?.content,
                    "${s.name}: REST meta.reqId"
                )
            assertEquals(restReqId, rest.reqIdHeader, "${s.name}: X-Req-Id equals the body's meta.reqId")
            val mcpReqId =
                assertNotNull(
                    mcp.structured["meta"]
                        ?.jsonObject
                        ?.get("reqId")
                        ?.jsonPrimitive
                        ?.content,
                    "${s.name}: MCP meta.reqId"
                )

            val restNorm = normalize(rest.body, restReqId)
            val mcpNorm = normalize(mcp.structured.toString(), mcpReqId)
            assertEquals(mcpNorm, restNorm, "${s.name}: body parity between MCP and REST")

            val ok = restBody["ok"]?.jsonPrimitive?.boolean ?: error("${s.name}: body has no ok flag")
            assertEquals(!ok, mcp.isError, "${s.name}: MCP isError is the negation of ok")
            val code =
                restBody["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content
            assertEquals(s.expectCode, code, "${s.name}: error code")
            assertEquals(s.expectStatus, rest.status, "${s.name}: REST status")
            if (code != null) {
                assertEquals(
                    ErrorCode.entries.single { it.wire == code }.httpStatus,
                    rest.status,
                    "${s.name}: status is the code's catalog status"
                )
            }
            for (needle in s.expectContains) assertTrue(needle in restNorm, "${s.name}: body must contain $needle but was $restNorm")
            for (needle in s.expectAbsent) assertFalse(needle in restNorm, "${s.name}: body must not contain $needle but was $restNorm")

            val restAfter = withoutTransportHost(ParityHarness.snapshot(restLeg.db.jdbcUrl))
            val mcpAfter = withoutTransportHost(ParityHarness.snapshot(mcpLeg.db.jdbcUrl))
            for (table in restAfter.keys) {
                assertEquals(restAfter.getValue(table), mcpAfter.getValue(table), "${s.name}: $table snapshot parity between REST and MCP")
            }
            val changed = restAfter != before
            s.expectDbChanged?.let { assertEquals(it, changed, "${s.name}: database changed") }
            return ParityOutcome(rest.status, restNorm, restAfter, changed)
        } finally {
            restLeg.close()
            mcpLeg.close()
        }
    }
}

/**
 * Independent tests (test-author seat, item 0b2d5199) for scenario S21: `notes_read` and `notes_write` answer the same bytes
 * over MCP and over `POST /api/v1/ops/{tool}` and leave the same database, under HAPPY and SCOPE_FORBIDDEN scenarios for
 * `get`, `list`, `upsert` and `delete` (plan 3.2: one canonical behaviour; task-scope 1.9: `ParityCoverageGuardTest` is
 * non-vacuous from this item on). The ported note write policy of S21 (role case, schema role, maxLength) is asserted in
 * `NotesWriteToolTest`.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class NotesParityTest {
    private val runner = NotesParityRunner(ToolRegistry(buildToolSpecs()))

    @Test
    fun `S21 happy get, list, upsert and delete agree across MCP and REST and leave equal databases`() {
        for (case in NotesParityCatalog.happy) runner.run(case)
    }

    @Test
    fun `S21 scope-forbidden get, list, upsert and delete agree across MCP and REST and write nothing`() {
        for (case in NotesParityCatalog.scopeForbidden) runner.run(case)
    }

    @Test
    fun `S21 not found, ifVersion, paging, capability, conflict and atomic scenarios agree across MCP and REST`() {
        for (case in NotesParityCatalog.other) runner.run(case)
    }

    @Test
    fun `S21 the catalog gives every notes select and op a happy and a restricted-scope forbidden scenario`() {
        val registry = ToolRegistry(buildToolSpecs())
        val members = ParityCoverage.members(registry)
        assertEquals(
            setOf("notes_read" to "get", "notes_read" to "list", "notes_write" to "upsert", "notes_write" to "delete"),
            members.filter { it.first.startsWith("notes_") }.toSet()
        )
        val missing = ParityCoverage.missing(registry, NotesParityCatalog.scenarios, NotesParityCatalog.exemptions)
        assertEquals(emptyList(), missing.filter { it.tool.startsWith("notes_") })
        assertEquals(emptyList(), ParityCoverage.stale(registry, NotesParityCatalog.scenarios))
        assertTrue(NotesParityCatalog.exemptions.isEmpty(), "no scope exemption: every notes member touches an item")
    }

    @Test
    fun `S21 the production catalog registers the notes scenarios`() {
        assertTrue(ProductionParity.scenarios.containsAll(NotesParityCatalog.scenarios))
        assertTrue(ProductionParity.exemptions.containsAll(NotesParityCatalog.exemptions))
    }
}

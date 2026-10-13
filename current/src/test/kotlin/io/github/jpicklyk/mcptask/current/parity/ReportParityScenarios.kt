package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `report` slice of [ProductionParity] (item eadaaa4e, R9; scenario S17 of the frozen test-plan): one HAPPY and one
 * SCOPE_FORBIDDEN scenario for `report.usage` plus OTHER scenarios for the failures whose status and shape the envelope spec and
 * the task-scope fix. Every scenario sends the SAME arguments over MCP (director profile) and over `POST /api/v1/ops/report`
 * against two identically seeded databases; the harness asserts byte-equal bodies (minus `meta.reqId`), the literal REST status,
 * the literal error code and an unchanged database. The expectations are literals taken from the acceptance criteria: AC1
 * (metrics), AC2 (window), AC3 (groupBy), AC4 (S22 scope: tag token forbidden{tag}, root scope filters rows, principal grouping
 * needs ADMIN), AC5 (rootId), AC6 (retention) and the envelope statuses (invalid_request 400, forbidden 403, not_found 404,
 * unknown_parameter 400, invalid_cursor 400).
 *
 * Fixture (clock fixed at [PARITY_NOW], 2026-10-10T12:00Z, so the default window is [2026-10-03T12:00Z, 2026-10-10T12:00Z)): roots
 * R1 "Alpha root" (tag alpha) with child C1 and R2 "Beta root" (tag beta), and four call_log rows. x_tool: two rows under R1
 * (one ok with 100 response bytes, latency 10, one error with latency 30 and two attempts, both principal pid-a) and one row with no
 * root (latency 5, no principal); y_tool: one row under R2 (latency 7, 8 bytes, principal pid-b). Hand sums over everything: x_tool
 * 3 calls, 1 error, 100 bytes, 25 tokens, latency 45 so average 15, maximum 30, 1 retry; y_tool 1 call. The oldest row is
 * 2026-10-08T09:00Z, so the default window starts before it and is truncated by retention.
 */
internal object ReportParityScenarios {
    private val r1: UUID = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val r2: UUID = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val c1: UUID = UUID.fromString("c3000001-0000-4000-8000-000000000003")

    private val reader = ParityToken("rp-reader", setOf(ApiCapability.READ))
    private val admin = ParityToken("rp-admin", setOf(ApiCapability.READ, ApiCapability.ADMIN))
    private val rootScoped = ParityToken("rp-root", setOf(ApiCapability.READ), rootIds = setOf(r1))
    private val adminRootScoped = ParityToken("rp-admin-root", setOf(ApiCapability.READ, ApiCapability.ADMIN), rootIds = setOf(r1))
    private val tagScoped = ParityToken("rp-tag", setOf(ApiCapability.READ), tagsInclude = setOf("api"))
    private val noRead = ParityToken("rp-no-read", setOf(ApiCapability.WRITE_ITEMS))

    private fun at(text: String): Instant = Instant.parse(text)

    val seed: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(r2, "Beta root", tags = "beta")
        item(c1, "Alpha child", parent = r1, tags = "alpha")
        callLog(
            ReportKit.rec(
                1,
                at("2026-10-09T10:00:00Z"),
                "x_tool",
                principalId = "pid-a",
                principalKind = "subagent",
                latencyMs = 10,
                responseBytes = 100,
                rootId = r1
            ),
            ReportKit.rec(
                2,
                at("2026-10-09T11:00:00Z"),
                "x_tool",
                principalId = "pid-a",
                principalKind = "subagent",
                error = "gate_blocked",
                attempts = 2,
                latencyMs = 30,
                rootId = r1
            ),
            ReportKit.rec(
                3,
                at("2026-10-08T09:00:00Z"),
                "y_tool",
                surface = "rest",
                principalId = "pid-b",
                principalKind = "user",
                latencyMs = 7,
                responseBytes = 8,
                rootId = r2
            ),
            ReportKit.rec(4, at("2026-10-08T10:00:00Z"), "x_tool", surface = "rest", latencyMs = 5)
        )
    }

    private fun scenario(
        name: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList()
    ) = ParityScenario(
        name = "report $name",
        tool = "report",
        member = "usage",
        kind = kind,
        token = token,
        arguments = arguments,
        expectStatus = expectStatus,
        expectCode = expectCode,
        expectContains = expectContains,
        expectAbsent = expectAbsent,
        expectDbChanged = false,
        seed = seed
    )

    val happy: List<ParityScenario> =
        listOf(
            scenario(
                "usage happy",
                ParityKind.HAPPY,
                reader,
                """{"select":"usage"}""",
                200,
                expectContains =
                    listOf(
                        "\"key\":{\"tool\":\"x_tool\"}",
                        "\"calls\":3",
                        "\"errors\":1",
                        "\"responseBytes\":100",
                        "\"responseTokensEst\":25",
                        "\"avgLatencyMs\":15",
                        "\"maxLatencyMs\":30",
                        "\"retries\":1",
                        "\"key\":{\"tool\":\"y_tool\"}",
                        "\"total\":2",
                        "\"basis\":\"raw\"",
                        "\"retainedSince\":",
                        "\"truncatedByRetention\":true"
                    )
            ),
            scenario(
                "usage by day",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"day"}""",
                200,
                expectContains = listOf("\"key\":{\"day\":\"2026-10-09\"}", "\"key\":{\"day\":\"2026-10-08\"}", "\"calls\":2")
            ),
            scenario(
                "usage rootId",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","rootId":"$r1"}""",
                200,
                expectContains = listOf("\"key\":{\"tool\":\"x_tool\"}", "\"calls\":2", "\"total\":1"),
                expectAbsent = listOf("y_tool")
            ),
            scenario(
                "usage first page of a limit of one",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","limit":1}""",
                200,
                expectContains = listOf("\"key\":{\"tool\":\"x_tool\"}", "\"total\":2", "\"truncated\":true", "\"next\":\""),
                expectAbsent = listOf("y_tool")
            )
        )

    /**
     * Groups whose key is NULL (an ok row has no errorCode, a call without a principal has no principal_id). The task-scope
     * decision says a NULL key is JSON null; the shared envelope conformance check (`EnvelopeConformance`, run by the parity
     * harness on every body) says absent members are omitted and never null. The two oracles disagree, so these scenarios are
     * kept apart from [happy]: they fail at the conformance check until the orchestrator arbitrates (see the test-manifest).
     */
    val nullKeys: List<ParityScenario> =
        listOf(
            scenario(
                "usage by principal for an admin keys by principal id, a missing principal omits the member",
                ParityKind.OTHER,
                admin,
                """{"select":"usage","groupBy":"principal"}""",
                200,
                expectContains =
                    listOf(
                        "\"key\":{\"principal\":\"pid-a\"}",
                        "\"key\":{\"principal\":\"pid-b\"}",
                        "\"key\":{}"
                    )
            ),
            scenario(
                "usage by errorCode, the ok rows form the null group",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"errorCode"}""",
                200,
                expectContains = listOf("\"key\":{}", "\"key\":{\"errorCode\":\"gate_blocked\"}")
            )
        )

    val scopeForbidden: List<ParityScenario> =
        listOf(
            scenario(
                "usage scope tag",
                ParityKind.SCOPE_FORBIDDEN,
                tagScoped,
                """{"select":"usage"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"tag\""),
                expectAbsent = listOf("x_tool", "y_tool")
            ),
            scenario(
                "usage scope root",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"usage","rootId":"$r2"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectAbsent = listOf("y_tool")
            )
        )

    val other: List<ParityScenario> =
        listOf(
            scenario(
                "usage scope tag with a rootId",
                ParityKind.OTHER,
                tagScoped,
                """{"select":"usage","rootId":"$r1"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"tag\""),
                expectAbsent = listOf("x_tool")
            ),
            scenario(
                "usage filtered by root scope",
                ParityKind.OTHER,
                rootScoped,
                """{"select":"usage"}""",
                200,
                expectContains = listOf("\"key\":{\"tool\":\"x_tool\"}", "\"calls\":2", "\"total\":1"),
                expectAbsent = listOf("y_tool")
            ),
            scenario(
                "usage by principal without admin",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"principal"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"admin\""),
                expectAbsent = listOf("pid-a", "pid-b")
            ),
            scenario(
                "usage by principal for an admin under a root scope sees only that scope",
                ParityKind.OTHER,
                adminRootScoped,
                """{"select":"usage","groupBy":"principal"}""",
                200,
                expectContains = listOf("\"key\":{\"principal\":\"pid-a\"}", "\"total\":1"),
                expectAbsent = listOf("pid-b", "\"principal\":null")
            ),
            scenario(
                "usage without the read capability",
                ParityKind.OTHER,
                noRead,
                """{"select":"usage"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"read\""),
                expectAbsent = listOf("x_tool")
            ),
            scenario(
                "usage groupBy noteKey",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"noteKey"}""",
                400,
                "invalid_request",
                expectContains = listOf("/groupBy")
            ),
            scenario(
                "usage groupBy resource",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"resource"}""",
                400,
                "invalid_request",
                expectContains = listOf("/groupBy")
            ),
            scenario(
                "usage groupBy with other casing",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","groupBy":"Tool"}""",
                400,
                "invalid_request"
            ),
            scenario(
                "usage unknown runId",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","runId":"$r1"}""",
                400,
                "unknown_parameter",
                listOf("/runId")
            ),
            scenario(
                "usage unknown anchorId",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","anchorId":"$r1"}""",
                400,
                "unknown_parameter",
                listOf("/anchorId")
            ),
            scenario(
                "usage unknown principalId filter",
                ParityKind.OTHER,
                admin,
                """{"select":"usage","principalId":"pid-a"}""",
                400,
                "unknown_parameter",
                listOf("/principalId")
            ),
            scenario(
                "usage unknown tool filter",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","tool":"x_tool"}""",
                400,
                "unknown_parameter",
                listOf("/tool")
            ),
            scenario(
                "usage unknown rootId",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","rootId":"99999999-9999-4999-8999-999999999999"}""",
                404,
                "not_found"
            ),
            scenario(
                "usage rootId of a child",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","rootId":"$c1"}""",
                400,
                "invalid_request",
                expectContains = listOf("/rootId")
            ),
            scenario(
                "usage since not before until",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2026-10-09T00:00:00Z","until":"2026-10-09T00:00:00Z"}""",
                400,
                "invalid_request"
            ),
            scenario(
                "usage window over 31 days",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2026-09-09T11:59:59.999Z"}""",
                400,
                "invalid_request"
            ),
            scenario(
                "usage window of exactly 31 days",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2026-09-09T12:00:00Z"}""",
                200,
                expectContains = listOf("\"total\":2", "\"truncatedByRetention\":true")
            ),
            scenario(
                "usage since with an offset is echoed",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2026-10-08T11:30:00+02:00","until":"2026-10-09T12:00:00+02:00"}""",
                200,
                expectContains = listOf("\"key\":{\"tool\":\"x_tool\"}", "\"calls\":1", "\"total\":1"),
                expectAbsent = listOf("y_tool")
            ),
            scenario(
                "usage limit zero",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","limit":0}""",
                400,
                "invalid_request",
                expectContains = listOf("\"field\":\"limit\"")
            ),
            scenario(
                "usage limit above the maximum",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","limit":201}""",
                400,
                "invalid_request",
                expectContains = listOf("\"field\":\"limit\"")
            ),
            scenario(
                "usage garbled cursor",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","cursor":"!!!not-a-cursor"}""",
                400,
                "invalid_cursor"
            )
        )

    val scenarios: List<ParityScenario> = happy + scopeForbidden + other + nullKeys

    val exemptions: List<ScopeExempt> = emptyList()
}

@Timeout(value = 900, unit = TimeUnit.SECONDS)
class ReportParityTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val harness = ParityHarness(registry)

    @Test
    fun `S17 happy report scenarios agree across MCP director and REST in body, status and database`() {
        for (s in ReportParityScenarios.happy) harness.run(s)
    }

    @Test
    fun `S17 scope-forbidden report scenarios agree across MCP director and REST under the same access`() {
        for (s in ReportParityScenarios.scopeForbidden) harness.run(s)
    }

    @Test
    fun `S17 scope, capability, validation, window and strictness scenarios agree across MCP director and REST`() {
        for (s in ReportParityScenarios.other) harness.run(s)
    }

    @Test
    fun `S17 NULL group keys agree across MCP director and REST and conform to the envelope`() {
        for (s in ReportParityScenarios.nullKeys) harness.run(s)
    }

    @Test
    fun `S17 control - the catalog holds a happy and a restricted scope-forbidden scenario for report usage and is registered`() {
        val mine = ReportParityScenarios.scenarios
        assertTrue(mine.any { it.kind == ParityKind.HAPPY })
        assertTrue(mine.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted })
        assertTrue(mine.all { it.tool == "report" && it.member in setOf("usage", "gates", "contention") })
        assertTrue(ProductionParity.scenarios.containsAll(mine), "every report scenario is registered in the production catalog")
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "report", "usage"), "the MCP leg runs on the director")
    }

    // ------------------------------------------------------------------ S16 surface

    @Test
    fun `S16 report is visible in director only`() {
        assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "report", "usage"))
        assertTrue("report" in registry.visible(ToolProfile.DIRECTOR).map { it.name })
        assertTrue("report" !in registry.visible(ToolProfile.ORCHESTRATION).map { it.name })
        assertTrue("report" !in registry.visible(ToolProfile.KNOWLEDGE).map { it.name })
        val spec = assertNotNull(registry["report"])
        assertNull(McpOperationAdapter.toolFor(spec, ToolProfile.ORCHESTRATION))
        assertNull(McpOperationAdapter.toolFor(spec, ToolProfile.KNOWLEDGE))
        assertNotNull(McpOperationAdapter.toolFor(spec, ToolProfile.DIRECTOR))
        assertEquals(
            listOf("usage", "gates", "contention"),
            ParityCoverage.members(registry).filter { it.first == "report" }.map { it.second }
        )
    }

    @Test
    fun `S16 the director report entry is read-only and idempotent and fits 1800 compact characters`() {
        val tool = assertNotNull(McpOperationAdapter.toolFor(assertNotNull(registry["report"]), ToolProfile.DIRECTOR))
        val annotations =
            McpJson
                .encodeToJsonElement(Tool.serializer(), tool)
                .jsonObject
                .getValue("annotations")
                .jsonObject
        assertEquals("true", annotations.getValue("readOnlyHint").jsonPrimitive.content)
        assertEquals("true", annotations.getValue("idempotentHint").jsonPrimitive.content)
        val size = ToolGoldens.compact(tool).length
        assertTrue(size in 1..1_800, "the compact report entry is $size characters")
        assertEquals(size, ToolSurfaceChecks.sizes(registry, ToolProfile.DIRECTOR).getValue("report"))
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(registry).filter { it.startsWith("report/") })
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
    }

    @Test
    fun `S16 the golden exists for director only`() {
        assertNotNull(ReportParityTest::class.java.getResource("/golden/tools/report.director.json"))
        assertNull(ReportParityTest::class.java.getResource("/golden/tools/report.orchestration.json"))
        assertNull(ReportParityTest::class.java.getResource("/golden/tools/report.knowledge.json"))
        assertNull(
            ReportParityTest::class.java.getResource("/golden/tools/planned/report.director.json"),
            "the full planned entry is stale once report is visible"
        )
        assertNotNull(ReportParityTest::class.java.getResource("/golden/tools/planned/report.director.delta.json"))
    }

    @Test
    fun `S16 describe lists the usage select with its inputs and no rollup input`() {
        val spec = assertNotNull(registry["report"])
        val described = ToolDescriber.describe(spec, ToolProfile.DIRECTOR, null)
        val operations = described.getValue("operations").jsonArray.map { it.jsonObject }
        assertEquals(listOf("usage", "gates", "contention"), operations.map { it.getValue("name").jsonPrimitive.content })
        val properties =
            operations
                .first { it.getValue("name").jsonPrimitive.content == "usage" }
                .getValue("parameters")
                .jsonObject
                .getValue("properties")
                .jsonObject.keys
        assertTrue(properties.containsAll(setOf("rootId", "since", "until", "groupBy", "limit", "cursor")), "inputs: $properties")
        for (reserved in listOf("runId", "anchorId", "principalId", "tool", "noteKey", "filters")) {
            assertTrue(reserved !in properties, "$reserved is not an input of usage: $properties")
        }
    }
}

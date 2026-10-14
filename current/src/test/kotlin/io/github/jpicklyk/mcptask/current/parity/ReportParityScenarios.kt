package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
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
        expectAbsent: List<String> = emptyList(),
        member: String = "usage",
        rows: suspend ParitySeeder.() -> Unit = seed
    ) = ParityScenario(
        name = "report $name",
        tool = "report",
        member = member,
        kind = kind,
        token = token,
        arguments = arguments,
        expectStatus = expectStatus,
        expectCode = expectCode,
        expectContains = expectContains,
        expectAbsent = expectAbsent,
        expectDbChanged = false,
        seed = rows
    )

    // ------------------------------------------------------------------ waste (item 03f3520d, R12; scenario S16 of its test-plan)

    private const val X1 = "a0000000-0000-4000-8000-000000000001"

    private fun wasteRow(
        n: Int,
        time: String,
        op: String,
        tool: String = "items_read",
        session: String? = null,
        surface: String = "mcp",
        error: String? = null,
        versions: String? = null,
        shape: String? = null,
        result: Int? = null,
        eligible: Int? = null,
        lf: Int? = null,
        root: UUID? = r1
    ) = ReportKit
        .rec(n, at(time), tool, op, surface = surface, error = error, rootId = root)
        .copy(
            sessionId = session,
            targetVersions = versions,
            requestShape = shape,
            resultCount = result,
            eligibleCount = eligible,
            lifecycleFreeRejections = lf
        )

    /**
     * The fixture of the waste scenarios (clock at [PARITY_NOW], 2026-10-10T12:00Z, so the default window is
     * [2026-10-03T12:00Z, 2026-10-10T12:00Z)): the roots of [seed] and five call_log rows. Session `sess-a` (MCP, root R1): at 10:00
     * and 10:01 on 2026-10-09 two ok items_read gets of the same key at version 1 (the first opted into `fields`), at 10:02 a failed
     * items_write that counted one lifecycle-free rejection, at 10:03 a next that returned nothing although two items were
     * eligible. At 10:04 a REST next (root R2, no session) that returned nothing although five items were eligible.
     *
     * Hand counts for an unrestricted reader: redundantReads 1 of 2 observations (value 0.5); overFetch 1 of the 4 fetch rows
     * (the two gets and the two nexts), value 0.25; readsWithoutLaterWrite 1 of the 3 ok session reads (only the next at 10:03 has no
     * later write), value 0.3333; emptyNext 2 of 2, value 1; relatedFollowThrough 0 of 0 for each of the four signals;
     * lifecycleFreeRejections 1 of 1 non-replayed write call, value 1.
     */
    private val wasteSeed: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(r2, "Beta root", tags = "beta")
        item(c1, "Alpha child", parent = r1, tags = "alpha")
        callLog(
            wasteRow(
                11,
                "2026-10-09T10:00:00Z",
                "get",
                session = "sess-a",
                versions = "{\"$X1\":1}",
                shape = "{\"fields\":[\"ancestors\"]}"
            ),
            wasteRow(12, "2026-10-09T10:01:00Z", "get", session = "sess-a", versions = "{\"$X1\":1}"),
            wasteRow(13, "2026-10-09T10:02:00Z", "update", tool = "items_write", session = "sess-a", error = "invalid_transition", lf = 1),
            wasteRow(14, "2026-10-09T10:03:00Z", "next", session = "sess-a", result = 0, eligible = 2),
            wasteRow(15, "2026-10-09T10:04:00Z", "next", surface = "rest", result = 0, eligible = 5, root = r2)
        )
    }

    private fun wasteScenario(
        name: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList()
    ) = scenario(
        name,
        kind,
        token,
        arguments,
        expectStatus,
        expectCode,
        expectContains,
        expectAbsent,
        member = "waste",
        rows = wasteSeed
    )

    private val wasteHappy: List<ParityScenario> =
        listOf(
            wasteScenario(
                "waste happy",
                ParityKind.HAPPY,
                reader,
                """{"select":"waste"}""",
                200,
                expectContains =
                    listOf(
                        "\"signals\"",
                        "\"redundantReads\"",
                        "\"overFetch\"",
                        "\"readsWithoutLaterWrite\"",
                        "\"emptyNext\"",
                        "\"relatedFollowThrough\"",
                        "\"lifecycleFreeRejections\"",
                        "\"edges\"",
                        "\"links\"",
                        "\"tags\"",
                        "\"text\"",
                        "\"basis\":\"mcp_sessions\"",
                        "\"value\":0.5",
                        "\"value\":0.25",
                        "\"value\":0.3333",
                        "\"numerator\":1",
                        "\"denominator\":3",
                        "\"denominator\":0",
                        "\"basis\":\"raw\"",
                        "\"retainedSince\":"
                    )
            )
        )

    private val wasteScopeForbidden: List<ParityScenario> =
        listOf(
            wasteScenario(
                "waste scope tag",
                ParityKind.SCOPE_FORBIDDEN,
                tagScoped,
                """{"select":"waste"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"tag\""),
                expectAbsent = listOf("\"signals\"", "\"redundantReads\"")
            ),
            wasteScenario(
                "waste scope root",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"waste","rootId":"$r2"}""",
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectAbsent = listOf("\"signals\"")
            )
        )

    private val wasteOther: List<ParityScenario> =
        listOf(
            wasteScenario(
                "waste filtered by root scope",
                ParityKind.OTHER,
                rootScoped,
                """{"select":"waste"}""",
                200,
                expectContains = listOf("\"signals\"", "\"basis\":\"mcp_sessions\"")
            ),
            wasteScenario(
                "waste rootId narrows",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","rootId":"$r1"}""",
                200,
                expectContains = listOf("\"signals\"", "\"value\":0.3333")
            ),
            wasteScenario(
                "waste groupBy is not an input",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","groupBy":"tool"}""",
                400,
                "unknown_parameter",
                listOf("/groupBy")
            ),
            wasteScenario(
                "waste limit is not an input",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","limit":5}""",
                400,
                "unknown_parameter",
                listOf("/limit")
            ),
            wasteScenario(
                "waste cursor is not an input",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","cursor":"abc"}""",
                400,
                "unknown_parameter",
                listOf("/cursor")
            ),
            wasteScenario(
                "waste window over 7 days",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","since":"2026-10-03T11:59:59.999Z"}""",
                400,
                "invalid_request",
                listOf("/since")
            ),
            wasteScenario(
                "waste window of exactly 7 days",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","since":"2026-10-03T12:00:00Z"}""",
                200,
                expectContains = listOf("\"signals\"", "\"basis\":\"raw\"")
            ),
            wasteScenario(
                "waste since not before until",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","since":"2026-10-09T00:00:00Z","until":"2026-10-09T00:00:00Z"}""",
                400,
                "invalid_request"
            ),
            wasteScenario(
                "waste unknown rootId",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","rootId":"99999999-9999-4999-8999-999999999999"}""",
                404,
                "not_found"
            ),
            wasteScenario(
                "waste rootId of a child",
                ParityKind.OTHER,
                reader,
                """{"select":"waste","rootId":"$c1"}""",
                400,
                "invalid_request",
                listOf("/rootId")
            ),
            wasteScenario(
                "waste without the read capability",
                ParityKind.OTHER,
                noRead,
                """{"select":"waste"}""",
                403,
                "forbidden",
                listOf("\"scope\":\"capability\"", "\"required\":\"read\""),
                listOf("\"signals\"")
            )
        )

    private val usageHappy: List<ParityScenario> =
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

    private val usageScopeForbidden: List<ParityScenario> =
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

    private val usageOther: List<ParityScenario> =
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
                "usage window over 400 days",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2025-09-05T11:59:59.999Z"}""",
                400,
                "invalid_request",
                expectContains = listOf("/since")
            ),
            scenario(
                "usage window over 31 days reads the daily basis",
                ParityKind.OTHER,
                reader,
                """{"select":"usage","since":"2026-08-01T00:00:00Z","until":"2026-10-10T12:00:00Z"}""",
                200,
                expectContains = listOf("\"key\":{\"tool\":\"x_tool\"}", "\"calls\":3", "\"total\":2", "\"basis\":\"daily\"")
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

    val happy: List<ParityScenario> = usageHappy + wasteHappy

    val scopeForbidden: List<ParityScenario> = usageScopeForbidden + wasteScopeForbidden

    val other: List<ParityScenario> = usageOther + wasteOther

    // ------------------------------------------------------------------ R11 (item 82397512): gates and contention

    /** The selects R11 adds; every scenario below runs once per select, so a rule is asserted on both. */
    private val newSelects = listOf("gates", "contention")

    private fun both(
        name: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: (select: String) -> String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList()
    ): List<ParityScenario> =
        newSelects.map { select ->
            scenario(
                "$select $name",
                kind,
                token,
                arguments(select),
                expectStatus,
                expectCode,
                expectContains,
                expectAbsent,
                member = select
            )
        }

    /**
     * The fixture seeds `call_log` and items only (the parity seeder has no `events` writer), so every answer below is over an
     * empty event table: the scenarios pin the envelope, status, window echo, scope handling and validation, byte-equal across
     * MCP and REST; the counted values are asserted at the store and service level.
     */
    val r11Happy: List<ParityScenario> =
        both(
            "happy",
            ParityKind.HAPPY,
            reader,
            { select -> """{"select":"$select"}""" },
            200,
            expectContains = listOf("\"total\":0", "\"basis\":\"raw\""),
            expectAbsent = listOf("retainedSince", "x_tool", "y_tool")
        ) +
            scenario(
                "gates groupBy type",
                ParityKind.OTHER,
                reader,
                """{"select":"gates","groupBy":"type"}""",
                200,
                expectContains = listOf("\"total\":0"),
                member = "gates"
            ) +
            scenario(
                "gates groupBy trait",
                ParityKind.OTHER,
                reader,
                """{"select":"gates","groupBy":"trait"}""",
                200,
                expectContains = listOf("\"total\":0"),
                member = "gates"
            ) +
            scenario(
                "gates groupBy errorCode",
                ParityKind.OTHER,
                reader,
                """{"select":"gates","groupBy":"errorCode"}""",
                200,
                expectContains = listOf("\"total\":0"),
                member = "gates"
            ) +
            scenario(
                "gates groupBy noteKey",
                ParityKind.OTHER,
                reader,
                """{"select":"gates","groupBy":"noteKey"}""",
                200,
                expectContains = listOf("\"total\":0"),
                member = "gates"
            ) +
            scenario(
                "contention groupBy resource",
                ParityKind.OTHER,
                reader,
                """{"select":"contention","groupBy":"resource"}""",
                200,
                expectContains = listOf("\"total\":0"),
                member = "contention"
            )

    val r11ScopeForbidden: List<ParityScenario> =
        both(
            "scope root",
            ParityKind.SCOPE_FORBIDDEN,
            rootScoped,
            { select -> """{"select":"$select","rootId":"$r2"}""" },
            403,
            "forbidden",
            expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
            expectAbsent = listOf("y_tool")
        )

    val r11Other: List<ParityScenario> =
        both(
            "tag scope is not forbidden",
            ParityKind.OTHER,
            tagScoped,
            { select -> """{"select":"$select"}""" },
            200,
            expectContains = listOf("\"total\":0"),
            expectAbsent = listOf("forbidden")
        ) +
            both(
                "filtered by root scope",
                ParityKind.OTHER,
                rootScoped,
                { select -> """{"select":"$select"}""" },
                200,
                expectContains = listOf("\"total\":0")
            ) +
            both(
                "own root under a root scope",
                ParityKind.OTHER,
                rootScoped,
                { select -> """{"select":"$select","rootId":"$r1"}""" },
                200,
                expectContains = listOf("\"total\":0")
            ) +
            both(
                "without the read capability",
                ParityKind.OTHER,
                noRead,
                { select -> """{"select":"$select"}""" },
                403,
                "forbidden",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"read\"")
            ) +
            listOf("tool", "principal", "resource", "day", "host").map { groupBy ->
                scenario(
                    "gates groupBy $groupBy",
                    ParityKind.OTHER,
                    admin,
                    """{"select":"gates","groupBy":"$groupBy"}""",
                    400,
                    "invalid_request",
                    expectContains = listOf("/groupBy"),
                    member = "gates"
                )
            } +
            listOf("noteKey", "type", "trait", "errorCode", "tool", "principal").map { groupBy ->
                scenario(
                    "contention groupBy $groupBy",
                    ParityKind.OTHER,
                    admin,
                    """{"select":"contention","groupBy":"$groupBy"}""",
                    400,
                    "invalid_request",
                    expectContains = listOf("/groupBy"),
                    member = "contention"
                )
            } +
            both(
                "groupBy with other casing",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","groupBy":"${if (select == "gates") "NoteKey" else "Resource"}"}""" },
                400,
                "invalid_request"
            ) +
            both(
                "unknown runId",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","runId":"$r1"}""" },
                400,
                "unknown_parameter",
                listOf("/runId")
            ) +
            both(
                "unknown noteKey filter",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","noteKey":"spec"}""" },
                400,
                "unknown_parameter",
                listOf("/noteKey")
            ) +
            both(
                "unknown rootId",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","rootId":"99999999-9999-4999-8999-999999999999"}""" },
                404,
                "not_found"
            ) +
            both(
                "rootId of a child",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","rootId":"$c1"}""" },
                400,
                "invalid_request",
                expectContains = listOf("/rootId")
            ) +
            both(
                "since not before until",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","since":"2026-10-09T00:00:00Z","until":"2026-10-09T00:00:00Z"}""" },
                400,
                "invalid_request"
            ) +
            both(
                "window over 31 days",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","since":"2026-09-09T11:59:59.999Z"}""" },
                400,
                "invalid_request"
            ) +
            both(
                "window of exactly 31 days",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","since":"2026-09-09T12:00:00Z"}""" },
                200,
                expectContains = listOf("\"total\":0")
            ) +
            both(
                "since with an offset is echoed in UTC",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","since":"2026-10-08T11:30:00+02:00","until":"2026-10-09T12:00:00+02:00"}""" },
                200,
                expectContains = listOf("2026-10-08T09:30:00Z", "2026-10-09T10:00:00Z")
            ) +
            both(
                "limit zero",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","limit":0}""" },
                400,
                "invalid_request",
                expectContains = listOf("\"field\":\"limit\"")
            ) +
            both(
                "limit above the maximum",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","limit":201}""" },
                400,
                "invalid_request",
                expectContains = listOf("\"field\":\"limit\"")
            ) +
            both(
                "garbled cursor",
                ParityKind.OTHER,
                reader,
                { select -> """{"select":"$select","cursor":"!!!not-a-cursor"}""" },
                400,
                "invalid_cursor"
            )

    val scenarios: List<ParityScenario> = happy + scopeForbidden + other + nullKeys + r11Happy + r11ScopeForbidden + r11Other

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
    fun `R11 S15 happy gates and contention scenarios agree across MCP director and REST in body, status and database`() {
        for (s in ReportParityScenarios.r11Happy) harness.run(s)
    }

    @Test
    fun `R11 S15 scope-forbidden gates and contention scenarios agree across MCP director and REST under the same access`() {
        for (s in ReportParityScenarios.r11ScopeForbidden) harness.run(s)
    }

    @Test
    fun `R11 S15 scope, capability, validation, window and strictness scenarios agree across MCP director and REST`() {
        for (s in ReportParityScenarios.r11Other) harness.run(s)
    }

    @Test
    fun `R11 S15 control - every report select holds a happy and a restricted scope-forbidden scenario and runs on the director`() {
        val selects = listOf("usage", "gates", "contention", "waste")
        for (select in selects) {
            val mine = ReportParityScenarios.scenarios.filter { it.member == select }
            assertTrue(mine.any { it.kind == ParityKind.HAPPY }, "$select has a happy scenario")
            assertTrue(
                mine.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$select has a restricted scope-forbidden scenario"
            )
            assertTrue(ProductionParity.scenarios.containsAll(mine), "every $select scenario is registered in the production catalog")
            assertEquals(
                ToolProfile.DIRECTOR,
                ParityCoverage.mcpLegProfile(registry, "report", select),
                "$select MCP leg runs on the director"
            )
            assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "report", select), select)
        }
        assertEquals(selects.toSet(), ReportParityScenarios.scenarios.map { it.member }.toSet(), "no scenario names another select")
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
    }

    /**
     * Merge-round ruling: R11's +20 rule over the R9 entry of 1540 was R11's own increment and cannot survive waste
     * landing beside it; the merged compact entry is held to the 1,800-char planned fixture instead.
     */
    @Test
    fun `R11 S15 the compact director report entry stays within the 1800-char planned fixture`() {
        val tool = assertNotNull(McpOperationAdapter.toolFor(assertNotNull(registry["report"]), ToolProfile.DIRECTOR))
        val size = ToolGoldens.compact(tool).length
        assertTrue(size in 1..1_800, "the compact report entry is $size characters")
    }

    @Test
    fun `R11 S15 describe lists usage, gates, contention and waste with their exact inputs and no rollup or filter input`() {
        val spec = assertNotNull(registry["report"])
        val described = ToolDescriber.describe(spec, ToolProfile.DIRECTOR, null)
        val operations = described.getValue("operations").jsonArray.map { it.jsonObject }
        assertEquals(
            setOf("usage", "gates", "contention", "waste"),
            operations.map { it.getValue("name").jsonPrimitive.content }.toSet()
        )
        assertEquals(4, operations.size)
        for (operation in operations) {
            val name = operation.getValue("name").jsonPrimitive.content
            val properties =
                operation
                    .getValue("parameters")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject.keys
            val expected =
                if (name == "waste") {
                    setOf("rootId", "since", "until")
                } else {
                    setOf("rootId", "since", "until", "groupBy", "limit", "cursor")
                }
            assertEquals(expected, properties.toSet(), "$name inputs: $properties")
            for (reserved in listOf("runId", "anchorId", "principalId", "tool", "noteKey", "filters")) {
                assertTrue(reserved !in properties, "$reserved is not an input of $name: $properties")
            }
        }
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
        assertTrue(mine.all { it.tool == "report" })
        assertEquals(
            setOf("usage", "gates", "contention", "waste"),
            mine
                .map {
                    it.member
                }.toSet(),
            "the catalog covers exactly the usage, gates, contention and waste selects"
        )
        assertTrue(ProductionParity.scenarios.containsAll(mine), "every report scenario is registered in the production catalog")
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "report", "usage"), "the MCP leg runs on the director")
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "report", "waste"), "the MCP leg runs on the director")
    }

    // ------------------------------------------------------------------ S16 surface

    @Test
    fun `S16 report is visible in director only`() {
        assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "report", "usage"))
        assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "report", "waste"))
        assertTrue("report" in registry.visible(ToolProfile.DIRECTOR).map { it.name })
        assertTrue("report" !in registry.visible(ToolProfile.ORCHESTRATION).map { it.name })
        assertTrue("report" !in registry.visible(ToolProfile.KNOWLEDGE).map { it.name })
        val spec = assertNotNull(registry["report"])
        assertNull(McpOperationAdapter.toolFor(spec, ToolProfile.ORCHESTRATION))
        assertNull(McpOperationAdapter.toolFor(spec, ToolProfile.KNOWLEDGE))
        assertNotNull(McpOperationAdapter.toolFor(spec, ToolProfile.DIRECTOR))
        assertEquals(
            listOf("usage", "gates", "contention", "waste"),
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
    fun `S16 describe lists the usage and waste selects with their inputs and no rollup input`() {
        val spec = assertNotNull(registry["report"])
        val described = ToolDescriber.describe(spec, ToolProfile.DIRECTOR, null)
        val operations = described.getValue("operations").jsonArray.map { it.jsonObject }
        assertEquals(listOf("usage", "gates", "contention", "waste"), operations.map { it.getValue("name").jsonPrimitive.content })

        fun inputsOf(select: String) =
            operations
                .single { it.getValue("name").jsonPrimitive.content == select }
                .getValue("parameters")
                .jsonObject
                .getValue("properties")
                .jsonObject.keys
        val properties = inputsOf("usage")
        val wasteInputs = inputsOf("waste")
        assertTrue(wasteInputs.containsAll(setOf("rootId", "since", "until")), "waste inputs: $wasteInputs")
        for (absent in listOf("groupBy", "limit", "cursor")) {
            assertTrue(absent !in wasteInputs, "$absent is not an input of waste: $wasteInputs")
        }
        assertTrue(properties.containsAll(setOf("rootId", "since", "until", "groupBy", "limit", "cursor")), "inputs: $properties")
        for (reserved in listOf("runId", "anchorId", "principalId", "tool", "noteKey", "filters")) {
            assertTrue(reserved !in properties, "$reserved is not an input of usage: $properties")
        }
    }
}

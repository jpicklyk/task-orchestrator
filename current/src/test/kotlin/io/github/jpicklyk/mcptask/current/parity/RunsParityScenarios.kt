package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `runs_read` / `runs_write` slice of [ProductionParity] (item 8df44a22, R20; scenarios S1-S19 of the frozen test-plan): one
 * HAPPY and one SCOPE_FORBIDDEN scenario per select and op (`get`, `list`, `open`, `update`, `close`, `pause`, `resume`, `mark`)
 * plus OTHER scenarios for the failures, boundaries and probes whose status and shape the envelope spec and the task-scope fix.
 * Every scenario sends the SAME arguments over MCP (on the profile whose set contains the member) and over
 * `POST /api/v1/ops/{tool}` against two identically seeded databases; the harness asserts byte-equal bodies (minus `meta.reqId`),
 * the literal REST status, the literal error code and the snapshot parity. `mark` is REST only: its MCP half asserts the member is
 * unknown on every profile that serves the tool.
 *
 * Oracles (never read from a run): P3.2 = plan 3.2 permission table, P3.4c = plan 3.4c profile sets, TS-P = the planner additions
 * of the task-scope (request and view shapes, permission predicates, order of checks, mark rules, limits), R17 = the run state
 * machine the `RunService` tests pin (`invalid_run_state` / `run_closed` / `run_paused` shapes, duplicate items collapse), R5 = the
 * empty-set contract, and the envelope spec for statuses (200, 207 for a failed element, 400 `invalid_request` /
 * `unknown_parameter` / `invalid_cursor`, 403 `forbidden`, 404 `not_found`, 409 `ambiguous_id` / `duplicate` / `run_closed` /
 * `run_paused` / `invalid_run_state` / `version_conflict`).
 *
 * Kind-based refusals of `pause` / `resume` (service or agent token) cannot be harness scenarios: on `/ops/runs_write` the served
 * profile is `rest` and the token is refused, while the MCP leg of the same member runs on the director profile and succeeds, so
 * the two bodies differ by design (D-parity). They are route tests in `RunsDirectorRouteTest`. The pause and resume scenarios here
 * run under a HUMAN token, which passes on both profiles.
 *
 * Fixture (clock fixed at [PARITY_NOW], 2026-10-10T12:00Z): root R1 "Alpha root" (tag alpha) with children C1, C2, C3 (tag alpha);
 * root R2 "Beta root" (tag beta) with child D1. Seeded runs are opened by `api:rw-coord` (kind service).
 */
internal object RunsParityScenarios {
    const val READ_TOOL = "runs_read"
    const val WRITE_TOOL = "runs_write"

    val r1: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val c1: UUID = UUID.fromString("21111111-1111-4111-8111-111111111111")
    val c2: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val c3: UUID = UUID.fromString("23333333-3333-4333-8333-333333333333")
    val r2: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    val d1: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

    /** An open run on R1 over C1 and C2 (opened 11:00). */
    val ra: UUID = UUID.fromString("a0000001-0000-4000-8000-000000000001")

    /** An open run on R2 over D1 (opened 11:30). */
    val rb: UUID = UUID.fromString("b0000002-0000-4000-8000-000000000002")

    /** A manually paused run on R1 over C3 (opened 10:30). */
    val rp: UUID = UUID.fromString("d0000005-0000-4000-8000-000000000005")

    /** A run on R1 paused by its budget (opened 10:30). */
    val rbp: UUID = UUID.fromString("d0000006-0000-4000-8000-000000000006")

    /** A manually paused run on R2 over D1. */
    val rq: UUID = UUID.fromString("d0000007-0000-4000-8000-000000000007")

    /** Closed completed runs on R1: k1 (marked `retro`, closed 10:00), k2 (closed 11:00), k3 and k4 (opened together 08:00, closed 08:30). */
    val k1: UUID = UUID.fromString("c0000001-0000-4000-8000-000000000001")
    val k2: UUID = UUID.fromString("c0000002-0000-4000-8000-000000000002")
    val k3: UUID = UUID.fromString("c0000003-0000-4000-8000-000000000003")
    val k4: UUID = UUID.fromString("c0000004-0000-4000-8000-000000000004")

    /** Two runs whose ids share the 8-hex prefix `ab000000`. */
    val am1: UUID = UUID.fromString("ab000000-0000-4000-8000-000000000001")
    val am2: UUID = UUID.fromString("ab000000-0000-4000-8000-000000000002")

    /** Closed runs holding 31 and 32 marks. */
    val m31: UUID = UUID.fromString("c1000031-0000-4000-8000-000000000031")
    val m32: UUID = UUID.fromString("c1000032-0000-4000-8000-000000000032")

    /** A well-formed run id no run carries. */
    val ghost: UUID = UUID.fromString("99999999-9999-4999-8999-999999999999")

    private fun at(text: String): Instant = Instant.parse(text)

    val coord = ParityToken("rw-coord", setOf(ApiCapability.RUNS, ApiCapability.READ))
    val other = ParityToken("rw-other", setOf(ApiCapability.RUNS, ApiCapability.READ))
    val reader = ParityToken("rw-read", setOf(ApiCapability.READ))
    val runsOnly = ParityToken("rw-runs", setOf(ApiCapability.RUNS))
    val rootScoped = ParityToken("rw-root", setOf(ApiCapability.RUNS, ApiCapability.READ), rootIds = setOf(r1))
    val tagScoped = ParityToken("rw-tag", setOf(ApiCapability.RUNS, ApiCapability.READ), tagsInclude = setOf("alpha"))
    val human = ParityToken("rw-human", setOf(ApiCapability.RUNS, ApiCapability.READ), kind = PrincipalKind.HUMAN)
    val humanRootScoped =
        ParityToken("rw-human-root", setOf(ApiCapability.RUNS, ApiCapability.READ), rootIds = setOf(r1), kind = PrincipalKind.HUMAN)
    val agent = ParityToken("rw-agent", setOf(ApiCapability.RUNS, ApiCapability.READ), kind = PrincipalKind.AGENT)

    /** The identity every seeded run was opened by. */
    private const val OPENER = "api:rw-coord"

    val tree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(c1, "Alpha child one", parent = r1, tags = "alpha")
        item(c2, "Alpha child two", parent = r1, tags = "alpha")
        item(c3, "Alpha child three", parent = r1, tags = "alpha")
        item(r2, "Beta root", tags = "beta")
        item(d1, "Beta child", parent = r2, tags = "beta")
    }

    /** Ids for the 500/501 member boundary: children of R1 that no run holds. */
    private fun bulk(i: Int): UUID = UUID.fromString(String.format("e%07x-0000-4000-8000-%012x", i, i))

    private val bulkTree: suspend ParitySeeder.() -> Unit = {
        tree()
        for (i in 1..501) item(bulk(i), "Bulk child $i", parent = r1)
    }

    private val seedOpenA: suspend ParitySeeder.() -> Unit = {
        tree()
        run(ra, r1, listOf(c1, c2), openedBy = OPENER, openedByKind = "service", openedAt = at("2026-10-10T11:00:00Z"))
    }

    private val seedOpenAB: suspend ParitySeeder.() -> Unit = {
        seedOpenA()
        run(rb, r2, listOf(d1), openedBy = OPENER, openedByKind = "service", openedAt = at("2026-10-10T11:30:00Z"))
    }

    private val seedPaused: suspend ParitySeeder.() -> Unit = {
        tree()
        run(
            rp,
            r1,
            listOf(c3),
            status = RunStatus.PAUSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T10:30:00Z")
        )
    }

    private val seedPausedOtherRoot: suspend ParitySeeder.() -> Unit = {
        tree()
        run(
            rq,
            r2,
            listOf(d1),
            status = RunStatus.PAUSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T10:30:00Z")
        )
    }

    private val seedBudgetPaused: suspend ParitySeeder.() -> Unit = {
        tree()
        run(
            rbp,
            r1,
            status = RunStatus.PAUSED,
            pauseReason = RunPauseReason.BUDGET,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T10:30:00Z")
        )
    }

    private val seedClosed: suspend ParitySeeder.() -> Unit = {
        tree()
        run(
            k2,
            r1,
            status = RunStatus.CLOSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T09:30:00Z"),
            closedAt = at("2026-10-10T11:00:00Z")
        )
    }

    private suspend fun ParitySeeder.closedRun(
        id: UUID,
        opened: String,
        closed: String,
        marks: Map<String, Instant> = emptyMap()
    ) {
        run(
            id,
            r1,
            status = RunStatus.CLOSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at(opened),
            closedAt = at(closed),
            marks = marks
        )
    }

    /** Every list-relevant shape: two open runs, a manual pause, four closed runs (one marked `retro`). */
    val listSeed: suspend ParitySeeder.() -> Unit = {
        seedOpenAB()
        run(
            rp,
            r1,
            listOf(c3),
            status = RunStatus.PAUSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T10:30:00Z")
        )
        closedRun(k1, "2026-10-10T09:00:00Z", "2026-10-10T10:00:00Z", mapOf("retro" to at("2026-10-10T10:30:00Z")))
        closedRun(k2, "2026-10-10T09:30:00Z", "2026-10-10T11:00:00Z")
        closedRun(k3, "2026-10-10T08:00:00Z", "2026-10-10T08:30:00Z")
        closedRun(k4, "2026-10-10T08:00:00Z", "2026-10-10T08:30:00Z")
    }

    private val seedMarks: suspend ParitySeeder.() -> Unit = {
        seedOpenAB()
        run(
            rp,
            r1,
            listOf(c3),
            status = RunStatus.PAUSED,
            openedBy = OPENER,
            openedByKind = "service",
            openedAt = at("2026-10-10T10:30:00Z")
        )
        closedRun(k1, "2026-10-10T09:00:00Z", "2026-10-10T10:00:00Z", mapOf("retro" to at("2026-10-10T10:30:00Z")))
        closedRun(k2, "2026-10-10T09:30:00Z", "2026-10-10T11:00:00Z")
        closedRun(
            m31,
            "2026-10-10T09:00:00Z",
            "2026-10-10T10:00:00Z",
            (0 until 31).associate {
                "m%02d".format(it) to
                    at("2026-10-10T10:30:00Z")
            }
        )
        closedRun(
            m32,
            "2026-10-10T09:00:00Z",
            "2026-10-10T10:00:00Z",
            (0 until 32).associate {
                "m%02d".format(it) to
                    at("2026-10-10T10:30:00Z")
            }
        )
    }

    private val seedAmbiguous: suspend ParitySeeder.() -> Unit = {
        tree()
        run(am1, r1, openedBy = OPENER, openedByKind = "service")
        run(am2, r1, openedBy = OPENER, openedByKind = "service")
    }

    // ------------------------------------------------------------------ request builders

    fun get(
        runId: Any,
        extra: String = ""
    ) = """{"select":"get","runId":"$runId"$extra}"""

    fun list(extra: String = "") = """{"select":"list"$extra}"""

    fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    fun open(
        root: Any,
        extra: String = ""
    ) = """{"op":"open","rootId":"$root"$extra}"""

    fun update(
        runId: Any,
        extra: String = ""
    ) = """{"op":"update","runId":"$runId"$extra}"""

    fun close(
        runId: Any,
        reason: String,
        extra: String = ""
    ) = """{"op":"close","runId":"$runId","reason":"$reason"$extra}"""

    fun pause(runId: Any) = """{"op":"pause","runId":"$runId"}"""

    fun resume(runId: Any) = """{"op":"resume","runId":"$runId"}"""

    fun mark(
        runId: Any,
        key: String,
        extra: String = ""
    ) = """{"op":"mark","runId":"$runId","key":"$key"$extra}"""

    private fun ids(vararg ids: Any) = ids.joinToString(",", "[", "]") { "\"$it\"" }

    private fun scopeRoot(required: UUID = r1) = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$required\"")

    private fun principalKind(required: String) =
        listOf("\"code\":\"forbidden\"", "\"scope\":\"principal_kind\"", "\"required\":\"$required\"")

    private fun key(
        prefix: String,
        total: Int
    ) = prefix + "a".repeat(total - prefix.length)

    // ------------------------------------------------------------------ scenario builders

    private fun sc(
        name: String,
        tool: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        status: Int,
        code: String? = null,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        dbChanged: Boolean? = null,
        seed: suspend ParitySeeder.() -> Unit
    ) = ParityScenario("runs $name", tool, member, kind, token, arguments, status, code, contains, absent, dbChanged, seed)

    private fun read(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        status: Int = 200,
        code: String? = null,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        seed: suspend ParitySeeder.() -> Unit = listSeed
    ) = sc(name, READ_TOOL, member, kind, token, arguments, status, code, contains, absent, dbChanged = false, seed = seed)

    /** A write that succeeds (200) and changes the database. */
    private fun wrote(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        contains: List<String>,
        absent: List<String> = emptyList(),
        seed: suspend ParitySeeder.() -> Unit
    ) = sc(name, WRITE_TOOL, member, kind, token, arguments, 200, null, contains, absent, dbChanged = true, seed = seed)

    /** A write whose single element fails (207 `partial_failure`, the element carries the code) and changes nothing. */
    private fun failed(
        name: String,
        member: String,
        token: ParityToken,
        arguments: String,
        contains: List<String>,
        absent: List<String> = emptyList(),
        kind: ParityKind = ParityKind.OTHER,
        seed: suspend ParitySeeder.() -> Unit
    ) = sc(name, WRITE_TOOL, member, kind, token, arguments, 207, "partial_failure", contains, absent, dbChanged = false, seed = seed)

    // ------------------------------------------------------------------ HAPPY and SCOPE_FORBIDDEN, one pair per member

    val happy: List<ParityScenario> =
        listOf(
            read(
                "get happy",
                "get",
                ParityKind.HAPPY,
                reader,
                get(ra),
                contains =
                    listOf(
                        "\"runId\":\"$ra\"",
                        "\"rootId\":\"$r1\"",
                        "\"status\":\"open\"",
                        "\"openedBy\":\"api:rw-coord\"",
                        "\"openedByKind\":\"service\"",
                        "\"members\":2",
                        "\"version\":1"
                    ),
                absent = listOf("\"closedAt\"", "\"terminalReason\"", "\"pauseReason\"", ":null")
            ),
            read(
                "get scope root",
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                get(rb),
                403,
                "forbidden",
                scopeRoot(),
                absent = listOf("not_found", "Beta"),
                seed = seedOpenAB
            ),
            read(
                "list happy",
                "list",
                ParityKind.HAPPY,
                reader,
                list(),
                contains = listOf("\"runId\":\"$ra\"", "\"runId\":\"$rb\"", "\"runId\":\"$k1\"", "\"runId\":\"$rp\"", "\"items\":[")
            ),
            read(
                "list filtered by root scope",
                "list",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                list(),
                contains = listOf("\"runId\":\"$ra\"", "\"runId\":\"$k1\"", "\"runId\":\"$rp\""),
                absent = listOf("\"runId\":\"$rb\"")
            ),
            wrote(
                "open happy",
                "open",
                ParityKind.HAPPY,
                coord,
                ops(open(r1, ""","items":${ids(c1, c2)}""")),
                contains =
                    listOf(
                        "\"rootId\":\"$r1\"",
                        "\"status\":\"open\"",
                        "\"members\":2",
                        "\"version\":1",
                        "\"openedBy\":\"api:rw-coord\"",
                        "\"openedByKind\":\"service\""
                    ),
                seed = tree
            ),
            failed(
                "open scope root",
                "open",
                rootScoped,
                ops(open(r2)),
                scopeRoot(),
                absent = listOf("not_found"),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = tree
            ),
            wrote(
                "update happy",
                "update",
                ParityKind.HAPPY,
                coord,
                ops(update(ra, ""","addItems":${ids(c3)},"hints":{"phase":"two"}""")),
                contains = listOf("\"version\":2", "\"members\":3", "\"hints\":{\"phase\":\"two\"}", "\"status\":\"open\""),
                seed = seedOpenA
            ),
            failed(
                "update scope root",
                "update",
                rootScoped,
                ops(update(rb, ""","hints":{"x":"y"}""")),
                scopeRoot(),
                absent = listOf("principal_kind"),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = seedOpenAB
            ),
            wrote(
                "close happy by the coordinator",
                "close",
                ParityKind.HAPPY,
                coord,
                ops(close(ra, "cancelled")),
                contains =
                    listOf(
                        "\"status\":\"closed\"",
                        "\"terminalReason\":\"cancelled\"",
                        "\"closedBy\":\"api:rw-coord\"",
                        "\"snapshot\":{",
                        "\"reason\":\"cancelled\"",
                        "\"released\":{\"claims\":0,\"leases\":0,\"seats\":0}",
                        "\"openDecisions\":0"
                    ),
                seed = seedOpenA
            ),
            failed(
                "close scope root",
                "close",
                rootScoped,
                ops(close(rb, "cancelled")),
                scopeRoot(),
                absent = listOf("principal_kind"),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = seedOpenAB
            ),
            wrote(
                "pause happy by a human",
                "pause",
                ParityKind.HAPPY,
                human,
                ops(pause(ra)),
                contains = listOf("\"status\":\"paused\"", "\"pauseReason\":\"manual\"", "\"version\":2"),
                seed = seedOpenA
            ),
            failed(
                "pause scope root, a non-human is refused on the scope first",
                "pause",
                rootScoped,
                ops(pause(rb)),
                scopeRoot(),
                absent = listOf("principal_kind"),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = seedOpenAB
            ),
            wrote(
                "resume happy by a human",
                "resume",
                ParityKind.HAPPY,
                human,
                ops(resume(rp)),
                contains = listOf("\"status\":\"open\"", "\"version\":2"),
                absent = listOf("\"pauseReason\""),
                seed = seedPaused
            ),
            failed(
                "resume scope root, a human of root R1 cannot resume a run of R2",
                "resume",
                humanRootScoped,
                ops(resume(rq)),
                scopeRoot(),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = seedPausedOtherRoot
            ),
            wrote(
                "mark happy on a closed run",
                "mark",
                ParityKind.HAPPY,
                other,
                ops(mark(k2, "retro")),
                contains = listOf("\"marked\":true", "\"marks\":{\"retro\":\"2026-10-10T12:00:00Z\"}", "\"status\":\"closed\""),
                seed = seedMarks
            ),
            failed(
                "mark scope root",
                "mark",
                rootScoped,
                ops(mark(rb, "retro")),
                scopeRoot(),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = seedMarks
            )
        )

    // ------------------------------------------------------------------ OTHER: reads

    val reads: List<ParityScenario> =
        listOf(
            read(
                "get by an 8-hex prefix",
                "get",
                ParityKind.OTHER,
                reader,
                get(ra.toString().substring(0, 8)),
                contains = listOf("\"runId\":\"$ra\"")
            ),
            read(
                "get by a 32-hex id without dashes",
                "get",
                ParityKind.OTHER,
                reader,
                get(ra.toString().replace("-", "")),
                contains = listOf("\"runId\":\"$ra\"")
            ),
            read(
                "get scope tag",
                "get",
                ParityKind.OTHER,
                tagScoped,
                get(rb),
                403,
                "forbidden",
                listOf("\"scope\":\"tag\"", "\"required\":\"alpha\""),
                absent = listOf("not_found"),
                seed = seedOpenAB
            ),
            read("get unknown uuid", "get", ParityKind.OTHER, reader, get(ghost), 404, "not_found", listOf("\"kind\":\"run\"")),
            read(
                "get shared prefix is ambiguous",
                "get",
                ParityKind.OTHER,
                reader,
                get("ab000000"),
                409,
                "ambiguous_id",
                seed = seedAmbiguous
            ),
            read(
                "get with a 7-character prefix",
                "get",
                ParityKind.OTHER,
                reader,
                get("ab00000"),
                400,
                "invalid_request",
                listOf("/runId"),
                seed = seedAmbiguous
            ),
            read(
                "get with a 33-character hex id",
                "get",
                ParityKind.OTHER,
                reader,
                get("a".repeat(33)),
                400,
                "invalid_request",
                listOf("/runId")
            ),
            read(
                "get with a non-hex 8-character id",
                "get",
                ParityKind.OTHER,
                reader,
                get("zzzzzzzz"),
                400,
                "invalid_request",
                listOf("/runId")
            ),
            read(
                "get with an unknown field",
                "get",
                ParityKind.OTHER,
                reader,
                get(ra, ""","bogus":1"""),
                400,
                "unknown_parameter",
                listOf("/bogus")
            ),
            read(
                "get without the read capability",
                "get",
                ParityKind.OTHER,
                runsOnly,
                get(ra),
                403,
                "forbidden",
                listOf("\"scope\":\"capability\"", "\"required\":\"read\""),
                absent = listOf("api:rw-coord")
            ),
            read(
                "list without the read capability",
                "list",
                ParityKind.OTHER,
                runsOnly,
                list(),
                403,
                "forbidden",
                listOf("\"scope\":\"capability\"", "\"required\":\"read\""),
                absent = listOf("api:rw-coord")
            ),
            read(
                "list filtered by tag scope",
                "list",
                ParityKind.OTHER,
                tagScoped,
                list(),
                contains = listOf("\"runId\":\"$ra\"", "\"runId\":\"$k1\""),
                absent = listOf("\"runId\":\"$rb\"")
            ),
            read(
                "list status closed",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","status":"closed""""),
                contains = listOf("\"runId\":\"$k1\"", "\"runId\":\"$k2\"", "\"runId\":\"$k3\"", "\"runId\":\"$k4\""),
                absent = listOf("\"runId\":\"$ra\"", "\"runId\":\"$rb\"", "\"runId\":\"$rp\"")
            ),
            read(
                "list status open",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","status":"open""""),
                contains = listOf("\"runId\":\"$ra\"", "\"runId\":\"$rb\""),
                absent = listOf("\"runId\":\"$rp\"", "\"runId\":\"$k1\"")
            ),
            read(
                "list status paused",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","status":"paused""""),
                contains = listOf("\"runId\":\"$rp\""),
                absent = listOf("\"runId\":\"$ra\"", "\"runId\":\"$k1\"")
            ),
            read(
                "list rootId",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","rootId":"$r2""""),
                contains = listOf("\"runId\":\"$rb\""),
                absent = listOf("\"runId\":\"$ra\"", "\"runId\":\"$k1\"")
            ),
            read(
                "list endedSince is inclusive at closedAt",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","endedSince":"2026-10-10T11:00:00Z""""),
                contains = listOf("\"runId\":\"$k2\""),
                absent = listOf("\"runId\":\"$k1\"", "\"runId\":\"$k3\"", "\"runId\":\"$ra\"", "\"runId\":\"$rb\"")
            ),
            read(
                "list endedSince one millisecond after closedAt excludes it",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","endedSince":"2026-10-10T11:00:00.001Z""""),
                contains = listOf("\"items\":[]"),
                absent = listOf("\"runId\":")
            ),
            read(
                "list endedSince one millisecond before closedAt includes it",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","endedSince":"2026-10-10T10:59:59.999Z""""),
                contains = listOf("\"runId\":\"$k2\""),
                absent = listOf("\"runId\":\"$k1\"")
            ),
            read(
                "list endedSince at an earlier closedAt keeps both",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","endedSince":"2026-10-10T10:00:00Z""""),
                contains = listOf("\"runId\":\"$k1\"", "\"runId\":\"$k2\""),
                absent = listOf("\"runId\":\"$k3\"", "\"runId\":\"$k4\"")
            ),
            read(
                "list unmarked retro keeps only closed runs without that mark",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","unmarked":"retro""""),
                contains = listOf("\"runId\":\"$k2\"", "\"runId\":\"$k3\"", "\"runId\":\"$k4\""),
                absent = listOf("\"runId\":\"$k1\"", "\"runId\":\"$ra\"", "\"runId\":\"$rb\"", "\"runId\":\"$rp\"")
            ),
            read(
                "list unmarked of a key nobody holds keeps every closed run and no other",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","unmarked":"audit""""),
                contains = listOf("\"runId\":\"$k1\"", "\"runId\":\"$k2\"", "\"runId\":\"$k3\"", "\"runId\":\"$k4\""),
                absent = listOf("\"runId\":\"$ra\"", "\"runId\":\"$rb\"", "\"runId\":\"$rp\"")
            ),
            read(
                "list unmarked with status closed",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","unmarked":"retro","status":"closed""""),
                contains = listOf("\"runId\":\"$k2\""),
                absent = listOf("\"runId\":\"$k1\"")
            ),
            read(
                "list unmarked with status open",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","unmarked":"retro","status":"open""""),
                400,
                "invalid_request",
                listOf("/unmarked")
            ),
            read(
                "list unmarked with status paused",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","unmarked":"retro","status":"paused""""),
                400,
                "invalid_request",
                listOf("/unmarked")
            ),
            read(
                "list unmarked under a root scope never leaks another root",
                "list",
                ParityKind.OTHER,
                rootScoped,
                list(""","unmarked":"retro","status":"closed""""),
                contains = listOf("\"runId\":\"$k2\""),
                absent = listOf("\"runId\":\"$k1\"", "\"runId\":\"$rb\"")
            ),
            read("list limit zero", "list", ParityKind.OTHER, reader, list(""","limit":0"""), 400, "invalid_request", listOf("limit")),
            read(
                "list limit above the maximum",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","limit":201"""),
                400,
                "invalid_request",
                listOf("limit")
            ),
            read(
                "list limit at the maximum",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","limit":200"""),
                contains = listOf("\"runId\":\"$ra\"")
            ),
            read(
                "list with an undecodable cursor",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","cursor":"not-a-cursor!""""),
                400,
                "invalid_cursor"
            ),
            read(
                "list with an unknown field",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","bogus":1"""),
                400,
                "unknown_parameter",
                listOf("/bogus")
            ),
            read(
                "list endedSince that is not a date",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","endedSince":"yesterday""""),
                400,
                "invalid_request",
                listOf("/endedSince")
            ),
            read(
                "list status that is not a status",
                "list",
                ParityKind.OTHER,
                reader,
                list(""","status":"terminal""""),
                400,
                "invalid_request",
                listOf("/status")
            )
        )

    // ------------------------------------------------------------------ OTHER: writes

    val writes: List<ParityScenario> =
        listOf(
            wrote(
                "open with every field",
                "open",
                ParityKind.OTHER,
                coord,
                ops(
                    open(
                        r1,
                        ""","items":${ids(c1)},"label":"nightly","title":"Nightly wave",""" +
                            """"budget":{"tokens":1000,"costUsd":1.5,"attemptsPerSeat":3,"maxActiveSeats":2},""" +
                            """"stallAfterSeconds":120,"hints":{"owner":"qa"}"""
                    )
                ),
                contains =
                    listOf(
                        "\"label\":\"nightly\"",
                        "\"title\":\"Nightly wave\"",
                        "\"tokens\":1000",
                        "\"costUsd\":1.5",
                        "\"attemptsPerSeat\":3",
                        "\"maxActiveSeats\":2",
                        "\"stallAfterSeconds\":120",
                        "\"hints\":{\"owner\":\"qa\"}",
                        "\"members\":1"
                    ),
                absent = listOf("costMicroUsd", "1500000"),
                seed = tree
            ),
            wrote(
                "open with no items is an empty run",
                "open",
                ParityKind.OTHER,
                coord,
                ops(open(r1)),
                contains = listOf("\"members\":0", "\"status\":\"open\"", "\"hints\":{}", "\"marks\":{}"),
                seed = tree
            ),
            wrote(
                "open collapses a repeated item to one membership",
                "open",
                ParityKind.OTHER,
                coord,
                ops(open(r1, ""","items":${ids(c1, c1)}""")),
                contains = listOf("\"members\":1"),
                seed = tree
            ),
            wrote(
                "open by a human records the human kind",
                "open",
                ParityKind.OTHER,
                human,
                ops(open(r1)),
                contains = listOf("\"openedBy\":\"api:rw-human\"", "\"openedByKind\":\"human\""),
                seed = tree
            ),
            wrote(
                "open by an agent token records the agent kind",
                "open",
                ParityKind.OTHER,
                agent,
                ops(open(r1)),
                contains = listOf("\"openedBy\":\"api:rw-agent\"", "\"openedByKind\":\"agent\""),
                seed = tree
            ),
            wrote(
                "open with a label of 64 characters",
                "open",
                ParityKind.OTHER,
                coord,
                ops(open(r1, ""","label":"${"l".repeat(64)}"""")),
                contains = listOf("\"label\":\"${"l".repeat(64)}\""),
                seed = tree
            ),
            failed(
                "open with a label of 65 characters",
                "open",
                coord,
                ops(open(r1, ""","label":"${"l".repeat(65)}"""")),
                listOf("\"code\":\"invalid_request\"", "/label"),
                seed = tree
            ),
            wrote(
                "open with a title of 200 characters",
                "open",
                ParityKind.OTHER,
                coord,
                ops(open(r1, ""","title":"${"t".repeat(200)}"""")),
                contains = listOf("\"title\":\"${"t".repeat(200)}\""),
                seed = tree
            ),
            failed(
                "open with a title of 201 characters",
                "open",
                coord,
                ops(open(r1, ""","title":"${"t".repeat(201)}"""")),
                listOf("\"code\":\"invalid_request\"", "/title"),
                seed = tree
            ),
            wrote(
                "open with 500 items",
                "open",
                ParityKind.OTHER,
                coord,
                ops(open(r1, ""","items":${ids(*(1..500).map { bulk(it) }.toTypedArray())}""")),
                contains = listOf("\"members\":500"),
                seed = bulkTree
            ),
            failed(
                "open with 501 items",
                "open",
                coord,
                ops(open(r1, ""","items":${ids(*(1..501).map { bulk(it) }.toTypedArray())}""")),
                listOf("\"code\":\"invalid_request\"", "/items"),
                seed = bulkTree
            ),
            failed(
                "open with an item of another root",
                "open",
                coord,
                ops(open(r1, ""","items":${ids(c1, d1)}""")),
                listOf("\"code\":\"invalid_request\"", "/items/1"),
                seed = tree
            ),
            failed(
                "open with an item already in an open run",
                "open",
                coord,
                ops(open(r1, ""","items":${ids(c1)}""")),
                listOf("\"code\":\"duplicate\""),
                seed = seedOpenA
            ),
            failed(
                "open without the runs capability",
                "open",
                reader,
                ops(open(r1)),
                listOf("\"code\":\"forbidden\"", "\"scope\":\"capability\"", "\"required\":\"runs\""),
                seed = tree
            ),
            failed(
                "open scope tag",
                "open",
                tagScoped,
                ops(open(r2)),
                listOf("\"code\":\"forbidden\"", "\"scope\":\"tag\"", "\"required\":\"alpha\""),
                seed = tree
            ),
            failed(
                "open with an unknown field",
                "open",
                coord,
                ops(open(r1, ""","bogus":1""")),
                listOf("\"code\":\"unknown_parameter\"", "/bogus"),
                seed = tree
            ),
            failed(
                "open with a field of another op",
                "open",
                coord,
                ops(open(r1, ""","runId":"$ra"""")),
                listOf("\"code\":\"unknown_parameter\""),
                seed = tree
            ),
            failed(
                "open without a root",
                "open",
                coord,
                ops("""{"op":"open"}"""),
                listOf("\"code\":\"invalid_request\"", "/rootId"),
                seed = tree
            ),
            sc(
                "open atomic with a forbidden second element rolls the first back",
                WRITE_TOOL,
                "open",
                ParityKind.OTHER,
                rootScoped,
                ops(open(r1, ""","items":${ids(c1)}"""), open(r2), top = ""","atomic":true"""),
                403,
                "forbidden",
                scopeRoot(),
                dbChanged = false,
                seed = tree
            ),
            failed(
                "update by a human is refused, only the coordinator updates",
                "update",
                human,
                ops(update(ra, ""","hints":{"x":"y"}""")),
                principalKind("coordinator"),
                seed = seedOpenA
            ),
            failed(
                "update by another service token is refused",
                "update",
                other,
                ops(update(ra, ""","hints":{"x":"y"}""")),
                principalKind("coordinator"),
                seed = seedOpenA
            ),
            failed(
                "update of a paused run is run_paused",
                "update",
                coord,
                ops(update(rp, ""","hints":{"x":"y"}""")),
                listOf("\"code\":\"run_paused\"", "\"pauseReason\":\"manual\""),
                seed = seedPaused
            ),
            failed(
                "update of a closed run is run_closed",
                "update",
                coord,
                ops(update(k2, ""","hints":{"x":"y"}""")),
                listOf("\"code\":\"run_closed\"", "\"terminalReason\":\"completed\""),
                seed = seedClosed
            ),
            failed(
                "update with a stale expectedVersion is version_conflict",
                "update",
                coord,
                ops(update(ra, ""","hints":{"x":"y"},"expectedVersion":5""")),
                listOf("\"code\":\"version_conflict\"", "\"actual\":1"),
                seed = seedOpenA
            ),
            wrote(
                "update with the matching expectedVersion",
                "update",
                ParityKind.OTHER,
                coord,
                ops(update(ra, ""","hints":{"x":"y"},"expectedVersion":1""")),
                contains = listOf("\"version\":2", "\"hints\":{\"x\":\"y\"}"),
                seed = seedOpenA
            ),
            wrote(
                "update removes a member",
                "update",
                ParityKind.OTHER,
                coord,
                ops(update(ra, ""","removeItems":${ids(c2)}""")),
                contains = listOf("\"members\":1", "\"version\":2"),
                seed = seedOpenA
            ),
            failed(
                "update adding an item of another root",
                "update",
                coord,
                ops(update(ra, ""","addItems":${ids(d1)}""")),
                listOf("\"code\":\"invalid_request\"", "/addItems/0"),
                seed = seedOpenA
            ),
            failed(
                "update of an unknown run is not_found",
                "update",
                coord,
                ops(update(ghost, ""","hints":{"x":"y"}""")),
                listOf("\"code\":\"not_found\"", "\"kind\":\"run\""),
                seed = tree
            ),
            failed(
                "update with a field of another op",
                "update",
                coord,
                ops(update(ra, ""","reason":"cancelled"""")),
                listOf("\"code\":\"unknown_parameter\""),
                seed = seedOpenA
            ),
            wrote(
                "close completed by a human who is not the coordinator",
                "close",
                ParityKind.OTHER,
                human,
                ops(close(ra, "completed")),
                contains = listOf("\"status\":\"closed\"", "\"terminalReason\":\"completed\"", "\"closedBy\":\"api:rw-human\""),
                seed = seedOpenA
            ),
            wrote(
                "close failed by the coordinator",
                "close",
                ParityKind.OTHER,
                coord,
                ops(close(ra, "failed")),
                contains = listOf("\"terminalReason\":\"failed\""),
                seed = seedOpenA
            ),
            wrote(
                "close abandoned by the coordinator",
                "close",
                ParityKind.OTHER,
                coord,
                ops(close(ra, "abandoned")),
                contains = listOf("\"terminalReason\":\"abandoned\""),
                seed = seedOpenA
            ),
            wrote(
                "close a paused run",
                "close",
                ParityKind.OTHER,
                human,
                ops(close(rp, "cancelled")),
                contains = listOf("\"status\":\"closed\"", "\"terminalReason\":\"cancelled\""),
                seed = seedPaused
            ),
            failed(
                "close by another service token is refused and the run stays open",
                "close",
                other,
                ops(close(ra, "cancelled")),
                principalKind("coordinator,director,human"),
                seed = seedOpenA
            ),
            failed(
                "close cancelled by an agent token of another principal is refused",
                "close",
                agent,
                ops(close(ra, "cancelled")),
                principalKind("coordinator,director,human"),
                seed = seedOpenA
            ),
            sc(
                "close by a body actor is unknown_parameter on /actor and stores nothing",
                WRITE_TOOL,
                "close",
                ParityKind.OTHER,
                other,
                ops(close(ra, "cancelled"), top = ""","actor":{"id":"api:rw-coord","kind":"subagent"}"""),
                400,
                "unknown_parameter",
                listOf("/actor"),
                dbChanged = false,
                seed = seedOpenA
            ),
            failed(
                "close of a closed run by a non-coordinator is refused on the kind before the state",
                "close",
                other,
                ops(close(k2, "cancelled")),
                principalKind("coordinator,director,human"),
                absent = listOf("run_closed"),
                seed = seedClosed
            ),
            failed(
                "close with the server-only reason budget_exhausted",
                "close",
                coord,
                ops(close(ra, "budget_exhausted")),
                listOf("\"code\":\"invalid_request\"", "/reason"),
                seed = seedOpenA
            ),
            failed(
                "close with an unknown reason",
                "close",
                coord,
                ops(close(ra, "bogus")),
                listOf("\"code\":\"invalid_request\"", "/reason"),
                seed = seedOpenA
            ),
            failed(
                "close without a reason",
                "close",
                coord,
                ops("""{"op":"close","runId":"$ra"}"""),
                listOf("\"code\":\"invalid_request\"", "/reason"),
                seed = seedOpenA
            ),
            failed(
                "close with a summary field is unknown",
                "close",
                coord,
                ops(close(ra, "completed", ""","summary":"done"""")),
                listOf("\"code\":\"unknown_parameter\"", "/summary"),
                seed = seedOpenA
            ),
            failed(
                "close of a closed run is run_closed",
                "close",
                coord,
                ops(close(k2, "cancelled")),
                listOf("\"code\":\"run_closed\"", "\"terminalReason\":\"completed\""),
                seed = seedClosed
            ),
            failed(
                "close of an unknown run is not_found",
                "close",
                coord,
                ops(close(ghost, "cancelled")),
                listOf("\"code\":\"not_found\""),
                seed = seedOpenA
            ),
            failed(
                "pause of a paused run is invalid_run_state",
                "pause",
                human,
                ops(pause(rp)),
                listOf("\"code\":\"invalid_run_state\"", "\"status\":\"paused\""),
                seed = seedPaused
            ),
            failed(
                "pause of a closed run is run_closed",
                "pause",
                human,
                ops(pause(k2)),
                listOf("\"code\":\"run_closed\""),
                seed = seedClosed
            ),
            failed(
                "resume of an open run is invalid_run_state allowing pause",
                "resume",
                human,
                ops(resume(ra)),
                listOf("\"code\":\"invalid_run_state\"", "\"status\":\"open\"", "pause"),
                seed = seedOpenA
            ),
            failed(
                "resume of a budget pause is invalid_run_state allowing only close",
                "resume",
                human,
                ops(resume(rbp)),
                listOf("\"code\":\"invalid_run_state\"", "\"status\":\"paused\"", "\"allowed\":[\"close\"]"),
                seed = seedBudgetPaused
            ),
            failed(
                "pause with a field of another op",
                "pause",
                human,
                ops("""{"op":"pause","runId":"$ra","reason":"cancelled"}"""),
                listOf("\"code\":\"unknown_parameter\""),
                seed = seedOpenA
            ),
            failed(
                "pause scope root, a root-scoped human is refused on the scope of the other root",
                "pause",
                humanRootScoped,
                ops(pause(rb)),
                scopeRoot(),
                seed = seedOpenAB
            ),
            wrote(
                "mark on an open run",
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(ra, "seen")),
                contains = listOf("\"marked\":true", "\"marks\":{\"seen\":\"2026-10-10T12:00:00Z\"}", "\"status\":\"open\""),
                seed = seedMarks
            ),
            wrote(
                "mark on a paused run",
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(rp, "seen")),
                contains = listOf("\"marked\":true", "\"status\":\"paused\""),
                seed = seedMarks
            ),
            sc(
                "mark replay returns the first instant and writes nothing",
                WRITE_TOOL,
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(k1, "retro")),
                200,
                null,
                listOf("\"marked\":false", "\"marks\":{\"retro\":\"2026-10-10T10:30:00Z\"}"),
                dbChanged = false,
                seed = seedMarks
            ),
            wrote(
                "mark with a key of 64 characters",
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(k2, key("k", 64))),
                contains = listOf("\"marked\":true", "\"${key("k", 64)}\":"),
                seed = seedMarks
            ),
            wrote(
                "mark with a one-character key and separators",
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(k2, "a"), mark(k2, "r-e_t9")),
                contains = listOf("\"marked\":true", "\"a\":", "\"r-e_t9\":"),
                seed = seedMarks
            ),
            failed(
                "mark with a capital letter in the key",
                "mark",
                other,
                ops(mark(k2, "Retro")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark with an empty key",
                "mark",
                other,
                ops(mark(k2, "")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark with a key of 65 characters",
                "mark",
                other,
                ops(mark(k2, key("k", 65))),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark with a key that starts with a digit",
                "mark",
                other,
                ops(mark(k2, "1abc")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark with a space in the key",
                "mark",
                other,
                ops(mark(k2, "a b")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark with a key that starts with a separator",
                "mark",
                other,
                ops(mark(k2, "-abc")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            wrote(
                "mark that is the 32nd mark of a run",
                "mark",
                ParityKind.OTHER,
                other,
                ops(mark(m31, "last")),
                contains = listOf("\"marked\":true", "\"last\":"),
                seed = seedMarks
            ),
            failed(
                "mark that would be the 33rd mark of a run",
                "mark",
                other,
                ops(mark(m32, "extra")),
                listOf("\"code\":\"invalid_request\"", "/key"),
                seed = seedMarks
            ),
            failed(
                "mark of an unknown run is not_found",
                "mark",
                other,
                ops(mark(ghost, "retro")),
                listOf("\"code\":\"not_found\"", "\"kind\":\"run\""),
                seed = seedMarks
            ),
            failed(
                "mark through a shared prefix is ambiguous",
                "mark",
                other,
                ops(mark("ab000000", "retro")),
                listOf("\"code\":\"ambiguous_id\""),
                seed = seedAmbiguous
            ),
            failed(
                "mark without the runs capability",
                "mark",
                reader,
                ops(mark(k2, "retro")),
                listOf("\"code\":\"forbidden\"", "\"scope\":\"capability\"", "\"required\":\"runs\""),
                seed = seedMarks
            ),
            failed(
                "mark scope tag",
                "mark",
                tagScoped,
                ops(mark(rb, "retro")),
                listOf("\"code\":\"forbidden\"", "\"scope\":\"tag\"", "\"required\":\"alpha\""),
                seed = seedMarks
            ),
            failed(
                "mark with a field of another op",
                "mark",
                other,
                ops(mark(k2, "retro", ""","reason":"cancelled"""")),
                listOf("\"code\":\"unknown_parameter\""),
                seed = seedMarks
            )
        )

    val scenarios: List<ParityScenario> = happy + reads + writes

    /** Every runs member touches a run that belongs to a root, so each carries a SCOPE_FORBIDDEN scenario and none is exempt. */
    val exemptions: List<ScopeExempt> = emptyList()

    val members: List<Pair<String, String>> =
        listOf(
            READ_TOOL to "get",
            READ_TOOL to "list",
            WRITE_TOOL to "open",
            WRITE_TOOL to "update",
            WRITE_TOOL to "close",
            WRITE_TOOL to "pause",
            WRITE_TOOL to "resume",
            WRITE_TOOL to "mark"
        )
}

/**
 * Independent tests (test-author seat, item 8df44a22, R20) for `runs_read` / `runs_write`: every scenario of
 * [RunsParityScenarios] runs through [ParityHarness]; the tests below read the outcome of selected runs for the facts the
 * catalog's substring checks cannot state (event rows, list order and paging, stored row counts). Oracles are the same as the
 * catalog's (plan 3.2 / 3.4c, the task-scope planner additions, the R17 service rules); generated ids reach this file as
 * positional tokens (`<ID1>`) because the harness normalizes them per leg.
 *
 * Domain invariants satisfied by construction: seeded runs are built through `ParitySeeder.run`, which derives the pause reason,
 * terminal reason and close instants from the status, so a fixture cannot name an inconsistent combination (every closed run
 * is opened before it is closed); members are children of the run's own root and no item sits in two not-closed runs.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsParityTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val harness = ParityHarness(registry)

    private fun named(name: String): ParityScenario = RunsParityScenarios.scenarios.single { it.name == "runs $name" }

    private fun data(o: ParityOutcome): JsonObject =
        Json
            .parseToJsonElement(o.body)
            .jsonObject
            .getValue("data")
            .jsonObject

    private fun events(
        o: ParityOutcome,
        type: String
    ): List<Map<String, Any?>> = o.snapshot.getValue("events").filter { it["type"] == type }

    private fun runEvents(o: ParityOutcome): List<Map<String, Any?>> =
        o.snapshot.getValue("events").filter { (it["type"] as String).startsWith("run.") }

    private fun runIds(o: ParityOutcome): List<String> =
        data(o).getValue("items").jsonArray.map {
            it.jsonObject
                .getValue("runId")
                .jsonPrimitive.content
        }

    private fun listPage(
        extra: String,
        label: String
    ): ParityOutcome =
        harness.run(
            ParityScenario(
                name = "runs paging $label",
                tool = RunsParityScenarios.READ_TOOL,
                member = "list",
                kind = ParityKind.OTHER,
                token = RunsParityScenarios.reader,
                arguments = RunsParityScenarios.list(extra),
                expectStatus = 200,
                expectDbChanged = false,
                seed = RunsParityScenarios.listSeed
            )
        )

    private val newestFirst: List<String> =
        listOf(
            RunsParityScenarios.rb,
            RunsParityScenarios.ra,
            RunsParityScenarios.rp,
            RunsParityScenarios.k2,
            RunsParityScenarios.k1,
            RunsParityScenarios.k3,
            RunsParityScenarios.k4
        ).map { it.toString() }

    // ------------------------------------------------------------------ the catalog

    @TestFactory
    fun `every runs scenario agrees across MCP and REST and meets its literal oracle`(): List<DynamicTest> =
        RunsParityScenarios.scenarios.map { s -> DynamicTest.dynamicTest(s.name) { harness.run(s) } }

    @Test
    fun `the catalog names are unique so a failure points at one scenario`() {
        val names = RunsParityScenarios.scenarios.map { it.name }
        assertEquals(
            names.size,
            names.toSet().size,
            "duplicate scenario names: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        )
    }

    @Test
    fun `control - every runs member has a happy and a restricted scope-forbidden scenario and the catalog is registered`() {
        val mine = RunsParityScenarios.scenarios
        for ((tool, member) in RunsParityScenarios.members) {
            assertTrue(
                mine.any { it.tool == tool && it.member == member && it.kind == ParityKind.HAPPY },
                "$tool.$member has no HAPPY scenario"
            )
            assertTrue(
                mine.any { it.tool == tool && it.member == member && it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$tool.$member has no restricted SCOPE_FORBIDDEN scenario"
            )
        }
        assertEquals(RunsParityScenarios.members.toSet(), ParityCoverage.members(registry).filter { it.first.startsWith("runs_") }.toSet())
        assertTrue(ProductionParity.scenarios.containsAll(mine), "every runs scenario is registered in the production catalog")
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(emptyList(), ParityCoverage.stale(registry, mine))
    }

    @Test
    fun `control - the MCP leg of each member runs on the profile whose set contains it and mark has no MCP leg`() {
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "runs_read", "get"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "runs_read", "list"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "runs_write", "open"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "runs_write", "update"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "runs_write", "close"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "runs_write", "pause"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "runs_write", "resume"))
        assertNull(ParityCoverage.mcpLegProfile(registry, "runs_write", "mark"))
        assertTrue(("runs_write" to "mark") in ParityCoverage.restOnly(registry))
    }

    // ------------------------------------------------------------------ S1 open

    @Test
    fun `S1 open stores one run row with its members and records one run opened`() {
        val o = harness.run(named("open happy"))
        assertEquals(1, o.snapshot.getValue("runs").size, "one run row")
        assertEquals(2, o.snapshot.getValue("run_items").size, "one membership row per item")
        assertEquals(1, events(o, "run.opened").size, "one run.opened")
        assertEquals(1, runEvents(o).size, "open records no run event besides run.opened")
    }

    @Test
    fun `S1 a refused open stores no run and no member and records no run event`() {
        for (name in listOf("open scope root", "open without the runs capability", "open with an item already in an open run")) {
            val o = harness.run(named(name))
            assertFalse(o.dbChanged, name)
            assertEquals(emptyList(), runEvents(o), name)
        }
    }

    @Test
    fun `S1 the cost in USD is a decimal on the wire and the micro-USD figure never reaches it`() {
        val o = harness.run(named("open with every field"))
        assertTrue("\"costUsd\":1.5" in o.body, o.body)
        assertFalse("costMicroUsd" in o.body, o.body)
        assertFalse("1500000" in o.body, o.body)
    }

    // ------------------------------------------------------------------ S3 list order and paging

    @Test
    fun `S3 list is ordered by openedAt newest first with the id as the tiebreak`() {
        assertEquals(newestFirst, runIds(harness.run(named("list happy"))))
    }

    @Test
    fun `S3 list pages over the ordered result and each cursor continues where the page stopped`() {
        val first = listPage(""","limit":3""", "first page")
        assertEquals(newestFirst.take(3), runIds(first))
        val firstPage = data(first).getValue("page").jsonObject
        assertEquals(
            7,
            firstPage
                .getValue("total")
                .jsonPrimitive.content
                .toInt()
        )

        val second = listPage(""","limit":3,"cursor":"${firstPage.getValue("next").jsonPrimitive.content}"""", "second page")
        assertEquals(newestFirst.subList(3, 6), runIds(second))

        val third =
            listPage(
                ""","limit":3,"cursor":"${data(second).getValue("page").jsonObject.getValue("next").jsonPrimitive.content}"""",
                "third page"
            )
        assertEquals(newestFirst.drop(6), runIds(third))
        assertNull(data(third).getValue("page").jsonObject["next"], "the last page has no continuation")
    }

    @Test
    fun `S3 a root-scoped token sees exactly the runs of its root in the same order and its total counts only those`() {
        val o = harness.run(named("list filtered by root scope"))
        assertEquals(newestFirst - RunsParityScenarios.rb.toString(), runIds(o))
        assertEquals(
            6,
            data(o)
                .getValue("page")
                .jsonObject
                .getValue("total")
                .jsonPrimitive.content
                .toInt()
        )
    }

    @Test
    fun `S4 unmarked retro lists exactly the unmarked closed runs in order`() {
        val o = harness.run(named("list unmarked retro keeps only closed runs without that mark"))
        assertEquals(listOf(RunsParityScenarios.k2, RunsParityScenarios.k3, RunsParityScenarios.k4).map { it.toString() }, runIds(o))
    }

    // ------------------------------------------------------------------ S5 .. S8 events and stored rows

    @Test
    fun `S5 close records exactly one run ended and no other run event`() {
        val o = harness.run(named("close happy by the coordinator"))
        assertEquals(1, events(o, "run.ended").size, "one run.ended")
        assertEquals(1, runEvents(o).size, "no other run event")
    }

    @Test
    fun `S6 pause and resume record one event each, the pause naming the manual reason`() {
        val paused = harness.run(named("pause happy by a human"))
        val pausedRows = events(paused, "run.paused")
        assertEquals(1, pausedRows.size)
        assertTrue("manual" in (pausedRows.single()["data"] as String), "payload: ${pausedRows.single()["data"]}")
        assertEquals(1, runEvents(paused).size)

        val resumed = harness.run(named("resume happy by a human"))
        assertEquals(1, events(resumed, "run.resumed").size)
        assertEquals(1, runEvents(resumed).size, "a resume records no pause")
    }

    @Test
    fun `S7 update records exactly one run updated`() {
        val o = harness.run(named("update happy"))
        assertEquals(1, events(o, "run.updated").size)
        assertEquals(1, runEvents(o).size)
    }

    @Test
    fun `S8 a first mark records exactly one run marked naming the key and a replay records none`() {
        val first = harness.run(named("mark happy on a closed run"))
        val rows = events(first, "run.marked")
        assertEquals(1, rows.size)
        assertTrue("retro" in (rows.single()["data"] as String), "payload: ${rows.single()["data"]}")
        assertEquals(1, runEvents(first).size, "a mark records no other run event")

        val replay = harness.run(named("mark replay returns the first instant and writes nothing"))
        assertEquals(emptyList(), events(replay, "run.marked"), "a replay records nothing")
        assertFalse(replay.dbChanged, "a replay writes nothing")
    }

    @Test
    fun `S8 marking a run in any status records one run marked and no state event`() {
        for (name in listOf("mark on an open run", "mark on a paused run")) {
            val o = harness.run(named(name))
            assertEquals(1, events(o, "run.marked").size, name)
            assertEquals(1, runEvents(o).size, "$name: a mark is bookkeeping, not an update, pause or end")
        }
    }

    @Test
    fun `S12 and S18 every refused close and mark leaves the run rows and the events untouched`() {
        for (name in listOf(
            "close by another service token is refused and the run stays open",
            "close cancelled by an agent token of another principal is refused",
            "close by a body actor is unknown_parameter on /actor and stores nothing",
            "mark that would be the 33rd mark of a run"
        )) {
            val o = harness.run(named(name))
            assertFalse(o.dbChanged, name)
            assertEquals(emptyList(), events(o, "run.ended"), name)
            assertEquals(emptyList(), events(o, "run.marked"), name)
        }
    }

    @Test
    fun `S19 the unmarked and status conflict stores nothing and names the field`() {
        val o = harness.run(named("list unmarked with status open"))
        assertFalse(o.dbChanged)
        assertTrue("/unmarked" in o.body, o.body)
    }
}

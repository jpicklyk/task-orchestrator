package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `decisions_read` / `decisions_write` slice of [ProductionParity] (item a331be0d, B1 stage 2; scenarios S1-S7, S10, S11, S14-S16
 * of the frozen test-plan): one HAPPY and one SCOPE_FORBIDDEN scenario per member (`decisions_read list`, `decisions_write raise`,
 * `ack`, `delegate`, `escalate`, `answer`, `withdraw`) plus OTHER scenarios for the failures, boundaries and probes. Every scenario
 * sends the SAME arguments over MCP (on the profile whose set contains the member) and over `POST /api/v1/ops/{tool}` against two
 * identically seeded databases; the harness asserts byte-equal bodies (minus `meta.reqId`), the literal REST status, the literal
 * error code and the snapshot parity.
 *
 * Oracles (never read from a run): the task-scope of a331be0d (D2 one decisionId per element and batching through `ops[]`, D8 the
 * list request and its filters, D9 the profile and capability table, D10 the request fields, D10b the declared error sets), the
 * eb933b68 stage-1 rules the declarations quote (delegate leaves a decision open and waiting on the director, escalate waits on the
 * user with a reason of 1 to 500 characters, an answer by a director on behalf of the user records decidedBy user, relayed true and
 * basis relayed, a seat never states decidedBy), `DecisionClasses` (the four built-in classes) and the envelope statuses (200, 207
 * for a failed element of a non-atomic batch, 400 `invalid_request`, 403 `forbidden`, 409 `decision_closed`). Expected values are
 * literals written here.
 *
 * Fixture (clock fixed at [PARITY_NOW], 2026-10-10T12:00Z): the [RunsParityScenarios.tree] items; decisions are `question`
 * decisions raised by `api:rw-coord` unless a scenario says otherwise.
 *
 * Domain invariants satisfied by construction: decisions are seeded through `ParitySeeder.decision`, which derives the answer and
 * acknowledgement fields from the status; every `runId` names a run seeded under the same root; an `expiresAt` in the past goes with
 * a pending status.
 */
internal object DecisionsParityScenarios {
    const val READ_TOOL = "decisions_read"
    const val WRITE_TOOL = "decisions_write"

    private val r1 = RunsParityScenarios.r1
    private val r2 = RunsParityScenarios.r2
    private val c1 = RunsParityScenarios.c1
    private val c2 = RunsParityScenarios.c2
    private val ra = RunsParityScenarios.ra
    private val ghost = RunsParityScenarios.ghost

    val dcOpen: UUID = RunsParityScenarios.dcOpen
    val dcOther: UUID = RunsParityScenarios.dcOther
    val dcDone: UUID = RunsParityScenarios.dcDone
    val dcAck: UUID = RunsParityScenarios.dcAck
    val dcDev: UUID = RunsParityScenarios.dcDev

    /** A `human-only` decision on R1. */
    val dcHuman: UUID = UUID.fromString("d2000001-0000-4000-8000-000000000001")

    /** A `deviation` decision on R2 (director-or-human). */
    val dcDevOther: UUID = UUID.fromString("d2000002-0000-4000-8000-000000000002")

    /** Items for the ancestor scenario: P (child of R1) > Q > G, and R1's other child [c2] as the sibling. */
    val itemP: UUID = c1
    val itemQ: UUID = UUID.fromString("24444444-4444-4444-8444-444444444444")
    val itemG: UUID = UUID.fromString("25555555-5555-4555-8555-555555555555")

    val dP: UUID = UUID.fromString("d3000001-0000-4000-8000-000000000001")
    val dQ: UUID = UUID.fromString("d3000002-0000-4000-8000-000000000002")
    val dG: UUID = UUID.fromString("d3000003-0000-4000-8000-000000000003")
    val dS: UUID = UUID.fromString("d3000004-0000-4000-8000-000000000004")
    val dRootLevel: UUID = UUID.fromString("d3000005-0000-4000-8000-000000000005")

    /** The list-order fixture: B and C share one instant, so the id breaks the tie; A is older. */
    val oA: UUID = UUID.fromString("d4000001-0000-4000-8000-000000000001")
    val oB: UUID = UUID.fromString("d4000002-0000-4000-8000-000000000002")
    val oC: UUID = UUID.fromString("d4000003-0000-4000-8000-000000000003")

    /** The severity fixture: a normal deviation past its expiry (effective severity high) and a normal one not yet due. */
    val sOverdue: UUID = UUID.fromString("d5000001-0000-4000-8000-000000000001")
    val sFresh: UUID = UUID.fromString("d5000002-0000-4000-8000-000000000002")
    val sLow: UUID = UUID.fromString("d5000003-0000-4000-8000-000000000003")

    private fun at(text: String): Instant = Instant.parse(text)

    private const val OPENER = "api:rw-coord"

    /** A seat token of an `implementer` assignment on [c1] of [ra]: 43 characters, held by the opener's principal. */
    private val seatToken = "q".repeat(43)

    // ------------------------------------------------------------------ tokens

    val coord = RunsParityScenarios.coord
    val reader = RunsParityScenarios.reader
    val runsOnly = RunsParityScenarios.runsOnly
    val rootScoped = RunsParityScenarios.rootScoped
    val decider = RunsParityScenarios.decider
    val deciderRootScoped = RunsParityScenarios.deciderRootScoped
    val humanDecider = RunsParityScenarios.humanDecider
    val readerRootScoped = RunsParityScenarios.rootScoped

    /** The opener's identity with the decide capability, so a seat token it holds is accepted and the answer is attempted. */
    val seatDecider = ParityToken("rw-coord", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE))

    // ------------------------------------------------------------------ seeds

    /** Question decisions on R1 (open, answered, acknowledged), one on R2, a director-or-human one on each root. */
    val seedDecisions: suspend ParitySeeder.() -> Unit = {
        RunsParityScenarios.tree.invoke(this)
        decision(dcOpen, r1, dedupeKey = "seed-open", raisedAt = at("2026-10-10T11:00:00Z"))
        decision(dcOther, r2, dedupeKey = "seed-other", raisedAt = at("2026-10-10T11:10:00Z"))
        decision(dcDone, r1, dedupeKey = "seed-done", status = DecisionRequestStatus.ANSWERED, raisedAt = at("2026-10-10T11:20:00Z"))
        decision(dcAck, r1, dedupeKey = "seed-ack", status = DecisionRequestStatus.ACKNOWLEDGED, raisedAt = at("2026-10-10T11:30:00Z"))
        decision(
            dcDev,
            r1,
            decisionClass = "deviation",
            answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
            severity = DecisionRequestSeverity.NORMAL,
            dedupeKey = "seed-dev",
            raisedAt = at("2026-10-10T11:40:00Z")
        )
        decision(
            dcDevOther,
            r2,
            decisionClass = "deviation",
            answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
            severity = DecisionRequestSeverity.NORMAL,
            dedupeKey = "seed-dev-other",
            raisedAt = at("2026-10-10T11:50:00Z")
        )
        decision(
            dcHuman,
            r1,
            decisionClass = "approval",
            answerers = DecisionRequestAnswerers.HUMAN_ONLY,
            severity = DecisionRequestSeverity.HIGH,
            dedupeKey = "seed-human",
            raisedAt = at("2026-10-10T11:55:00Z")
        )
    }

    /** P (child of R1) > Q > G, R1's sibling [c2], and one decision on each plus one on R1 itself with no item. */
    val seedAncestors: suspend ParitySeeder.() -> Unit = {
        RunsParityScenarios.tree.invoke(this)
        item(itemQ, "Alpha grandchild", parent = itemP, tags = "alpha")
        item(itemG, "Alpha great-grandchild", parent = itemQ, tags = "alpha")
        decision(dP, r1, itemId = itemP, dedupeKey = "p", raisedAt = at("2026-10-10T11:01:00Z"))
        decision(dQ, r1, itemId = itemQ, dedupeKey = "q", raisedAt = at("2026-10-10T11:02:00Z"))
        decision(dG, r1, itemId = itemG, dedupeKey = "g", raisedAt = at("2026-10-10T11:03:00Z"))
        decision(dS, r1, itemId = c2, dedupeKey = "s", raisedAt = at("2026-10-10T11:04:00Z"))
        decision(dRootLevel, r1, dedupeKey = "root-level", raisedAt = at("2026-10-10T11:05:00Z"))
    }

    /** Three decisions of differing status, class and run; B and C raised at one instant. */
    val seedOrder: suspend ParitySeeder.() -> Unit = {
        RunsParityScenarios.tree.invoke(this)
        run(ra, r1, listOf(c1, c2), openedBy = OPENER, openedByKind = "service", openedAt = at("2026-10-10T11:00:00Z"))
        decision(oA, r1, dedupeKey = "o-a", raisedAt = at("2026-10-10T11:00:00Z"))
        decision(
            oB,
            r1,
            runId = ra,
            decisionClass = "deviation",
            answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
            severity = DecisionRequestSeverity.NORMAL,
            status = DecisionRequestStatus.ACKNOWLEDGED,
            dedupeKey = "o-b",
            raisedAt = at("2026-10-10T11:20:00Z")
        )
        decision(
            oC,
            r1,
            runId = ra,
            status = DecisionRequestStatus.ANSWERED,
            dedupeKey = "o-c",
            raisedAt = at("2026-10-10T11:20:00Z")
        )
    }

    /** Effective-severity fixture: [sOverdue] is a normal deviation whose expiry has passed, [sFresh] one that has not, [sLow] a question. */
    val seedSeverity: suspend ParitySeeder.() -> Unit = {
        RunsParityScenarios.tree.invoke(this)
        decision(
            sOverdue,
            r1,
            decisionClass = "deviation",
            answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
            severity = DecisionRequestSeverity.NORMAL,
            dedupeKey = "s-overdue",
            raisedAt = at("2026-10-10T10:00:00Z"),
            expiresAt = at("2026-10-10T11:00:00Z")
        )
        decision(
            sFresh,
            r1,
            decisionClass = "deviation",
            answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
            severity = DecisionRequestSeverity.NORMAL,
            dedupeKey = "s-fresh",
            raisedAt = at("2026-10-10T11:30:00Z"),
            expiresAt = at("2026-10-10T15:00:00Z")
        )
        decision(sLow, r1, dedupeKey = "s-low", raisedAt = at("2026-10-10T11:40:00Z"))
    }

    /** [seedDecisions] plus a run [ra] with an active `implementer` seat on [c1] holding [seatToken]. */
    private val seedWithSeat: suspend ParitySeeder.() -> Unit = {
        seedDecisions()
        run(ra, r1, listOf(c1, c2), openedBy = OPENER, openedByKind = "service", openedAt = at("2026-10-10T11:00:00Z"))
        seat(ra, c1, "implementer", principalId = OPENER, token = seatToken)
    }

    // ------------------------------------------------------------------ request builders

    fun list(extra: String = "") = """{"select":"list"$extra}"""

    fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    fun raise(
        key: String,
        extra: String = ""
    ) = """{"op":"raise","class":"question","question":"Which one?","options":[{"key":"a","label":"Label A"},{"key":"b","label":"Label B"}],""" +
        """"dedupeKey":"$key"$extra}"""

    fun ack(decisionId: Any) = """{"op":"ack","decisionId":"$decisionId"}"""

    fun delegate(decisionId: Any) = """{"op":"delegate","decisionId":"$decisionId"}"""

    fun escalate(
        decisionId: Any,
        reason: String = "needs a human"
    ) = """{"op":"escalate","decisionId":"$decisionId","reason":"$reason"}"""

    fun answer(
        decisionId: Any,
        option: String = "a",
        extra: String = ""
    ) = """{"op":"answer","decisionId":"$decisionId","option":"$option"$extra}"""

    fun withdraw(decisionId: Any) = """{"op":"withdraw","decisionId":"$decisionId"}"""

    private fun scopeRoot(required: UUID = r1) = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\"", "\"required\":\"$required\"")

    private fun capability(required: String) = listOf("\"code\":\"forbidden\"", "\"scope\":\"capability\"", "\"required\":\"$required\"")

    /** Never in a body: the principal id of a raiser or answerer, the host and the session id. */
    private val hidden = listOf("principalId", "\"host\"", "sessionId")

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
    ) = ParityScenario("decisions $name", tool, member, kind, token, arguments, status, code, contains, absent, dbChanged, seed)

    /** A list that answers 200 and changes nothing. */
    private fun read(
        name: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        contains: List<String> = emptyList(),
        absent: List<String> = emptyList(),
        seed: suspend ParitySeeder.() -> Unit = seedDecisions
    ) = sc(name, READ_TOOL, "list", kind, token, arguments, 200, null, contains, absent, dbChanged = false, seed = seed)

    /** A list refused as a whole call: [status] and [code] literal. */
    private fun readFailed(
        name: String,
        token: ParityToken,
        arguments: String,
        status: Int,
        code: String,
        contains: List<String>,
        absent: List<String> = emptyList(),
        kind: ParityKind = ParityKind.OTHER,
        seed: suspend ParitySeeder.() -> Unit = seedDecisions
    ) = sc(name, READ_TOOL, "list", kind, token, arguments, status, code, contains, absent, dbChanged = false, seed = seed)

    private fun wrote(
        name: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        contains: List<String>,
        absent: List<String> = emptyList(),
        seed: suspend ParitySeeder.() -> Unit = seedDecisions
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
        seed: suspend ParitySeeder.() -> Unit = seedDecisions
    ) = sc(name, WRITE_TOOL, member, kind, token, arguments, 207, "partial_failure", contains, absent, dbChanged = false, seed = seed)

    // ------------------------------------------------------------------ scenarios

    val scenarios: List<ParityScenario> =
        listOf(
            // ---- decisions_read list
            read(
                "list happy",
                ParityKind.HAPPY,
                reader,
                list(),
                contains = listOf("\"id\":\"$dcOpen\"", "\"id\":\"$dcDone\"", "\"id\":\"$dcOther\"", "\"classes\"", "\"deviation\""),
                absent = hidden
            ),
            read(
                "list of a restricted token omits the roots outside its scope and their classes",
                ParityKind.SCOPE_FORBIDDEN,
                readerRootScoped,
                list(),
                contains = listOf("\"id\":\"$dcOpen\"", "\"$r1\""),
                absent = listOf("\"id\":\"$dcOther\"", "\"id\":\"$dcDevOther\"", "\"$r2\"")
            ),
            readFailed(
                "list with a rootId outside the scope is forbidden, not not_found",
                readerRootScoped,
                list(""","rootId":"$r2""""),
                403,
                "forbidden",
                scopeRoot(),
                absent = listOf("not_found", "\"id\":\"$dcOther\""),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            readFailed(
                "list with an itemId outside the scope is forbidden",
                readerRootScoped,
                list(""","itemId":"${RunsParityScenarios.d1}""""),
                403,
                "forbidden",
                scopeRoot(),
                kind = ParityKind.SCOPE_FORBIDDEN,
                seed = {
                    seedDecisions()
                    decision(
                        UUID.fromString("d6000001-0000-4000-8000-000000000001"),
                        r2,
                        itemId = RunsParityScenarios.d1,
                        dedupeKey = "on-d1"
                    )
                }
            ),
            read(
                "list of a root that has no decisions answers an empty page with that root's classes",
                ParityKind.OTHER,
                reader,
                list(""","rootId":"$r2""""),
                // The root R2 holds no decision in this fixture, so the page is empty and the classes of R2 are still named.
                contains = listOf("\"items\":[]", "\"$r2\"", "\"question\"", "\"deviation\""),
                seed = { RunsParityScenarios.tree.invoke(this) }
            ),
            read(
                "list by status answered returns only the answered decision",
                ParityKind.OTHER,
                reader,
                list(""","status":["answered"]"""),
                contains = listOf("\"id\":\"$dcDone\""),
                absent = listOf("\"id\":\"$dcOpen\"", "\"id\":\"$dcAck\"", "\"id\":\"$dcDev\"")
            ),
            read(
                "list by class deviation returns only deviations",
                ParityKind.OTHER,
                reader,
                list(""","class":"deviation""""),
                contains = listOf("\"id\":\"$dcDev\"", "\"id\":\"$dcDevOther\""),
                absent = listOf("\"id\":\"$dcOpen\"", "\"id\":\"$dcDone\"", "\"id\":\"$dcHuman\"")
            ),
            read(
                "list by runId returns only the decisions of that run",
                ParityKind.OTHER,
                reader,
                list(""","runId":"$ra""""),
                contains = listOf("\"id\":\"$oB\"", "\"id\":\"$oC\""),
                absent = listOf("\"id\":\"$oA\""),
                seed = seedOrder
            ),
            read(
                "list by waitingOn director matches nothing while no decision is delegated",
                ParityKind.OTHER,
                reader,
                list(""","waitingOn":"director""""),
                contains = listOf("\"items\":[]"),
                absent = listOf("\"id\":\"$dcOpen\"")
            ),
            read(
                "list by severity high matches the overdue deviation and the seeded high decision, not the normal one not yet due",
                ParityKind.OTHER,
                reader,
                list(""","severity":"high""""),
                contains = listOf("\"id\":\"$sOverdue\""),
                absent = listOf("\"id\":\"$sFresh\"", "\"id\":\"$sLow\""),
                seed = seedSeverity
            ),
            read(
                "list by severity normal excludes the overdue deviation whose effective severity is high",
                ParityKind.OTHER,
                reader,
                list(""","severity":"normal""""),
                contains = listOf("\"id\":\"$sFresh\""),
                absent = listOf("\"id\":\"$sOverdue\"", "\"id\":\"$sLow\""),
                seed = seedSeverity
            ),
            read(
                "list overdue true returns only the pending decision past its expiry",
                ParityKind.OTHER,
                reader,
                list(""","overdue":true"""),
                contains = listOf("\"id\":\"$sOverdue\""),
                absent = listOf("\"id\":\"$sFresh\"", "\"id\":\"$sLow\""),
                seed = seedSeverity
            ),
            read(
                "list overdue false is no filter",
                ParityKind.OTHER,
                reader,
                list(""","overdue":false"""),
                contains = listOf("\"id\":\"$sOverdue\"", "\"id\":\"$sFresh\"", "\"id\":\"$sLow\""),
                seed = seedSeverity
            ),
            read(
                "list ancestorId of P returns P, its child and its grandchild but not the sibling or the root-level decision",
                ParityKind.OTHER,
                reader,
                list(""","ancestorId":"$itemP""""),
                contains = listOf("\"id\":\"$dP\"", "\"id\":\"$dQ\"", "\"id\":\"$dG\""),
                absent = listOf("\"id\":\"$dS\"", "\"id\":\"$dRootLevel\""),
                seed = seedAncestors
            ),
            read(
                "list ancestorId of the root also returns the decisions that name no item",
                ParityKind.OTHER,
                reader,
                list(""","ancestorId":"$r1""""),
                contains =
                    listOf(
                        "\"id\":\"$dP\"",
                        "\"id\":\"$dQ\"",
                        "\"id\":\"$dG\"",
                        "\"id\":\"$dS\"",
                        "\"id\":\"$dRootLevel\""
                    ),
                seed = seedAncestors
            ),
            read(
                "list with decisionIds returns those decisions and leaves an unknown id out",
                ParityKind.OTHER,
                reader,
                list(""","decisionIds":["$dcOpen","$ghost"]"""),
                contains = listOf("\"id\":\"$dcOpen\""),
                absent = listOf("\"id\":\"$dcDone\"", "$ghost")
            ),
            read(
                "list with an empty decisionIds matches nothing",
                ParityKind.OTHER,
                reader,
                list(""","decisionIds":[]"""),
                contains = listOf("\"items\":[]"),
                absent = listOf("\"id\":\"$dcOpen\"")
            ),
            readFailed(
                "list with 51 decisionIds is invalid",
                reader,
                list(""","decisionIds":${(1..51).joinToString(",", "[", "]") { "\"d7000000-0000-4000-8000-%012d\"".format(it) }}"""),
                400,
                "invalid_request",
                listOf("/decisionIds")
            ),
            read(
                "list with 50 decisionIds is accepted",
                ParityKind.OTHER,
                reader,
                list(""","decisionIds":${(1..50).joinToString(",", "[", "]") { "\"d7000000-0000-4000-8000-%012d\"".format(it) }}"""),
                contains = listOf("\"items\":[]")
            ),
            readFailed(
                "list with a decisionId that is not a UUID names its index",
                reader,
                list(""","decisionIds":["$dcOpen","not-a-uuid"]"""),
                400,
                "invalid_request",
                listOf("/decisionIds/1")
            ),
            readFailed(
                "list with limit 0 is invalid",
                reader,
                list(""","limit":0"""),
                400,
                "invalid_request",
                listOf("\"field\":\"limit\"")
            ),
            readFailed(
                "list with limit 201 is invalid",
                reader,
                list(""","limit":201"""),
                400,
                "invalid_request",
                listOf("\"field\":\"limit\"")
            ),
            read(
                "list with limit 200 is accepted",
                ParityKind.OTHER,
                reader,
                list(""","limit":200"""),
                contains = listOf("\"id\":\"$dcOpen\"")
            ),
            // ---- decisions_write raise
            wrote(
                "raise happy by root",
                "raise",
                ParityKind.HAPPY,
                coord,
                ops(raise("new-key", ""","rootId":"$r1"""")),
                contains = listOf("\"class\":\"question\"", "\"status\":\"open\"", "\"severity\":\"low\""),
                absent = hidden
            ),
            failed(
                "raise scope root",
                "raise",
                rootScoped,
                ops(raise("new-key", ""","rootId":"$r2"""")),
                scopeRoot(),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            wrote(
                "raise by a token with only the runs capability is accepted",
                "raise",
                ParityKind.OTHER,
                runsOnly,
                ops(raise("new-key", ""","rootId":"$r1"""")),
                contains = listOf("\"status\":\"open\"")
            ),
            failed(
                "raise with a field of another op is refused",
                "raise",
                coord,
                ops(raise("new-key", ""","rootId":"$r1","reason":"x"""")),
                listOf("\"code\":\"unknown_parameter\"")
            ),
            // ---- ack
            wrote(
                "ack happy",
                "ack",
                ParityKind.HAPPY,
                decider,
                ops(ack(dcOpen)),
                contains = listOf("\"$dcOpen\"", "\"status\":\"acknowledged\""),
                absent = hidden
            ),
            failed(
                "ack scope root",
                "ack",
                deciderRootScoped,
                ops(ack(dcOther)),
                scopeRoot(),
                absent = listOf("not_found"),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            failed(
                "ack of an answered decision is decision_closed",
                "ack",
                decider,
                ops(ack(dcDone)),
                listOf("\"code\":\"decision_closed\"", "\"status\":\"answered\"", "\"decisionId\":\"$dcDone\"")
            ),
            // ---- delegate
            wrote(
                "delegate happy",
                "delegate",
                ParityKind.HAPPY,
                decider,
                ops(delegate(dcDev)),
                contains = listOf("\"$dcDev\"", "\"status\":\"open\"", "\"waitingOn\":\"director\""),
                absent = hidden + "waitingReason"
            ),
            failed(
                "delegate scope root",
                "delegate",
                deciderRootScoped,
                ops(delegate(dcDevOther)),
                scopeRoot(),
                absent = listOf("not_found"),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            failed(
                "delegate by a token that lacks the decide capability",
                "delegate",
                runsOnly,
                ops(delegate(dcDev)),
                capability("decide")
            ),
            failed(
                "delegate of a human-only decision names the decisionId",
                "delegate",
                humanDecider,
                ops(delegate(dcHuman)),
                listOf("\"code\":\"invalid_request\"", "/decisionId")
            ),
            failed(
                "delegate of a human-only decision by a caller who is not human is refused by kind",
                "delegate",
                decider,
                ops(delegate(dcHuman)),
                listOf("\"code\":\"forbidden\"", "\"scope\":\"principal_kind\"", "\"required\":\"human\"")
            ),
            failed(
                "delegate of an answered decision is decision_closed",
                "delegate",
                decider,
                ops(delegate(dcDone)),
                listOf("\"code\":\"decision_closed\"", "\"status\":\"answered\"", "\"decisionId\":\"$dcDone\"")
            ),
            failed(
                "delegate of an unknown decision is not_found",
                "delegate",
                decider,
                ops(delegate(ghost)),
                listOf("\"code\":\"not_found\"", "\"kind\":\"decision\"")
            ),
            // ---- escalate
            wrote(
                "escalate happy",
                "escalate",
                ParityKind.HAPPY,
                decider,
                ops(escalate(dcDev, "needs a human")),
                contains = listOf("\"$dcDev\"", "\"status\":\"open\"", "\"waitingOn\":\"user\"", "\"waitingReason\":\"needs a human\""),
                absent = hidden
            ),
            failed(
                "escalate scope root",
                "escalate",
                deciderRootScoped,
                ops(escalate(dcDevOther)),
                scopeRoot(),
                absent = listOf("not_found"),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            failed(
                "escalate without a reason is invalid",
                "escalate",
                decider,
                ops("""{"op":"escalate","decisionId":"$dcDev"}"""),
                listOf("\"code\":\"invalid_request\"", "reason")
            ),
            failed(
                "escalate with an empty reason is invalid",
                "escalate",
                decider,
                ops(escalate(dcDev, "")),
                listOf("\"code\":\"invalid_request\"", "reason")
            ),
            failed(
                "escalate with a reason of 501 characters is invalid",
                "escalate",
                decider,
                ops(escalate(dcDev, "r".repeat(501))),
                listOf("\"code\":\"invalid_request\"", "reason")
            ),
            wrote(
                "escalate with a reason of exactly 500 characters is accepted",
                "escalate",
                ParityKind.OTHER,
                decider,
                ops(escalate(dcDev, "r".repeat(500))),
                contains = listOf("\"waitingOn\":\"user\"")
            ),
            failed(
                "escalate of a withdrawn-or-answered decision is decision_closed",
                "escalate",
                decider,
                ops(escalate(dcDone)),
                listOf("\"code\":\"decision_closed\"", "\"status\":\"answered\"")
            ),
            // ---- answer
            wrote(
                "answer happy",
                "answer",
                ParityKind.HAPPY,
                decider,
                ops(answer(dcOpen, "b", ""","rationale":"safer"""")),
                contains = listOf("\"$dcOpen\"", "\"status\":\"answered\""),
                absent = hidden
            ),
            failed(
                "answer scope root",
                "answer",
                deciderRootScoped,
                ops(answer(dcOther)),
                scopeRoot(),
                absent = listOf("not_found"),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            wrote(
                "answer by a director on behalf of the user records decidedBy user, relayed true and basis relayed",
                "answer",
                ParityKind.OTHER,
                decider,
                ops(answer(dcDev, "b", ""","decidedBy":"user"""")),
                contains = listOf("\"$dcDev\"", "\"status\":\"answered\"", "\"decidedBy\":\"user\"", "\"relayed\":true", "\"basis\":\"relayed\"")
            ),
            wrote(
                "answer with citation links stores them in the answer",
                "answer",
                ParityKind.OTHER,
                decider,
                ops(answer(dcDev, "b", ""","links":[{"kind":"note","ref":"item:$c1#task-scope"}]""")),
                contains = listOf("\"status\":\"answered\"", "\"kind\":\"note\"", "\"ref\":\"item:$c1#task-scope\"")
            ),
            failed(
                "answer with a link of an unknown kind names the link",
                "answer",
                decider,
                ops(answer(dcDev, "b", ""","links":[{"kind":"bogus","ref":"x"}]""")),
                listOf("\"code\":\"invalid_request\"", "/links/0/kind")
            ),
            failed(
                "answer under a seat token that states decidedBy names decidedBy",
                "answer",
                seatDecider,
                ops(answer(dcOpen, "a", ""","decidedBy":"user""""), top = ""","seatToken":"$seatToken""""),
                listOf("\"code\":\"invalid_request\"", "/decidedBy"),
                seed = seedWithSeat
            ),
            failed(
                "a second answer is decision_closed",
                "answer",
                decider,
                ops(answer(dcDone)),
                listOf("\"code\":\"decision_closed\"", "\"status\":\"answered\"")
            ),
            // ---- withdraw
            wrote(
                "withdraw happy by the raiser",
                "withdraw",
                ParityKind.HAPPY,
                coord,
                ops(withdraw(dcOpen)),
                contains = listOf("\"$dcOpen\"", "\"status\":\"withdrawn\""),
                absent = hidden
            ),
            failed(
                "withdraw scope root",
                "withdraw",
                rootScoped,
                ops(withdraw(dcOther)),
                scopeRoot(),
                kind = ParityKind.SCOPE_FORBIDDEN
            ),
            // ---- batches (D2)
            sc(
                "an atomic batch whose second delegate hits a closed decision writes nothing and names index 1",
                WRITE_TOOL,
                "delegate",
                ParityKind.OTHER,
                decider,
                ops(delegate(dcDev), delegate(dcDone), top = ""","atomic":true"""),
                409,
                "decision_closed",
                listOf("\"index\":1", "\"decisionId\":\"$dcDone\""),
                dbChanged = false,
                seed = seedDecisions
            ),
            sc(
                "a non-atomic batch keeps the first delegate and fails the second as decision_closed",
                WRITE_TOOL,
                "delegate",
                ParityKind.OTHER,
                decider,
                ops(delegate(dcDev), delegate(dcDone)),
                207,
                "partial_failure",
                listOf("\"waitingOn\":\"director\"", "\"code\":\"decision_closed\"", "\"decisionId\":\"$dcDone\""),
                dbChanged = true,
                seed = seedDecisions
            )
        )

    /** Every member holds a HAPPY and a restricted SCOPE_FORBIDDEN scenario above, so no member is exempt. */
    val exemptions: List<ScopeExempt> = emptyList()

    val members: List<Pair<String, String>> =
        listOf(
            READ_TOOL to "list",
            WRITE_TOOL to "raise",
            WRITE_TOOL to "ack",
            WRITE_TOOL to "delegate",
            WRITE_TOOL to "escalate",
            WRITE_TOOL to "answer",
            WRITE_TOOL to "withdraw"
        )
}

/**
 * Independent tests (test-author seat, item a331be0d) for `decisions_read` / `decisions_write`: every scenario of
 * [DecisionsParityScenarios] runs through [ParityHarness]; the tests below read the outcome of selected runs for the facts the
 * catalog's substring checks cannot state (list order, the root classes, event rows, paging).
 *
 * Oracles: task-scope D8 (rows raisedAt descending, then id ascending; `classes` is `{rootId: {name: {answerers, severity,
 * expiresAfterSeconds?, freeform, serverRaised}}}`; context only with `includeContext true`), `DecisionClasses` (built-ins
 * `run-budget`, `attempt-cap`, `deviation` expiring after 14,400 s, `question` freeform) and the event names of the decision events.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionsParityTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val harness = ParityHarness(registry)

    private fun named(name: String): ParityScenario = DecisionsParityScenarios.scenarios.single { it.name == "decisions $name" }

    private fun data(o: ParityOutcome): JsonObject =
        Json
            .parseToJsonElement(o.body)
            .jsonObject
            .getValue("data")
            .jsonObject

    private fun items(o: ParityOutcome): List<JsonObject> = data(o).getValue("items").jsonArray.map { it.jsonObject }

    private fun ids(o: ParityOutcome): List<String> = items(o).map { it.getValue("id").jsonPrimitive.content }

    private fun events(
        o: ParityOutcome,
        type: String
    ): List<Map<String, Any?>> = o.snapshot.getValue("events").filter { it["type"] == type }

    private fun listOutcome(
        label: String,
        extra: String,
        seed: suspend ParitySeeder.() -> Unit,
        token: ParityToken = DecisionsParityScenarios.reader
    ): ParityOutcome =
        harness.run(
            ParityScenario(
                name = "decisions probe $label",
                tool = DecisionsParityScenarios.READ_TOOL,
                member = "list",
                kind = ParityKind.OTHER,
                token = token,
                arguments = DecisionsParityScenarios.list(extra),
                expectStatus = 200,
                expectDbChanged = false,
                seed = seed
            )
        )

    // ------------------------------------------------------------------ the catalog

    @TestFactory
    fun `every decisions scenario agrees across MCP and REST and meets its literal oracle`(): List<DynamicTest> =
        DecisionsParityScenarios.scenarios.map { s -> DynamicTest.dynamicTest(s.name) { harness.run(s) } }

    @Test
    fun `the catalog names are unique so a failure points at one scenario`() {
        val names = DecisionsParityScenarios.scenarios.map { it.name }
        assertEquals(
            names.size,
            names.toSet().size,
            "duplicate scenario names: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        )
    }

    @Test
    fun `control - every decisions member has a happy and a restricted scope-forbidden scenario and the catalog is registered`() {
        val mine = DecisionsParityScenarios.scenarios
        for ((tool, member) in DecisionsParityScenarios.members) {
            assertTrue(
                mine.any { it.tool == tool && it.member == member && it.kind == ParityKind.HAPPY },
                "$tool.$member has no HAPPY scenario"
            )
            assertTrue(
                mine.any { it.tool == tool && it.member == member && it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$tool.$member has no restricted SCOPE_FORBIDDEN scenario"
            )
        }
        assertEquals(
            DecisionsParityScenarios.members.toSet(),
            ParityCoverage.members(registry).filter { it.first.startsWith("decisions_") }.toSet(),
            "the registry serves exactly these decisions members"
        )
        assertTrue(ProductionParity.scenarios.containsAll(mine), "every decisions scenario is registered in the production catalog")
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(emptyList(), ParityCoverage.stale(registry, mine))
    }

    @Test
    fun `control - the MCP leg of each member runs on the profile whose set contains it`() {
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "decisions_read", "list"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "decisions_write", "raise"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "decisions_write", "delegate"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "decisions_write", "withdraw"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "decisions_write", "ack"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "decisions_write", "escalate"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "decisions_write", "answer"))
        assertEquals(
            setOf(ToolProfile.DIRECTOR),
            ParityCoverage.profilesOf(registry, "decisions_read", "list"),
            "S11: decisions_read is a director tool only"
        )
        assertEquals(
            setOf(ToolProfile.ORCHESTRATION),
            ParityCoverage.profilesOf(registry, "decisions_write", "raise"),
            "S11: raise is an orchestration member only"
        )
        assertEquals(
            setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR),
            ParityCoverage.profilesOf(registry, "decisions_write", "delegate")
        )
        assertEquals(
            setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR),
            ParityCoverage.profilesOf(registry, "decisions_write", "withdraw")
        )
        for (op in listOf("ack", "escalate", "answer")) {
            assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "decisions_write", op), "S11: $op is director only")
        }
    }

    // ------------------------------------------------------------------ S1 list order, classes, no context

    @Test
    fun `S1 list answers full views newest first with the id breaking a tie, no context member and the four built-in classes`() {
        val o = listOutcome("order", "", DecisionsParityScenarios.seedOrder)
        assertEquals(
            listOf(DecisionsParityScenarios.oB, DecisionsParityScenarios.oC, DecisionsParityScenarios.oA).map { it.toString() },
            ids(o),
            "raisedAt descending, then id ascending (B and C share an instant)"
        )
        val byId = items(o).associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals("deviation", byId.getValue(DecisionsParityScenarios.oB.toString()).getValue("class").jsonPrimitive.content)
        assertEquals("acknowledged", byId.getValue(DecisionsParityScenarios.oB.toString()).getValue("status").jsonPrimitive.content)
        assertEquals("answered", byId.getValue(DecisionsParityScenarios.oC.toString()).getValue("status").jsonPrimitive.content)
        assertEquals("open", byId.getValue(DecisionsParityScenarios.oA.toString()).getValue("status").jsonPrimitive.content)
        for ((id, view) in byId) {
            assertFalse("context" in view, "$id: a list row carries no context member unless includeContext is true")
            assertTrue("question" in view && "options" in view, "$id: a full view carries its question and options")
        }
        val classes = data(o).getValue("classes").jsonObject.getValue(RunsParityScenarios.r1.toString()).jsonObject
        assertEquals(setOf("run-budget", "attempt-cap", "deviation", "question"), classes.keys)
        assertEquals(
            14_400L,
            classes.getValue("deviation").jsonObject.getValue("expiresAfterSeconds").jsonPrimitive.content.toLong()
        )
        assertEquals(true, classes.getValue("question").jsonObject.getValue("freeform").jsonPrimitive.booleanOrNull)
        assertEquals(false, classes.getValue("deviation").jsonObject.getValue("freeform").jsonPrimitive.booleanOrNull)
        assertEquals(true, classes.getValue("run-budget").jsonObject.getValue("serverRaised").jsonPrimitive.booleanOrNull)
        assertEquals(false, classes.getValue("question").jsonObject.getValue("serverRaised").jsonPrimitive.booleanOrNull)
        assertEquals("director-or-human", classes.getValue("deviation").jsonObject.getValue("answerers").jsonPrimitive.content)
        assertEquals("any-actor", classes.getValue("question").jsonObject.getValue("answerers").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ S2 the filters, each alone

    @Test
    fun `S2 each filter alone returns exactly its subset`() {
        val everyone = ids(listOutcome("all", "", DecisionsParityScenarios.seedDecisions)).toSet()
        assertEquals(
            setOf(
                DecisionsParityScenarios.dcOpen,
                DecisionsParityScenarios.dcOther,
                DecisionsParityScenarios.dcDone,
                DecisionsParityScenarios.dcAck,
                DecisionsParityScenarios.dcDev,
                DecisionsParityScenarios.dcDevOther,
                DecisionsParityScenarios.dcHuman
            ).map { it.toString() }.toSet(),
            everyone
        )
        val open = ids(listOutcome("open", ""","status":["open"]""", DecisionsParityScenarios.seedDecisions)).toSet()
        assertEquals(
            setOf(
                DecisionsParityScenarios.dcOpen,
                DecisionsParityScenarios.dcOther,
                DecisionsParityScenarios.dcDev,
                DecisionsParityScenarios.dcDevOther,
                DecisionsParityScenarios.dcHuman
            ).map { it.toString() }.toSet(),
            open
        )
        val twoStatuses =
            ids(listOutcome("two", ""","status":["answered","acknowledged"]""", DecisionsParityScenarios.seedDecisions)).toSet()
        assertEquals(setOf(DecisionsParityScenarios.dcDone, DecisionsParityScenarios.dcAck).map { it.toString() }.toSet(), twoStatuses)
        assertEquals(
            setOf(DecisionsParityScenarios.dcHuman.toString()),
            ids(listOutcome("class", ""","class":"approval"""", DecisionsParityScenarios.seedDecisions)).toSet()
        )
        assertEquals(
            listOf(DecisionsParityScenarios.sOverdue.toString()),
            ids(listOutcome("high", ""","severity":"high"""", DecisionsParityScenarios.seedSeverity)),
            "an overdue normal deviation reads one step up"
        )
        assertEquals(
            listOf(DecisionsParityScenarios.sFresh.toString()),
            ids(listOutcome("normal", ""","severity":"normal"""", DecisionsParityScenarios.seedSeverity))
        )
        assertEquals(
            listOf(DecisionsParityScenarios.sLow.toString()),
            ids(listOutcome("low", ""","severity":"low"""", DecisionsParityScenarios.seedSeverity))
        )
        val overdue = items(listOutcome("overdue", ""","overdue":true""", DecisionsParityScenarios.seedSeverity)).single()
        assertEquals(true, overdue.getValue("overdue").jsonPrimitive.booleanOrNull)
        assertEquals("high", overdue.getValue("effectiveSeverity").jsonPrimitive.content)
        assertEquals("normal", overdue.getValue("severity").jsonPrimitive.content)
    }

    @Test
    fun `S2 a root filter limits the rows to that root and an itemId filter to that item`() {
        val onR2 = ids(listOutcome("root", ""","rootId":"${RunsParityScenarios.r2}"""", DecisionsParityScenarios.seedDecisions)).toSet()
        assertEquals(
            setOf(DecisionsParityScenarios.dcOther, DecisionsParityScenarios.dcDevOther).map { it.toString() }.toSet(),
            onR2
        )
        assertEquals(
            listOf(DecisionsParityScenarios.dP.toString()),
            ids(listOutcome("item", ""","itemId":"${DecisionsParityScenarios.itemP}"""", DecisionsParityScenarios.seedAncestors)),
            "an itemId filter matches the item itself, not its descendants"
        )
    }

    // ------------------------------------------------------------------ S3 ancestorId

    @Test
    fun `S3 ancestorId returns the item and every descendant newest first and the root also returns the item-less decisions`() {
        val p = ids(listOutcome("anc-p", ""","ancestorId":"${DecisionsParityScenarios.itemP}"""", DecisionsParityScenarios.seedAncestors))
        assertEquals(
            listOf(DecisionsParityScenarios.dG, DecisionsParityScenarios.dQ, DecisionsParityScenarios.dP).map { it.toString() },
            p
        )
        val q = ids(listOutcome("anc-q", ""","ancestorId":"${DecisionsParityScenarios.itemQ}"""", DecisionsParityScenarios.seedAncestors))
        assertEquals(listOf(DecisionsParityScenarios.dG, DecisionsParityScenarios.dQ).map { it.toString() }, q)
        val root =
            ids(listOutcome("anc-r", ""","ancestorId":"${RunsParityScenarios.r1}"""", DecisionsParityScenarios.seedAncestors)).toSet()
        assertEquals(
            setOf(
                DecisionsParityScenarios.dP,
                DecisionsParityScenarios.dQ,
                DecisionsParityScenarios.dG,
                DecisionsParityScenarios.dS,
                DecisionsParityScenarios.dRootLevel
            ).map { it.toString() }.toSet(),
            root
        )
    }

    // ------------------------------------------------------------------ paging probes

    @Test
    fun `probe a page of one is followed by the cursor, a replayed cursor returns the same page and the pages cover the list once`() {
        val first = listOutcome("page1", ""","limit":1""", DecisionsParityScenarios.seedOrder)
        val next =
            assertNotNull(
                data(first)
                    .getValue("page")
                    .jsonObject["next"]
                    ?.jsonPrimitive
                    ?.content,
                "a full page of a longer list carries page.next: ${first.body}"
            )
        val second = listOutcome("page2", ""","limit":1,"cursor":"$next"""", DecisionsParityScenarios.seedOrder)
        val replay = listOutcome("page2-replay", ""","limit":1,"cursor":"$next"""", DecisionsParityScenarios.seedOrder)
        assertEquals(ids(second), ids(replay), "the same cursor answers the same page")
        assertEquals(1, ids(first).size)
        assertEquals(1, ids(second).size)
        assertTrue(ids(first).single() != ids(second).single(), "the second page continues after the first")
        assertEquals(DecisionsParityScenarios.oB.toString(), ids(first).single())
        assertEquals(DecisionsParityScenarios.oC.toString(), ids(second).single())
    }

    // ------------------------------------------------------------------ S5, S6, S7 events and provenance

    @Test
    fun `S5 each write records its decision event`() {
        val raised = harness.run(named("raise happy by root"))
        assertEquals(1, events(raised, "decision.raised").size, "raise: ${raised.snapshot["events"]}")
        val acked = harness.run(named("ack happy"))
        assertEquals(1, events(acked, "decision.acknowledged").size)
        val answered = harness.run(named("answer happy"))
        assertEquals(1, events(answered, "decision.answered").size)
        val withdrawn = harness.run(named("withdraw happy by the raiser"))
        assertEquals(1, events(withdrawn, "decision.withdrawn").size)
    }

    @Test
    fun `S6 an answer on behalf of the user records the relay and its cited links, and a plain answer records neither`() {
        val relayed = harness.run(named("answer by a director on behalf of the user records decidedBy user, relayed true and basis relayed"))
        val view = data(relayed).getValue("results").jsonArray.single().jsonObject
        val decision = view.getValue("answer").jsonObject
        assertEquals("user", decision.getValue("decidedBy").jsonPrimitive.content)
        assertEquals(true, decision.getValue("relayed").jsonPrimitive.booleanOrNull)
        assertEquals("relayed", decision.getValue("basis").jsonPrimitive.content)
        assertEquals(1, events(relayed, "decision.answered").size)

        val cited = harness.run(named("answer with citation links stores them in the answer"))
        assertTrue(
            "item:${RunsParityScenarios.c1}#task-scope" in cited.body && "\"links\"" in cited.body,
            "the cited note link is returned in the answer: ${cited.body}"
        )
    }

    @Test
    fun `S7 delegate leaves the decision open and waiting on the director and escalate waits on the user with its reason`() {
        val delegated = harness.run(named("delegate happy"))
        assertEquals(1, events(delegated, "decision.delegated").size, "delegate: ${delegated.snapshot["events"]}")
        assertTrue("\"waitingOn\":\"director\"" in delegated.body, delegated.body)
        val escalated = harness.run(named("escalate happy"))
        assertEquals(1, events(escalated, "decision.escalated").size, "escalate: ${escalated.snapshot["events"]}")
        assertTrue("\"waitingReason\":\"needs a human\"" in escalated.body, escalated.body)
        assertFalse("\"waitingOn\":\"director\"" in escalated.body, "escalate replaces the director wait: ${escalated.body}")
    }

    // ------------------------------------------------------------------ S16 batches

    @Test
    fun `S16 an atomic batch with a failing second element writes no event and a non-atomic one keeps the first`() {
        val atomic = harness.run(named("an atomic batch whose second delegate hits a closed decision writes nothing and names index 1"))
        assertEquals(0, events(atomic, "decision.delegated").size, "atomic: nothing written: ${atomic.snapshot["events"]}")
        assertFalse(atomic.dbChanged)
        val loose = harness.run(named("a non-atomic batch keeps the first delegate and fails the second as decision_closed"))
        assertEquals(1, events(loose, "decision.delegated").size, "non-atomic: the first element committed: ${loose.snapshot["events"]}")
        assertTrue(loose.dbChanged)
        val results = data(loose).getValue("results") as JsonArray
        assertEquals(2, results.size)
        assertTrue(results[0].jsonObject["ok"] == JsonPrimitive(true), results.toString())
        assertTrue(results[1].jsonObject["ok"] == JsonPrimitive(false), results.toString())
    }
}

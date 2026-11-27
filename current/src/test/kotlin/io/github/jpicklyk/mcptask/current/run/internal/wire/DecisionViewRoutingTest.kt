package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolServiceRegistry
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.run.DecisionAnswerBasis
import io.github.jpicklyk.mcptask.current.model.run.DecisionDecidedBy
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswer
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.model.run.DecisionWaitingOn
import io.github.jpicklyk.mcptask.current.model.run.RunSeatLink
import io.github.jpicklyk.mcptask.current.platform.call.AnswerProvenance
import io.github.jpicklyk.mcptask.current.platform.call.PrincipalKind
import io.github.jpicklyk.mcptask.current.platform.call.ProofStatus
import io.github.jpicklyk.mcptask.current.run.DecisionAnswerCommand
import io.github.jpicklyk.mcptask.current.run.DecisionCaller
import io.github.jpicklyk.mcptask.current.run.DecisionRaiseCommand
import io.github.jpicklyk.mcptask.current.run.RunToolServices
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item eb933b68) for how [DecisionView] shows who a decision waits on and the answer's
 * provenance.
 *
 * Oracles (never read from a run): the frozen task-scope decision D7 and the declared view members. In short: `full` gains
 * `waitingOn` and `waitingReason` when they are set (an unrouted decision has neither, a delegated one has `waitingOn` alone, an
 * escalated one has both) and, inside `answer`, `decidedBy`, `basis`, `relayed`, `withinAutonomy` and `links` as `[{kind, ref}]`;
 * an absent member is omitted, never JSON null; `relayed` and `withinAutonomy` are JSON booleans; `summary` is unchanged (no
 * routing member, and equal for a routed decision and its unrouted twin); a stored row whose new columns are NULL (a decision raised
 * before the routing existed, or answered by a seat) shows no new key; and the values survive a round trip through storage.
 *
 * Negative-assertion fixture conditions: every absence assertion is paired with a view of the same decision with the member set
 * (an unrouted decision beside a routed twin built by `copy`), so a view that never emits the member would fail the presence side.
 *
 * Domain invariants satisfied by construction: decisions are built through the one `decision()` helper that derives the answer from
 * the status (an answered decision has an answer and no routing, a pending one has no answer, the waiting reason only beside
 * `user`), and the stored cases go through the service.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionViewRoutingTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val now: Instant = Instant.parse("2026-10-10T12:00:00Z")

    private val newMembers = listOf("waitingOn", "waitingReason")
    private val newAnswerMembers = listOf("decidedBy", "basis", "relayed", "withinAutonomy")

    private fun decision(
        status: DecisionRequestStatus = DecisionRequestStatus.OPEN,
        waitingOn: DecisionWaitingOn? = null,
        waitingReason: String? = null,
        answer: DecisionRequestAnswer? = null
    ) = DecisionRequest(
        rootId = UUID.fromString("c0000001-0000-4000-8000-000000000001"),
        itemId = UUID.fromString("c0000002-0000-4000-8000-000000000002"),
        decisionClass = "deviation",
        answerers = DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
        severity = DecisionRequestSeverity.NORMAL,
        status = status,
        dedupeKey = "k1",
        scopeKey = "scope",
        question = "Which one?",
        options = listOf(DecisionRequestOption("a", "Label A")),
        raisedById = "api:orch",
        raisedByKind = "service",
        raisedAt = now,
        ackById = if (status == DecisionRequestStatus.ACKNOWLEDGED) "api:person" else null,
        ackByKind = if (status == DecisionRequestStatus.ACKNOWLEDGED) "human" else null,
        ackAt = if (status == DecisionRequestStatus.ACKNOWLEDGED) now else null,
        waitingOn = waitingOn,
        waitingReason = waitingReason,
        answer = answer
    )

    private fun answer(
        decidedBy: DecisionDecidedBy? = null,
        basis: DecisionAnswerBasis? = null,
        relayed: Boolean? = null,
        withinAutonomy: Boolean? = null,
        links: List<RunSeatLink> = emptyList()
    ) = DecisionRequestAnswer(
        option = "a",
        answeredById = "api:svc",
        answeredByKind = "service",
        answeredByProof = "issued",
        answeredAt = now,
        decidedBy = decidedBy,
        basis = basis,
        relayed = relayed,
        withinAutonomy = withinAutonomy,
        links = links
    )

    private fun full(
        decision: DecisionRequest,
        principalsVisible: Boolean = true
    ): JsonObject = DecisionView.full(decision, now, principalsVisible)

    private fun JsonObject.answerObject(): JsonObject = getValue("answer").jsonObject

    // ------------------------------------------------------------------ S23: the full view

    @Test
    fun `S23 an unrouted decision shows neither waitingOn nor waitingReason`() {
        val view = full(decision())

        for (member in newMembers) assertFalse(member in view.keys, "an unrouted decision has no $member: $view")
        // fixture condition: the routed twin shows the member, so the absence above can fail
        assertTrue("waitingOn" in full(decision(waitingOn = DecisionWaitingOn.DIRECTOR)).keys)
    }

    @Test
    fun `S23 a delegated decision shows waitingOn director and no waitingReason`() {
        val view = full(decision(waitingOn = DecisionWaitingOn.DIRECTOR))

        assertEquals("director", view.getValue("waitingOn").jsonPrimitive.content)
        assertFalse("waitingReason" in view.keys, "$view")
        assertEquals("open", view.getValue("status").jsonPrimitive.content, "the status is untouched by the routing")
    }

    @Test
    fun `S23 an escalated decision shows waitingOn user and its waitingReason, also while acknowledged`() {
        for (status in listOf(DecisionRequestStatus.OPEN, DecisionRequestStatus.ACKNOWLEDGED)) {
            val view = full(decision(status = status, waitingOn = DecisionWaitingOn.USER, waitingReason = "needs the product owner"))

            assertEquals("user", view.getValue("waitingOn").jsonPrimitive.content, "$status")
            assertEquals("needs the product owner", view.getValue("waitingReason").jsonPrimitive.content, "$status")
            assertEquals(status.wire, view.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun `S23 an answered decision shows the four provenance members and the links, with booleans as JSON booleans`() {
        val links = listOf(RunSeatLink("url", "https://example.test/a"), RunSeatLink("commit", "abc1234"))
        val answered =
            decision(
                status = DecisionRequestStatus.ANSWERED,
                answer =
                    answer(
                        decidedBy = DecisionDecidedBy.DIRECTOR,
                        basis = DecisionAnswerBasis.DELEGATION,
                        relayed = false,
                        withinAutonomy = true,
                        links = links
                    )
            )

        for (visible in listOf(true, false)) {
            val shown = full(answered, visible).answerObject()

            assertEquals("director", shown.getValue("decidedBy").jsonPrimitive.content, "visible=$visible")
            assertEquals("delegation", shown.getValue("basis").jsonPrimitive.content)
            val relayed = shown.getValue("relayed") as JsonPrimitive
            assertFalse(relayed.isString, "relayed is a JSON boolean, not a string: $relayed")
            assertEquals(false, relayed.boolean)
            val within = shown.getValue("withinAutonomy") as JsonPrimitive
            assertFalse(within.isString, "withinAutonomy is a JSON boolean, not a string: $within")
            assertEquals(true, within.boolean)
            val shownLinks: JsonArray = shown.getValue("links").jsonArray
            assertEquals(
                links.map { it.kind to it.ref },
                shownLinks.map {
                    it.jsonObject
                        .getValue(
                            "kind"
                        ).jsonPrimitive.content to
                        it.jsonObject
                            .getValue("ref")
                            .jsonPrimitive.content
                },
                "the links in order, each as {kind, ref}"
            )
            assertEquals("a", shown.getValue("option").jsonPrimitive.content, "the existing answer members stay")
        }
    }

    @Test
    fun `S23 the relayed provenance shows decidedBy user, basis relayed and relayed true`() {
        val shown =
            full(
                decision(
                    status = DecisionRequestStatus.ANSWERED,
                    answer = answer(DecisionDecidedBy.USER, DecisionAnswerBasis.RELAYED, relayed = true)
                )
            ).answerObject()

        assertEquals("user", shown.getValue("decidedBy").jsonPrimitive.content)
        assertEquals("relayed", shown.getValue("basis").jsonPrimitive.content)
        assertEquals(true, (shown.getValue("relayed") as JsonPrimitive).boolean)
        assertFalse("withinAutonomy" in shown.keys, "absent, so omitted: $shown")
    }

    @Test
    fun `S23 absent provenance members are omitted from the answer, never JSON null, and a present false is kept`() {
        val none = full(decision(status = DecisionRequestStatus.ANSWERED, answer = answer())).answerObject()
        for (member in newAnswerMembers) assertFalse(member in none.keys, "an answer with no $member omits it: $none")

        // the user answer: decidedBy and relayed set, basis and withinAutonomy absent; relayed false is present, not omitted
        val human =
            full(
                decision(status = DecisionRequestStatus.ANSWERED, answer = answer(DecisionDecidedBy.USER, relayed = false))
            ).answerObject()
        assertEquals("user", human.getValue("decidedBy").jsonPrimitive.content)
        assertEquals(false, (human.getValue("relayed") as JsonPrimitive).boolean)
        assertFalse("basis" in human.keys, "$human")
        assertFalse("withinAutonomy" in human.keys, "$human")
    }

    @Test
    fun `S23 the summary is unchanged by the routing - no routing member, and equal to its unrouted twin`() {
        val plain = decision()
        val routed =
            listOf(
                plain.copy(waitingOn = DecisionWaitingOn.DIRECTOR),
                plain.copy(waitingOn = DecisionWaitingOn.USER, waitingReason = "needs the product owner")
            )

        for (twin in routed) {
            val summary = DecisionView.summary(twin, now)
            for (member in newMembers) assertFalse(member in summary.keys, "the summary has no $member: $summary")
            assertEquals(DecisionView.summary(plain, now), summary, "the summary of a routed decision equals its unrouted twin")
        }
        // fixture condition: the full view of the same twins does differ, so the summary equality is not vacuous
        assertTrue(full(routed.first()) != full(plain))
    }

    // ------------------------------------------------------------------ S24, S27: stored rows

    private suspend fun rigAndService(): Triple<EventLogRig, io.github.jpicklyk.mcptask.current.run.DecisionService, DecisionRaiseCommand> {
        val rig = EventLogRig.build(db.db, dir, "work_item_schemas:\n  test-item:\n    notes: []\n", clock)
        val root = rig.seed("Decision root")
        val item = rig.seed("Decision item", parent = root)
        val svc = ToolServiceRegistry(rig.ctx).get(RunToolServices::class).decisionService
        val command =
            DecisionRaiseCommand(
                rootId = root.id,
                itemId = item.id,
                decisionClass = "deviation",
                question = "Which one?",
                options = listOf(DecisionRequestOption("a", "Label A"), DecisionRequestOption("b", "Label B")),
                dedupeKey = "k1",
                raisedById = "api:orch",
                raisedByKind = "service"
            )
        return Triple(rig, svc, command)
    }

    private fun caller(
        id: String,
        kind: PrincipalKind,
        director: Boolean = false,
        seat: String? = null
    ) = DecisionCaller(
        principalId = id,
        kind = kind,
        proofStatus = ProofStatus.ISSUED,
        director = director,
        seat = seat,
        runId = null,
        assignmentId = null,
        provenance = AnswerProvenance(id, kind, ProofStatus.ISSUED, "laptop-7", "sess-77", null, seat, "rest")
    )

    private fun <T> Outcome<T>.ok(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

    @Test
    fun `S24 a stored decision whose new columns are NULL shows no new key, open or answered by a seat`(): Unit =
        runBlocking {
            val (rig, svc, command) = rigAndService()
            val open = svc.raise(command).ok().decision
            val seated = svc.raise(command.copy(decisionClass = "question", dedupeKey = "k2")).ok().decision
            svc.answer(seated.id, DecisionAnswerCommand(option = "a"), caller("api:seat", PrincipalKind.SERVICE, seat = "implementer")).ok()
            val store = rig.provider.decisionRequestStore()

            val openView = full(assertNotNull(store.getById(open.id)))
            for (member in newMembers) assertFalse(member in openView.keys, "no $member on a decision that was never routed: $openView")
            val answeredView = full(assertNotNull(store.getById(seated.id))).answerObject()
            for (member in newAnswerMembers) assertFalse(member in answeredView.keys, "a seat answer records no $member: $answeredView")
            assertFalse(
                "links" in answeredView.keys && answeredView.getValue("links").jsonArray.isNotEmpty(),
                "and no evidence links: $answeredView"
            )
            assertEquals("a", answeredView.getValue("option").jsonPrimitive.content, "control: the answer itself is shown")
        }

    @Test
    fun `S27 the routing and the provenance survive a re-read from storage and show in the view`(): Unit =
        runBlocking {
            val (rig, svc, command) = rigAndService()
            val director = caller("api:svc", PrincipalKind.SERVICE, director = true)
            val escalated = svc.raise(command).ok().decision
            val answered = svc.raise(command.copy(dedupeKey = "k2")).ok().decision
            svc.escalate(escalated.id, "needs the product owner", director).ok()
            svc.delegate(answered.id, director).ok()
            val links = listOf(RunSeatLink("url", "https://example.test/evidence"), RunSeatLink("note", "evidence-note"))
            svc.answer(answered.id, DecisionAnswerCommand(option = "b", links = links), director).ok()
            val store = rig.provider.decisionRequestStore()

            val escalatedStored = assertNotNull(store.getById(escalated.id))
            assertEquals(DecisionWaitingOn.USER, escalatedStored.waitingOn)
            assertEquals("needs the product owner", escalatedStored.waitingReason)
            val escalatedView = full(escalatedStored)
            assertEquals("user", escalatedView.getValue("waitingOn").jsonPrimitive.content)
            assertEquals("needs the product owner", escalatedView.getValue("waitingReason").jsonPrimitive.content)

            val answeredStored = assertNotNull(store.getById(answered.id))
            assertEquals(DecisionRequestStatus.ANSWERED, answeredStored.status)
            assertEquals(null, answeredStored.waitingOn, "a closed decision waits on no one")
            val storedAnswer = assertNotNull(answeredStored.answer)
            assertEquals(DecisionDecidedBy.DIRECTOR, storedAnswer.decidedBy)
            assertEquals(DecisionAnswerBasis.DELEGATION, storedAnswer.basis)
            assertEquals(false, storedAnswer.relayed)
            assertEquals(true, storedAnswer.withinAutonomy)
            assertEquals(links, storedAnswer.links)
            val shown = full(answeredStored)
            assertFalse("waitingOn" in shown.keys, "the answered view shows no routing: $shown")
            val answerView = shown.answerObject()
            assertEquals("director", answerView.getValue("decidedBy").jsonPrimitive.content)
            assertEquals("delegation", answerView.getValue("basis").jsonPrimitive.content)
            assertEquals(false, (answerView.getValue("relayed") as JsonPrimitive).boolean)
            assertEquals(true, (answerView.getValue("withinAutonomy") as JsonPrimitive).boolean)
            assertEquals(
                links.map { it.ref },
                answerView.getValue("links").jsonArray.map {
                    it.jsonObject
                        .getValue("ref")
                        .jsonPrimitive.content
                }
            )
        }
}

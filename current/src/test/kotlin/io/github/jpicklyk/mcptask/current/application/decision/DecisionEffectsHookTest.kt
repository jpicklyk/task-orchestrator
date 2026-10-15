package io.github.jpicklyk.mcptask.current.application.decision

import io.github.jpicklyk.mcptask.current.application.api.AnswerProvenance
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.DecisionEffects
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item a53e6875, R24, round 2) for the [DecisionEffects] hook of [DecisionService].
 *
 * Oracle: orchestrator Ruling 3 and the supplementary declaration: a hook supplied at construction runs inside the answer's unit,
 * after the answered row is stored and before `decision.answered` is recorded; a hook that throws rolls the answer back; the NONE
 * hook changes nothing. The service is built here over the production composition (decorated provider, the composition's unit of
 * work) with the hook under test.
 *
 * Negative-assertion fixture conditions: the rollback test pairs the throwing hook with a control (the same answer through the NONE
 * hook is answered and records one event), so a rollback that did not happen would leave status answered / one event and fail.
 *
 * Forbidden-construct declaration: none. The thrown-exception assertion is a typed assertThrows, nothing swallowed.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionEffectsHookTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val answerer =
        DecisionCaller(
            principalId = "api:person",
            kind = PrincipalKind.HUMAN,
            proofStatus = ProofStatus.ISSUED,
            director = false,
            seat = null,
            runId = null,
            assignmentId = null,
            provenance = AnswerProvenance("api:person", PrincipalKind.HUMAN, ProofStatus.ISSUED, "laptop-7", "sess-77", null, null, "rest")
        )

    private inner class Rig(
        val rig: EventLogRig,
        val service: DecisionService,
        val raised: DecisionRequest
    ) {
        suspend fun answeredEvents() = rig.rows().filter { it.type == "decision.answered" }

        suspend fun maxSeq() = rig.maxSeq()
    }

    private suspend fun rig(effects: DecisionEffects): Rig {
        val rig = EventLogRig.build(db.db, dir, "work_item_schemas:\n  test-item:\n    notes: []\n", clock)
        val root = rig.seed("Decision root")
        val item = rig.seed("Decision item", parent = root)
        val service = DecisionService(rig.provider, rig.composition.unitOfWork, { emptyMap() }, effects)
        val raised =
            service
                .raise(
                    DecisionRaiseCommand(
                        rootId = root.id,
                        runId = null,
                        itemId = item.id,
                        decisionClass = "question",
                        question = "Which one?",
                        options = listOf(DecisionRequestOption("a", "Label A"), DecisionRequestOption("b", "Label B")),
                        recommended = null,
                        links = emptyList(),
                        context = null,
                        dedupeKey = "k1",
                        severity = null,
                        raisedById = "api:orch",
                        raisedByKind = "service",
                        raisedBySeat = null
                    )
                ).getOrNull()!!
                .decision
        return Rig(rig, service, raised)
    }

    @Test
    fun `the hook runs once inside the answer unit, sees the answered decision, and runs before decision answered is recorded`(): Unit =
        runBlocking {
            val seen = mutableListOf<DecisionRequest>()
            val storedStatusInside = mutableListOf<DecisionRequestStatus?>()
            val answeredEventsInside = mutableListOf<Int>()
            lateinit var r: Rig
            val hook =
                DecisionEffects { decision ->
                    seen += decision
                    storedStatusInside +=
                        r.rig.provider
                            .decisionRequestStore()
                            .getById(decision.id)
                            ?.status
                    answeredEventsInside += r.answeredEvents().size
                }
            r = rig(hook)
            assertEquals(DecisionRequestStatus.OPEN, r.raised.status)
            val seqBefore = r.maxSeq()

            val result = r.service.answer(r.raised.id, DecisionAnswerCommand(option = "b"), answerer).getOrNull()

            assertNotNull(result, "the answer succeeds")
            assertEquals(1, seen.size, "the hook runs exactly once")
            val hooked = seen.single()
            assertEquals(r.raised.id, hooked.id)
            assertEquals(DecisionRequestStatus.ANSWERED, hooked.status, "the hook sees the answered decision")
            assertEquals("b", hooked.answer?.option)
            assertEquals(
                listOf<DecisionRequestStatus?>(DecisionRequestStatus.ANSWERED),
                storedStatusInside,
                "the answered row is already stored when the hook runs"
            )
            assertEquals(listOf(0), answeredEventsInside, "decision.answered is not yet recorded when the hook runs")
            assertEquals(1, r.answeredEvents().size, "the answered event is recorded after the hook")
            assertEquals(seqBefore + 1, r.maxSeq(), "exactly one event row")
            assertEquals(DecisionRequestStatus.ANSWERED, result.status)
        }

    @Test
    fun `a hook that throws rolls the answer back - still pending, no answered event, the error surfaces`(): Unit =
        runBlocking {
            val r = rig(DecisionEffects { throw IllegalStateException("effects exploded") })
            val seqBefore = r.maxSeq()

            val thrown =
                assertThrows<IllegalStateException> {
                    runBlocking { r.service.answer(r.raised.id, DecisionAnswerCommand(option = "a"), answerer) }
                }

            assertEquals("effects exploded", thrown.message, "the hook's own error surfaces")
            val stored =
                r.rig.provider
                    .decisionRequestStore()
                    .getById(r.raised.id)
            assertNotNull(stored)
            assertEquals(DecisionRequestStatus.OPEN, stored.status, "the answer was rolled back")
            assertNull(stored.answer)
            assertEquals(r.raised.version, stored.version, "the version did not move")
            assertEquals(0, r.answeredEvents().size, "no decision.answered event")
            assertEquals(seqBefore, r.maxSeq(), "no event row of any kind was recorded by the failed answer")

            // control: the same answer with the NONE hook lands, so the assertions above can fail
            val control = rig(DecisionEffects.NONE)
            control.service.answer(control.raised.id, DecisionAnswerCommand(option = "a"), answerer).getOrNull()
            assertEquals(1, control.answeredEvents().size)
        }

    @Test
    fun `the NONE hook changes nothing - answered, version bumped once, one answered event`(): Unit =
        runBlocking {
            val r = rig(DecisionEffects.NONE)
            val seqBefore = r.maxSeq()

            val result = r.service.answer(r.raised.id, DecisionAnswerCommand(option = "a"), answerer).getOrNull()

            assertNotNull(result)
            assertEquals(DecisionRequestStatus.ANSWERED, result.status)
            assertEquals(r.raised.version + 1, result.version)
            assertEquals(1, r.answeredEvents().size)
            assertEquals(seqBefore + 1, r.maxSeq(), "exactly one event row")
        }
}

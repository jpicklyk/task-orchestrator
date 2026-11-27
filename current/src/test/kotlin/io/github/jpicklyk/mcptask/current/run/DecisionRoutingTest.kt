package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolServiceRegistry
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.run.DecisionDecidedBy
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.model.run.DecisionWaitingOn
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.platform.call.AnswerProvenance
import io.github.jpicklyk.mcptask.current.platform.call.PrincipalKind
import io.github.jpicklyk.mcptask.current.platform.call.ProofStatus
import io.github.jpicklyk.mcptask.current.platform.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.BaselineDataset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item eb933b68) for who a decision waits on: `DecisionService.delegate` and `escalate`, the
 * refusal of an answer that is not the user's while a decision is escalated, and the clearing of the routing when a decision closes.
 *
 * Oracles (never read from a run): the frozen task-scope decisions D1, D1b, D4, D5 and D6 and the migration-assessment column list
 * (the stored row is checked by raw SQL against the assessment's column names). In short: `waitingOn` is `director` or `user`
 * with a `waitingReason` only beside `user`; the status never changes on delegate or escalate; delegate needs the class's answerers
 * to admit the caller (else `forbidden` of scope `principal_kind`), a pending decision (else `decision_closed{decisionId, status}`),
 * and a class the director could answer (a human-only class is `invalid_request` on `/decisionId`); escalate admits the class's
 * answerers or the director, needs a pending decision and a reason of 1 to 500 characters that is not blank (else
 * `invalid_request` on `/reason`); an unknown id is `not_found{kind: decision}`; every effective write bumps the version once and
 * records one `decision.delegated` / `decision.escalated` event carrying `waitingOn` and never the reason; a repeat with the
 * same effect changes nothing and records nothing; while a decision waits on `user` an answer whose recorded `decidedBy` is not
 * `user` is `invalid_request` on `/decidedBy` with a reason that names delegate, writes nothing and records nothing; an answered or
 * withdrawn decision waits on no one.
 *
 * Negative-assertion fixture conditions: every refusal is followed by a read of the stored row (unchanged, by value) and of the
 * event table (no new decision event), and is paired with the same call on the input that succeeds.
 *
 * Domain invariants satisfied by construction: items are children of the root and seeded through the rig; the run is seeded through
 * `ParitySeeder.run`; options are built by one helper that satisfies the key pattern and the option limits; every [DecisionCaller]
 * derives its provenance from the same fields.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionRoutingTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val CONFIG = """
work_item_schemas:
  test-item:
    notes: []
decision_classes:
  signoff:
    answerers: human-only
"""
        const val ORCH = "api:orch"
    }

    private fun <T> Outcome<T>.ok(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

    private fun <T> Outcome<T>.err(): DomainError = (this as? Outcome.Err)?.error ?: error("expected Outcome.Err, got $this")

    private inner class Fixture(
        val rig: EventLogRig,
        val root: WorkItem,
        val item: WorkItem,
        val runA: UUID
    ) {
        val svc get() = ToolServiceRegistry(rig.ctx).get(RunToolServices::class).decisionService

        /** Raises one decision of [decisionClass] (deviation: director-or-human; signoff: human-only; question: any-actor). */
        suspend fun raise(
            decisionClass: String,
            key: String = "k1"
        ): DecisionRequest =
            svc
                .raise(
                    DecisionRaiseCommand(
                        rootId = root.id,
                        runId = null,
                        itemId = item.id,
                        decisionClass = decisionClass,
                        question = "Which one?",
                        options = listOf(DecisionRequestOption("a", "Label A"), DecisionRequestOption("b", "Label B")),
                        dedupeKey = key,
                        raisedById = ORCH,
                        raisedByKind = "service"
                    )
                ).ok()
                .decision
    }

    private suspend fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, CONFIG, clock)
        val root = rig.seed("Decision root")
        val item = rig.seed("Decision item", parent = root)
        val provider = db.db.repositoryProvider()
        val seeder = ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork(clock))
        val runA = UUID.fromString("a0000001-0000-4000-8000-000000000001")
        seeder.run(runA, root.id, listOf(item.id), openedBy = ORCH, openedByKind = "service")
        return Fixture(rig, root, item, runA)
    }

    // ------------------------------------------------------------------ callers (the shape the tool layer builds)

    private fun caller(
        id: String = "api:person",
        kind: PrincipalKind? = PrincipalKind.HUMAN,
        proof: ProofStatus? = ProofStatus.ISSUED,
        director: Boolean = false,
        seat: String? = null,
        runId: UUID? = null
    ) = DecisionCaller(
        principalId = id,
        kind = kind,
        proofStatus = proof,
        director = director,
        seat = seat,
        runId = runId,
        assignmentId = null,
        provenance =
            if (kind != null && proof != null) {
                AnswerProvenance(id, kind, proof, "laptop-7", "sess-77", runId, seat, if (director) "director" else "rest")
            } else {
                null
            }
    )

    private val human get() = caller()
    private val director get() = caller("api:svc", PrincipalKind.SERVICE, ProofStatus.ISSUED, director = true)
    private val orchestrator get() = caller(ORCH, PrincipalKind.SERVICE, ProofStatus.ISSUED)

    private fun seat(f: Fixture) = caller("api:seat", PrincipalKind.SERVICE, ProofStatus.ISSUED, seat = "implementer", runId = f.runA)

    // ------------------------------------------------------------------ raw reads

    private fun query(
        sql: String,
        vararg args: Any?
    ): List<Map<String, Any?>> =
        DriverManager.getConnection(db.jdbcUrl).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> ps.setNull(i + 1, java.sql.Types.NULL)
                        is UUID -> ps.setBytes(i + 1, BaselineDataset.bytes(a))
                        else -> ps.setString(i + 1, a.toString())
                    }
                }
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add(
                                (1..meta.columnCount).associate { column ->
                                    meta.getColumnLabel(column) to rs.getObject(column).let { v -> if (v is Int) v.toLong() else v }
                                }
                            )
                        }
                    }
                }
            }
        }

    private fun row(id: UUID): Map<String, Any?> = query("SELECT * FROM decision_requests WHERE id = ?", id).single()

    private fun frozen(id: UUID): Map<String, Any?> = row(id).mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }

    private fun events(type: String): List<Map<String, Any?>> = query("SELECT * FROM events WHERE type = ? ORDER BY seq", type)

    private fun decisionEventCount(): Int = query("SELECT * FROM events WHERE type LIKE 'decision.%'").size

    private fun uuidOf(bytes: Any?): UUID = ByteBuffer.wrap(bytes as ByteArray).let { UUID(it.long, it.long) }

    private fun data(event: Map<String, Any?>): JsonObject = Json.parseToJsonElement(event["data"] as String).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun detail(error: DomainError): JsonObject = EnvelopeEncoder.detail(assertNotNull(error.detail, "the error carries a detail"))

    private fun pointers(error: DomainError): List<String> =
        detail(error).getValue("fields").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }

    private fun reasons(error: DomainError): List<String> =
        detail(error).getValue("fields").jsonArray.map { it.jsonObject.getValue("reason").jsonPrimitive.content }

    private fun assertInvalid(
        error: DomainError,
        pointer: String,
        label: String
    ) {
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, "$label: ${error.message}")
        assertTrue(pointer in pointers(error), "$label: expected a violation at $pointer, got ${pointers(error)}")
    }

    private fun assertForbiddenKind(
        error: DomainError,
        answerers: DecisionRequestAnswerers,
        label: String
    ) {
        assertEquals(ErrorCode.FORBIDDEN, error.code, "$label: ${error.message}")
        assertEquals("principal_kind", detail(error).text("scope"), label)
        assertEquals(answerers.required(), detail(error).text("required"), label)
    }

    private fun assertClosed(
        error: DomainError,
        id: UUID,
        status: String,
        label: String
    ) {
        assertEquals(ErrorCode.DECISION_CLOSED, error.code, "$label: ${error.message}")
        assertEquals(id.toString(), detail(error).text("decisionId"), label)
        assertEquals(status, detail(error).text("status"), label)
    }

    // ------------------------------------------------------------------ S1, S2: delegate

    @Test
    fun `S1 the director delegates an open decision - waitingOn director, status open, version plus one, one delegated event`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            val eventsBefore = decisionEventCount()

            val result = f.svc.delegate(d.id, director).ok()

            assertEquals(DecisionWaitingOn.DIRECTOR, result.waitingOn)
            assertNull(result.waitingReason)
            assertEquals(DecisionRequestStatus.OPEN, result.status, "delegating never changes the status")
            assertEquals(2L, result.version)
            val r = row(d.id)
            assertEquals("director", r["waiting_on"])
            assertNull(r["waiting_reason"])
            assertEquals("open", r["status"])
            assertEquals(2L, r["version"])
            assertEquals(eventsBefore + 1, decisionEventCount(), "exactly one decision event")
            val event = events("decision.delegated").single()
            assertEquals("decision", event["entity_kind"])
            assertEquals(d.id, uuidOf(event["entity_id"]))
            assertEquals(f.root.id, uuidOf(event["root_id"]))
            val payload = data(event)
            assertEquals("director", payload.text("waitingOn"))
            assertEquals("deviation", payload.text("class"))
            assertEquals("k1", payload.text("dedupeKey"))
        }

    @Test
    fun `S2 delegating an acknowledged decision sets waitingOn director and the decision stays acknowledged`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.acknowledge(d.id, human).ok()
            assertEquals(2L, row(d.id)["version"], "fixture condition: the ack bumped the version")

            val result = f.svc.delegate(d.id, director).ok()

            assertEquals(DecisionWaitingOn.DIRECTOR, result.waitingOn)
            assertEquals(DecisionRequestStatus.ACKNOWLEDGED, result.status)
            val r = row(d.id)
            assertEquals("acknowledged", r["status"])
            assertEquals("director", r["waiting_on"])
            assertEquals(3L, r["version"])
            assertEquals(1, events("decision.delegated").size)
            assertEquals("api:person", r["ack_by_id"], "the ack record is kept")
        }

    @Test
    fun `S3 a human-only decision cannot be delegated - the human who may answer it gets invalid_request on decisionId, nothing is written`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("signoff")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            val error = f.svc.delegate(d.id, human).err()

            assertInvalid(error, "/decisionId", "a human delegating a human-only decision")
            assertEquals(before, frozen(d.id), "the row is unchanged")
            assertEquals(eventsBefore, decisionEventCount(), "no event")

            // the director is not admitted by a human-only class at all, so it is refused before the class check (order D5: forbidden first)
            assertForbiddenKind(f.svc.delegate(d.id, director).err(), DecisionRequestAnswerers.HUMAN_ONLY, "director on human-only")
            assertEquals(before, frozen(d.id))

            // control: a director-or-human decision is delegated by the same human
            val ok = f.raise("deviation", "k2")
            assertEquals(DecisionWaitingOn.DIRECTOR, f.svc.delegate(ok.id, human).ok().waitingOn)
        }

    @Test
    fun `S4 a seat-token caller cannot delegate a director-or-human decision, open or answered`(): Unit =
        runBlocking {
            val f = fixture()
            val open = f.raise("deviation")
            val answered = f.raise("deviation", "k2")
            f.svc.answer(answered.id, DecisionAnswerCommand(option = "a"), director).ok()
            val openBefore = frozen(open.id)
            val answeredBefore = frozen(answered.id)
            val eventsBefore = decisionEventCount()

            assertForbiddenKind(f.svc.delegate(open.id, seat(f)).err(), DecisionRequestAnswerers.DIRECTOR_OR_HUMAN, "seat on open")
            assertForbiddenKind(f.svc.delegate(answered.id, seat(f)).err(), DecisionRequestAnswerers.DIRECTOR_OR_HUMAN, "seat on answered")

            assertEquals(openBefore, frozen(open.id))
            assertEquals(answeredBefore, frozen(answered.id))
            assertEquals(eventsBefore, decisionEventCount())
            // control: the director delegates the same open decision
            assertEquals(DecisionWaitingOn.DIRECTOR, f.svc.delegate(open.id, director).ok().waitingOn)
        }

    @Test
    fun `S5 delegating twice changes nothing the second time - same version, no second event`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.delegate(d.id, director).ok()
            val once = frozen(d.id)

            val again = f.svc.delegate(d.id, director).ok()

            assertEquals(DecisionWaitingOn.DIRECTOR, again.waitingOn)
            assertEquals(2L, again.version)
            assertEquals(once, frozen(d.id), "the row is returned unchanged")
            assertEquals(1, events("decision.delegated").size, "no second event")
        }

    @Test
    fun `S6 delegate and escalate of an answered or a withdrawn decision are decision_closed with its status`(): Unit =
        runBlocking {
            val f = fixture()
            val answered = f.raise("deviation")
            f.svc.answer(answered.id, DecisionAnswerCommand(option = "a"), director).ok()
            val withdrawn = f.raise("deviation", "k2")
            f.svc.withdraw(withdrawn.id, orchestrator).ok()
            val answeredBefore = frozen(answered.id)
            val withdrawnBefore = frozen(withdrawn.id)
            val eventsBefore = decisionEventCount()

            assertClosed(f.svc.delegate(answered.id, director).err(), answered.id, "answered", "delegate answered")
            assertClosed(f.svc.escalate(answered.id, "why", director).err(), answered.id, "answered", "escalate answered")
            assertClosed(f.svc.delegate(withdrawn.id, director).err(), withdrawn.id, "withdrawn", "delegate withdrawn")
            assertClosed(f.svc.escalate(withdrawn.id, "why", director).err(), withdrawn.id, "withdrawn", "escalate withdrawn")

            assertEquals(answeredBefore, frozen(answered.id))
            assertEquals(withdrawnBefore, frozen(withdrawn.id))
            assertEquals(eventsBefore, decisionEventCount())
            // control: a pending decision takes both
            val pending = f.raise("deviation", "k3")
            f.svc.delegate(pending.id, director).ok()
            f.svc.escalate(pending.id, "why", director).ok()
        }

    @Test
    fun `S7 delegate and escalate of an unknown decision are not_found of kind decision`(): Unit =
        runBlocking {
            val f = fixture()
            f.raise("deviation")
            val ghost = UUID.randomUUID()
            val eventsBefore = decisionEventCount()

            for ((what, error) in listOf(
                "delegate" to f.svc.delegate(ghost, director).err(),
                "escalate" to f.svc.escalate(ghost, "why", director).err()
            )) {
                assertEquals(ErrorCode.NOT_FOUND, error.code, what)
                assertEquals("decision", detail(error).text("kind"), what)
            }
            assertEquals(eventsBefore, decisionEventCount())
        }

    // ------------------------------------------------------------------ S8 to S12: escalate

    @Test
    fun `S8 escalate with a reason - waitingOn user, the reason stored, status unchanged, event with waitingOn and without the reason`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            val eventsBefore = decisionEventCount()

            val result = f.svc.escalate(d.id, "needs the product owner", director).ok()

            assertEquals(DecisionWaitingOn.USER, result.waitingOn)
            assertEquals("needs the product owner", result.waitingReason)
            assertEquals(DecisionRequestStatus.OPEN, result.status)
            assertEquals(2L, result.version)
            val r = row(d.id)
            assertEquals("user", r["waiting_on"])
            assertEquals("needs the product owner", r["waiting_reason"])
            assertEquals("open", r["status"])
            assertEquals(2L, r["version"])
            assertEquals(eventsBefore + 1, decisionEventCount())
            val event = events("decision.escalated").single()
            assertEquals(d.id, uuidOf(event["entity_id"]))
            assertEquals("user", data(event).text("waitingOn"))
            assertFalse("needs the product owner" in (event["data"] as String), "the event never carries the reason: ${event["data"]}")
            assertFalse("waitingReason" in data(event).keys, "no waitingReason member: ${event["data"]}")
        }

    @Test
    fun `S9 the director may escalate a human-only decision, a seat-token caller may not escalate any class`(): Unit =
        runBlocking {
            val f = fixture()
            val humanOnly = f.raise("signoff")
            val shared = f.raise("deviation", "k2")
            val eventsBefore = decisionEventCount()
            val humanOnlyBefore = frozen(humanOnly.id)

            for ((what, error) in listOf(
                "seat on human-only" to f.svc.escalate(humanOnly.id, "why", seat(f)).err(),
                "seat on director-or-human" to f.svc.escalate(shared.id, "why", seat(f)).err()
            )) {
                assertEquals(ErrorCode.FORBIDDEN, error.code, what)
                assertEquals("principal_kind", detail(error).text("scope"), what)
            }
            assertEquals(humanOnlyBefore, frozen(humanOnly.id))
            assertEquals(1L, row(shared.id)["version"])
            assertEquals(eventsBefore, decisionEventCount())

            // controls: the director escalates the human-only decision, and a human escalates it too
            assertEquals(DecisionWaitingOn.USER, f.svc.escalate(humanOnly.id, "why", director).ok().waitingOn)
            assertEquals("user", row(humanOnly.id)["waiting_on"])
            assertEquals(DecisionWaitingOn.USER, f.svc.escalate(shared.id, "why", human).ok().waitingOn)
        }

    @Test
    fun `S10 the escalate reason is 1 to 500 characters and not blank, else invalid_request on reason`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            for ((what, reason) in listOf(
                "empty" to "",
                "one space" to " ",
                "spaces" to "   ",
                "tab and newline" to "\t\n",
                "501 characters" to "r".repeat(501)
            )) {
                assertInvalid(f.svc.escalate(d.id, reason, director).err(), "/reason", what)
                assertEquals(before, frozen(d.id), "$what: nothing written")
                assertEquals(eventsBefore, decisionEventCount(), "$what: no event")
            }

            // boundaries: 1 and 500 characters are accepted
            assertEquals("x", f.svc.escalate(d.id, "x", director).ok().waitingReason)
            assertEquals("x", row(d.id)["waiting_reason"])
            val longest = "r".repeat(500)
            assertEquals(longest, f.svc.escalate(d.id, longest, director).ok().waitingReason)
            assertEquals(longest, row(d.id)["waiting_reason"])
        }

    @Test
    fun `S11 escalating again with the same reason changes nothing, with a new reason updates it and records an event`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.escalate(d.id, "first reason", director).ok()
            val once = frozen(d.id)
            assertEquals(1, events("decision.escalated").size)

            val same = f.svc.escalate(d.id, "first reason", director).ok()
            assertEquals(2L, same.version)
            assertEquals(once, frozen(d.id), "the same waitingOn and reason: unchanged")
            assertEquals(1, events("decision.escalated").size, "no event for a no-op")

            val updated = f.svc.escalate(d.id, "second reason", director).ok()
            assertEquals("second reason", updated.waitingReason)
            assertEquals(3L, updated.version)
            assertEquals("second reason", row(d.id)["waiting_reason"])
            assertEquals(3L, row(d.id)["version"])
            assertEquals(2, events("decision.escalated").size, "a changed reason is a write")
        }

    @Test
    fun `S12 escalate then delegate hands the decision back to the director with no reason, and escalate again goes to the user`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.escalate(d.id, "to the user", director).ok()

            val back = f.svc.delegate(d.id, director).ok()
            assertEquals(DecisionWaitingOn.DIRECTOR, back.waitingOn)
            assertNull(back.waitingReason, "the reason goes with user only")
            val r = row(d.id)
            assertEquals("director", r["waiting_on"])
            assertNull(r["waiting_reason"])
            assertEquals(3L, r["version"])
            assertEquals(1, events("decision.delegated").size)

            val again = f.svc.escalate(d.id, "again", director).ok()
            assertEquals(DecisionWaitingOn.USER, again.waitingOn)
            assertEquals("again", row(d.id)["waiting_reason"])
            assertEquals(4L, row(d.id)["version"])
            assertEquals(2, events("decision.escalated").size)
        }

    // ------------------------------------------------------------------ S19 to S21: refusal while escalated, clearing on close

    @Test
    fun `S19 while escalated an answer without the user as decider is refused naming delegate, with the user it lands as relayed`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.escalate(d.id, "ask the user", director).ok()
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), director).err()

            assertInvalid(error, "/decidedBy", "director, no decidedBy, while waiting on the user")
            assertTrue(reasons(error).any { "delegate" in it.lowercase() }, "the reason names delegate as the way back: ${reasons(error)}")
            assertEquals(before, frozen(d.id), "the row is kept")
            assertEquals(eventsBefore, decisionEventCount(), "no event")

            val director2 = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.DIRECTOR), director).err()
            assertInvalid(director2, "/decidedBy", "director sending director while waiting on the user")
            assertEquals(before, frozen(d.id))

            val landed = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), director).ok()
            assertEquals(DecisionRequestStatus.ANSWERED, landed.status)
            val r = row(d.id)
            assertEquals("answered", r["status"])
            assertEquals("user", r["answer_decided_by"])
            assertEquals("relayed", r["answer_basis"])
            assertEquals(1L, r["answer_relayed"])
            assertNull(r["answer_within_autonomy"])
        }

    @Test
    fun `S19 after delegating back the director answers on the delegation basis`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.escalate(d.id, "ask the user", director).ok()
            assertInvalid(f.svc.answer(d.id, DecisionAnswerCommand(option = "b"), director).err(), "/decidedBy", "escalated")

            f.svc.delegate(d.id, director).ok()
            f.svc.answer(d.id, DecisionAnswerCommand(option = "b"), director).ok()

            val r = row(d.id)
            assertEquals("answered", r["status"])
            assertEquals("director", r["answer_decided_by"])
            assertEquals("delegation", r["answer_basis"])
            assertEquals(0L, r["answer_relayed"])
            assertEquals(1L, r["answer_within_autonomy"])
        }

    @Test
    fun `S20 while escalated a seat-token answer is invalid_request on decidedBy, and the human who answers it lands as the user`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")
            f.svc.escalate(d.id, "ask the user", director).ok()
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), seat(f)).err()

            assertInvalid(error, "/decidedBy", "seat answering a decision that waits on the user")
            assertEquals(before, frozen(d.id))
            assertEquals(eventsBefore, decisionEventCount())

            // control: the same decision, answered by a human, is accepted
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()
            assertEquals("answered", row(d.id)["status"])
            assertEquals("user", row(d.id)["answer_decided_by"])
        }

    @Test
    fun `S21 an answered and a withdrawn decision wait on no one, however they were routed`(): Unit =
        runBlocking {
            val f = fixture()
            val escalatedThenAnswered = f.raise("deviation", "k1")
            val delegatedThenAnswered = f.raise("deviation", "k2")
            val escalatedThenWithdrawn = f.raise("deviation", "k3")
            val delegatedThenWithdrawn = f.raise("deviation", "k4")
            f.svc.escalate(escalatedThenAnswered.id, "ask", director).ok()
            f.svc.delegate(delegatedThenAnswered.id, director).ok()
            f.svc.escalate(escalatedThenWithdrawn.id, "ask", director).ok()
            f.svc.delegate(delegatedThenWithdrawn.id, director).ok()
            for (id in listOf(escalatedThenAnswered.id, delegatedThenAnswered.id, escalatedThenWithdrawn.id, delegatedThenWithdrawn.id)) {
                assertTrue(row(id)["waiting_on"] != null, "fixture condition: $id waits before it closes")
            }

            val answeredA = f.svc.answer(escalatedThenAnswered.id, DecisionAnswerCommand(option = "a"), human).ok()
            val answeredB = f.svc.answer(delegatedThenAnswered.id, DecisionAnswerCommand(option = "a"), director).ok()
            val withdrawnA = f.svc.withdraw(escalatedThenWithdrawn.id, orchestrator).ok()
            val withdrawnB = f.svc.withdraw(delegatedThenWithdrawn.id, orchestrator).ok()

            for (result in listOf(answeredA, answeredB, withdrawnA, withdrawnB)) {
                assertNull(result.waitingOn, "${result.status}: the returned decision waits on no one")
                assertNull(result.waitingReason)
                val r = row(result.id)
                assertNull(r["waiting_on"], "${result.status}: stored waiting_on")
                assertNull(r["waiting_reason"], "${result.status}: stored waiting_reason")
            }
            assertEquals("answered", row(answeredA.id)["status"])
            assertEquals("withdrawn", row(withdrawnA.id)["status"])
            // the history stays in the events
            assertEquals(2, events("decision.escalated").size)
            assertEquals(2, events("decision.delegated").size)
        }
}

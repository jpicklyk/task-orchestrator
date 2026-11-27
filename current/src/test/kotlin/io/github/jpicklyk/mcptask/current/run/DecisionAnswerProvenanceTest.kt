package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolServiceRegistry
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.run.DecisionAnswerBasis
import io.github.jpicklyk.mcptask.current.model.run.DecisionDecidedBy
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.model.run.RunSeatLink
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
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
 * Independent tests (test-author seat, item eb933b68) for the provenance an answer records: who decided (`decidedBy`), on what
 * basis, whether it was relayed, whether it was within the director's autonomy, and the answer's evidence links; and for the
 * `decision.answered` event payload that carries the four members.
 *
 * Oracles (never read from a run): the frozen task-scope decisions D3, D6 and D8 and the migration-assessment column names. In
 * short: provenance is derived from the caller's seat and credential kind, never from the serving profile, and the caller sends at
 * most `decidedBy`. A human is recorded `user`, relayed false, with no basis and no withinAutonomy, and sending `director` is
 * `invalid_request` on `/decidedBy`. A caller without a seat token that is not a human is recorded `director`, relayed false, with
 * basis `delegation` and withinAutonomy true when the decision waits on the director at answer time, else (item b00cdc00 D5) basis `autonomy` and
 * withinAutonomy true when the class's autonomy is `answer`, else no basis and withinAutonomy false; a seat-less caller without the
 * decide capability records nothing, and its `decidedBy` is `invalid_request` on `/decidedBy`; sending `user` records `user`, relayed true, basis `relayed` and no withinAutonomy. A seat-token caller
 * has nothing recorded and sending `decidedBy` is `invalid_request` on `/decidedBy`. Answer links are evidence with the raise-link
 * limits (at most 10, kept in the request's order, a known kind, a non-blank ref), violations pointing at `/links`,
 * `/links/i/kind` and `/links/i/ref`. The `decision.answered` payload carries `decidedBy`, `basis`, `relayed` and `withinAutonomy`
 * (null when absent) and never the waiting reason, a principal id, the text or the rationale.
 *
 * Negative-assertion fixture conditions: every refusal is followed by a read of the stored row (unchanged, by value) and of the
 * event table (no new decision event), and is paired with the same call on the input that succeeds.
 *
 * Domain invariants satisfied by construction: items are children of the root and seeded through the rig; the run is seeded through
 * `ParitySeeder.run`; every [DecisionCaller] derives its provenance from the same fields; the delegate set-up uses the service,
 * never a hand-written row.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionAnswerProvenanceTest {
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
  routine:
    answerers: director-or-human
    autonomy: answer
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

        /** Raises one decision of [decisionClass] (deviation: director-or-human; question: any-actor). */
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
        id: String,
        kind: PrincipalKind,
        proof: ProofStatus,
        director: Boolean = false,
        seat: String? = null,
        runId: UUID? = null,
        profile: String = "rest"
    ) = DecisionCaller(
        principalId = id,
        kind = kind,
        proofStatus = proof,
        director = director,
        seat = seat,
        runId = runId,
        assignmentId = null,
        provenance = AnswerProvenance(id, kind, proof, "laptop-7", "sess-77", runId, seat, profile)
    )

    private val human get() = caller("api:person-secret", PrincipalKind.HUMAN, ProofStatus.ISSUED)
    private val director get() = caller("api:svc-secret", PrincipalKind.SERVICE, ProofStatus.ISSUED, director = true, profile = "director")

    /** An agent credential with no seat token, on a profile that is not the director's, whose credential grants decide. */
    private val agent get() = caller("api:agent-secret", PrincipalKind.AGENT, ProofStatus.VERIFIED, director = true)

    /** The same agent credential without the decide capability. */
    private val agentNoDecide get() = caller("api:agent-secret", PrincipalKind.AGENT, ProofStatus.VERIFIED)

    private fun seat(f: Fixture) =
        caller(
            "api:seat-secret",
            PrincipalKind.SERVICE,
            ProofStatus.ISSUED,
            seat = "implementer",
            runId = f.runA,
            profile = "orchestration"
        )

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

    private fun data(event: Map<String, Any?>): JsonObject = Json.parseToJsonElement(event["data"] as String).jsonObject

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun detail(error: DomainError): JsonObject = EnvelopeEncoder.detail(assertNotNull(error.detail, "the error carries a detail"))

    private fun pointers(error: DomainError): List<String> =
        detail(error).getValue("fields").jsonArray.map {
            it.jsonObject
                .getValue("field")
                .jsonPrimitive.content
        }

    private fun assertInvalid(
        error: DomainError,
        pointer: String,
        label: String
    ) {
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, "$label: ${error.message}")
        assertTrue(pointer in pointers(error), "$label: expected a violation at $pointer, got ${pointers(error)}")
    }

    /** The four provenance columns of a row, in the order decided_by, basis, relayed, within_autonomy. */
    private fun provenance(id: UUID): List<Any?> =
        row(id).let { listOf(it["answer_decided_by"], it["answer_basis"], it["answer_relayed"], it["answer_within_autonomy"]) }

    // ------------------------------------------------------------------ S13, S14: the director's answers

    @Test
    fun `S13 a director answer on an unrouted ask decision records director, no basis, not relayed, not within autonomy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), director).ok()

            assertEquals(listOf<Any?>("director", null, 0L, 0L), provenance(d.id))
            val answer = assertNotNull(result.answer)
            assertEquals(DecisionDecidedBy.DIRECTOR, answer.decidedBy)
            assertNull(answer.basis)
            assertEquals(false, answer.relayed)
            assertEquals(false, answer.withinAutonomy)
        }

    @Test
    fun `S13 a director answer on an unrouted autonomy-answer class records the autonomy basis, within autonomy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("routine")

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), director).ok()

            assertEquals(listOf<Any?>("director", "autonomy", 0L, 1L), provenance(d.id))
            val answer = assertNotNull(result.answer)
            assertEquals(DecisionAnswerBasis.AUTONOMY, answer.basis)
            assertEquals(true, answer.withinAutonomy)
            // control: the delegated routine decision records delegation, so the basis turns on the routing first
            val delegated = f.raise("routine", "k2")
            f.svc.delegate(delegated.id, director).ok()
            f.svc.answer(delegated.id, DecisionAnswerCommand(option = "a"), director).ok()
            assertEquals(listOf<Any?>("director", "delegation", 0L, 1L), provenance(delegated.id))
        }

    @Test
    fun `S14 the director answering a decision delegated to it is recorded on the delegation basis, within autonomy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.delegate(d.id, director).ok()

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "b"), director).ok()

            assertEquals(listOf<Any?>("director", "delegation", 0L, 1L), provenance(d.id))
            val answer = assertNotNull(result.answer)
            assertEquals(DecisionDecidedBy.DIRECTOR, answer.decidedBy)
            assertEquals(DecisionAnswerBasis.DELEGATION, answer.basis)
            assertEquals(false, answer.relayed)
            assertEquals(true, answer.withinAutonomy)
        }

    @Test
    fun `S14 the basis follows the routing at answer time, not at raise time - delegated then answered by an agent credential`(): Unit =
        runBlocking {
            val f = fixture()
            val delegated = f.raise("question", "k1")
            val plain = f.raise("question", "k2")
            f.svc.delegate(delegated.id, director).ok()

            f.svc.answer(delegated.id, DecisionAnswerCommand(option = "a"), agent).ok()
            f.svc.answer(plain.id, DecisionAnswerCommand(option = "a"), agent).ok()

            assertEquals(listOf<Any?>("director", "delegation", 0L, 1L), provenance(delegated.id))
            assertEquals(listOf<Any?>("director", null, 0L, 0L), provenance(plain.id))
        }

    // ------------------------------------------------------------------ S15: relayed

    @Test
    fun `S15 a non-human caller that sends the user is recorded user, relayed, on the relayed basis, with no withinAutonomy`(): Unit =
        runBlocking {
            val f = fixture()
            val byDirector = f.raise("deviation", "k1")
            val byAgent = f.raise("question", "k2")
            val whileDelegated = f.raise("deviation", "k3")
            f.svc.delegate(whileDelegated.id, director).ok()

            val result =
                f.svc.answer(byDirector.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), director).ok()
            f.svc.answer(byAgent.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), agent).ok()
            f.svc.answer(whileDelegated.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), director).ok()

            for (id in listOf(byDirector.id, byAgent.id, whileDelegated.id)) {
                assertEquals(listOf<Any?>("user", "relayed", 1L, null), provenance(id), "decision $id")
            }
            val answer = assertNotNull(result.answer)
            assertEquals(DecisionDecidedBy.USER, answer.decidedBy)
            assertEquals(DecisionAnswerBasis.RELAYED, answer.basis)
            assertEquals(true, answer.relayed)
            assertNull(answer.withinAutonomy)
        }

    // ------------------------------------------------------------------ S16: the human

    @Test
    fun `S16 a human is recorded user, not relayed, with no basis and no withinAutonomy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()

            assertEquals(listOf<Any?>("user", null, 0L, null), provenance(d.id))
            val answer = assertNotNull(result.answer)
            assertEquals(DecisionDecidedBy.USER, answer.decidedBy)
            assertNull(answer.basis)
            assertEquals(false, answer.relayed)
            assertNull(answer.withinAutonomy)
        }

    @Test
    fun `S16 a human who sends director is invalid_request on decidedBy and nothing is written`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.DIRECTOR), human).err()

            assertInvalid(error, "/decidedBy", "human sending director")
            assertEquals(before, frozen(d.id))
            assertEquals(eventsBefore, decisionEventCount())
            // control: the same human without decidedBy answers, and the row says user
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()
            assertEquals("user", row(d.id)["answer_decided_by"])
        }

    @Test
    fun `S16 probe - an answered decision is closed whatever decidedBy a second answer sends, and the stored provenance stays`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), director).ok()
            val answered = frozen(d.id)
            val eventsBefore = decisionEventCount()

            for (sent in listOf(null, DecisionDecidedBy.USER, DecisionDecidedBy.DIRECTOR)) {
                val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "b", decidedBy = sent), director).err()
                assertEquals(ErrorCode.DECISION_CLOSED, error.code, "decidedBy $sent")
            }
            assertEquals(answered, frozen(d.id))
            assertEquals(eventsBefore, decisionEventCount())
        }

    // ------------------------------------------------------------------ S17, S18: seat tokens and seat-less agents

    @Test
    fun `S17 a seat-token caller on an any-actor decision records no provenance`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), seat(f)).ok()

            assertEquals(listOf<Any?>(null, null, null, null), provenance(d.id))
            val answer = assertNotNull(result.answer)
            assertNull(answer.decidedBy)
            assertNull(answer.basis)
            assertNull(answer.relayed)
            assertNull(answer.withinAutonomy)
            assertEquals("answered", row(d.id)["status"], "control: the seat's answer itself is accepted")
        }

    @Test
    fun `S17 a seat-token caller that sends decidedBy, either value, is invalid_request on decidedBy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            for (sent in listOf(DecisionDecidedBy.USER, DecisionDecidedBy.DIRECTOR)) {
                val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", decidedBy = sent), seat(f)).err()
                assertInvalid(error, "/decidedBy", "seat sending $sent")
            }
            assertEquals(before, frozen(d.id))
            assertEquals(eventsBefore, decisionEventCount())
            // control: the same seat without decidedBy answers
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), seat(f)).ok()
            assertEquals("answered", row(d.id)["status"])
        }

    @Test
    fun `S18 a seat-less decide agent on an any-actor ask decision off the director profile is recorded director with no basis`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")

            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), agent).ok()

            assertEquals(listOf<Any?>("director", null, 0L, 0L), provenance(d.id))
        }

    @Test
    fun `S18 a seat-less caller without decide on an any-actor decision records nothing, even a human credential with no proof`(): Unit =
        runBlocking {
            val f = fixture()
            val byAgent = f.raise("question", "k1")
            val byUnprovenHuman = f.raise("question", "k2")
            val unprovenHuman = caller("api:person-secret", PrincipalKind.HUMAN, ProofStatus.NONE)

            val result = f.svc.answer(byAgent.id, DecisionAnswerCommand(option = "a"), agentNoDecide).ok()
            f.svc.answer(byUnprovenHuman.id, DecisionAnswerCommand(option = "a"), unprovenHuman).ok()

            for (id in listOf(byAgent.id, byUnprovenHuman.id)) {
                assertEquals(listOf<Any?>(null, null, null, null), provenance(id), "decision $id")
                assertEquals("answered", row(id)["status"], "control: the answer itself is accepted")
            }
            assertNull(assertNotNull(result.answer).decidedBy)
            // control: the same human credential with decide is the director (D5), so the none above turns on decide
            val withDecide = f.raise("question", "k3")
            val unprovenWithDecide = caller("api:person-secret", PrincipalKind.HUMAN, ProofStatus.NONE, director = true)
            f.svc.answer(withDecide.id, DecisionAnswerCommand(option = "a"), unprovenWithDecide).ok()
            assertEquals(listOf<Any?>("director", null, 0L, 0L), provenance(withDecide.id))
        }

    @Test
    fun `S18 a seat-less caller without decide that sends decidedBy, either value, is invalid_request on decidedBy`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            for (sent in listOf(DecisionDecidedBy.USER, DecisionDecidedBy.DIRECTOR)) {
                val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", decidedBy = sent), agentNoDecide).err()
                assertInvalid(error, "/decidedBy", "no-decide caller sending $sent")
            }
            assertEquals(before, frozen(d.id))
            assertEquals(eventsBefore, decisionEventCount())
            // control: the same caller without decidedBy answers, and the decide holder's relayed claim is accepted
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), agentNoDecide).ok()
            val relayed = f.raise("question", "k2")
            f.svc.answer(relayed.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), agent).ok()
            assertEquals(listOf<Any?>("user", "relayed", 1L, null), provenance(relayed.id))
        }

    // ------------------------------------------------------------------ S22: links

    private fun links(count: Int) =
        (1..count).map { RunSeatLink(listOf("item", "note", "doc", "commit", "url")[(it - 1) % 5], "evidence-$it") }

    @Test
    fun `S22 ten answer links are kept in order, in the answer and in the stored column`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")
            val sent = links(10)

            val result = f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = sent), human).ok()

            assertEquals(sent, assertNotNull(result.answer).links)
            val reread =
                assertNotNull(
                    f.rig.provider
                        .decisionRequestStore()
                        .getById(d.id),
                    "the stored decision"
                )
            assertEquals(sent, assertNotNull(reread.answer).links, "a re-read from storage keeps the links in order")
            val stored = Json.parseToJsonElement(row(d.id)["answer_links"] as String).jsonArray.map { it.jsonObject }
            assertEquals(sent.map { it.ref }, stored.map { it.text("ref") })
            assertEquals(sent.map { it.kind }, stored.map { it.text("kind") })
        }

    @Test
    fun `S22 an answer without links stores none`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")

            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()

            assertNull(row(d.id)["answer_links"], "NULL when there are none")
            assertEquals(
                emptyList(),
                assertNotNull(
                    assertNotNull(
                        f.rig.provider
                            .decisionRequestStore()
                            .getById(d.id)
                    ).answer
                ).links
            )
        }

    @Test
    fun `S22 eleven links, an unknown kind and a blank ref are invalid_request with the pointer of the offending part`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("question")
            val before = frozen(d.id)
            val eventsBefore = decisionEventCount()

            assertInvalid(
                f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = links(11)), human).err(),
                "/links",
                "eleven links"
            )
            assertInvalid(
                f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = listOf(RunSeatLink("bogus", "x"))), human).err(),
                "/links/0/kind",
                "unknown kind"
            )
            for (blank in listOf("", "   ")) {
                assertInvalid(
                    f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = listOf(RunSeatLink("url", blank))), human).err(),
                    "/links/0/ref",
                    "blank ref '$blank'"
                )
            }
            val second = listOf(RunSeatLink("url", "https://example.test/ok"), RunSeatLink("bogus", "x"))
            assertInvalid(
                f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = second), human).err(),
                "/links/1/kind",
                "kind at index 1"
            )

            assertEquals(before, frozen(d.id), "every refusal left the row alone")
            assertEquals(eventsBefore, decisionEventCount())
            // control: ten links are accepted on the same decision, so the refusals turn on the limit and the parts
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a", links = links(10)), human).ok()
            assertEquals("answered", row(d.id)["status"])
        }

    // ------------------------------------------------------------------ S25: the answered event

    @Test
    fun `S25 the answered event carries decidedBy, basis, relayed, withinAutonomy and no principal, text or reasons`(): Unit =
        runBlocking {
            val f = fixture()
            val d = f.raise("deviation")
            f.svc.delegate(d.id, director).ok()

            f.svc.answer(d.id, DecisionAnswerCommand(option = "a", rationale = "because secret rationale"), director).ok()

            val event = events("decision.answered").single()
            val payload = data(event)
            assertEquals("director", payload.text("decidedBy"))
            assertEquals("delegation", payload.text("basis"))
            assertEquals("false", payload.text("relayed"))
            assertEquals("true", payload.text("withinAutonomy"))
            assertEquals("a", payload.text("option"), "the existing members stay")
            val serialized = event["data"] as String
            for (secret in listOf("api:svc-secret", "laptop-7", "sess-77", "because secret rationale")) {
                assertFalse(secret in serialized, "the event payload must not carry '$secret': $serialized")
            }
            for (key in listOf("waitingReason", "waitingOn", "principalId", "text", "rationale")) {
                assertFalse(key in payload.keys, "the answered payload has no $key member: $payload")
            }
        }

    @Test
    fun `S25 members are null when absent, for a seat-token answer, and relayed false for a human`(): Unit =
        runBlocking {
            val f = fixture()
            val bySeat = f.raise("question", "k1")
            val byHuman = f.raise("question", "k2")
            val escalated = f.raise("deviation", "k3")
            f.svc.escalate(escalated.id, "secret waiting reason", director).ok()

            f.svc.answer(bySeat.id, DecisionAnswerCommand(option = "a"), seat(f)).ok()
            f.svc.answer(byHuman.id, DecisionAnswerCommand(option = "a"), human).ok()
            f.svc.answer(escalated.id, DecisionAnswerCommand(option = "a", decidedBy = DecisionDecidedBy.USER), director).ok()

            assertEquals(3, events("decision.answered").size)
            val seatPayload = events("decision.answered").map { data(it) }.single { it["decidedBy"] is JsonNull }
            for (key in listOf("decidedBy", "basis", "relayed", "withinAutonomy")) {
                assertTrue(key in seatPayload.keys, "$key is present, as null, when absent: $seatPayload")
                assertEquals(JsonNull, seatPayload[key], "$key is null for a seat-token answer")
            }
            val userPayloads = events("decision.answered").map { data(it) }.filter { it.text("decidedBy") == "user" }
            assertEquals(2, userPayloads.size, "the human answer and the relayed answer: $userPayloads")
            val humanPayload = userPayloads.single { it["basis"] is JsonNull }
            assertEquals("false", humanPayload.text("relayed"))
            assertEquals(JsonNull, humanPayload["withinAutonomy"])
            val relayed = userPayloads.single { it.text("basis") == "relayed" }
            assertEquals("true", relayed.text("relayed"))
            assertEquals(JsonNull, relayed["withinAutonomy"])
            for (event in events("decision.answered")) {
                val serialized = event["data"] as String
                assertFalse("secret waiting reason" in serialized, "no waiting reason in $serialized")
                for (secret in listOf("api:person-secret", "api:svc-secret", "api:seat-secret", "api:agent-secret")) {
                    assertFalse(secret in serialized, "no principal id '$secret' in $serialized")
                }
            }
            assertEquals("answered", row(bySeat.id)["status"])
        }
}

package io.github.jpicklyk.mcptask.current.application.decision

import io.github.jpicklyk.mcptask.current.application.api.AnswerProvenance
import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswer
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.FROM_START
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.decisionService
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.pushConfig
import io.github.jpicklyk.mcptask.current.test.seedItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (test-author seat, item a53e6875, R24) for [DecisionService] over the production composition and a real SQLite
 * database: raise (scope key, class table, dedupe match and mismatch, payload and field limits), acknowledge, answer (the answerer
 * check, provenance, immutability, the expiry flag), withdraw (raiser or coordinator), the open count and the class table. The
 * matrix itself is exhaustive in `DecisionAnswererMatrixTest`; the tool half (order of checks, reads, `items_read context`) is in
 * [DecisionToolsTest] at the end of this file.
 *
 * Oracles (never read from a run): task-scope D3 to D12 and the migration-assessment column list (the stored row is checked by raw
 * SQL against the assessment's column names), plan 3.3 for who may answer, and the envelope spec for the error shapes (read through
 * `EnvelopeEncoder.detail`, so the Kotlin property names play no part). In short: `scope_key` is the item id text, else the run id
 * text, else `root`; a lookup is by (root, scope_key, dedupe_key) among the non-withdrawn rows and a hit is the stored decision with
 * `existing` true (an answered one carries its answer), unless class, question or the option keys in order differ, which is
 * `idempotency_mismatch` carrying the dedupe key; answer needs the class's answerers, an `option` that is a key of the options (a
 * freeform class may answer with `text` alone, a non-freeform class must not use `text`), is final (a second answer, and an ack or a
 * withdraw of an answered or withdrawn decision, are `decision_closed{decisionId, status}`), records the caller's kind, proof, host,
 * session, run, seat and profile, and sets `after_expiry` exactly when it lands at or after `expires_at`; withdraw is the raiser (same
 * principal and same seat) or the coordinator token of the decision's run, while the decision is open or acknowledged, and frees the
 * dedupe key; the payload (question, options, recommended, links, context as compact JSON) is at most 4,096 UTF-8 bytes, else
 * `payload_too_large{max: 4096, actual}`; `run-budget` and `attempt-cap` are refused on the tool path; the event payload never carries
 * the principal id, host, session, text or rationale.
 *
 * Negative-assertion fixture conditions: every refusal is followed by a read of the stored row (unchanged version, status and
 * answer columns) and of the event table (no new row), and is paired with the same call on the input that succeeds.
 *
 * Domain invariants satisfied by construction: items are children of the root and seeded through the rig; runs are seeded through
 * `ParitySeeder.run` (state derived from the status); options are built by one helper that satisfies the key pattern, the 8 option
 * limit and the label limits; every [DecisionCaller] derives its provenance from the same fields.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionServiceTest {
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
  review:
    answerers: director-or-human
    severity: low
    expires_after: 600
    on_expiry: escalate
  chat:
    answerers: any-actor
    freeform: true
"""
        const val ORCH = "api:orch"
        const val OTHER = "api:other"
    }

    private fun <T> Outcome<T>.ok(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

    private fun <T> Outcome<T>.err(): DomainError = (this as? Outcome.Err)?.error ?: error("expected Outcome.Err, got $this")

    private inner class Fixture(
        val rig: EventLogRig,
        val root: WorkItem,
        val item: WorkItem,
        val itemTwo: WorkItem,
        val runA: UUID,
        val runB: UUID
    ) {
        val svc get() = rig.ctx.decisionService

        fun options(vararg keys: String) = keys.map { DecisionRequestOption(key = it, label = "Label ${it.uppercase()}") }

        fun cmd(
            decisionClass: String = "question",
            question: String = "Which one?",
            options: List<DecisionRequestOption> = options("a", "b"),
            dedupeKey: String = "k1",
            itemId: UUID? = item.id,
            runId: UUID? = null,
            rootId: UUID = root.id,
            recommended: String? = null,
            links: List<RunSeatLink> = emptyList(),
            context: String? = null,
            severity: DecisionRequestSeverity? = null,
            raisedById: String = ORCH,
            raisedByKind: String = "service",
            raisedBySeat: String? = null
        ) = DecisionRaiseCommand(
            rootId = rootId,
            runId = runId,
            itemId = itemId,
            decisionClass = decisionClass,
            question = question,
            options = options,
            recommended = recommended,
            links = links,
            context = context,
            dedupeKey = dedupeKey,
            severity = severity,
            raisedById = raisedById,
            raisedByKind = raisedByKind,
            raisedBySeat = raisedBySeat
        )
    }

    private suspend fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, CONFIG, clock)
        val root = rig.seed("Decision root")
        val item = rig.seed("Decision item", parent = root)
        val itemTwo = rig.seed("Second item", parent = root)
        val provider = db.db.repositoryProvider()
        val seeder = ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork(clock))
        val runA = UUID.fromString("a0000001-0000-4000-8000-000000000001")
        val runB = UUID.fromString("b0000002-0000-4000-8000-000000000002")
        seeder.run(runA, root.id, listOf(item.id), openedBy = ORCH, openedByKind = "service")
        seeder.run(runB, root.id, listOf(itemTwo.id), openedBy = ORCH, openedByKind = "service")
        return Fixture(rig, root, item, itemTwo, runA, runB)
    }

    // ------------------------------------------------------------------ callers (the shape the tool layer builds)

    private fun caller(
        id: String = "api:person",
        kind: PrincipalKind? = PrincipalKind.HUMAN,
        proof: ProofStatus? = ProofStatus.ISSUED,
        director: Boolean = false,
        seat: String? = null,
        runId: UUID? = null,
        assignmentId: UUID? = null,
        host: String? = "laptop-7",
        sessionId: String? = "sess-77",
        profile: String? = "rest",
        withProvenance: Boolean = true
    ) = DecisionCaller(
        principalId = id,
        kind = kind,
        proofStatus = proof,
        director = director,
        seat = seat,
        runId = runId,
        assignmentId = assignmentId,
        provenance =
            if (withProvenance && kind != null && proof != null) {
                AnswerProvenance(id, kind, proof, host, sessionId, runId, seat, profile)
            } else {
                null
            }
    )

    private val human get() = caller()
    private val agent get() = caller("api:agent", PrincipalKind.AGENT, ProofStatus.VERIFIED)
    private val directorCaller get() = caller("api:svc", PrincipalKind.SERVICE, ProofStatus.ISSUED, director = true, profile = "director")

    private fun raiser(
        id: String = ORCH,
        kind: PrincipalKind = PrincipalKind.SERVICE,
        seat: String? = null,
        runId: UUID? = null
    ) = caller(id, kind, ProofStatus.ISSUED, seat = seat, runId = runId)

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

    /** The stored row with its byte columns as lists, so two reads compare by value. */
    private fun frozen(id: UUID): Map<String, Any?> = row(id).mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }

    private fun rowCount(): Int = query("SELECT * FROM decision_requests").size

    private fun events(type: String): List<Map<String, Any?>> = query("SELECT * FROM events WHERE type = ? ORDER BY seq", type)

    private fun decisionEventCount(): Int = query("SELECT * FROM events WHERE type LIKE 'decision.%'").size

    private fun uuidOf(bytes: Any?): UUID = ByteBuffer.wrap(bytes as ByteArray).let { UUID(it.long, it.long) }

    private fun stamp(text: Any?): Instant = LocalDateTime.parse((text as String).replace(' ', 'T')).toInstant(ZoneOffset.UTC)

    private fun data(event: Map<String, Any?>): JsonObject = Json.parseToJsonElement(event["data"] as String).jsonObject

    private fun detail(error: DomainError): JsonObject = EnvelopeEncoder.detail(assertNotNull(error.detail, "the error carries a detail"))

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun pointers(error: DomainError): List<String> =
        detail(error)
            .getValue("fields")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("field")
                    .jsonPrimitive.content
            }

    private fun reasons(error: DomainError): List<String> =
        detail(error)
            .getValue("fields")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("reason")
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

    private fun assertForbiddenKind(
        error: DomainError,
        required: String,
        label: String
    ) {
        assertEquals(ErrorCode.FORBIDDEN, error.code, "$label: ${error.message}")
        val d = detail(error)
        assertEquals("principal_kind", d.text("scope"), label)
        assertEquals(required, d.text("required"), label)
    }

    // ------------------------------------------------------------------ S1, S2: raise and the dedupe match

    @Test
    fun `S1 raise stores one open decision with the class defaults and records one decision raised`(): Unit =
        runBlocking {
            val f = fixture()
            val before = decisionEventCount()

            val result = f.svc.raise(f.cmd()).ok()

            assertFalse(result.existing, "a first raise is new")
            val d = result.decision
            assertEquals(DecisionRequestStatus.OPEN, d.status)
            assertEquals(DecisionRequestSeverity.LOW, d.severity, "the question class is low")
            assertEquals(f.item.id.toString(), d.scopeKey, "scope_key is the item id")
            assertNull(d.expiresAt, "the question class never expires")
            assertEquals(1L, d.version)

            val r = row(d.id)
            assertEquals("question", r["class"])
            assertEquals("any-actor", r["answerers"])
            assertEquals("low", r["severity"])
            assertEquals("open", r["status"])
            assertEquals("k1", r["dedupe_key"])
            assertEquals(f.item.id.toString(), r["scope_key"])
            assertEquals("Which one?", r["question"])
            assertEquals(f.root.id, uuidOf(r["root_id"]))
            assertEquals(f.item.id, uuidOf(r["item_id"]))
            assertNull(r["run_id"])
            assertEquals(ORCH, r["raised_by_id"])
            assertEquals("service", r["raised_by_kind"])
            assertNull(r["raised_by_seat"])
            assertEquals(clock.now(), stamp(r["raised_at"]), "raised_at is the unit's clock")
            assertEquals(23, (r["raised_at"] as String).length, "the canonical 23-character stamp")
            assertNull(r["expires_at"])
            assertEquals("escalate", r["on_expiry"])
            assertEquals(0L, r["after_expiry"])
            assertEquals(1L, r["version"])
            val options = Json.parseToJsonElement(r["options"] as String).jsonArray.map { it.jsonObject }
            assertEquals(listOf("a", "b"), options.map { it.text("key") })
            assertEquals(listOf("Label A", "Label B"), options.map { it.text("label") })

            val raisedEvents = events("decision.raised")
            assertEquals(1, raisedEvents.size)
            assertEquals(before + 1, decisionEventCount(), "exactly one decision event")
            val event = raisedEvents.single()
            assertEquals("decision", event["entity_kind"])
            assertEquals(d.id, uuidOf(event["entity_id"]))
            assertEquals(f.root.id, uuidOf(event["root_id"]))
            val payload = data(event)
            assertEquals("question", payload.text("class"))
            assertEquals("low", payload.text("severity"))
            assertEquals(f.item.id.toString(), payload.text("itemId"))
            assertEquals("k1", payload.text("dedupeKey"))
            assertFalse("question" in payload.keys || "options" in payload.keys, "the event carries no question text: $payload")
        }

    @Test
    fun `S2 the same dedupe key with the same class, question and option keys returns the stored decision and records nothing`(): Unit =
        runBlocking {
            val f = fixture()
            val first = f.svc.raise(f.cmd()).ok()
            val eventsBefore = decisionEventCount()

            val again = f.svc.raise(f.cmd()).ok()

            assertTrue(again.existing)
            assertEquals(first.decision.id, again.decision.id)
            assertNull(again.decision.answer, "an open decision has no answer")
            assertEquals(1, rowCount())
            assertEquals(eventsBefore, decisionEventCount(), "a replay records no event")
            assertEquals(1L, row(first.decision.id)["version"], "a replay does not bump the version")
        }

    @Test
    fun `S2 only class, question and the option keys in order decide the match, not labels or context`(): Unit =
        runBlocking {
            val f = fixture()
            val first = f.svc.raise(f.cmd()).ok()

            val reworded =
                f.cmd(
                    options = listOf(DecisionRequestOption("a", "Another label", "a consequence"), DecisionRequestOption("b", "B again")),
                    recommended = "b",
                    context = "new background",
                    links = listOf(RunSeatLink("url", "https://example.test/x")),
                    severity = DecisionRequestSeverity.HIGH
                )
            val hit = f.svc.raise(reworded).ok()
            assertTrue(hit.existing, "a different label, consequence, recommendation, context, links or severity is still the same request")
            assertEquals(first.decision.id, hit.decision.id)
            val storedLabel =
                Json
                    .parseToJsonElement(row(first.decision.id)["options"] as String)
                    .jsonArray[0]
                    .jsonObject
                    .text("label")
            assertEquals("Label A", storedLabel)
        }

    @Test
    fun `S11 the same key with another question, class or option keys is idempotency_mismatch carrying the key`(): Unit =
        runBlocking {
            val f = fixture()
            val first = f.svc.raise(f.cmd()).ok()
            val eventsBefore = decisionEventCount()

            val differing =
                mapOf(
                    "question" to f.cmd(question = "Another question?"),
                    "class" to f.cmd(decisionClass = "chat"),
                    "option keys" to f.cmd(options = f.options("a", "c")),
                    "option order" to f.cmd(options = f.options("b", "a")),
                    "an extra option" to f.cmd(options = f.options("a", "b", "c")),
                    "a missing option" to f.cmd(options = f.options("a"))
                )
            for ((what, command) in differing) {
                val error = f.svc.raise(command).err()
                assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, error.code, what)
                assertEquals("k1", detail(error).text("idempotencyKey"), "$what: the detail carries the dedupe key")
            }
            assertEquals(1, rowCount(), "no mismatch created a row")
            assertEquals(eventsBefore, decisionEventCount())
            assertEquals(first.decision.id, uuidOf(query("SELECT id FROM decision_requests").single()["id"]))

            // control: the identical request is the hit, so the mismatches turn on the one changed input
            assertTrue(
                f.svc
                    .raise(f.cmd())
                    .ok()
                    .existing
            )
        }

    @Test
    fun `S2 probe - the dedupe key is exact, a trailing space or another case is another decision`(): Unit =
        runBlocking {
            val f = fixture()
            val plain = f.svc.raise(f.cmd(dedupeKey = "k")).ok()
            val spaced = f.svc.raise(f.cmd(dedupeKey = "k ")).ok()
            val upper = f.svc.raise(f.cmd(dedupeKey = "K")).ok()

            assertEquals(3, setOf(plain.decision.id, spaced.decision.id, upper.decision.id).size)
            assertTrue(listOf(plain, spaced, upper).none { it.existing })
            assertEquals(3, rowCount())
        }
    // ------------------------------------------------------------------ S3 to S7: answer

    @Test
    fun `S3 a human answers a human-only decision and the row, the event and the provenance say who`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff"))
                    .ok()
                    .decision
            assertEquals("human-only", row(d.id)["answerers"])
            val who =
                caller(
                    "api:person-9",
                    PrincipalKind.HUMAN,
                    ProofStatus.VERIFIED,
                    host = "laptop-7",
                    sessionId = "sess-77",
                    profile = "rest"
                )
            clock.advanceSeconds(30)

            f.svc.answer(d.id, DecisionAnswerCommand(option = "a", rationale = "because it is safer"), who).ok()

            val r = row(d.id)
            assertEquals("answered", r["status"])
            assertEquals("a", r["answer_option"])
            assertNull(r["answer_text"])
            assertEquals("because it is safer", r["answer_rationale"])
            assertEquals("api:person-9", r["answered_by_id"])
            assertEquals("human", r["answered_by_kind"])
            assertEquals("verified", r["answered_by_proof"])
            assertEquals("laptop-7", r["answered_host"])
            assertEquals("sess-77", r["answered_session_id"])
            assertEquals("rest", r["answered_profile"])
            assertNull(r["answered_run_id"])
            assertNull(r["answered_seat"])
            assertEquals(clock.now(), stamp(r["answered_at"]))
            assertEquals(0L, r["after_expiry"], "the signoff class never expires, so the answer is never after expiry")
            assertEquals(2L, r["version"], "an answer bumps the version once")

            val answered = events("decision.answered").single()
            assertEquals("decision", answered["entity_kind"])
            assertEquals(d.id, uuidOf(answered["entity_id"]))
            val payload = data(answered)
            assertEquals("signoff", payload.text("class"))
            assertEquals("a", payload.text("option"))
            assertEquals("false", payload.text("afterExpiry"))
            assertEquals("human", payload.text("kind"))
            assertEquals("verified", payload.text("proofStatus"))
            assertEquals("rest", payload.text("profile"))
            val serialized = answered["data"] as String
            for (secret in listOf("api:person-9", "laptop-7", "sess-77", "because it is safer")) {
                assertFalse(secret in serialized, "the event payload must not carry '$secret': $serialized")
            }
            for (key in listOf("principalId", "host", "sessionId", "text", "rationale")) {
                assertFalse(key in payload.keys, "the event payload has no $key: $payload")
            }
        }

    @Test
    fun `S3 the answer records the answering run and seat of a caller that holds one`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "chat"))
                    .ok()
                    .decision
            val seated =
                caller(
                    "api:agent-2",
                    PrincipalKind.AGENT,
                    ProofStatus.ISSUED,
                    seat = "implementer",
                    runId = f.runA,
                    profile = "orchestration"
                )

            f.svc.answer(d.id, DecisionAnswerCommand(text = "free words"), seated).ok()

            val r = row(d.id)
            assertEquals("api:agent-2", r["answered_by_id"])
            assertEquals("agent", r["answered_by_kind"])
            assertEquals("issued", r["answered_by_proof"])
            assertEquals(f.runA, uuidOf(r["answered_run_id"]))
            assertEquals("implementer", r["answered_seat"])
            assertEquals("orchestration", r["answered_profile"])
            assertEquals("free words", r["answer_text"])
            assertNull(r["answer_option"], "a free-text answer names no option")
        }

    @Test
    fun `S4 the director profile answers a director-or-human decision`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "deviation"))
                    .ok()
                    .decision
            assertEquals("director-or-human", row(d.id)["answerers"])

            f.svc.answer(d.id, DecisionAnswerCommand(option = "b"), directorCaller).ok()

            assertEquals("answered", row(d.id)["status"])
            assertEquals("b", row(d.id)["answer_option"])
            assertEquals("service", row(d.id)["answered_by_kind"])
            assertEquals("director", row(d.id)["answered_profile"])
        }

    @Test
    fun `S5 a re-raise after the answer returns the answered decision with its answer and creates no new request`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff"))
                    .ok()
                    .decision
            f.svc.answer(d.id, DecisionAnswerCommand(option = "b", rationale = "done"), human).ok()
            val eventsBefore = decisionEventCount()

            val again = f.svc.raise(f.cmd(decisionClass = "signoff")).ok()

            assertTrue(again.existing)
            assertEquals(d.id, again.decision.id)
            assertEquals(DecisionRequestStatus.ANSWERED, again.decision.status)
            assertEquals("b", assertNotNull(again.decision.answer, "the answered decision carries its answer").option)
            assertEquals(1, rowCount(), "the answer is binding: no second request")
            assertEquals(eventsBefore, decisionEventCount())
        }

    @Test
    fun `S9 an agent credential cannot answer a human-only decision and nothing changes`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff"))
                    .ok()
                    .decision
            val eventsBefore = decisionEventCount()

            for (who in listOf(
                agent,
                caller("api:svc", PrincipalKind.SERVICE, ProofStatus.ISSUED),
                caller("api:h-none", PrincipalKind.HUMAN, ProofStatus.NONE),
                caller("api:h-rej", PrincipalKind.HUMAN, ProofStatus.REJECTED),
                caller("api:h-seat", PrincipalKind.HUMAN, ProofStatus.ISSUED, seat = "implementer", runId = f.runA),
                directorCaller
            )) {
                assertForbiddenKind(f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), who).err(), "human", who.principalId)
            }
            val r = row(d.id)
            assertEquals("open", r["status"])
            assertEquals(1L, r["version"])
            assertNull(r["answered_by_id"])
            assertNull(r["answer_option"])
            assertEquals(eventsBefore, decisionEventCount(), "a refused answer records no event")

            // control: the credential that is a human, with a real proof and no seat, answers the same decision
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()
            assertEquals("answered", row(d.id)["status"])
        }

    @Test
    fun `S10 a caller under a seat token cannot answer a director-or-human decision, even on the director profile`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "deviation"))
                    .ok()
                    .decision

            val seated =
                caller(
                    "api:svc",
                    PrincipalKind.SERVICE,
                    ProofStatus.ISSUED,
                    director = false,
                    seat = "implementer",
                    runId = f.runA,
                    profile = "director"
                )
            assertForbiddenKind(
                f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), seated).err(),
                "director,human",
                "seat on the director profile"
            )
            assertForbiddenKind(
                f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), agent).err(),
                "director,human",
                "an agent off the director profile"
            )
            assertEquals("open", row(d.id)["status"])

            // controls: the director and a human are admitted
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), directorCaller).ok()
            assertEquals("answered", row(d.id)["status"])
        }

    @Test
    fun `a call with no provenance cannot answer and leaves the decision open`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "chat"))
                    .ok()
                    .decision

            val error = f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), caller(withProvenance = false)).err()

            assertEquals(ErrorCode.UNAUTHENTICATED, error.code)
            assertEquals("open", row(d.id)["status"])
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), caller()).ok()
            assertEquals("answered", row(d.id)["status"], "control: the same answer with provenance is accepted")
        }

    // ------------------------------------------------------------------ S12: closed decisions

    @Test
    fun `S12 an answered decision is closed - a second answer, an ack and a withdraw are decision_closed with the status`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "chat"))
                    .ok()
                    .decision
            f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human).ok()
            val answeredRow = frozen(d.id)
            val eventsBefore = decisionEventCount()

            val attempts =
                mapOf(
                    "second answer" to f.svc.answer(d.id, DecisionAnswerCommand(option = "b"), human),
                    "ack" to f.svc.acknowledge(d.id, human),
                    "withdraw by the raiser" to f.svc.withdraw(d.id, raiser())
                )
            for ((what, outcome) in attempts) {
                val error = outcome.err()
                assertEquals(ErrorCode.DECISION_CLOSED, error.code, what)
                assertEquals(d.id.toString(), detail(error).text("decisionId"), what)
                assertEquals("answered", detail(error).text("status"), what)
            }
            assertEquals(answeredRow, frozen(d.id), "the answered row is immutable")
            assertEquals(eventsBefore, decisionEventCount())
        }

    @Test
    fun `S12 a withdrawn decision is closed too, with the status withdrawn`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "chat"))
                    .ok()
                    .decision
            f.svc.withdraw(d.id, raiser()).ok()
            val withdrawnRow = frozen(d.id)

            for ((what, outcome) in mapOf(
                "answer" to f.svc.answer(d.id, DecisionAnswerCommand(option = "a"), human),
                "ack" to f.svc.acknowledge(d.id, human),
                "withdraw again" to f.svc.withdraw(d.id, raiser())
            )) {
                val error = outcome.err()
                assertEquals(ErrorCode.DECISION_CLOSED, error.code, what)
                assertEquals("withdrawn", detail(error).text("status"), what)
            }
            assertEquals(withdrawnRow, frozen(d.id))
        }

    // ------------------------------------------------------------------ S6: withdraw and the freed key

    @Test
    fun `S6 the raiser withdraws an open decision, and the same key then raises a new decision`(): Unit =
        runBlocking {
            val f = fixture()
            val first =
                f.svc
                    .raise(f.cmd())
                    .ok()
                    .decision

            f.svc.withdraw(first.id, raiser()).ok()

            assertEquals("withdrawn", row(first.id)["status"])
            assertEquals(2L, row(first.id)["version"])
            val withdrawn = events("decision.withdrawn").single()
            assertEquals(first.id, uuidOf(withdrawn["entity_id"]))

            val again = f.svc.raise(f.cmd()).ok()
            assertFalse(again.existing, "a withdrawn decision does not match")
            assertNotEquals(first.id, again.decision.id)
            assertEquals(2, rowCount())

            // a withdrawn row falls out of the match, so another question under the freed key is a new decision, not a mismatch
            f.svc.withdraw(again.decision.id, raiser()).ok()
            val reworded = f.svc.raise(f.cmd(question = "A different question?")).ok()
            assertFalse(reworded.existing)
            assertEquals(3, rowCount())
        }

    @Test
    fun `S17 withdraw is the raiser with the same seat or the coordinator of the decision's run, and nobody else`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(raisedById = ORCH, raisedBySeat = "implementer", runId = f.runA))
                    .ok()
                    .decision
            val eventsBefore = decisionEventCount()

            val refused =
                mapOf(
                    "a third principal" to raiser(OTHER),
                    "the raiser id without the seat" to raiser(ORCH, seat = null),
                    "the raiser id under another seat" to raiser(ORCH, seat = "reviewer"),
                    "the coordinator of another run" to
                        caller("api:coord-b", PrincipalKind.SERVICE, ProofStatus.ISSUED, seat = "coordinator", runId = f.runB),
                    "a member seat of the decision's run" to
                        caller("api:member", PrincipalKind.SERVICE, ProofStatus.ISSUED, seat = "implementer", runId = f.runA)
                )
            for ((what, who) in refused) {
                assertForbiddenKind(f.svc.withdraw(d.id, who).err(), "coordinator,raiser", what)
            }
            assertEquals("open", row(d.id)["status"])
            assertEquals(eventsBefore, decisionEventCount())

            // controls: the raiser under the same seat, and the coordinator of the decision's run, are each admitted
            val second =
                f.svc
                    .raise(f.cmd(dedupeKey = "k2", raisedById = ORCH, raisedBySeat = "implementer", runId = f.runA))
                    .ok()
                    .decision
            f.svc.withdraw(second.id, raiser(ORCH, seat = "implementer")).ok()
            assertEquals("withdrawn", row(second.id)["status"])
            f.svc
                .withdraw(
                    d.id,
                    caller("api:coord-a", PrincipalKind.SERVICE, ProofStatus.ISSUED, seat = "coordinator", runId = f.runA)
                ).ok()
            assertEquals("withdrawn", row(d.id)["status"])
        }

    @Test
    fun `S17 a decision may be withdrawn while acknowledged`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "chat"))
                    .ok()
                    .decision
            f.svc.acknowledge(d.id, human).ok()
            assertEquals("acknowledged", row(d.id)["status"])

            f.svc.withdraw(d.id, raiser()).ok()

            assertEquals("withdrawn", row(d.id)["status"])
        }

    // ------------------------------------------------------------------ S22: ack

    @Test
    fun `S22 ack records who and when, and a second ack changes nothing and records no event`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff"))
                    .ok()
                    .decision
            clock.advanceSeconds(5)

            f.svc.acknowledge(d.id, human).ok()

            val r = row(d.id)
            val acked = frozen(d.id)
            assertEquals("acknowledged", r["status"])
            assertEquals("api:person", r["ack_by_id"])
            assertEquals("human", r["ack_by_kind"])
            assertEquals(clock.now(), stamp(r["ack_at"]))
            assertEquals(2L, r["version"])
            assertEquals(1, events("decision.acknowledged").size)

            clock.advanceSeconds(5)
            f.svc.acknowledge(d.id, human).ok()
            assertEquals(acked, frozen(d.id), "an acknowledged decision is returned unchanged")
            assertEquals(1, events("decision.acknowledged").size, "no second event")
        }

    @Test
    fun `S22 ack follows the answerer check, an agent cannot ack a human-only decision`(): Unit =
        runBlocking {
            val f = fixture()
            val d =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff"))
                    .ok()
                    .decision

            assertForbiddenKind(f.svc.acknowledge(d.id, agent).err(), "human", "agent ack")
            assertEquals("open", row(d.id)["status"])
            f.svc.acknowledge(d.id, human).ok()
            assertEquals("acknowledged", row(d.id)["status"], "control: a human acks")
        }

    @Test
    fun `an answer is accepted straight from open and from acknowledged`(): Unit =
        runBlocking {
            val f = fixture()
            val open =
                f.svc
                    .raise(f.cmd(dedupeKey = "from-open", decisionClass = "chat"))
                    .ok()
                    .decision
            val acked =
                f.svc
                    .raise(f.cmd(dedupeKey = "from-ack", decisionClass = "chat"))
                    .ok()
                    .decision
            f.svc.acknowledge(acked.id, human).ok()

            f.svc.answer(open.id, DecisionAnswerCommand(option = "a"), human).ok()
            f.svc.answer(acked.id, DecisionAnswerCommand(option = "a"), human).ok()

            assertEquals(listOf("answered", "answered"), listOf(row(open.id)["status"], row(acked.id)["status"]))
            assertEquals(2L, row(open.id)["version"])
            assertEquals(3L, row(acked.id)["version"], "ack and answer each bump the version")
        }
    // ------------------------------------------------------------------ S14, S8: the class table

    @Test
    fun `S14 an unknown class is invalid_request on the class and the reason names the sorted classes`(): Unit =
        runBlocking {
            val f = fixture()
            val names = listOf("attempt-cap", "chat", "deviation", "question", "review", "run-budget", "signoff")

            for (name in listOf("nonsense", "Question", "QUESTION", "question ", "")) {
                val error = f.svc.raise(f.cmd(decisionClass = name)).err()
                assertInvalid(error, "/class", "class '$name'")
                val reason = reasons(error).first()
                val positions = names.map { reason.indexOf(it) }
                assertTrue(positions.all { it >= 0 }, "the reason names every class: $reason")
                assertEquals(positions.sorted(), positions, "in sorted order: $reason")
            }
            assertEquals(0, rowCount())
            // control: a known class is accepted
            f.svc.raise(f.cmd(decisionClass = "question")).ok()
        }

    @Test
    fun `S14 a server-raised class is refused on the tool path and accepted with the server flag`(): Unit =
        runBlocking {
            val f = fixture()
            for (name in listOf("run-budget", "attempt-cap")) {
                val error = f.svc.raise(f.cmd(decisionClass = name, dedupeKey = "tool-$name")).err()
                assertInvalid(error, "/class", name)
                assertTrue(reasons(error).any { "server-raised" in it }, "the reason says so: ${reasons(error)}")
            }
            assertEquals(0, rowCount())

            val viaServer =
                f.svc
                    .raise(f.cmd(decisionClass = "run-budget", dedupeKey = "server"), serverRaised = true)
                    .ok()
                    .decision
            val r = row(viaServer.id)
            assertEquals("run-budget", r["class"])
            assertEquals("director-or-human", r["answerers"])
            assertEquals("high", r["severity"])
            assertNull(r["expires_at"])
        }

    @Test
    fun `S8 the class severity is the default and the request severity overrides it`(): Unit =
        runBlocking {
            val f = fixture()

            val byClass =
                f.svc
                    .raise(f.cmd(decisionClass = "deviation", dedupeKey = "s1"))
                    .ok()
                    .decision
            val byRequest =
                f.svc
                    .raise(
                        f.cmd(decisionClass = "deviation", dedupeKey = "s2", severity = DecisionRequestSeverity.HIGH)
                    ).ok()
                    .decision
            val lowered =
                f.svc
                    .raise(
                        f.cmd(decisionClass = "deviation", dedupeKey = "s3", severity = DecisionRequestSeverity.LOW)
                    ).ok()
                    .decision

            assertEquals("normal", row(byClass.id)["severity"], "deviation is normal")
            assertEquals("high", row(byRequest.id)["severity"])
            assertEquals("low", row(lowered.id)["severity"])
        }

    @Test
    fun `S8 the configured classes resolve beside the built-ins, and a per-root entry replaces the server entry of its key`(): Unit =
        runBlocking {
            val f = fixture()
            val table = f.svc.classes(f.root.id)
            assertEquals(
                setOf("run-budget", "attempt-cap", "deviation", "question", "signoff", "review", "chat"),
                table.keys,
                "the built-ins and the three configured classes"
            )
            assertEquals(DecisionRequestSeverity.LOW, table.getValue("review").severity)
            assertEquals(600L, table.getValue("review").expiresAfterSeconds)
            assertTrue(table.getValue("chat").freeform)

            pushConfig(
                db.db.repositoryProvider(),
                db.db.unitOfWork(clock),
                f.root.id,
                "work_item_schemas:\n  test-item:\n    notes: []\n" +
                    "decision_classes:\n  deviation:\n    severity: high\n  review:\n    answerers: human-only\n"
            )

            val perRoot = f.svc.classes(f.root.id)
            assertEquals(DecisionRequestSeverity.HIGH, perRoot.getValue("deviation").severity, "the per-root field wins over the built-in")
            assertEquals(14_400L, perRoot.getValue("deviation").expiresAfterSeconds, "a built-in field the entry does not state stays")
            assertEquals(
                DecisionRequestSeverity.NORMAL,
                perRoot.getValue("review").severity,
                "the per-root review replaces the server review as a whole: its low severity is gone (per KEY)"
            )
            assertNull(perRoot.getValue("review").expiresAfterSeconds, "so is its expiry")
            val raised =
                f.svc
                    .raise(f.cmd(decisionClass = "deviation", dedupeKey = "pr"))
                    .ok()
                    .decision
            assertEquals("high", row(raised.id)["severity"], "raise reads the per-root class")
            assertEquals(
                DecisionRequestSeverity.NORMAL,
                f.svc
                    .classes(UUID.randomUUID())
                    .getValue("deviation")
                    .severity,
                "another root keeps the server table"
            )
        }

    // ------------------------------------------------------------------ S19: scope keys

    @Test
    fun `S19 the scope key is the item id, else the run id, else root, and the item and run ids are stored`(): Unit =
        runBlocking {
            val f = fixture()

            val both =
                f.svc
                    .raise(f.cmd(dedupeKey = "x", itemId = f.item.id, runId = f.runA))
                    .ok()
                    .decision
            val runOnly =
                f.svc
                    .raise(f.cmd(dedupeKey = "x", itemId = null, runId = f.runA))
                    .ok()
                    .decision
            val rootOnly =
                f.svc
                    .raise(f.cmd(dedupeKey = "x", itemId = null, runId = null))
                    .ok()
                    .decision

            assertEquals(f.item.id.toString(), row(both.id)["scope_key"])
            assertEquals(f.runA, uuidOf(row(both.id)["run_id"]), "the run is stored beside the item")
            assertEquals(f.item.id, uuidOf(row(both.id)["item_id"]))
            assertEquals(f.runA.toString(), row(runOnly.id)["scope_key"])
            assertNull(row(runOnly.id)["item_id"])
            assertEquals("root", row(rootOnly.id)["scope_key"])
            assertNull(row(rootOnly.id)["run_id"])
            assertEquals(3, setOf(both.id, runOnly.id, rootOnly.id).size, "one dedupe key under three scopes is three decisions")
            assertEquals(3, rowCount())
        }

    @Test
    fun `S19 with an item the run takes no part in the key, so the same key and item under another run is the same decision`(): Unit =
        runBlocking {
            val f = fixture()
            val first = f.svc.raise(f.cmd(itemId = f.item.id, runId = f.runA)).ok()

            val second = f.svc.raise(f.cmd(itemId = f.item.id, runId = f.runB)).ok()

            assertTrue(second.existing, "the scope key is the item id")
            assertEquals(first.decision.id, second.decision.id)
            assertEquals(f.runA, uuidOf(row(first.decision.id)["run_id"]), "the stored run is the first raise's")
            assertEquals(1, rowCount())
            // control: the same key under a run only (no item) is keyed by that run, so two runs are two decisions
            val runKeyedA = f.svc.raise(f.cmd(dedupeKey = "r", itemId = null, runId = f.runA)).ok()
            val runKeyedB = f.svc.raise(f.cmd(dedupeKey = "r", itemId = null, runId = f.runB)).ok()
            assertNotEquals(runKeyedA.decision.id, runKeyedB.decision.id)
        }

    @Test
    fun `S19 the same key on another item or another root is another decision`(): Unit =
        runBlocking {
            val f = fixture()
            val a =
                f.svc
                    .raise(f.cmd(itemId = f.item.id))
                    .ok()
                    .decision
            val b =
                f.svc
                    .raise(f.cmd(itemId = f.itemTwo.id))
                    .ok()
                    .decision
            val otherRoot = f.rig.seed("Other root")
            val c =
                f.svc
                    .raise(f.cmd(itemId = null, rootId = otherRoot.id))
                    .ok()
                    .decision
            val d =
                f.svc
                    .raise(f.cmd(itemId = null, rootId = f.root.id))
                    .ok()
                    .decision

            assertEquals(4, setOf(a.id, b.id, c.id, d.id).size)
            assertEquals(otherRoot.id, uuidOf(row(c.id)["root_id"]))
        }

    // ------------------------------------------------------------------ S15, S21: limits

    private fun bigOptions(
        count: Int,
        label: Int,
        consequence: Int
    ) = (1..count).map { DecisionRequestOption("o$it", "l".repeat(label), "c".repeat(consequence)) }

    @Test
    fun `S15 the payload cap is 4096 bytes inclusive, measured on the compact JSON of the five payload fields`(): Unit =
        runBlocking {
            val f = fixture()
            val options = bigOptions(8, 100, 250)

            fun command(
                contextLength: Int,
                key: String
            ) = f.cmd(question = "q".repeat(400), options = options, context = "c".repeat(contextLength), dedupeKey = key)

            val over = f.svc.raise(command(1000, "over")).err()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, over.code)
            assertEquals("4096", detail(over).text("max"))
            val measured = detail(over).text("actual")!!.toInt()
            // sanity of the measure: at least the characters of the fields, at most those plus a modest JSON overhead
            val characters = 400 + 8 * (2 + 100 + 250) + 1000
            assertTrue(measured in (characters + 1)..(characters + 400), "actual $measured for $characters field characters")
            val delta = measured - 4096
            assertTrue(delta in 1..900, "fixture condition: the overshoot $delta can be trimmed from the context")
            assertEquals(0, rowCount(), "an over-cap raise stores nothing")

            val exact = f.svc.raise(command(1000 - delta, "exact")).ok()
            assertFalse(exact.existing, "a payload of exactly 4096 bytes is accepted")
            val oneOver = f.svc.raise(command(1000 - delta + 1, "one-over")).err()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, oneOver.code, "4097 bytes is refused")
            assertEquals("4097", detail(oneOver).text("actual"))
            assertEquals(1, rowCount())
        }

    @Test
    fun `S15 probe - the cap counts UTF-8 bytes, so a two-byte character weighs two`(): Unit =
        runBlocking {
            val f = fixture()
            val options = bigOptions(8, 100, 250)

            suspend fun actualFor(question: String): Int {
                val error = f.svc.raise(f.cmd(question = question, options = options, context = "c".repeat(1000), dedupeKey = "u")).err()
                assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.code)
                return detail(error).text("actual")!!.toInt()
            }
            val ascii = actualFor("e".repeat(400))
            val twoByte = actualFor("\u00e9".repeat(400))

            assertEquals(400, twoByte - ascii, "each e-acute is one byte more than an e")
        }

    @Test
    fun `S21 the options, question, recommended, context and links limits are field violations with their pointers`(): Unit =
        runBlocking {
            val f = fixture()
            val cases =
                listOf(
                    Triple("no options", f.cmd(options = emptyList()), "/options"),
                    Triple("nine options", f.cmd(options = f.options("a", "b", "c", "d", "e", "f", "g", "h", "i")), "/options"),
                    Triple("an upper-case option key", f.cmd(options = f.options("A", "b")), "/options/0/key"),
                    Triple("an option key starting with a digit", f.cmd(options = f.options("a", "1b")), "/options/1/key"),
                    Triple("an option key with a space", f.cmd(options = f.options("a b", "c")), "/options/0/key"),
                    Triple("a 33-character option key", f.cmd(options = f.options("a" + "b".repeat(32), "c")), "/options/0/key"),
                    Triple("a duplicate option key", f.cmd(options = f.options("a", "a")), "/options/1/key"),
                    Triple(
                        "a 121-character option label",
                        f.cmd(options = listOf(DecisionRequestOption("a", "l".repeat(121)))),
                        "/options/0/label"
                    ),
                    Triple("an empty option label", f.cmd(options = listOf(DecisionRequestOption("a", ""))), "/options/0/label"),
                    Triple(
                        "a 301-character consequence",
                        f.cmd(options = listOf(DecisionRequestOption("a", "A", "c".repeat(301)))),
                        "/options/0/consequence"
                    ),
                    Triple("a recommendation that is not a key", f.cmd(recommended = "zzz"), "/recommended"),
                    Triple("an empty recommendation, which names no key", f.cmd(recommended = ""), "/recommended"),
                    Triple("a 501-character question", f.cmd(question = "q".repeat(501)), "/question"),
                    Triple("an empty question", f.cmd(question = ""), "/question"),
                    Triple("a 1001-character context", f.cmd(context = "c".repeat(1001)), "/context"),
                    Triple("eleven links", f.cmd(links = (1..11).map { RunSeatLink("url", "https://example.test/$it") }), "/links"),
                    Triple("a link of an unknown kind", f.cmd(links = listOf(RunSeatLink("blog", "https://example.test/1"))), "/links"),
                    Triple("a link with an empty ref", f.cmd(links = listOf(RunSeatLink("url", ""))), "/links"),
                    Triple("a 129-character dedupe key", f.cmd(dedupeKey = "k".repeat(129)), "/dedupeKey"),
                    Triple("an empty dedupe key", f.cmd(dedupeKey = ""), "/dedupeKey")
                )
            for ((what, command, pointer) in cases) {
                val error = f.svc.raise(command).err()
                assertEquals(ErrorCode.INVALID_REQUEST, error.code, "$what: ${error.message}")
                assertTrue(
                    pointers(error).any { it == pointer || it.startsWith("$pointer/") },
                    "$what: expected a violation at $pointer, got ${pointers(error)}"
                )
            }
            assertEquals(0, rowCount(), "no refused raise stored a row")
            assertEquals(0, decisionEventCount())
        }

    @Test
    fun `S21 each limit is admitted at its boundary`(): Unit =
        runBlocking {
            val f = fixture()
            var n = 0

            fun key(prefix: String) = "$prefix-${n++}"

            suspend fun accepted(
                what: String,
                command: DecisionRaiseCommand
            ) {
                val outcome = f.svc.raise(command)
                assertTrue(outcome is Outcome.Ok, "$what must be accepted: ${(outcome as? Outcome.Err)?.error?.message}")
            }

            accepted("a 500-character question", f.cmd(question = "q".repeat(500), dedupeKey = key("q")))
            accepted("a 1000-character context", f.cmd(context = "c".repeat(1000), dedupeKey = key("c")))
            accepted("a 32-character option key", f.cmd(options = f.options("a" + "b".repeat(31), "c"), dedupeKey = key("k")))
            accepted("a 120-character label", f.cmd(options = listOf(DecisionRequestOption("a", "l".repeat(120))), dedupeKey = key("l")))
            accepted(
                "a 300-character consequence",
                f.cmd(options = listOf(DecisionRequestOption("a", "A", "c".repeat(300))), dedupeKey = key("s"))
            )
            accepted("one option", f.cmd(options = f.options("a"), dedupeKey = key("one")))
            accepted("eight options", f.cmd(options = f.options("a", "b", "c", "d", "e", "f", "g", "h"), dedupeKey = key("eight")))
            accepted("ten links", f.cmd(links = (1..10).map { RunSeatLink("url", "https://e.test/$it") }, dedupeKey = key("links")))
            accepted(
                "a link kind of each type",
                f.cmd(links = listOf("item", "note", "doc", "commit", "url").map { RunSeatLink(it, "ref") }, dedupeKey = key("kinds"))
            )
            accepted("a recommendation naming the second key", f.cmd(recommended = "b", dedupeKey = key("rec")))
            accepted("a 128-character dedupe key", f.cmd(dedupeKey = "k".repeat(128)))
            accepted("a one-character dedupe key", f.cmd(dedupeKey = "k"))
        }

    @Test
    fun `S21 the answer is checked against the options and the class - option, text and rationale`(): Unit =
        runBlocking {
            val f = fixture()
            val fixed =
                f.svc
                    .raise(f.cmd(decisionClass = "signoff", dedupeKey = "fixed"))
                    .ok()
                    .decision
            val free =
                f.svc
                    .raise(f.cmd(decisionClass = "chat", dedupeKey = "free"))
                    .ok()
                    .decision

            val bad =
                listOf(
                    Triple("an option that is not a key", fixed.id to DecisionAnswerCommand(option = "zzz"), "/option"),
                    Triple("no option on a non-freeform class", fixed.id to DecisionAnswerCommand(), "/option"),
                    Triple("text on a non-freeform class", fixed.id to DecisionAnswerCommand(option = "a", text = "words"), "/text"),
                    Triple("text alone on a non-freeform class", fixed.id to DecisionAnswerCommand(text = "words"), "/text"),
                    Triple(
                        "a 1001-character rationale",
                        fixed.id to DecisionAnswerCommand(option = "a", rationale = "r".repeat(1001)),
                        "/rationale"
                    ),
                    Triple("a 1001-character text on a freeform class", free.id to DecisionAnswerCommand(text = "t".repeat(1001)), "/text"),
                    Triple("an option that is not a key on a freeform class", free.id to DecisionAnswerCommand(option = "zzz"), "/option")
                )
            for ((what, target, pointer) in bad) {
                val (id, command) = target
                assertInvalid(f.svc.answer(id, command, human).err(), pointer, what)
            }
            // an answer that names neither an option nor text on a freeform class is refused too
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                f.svc
                    .answer(free.id, DecisionAnswerCommand(rationale = "only why"), human)
                    .err()
                    .code
            )
            assertEquals("open", row(fixed.id)["status"])
            assertEquals("open", row(free.id)["status"])

            // controls: the boundaries are admitted
            f.svc.answer(fixed.id, DecisionAnswerCommand(option = "a", rationale = "r".repeat(1000)), human).ok()
            f.svc.answer(free.id, DecisionAnswerCommand(text = "t".repeat(1000)), human).ok()
            assertEquals(1000, (row(free.id)["answer_text"] as String).length)
            assertEquals(1000, (row(fixed.id)["answer_rationale"] as String).length)
        }

    @Test
    fun `S21 a freeform class takes an option, a text or both`(): Unit =
        runBlocking {
            val f = fixture()
            val optionOnly =
                f.svc
                    .raise(f.cmd(decisionClass = "chat", dedupeKey = "o"))
                    .ok()
                    .decision
            val textOnly =
                f.svc
                    .raise(f.cmd(decisionClass = "chat", dedupeKey = "t"))
                    .ok()
                    .decision
            val both =
                f.svc
                    .raise(f.cmd(decisionClass = "chat", dedupeKey = "b"))
                    .ok()
                    .decision

            f.svc.answer(optionOnly.id, DecisionAnswerCommand(option = "a"), human).ok()
            f.svc.answer(textOnly.id, DecisionAnswerCommand(text = "my own words"), human).ok()
            f.svc.answer(both.id, DecisionAnswerCommand(option = "b", text = "b, with a caveat"), human).ok()

            assertEquals("a" to null, row(optionOnly.id)["answer_option"] to row(optionOnly.id)["answer_text"])
            assertEquals(null to "my own words", row(textOnly.id)["answer_option"] to row(textOnly.id)["answer_text"])
            assertEquals("b" to "b, with a caveat", row(both.id)["answer_option"] to row(both.id)["answer_text"])
        }

    // ------------------------------------------------------------------ S20: expiry

    @Test
    fun `S20 a class with an expiry stamps expires_at from the raise instant`(): Unit =
        runBlocking {
            val f = fixture()
            val raisedAt = clock.now()

            val review =
                f.svc
                    .raise(f.cmd(decisionClass = "review"))
                    .ok()
                    .decision
            val deviation =
                f.svc
                    .raise(f.cmd(decisionClass = "deviation", dedupeKey = "d"))
                    .ok()
                    .decision
            val question =
                f.svc
                    .raise(f.cmd(decisionClass = "question", dedupeKey = "q"))
                    .ok()
                    .decision

            assertEquals(raisedAt.plusSeconds(600), stamp(row(review.id)["expires_at"]), "review expires after 600 seconds")
            assertEquals(raisedAt.plusSeconds(14_400), stamp(row(deviation.id)["expires_at"]), "deviation expires after 14,400 seconds")
            assertNull(row(question.id)["expires_at"], "question never expires")
            assertEquals("escalate", row(review.id)["on_expiry"])
            assertEquals(raisedAt.plusSeconds(600), review.expiresAt)
        }

    @Test
    fun `S20 an answer at or after expires_at sets after_expiry and one before does not`(): Unit =
        runBlocking {
            val f = fixture()
            val before =
                f.svc
                    .raise(f.cmd(decisionClass = "review", dedupeKey = "before"))
                    .ok()
                    .decision
            val exact =
                f.svc
                    .raise(f.cmd(decisionClass = "review", dedupeKey = "exact"))
                    .ok()
                    .decision
            val late =
                f.svc
                    .raise(f.cmd(decisionClass = "review", dedupeKey = "late"))
                    .ok()
                    .decision

            clock.advanceSeconds(599)
            f.svc.answer(before.id, DecisionAnswerCommand(option = "a"), directorCaller).ok()
            clock.advanceSeconds(1)
            f.svc.answer(exact.id, DecisionAnswerCommand(option = "a"), directorCaller).ok()
            clock.advanceSeconds(3_600)
            f.svc.answer(late.id, DecisionAnswerCommand(option = "a"), directorCaller).ok()

            assertEquals(0L, row(before.id)["after_expiry"], "one second before the expiry")
            assertEquals(1L, row(exact.id)["after_expiry"], "at the expiry instant")
            assertEquals(1L, row(late.id)["after_expiry"], "long after")
            assertEquals("true", data(events("decision.answered").first { uuidOf(it["entity_id"]) == late.id }).text("afterExpiry"))
            assertEquals("false", data(events("decision.answered").first { uuidOf(it["entity_id"]) == before.id }).text("afterExpiry"))
        }

    private fun domainDecision(
        status: DecisionRequestStatus,
        severity: DecisionRequestSeverity,
        expiresAt: Instant?
    ) = DecisionRequest(
        answer =
            if (status == DecisionRequestStatus.ANSWERED) {
                DecisionRequestAnswer(
                    option = "a",
                    answeredById = ORCH,
                    answeredByKind = "human",
                    answeredByProof = "issued",
                    answeredAt = Instant.parse("2026-10-10T11:30:00Z")
                )
            } else {
                null
            },
        rootId = UUID.randomUUID(),
        decisionClass = "review",
        answerers = DecisionRequestAnswerers.ANY_ACTOR,
        severity = severity,
        status = status,
        dedupeKey = "k",
        scopeKey = DecisionRequest.SCOPE_ROOT,
        question = "Q?",
        options = listOf(DecisionRequestOption("a", "A")),
        raisedById = ORCH,
        raisedByKind = "service",
        raisedAt = Instant.parse("2026-10-10T11:00:00Z"),
        expiresAt = expiresAt
    )

    @Test
    fun `S20 overdue is a pending decision at or past its expiry, and the severity rises one step while overdue`() {
        val expiry = Instant.parse("2026-10-10T12:00:00Z")
        val pending = listOf(DecisionRequestStatus.OPEN, DecisionRequestStatus.ACKNOWLEDGED)
        for (status in pending) {
            val d = domainDecision(status, DecisionRequestSeverity.LOW, expiry)
            assertFalse(d.isOverdue(expiry.minusMillis(1)), "$status one millisecond early")
            assertTrue(d.isOverdue(expiry), "$status at the expiry instant")
            assertTrue(d.isOverdue(expiry.plusSeconds(1)), "$status after")
        }
        for (status in listOf(DecisionRequestStatus.ANSWERED, DecisionRequestStatus.WITHDRAWN)) {
            val closed = domainDecision(status, DecisionRequestSeverity.LOW, expiry)
            assertFalse(closed.isOverdue(expiry.plusSeconds(3_600)), "$status is never overdue")
        }
        val neverExpires = domainDecision(DecisionRequestStatus.OPEN, DecisionRequestSeverity.LOW, null)
        assertFalse(neverExpires.isOverdue(Instant.parse("2030-01-01T00:00:00Z")), "no expiry is never overdue")

        val steps =
            mapOf(
                DecisionRequestSeverity.LOW to DecisionRequestSeverity.NORMAL,
                DecisionRequestSeverity.NORMAL to DecisionRequestSeverity.HIGH,
                DecisionRequestSeverity.HIGH to DecisionRequestSeverity.HIGH
            )
        for ((severity, escalated) in steps) {
            val d = domainDecision(DecisionRequestStatus.OPEN, severity, expiry)
            assertEquals(severity, d.effectiveSeverity(expiry.minusSeconds(1)), "$severity before the expiry")
            assertEquals(escalated, d.effectiveSeverity(expiry), "$severity once overdue")
            assertEquals(escalated, severity.escalated(), "$severity escalated")
        }
        val answered = domainDecision(DecisionRequestStatus.ANSWERED, DecisionRequestSeverity.LOW, expiry)
        assertEquals(
            DecisionRequestSeverity.LOW,
            answered.effectiveSeverity(expiry.plusSeconds(60)),
            "an answered decision does not escalate"
        )
    }

    // ------------------------------------------------------------------ run close: the open count

    @Test
    fun `the open count of a run is its open and acknowledged decisions, never answered or withdrawn ones or another run's`(): Unit =
        runBlocking {
            val f = fixture()
            val open =
                f.svc
                    .raise(f.cmd(dedupeKey = "open", itemId = null, runId = f.runA, decisionClass = "chat"))
                    .ok()
                    .decision
            val acked =
                f.svc
                    .raise(f.cmd(dedupeKey = "acked", itemId = null, runId = f.runA, decisionClass = "chat"))
                    .ok()
                    .decision
            val answered =
                f.svc
                    .raise(f.cmd(dedupeKey = "answered", itemId = null, runId = f.runA, decisionClass = "chat"))
                    .ok()
                    .decision
            val withdrawn =
                f.svc
                    .raise(f.cmd(dedupeKey = "withdrawn", itemId = null, runId = f.runA, decisionClass = "chat"))
                    .ok()
                    .decision
            f.svc.raise(f.cmd(dedupeKey = "elsewhere", itemId = null, runId = f.runB, decisionClass = "chat")).ok()
            f.svc.raise(f.cmd(dedupeKey = "rootwide", itemId = null, runId = null, decisionClass = "chat")).ok()
            f.svc.acknowledge(acked.id, human).ok()
            f.svc.answer(answered.id, DecisionAnswerCommand(option = "a"), human).ok()
            f.svc.withdraw(withdrawn.id, raiser()).ok()

            assertEquals(2, f.svc.openCount(f.runA), "the open one and the acknowledged one")
            assertEquals(1, f.svc.openCount(f.runB))
            assertEquals(0, f.svc.openCount(UUID.randomUUID()), "an unknown run has none")

            f.svc.withdraw(open.id, raiser()).ok()
            assertEquals(1, f.svc.openCount(f.runA))
        }
}

/**
 * Independent tests (test-author seat, item a53e6875, R24) for the tool half of the decision lifecycle, through the real MCP adapter
 * and the production dispatcher over the real composition and SQLite (the orchestration profile for `raise`, the director profile
 * for the reads and for ack, answer and withdraw): the order of the `raise` checks, the root and scope derivation, the seat-token
 * defaults, the director reads with their filters, paging, scope and provenance redaction, `items_read context` and the run close
 * snapshot.
 *
 * Oracles (never read from a run): task-scope D3 (an item gives the root and a run the run's root, neither is `invalid_request`
 * `/rootId` "one of itemId, runId or rootId is required", an item of another root than the run's is `/itemId`, a `rootId` that
 * disagrees with the derived root is `/rootId`, a lifecycle-free item is `/itemId` "has no lifecycle", the order is ids under scope
 * (`forbidden` before `not_found`), scope, lifecycle, class, payload, and a seat token supplies its run and item), D6 (`raised_by_*`
 * come from the credential: its kind wire and the seat), D8 and the published `api-reference.md` rows for `runs_read decisions` and
 * `decision` (newest first, id, class, severity, effectiveSeverity, status, overdue, question; runId, itemId and rootId filter;
 * `overdue` keeps the pending decisions past their expiry; an id is a UUID or an 8-32 hex prefix, `not_found{kind: decision}` or
 * `ambiguous_id`; a root outside the scope is `forbidden`, not `not_found`, and is left out of lists; the principal id, host and
 * session id appear only for an ADMIN), D8 for `items_read context` (`decisions[{id, class, status, option?}]`, withdrawn excluded,
 * omitted when empty) and D12 (the run close snapshot counts the open and acknowledged decisions of the run).
 *
 * Self-resolved from public evidence (`current/docs/api-reference.md` and the registered goldens, not from the implementation): the
 * result keys are looked up by name anywhere in the element result, so a nesting choice does not decide a test; a decision id is the
 * first `decisionId` (else `id`) UUID of a raise result.
 *
 * Negative-assertion fixture conditions: every refusal is paired with the same call on the input that succeeds and is followed by a
 * count of the decision table, which a refusal must leave unchanged.
 *
 * Domain invariants satisfied by construction: items are children of their root and seeded through the rig; the lifecycle-free item
 * is created through the item command service from a schema with `lifecycle: none`; runs are opened through the tool itself; tokens
 * are `ParityToken`s whose principal the harness builds.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class DecisionToolsTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val CONFIG = """
work_item_schemas:
  seated-task:
    notes: []
  plain-task:
    notes: []
  page:
    lifecycle: none
    notes: []
decision_classes:
  signoff:
    answerers: human-only
  review:
    answerers: director-or-human
    severity: low
    expires_after: 600
  chat:
    answerers: any-actor
    freeform: true
"""
    }

    private val base = setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE)
    private val dtOrch = ParityToken("dt-orch", base)
    private val dtHuman = ParityToken("dt-human", base, kind = PrincipalKind.HUMAN)
    private val dtAdmin = ParityToken("dt-admin", base + ApiCapability.ADMIN, kind = PrincipalKind.HUMAN)

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun element(
        key: String,
        cls: String = "question",
        extra: String = "",
        question: String = "Which one?"
    ) =
        """{"op":"raise","class":"$cls","question":"$question","options":[{"key":"a","label":"A"},{"key":"b","label":"B"}],"dedupeKey":"$key"$extra}"""

    private suspend fun SeatWorld.raise(
        key: String,
        cls: String = "question",
        extra: String = "",
        by: ParityToken = dtOrch,
        token: String? = null
    ): Reply = ops("runs_write", element(key, cls, extra), token = token, by = by)

    /** The `kind` of the error detail (the error object has a `kind` of its own: permanent or transient). */
    private fun detailKind(reply: Reply): String? = reply.find("kind", reply.findElement("detail", reply.error()))

    private fun decisionId(reply: Reply): String = assertNotNull(reply.find("decisionId") ?: reply.find("id"), "a decision id in $reply")

    private fun decisionRows(w: SeatWorld): List<Map<String, Any?>> =
        w.rows("SELECT * FROM decision_requests ORDER BY raised_at, dedupe_key")

    private fun eventTypes(w: SeatWorld): List<String> =
        w.rows("SELECT type FROM events WHERE type LIKE 'decision.%' ORDER BY seq").map {
            it["type"] as String
        }

    private fun objectsWith(
        within: JsonElement?,
        vararg keys: String
    ): List<JsonObject> =
        when (within) {
            is JsonObject ->
                (if (keys.all { it in within }) listOf(within) else emptyList()) +
                    within.values.flatMap { objectsWith(it, *keys) }
            is JsonArray -> within.flatMap { objectsWith(it, *keys) }
            else -> emptyList()
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun assertInvalid(
        reply: Reply,
        pointer: String,
        label: String
    ) {
        assertEquals("invalid_request", reply.code(), "$label: $reply")
        assertTrue(reply.pointers().any { it.endsWith(pointer) }, "$label: expected $pointer in ${reply.pointers()}: $reply")
    }

    // ------------------------------------------------------------------ raise: root, scope, defaults

    @Test
    fun `raise by root id stores a root-scoped open decision and a replay returns the same one`(): Unit =
        world { w ->
            val first = w.raise("k1", extra = ""","rootId":"${w.root.id}"""")
            assertTrue(first.ok(), "$first")
            val id = decisionId(first)
            assertNotEquals("true", first.find("existing"), "a first raise is new: $first")

            val row = decisionRows(w).single()
            assertEquals("root", row["scope_key"])
            assertEquals(w.root.id, w.uuidOf(row["root_id"]))
            assertNull(row["item_id"])
            assertNull(row["run_id"])
            assertEquals("api:dt-orch", row["raised_by_id"])
            assertEquals("service", row["raised_by_kind"], "the credential kind wire")
            assertNull(row["raised_by_seat"])
            assertEquals(listOf("decision.raised"), eventTypes(w))

            val again = w.raise("k1", extra = ""","rootId":"${w.root.id}"""")
            assertTrue(again.ok())
            assertEquals("true", again.find("existing"), "the replay says so: $again")
            assertEquals(id, decisionId(again))
            assertEquals(1, decisionRows(w).size)
            assertEquals(listOf("decision.raised"), eventTypes(w), "a replay records no event")
        }

    @Test
    fun `raise by item id derives the root of the item and keys by the item`(): Unit =
        world { w ->
            val reply = w.raise("k1", extra = ""","itemId":"${w.m1.id}"""")
            assertTrue(reply.ok(), "$reply")

            val row = decisionRows(w).single()
            assertEquals(w.m1.id.toString(), row["scope_key"])
            assertEquals(w.m1.id, w.uuidOf(row["item_id"]))
            assertEquals(w.root.id, w.uuidOf(row["root_id"]), "the item's root, not the item")
            // control: the same key on the root is another decision
            assertTrue(w.raise("k1", extra = ""","rootId":"${w.root.id}"""").ok())
            assertEquals(2, decisionRows(w).size)
        }

    @Test
    fun `raise by run id derives the root of the run and keys by the run`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))

            val reply = w.raise("k1", extra = ""","runId":"${run.runId}"""")
            assertTrue(reply.ok(), "$reply")

            val row = decisionRows(w).single()
            assertEquals(run.runId, w.uuidOf(row["run_id"]).toString())
            assertEquals(run.runId, row["scope_key"])
            assertEquals(w.root.id, w.uuidOf(row["root_id"]))
            assertNull(row["item_id"])
        }

    @Test
    fun `raise with an item and a run keys by the item and stores both`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))

            assertTrue(w.raise("k1", extra = ""","runId":"${run.runId}","itemId":"${w.m1.id}"""").ok())

            val row = decisionRows(w).single()
            assertEquals(w.m1.id.toString(), row["scope_key"])
            assertEquals(run.runId, w.uuidOf(row["run_id"]).toString())
            assertEquals(w.m1.id, w.uuidOf(row["item_id"]))
        }

    @Test
    fun `raise under a seat token takes the run and item of the seat and the raiser seat`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val seat = w.assignOk(run, w.m1, "implementer")

            val reply = w.raise("k1", token = seat.token, by = w.orch)
            assertTrue(reply.ok(), "a seat token supplies its run and item: $reply")

            val row = decisionRows(w).single()
            assertEquals(run.runId, w.uuidOf(row["run_id"]).toString())
            assertEquals(w.m1.id, w.uuidOf(row["item_id"]))
            assertEquals(w.m1.id.toString(), row["scope_key"])
            assertEquals("implementer", row["raised_by_seat"])
            assertEquals("api:seat-orch", row["raised_by_id"], "the seat is held by the opener's principal")
            // control: without the token the same call has no scope at all
            assertInvalid(w.raise("k2", by = w.orch), "/rootId", "no scope")
        }

    @Test
    fun `a human credential raises as kind human and the stored raiser is the credential`(): Unit =
        world { w ->
            assertTrue(w.raise("k1", extra = ""","rootId":"${w.root.id}"""", by = dtHuman).ok())

            val row = decisionRows(w).single()
            assertEquals("human", row["raised_by_kind"])
            assertEquals("api:dt-human", row["raised_by_id"])
        }

    // ------------------------------------------------------------------ raise: the order of the checks

    @Test
    fun `raise needs an item, a run or a root`(): Unit =
        world { w ->
            val none = w.raise("k1")
            assertInvalid(none, "/rootId", "no id at all")
            assertTrue(none.toString().contains("one of itemId, runId or rootId is required"), "the reason: $none")
            assertEquals(0, decisionRows(w).size)
        }

    @Test
    fun `raise refuses an item outside the run's root, a root that disagrees and a missing target`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val cases =
                listOf(
                    Triple("an item of another root than the run's", ""","runId":"${run.runId}","itemId":"${w.e.id}"""", "/itemId"),
                    Triple("a root that is not the item's root", ""","itemId":"${w.m1.id}","rootId":"${w.root2.id}"""", "/rootId"),
                    Triple("a root that is not the run's root", ""","runId":"${run.runId}","rootId":"${w.root2.id}"""", "/rootId")
                )
            for ((what, extra, pointer) in cases) {
                assertInvalid(w.raise("k-${what.length}", extra = extra), pointer, what)
            }
            val ghost = "99999999-9999-4999-8999-999999999999"
            assertEquals("not_found", w.raise("g1", extra = ""","itemId":"$ghost"""").code(), "an unknown item")
            assertEquals("not_found", w.raise("g2", extra = ""","runId":"$ghost"""").code(), "an unknown run")
            assertEquals("not_found", w.raise("g3", extra = ""","rootId":"$ghost"""").code(), "an unknown root")
            assertEquals(0, decisionRows(w).size, "no refusal stored a decision")

            // controls: the item of the run's own root, and the matching root, are accepted
            assertTrue(w.raise("ok1", extra = ""","runId":"${run.runId}","itemId":"${w.m1.id}"""").ok())
            assertTrue(w.raise("ok2", extra = ""","itemId":"${w.m1.id}","rootId":"${w.root.id}"""").ok())
        }

    @Test
    fun `raise on a lifecycle-free item is invalid_request on the item and a lifecycle item is accepted`(): Unit =
        world { w ->
            val pageId =
                seedItem(
                    w.rig.ctx,
                    buildJsonObject {
                        put("title", "A page")
                        put("type", "page")
                        put("parentId", w.root.id.toString())
                    }
                )

            val refused = w.raise("k1", extra = ""","itemId":"$pageId"""")
            assertInvalid(refused, "/itemId", "a lifecycle-free item")
            assertTrue(refused.toString().contains("has no lifecycle"), "the reason: $refused")
            assertEquals(0, decisionRows(w).size)

            assertTrue(w.raise("k2", extra = ""","itemId":"${w.m1.id}"""").ok(), "control: a lifecycle item")
        }

    @Test
    fun `the checks run in the stated order - scope before lifecycle, lifecycle before class, class before payload`(): Unit =
        world { w ->
            val pageId =
                seedItem(
                    w.rig.ctx,
                    buildJsonObject {
                        put("title", "A page")
                        put("type", "page")
                        put("parentId", w.root.id.toString())
                    }
                )
            // a token scoped to root 2 only: the page and every other item of root 1 are outside its scope
            val scoped = ParityToken("dt-scoped", base, rootIds = setOf(w.root2.id))

            val outOfScope = w.raise("o1", cls = "nonsense", extra = ""","itemId":"$pageId"""", by = scoped)
            assertEquals("forbidden", outOfScope.code(), "scope is checked before the lifecycle and the class: $outOfScope")
            assertEquals("root", outOfScope.find("scope", outOfScope.error()))

            val lifecycle = w.raise("o2", cls = "nonsense", extra = ""","itemId":"$pageId"""")
            assertInvalid(lifecycle, "/itemId", "the lifecycle is checked before the class")
            assertFalse(lifecycle.pointers().any { it.endsWith("/class") }, "the class is not reached: $lifecycle")

            val bigContext = "c".repeat(1000)
            val big =
                """{"op":"raise","class":"nonsense","question":"${"q".repeat(500)}","options":[""" +
                    (1..8).joinToString(",") { """{"key":"o$it","label":"${"l".repeat(120)}","consequence":"${"c".repeat(300)}"}""" } +
                    """],"dedupeKey":"o3","context":"$bigContext","rootId":"${w.root.id}"}"""
            val classFirst = w.ops("runs_write", big, by = dtOrch)
            assertInvalid(classFirst, "/class", "the class is checked before the payload size")
            assertEquals(0, decisionRows(w).size)
        }

    @Test
    fun `raise refuses an unknown class and the server-raised classes on the tool path`(): Unit =
        world { w ->
            for (name in listOf("nonsense", "Question", "run-budget", "attempt-cap")) {
                assertInvalid(w.raise("c-$name", cls = name, extra = ""","rootId":"${w.root.id}""""), "/class", name)
            }
            assertEquals(0, decisionRows(w).size)
            for (name in listOf("question", "deviation", "signoff", "review", "chat")) {
                assertTrue(w.raise("ok-$name", cls = name, extra = ""","rootId":"${w.root.id}"""").ok(), "control: $name")
            }
        }

    @Test
    fun `raise with a field of another op is an unknown parameter`(): Unit =
        world { w ->
            val reply = w.raise("k1", extra = ""","rootId":"${w.root.id}","reason":"cancelled"""")
            assertEquals("unknown_parameter", reply.code(), "$reply")
            assertEquals(0, decisionRows(w).size)
        }

    @Test
    fun `raise is refused without the runs capability and with a root outside the scope`(): Unit =
        world { w ->
            val reader = ParityToken("dt-reader", setOf(ApiCapability.READ))
            val noRuns = w.raise("k1", extra = ""","rootId":"${w.root.id}"""", by = reader)
            assertEquals("forbidden", noRuns.code(), "$noRuns")
            assertEquals("capability", noRuns.find("scope", noRuns.error()))

            val scoped = ParityToken("dt-scoped", base, rootIds = setOf(w.root2.id))
            val outside = w.raise("k2", extra = ""","rootId":"${w.root.id}"""", by = scoped)
            assertEquals("forbidden", outside.code(), "$outside")
            assertEquals("root", outside.find("scope", outside.error()))
            assertEquals(0, decisionRows(w).size)
            assertTrue(
                w.raise("k3", extra = ""","rootId":"${w.root2.id}"""", by = scoped).ok(),
                "control: the scoped token on its own root"
            )
        }

    @Test
    fun `raise replayed under one idempotency key returns the stored result and the same key with another element is a mismatch`(): Unit =
        world { w ->
            val keyed = element("idem").dropLast(1) + ""","rootId":"${w.root.id}","idempotencyKey":"call-1"}"""
            val first = w.ops("runs_write", keyed, by = dtOrch)
            assertTrue(first.ok(), "$first")
            val replay = w.ops("runs_write", keyed, by = dtOrch)
            assertTrue(replay.ok(), "$replay")
            assertEquals(1, decisionRows(w).size)
            assertEquals(listOf("decision.raised"), eventTypes(w))

            val different = element("idem-2", question = "Another?").dropLast(1) + ""","rootId":"${w.root.id}","idempotencyKey":"call-1"}"""
            assertEquals("idempotency_mismatch", w.ops("runs_write", different, by = dtOrch).code())
            assertEquals(1, decisionRows(w).size)
        }
    // ------------------------------------------------------------------ the director reads

    private suspend fun SeatWorld.readDecisions(
        extra: String = "",
        by: ParityToken = dtHuman
    ): Reply = director("runs_read", """{"select":"decisions"$extra}""", by)

    private suspend fun SeatWorld.readDecision(
        id: String,
        by: ParityToken = dtHuman
    ): Reply = director("runs_read", """{"select":"decision","decisionId":"$id"}""", by)

    private fun summaries(reply: Reply): List<JsonObject> = objectsWith(reply.data, "class", "severity", "status")

    @Test
    fun `decisions lists newest first with the summary fields, and a long question is cut to 120 characters`(): Unit =
        world { w ->
            val long = "x".repeat(300)
            val ids = mutableListOf<String>()
            for (i in 1..3) {
                clock.advanceSeconds(60)
                val extra = ""","rootId":"${w.root.id}""""
                ids +=
                    decisionId(
                        w.ops(
                            "runs_write",
                            element(
                                "k$i",
                                cls = "review",
                                extra = extra,
                                question =
                                    if (i ==
                                        3
                                    ) {
                                        long
                                    } else {
                                        "Q$i?"
                                    }
                            ),
                            by = dtOrch
                        )
                    )
            }

            val reply = w.readDecisions()

            assertTrue(reply.ok(), "$reply")
            val rows = summaries(reply)
            assertEquals(ids.reversed(), rows.map { it.str("id") }, "raisedAt descending")
            for (row in rows) {
                assertEquals("review", row.str("class"))
                assertEquals("low", row.str("severity"))
                assertEquals("low", row.str("effectiveSeverity"), "not overdue yet")
                assertEquals("open", row.str("status"))
                assertEquals("false", row.str("overdue"))
            }
            assertEquals(120, rows.first().str("question")!!.length, "the first 120 characters of the question")
            assertEquals("Q2?", rows[1].str("question"))
        }

    @Test
    fun `decisions filters by run, item, root and overdue`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val onRun = decisionId(w.raise("a", extra = ""","runId":"${run.runId}""""))
            val onItem = decisionId(w.raise("b", extra = ""","itemId":"${w.m2.id}""""))
            val onRoot = decisionId(w.raise("c", extra = ""","rootId":"${w.root.id}""""))
            val otherRoot = decisionId(w.raise("d", extra = ""","rootId":"${w.root2.id}""""))
            val expiring = decisionId(w.raise("e", cls = "review", extra = ""","rootId":"${w.root.id}""""))

            fun idsOf(reply: Reply) = summaries(reply).mapNotNull { it.str("id") }.toSet()

            assertEquals(setOf(onRun, onItem, onRoot, otherRoot, expiring), idsOf(w.readDecisions()))
            assertEquals(setOf(onRun), idsOf(w.readDecisions(""","runId":"${run.runId}"""")))
            assertEquals(setOf(onItem), idsOf(w.readDecisions(""","itemId":"${w.m2.id}"""")))
            assertEquals(setOf(onRun, onItem, onRoot, expiring), idsOf(w.readDecisions(""","rootId":"${w.root.id}"""")))
            assertEquals(setOf(otherRoot), idsOf(w.readDecisions(""","rootId":"${w.root2.id}"""")))
            assertEquals(emptySet(), idsOf(w.readDecisions(""","overdue":true""")), "nothing is overdue yet")

            clock.advanceSeconds(600)
            val overdue = w.readDecisions(""","overdue":true""")
            assertEquals(setOf(expiring), idsOf(overdue), "only the review decision has an expiry, and it has passed")
            val row = summaries(overdue).single()
            assertEquals("true", row.str("overdue"))
            assertEquals("normal", row.str("effectiveSeverity"), "low rises one step while overdue")
            assertEquals("low", row.str("severity"), "the declared severity is unchanged")
        }

    @Test
    fun `S7 decisions has no status filter - the argument is an unknown parameter, and every summary carries its own status`(): Unit =
        world { w ->
            val extra = ""","rootId":"${w.root.id}""""
            val open = decisionId(w.raise("s-open", cls = "chat", extra = extra))
            val acked = decisionId(w.raise("s-ack", cls = "chat", extra = extra))
            val answered = decisionId(w.raise("s-ans", cls = "chat", extra = extra))
            val withdrawn = decisionId(w.raise("s-wd", cls = "chat", extra = extra))
            assertTrue(w.writeDirector("""{"op":"ack","decisionId":"$acked"}""", dtHuman).ok())
            assertTrue(w.writeDirector("""{"op":"answer","decisionId":"$answered","option":"a"}""", dtHuman).ok())
            assertTrue(w.writeDirector("""{"op":"withdraw","decisionId":"$withdrawn"}""", dtOrch).ok())

            // control: without a filter the read succeeds and each listed request carries its status
            val all = w.readDecisions()
            assertTrue(all.ok(), "$all")
            val byId = summaries(all).associateBy { it.str("id") }
            for (id in listOf(open, acked, answered)) assertTrue(id in byId, "$id is listed: ${byId.keys}")
            assertEquals("open", byId.getValue(open).str("status"))
            assertEquals("acknowledged", byId.getValue(acked).str("status"))
            assertEquals("answered", byId.getValue(answered).str("status"))

            // a status argument would have narrowed the list had the filter existed; it is refused instead
            val filtered = w.readDecisions(""","status":"open"""")
            assertEquals("unknown_parameter", filtered.code(), "$filtered")
        }

    @Test
    fun `decisions pages with a limit and a cursor and visits every decision once`(): Unit =
        world { w ->
            val ids =
                (1..5).map { i ->
                    clock.advanceSeconds(1)
                    decisionId(w.raise("p$i", extra = ""","rootId":"${w.root.id}""""))
                }

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val extra = ""","limit":2""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val reply = w.readDecisions(extra)
                assertTrue(reply.ok(), "$reply")
                seen += summaries(reply).mapNotNull { it.str("id") }
                cursor = reply.find("next")?.takeIf { it != "null" }
                pages++
            } while (cursor != null && pages < 10)

            assertEquals(ids.reversed(), seen, "newest first across the pages, each decision once")
            assertEquals(3, pages, "five decisions at two a page")
        }

    @Test
    fun `decisions and decision leave out and refuse a root outside the call scope`(): Unit =
        world { w ->
            val inRoot = decisionId(w.raise("a", extra = ""","rootId":"${w.root.id}""""))
            val outRoot = decisionId(w.raise("b", extra = ""","rootId":"${w.root2.id}""""))
            val scoped = ParityToken("dt-scoped", base, rootIds = setOf(w.root.id))

            val listed = w.readDecisions(by = scoped)
            assertEquals(setOf(inRoot), summaries(listed).mapNotNull { it.str("id") }.toSet(), "the other root's decision is left out")

            assertTrue(w.readDecision(inRoot, by = scoped).ok(), "control: a decision in scope")
            val refused = w.readDecision(outRoot, by = scoped)
            assertEquals("forbidden", refused.code(), "outside the scope is forbidden, not not_found: $refused")
            assertEquals("root", refused.find("scope", refused.error()))
            val unknown = w.readDecision("99999999-9999-4999-8999-999999999999")
            assertEquals("not_found", unknown.code(), "an unrestricted caller sees not_found for an unknown id: $unknown")
            assertEquals("decision", detailKind(unknown), "of the kind decision")
        }

    private fun insertDecisionRow(
        w: SeatWorld,
        id: UUID,
        dedupeKey: String
    ) = w.exec(
        "INSERT INTO decision_requests (id, root_id, class, answerers, severity, status, dedupe_key, scope_key, question, options, " +
            "raised_by_id, raised_by_kind, raised_at, on_expiry) " +
            "VALUES (?, ?, 'question', 'any-actor', 'low', 'open', ?, 'root', 'Seeded?', '[{\"key\":\"a\",\"label\":\"A\"}]', " +
            "'api:seeded', 'service', '2026-10-10 12:00:00.000', 'escalate')",
        id,
        w.root.id,
        dedupeKey
    )

    @Test
    fun `decision resolves a full id or a hex prefix of 8 to 32 digits and reports an ambiguous one`(): Unit =
        world { w ->
            val one = UUID.fromString("ab000000-0000-4000-8000-000000000001")
            val two = UUID.fromString("ab000000-0000-4000-8000-000000000002")
            val lone = UUID.fromString("cd000000-0000-4000-8000-000000000003")
            insertDecisionRow(w, one, "s1")
            insertDecisionRow(w, two, "s2")
            insertDecisionRow(w, lone, "s3")

            assertEquals(one.toString(), w.readDecision(one.toString()).find("id"), "a full dashed id")
            assertEquals(one.toString(), w.readDecision(one.toString().replace("-", "")).find("id"), "32 hex digits")
            assertEquals(lone.toString(), w.readDecision("cd000000").find("id"), "a unique 8-digit prefix")
            for (prefix in listOf("ab000000", "ab000000000040008000000000000000".take(31))) {
                val ambiguous = w.readDecision(prefix)
                assertEquals("ambiguous_id", ambiguous.code(), "prefix $prefix is shared by two decisions: $ambiguous")
            }
            assertEquals("not_found", w.readDecision("ee000000").code(), "a well-formed prefix nobody has")
            assertEquals("invalid_request", w.readDecision("cd00000").code(), "7 digits is too short")
            assertEquals("invalid_request", w.readDecision("zzzzzzzz").code(), "not hex")
        }

    @Test
    fun `decision shows the question, options, recommendation, links, context and who raised it, with no principal or host`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val seat = w.assignOk(run, w.m1, "implementer")
            val extra =
                ""","recommended":"b","context":"why we ask","links":[{"kind":"url","ref":"https://example.test/x"}]""" +
                    ""","itemId":"${w.m1.id}""""
            val raised = w.ops("runs_write", element("k1", cls = "review", extra = extra), token = seat.token, by = w.orch)
            val id = decisionId(raised)

            val view = w.readDecision(id)

            assertTrue(view.ok(), "$view")
            assertEquals("review", view.find("class"))
            assertEquals("Which one?", view.find("question"))
            assertEquals("b", view.find("recommended"))
            assertEquals("why we ask", view.find("context"))
            assertEquals("https://example.test/x", view.find("ref"))
            assertEquals(listOf("a", "b"), objectsWith(view.data, "key", "label").mapNotNull { it.str("key") })
            assertEquals("service", view.find("kind", view.findElement("raisedBy")), "who raised it: the credential kind")
            assertEquals("implementer", view.find("seat", view.findElement("raisedBy")), "and the seat")
            val keys = view.allKeys()
            for (hidden in listOf("principalId", "host", "sessionId", "raised_by_id", "raisedById")) {
                assertFalse(hidden in keys, "a non-admin read must not carry $hidden: $keys")
            }
            assertFalse("api:seat-orch" in view.toString(), "the raiser's principal id is not on the wire: $view")
        }

    @Test
    fun `an answered decision shows its answer with the provenance kind, proof, seat, run and profile and hides the principal`(): Unit =
        world { w ->
            val id = decisionId(w.raise("k1", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))
            val answered =
                w.director(
                    "runs_write",
                    """{"ops":[{"op":"answer","decisionId":"$id","option":"b","rationale":"safer"}]}""",
                    dtHuman
                )
            assertTrue(answered.ok(), "$answered")

            val view = w.readDecision(id)

            assertEquals("answered", view.find("status"))
            val answer = assertNotNull(view.findElement("answer"), "the answer: $view")
            assertEquals("b", view.find("option", answer))
            assertEquals("safer", view.find("rationale", answer))
            assertEquals("false", view.find("afterExpiry", answer))
            val provenance = assertNotNull(view.findElement("provenance", answer), "the provenance: $view")
            assertEquals("human", view.find("kind", provenance))
            assertEquals("issued", view.find("proofStatus", provenance))
            assertEquals("director", view.find("profile", provenance), "the profile the answer was served on")
            for (hidden in listOf("principalId", "host", "sessionId")) {
                assertFalse(hidden in view.allKeys(), "a non-admin read must not carry $hidden: ${view.allKeys()}")
            }
            assertFalse("api:dt-human" in view.toString(), "the answerer's principal id is not on the wire: $view")
        }

    @Test
    fun `an admin read of an answered decision carries the principal id`(): Unit =
        world { w ->
            val id = decisionId(w.raise("k1", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))
            assertTrue(w.director("runs_write", """{"ops":[{"op":"answer","decisionId":"$id","option":"a"}]}""", dtHuman).ok())

            val plain = w.readDecision(id, by = dtHuman)
            val adminView = w.readDecision(id, by = dtAdmin)

            assertFalse("principalId" in plain.allKeys(), "control: the non-admin read hides it")
            assertTrue("principalId" in adminView.allKeys(), "an ADMIN read carries it: ${adminView.allKeys()}")
            assertTrue("api:dt-human" in adminView.toString(), "and it is the answerer: $adminView")
        }

    // ------------------------------------------------------------------ director writes through the tool

    private suspend fun SeatWorld.writeDirector(
        element: String,
        by: ParityToken,
        top: String = ""
    ): Reply = director("runs_write", """{"ops":[$element]$top}""", by)

    private fun statusOf(
        w: SeatWorld,
        id: String
    ): String = w.rows("SELECT status FROM decision_requests WHERE id = ?", UUID.fromString(id)).single()["status"] as String

    @Test
    fun `ack, answer and withdraw run on the director profile and each records one event`(): Unit =
        world { w ->
            val acked = decisionId(w.raise("a", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))
            val answered = decisionId(w.raise("b", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))
            val withdrawn = decisionId(w.raise("c", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))

            assertTrue(w.writeDirector("""{"op":"ack","decisionId":"$acked"}""", dtHuman).ok())
            assertEquals("acknowledged", statusOf(w, acked))
            val answer = w.writeDirector("""{"op":"answer","decisionId":"$answered","option":"a","rationale":"go"}""", dtHuman)
            assertTrue(answer.ok(), "$answer")
            assertEquals("answered", statusOf(w, answered))
            assertEquals("answered", answer.find("status"), "the op returns the decision: $answer")
            assertTrue(w.writeDirector("""{"op":"withdraw","decisionId":"$withdrawn"}""", dtOrch).ok(), "the raiser withdraws")
            assertEquals("withdrawn", statusOf(w, withdrawn))

            assertEquals(
                listOf(
                    "decision.raised",
                    "decision.raised",
                    "decision.raised",
                    "decision.acknowledged",
                    "decision.answered",
                    "decision.withdrawn"
                ),
                eventTypes(w)
            )
        }

    @Test
    fun `a second answer through the tool is decision_closed with the answered status`(): Unit =
        world { w ->
            val id = decisionId(w.raise("a", cls = "chat", extra = ""","rootId":"${w.root.id}""""))
            assertTrue(w.writeDirector("""{"op":"answer","decisionId":"$id","option":"a"}""", dtHuman).ok())

            val second = w.writeDirector("""{"op":"answer","decisionId":"$id","option":"b"}""", dtHuman)

            assertEquals("decision_closed", second.code(), "$second")
            assertEquals("answered", second.find("status", second.error()))
            assertEquals(id, second.find("decisionId", second.error()))
            assertEquals("a", w.rows("SELECT answer_option FROM decision_requests")[0]["answer_option"], "the first answer stands")
        }

    @Test
    fun `an answer that names no option of the decision or uses text on a fixed class is invalid_request on the field`(): Unit =
        world { w ->
            val id = decisionId(w.raise("a", cls = "signoff", extra = ""","rootId":"${w.root.id}""""))

            val bad = w.writeDirector("""{"op":"answer","decisionId":"$id","option":"zzz"}""", dtHuman)
            assertEquals("invalid_request", bad.code(), "$bad")
            assertTrue(bad.pointers().any { it.endsWith("/option") }, "the option pointer: ${bad.pointers()}")
            val text = w.writeDirector("""{"op":"answer","decisionId":"$id","option":"a","text":"words"}""", dtHuman)
            assertEquals("invalid_request", text.code(), "$text")
            assertTrue(text.pointers().any { it.endsWith("/text") }, "the text pointer: ${text.pointers()}")
            assertEquals("open", statusOf(w, id))
            assertTrue(
                w.writeDirector("""{"op":"answer","decisionId":"$id","option":"a"}""", dtHuman).ok(),
                "control: a key of the options"
            )
        }

    @Test
    fun `withdraw is the raiser or the coordinator token of the decision's run, through the tool`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val byOrch = decisionId(w.raise("a", extra = ""","runId":"${run.runId}""""))
            val forCoordinator = decisionId(w.raise("b", by = dtHuman, extra = ""","runId":"${run.runId}""""))

            val stranger = w.writeDirector("""{"op":"withdraw","decisionId":"$byOrch"}""", dtHuman)
            assertEquals("forbidden", stranger.code(), "$stranger")
            assertEquals("principal_kind", stranger.find("scope", stranger.error()))
            assertEquals("coordinator,raiser", stranger.find("required", stranger.error()))
            assertEquals("open", statusOf(w, byOrch))

            assertTrue(w.writeDirector("""{"op":"withdraw","decisionId":"$byOrch"}""", dtOrch).ok(), "the raiser")
            assertEquals("withdrawn", statusOf(w, byOrch))

            val notRaiser = w.writeDirector("""{"op":"withdraw","decisionId":"$forCoordinator"}""", w.orch)
            assertEquals("forbidden", notRaiser.code(), "the opener principal alone is not the coordinator: $notRaiser")
            val viaToken =
                w.writeDirector(
                    """{"op":"withdraw","decisionId":"$forCoordinator"}""",
                    w.orch,
                    top = ""","seatToken":"${run.coordinatorToken}""""
                )
            assertTrue(viaToken.ok(), "the coordinator token of the decision's run: $viaToken")
            assertEquals("withdrawn", statusOf(w, forCoordinator))
        }

    @Test
    fun `ack, answer and withdraw of an unknown decision are not_found of kind decision`(): Unit =
        world { w ->
            val ghost = "99999999-9999-4999-8999-999999999999"
            for (op in listOf("ack", "answer", "withdraw")) {
                val extra = if (op == "answer") ""","option":"a"""" else ""
                val reply = w.writeDirector("""{"op":"$op","decisionId":"$ghost"$extra}""", dtHuman)
                assertEquals("not_found", reply.code(), "$op: $reply")
                assertEquals("decision", detailKind(reply), op)
            }
        }

    @Test
    fun `a decision outside the call scope is forbidden for ack, answer and withdraw and stays open`(): Unit =
        world { w ->
            val id = decisionId(w.raise("a", cls = "chat", extra = ""","rootId":"${w.root.id}""""))
            val scoped = ParityToken("dt-scoped-human", base, rootIds = setOf(w.root2.id), kind = PrincipalKind.HUMAN)

            for (op in listOf("ack", "answer", "withdraw")) {
                val extra = if (op == "answer") ""","option":"a"""" else ""
                val reply = w.writeDirector("""{"op":"$op","decisionId":"$id"$extra}""", scoped)
                assertEquals("forbidden", reply.code(), "$op: $reply")
                assertEquals("root", reply.find("scope", reply.error()), op)
            }
            assertEquals("open", statusOf(w, id))
        }

    // ------------------------------------------------------------------ items_read context and the run close snapshot

    @Test
    fun `items_read context lists the decisions of the item without the withdrawn ones, and omits the key when there are none`(): Unit =
        world { w ->
            val extra = ""","itemId":"${w.m1.id}""""
            val open = decisionId(w.raise("o", cls = "chat", extra = extra))
            val answered = decisionId(w.raise("a", cls = "chat", extra = extra))
            val withdrawn = decisionId(w.raise("w", cls = "chat", extra = extra))
            val acked = decisionId(w.raise("k", cls = "chat", extra = extra))
            assertTrue(w.writeDirector("""{"op":"answer","decisionId":"$answered","option":"b"}""", dtHuman).ok())
            assertTrue(w.writeDirector("""{"op":"withdraw","decisionId":"$withdrawn"}""", dtOrch).ok())
            assertTrue(w.writeDirector("""{"op":"ack","decisionId":"$acked"}""", dtHuman).ok())

            val context = w.call("items_read", """{"select":"context","itemId":"${w.m1.id}"}""", by = dtOrch)

            assertTrue(!context.isError && context.wholeCode() == null, "$context")
            val listed = (context.findElement("decisions") as? JsonArray)?.map { it.jsonObject } ?: error("no decisions array in $context")
            assertEquals(setOf(open, answered, acked), listed.mapNotNull { it.str("id") }.toSet(), "withdrawn excluded")
            val byId = listed.associateBy { it.str("id") }
            assertEquals("open", byId.getValue(open).str("status"))
            assertEquals("acknowledged", byId.getValue(acked).str("status"))
            assertEquals("answered", byId.getValue(answered).str("status"))
            assertEquals("b", byId.getValue(answered).str("option"), "an answered decision carries its option")
            assertNull(byId.getValue(open).str("option"), "an open decision has no option")
            assertTrue(listed.all { it.str("class") == "chat" })

            val none = w.call("items_read", """{"select":"context","itemId":"${w.m2.id}"}""", by = dtOrch)
            assertTrue(none.wholeCode() == null, "$none")
            assertFalse("decisions" in none.allKeys(), "an item with no decision omits the key: ${none.allKeys()}")
        }

    @Test
    fun `the run close snapshot counts the open and acknowledged decisions of the run and withdraws none`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val extra = ""","runId":"${run.runId}""""
            val open = decisionId(w.raise("o", cls = "chat", extra = extra))
            val acked = decisionId(w.raise("k", cls = "chat", extra = extra))
            val answered = decisionId(w.raise("a", cls = "chat", extra = extra))
            val withdrawn = decisionId(w.raise("w", cls = "chat", extra = extra))
            assertTrue(w.raise("elsewhere", cls = "chat", extra = ""","rootId":"${w.root.id}"""").ok())
            assertTrue(w.writeDirector("""{"op":"ack","decisionId":"$acked"}""", dtHuman).ok())
            assertTrue(w.writeDirector("""{"op":"answer","decisionId":"$answered","option":"a"}""", dtHuman).ok())
            assertTrue(w.writeDirector("""{"op":"withdraw","decisionId":"$withdrawn"}""", dtOrch).ok())

            val closed = w.close(run, "cancelled", token = run.coordinatorToken)

            assertTrue(closed.ok(), "$closed")
            assertEquals("2", closed.find("openDecisions"), "the open one and the acknowledged one: $closed")
            assertEquals("open", statusOf(w, open), "closing the run withdraws nothing")
            assertEquals("acknowledged", statusOf(w, acked))
        }

    @Test
    fun `the run close snapshot reports no open decisions for a run without any`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))

            val closed = w.close(run, "cancelled", token = run.coordinatorToken)

            assertTrue(closed.ok(), "$closed")
            assertEquals("0", closed.find("openDecisions"), "$closed")
        }
}

/**
 * Independent tests (test-author seat, item a53e6875, R24) for the SSE projection of the four decision events on
 * `GET /api/v1/events`: what a frame carries, and who sees it, live and on `Last-Event-ID` replay.
 *
 * Oracles (never read from a run): task-scope D11 and `api-rest.md` (the event types `decision.raised`, `decision.acknowledged`,
 * `decision.answered` and `decision.withdrawn`; a frame carries `decisionId`, `class` and `severity`, and `itemId` / `runId` when
 * the decision names them, and never an actor, a principal id, a host, a session id, the answer text or the rationale; with an
 * `itemId` the event has item-scoped visibility like `note.*`, so a tag-scoped subscriber sees it when the item's tags are admitted;
 * without one it is anchored to its root like `run.*`, so a root-scoped subscriber sees it when its root set holds the root and a
 * tag-scoped one when the root item's tags are admitted; an unrestricted subscriber sees all; live and replay apply the same rule).
 * Frames are read as raw JSON objects, so the Kotlin property names of the frame class play no part.
 *
 * Negative-assertion fixture conditions: every hidden decision is paired with a visible one on the same connection, so an empty
 * stream cannot pass the absence checks.
 *
 * Domain invariants satisfied by construction: roots and items are seeded through the rig with tags; decisions are raised through the
 * service, which records the event rows the bus replays.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DecisionSseTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val OPEN_TOKEN = "decision-sse-open-token"
        const val ROOT_TOKEN = "decision-sse-root-token"
        const val TAG_TOKEN = "decision-sse-tag-token"
        const val SETTLE_DELAY_MS = 150L
        const val COLLECT_WINDOW_MS = 900L

        fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        fun entry(
            id: String,
            rootIds: Set<UUID>?,
            tagsInclude: Set<String>
        ) = HashBytes(sha256(id)) to
            BearerTokenStore.TokenEntry(
                Principal(
                    id = "decision-sse-$id",
                    scope = AccessScope.principal(rootIds = rootIds, tagsInclude = tagsInclude),
                    capabilities = setOf(ApiCapability.READ),
                    kind = PrincipalKind.SERVICE,
                    proofStatus = ProofStatus.ISSUED
                ),
                expiresAt = null
            )

        suspend fun collectWithin(incoming: Flow<ServerSentEvent>): List<JsonObject> {
            val collected = mutableListOf<JsonObject>()
            coroutineScope {
                val job = launch { incoming.collect { sse -> collected.add(Json.parseToJsonElement(sse.data.orEmpty()).jsonObject) } }
                delay(COLLECT_WINDOW_MS)
                job.cancel()
            }
            return collected
        }
    }

    private class Fixture(
        val rig: EventLogRig,
        val alphaRoot: WorkItem,
        val alphaItem: WorkItem,
        val betaRoot: WorkItem,
        val betaItem: WorkItem
    ) {
        val svc get() = rig.ctx.decisionService

        fun cmd(
            root: WorkItem,
            item: WorkItem?,
            key: String
        ) = DecisionRaiseCommand(
            rootId = root.id,
            itemId = item?.id,
            decisionClass = "question",
            question = "Which one?",
            options = listOf(DecisionRequestOption("a", "A"), DecisionRequestOption("b", "B")),
            dedupeKey = key,
            raisedById = "api:orch",
            raisedByKind = "service"
        )
    }

    private fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, "work_item_schemas: {}\n")
        return runBlocking {
            val alpha = rig.seed("Alpha root", tags = "alpha")
            val beta = rig.seed("Beta root", tags = "beta")
            Fixture(
                rig,
                alpha,
                rig.seed("Alpha item", parent = alpha, tags = "alpha"),
                beta,
                rig.seed("Beta item", parent = beta, tags = "beta")
            )
        }
    }

    private val answerer =
        DecisionCaller(
            principalId = "api:person-secret",
            kind = PrincipalKind.HUMAN,
            proofStatus = ProofStatus.ISSUED,
            director = false,
            seat = null,
            runId = null,
            assignmentId = null,
            provenance =
                AnswerProvenance(
                    "api:person-secret",
                    PrincipalKind.HUMAN,
                    ProofStatus.ISSUED,
                    "laptop-secret-host",
                    "session-secret",
                    null,
                    null,
                    "rest"
                )
        )

    /** Raises, acknowledges, answers and withdraws decisions on the four anchors, returning every row the writes recorded. */
    private suspend fun produce(f: Fixture): List<EventRecord> =
        f.rig
            .written {
                val anchors = listOf(f.alphaRoot to null, f.alphaRoot to f.alphaItem, f.betaRoot to null, f.betaRoot to f.betaItem)
                anchors.forEachIndexed { i, (root, item) ->
                    val d =
                        f.svc
                            .raise(f.cmd(root, item, "k$i"))
                            .getOrNull()!!
                            .decision
                    f.svc.acknowledge(d.id, answerer).getOrNull()
                    f.svc.answer(d.id, DecisionAnswerCommand(option = "a", rationale = "secret rationale"), answerer).getOrNull()
                    val w =
                        f.svc
                            .raise(f.cmd(root, item, "w$i"))
                            .getOrNull()!!
                            .decision
                    f.svc
                        .withdraw(
                            w.id,
                            DecisionCaller("api:orch", PrincipalKind.SERVICE, ProofStatus.ISSUED, false, null, null, null, null)
                        ).getOrNull()
                }
            }.second

    private fun Application.wire(
        bus: ApiEventBus,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry>
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                entries,
                allowQueryToken = false,
                authCheckIntervalSeconds = 60,
                workItemRepository = db.repositoryProvider().workItemRepository(),
                redactAttribution = true
            )
        }
    }

    private fun entries(f: Fixture) =
        mapOf(
            entry(OPEN_TOKEN, null, emptySet()),
            entry(ROOT_TOKEN, setOf(f.alphaRoot.id), emptySet()),
            entry(TAG_TOKEN, null, setOf("alpha"))
        )

    private suspend fun ApplicationTestBuilder.stream(
        token: String,
        lastEventId: String?,
        duringConnection: suspend () -> Unit = {}
    ): List<JsonObject> {
        val client = createClient { install(ClientSSE) }
        val collected = mutableListOf<JsonObject>()
        withTimeout(10.seconds) {
            coroutineScope {
                launch {
                    delay(SETTLE_DELAY_MS)
                    duringConnection()
                }
                client.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        if (lastEventId != null) header("Last-Event-ID", lastEventId)
                    }
                ) {
                    collected.addAll(collectWithin(incoming))
                }
            }
        }
        return collected
    }

    private fun decisionFrames(frames: List<JsonObject>) =
        frames.filter { (it["event"]?.jsonPrimitive?.content ?: "").startsWith("decision.") }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun rootsOf(frames: List<JsonObject>) = decisionFrames(frames).mapNotNull { it.text("rootId") }.toSet()

    @Test
    fun `a decision frame carries the decision id, class and severity, and never a principal, host, session, text or rationale`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f)

            val frames = decisionFrames(stream(OPEN_TOKEN, FROM_START))

            assertEquals(
                setOf("decision.raised", "decision.acknowledged", "decision.answered", "decision.withdrawn"),
                frames.mapNotNull { it.text("event") }.toSet(),
                "all four types arrive: $frames"
            )
            assertEquals(4 * 4 + 4, frames.size, "per anchor: raise, ack, answer, a second raise and its withdraw: $frames")
            for (frame in frames) {
                assertNotNull(frame.text("decisionId"), "decisionId: $frame")
                assertEquals("question", frame.text("class"), "class: $frame")
                assertEquals("low", frame.text("severity"), "severity: $frame")
                val serialized = frame.toString()
                for (secret in listOf("person-secret", "laptop-secret-host", "session-secret", "secret rationale")) {
                    assertFalse(secret in serialized, "a frame must not carry '$secret': $frame")
                }
                for (key in listOf("actor", "principalId", "host", "sessionId", "text", "rationale", "option")) {
                    assertFalse(key in frame.keys, "a frame has no $key member: $frame")
                }
            }
            val withItem = frames.filter { it.text("itemId") != null }
            val withoutItem = frames.filter { it.text("itemId") == null }
            assertEquals(10, withItem.size, "the item-anchored decisions' frames name their item")
            assertEquals(10, withoutItem.size)
            assertTrue(withItem.all { it.text("itemId") in setOf(f.alphaItem.id.toString(), f.betaItem.id.toString()) })
        }

    @Test
    fun `replay - a root-scoped subscriber sees the decisions of its root, item-anchored or not, and nobody else's`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f)

            val open = stream(OPEN_TOKEN, FROM_START)
            assertEquals(setOf(f.alphaRoot.id.toString(), f.betaRoot.id.toString()), rootsOf(open), "unrestricted control: $open")

            val scoped = stream(ROOT_TOKEN, FROM_START)
            assertEquals(10, decisionFrames(scoped).size, "alpha's two anchors, five frames each: $scoped")
            assertEquals(setOf(f.alphaRoot.id.toString()), rootsOf(scoped), "only the subscriber's own root: $scoped")
            assertTrue(decisionFrames(scoped).any { it.text("itemId") == null }, "the root-anchored decision is delivered: $scoped")
            assertTrue(decisionFrames(scoped).any { it.text("itemId") != null }, "and so is the item-anchored one: $scoped")
        }

    @Test
    fun `replay - a tag-scoped subscriber sees a decision whose item or root item carries an admitted tag`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f)

            val scoped = stream(TAG_TOKEN, FROM_START)

            assertEquals(10, decisionFrames(scoped).size, "alpha's two anchors, five frames each: $scoped")
            assertEquals(setOf(f.alphaRoot.id.toString()), rootsOf(scoped), "the beta-tagged root and item stay hidden: $scoped")
            assertTrue(
                decisionFrames(scoped).any { it.text("itemId") == null },
                "the root-anchored decision follows its root item's tags: $scoped"
            )
            assertTrue(
                decisionFrames(scoped).any {
                    it.text("itemId") == f.alphaItem.id.toString()
                },
                "the item-anchored one follows its item's tags: $scoped"
            )
        }

    @Test
    fun `live - the same rule applies to decision frames delivered as they commit`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }

            val open = stream(OPEN_TOKEN, null) { bus.committed(produce(f)) }
            assertEquals(setOf(f.alphaRoot.id.toString(), f.betaRoot.id.toString()), rootsOf(open), "unrestricted control: $open")

            val scoped = stream(ROOT_TOKEN, null) { bus.committed(produce(f)) }
            assertTrue(decisionFrames(scoped).isNotEmpty(), "the in-scope decision frames arrive live: $scoped")
            assertEquals(setOf(f.alphaRoot.id.toString()), rootsOf(scoped), "only the subscriber's own root: $scoped")

            val tagged = stream(TAG_TOKEN, null) { bus.committed(produce(f)) }
            assertTrue(decisionFrames(tagged).isNotEmpty(), "the alpha-tagged decisions arrive live: $tagged")
            assertEquals(setOf(f.alphaRoot.id.toString()), rootsOf(tagged), "the beta-tagged root's decisions stay hidden: $tagged")
        }
}

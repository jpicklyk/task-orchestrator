package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.AssigneeHolder
import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.InFlightSeat
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.EventClass
import io.github.jpicklyk.mcptask.current.domain.event.EventClasses
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 95a22e6e, R22) for [SeatService] over the production composition and a real SQLite
 * database: the coordinator row, assign and supersede, the token (shape, storage, resolution), end, usage and the liveness touch.
 * Scenarios S1 to S3, S8, S10, S12 to S14, S16 and S18 of the frozen test-plan at the service seam (the op and dispatcher halves
 * live in `RunsSeatOpsTest` and `SeatTokenLiftTest`).
 *
 * Oracles (never read from a run): task-scope "Token" (32 random bytes, base64url, stored as SHA-256; unknown, ended or presented
 * by a principal other than the row's `principal_id` is refused), D6 (a re-assign ends the old row `superseded`, the new attempt is
 * 1 + the maximum over EVERY row of the (run, item, seat) triple, ended included, and the old holder's claims are released in the
 * same unit with reason `superseded`), D7 (the coordinator row is run-scoped: no item, attempt 1), D8 (end releases the claims of
 * the ending holder only and leaves leases alone; the `seat.ended` event carries runId, itemId, seat, attempt, outcome, summary
 * and links), D9 (usage keeps the maximum per field independently; the liveness touch is skipped while the stored value is under
 * 5 s old and written from 5 s on), D5 (membership, seat vocabulary: the item's schema seats, or `implementer` / `planner` for a
 * seat-less or schema-free item, never `coordinator`), the FIPS 180-2 SHA-256 test vector for the hash, and the migration-assessment
 * column list for the stored row. Tokens are checked by shape and by independent hashing, never against a literal a run produced.
 *
 * Negative-assertion fixture conditions: every "refused / not released / not written" assertion is paired with the same call that
 * succeeds when the single input it turns on is changed (the right principal, the live token, the same seat, the exact interval).
 *
 * Domain invariants satisfied by construction: items are children of the run's root; the run is seeded with its members through
 * `ParitySeeder.run`, which derives the state fields from the status.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SeatServiceTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val ORCH = "api:orch"
        const val OTHER = "api:other"

        const val CONFIG = """
work_item_schemas:
  seated-task:
    seats:
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
      - key: check-notes
        role: work
        required: false
        seat: checker
  plain-task:
    notes: []
"""
        val BASE64URL = Regex("^[A-Za-z0-9_-]{43}$")
    }

    private fun <T> Outcome<T>.ok(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

    private fun <T> Outcome<T>.err(): DomainError = (this as? Outcome.Err)?.error ?: error("expected Outcome.Err, got $this")

    private inner class Fixture(
        val rig: EventLogRig,
        val root: WorkItem,
        val seated: WorkItem,
        val seatedTwo: WorkItem,
        val plain: WorkItem,
        val free: WorkItem,
        val outsider: WorkItem,
        val run: Run
    ) {
        val seats get() = rig.ctx.seatService
        val claims = ClaimService(rig.provider, rig.composition.unitOfWork)

        suspend fun assign(
            item: WorkItem,
            seat: String,
            principal: String = ORCH,
            hints: Map<String, String> = emptyMap(),
            runId: UUID = run.id
        ): Outcome<IssuedSeat> = seats.assign(SeatAssignCommand(runId, item.id, seat, hints, principal))
    }

    private suspend fun fixture(runStatus: RunStatus = RunStatus.OPEN): Fixture {
        val rig = EventLogRig.build(db.db, dir, CONFIG, clock)
        val root = rig.seed("Seat root")
        val seated = rig.seed("Seated one", parent = root, tags = "seated-task")
        val seatedTwo = rig.seed("Seated two", parent = root, tags = "seated-task")
        val plain = rig.seed("Plain", parent = root, tags = "plain-task")
        val free = rig.seed("Free", parent = root, tags = "free-task")
        val outsider = rig.seed("Outsider", parent = root, tags = "seated-task")
        val provider = db.db.repositoryProvider()
        val run =
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork(clock)).run(
                id = UUID.randomUUID(),
                rootId = root.id,
                items = listOf(seated.id, seatedTwo.id, plain.id, free.id),
                status = runStatus,
                pauseReason = RunPauseReason.MANUAL,
                terminalReason = RunTerminalReason.CANCELLED,
                openedBy = ORCH,
                openedByKind = "service",
                openedAt = Instant.parse("2026-10-10T11:00:00Z"),
                // the tests open the coordinator themselves through the service (one active coordinator per run), so the seeder adds none
                coordinatorToken = null
            )
        return Fixture(rig, root, seated, seatedTwo, plain, free, outsider, run)
    }

    // ------------------------------------------------------------------ raw reads of run_seats and claims

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

    private fun seatRow(assignmentId: UUID): Map<String, Any?> = query("SELECT * FROM run_seats WHERE id = ?", assignmentId).single()

    private fun allSeatRows(runId: UUID): List<Map<String, Any?>> =
        query("SELECT * FROM run_seats WHERE run_id = ? ORDER BY assigned_at, attempt", runId)

    private fun stamp(text: Any?): Instant = LocalDateTime.parse((text as String).replace(' ', 'T')).toInstant(ZoneOffset.UTC)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun usageOf(assignmentId: UUID): Pair<Long, Long> =
        seatRow(assignmentId).let { (it["usage_tokens"] as Long) to (it["usage_cost_micro"] as Long) }

    private suspend fun EventLogRig.eventsOf(
        type: String,
        block: suspend () -> Unit
    ): List<EventRecord> {
        val mark = maxSeq()
        block()
        return rowsAfter(mark).filter { it.type == type }
    }

    // ------------------------------------------------------------------ the token

    @Test
    fun `S1 the coordinator row is run-scoped and active, its token is 32 base64url bytes stored only as a SHA-256`() =
        runBlocking {
            val f = fixture()

            val issued = f.seats.openCoordinator(f.run, ORCH).ok()

            val token = issued.token
            assertTrue(BASE64URL.matches(token), "43 base64url characters, no padding: [$token]")
            assertEquals(32, Base64.getUrlDecoder().decode(token).size, "the token is 32 bytes")
            val seat = issued.seat
            assertEquals("coordinator", seat.seat)
            assertNull(seat.itemId, "the coordinator pseudo-seat has no item")
            assertEquals(f.run.id, seat.runId)
            assertEquals(1, seat.attempt)
            assertEquals(ORCH, seat.principalId)
            assertEquals(RunSeatStatus.ACTIVE, seat.status)
            assertNull(seat.outcome)
            assertNull(seat.endedAt)

            val row = seatRow(seat.id)
            assertEquals(sha256Hex(token), row["token_hash"], "the stored hash is the SHA-256 of the token's UTF-8 text")
            assertEquals(64, (row["token_hash"] as String).length)
            assertEquals("active", row["status"])
            assertNull(row["item_id"], "item_id is NULL for the coordinator row")
            assertEquals(ORCH, row["principal_id"])
            assertEquals(clock.now(), stamp(row["assigned_at"]), "assigned_at is the unit clock")
            // the plaintext token is stored nowhere in the table
            val everything = query("SELECT * FROM run_seats").flatMap { it.values }.map { it.toString() }
            assertTrue(everything.none { token in it }, "no column of run_seats holds the token: $everything")
        }

    @Test
    fun `S1 the hash of the token is the SHA-256 of its UTF-8 text - the FIPS test vector, and equal to an independent digest`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SeatService.hash("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SeatService.hash(""))
        val token = "A".repeat(43)
        assertEquals(sha256Hex(token), SeatService.hash(token))
        assertEquals(SeatService.hash(token), SeatService.hash(token), "hashing is deterministic")
        assertNotEquals(SeatService.hash(token), SeatService.hash("B".repeat(43)))
    }

    @Test
    fun `S12 the refusal is one fixed unauthenticated error with the fixed message and no fix arguments`() {
        val error = SeatService.tokenRefused()
        assertEquals(ErrorCode.UNAUTHENTICATED, error.code)
        assertEquals("The seat token is not accepted.", error.message)
        assertEquals(SeatService.TOKEN_REFUSED_MESSAGE, error.message)
        assertEquals(emptyMap(), error.fixArgs, "no fix arguments")
        assertEquals(SeatService.tokenRefused().message, error.message, "the same text on every refusal")
    }

    @Test
    fun `tokens are unique and always 43 base64url characters`() =
        runBlocking {
            val f = fixture()
            val tokens = mutableSetOf<String>()
            tokens +=
                f.seats
                    .openCoordinator(f.run, ORCH)
                    .ok()
                    .token
            repeat(40) { i ->
                // supersede keeps the triple, so the attempt grows while every token stays distinct
                tokens += f.assign(f.seated, "builder").ok().token
                if (i % 10 == 0) tokens += f.assign(f.seatedTwo, "checker").ok().token
            }
            assertEquals(1 + 40 + 4, tokens.size, "every issued token is distinct")
            assertTrue(tokens.all { BASE64URL.matches(it) }, "every token has the shape: ${tokens.filterNot { BASE64URL.matches(it) }}")
        }

    // ------------------------------------------------------------------ S2 assign

    @Test
    fun `S2 assign stores the triple's row with attempt 1, hints and the assigning principal, and records one seat assigned`() =
        runBlocking {
            val f = fixture()
            f.seats.openCoordinator(f.run, ORCH).ok()
            clock.advanceSeconds(7)

            var issued: IssuedSeat? = null
            val rows =
                f.rig.eventsOf("seat.assigned") {
                    issued = f.assign(f.seated, "builder", hints = mapOf("model" to "opus", "wave" to "3")).ok()
                }

            val seat = assertNotNull(issued).seat
            assertEquals(f.run.id, seat.runId)
            assertEquals(f.seated.id, seat.itemId)
            assertEquals("builder", seat.seat)
            assertEquals(1, seat.attempt)
            assertEquals(ORCH, seat.principalId)
            assertEquals(RunSeatStatus.ACTIVE, seat.status)
            assertEquals(mapOf("model" to "opus", "wave" to "3"), seat.hints)
            val row = seatRow(seat.id)
            assertEquals(clock.now(), stamp(row["assigned_at"]))
            assertEquals(0L, row["usage_tokens"])
            assertEquals(0L, row["usage_cost_micro"])
            assertEquals(sha256Hex(assertNotNull(issued).token), row["token_hash"])

            val event = rows.single()
            assertEquals(f.run.id.toString(), event.str("runId"))
            assertEquals(f.seated.id.toString(), event.str("itemId"))
            assertEquals("builder", event.str("seat"))
            assertEquals(
                1,
                event
                    .payload()["attempt"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(
                "opus",
                event
                    .payload()["hints"]!!
                    .jsonObject["model"]!!
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `S2 a different seat or item of the same run is its own triple with attempt 1, and each token resolves to its own row`() =
        runBlocking {
            val f = fixture()
            val builder = f.assign(f.seated, "builder").ok()
            val checker = f.assign(f.seated, "checker").ok()
            val other = f.assign(f.seatedTwo, "builder").ok()

            assertEquals(listOf(1, 1, 1), listOf(builder.seat.attempt, checker.seat.attempt, other.seat.attempt))
            assertEquals(3, f.seats.list(f.run.id, null, false).size, "all three are active together")
            assertEquals(builder.seat.id, f.seats.resolve(builder.token, ORCH)?.id)
            assertEquals(checker.seat.id, f.seats.resolve(checker.token, ORCH)?.id)
            assertEquals(other.seat.id, f.seats.resolve(other.token, ORCH)?.id)
        }

    // ------------------------------------------------------------------ S3 supersede

    @Test
    fun `S3 re-assigning a triple ends the old row superseded, counts attempt 2 and kills the old token`() =
        runBlocking {
            val f = fixture()
            val first = f.assign(f.seated, "builder").ok()
            clock.advanceSeconds(5)

            val second = f.assign(f.seated, "builder").ok()

            assertEquals(2, second.seat.attempt)
            assertNotEquals(first.token, second.token)
            val old = seatRow(first.seat.id)
            assertEquals("ended", old["status"])
            assertEquals("superseded", old["outcome"])
            assertEquals(clock.now(), stamp(old["ended_at"]), "ended_at is the re-assign instant")
            assertEquals("active", seatRow(second.seat.id)["status"])
            assertNull(f.seats.resolve(first.token, ORCH), "the superseded token is dead")
            assertEquals(second.seat.id, f.seats.resolve(second.token, ORCH)?.id, "control: the new token resolves")
        }

    @Test
    fun `S3 the attempt counts every row of the triple, ended ones included, and other seats of the item do not count`() =
        runBlocking {
            val f = fixture()
            assertEquals(
                1,
                f
                    .assign(f.seated, "builder")
                    .ok()
                    .seat.attempt
            )
            assertEquals(
                1,
                f
                    .assign(f.seated, "checker")
                    .ok()
                    .seat.attempt,
                "another seat of the item starts at 1"
            )
            assertEquals(
                2,
                f
                    .assign(f.seated, "builder")
                    .ok()
                    .seat.attempt
            )
            assertEquals(
                3,
                f
                    .assign(f.seated, "builder")
                    .ok()
                    .seat.attempt,
                "the ended attempts 1 and 2 both count"
            )
            assertEquals(
                2,
                f
                    .assign(f.seated, "checker")
                    .ok()
                    .seat.attempt,
                "the checker triple has its own count"
            )
            assertEquals(
                1,
                f
                    .assign(f.seatedTwo, "builder")
                    .ok()
                    .seat.attempt,
                "the same seat on another item starts at 1"
            )
            val itemBytes = BaselineDataset.bytes(f.seated.id)
            val triple = allSeatRows(f.run.id).filter { it["seat"] == "builder" && (it["item_id"] as ByteArray).contentEquals(itemBytes) }
            assertEquals(listOf(1L, 2L, 3L), triple.map { it["attempt"] as Long }.sorted(), "three rows of the triple, ended ones kept")
            assertEquals(1, triple.count { it["status"] == "active" }, "exactly one of them is active")
        }

    @Test
    fun `S3 a re-assign releases the old holder's claims in the same unit with reason superseded, so attempt 2 can claim at once`() =
        runBlocking {
            val f = fixture()
            val first = f.assign(f.seated, "builder").ok()
            f.claims.claim(f.seated.id, ClaimHolder(ORCH, first.seat.id), 900).ok()
            assertEquals(
                first.seat.id,
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seated.id)!!
                    .claimAssignmentId,
                "fixture: the old seat holds the item"
            )

            var second: IssuedSeat? = null
            val released = f.rig.eventsOf("claim.released") { second = f.assign(f.seated, "builder").ok() }

            val reload =
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seated.id)!!
            assertNull(reload.claimedBy, "the old claim is released")
            assertNull(reload.claimAssignmentId)
            assertEquals("superseded", released.single().str("reason"))
            assertEquals(f.seated.id, released.single().entityId)
            // control: the new attempt claims without waiting for the old TTL
            val newHolder = ClaimHolder(ORCH, assertNotNull(second).seat.id)
            assertIs<ClaimResult.Success>(f.claims.claim(f.seated.id, newHolder, 900).ok())
        }

    @Test
    fun `S3 a re-assign of one triple releases nothing held by a sibling seat of the same principal`() =
        runBlocking {
            val f = fixture()
            val builder = f.assign(f.seated, "builder").ok()
            val checker = f.assign(f.seatedTwo, "checker").ok()
            f.claims.claim(f.seated.id, ClaimHolder(ORCH, builder.seat.id), 900).ok()
            f.claims.claim(f.seatedTwo.id, ClaimHolder(ORCH, checker.seat.id), 900).ok()

            f.assign(f.seated, "builder").ok()

            assertNull(
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seated.id)!!
                    .claimedBy,
                "the superseded seat's claim is released"
            )
            val sibling =
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seatedTwo.id)!!
            assertEquals(ORCH, sibling.claimedBy, "control: the sibling seat's claim stays")
            assertEquals(checker.seat.id, sibling.claimAssignmentId)
        }
    // ------------------------------------------------------------------ S12 .. S14 resolve

    @Test
    fun `S12 to S14 resolve returns the row only for a live token presented by the principal that holds it`() =
        runBlocking {
            val f = fixture()
            val issued = f.assign(f.seated, "builder").ok()
            val token = issued.token

            assertEquals(issued.seat.id, f.seats.resolve(token, ORCH)?.id, "control: the right principal, the live token")
            assertNull(f.seats.resolve(token, OTHER), "another principal")
            for (spelling in listOf("API:ORCH", "api:orch ", " api:orch", "api:orc", "api:orchx", "")) {
                assertNull(f.seats.resolve(token, spelling), "principal spelled [$spelling]")
            }
            assertNull(f.seats.resolve("x".repeat(43), ORCH), "an unknown token")
            assertNull(f.seats.resolve("", ORCH), "an empty token")
            assertNull(f.seats.resolve(token.dropLast(1), ORCH), "a truncated token")
            assertNull(f.seats.resolve(token + "A", ORCH), "a token with a character appended")
            assertNull(f.seats.resolve(" $token", ORCH), "a token with leading whitespace")
            assertNull(f.seats.resolve("$token ", ORCH), "a token with trailing whitespace")
            assertNull(f.seats.resolve(token + "=", ORCH), "a padded token")
            if (token.uppercase() != token) assertNull(f.seats.resolve(token.uppercase(), ORCH), "an upper-cased token")
            if (token.lowercase() != token) assertNull(f.seats.resolve(token.lowercase(), ORCH), "a lower-cased token")
            assertEquals(issued.seat.id, f.seats.resolve(token, ORCH)?.id, "control after the probes: the exact token still resolves")
        }

    @Test
    fun `S13 an ended seat's token no longer resolves, whichever way the seat ended`() =
        runBlocking {
            val f = fixture()
            val ended = f.assign(f.seated, "builder").ok()
            val superseded = f.assign(f.seatedTwo, "builder").ok()
            val live = f.assign(f.plain, "implementer").ok()
            f.seats.endSeat(ended.seat.id, RunSeatOutcome.DONE, null, emptyList()).ok()
            f.assign(f.seatedTwo, "builder").ok()

            assertNull(f.seats.resolve(ended.token, ORCH), "ended by end_seat")
            assertNull(f.seats.resolve(superseded.token, ORCH), "superseded")
            assertEquals(live.seat.id, f.seats.resolve(live.token, ORCH)?.id, "control: a live seat of the same run resolves")
        }

    // ------------------------------------------------------------------ S8 end

    @Test
    fun `S8 end stores the outcome, summary and links, releases the ending holder's claims only, and records one seat ended`() =
        runBlocking {
            val f = fixture()
            val builder = f.assign(f.seated, "builder").ok()
            val checker = f.assign(f.seatedTwo, "checker").ok()
            f.claims.claim(f.seated.id, ClaimHolder(ORCH, builder.seat.id), 900).ok()
            f.claims.claim(f.seatedTwo.id, ClaimHolder(ORCH, checker.seat.id), 900).ok()
            clock.advanceSeconds(11)
            val links = listOf(RunSeatLink("commit", "abc123"), RunSeatLink("url", "https://example.test/pr/1"))

            var result: SeatEndResult? = null
            val rows =
                f.rig.eventsOf("seat.ended") {
                    result = f.seats.endSeat(builder.seat.id, RunSeatOutcome.DONE, "all green", links).ok()
                }

            val end = assertNotNull(result)
            assertEquals(listOf(f.seated.id), end.released, "the ending holder's claim is reported")
            assertEquals(RunSeatStatus.ENDED, end.seat.status)
            assertEquals(RunSeatOutcome.DONE, end.seat.outcome)
            val row = seatRow(builder.seat.id)
            assertEquals("ended", row["status"])
            assertEquals("done", row["outcome"])
            assertEquals("all green", row["summary"])
            assertEquals(clock.now(), stamp(row["ended_at"]))
            assertNull(
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seated.id)!!
                    .claimedBy,
                "the ending seat's claim is released"
            )
            val sibling =
                f.rig.raw
                    .workItemRepository()
                    .getById(f.seatedTwo.id)!!
            assertEquals(checker.seat.id, sibling.claimAssignmentId, "control: the sibling seat keeps its claim")

            val event = rows.single()
            assertEquals(f.run.id.toString(), event.str("runId"))
            assertEquals(f.seated.id.toString(), event.str("itemId"))
            assertEquals("builder", event.str("seat"))
            assertEquals(
                1,
                event
                    .payload()["attempt"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals("done", event.str("outcome"))
            assertEquals("all green", event.str("summary"))
            val eventLinks = event.payload()["links"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("commit" to "abc123", "url" to "https://example.test/pr/1"),
                eventLinks.map { it["kind"]!!.jsonPrimitive.content to it["ref"]!!.jsonPrimitive.content }
            )
        }

    @Test
    fun `S8 each reportable outcome is stored with its wire word`() =
        runBlocking {
            val f = fixture()
            val pairs = listOf(RunSeatOutcome.DONE to "done", RunSeatOutcome.FAILED to "failed", RunSeatOutcome.ABANDONED to "abandoned")
            val targets = listOf(f.seated to "builder", f.seatedTwo to "builder", f.plain to "implementer")
            for ((i, pair) in pairs.withIndex()) {
                val issued = f.assign(targets[i].first, targets[i].second).ok()
                f.seats.endSeat(issued.seat.id, pair.first, null, emptyList()).ok()
                assertEquals(pair.second, seatRow(issued.seat.id)["outcome"], "outcome ${pair.first}")
            }
            assertEquals(setOf(RunSeatOutcome.DONE, RunSeatOutcome.FAILED, RunSeatOutcome.ABANDONED), SeatService.REPORTABLE_OUTCOMES)
        }

    @Test
    fun `S8 end leaves the leases of the item alone and reports no released claim when the seat held none`() =
        runBlocking {
            val f = fixture()
            val issued = f.assign(f.seated, "builder").ok()
            f.claims.acquireLeases(f.seated.id, "actor-lease", listOf("res-seat-test" to 900)).ok()
            val before =
                f.rig.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(f.seated.id)
                    .size
            assertEquals(1, before, "fixture: the item holds a lease")

            val result = f.seats.endSeat(issued.seat.id, RunSeatOutcome.FAILED, null, emptyList()).ok()

            assertEquals(emptyList(), result.released, "the seat held no claim")
            val after =
                f.rig.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(f.seated.id)
                    .size
            assertEquals(before, after, "the lease is the close cascade's, not the seat's")
        }

    // ------------------------------------------------------------------ S10 usage and liveness

    @Test
    fun `S10 usage keeps the maximum per field independently, as tokens and micro-USD`() =
        runBlocking {
            val f = fixture()
            val id =
                f
                    .assign(f.seated, "builder")
                    .ok()
                    .seat.id

            f.seats.reportUsage(id, 100, 500_000).ok()
            assertEquals(100L to 500_000L, usageOf(id))
            f.seats.reportUsage(id, 50, null).ok()
            assertEquals(100L to 500_000L, usageOf(id), "a lower tokens figure is kept out")
            f.seats.reportUsage(id, null, 250_000).ok()
            assertEquals(100L to 500_000L, usageOf(id), "a lower cost figure is kept out")
            f.seats.reportUsage(id, 150, 250_000).ok()
            assertEquals(150L to 500_000L, usageOf(id), "tokens rise on their own, cost does not follow")
            f.seats.reportUsage(id, 150, 750_000).ok()
            assertEquals(150L to 750_000L, usageOf(id), "cost rises on its own")
            f.seats.reportUsage(id, 0, 0).ok()
            assertEquals(150L to 750_000L, usageOf(id), "zero never lowers")
        }

    @Test
    fun `S10 usage beyond the int range is stored exactly, and usage of one seat never touches another seat's row`() =
        runBlocking {
            val f = fixture()
            val a = f.assign(f.seated, "builder").ok()
            val b = f.assign(f.seatedTwo, "builder").ok()

            f.seats.reportUsage(a.seat.id, 3_000_000_000L, 9_000_000_000L).ok()

            assertEquals(3_000_000_000L to 9_000_000_000L, usageOf(a.seat.id))
            assertEquals(0L to 0L, usageOf(b.seat.id), "control: the other seat is untouched")
        }

    @Test
    fun `S10 the liveness touch is written at once, skipped under 5 seconds old, and written from 5 seconds on`() =
        runBlocking {
            val f = fixture()
            val issued = f.assign(f.seated, "builder").ok()
            val other = f.assign(f.seatedTwo, "builder").ok()
            val otherBefore = seatRow(other.seat.id)["last_seen_at"]
            clock.advanceSeconds(10)
            val t0 = clock.now()

            f.seats.touch(issued.seat)
            assertEquals(t0, stamp(seatRow(issued.seat.id)["last_seen_at"]), "the first touch writes")
            assertEquals(23, (seatRow(issued.seat.id)["last_seen_at"] as String).length, "the canonical 23-character text")
            assertEquals(otherBefore, seatRow(other.seat.id)["last_seen_at"], "another seat's row is not touched")

            clock.advance(Duration.ofMillis(4_999))
            f.seats.touch(issued.seat)
            assertEquals(t0, stamp(seatRow(issued.seat.id)["last_seen_at"]), "4.999 s old: skipped")

            clock.advance(Duration.ofMillis(1))
            f.seats.touch(issued.seat)
            assertEquals(t0.plusSeconds(5), stamp(seatRow(issued.seat.id)["last_seen_at"]), "exactly 5 s old: written")

            clock.advanceSeconds(60)
            f.seats.touch(issued.seat)
            assertEquals(t0.plusSeconds(65), stamp(seatRow(issued.seat.id)["last_seen_at"]), "well past 5 s: written")
        }

    // ------------------------------------------------------------------ list

    @Test
    fun `list is oldest first, active only by default, the ended ones with the flag, and one item's seats with the filter`() =
        runBlocking {
            val f = fixture()
            val coordinator = f.seats.openCoordinator(f.run, ORCH).ok()
            clock.advanceSeconds(1)
            val first = f.assign(f.seated, "builder").ok()
            clock.advanceSeconds(1)
            val second = f.assign(f.seatedTwo, "builder").ok()
            clock.advanceSeconds(1)
            val third = f.assign(f.seated, "builder").ok()

            val active = f.seats.list(f.run.id, null, false)
            assertEquals(listOf(coordinator.seat.id, second.seat.id, third.seat.id), active.map { it.id }, "active only, oldest first")
            val everything = f.seats.list(f.run.id, null, true)
            assertEquals(
                listOf(coordinator.seat.id, first.seat.id, second.seat.id, third.seat.id),
                everything.map { it.id },
                "ended included"
            )
            assertEquals(listOf(third.seat.id), f.seats.list(f.run.id, f.seated.id, false).map { it.id }, "one item, active only")
            assertEquals(
                listOf(first.seat.id, third.seat.id),
                f.seats.list(f.run.id, f.seated.id, true).map { it.id },
                "one item, ended too"
            )
            assertEquals(emptyList(), f.seats.list(f.run.id, f.plain.id, true), "an item with no seat")
            assertEquals(emptyList(), f.seats.list(UUID.randomUUID(), null, true), "an unknown run")
        }

    // ------------------------------------------------------------------ S16 assign validation

    @Test
    fun `S16 the seat must be one of the item's schema seats, or implementer or planner when the schema has none`() =
        runBlocking {
            val f = fixture()
            // the seated schema: its two seats are accepted, the default pair is not
            f.assign(f.seated, "builder").ok()
            f.assign(f.seated, "checker").ok()
            for (seat in listOf("implementer", "planner", "Builder", "builder ", " builder", "build", "builders", "reviewer")) {
                val error = f.assign(f.seated, seat).err()
                assertEquals(ErrorCode.NOT_FOUND, error.code, "seat [$seat] on a seated schema")
                assertEquals("seat", error.fixArgs["kind"], "[$seat]")
                assertEquals(seat, error.fixArgs["id"], "[$seat]")
            }
            // a seat-less schema and a schema-free item: the default pair only
            for (item in listOf(f.plain, f.free)) {
                f.assign(item, "implementer").ok()
                f.assign(item, "planner").ok()
                for (seat in listOf("builder", "reviewer", "Implementer", "implementer ", "")) {
                    assertEquals(ErrorCode.NOT_FOUND, f.assign(item, seat).err().code, "seat [$seat] on ${item.title}")
                }
            }
        }

    @Test
    fun `S16 the seat name coordinator is invalid_request on the seat pointer, and never a not_found`() =
        runBlocking {
            val f = fixture()
            for (item in listOf(f.seated, f.plain, f.free)) {
                val error = f.assign(item, "coordinator").err()
                assertEquals(ErrorCode.INVALID_REQUEST, error.code, item.title)
                val fields = assertIs<ErrorDetail.InvalidRequest>(error.detail).fields
                assertTrue(fields.any { it.field.endsWith("/seat") }, "pointer: $fields")
            }
            assertEquals(
                1,
                f
                    .assign(f.plain, "implementer")
                    .ok()
                    .seat.attempt,
                "control: a normal seat on the same item is accepted"
            )
        }

    @Test
    fun `S16 an item that is not a member of the run is invalid_request on the item pointer - also an item of the same root`() =
        runBlocking {
            val f = fixture()
            val error = f.assign(f.outsider, "builder").err()
            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            val fields = assertIs<ErrorDetail.InvalidRequest>(error.detail).fields
            assertTrue(fields.any { it.field.endsWith("/itemId") && "is not a member of the run" in it.reason }, "pointer: $fields")
            assertEquals(emptyList(), query("SELECT * FROM run_seats WHERE item_id = ?", f.outsider.id), "nothing was written")
            assertEquals(
                1,
                f
                    .assign(f.seated, "builder")
                    .ok()
                    .seat.attempt,
                "control: a member of the same run is accepted"
            )
        }

    @Test
    fun `S16 a closed run refuses an assignment as run_closed and writes no row`() =
        runBlocking {
            val closed = fixture(RunStatus.CLOSED)
            assertEquals(ErrorCode.RUN_CLOSED, closed.assign(closed.seated, "builder").err().code)
            assertEquals(0, query("SELECT * FROM run_seats WHERE run_id = ?", closed.run.id).size)
        }

    @Test
    fun `S16 a paused run refuses an assignment as run_paused and writes no row`() =
        runBlocking {
            val paused = fixture(RunStatus.PAUSED)
            assertEquals(ErrorCode.RUN_PAUSED, paused.assign(paused.seated, "builder").err().code)
            assertEquals(0, query("SELECT * FROM run_seats WHERE run_id = ?", paused.run.id).size)
        }

    // ------------------------------------------------------------------ events: class and SSE projection

    @Test
    fun `the seat event types are declared and classify as activity`() {
        assertEquals("seat.assigned", DomainEvent.SEAT_ASSIGNED)
        assertEquals("seat.ended", DomainEvent.SEAT_ENDED)
        assertEquals(EventClass.ACTIVITY, EventClasses.of("seat.assigned"))
        assertEquals(EventClass.ACTIVITY, EventClasses.of("seat.ended"))
    }

    @Test
    fun `seat ended is streamed with its run id, item id and root, and the root scope applies`() =
        runBlocking {
            val f = fixture()
            val issued = f.assign(f.seated, "builder").ok()
            val mark = f.rig.maxSeq()
            f.seats.endSeat(issued.seat.id, RunSeatOutcome.DONE, "s", emptyList()).ok()
            val bus = ApiEventBus(source = f.rig.provider.eventStore())

            val projected = bus.projectedEvents(afterSeq = mark, rootIds = null).filter { it.event == "seat.ended" }

            assertEquals(1, projected.size, "exactly one seat.ended is streamed")
            val event = projected.single()
            assertEquals(f.run.id.toString(), event.runId)
            assertEquals(f.seated.id.toString(), event.itemId)
            assertEquals(f.root.id.toString(), event.rootId)
            assertEquals(
                1,
                bus.projectedEvents(afterSeq = mark, rootIds = setOf(f.root.id)).count {
                    it.event == "seat.ended"
                },
                "its own root admits it"
            )
            assertEquals(
                0,
                bus.projectedEvents(afterSeq = mark, rootIds = setOf(UUID.randomUUID())).count {
                    it.event == "seat.ended"
                },
                "any other root excludes it"
            )
        }

    // ------------------------------------------------------------------ S18 the close cascade port

    @Test
    fun `S18 the port counts the active member seats, hands out their holder pairs and ends every seat of the run with run_closed`() =
        runBlocking {
            val f = fixture()
            val coordinator = f.seats.openCoordinator(f.run, ORCH).ok()
            val builder = f.assign(f.seated, "builder").ok()
            val checker = f.assign(f.seatedTwo, "checker").ok()
            val ended = f.assign(f.plain, "implementer").ok()
            f.seats.endSeat(ended.seat.id, RunSeatOutcome.DONE, null, emptyList()).ok()

            assertEquals(2, f.seats.activeCount(f.run.id), "the two active member seats; the coordinator and the ended seat do not count")
            val holders = f.seats.assigneeHolders(f.run.id)
            // The coordinator row may or may not be listed (its pair can hold claims too); the member pairs must be.
            assertTrue(
                holders.containsAll(setOf(AssigneeHolder(ORCH, builder.seat.id), AssigneeHolder(ORCH, checker.seat.id))),
                "the pairs of the active member seats: $holders"
            )
            assertFalse(holders.contains(AssigneeHolder(ORCH, ended.seat.id)), "an ended seat is no longer an assignee: $holders")

            var inFlight: List<InFlightSeat> = emptyList()
            f.rig.inUnit("S18.endActive") { inFlight = f.seats.endActive(f.run.id, "run_closed") }

            assertEquals(
                setOf(f.seated.id to "builder", f.seatedTwo.id to "checker"),
                inFlight.map { it.itemId to it.seat }.toSet(),
                "the in-flight seats are the active member seats"
            )
            for (id in listOf(coordinator.seat.id, builder.seat.id, checker.seat.id)) {
                assertEquals("ended", seatRow(id)["status"], "$id is ended")
                assertEquals("run_closed", seatRow(id)["outcome"], "$id ended with run_closed")
            }
            assertEquals("done", seatRow(ended.seat.id)["outcome"], "control: an already ended seat keeps its own outcome")
            assertNull(f.seats.resolve(coordinator.token, ORCH), "the coordinator token dies with the run")
            assertNull(f.seats.resolve(builder.token, ORCH), "so does every seat token")
            assertEquals(0, f.seats.activeCount(f.run.id))
        }

    @Test
    fun `S18 ending the seats of one run leaves the seats of another run alone`() =
        runBlocking {
            val f = fixture()
            val mine = f.assign(f.seated, "builder").ok()
            val provider = db.db.repositoryProvider()
            val otherRun =
                ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork(clock)).run(
                    id = UUID.randomUUID(),
                    rootId = f.root.id,
                    items = listOf(f.outsider.id),
                    openedBy = ORCH,
                    openedByKind = "service",
                    coordinatorToken = null
                )
            val theirs = f.assign(f.outsider, "builder", runId = otherRun.id).ok()

            f.rig.inUnit("S18.endActive.other") { f.seats.endActive(f.run.id, "run_closed") }

            assertEquals("ended", seatRow(mine.seat.id)["status"])
            assertEquals("active", seatRow(theirs.seat.id)["status"], "control: the other run's seat is untouched")
            assertEquals(theirs.seat.id, f.seats.resolve(theirs.token, ORCH)?.id)
        }
}

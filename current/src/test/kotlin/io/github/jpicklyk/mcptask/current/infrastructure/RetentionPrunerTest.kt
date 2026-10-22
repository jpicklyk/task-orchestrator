package io.github.jpicklyk.mcptask.current.infrastructure

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.application.port.CallLogFoldWatermark
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.RemovedRowsUnitOfWork
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0da29ea1) for [RetentionPruner]: scenarios S1-S9, S12 and S13 of the frozen
 * test-plan, through the production SQLite database and production unit of work. (S10 is RetentionSettingsTest, S11 is
 * EventClassCoverageTest, the store-level halves are the *PruneTest classes beside the stores.)
 *
 * Oracles (frozen task-scope, test-plan and decisions D1-D8; none read from an implementation): cutoff = unit `now` minus
 * `days * 24h`, strict `<` everywhere; activity events follow `eventsDays`, audit events follow `auditEventsDays` and are kept
 * when it is 0 ("0 = forever"), an event type the class map does not know is never pruned; `protectFrom = maxSeq - sseBufferSize`
 * and rows with `seq >= protectFrom` survive; the latest `claim.expired` of each item survives however old; only lease
 * intervals with `released_at IS NOT NULL` are pruned; `call_log` rows are pruned only below `min(now - days, foldedThrough)`
 * and not at all while the watermark is null; at most `batchSize` rows per write unit and `passCap` rows per pass shared
 * across the three tables; one INFO line per pass; a prune writes no event; a failing unit only WARNs and the loop goes on.
 *
 * Fixtures are raw SQL rows at explicit seqs above [EventStore.SEQ_FLOOR] (survivors are reported as offsets from the
 * floor; sub-floor rows keep their raw seq) with a unit clock fixed at [now], so every boundary is exact to the millisecond.
 * Where a scenario removes protection (sseBufferSize = 0) the NEWEST row survives by design, so most fixtures end with an
 * unprunable recent "anchor" row. Every expected value is an input this test chose.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RetentionPrunerTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val now: Instant = Instant.parse("2026-06-01T12:00:00.000Z")
    private val floor = EventStore.SEQ_FLOOR
    private val jdbc get() = sqlite.jdbcUrl

    private val upserted = DomainEvent.NOTE_UPSERTED
    private val transitioned = DomainEvent.ITEM_TRANSITIONED
    private val claimExpired = DomainEvent.CLAIM_EXPIRED

    private fun uow(): UnitOfWork = sqlite.unitOfWork(Clock { now })

    private fun days(n: Long): Duration = Duration.ofDays(n)

    private fun ago(
        n: Long,
        plusMillis: Long = 0
    ): Instant = now.minus(days(n)).plusMillis(plusMillis)

    private fun settings(
        callLog: Int = 0,
        events: Int = 0,
        audit: Int = 0,
        lease: Int = 0
    ) = RetentionSettings(callLogDays = callLog, eventsDays = events, auditEventsDays = audit, leaseHistoryDays = lease)

    private fun pruner(
        settings: RetentionSettings,
        sse: Int = 0,
        batch: Int = 5_000,
        cap: Int = 200_000,
        watermark: CallLogFoldWatermark = CallLogFoldWatermark.NONE,
        unit: UnitOfWork = uow()
    ) = RetentionPruner(
        unitOfWork = unit,
        settings = settings,
        sseBufferSize = sse,
        interval = Duration.ofHours(1),
        batchSize = batch,
        passCap = cap,
        foldWatermark = watermark
    )

    /** A raw event row at [floor] + [offset]. */
    private fun ev(
        offset: Long,
        type: String,
        at: Instant,
        entity: UUID = UUID.randomUUID()
    ) = RetentionRows.insertEvent(jdbc, floor + offset, type, at, entity)

    /** The newest, recent, unprunable row: with sseBufferSize = 0 it is the one row the seq floor always keeps. */
    private fun anchor(offset: Long) = ev(offset, upserted, now.minusSeconds(3600))

    /** Surviving event seqs as offsets from the floor (sub-floor rows keep their raw seq). */
    private fun left(): List<Long> = RetentionRows.eventSeqs(jdbc).map { if (it >= floor) it - floor else it }

    private fun left(type: String): List<Long> = RetentionRows.eventSeqs(jdbc, type).map { if (it >= floor) it - floor else it }

    private fun watermark(through: Instant?) =
        object : CallLogFoldWatermark {
            override suspend fun foldedThrough(): Instant? = through
        }

    private suspend fun call(
        n: Int,
        at: Instant
    ) {
        sqlite.repositoryProvider().callLogStore().append(listOf(sampleCallLogRecord(testReqId(n), at = at)))
    }

    private fun callIds(vararg ns: Int) = ns.map { testReqId(it) }

    // ------------------------------------------------------------------ S1 activity events by eventsDays

    @Test
    fun `S1 an activity event strictly older than now minus eventsDays is deleted, the cutoff row and newer survive`(): Unit =
        runBlocking {
            val cutoff = ago(180)
            ev(1, upserted, cutoff.minusMillis(1))
            ev(2, upserted, cutoff)
            ev(3, upserted, cutoff.plusMillis(1))
            anchor(4)

            val pass = pruner(settings(events = 180)).pruneOnce()

            assertEquals(1, pass.events)
            assertEquals(0, pass.callLog)
            assertEquals(0, pass.leaseHistory)
            assertEquals(1, pass.total)
            assertEquals(false, pass.capped)
            assertEquals(listOf(2L, 3L, 4L), left())
        }

    @Test
    fun `S1 every activity type is pruned, not only one`(): Unit =
        runBlocking {
            val types =
                listOf(
                    DomainEvent.NOTE_UPSERTED,
                    DomainEvent.NOTE_DELETED,
                    DomainEvent.ITEM_UPDATED,
                    DomainEvent.DEPENDENCY_ADDED,
                    DomainEvent.CLAIM_ACQUIRED,
                    DomainEvent.LEASE_ACQUIRED,
                    DomainEvent.TRANSITION_REJECTED
                )
            types.forEachIndexed { i, t -> ev(i + 1L, t, ago(400)) }
            anchor(types.size + 1L)

            val pass = pruner(settings(events = 180)).pruneOnce()

            assertEquals(types.size, pass.events)
            assertEquals(listOf(types.size + 1L), left())
        }

    @Test
    fun `S1 probe - a second pass over the same data and a pass over empty tables both answer zero`(): Unit =
        runBlocking {
            val empty = pruner(settings(callLog = 1, events = 1, audit = 1, lease = 1)).pruneOnce()
            assertEquals(0, empty.total)
            assertEquals(false, empty.capped)

            ev(1, upserted, ago(400))
            anchor(2)
            val p = pruner(settings(events = 180))
            assertEquals(1, p.pruneOnce().events)
            val second = p.pruneOnce()
            assertEquals(0, second.total)
            assertEquals(listOf(2L), left())
        }

    // ------------------------------------------------------------------ S13 eventsDays = 0

    @Test
    fun `S13 eventsDays of zero keeps ancient activity events forever`(): Unit =
        runBlocking {
            ev(1, upserted, ago(5000))
            ev(2, DomainEvent.NOTE_DELETED, ago(5000))
            anchor(3)

            val pass = pruner(settings(events = 0, audit = 0)).pruneOnce()

            assertEquals(0, pass.events)
            assertEquals(listOf(1L, 2L, 3L), left())
        }

    @Test
    fun `S13 eventsDays of zero does not stop a finite auditEventsDays from pruning audit rows`(): Unit =
        runBlocking {
            ev(1, upserted, ago(5000))
            ev(2, transitioned, ago(5000))
            anchor(3)

            val pass = pruner(settings(events = 0, audit = 1)).pruneOnce()

            assertEquals(1, pass.events)
            assertEquals(listOf(1L, 3L), left(), "audit row 2 is gone, the forever activity row 1 stays")
        }

    @Test
    fun `S1 probe - an enormous window deletes nothing and does not throw`(): Unit =
        runBlocking {
            ev(1, upserted, ago(5000))
            ev(2, transitioned, ago(5000))
            anchor(3)

            val pass =
                pruner(
                    settings(callLog = Int.MAX_VALUE, events = Int.MAX_VALUE, audit = Int.MAX_VALUE, lease = Int.MAX_VALUE)
                ).pruneOnce()

            assertEquals(0, pass.total)
            assertEquals(listOf(1L, 2L, 3L), left())
        }

    // ------------------------------------------------------------------ S2 / S3 audit

    @Test
    fun `S2 audit events survive by default however old, including rows below the seq floor, while old activity is pruned`(): Unit =
        runBlocking {
            val auditTypes =
                listOf(
                    DomainEvent.ITEM_TRANSITIONED,
                    DomainEvent.ITEM_CREATED,
                    DomainEvent.ITEM_DELETED,
                    DomainEvent.ITEM_REPARENTED,
                    DomainEvent.ITEM_PINNED,
                    DomainEvent.CONFIG_VERSION_CREATED,
                    DomainEvent.DOCUMENT_CREATED,
                    DomainEvent.DOCUMENT_UPDATED,
                    DomainEvent.DOCUMENT_ARCHIVED,
                    DomainEvent.DOCUMENT_DELETED,
                    DomainEvent.DOCUMENT_ADOPTED,
                    "note.ownership_transferred",
                    "run.started",
                    "decision.recorded"
                )
            auditTypes.forEachIndexed { i, t -> ev(i + 1L, t, ago(4000)) }
            // backfilled rows sit below the floor (V29): raw seqs 100 and 101 stay audit
            RetentionRows.insertEvent(jdbc, 100, transitioned, ago(4000))
            RetentionRows.insertEvent(jdbc, 101, DomainEvent.ITEM_CREATED, ago(4000))
            // controls: ancient ACTIVITY rows, one above and one below the floor, are deletable in this same fixture
            ev(20, upserted, ago(4000))
            RetentionRows.insertEvent(jdbc, 102, upserted, ago(4000))
            anchor(21)

            val pass = pruner(settings(events = 1, audit = 0)).pruneOnce()

            assertEquals(2, pass.events, "exactly the two ancient activity controls are deleted")
            val expected = (1L..auditTypes.size.toLong()).toList() + listOf(100L, 101L, 21L)
            assertEquals(expected.sorted(), left().sorted())
        }

    @Test
    fun `S2 an event type the class map does not know is never pruned`(): Unit =
        runBlocking {
            ev(1, "zzz.unknown", ago(4000))
            ev(2, upserted, ago(4000))
            anchor(3)

            val pass = pruner(settings(events = 1, audit = 0)).pruneOnce()

            assertEquals(1, pass.events)
            assertEquals(listOf(1L, 3L), left())
        }

    @Test
    fun `S3 a finite auditEventsDays deletes audit rows past its own cutoff, independently of eventsDays`(): Unit =
        runBlocking {
            val auditCutoff = ago(1)
            ev(1, transitioned, auditCutoff.minusMillis(1)) // deleted: audit older than 1 day
            ev(2, transitioned, auditCutoff) // kept: exactly at the audit cutoff
            ev(3, DomainEvent.CONFIG_VERSION_CREATED, ago(400)) // deleted: audit, ancient
            ev(4, upserted, ago(2)) // kept: activity, 2 days old, eventsDays = 180
            ev(5, upserted, ago(181)) // deleted: activity past 180 days
            anchor(6)

            val pass = pruner(settings(events = 180, audit = 1)).pruneOnce()

            assertEquals(3, pass.events)
            assertEquals(listOf(2L, 4L, 6L), left())
        }

    // ------------------------------------------------------------------ S4 SSE floor

    @Test
    fun `S4 rows within sseBufferSize of the newest seq survive, older ancient rows go, and maxSeq is unchanged`(): Unit =
        runBlocking {
            for (i in 1L..10L) ev(i, upserted, ago(400))
            val maxBefore = sqlite.repositoryProvider().eventStore().maxSeq()
            assertEquals(floor + 10, maxBefore)

            val pass = pruner(settings(events = 180), sse = 3).pruneOnce()

            assertEquals(6, pass.events)
            assertEquals(listOf(7L, 8L, 9L, 10L), left(), "seq >= maxSeq - 3 survive, the row AT the floor included")
            assertEquals(maxBefore, sqlite.repositoryProvider().eventStore().maxSeq())
        }

    @Test
    fun `S4 with sseBufferSize zero only the newest row survives`(): Unit =
        runBlocking {
            for (i in 1L..10L) ev(i, upserted, ago(400))

            val pass = pruner(settings(events = 180), sse = 0).pruneOnce()

            assertEquals(9, pass.events)
            assertEquals(listOf(10L), left())
        }

    @Test
    fun `S4 probe - a sseBufferSize larger than the row count protects every row`(): Unit =
        runBlocking {
            for (i in 1L..10L) ev(i, upserted, ago(400))

            val pass = pruner(settings(events = 180), sse = 100).pruneOnce()

            assertEquals(0, pass.events)
            assertEquals((1L..10L).toList(), left())
        }

    @Test
    fun `S4 a sseBufferSize of one keeps the newest two rows`(): Unit =
        runBlocking {
            for (i in 1L..4L) ev(i, upserted, ago(400))
            ev(5, upserted, ago(400))

            val pass = pruner(settings(events = 180), sse = 1).pruneOnce()

            assertEquals(3, pass.events)
            assertEquals(listOf(4L, 5L), left())
        }

    // ------------------------------------------------------------------ S5 claim.expired

    @Test
    fun `S5 the latest claim expired of each item survives however old, older ones and other claim events go`(): Unit =
        runBlocking {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val c = UUID.randomUUID()
            // occurred_at rises with seq inside each item, so "latest" is unambiguous
            ev(1, claimExpired, ago(399), a)
            ev(2, claimExpired, ago(398), b)
            ev(3, claimExpired, ago(397), a)
            ev(4, claimExpired, ago(396), b)
            ev(5, claimExpired, ago(395), a) // latest of A
            ev(6, claimExpired, ago(394), b) // latest of B
            ev(7, claimExpired, ago(393), c) // older than the next row of C, so it goes
            ev(8, claimExpired, now.minusSeconds(3600), c) // latest of C, also inside the retention window
            ev(9, DomainEvent.CLAIM_ACQUIRED, ago(392), a) // control: another claim.* type is plain activity
            anchor(10)

            val pass = pruner(settings(events = 180), sse = 0).pruneOnce()

            assertEquals(6, pass.events)
            assertEquals(listOf(5L, 6L, 8L, 10L), left())
            assertEquals(listOf(5L, 6L, 8L), left(claimExpired))
        }

    @Test
    fun `S5 probe - a lone ancient claim expired is kept and stays kept across passes`(): Unit =
        runBlocking {
            ev(1, claimExpired, ago(2000))
            anchor(2)
            val p = pruner(settings(events = 1), sse = 0)

            assertEquals(0, p.pruneOnce().events)
            assertEquals(0, p.pruneOnce().events)
            assertEquals(listOf(1L, 2L), left())
        }

    @Test
    fun `S5 probe - the guard is per item, interleaved items do not shield each other`(): Unit =
        runBlocking {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            // A's only claim.expired is the highest seq overall; B's rows are all lower
            ev(1, claimExpired, ago(300), b)
            ev(2, claimExpired, ago(299), b)
            ev(3, claimExpired, ago(298), b)
            ev(4, claimExpired, ago(297), a)
            anchor(5)

            pruner(settings(events = 180), sse = 0).pruneOnce()

            assertEquals(listOf(3L, 4L, 5L), left())
        }

    // ------------------------------------------------------------------ S6 lease history

    @Test
    fun `S6 closed lease intervals strictly older than the cutoff are deleted, boundary and newer and every open interval survive`(): Unit =
        runBlocking {
            val cutoff = ago(365)
            val j = jdbc
            RetentionRows.insertLeaseInterval(j, "k-before", ago(900), cutoff.minusMillis(1))
            RetentionRows.insertLeaseInterval(j, "k-at", ago(900), cutoff)
            RetentionRows.insertLeaseInterval(j, "k-after", ago(900), cutoff.plusMillis(1))
            RetentionRows.insertLeaseInterval(j, "k-open-ancient", ago(4000), null)
            RetentionRows.insertLeaseInterval(j, "k-open-recent", ago(1), null)

            val pass = pruner(settings(lease = 365)).pruneOnce()

            assertEquals(1, pass.leaseHistory)
            assertEquals(1, pass.total)
            assertEquals(listOf("k-after", "k-at", "k-open-ancient", "k-open-recent"), RetentionRows.leaseKeys(j))
        }

    @Test
    fun `S6 leaseHistoryDays of zero keeps closed intervals forever`(): Unit =
        runBlocking {
            RetentionRows.insertLeaseInterval(jdbc, "k-old", ago(4000), ago(3999))

            val pass = pruner(settings(lease = 0)).pruneOnce()

            assertEquals(0, pass.leaseHistory)
            assertEquals(listOf("k-old"), RetentionRows.leaseKeys(jdbc))
        }

    // ------------------------------------------------------------------ S7 call_log + fold watermark

    @Test
    fun `S7 with the NONE watermark no call_log row is deleted however old`(): Unit =
        runBlocking {
            call(1, ago(4000))
            call(2, ago(200))
            call(3, now)

            val pass = pruner(settings(callLog = 90), watermark = CallLogFoldWatermark.NONE).pruneOnce()

            assertEquals(0, pass.callLog)
            assertEquals(callIds(1, 2, 3), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 the NONE watermark reports nothing folded`(): Unit =
        runBlocking {
            assertNull(CallLogFoldWatermark.NONE.foldedThrough())
        }

    @Test
    fun `S7 the default foldWatermark of a pruner is NONE, nothing is deleted`(): Unit =
        runBlocking {
            call(1, ago(4000))
            val p =
                RetentionPruner(
                    unitOfWork = uow(),
                    settings = settings(callLog = 90),
                    sseBufferSize = 0
                )

            assertEquals(0, p.pruneOnce().callLog)
            assertEquals(callIds(1), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 a watermark older than the cutoff limits the delete to rows strictly below the watermark`(): Unit =
        runBlocking {
            val cutoff = ago(90)
            call(1, cutoff.minusSeconds(10L * 86_400)) // below the watermark: deleted
            call(2, cutoff.minusSeconds(5L * 86_400)) // past the watermark, still older than the cutoff: kept (not folded)
            call(3, cutoff.minusMillis(1)) // kept
            call(4, cutoff) // kept
            call(5, cutoff.plusSeconds(86_400)) // kept
            val w = cutoff.minusSeconds(7L * 86_400)

            val pass = pruner(settings(callLog = 90), watermark = watermark(w)).pruneOnce()

            assertEquals(1, pass.callLog)
            assertEquals(callIds(2, 3, 4, 5), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 a watermark newer than the cutoff does not extend the delete past the cutoff`(): Unit =
        runBlocking {
            val cutoff = ago(90)
            call(1, cutoff.minusSeconds(10L * 86_400))
            call(2, cutoff.minusMillis(1))
            call(3, cutoff)
            call(4, cutoff.plusSeconds(86_400))
            val w = cutoff.plusSeconds(30L * 86_400)

            val pass = pruner(settings(callLog = 90), watermark = watermark(w)).pruneOnce()

            assertEquals(2, pass.callLog)
            assertEquals(callIds(3, 4), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 a row exactly at the watermark is kept`(): Unit =
        runBlocking {
            val cutoff = ago(90)
            val w = cutoff.minusSeconds(5L * 86_400)
            call(1, w.minusMillis(1))
            call(2, w)

            val pass = pruner(settings(callLog = 90), watermark = watermark(w)).pruneOnce()

            assertEquals(1, pass.callLog)
            assertEquals(callIds(2), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 a watermark that answers null is the same as NONE`(): Unit =
        runBlocking {
            call(1, ago(4000))

            val pass = pruner(settings(callLog = 90), watermark = watermark(null)).pruneOnce()

            assertEquals(0, pass.callLog)
            assertEquals(callIds(1), RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S7 callLogDays of zero keeps call_log forever even with a fully advanced watermark`(): Unit =
        runBlocking {
            call(1, ago(4000))

            val pass = pruner(settings(callLog = 0), watermark = watermark(now)).pruneOnce()

            assertEquals(0, pass.callLog)
            assertEquals(callIds(1), RetentionRows.callLogIds(jdbc))
        }

    // ------------------------------------------------------------------ S8 batching and the pass cap

    @Test
    fun `S8 twelve prunable rows with batchSize five take three deleting write units of 5, 5 and 2`(): Unit =
        runBlocking {
            for (i in 1L..12L) ev(i, upserted, ago(400))
            anchor(13)
            val counting = RemovedRowsUnitOfWork(uow(), jdbc)

            val pass = pruner(settings(events = 180), batch = 5, cap = 100, unit = counting).pruneOnce()

            assertEquals(12, pass.events)
            assertEquals(false, pass.capped)
            assertEquals(listOf(5L, 5L, 2L), counting.deletingUnits)
            assertEquals(listOf(13L), left())
        }

    @Test
    fun `S8 no write unit ever removes more than batchSize rows, across several types`(): Unit =
        runBlocking {
            var seq = 0L
            for (type in listOf(upserted, DomainEvent.NOTE_DELETED, DomainEvent.DEPENDENCY_ADDED)) {
                repeat(8) { ev(++seq, type, ago(400)) }
            }
            anchor(++seq)
            val counting = RemovedRowsUnitOfWork(uow(), jdbc)

            val pass = pruner(settings(events = 180), batch = 5, cap = 1_000, unit = counting).pruneOnce()

            assertEquals(24, pass.events)
            assertTrue(counting.deletingUnits.all { it <= 5 }, "a unit removed more than batchSize rows: ${counting.deletingUnits}")
            assertEquals(24L, counting.deletingUnits.sum())
            assertEquals(listOf(25L), left())
        }

    @Test
    fun `S8 a passCap of seven stops the pass at seven rows and reports capped`(): Unit =
        runBlocking {
            for (i in 1L..12L) ev(i, upserted, ago(400))
            anchor(13)
            val counting = RemovedRowsUnitOfWork(uow(), jdbc)

            val pass = pruner(settings(events = 180), batch = 5, cap = 7, unit = counting).pruneOnce()

            assertEquals(7, pass.events)
            assertEquals(7, pass.total)
            assertEquals(true, pass.capped)
            assertEquals(7L, counting.deletingUnits.sum())
            assertTrue(counting.deletingUnits.all { it <= 5 }, "${counting.deletingUnits}")
            assertEquals(6, left().size, "five prunable rows plus the anchor survive")
        }

    @Test
    fun `S8 the pass cap is shared across events and lease history`(): Unit =
        runBlocking {
            for (i in 1L..6L) ev(i, upserted, ago(400))
            anchor(7)
            for (i in 1..6) RetentionRows.insertLeaseInterval(jdbc, "k-%02d".format(i), ago(900), ago(800))

            val pass = pruner(settings(events = 180, lease = 365), batch = 5, cap = 8).pruneOnce()

            assertEquals(8, pass.total, "events ${pass.events} + lease ${pass.leaseHistory}")
            assertEquals(true, pass.capped)
            assertEquals(
                13 - 8,
                RetentionRows.eventCount(jdbc) + RetentionRows.leaseCount(jdbc),
                "13 rows (6 + anchor + 6) minus the 8 the cap allows"
            )
        }

    @Test
    fun `S8 the defaults are 5000 rows per unit`(): Unit =
        runBlocking {
            RetentionRows.insertEventsBatch(jdbc, floor + 1, 5_001, upserted, ago(400))
            anchor(5_002)
            val counting = RemovedRowsUnitOfWork(uow(), jdbc)

            val pass = pruner(settings(events = 180), unit = counting).pruneOnce()

            assertEquals(5_001, pass.events)
            assertEquals(listOf(5_000L, 1L), counting.deletingUnits)
        }

    @Test
    fun `S8 the default pass cap is 200000 rows`(): Unit =
        runBlocking {
            RetentionRows.insertEventsBatch(jdbc, floor + 1, 200_001, upserted, ago(400))
            anchor(200_002)

            val first = pruner(settings(events = 180)).pruneOnce()

            assertEquals(200_000, first.events)
            assertEquals(true, first.capped)
            assertEquals(2, RetentionRows.eventCount(jdbc), "one prunable row plus the anchor survive the capped pass")

            val second = pruner(settings(events = 180)).pruneOnce()
            assertEquals(1, second.events)
            assertEquals(false, second.capped)
        }

    // ------------------------------------------------------------------ S9 writes no events

    @Test
    fun `S9 a pass adds no event row and stamps nothing at the pass time`(): Unit =
        runBlocking {
            for (i in 1L..6L) ev(i, upserted, ago(400))
            anchor(7)
            val before = RetentionRows.eventSeqs(jdbc)

            val pass = pruner(settings(events = 180), sse = 0).pruneOnce()

            val after = RetentionRows.eventSeqs(jdbc)
            assertEquals(6, pass.events)
            assertEquals(before.size - pass.events, after.size)
            assertTrue(before.containsAll(after), "a seq appeared that was not there before: ${after - before.toSet()}")
            assertEquals(
                0L,
                RetentionRows.scalar(jdbc, "SELECT count(*) FROM events WHERE occurred_at >= '${RetentionRows.stamp(now)}'"),
                "no row may be stamped at or after the unit now"
            )
        }

    // ------------------------------------------------------------------ INFO line

    @Test
    fun `S8 exactly one INFO line is logged per pass, including a pass that deletes nothing`(): Unit =
        runBlocking {
            ev(1, upserted, ago(400))
            anchor(2)
            val capture = LogCapture().attach()
            try {
                val p = pruner(settings(events = 180), sse = 0)
                p.pruneOnce()
                val afterFirst = capture.events.count { it.level == Level.INFO && it.logger.endsWith("RetentionPruner") }
                p.pruneOnce()
                val afterSecond = capture.events.count { it.level == Level.INFO && it.logger.endsWith("RetentionPruner") }

                assertEquals(1, afterFirst, "one INFO line for the deleting pass: ${capture.events.map { it.logger to it.message }}")
                assertEquals(2, afterSecond, "one more INFO line for the empty pass")
            } finally {
                capture.detach()
            }
        }

    // ------------------------------------------------------------------ S12 lifecycle

    private suspend fun waitUntil(
        timeoutMs: Long = 15_000,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    @Test
    fun `S12 start prunes at once and then on the interval, and nothing runs after stop`(): Unit =
        runBlocking {
            val real = sqlite.unitOfWork(Clock { Instant.now() })
            val ancient = Instant.now().minus(days(400))
            ev(1, upserted, ancient)
            anchor(4)
            val p =
                RetentionPruner(
                    unitOfWork = real,
                    settings = settings(events = 180),
                    sseBufferSize = 0,
                    interval = Duration.ofMillis(100)
                )

            p.start()
            try {
                assertTrue(waitUntil { 1L !in left() }, "the startup pass must delete the ancient row")

                ev(3, upserted, ancient) // appears AFTER startup, below the anchor
                assertTrue(waitUntil { 3L !in left() }, "a periodic pass must delete a row added after startup")
            } finally {
                p.stop()
            }

            // control: the very same kind of row at the very same seq was pruned while the loop ran
            ev(3, upserted, ancient)
            delay(1_000)
            assertTrue(3L in left(), "nothing may run after stop()")
        }

    /** Delegates to the real unit of work but fails every write, counting the attempts. */
    private class FailingWrites(
        private val real: UnitOfWork,
        val attempts: AtomicInteger
    ) : UnitOfWork by real {
        override suspend fun <T> write(
            op: String,
            block: suspend WriteScope.() -> Outcome<T>
        ): Outcome<T> {
            attempts.incrementAndGet()
            error("simulated store fault")
        }
    }

    @Test
    fun `S12 a failing unit does not throw out of start, only WARNs, the loop keeps trying and stop completes`(): Unit =
        runBlocking {
            val attempts = AtomicInteger(0)
            val p =
                RetentionPruner(
                    unitOfWork = FailingWrites(sqlite.unitOfWork(Clock { Instant.now() }), attempts),
                    settings = settings(events = 180),
                    sseBufferSize = 0,
                    interval = Duration.ofMillis(50)
                )
            val capture = LogCapture().attach()
            try {
                p.start() // must not throw although the first pass fails
                try {
                    assertTrue(waitUntil { attempts.get() >= 3 }, "the loop must survive failures and attempt again: ${attempts.get()}")
                } finally {
                    p.stop()
                }
                val afterStop = attempts.get()
                delay(500)
                assertEquals(afterStop, attempts.get(), "no attempt may run after stop()")

                val own = capture.events.filter { it.logger.endsWith("RetentionPruner") }
                assertTrue(own.any { it.level == Level.WARN }, "a failed pass must WARN: ${own.map { it.level to it.message }}")
                assertTrue(own.none { it.level == Level.ERROR }, "a failed pass is not an ERROR: ${own.map { it.level to it.message }}")
            } finally {
                capture.detach()
            }
        }
}

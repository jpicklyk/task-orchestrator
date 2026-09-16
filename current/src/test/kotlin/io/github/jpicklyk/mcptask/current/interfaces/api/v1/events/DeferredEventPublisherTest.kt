package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Independent test authorship for item 0e9d5675 (needs-test-author).
 *
 * Oracles (frozen in test-plan note 46ed4b0a, before this file was written):
 *  O1 current/docs/api-rest.md:1462-1475,:1493 — monotonic id namespace; Last-Event-ID ring-buffer
 *     replay; a data event denotes a persisted change.
 *  O2 this item's diagnosis note, "Corrections" 3-4 — the four rollback-capable enclosing sites,
 *     and the build-at-flush id rule (a deferred event must not be built — and therefore must not
 *     be id-stamped — before the transaction that encloses it commits).
 *  O3 WorkItemRepository.kt:100-111 KDoc: "if [block] throws, all writes are rolled back
 *     atomically" (supplied verbatim in this item's DECLARATIONS block).
 *
 * These tests exercise [DeferredEventPublisher] directly against a real Exposed/H2 transaction
 * obtained via [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.inTransaction]
 * (the transaction seam named in the DECLARATIONS block) — no [EventPublishingRepositoryProvider]
 * involved here; the decorator's own routing is covered separately by
 * [EventPublishingTransactionRollbackTest].
 *
 * Every scenario is NEW-SURFACE per the test-plan's labelling (the fix introduces both
 * [PendingApiEvent] and [DeferredEventPublisher]); a plain revert of the fix removes the
 * declarations these tests bind to and would not compile, so red-first here means the
 * "concurrent immediate publish" recipe in `deferred events receive ids allocated at flush time...`
 * — a build-at-enqueue-time bug (the narrowest regression this fix could reintroduce) turns that
 * one test red without touching any other test's compilation.
 */
class DeferredEventPublisherTest {
    // -------------------------------------------------------------------------
    // S1 — outside a transaction, publish is synchronous
    // -------------------------------------------------------------------------

    @Test
    fun `S1 - publishOnCommit outside a transaction publishes synchronously`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)
            val itemId = UUID.randomUUID()

            publisher.publishOnCommit(
                PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = itemId),
            )

            val snapshot = bus.ringBufferSnapshot()
            assertEquals(1, snapshot.size, "with no ambient transaction the event must be visible immediately")
            assertEquals(ApiEventType.ITEM_CREATED, snapshot[0].event)
            assertEquals(itemId.toString(), snapshot[0].itemId)
        }

    // -------------------------------------------------------------------------
    // S2 — inside a committing transaction, buffered then flushed FIFO
    // -------------------------------------------------------------------------

    @Test
    fun `S2 - publishOnCommit inside a committing transaction defers until commit then flushes FIFO`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val id3 = UUID.randomUUID()

            delegate.workItemRepository().inTransaction {
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = id1))
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_UPDATED, itemId = id2))
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_DELETED, itemId = id3))

                // Nothing built or published while the transaction is still open (O2/O3).
                assertTrue(bus.ringBufferSnapshot().isEmpty(), "no event must be visible before commit")
            }

            val snapshot = bus.ringBufferSnapshot()
            assertEquals(3, snapshot.size, "all three deferred events must flush on commit")
            assertEquals(
                listOf(ApiEventType.ITEM_CREATED, ApiEventType.ITEM_UPDATED, ApiEventType.ITEM_DELETED),
                snapshot.map { it.event },
                "flush order must match enqueue order (FIFO)",
            )
            assertEquals(listOf(id1.toString(), id2.toString(), id3.toString()), snapshot.map { it.itemId })
            assertTrue(
                snapshot[0].id < snapshot[1].id && snapshot[1].id < snapshot[2].id,
                "ids must be strictly increasing in FIFO/commit order — got ids ${snapshot.map { it.id }}",
            )
        }

    // -------------------------------------------------------------------------
    // S3 — inside a rolling-back transaction, buffer is dropped entirely
    // -------------------------------------------------------------------------

    @Test
    fun `S3 - publishOnCommit inside a rolling-back transaction discards the buffer entirely`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)

            var caught: Throwable? = null
            try {
                delegate.workItemRepository().inTransaction {
                    publisher.publishOnCommit(
                        PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()),
                    )
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the transaction failure to propagate, got: $caught")

            assertTrue(
                bus.ringBufferSnapshot().isEmpty(),
                "rollback must drop the buffer with zero ring-buffer entries (O1/O3)",
            )

            // A later Last-Event-ID replay must not surface anything either — per the
            // codebase's own established probe convention (SyncLostReplayTest.kt: lastEventId=0L
            // against a bus that has never published anything yields no sentinel and no events).
            val replayed =
                withTimeoutOrNull(200) {
                    bus.subscribe("sub-replay-0e9d5675", emptySet(), lastEventId = 0L).take(1).toList()
                }
            assertTrue(replayed.isNullOrEmpty(), "nothing should be replayable for a rolled-back publish, got: $replayed")
            bus.unsubscribe("sub-replay-0e9d5675")
        }

    // -------------------------------------------------------------------------
    // S6 — ids are allocated at flush time, never at enqueue time
    // -------------------------------------------------------------------------

    @Test
    fun `S6 - deferred events receive ids allocated at flush time, after any concurrent immediate publish`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)
            val idE1 = UUID.randomUUID()
            val idE2 = UUID.randomUUID()

            delegate.workItemRepository().inTransaction {
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = idE1))
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_UPDATED, itemId = idE2))

                // A concurrent, non-transactional publish lands on the bus WHILE the deferred
                // pair is still buffered (open transaction). If publishOnCommit built (and
                // therefore id-stamped) its event at enqueue time rather than at flush time,
                // E1/E2 would already hold lower ids than this one — this is the narrowest
                // revert that would turn this test red (O1/diagnosis §4).
                bus.publish(bus.buildEvent(ApiEventType.NOTE_UPSERTED, itemId = UUID.randomUUID()))
            }

            val snapshot = bus.ringBufferSnapshot()
            assertEquals(3, snapshot.size)
            val e0 = snapshot.first { it.event == ApiEventType.NOTE_UPSERTED }
            val e1 = snapshot.first { it.itemId == idE1.toString() }
            val e2 = snapshot.first { it.itemId == idE2.toString() }
            assertTrue(
                e0.id < e1.id,
                "the immediate publish made while the transaction was open must get a LOWER id " +
                    "than the deferred events (e0.id=${e0.id}, e1.id=${e1.id})",
            )
            assertTrue(e1.id < e2.id, "deferred events keep FIFO id order among themselves")
        }

    // -------------------------------------------------------------------------
    // S7 — nested inTransaction shares one buffer, flushed once at the outermost boundary
    // -------------------------------------------------------------------------

    @Test
    fun `S7a - nested inTransaction shares one buffer and flushes once on the outermost commit`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)
            val id = UUID.randomUUID()

            delegate.workItemRepository().inTransaction {
                delegate.workItemRepository().inTransaction {
                    publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = id))
                }
                // Still open at the outer level: the inner block's completion alone must not flush.
                assertTrue(
                    bus.ringBufferSnapshot().isEmpty(),
                    "inner completion must not flush; only the outermost commit does",
                )
            }

            val snapshot = bus.ringBufferSnapshot()
            assertEquals(1, snapshot.size, "nested transactions share one buffer, flushed exactly once")
            assertEquals(id.toString(), snapshot[0].itemId)
        }

    @Test
    fun `S7b - nested inTransaction rollback at the outer level publishes nothing`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)

            var caught: Throwable? = null
            try {
                delegate.workItemRepository().inTransaction {
                    delegate.workItemRepository().inTransaction {
                        publisher.publishOnCommit(
                            PendingApiEvent(eventType = ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()),
                        )
                    }
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the outer rollback to propagate, got: $caught")
            assertTrue(bus.ringBufferSnapshot().isEmpty(), "outer rollback must drop the shared buffer")
        }

    // -------------------------------------------------------------------------
    // Edge probes — boundary (empty buffer) and empty-vs-absent-vs-null (minimal descriptor)
    // -------------------------------------------------------------------------

    @Test
    fun `edge - a transaction that enqueues nothing commits as a no-op`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            // DeferredEventPublisher is constructed but never used inside the transaction below —
            // this pins the declared "empty buffer commits as a no-op" behavior.
            DeferredEventPublisher(bus)

            delegate.workItemRepository().inTransaction {
                // intentionally empty
            }

            assertTrue(bus.ringBufferSnapshot().isEmpty(), "an empty deferred buffer must not publish anything on commit")
        }

    @Test
    fun `edge - a minimal PendingApiEvent with all-default optional fields flushes with those fields null-absent`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val publisher = DeferredEventPublisher(bus)

            delegate.workItemRepository().inTransaction {
                // eventType is the only required field; itemId/modifiedAt/newRole default to null
                // and affectedRoots defaults to emptySet() — distinct from explicitly passing them.
                publisher.publishOnCommit(PendingApiEvent(eventType = ApiEventType.AUTH_EXPIRED))
            }

            val snapshot = bus.ringBufferSnapshot()
            assertEquals(1, snapshot.size)
            assertEquals(ApiEventType.AUTH_EXPIRED, snapshot[0].event)
            assertEquals(null, snapshot[0].itemId, "absent itemId must flush through as null, not a placeholder")
            assertEquals(null, snapshot[0].modifiedAt)
            assertEquals(null, snapshot[0].newRole)
        }
}

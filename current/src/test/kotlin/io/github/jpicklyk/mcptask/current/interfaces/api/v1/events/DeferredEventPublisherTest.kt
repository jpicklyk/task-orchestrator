package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

/**
 * The commit signal from the event recorder to the SSE projection (item 0e9d5675's contracts, re-pointed by P8
 * ea2b9b63 from the per-transaction event buffer to the events table).
 *
 * Contracts kept from the buffer era: outside a unit a recorded event streams immediately; inside a unit nothing
 * streams (or becomes visible to another connection) before the commit, then everything streams in record order
 * with increasing ids; a rollback streams nothing and leaves nothing replayable; nested units signal once, at the
 * outermost commit. The buffer era's build-at-flush id rule (S6) and its minimal-descriptor probe are gone with
 * the buffer: ids are now the rows' seqs, allocated by the append inside the writer unit.
 */
class DeferredEventPublisherTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun wiring(): Pair<ApiEventBus, EventRecorder> {
        val store = db.repositoryProvider().eventStore()
        val bus = ApiEventBus(source = store)
        return bus to EventRecorder(store, listener = DeferredEventPublisher(bus))
    }

    private fun created(id: UUID) = DomainEvent.ItemCreated(id, id, null)

    private fun updated(id: UUID) = DomainEvent.ItemUpdated(id, id, listOf("title"))

    private fun deleted(id: UUID) = DomainEvent.ItemDeleted(id, id)

    /** Reads the projected log from a fresh context (no ambient unit), i.e. as another connection sees it. */
    private suspend fun committedView(bus: ApiEventBus): List<ApiEvent> =
        CoroutineScope(Dispatchers.IO).async { bus.projectedEvents() }.await()

    @Test
    fun `S1 - recording outside a unit streams synchronously`(): Unit =
        runBlocking {
            val (bus, recorder) = wiring()
            val itemId = UUID.randomUUID()
            val flow = bus.subscribe("s1", emptySet())

            recorder.record(created(itemId))

            val delivered = bus.drainDelivered("s1", flow)
            assertEquals(1, delivered.size, "with no ambient unit the event must stream immediately")
            assertEquals(ApiEventType.ITEM_CREATED, delivered[0].event)
            assertEquals(itemId.toString(), delivered[0].itemId)
        }

    @Test
    fun `S2 - recording inside a committing unit streams nothing until commit then everything in order`(): Unit =
        runBlocking {
            val (bus, recorder) = wiring()
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val id3 = UUID.randomUUID()
            val flow = bus.subscribe("s2", emptySet())

            db.unitOfWork().inUnit {
                recorder.record(listOf(created(id1), updated(id2), deleted(id3)))
                assertTrue(committedView(bus).isEmpty(), "no row may be visible to another connection before commit")
            }

            val delivered = bus.drainDelivered("s2", flow)
            assertEquals(
                listOf(ApiEventType.ITEM_CREATED, ApiEventType.ITEM_UPDATED, ApiEventType.ITEM_DELETED),
                delivered.map { it.event },
                "delivery order must match record order",
            )
            assertEquals(listOf(id1.toString(), id2.toString(), id3.toString()), delivered.map { it.itemId })
            assertTrue(
                delivered[0].id < delivered[1].id && delivered[1].id < delivered[2].id,
                "ids must be strictly increasing in record/commit order, got ${delivered.map { it.id }}",
            )
            assertEquals(delivered, committedView(bus), "live delivery must equal the committed projection")
        }

    @Test
    fun `S3 - recording inside a rolling-back unit streams nothing and leaves nothing replayable`(): Unit =
        runBlocking {
            val (bus, recorder) = wiring()
            val flow = bus.subscribe("s3", emptySet())

            var caught: Throwable? = null
            try {
                db.unitOfWork().inUnit {
                    recorder.record(created(UUID.randomUUID()))
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the unit failure to propagate, got: $caught")

            assertTrue(bus.drainDelivered("s3", flow).isEmpty(), "a rolled-back unit must stream nothing")
            assertTrue(committedView(bus).isEmpty(), "a rolled-back unit must leave no row")

            val replayed =
                withTimeoutOrNull(200) {
                    bus.subscribe("sub-replay-0e9d5675", emptySet(), lastEventId = 1_000_000_000_000L).take(1).toList()
                }
            assertTrue(replayed.isNullOrEmpty(), "nothing should be replayable for a rolled-back record, got: $replayed")
            bus.unsubscribe("sub-replay-0e9d5675")
        }

    @Test
    fun `S7a - nested units signal once on the outermost commit`(): Unit =
        runBlocking {
            val (bus, recorder) = wiring()
            val id = UUID.randomUUID()
            val flow = bus.subscribe("s7a", emptySet())

            db.unitOfWork().inUnit {
                db.unitOfWork().inUnit {
                    recorder.record(created(id))
                }
                assertTrue(committedView(bus).isEmpty(), "inner completion must not commit; only the outermost does")
            }

            val delivered = bus.drainDelivered("s7a", flow)
            assertEquals(1, delivered.size, "nested units share one commit, signalled exactly once")
            assertEquals(id.toString(), delivered[0].itemId)
        }

    @Test
    fun `S7b - nested units rolled back at the outer level stream nothing`(): Unit =
        runBlocking {
            val (bus, recorder) = wiring()
            val flow = bus.subscribe("s7b", emptySet())

            var caught: Throwable? = null
            try {
                db.unitOfWork().inUnit {
                    db.unitOfWork().inUnit {
                        recorder.record(created(UUID.randomUUID()))
                    }
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the outer rollback to propagate, got: $caught")
            assertTrue(bus.drainDelivered("s7b", flow).isEmpty(), "outer rollback must stream nothing")
            assertTrue(committedView(bus).isEmpty(), "outer rollback must leave no row")
        }

    @Test
    fun `edge - a unit that records nothing commits as a no-op`(): Unit =
        runBlocking {
            val (bus, _) = wiring()
            val flow = bus.subscribe("edge", emptySet())

            db.unitOfWork().inUnit {
                // intentionally empty
            }

            assertTrue(bus.drainDelivered("edge", flow).isEmpty(), "an empty unit must not stream anything on commit")
            assertTrue(committedView(bus).isEmpty())
        }
}

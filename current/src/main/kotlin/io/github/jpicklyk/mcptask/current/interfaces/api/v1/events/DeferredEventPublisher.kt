package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * A description of an [ApiEvent] that has NOT been built yet.
 *
 * The event id is what makes this indirection necessary: [ApiEventBus.buildEvent] stamps a
 * monotonic id at BUILD time, and both the SSE `Last-Event-ID` replay filter and the ring
 * buffer's oldest-id gap check assume id order equals buffer order. Buffering an already-built
 * event until commit would let a concurrent non-transactional publish take a HIGHER id and land
 * in front of it, so a deferred publish must carry a descriptor and build at flush time instead.
 *
 * [affectedRoots] is resolved at ENQUEUE time — inside the transaction, while the pre-update
 * parent chain is still visible — and carried through unchanged to
 * [ApiEventBus.publish]. An empty set means "roots could not be resolved" exactly as it does at
 * the enqueue site; see [EventPublishingRepositoryProvider]'s publish helper.
 */
data class PendingApiEvent(
    val eventType: String,
    val itemId: UUID? = null,
    val modifiedAt: Instant? = null,
    val newRole: String? = null,
    val affectedRoots: Set<UUID> = emptySet(),
)

/**
 * Publishes [ApiEvent]s to [eventBus] only once the enclosing database transaction has COMMITTED.
 *
 * ## Why
 *
 * Every SQLite repository method opens its own `suspendTransaction`, so a standalone write is
 * already durable by the time the [EventPublishingRepositoryProvider] decorator publishes. But
 * when an OUTER transaction is open — `WorkItemRepository.inTransaction`, used by the
 * role-transition pipeline, the recursive delete handler and both reparent cascades — the inner
 * transaction merely joins the outer one and does not commit. A publish there escapes to SSE
 * subscribers describing a change that a later failure in the same transaction then rolls back:
 * a phantom event the client can never reconcile, since the read API will not show the change.
 *
 * ## Semantics
 *
 * - **No active transaction** → build and publish immediately, synchronously, before
 *   [publishOnCommit] returns. This is the pre-existing behaviour of every standalone write and
 *   the success-path ordering is unchanged.
 * - **Transaction active** → append the descriptor to that transaction's FIFO buffer and register
 *   ONE [StatementInterceptor] on it. On `afterCommit` the buffered descriptors are built and
 *   published in enqueue order; on `afterRollback` they are discarded and nothing is published,
 *   nothing enters the ring buffer, and nothing can be replayed later.
 * - Nested `inTransaction` calls reuse the same [Transaction] object, so there is one buffer and
 *   one flush, at the outermost commit.
 *
 * ## Guarantees and non-goals
 *
 * The rollback guarantee is one-directional: a rolled-back transaction publishes nothing. It is
 * NOT a durability guarantee — the bus is in-process and its ring buffer is volatile, so a commit
 * whose flush races JVM exit still loses events, exactly as before this class existed.
 *
 * The per-transaction registry is a [WeakHashMap] behind a synchronized wrapper, so a transaction
 * whose `afterRollback` never fires cannot leak: the entry becomes unreachable with the
 * transaction and is collected. The failure mode of a missed callback is an unflushed buffer —
 * events lost, never events published for work that was rolled back.
 */
class DeferredEventPublisher(
    private val eventBus: ApiEventBus,
) {
    private val logger = LoggerFactory.getLogger(DeferredEventPublisher::class.java)

    /** FIFO buffer of descriptors enqueued by one transaction. Guards itself for mutation. */
    private class TransactionBuffer {
        val pending: MutableList<PendingApiEvent> = mutableListOf()
    }

    /**
     * Per-transaction buffers. Weakly keyed so an abandoned transaction cannot retain events.
     *
     * An entry is created once per transaction and kept (drained, not removed) across an
     * intermediate commit, so the interceptor is registered exactly once per transaction.
     */
    private val buffers: MutableMap<Transaction, TransactionBuffer> =
        Collections.synchronizedMap(WeakHashMap<Transaction, TransactionBuffer>())

    /**
     * Publish the event described by [pending] — now if no transaction is open, otherwise when the
     * open transaction commits.
     *
     * Safe to call from any context; never throws on the caller's behalf.
     */
    fun publishOnCommit(pending: PendingApiEvent) {
        val transaction = currentTransactionOrNull()
        if (transaction == null) {
            publishNow(pending)
            return
        }

        val buffer =
            synchronized(buffers) {
                buffers.getOrPut(transaction) {
                    transaction.registerInterceptor(FlushOnCommitInterceptor())
                    TransactionBuffer()
                }
            }
        synchronized(buffer) { buffer.pending.add(pending) }
    }

    /**
     * The currently bound JDBC transaction, or null when none is open.
     *
     * Defensive: a [TransactionManager] lookup that throws (no manager registered for the default
     * database in a bare unit-test context, for instance) must degrade to "publish now" rather
     * than fail the caller's write.
     */
    private fun currentTransactionOrNull(): JdbcTransaction? =
        try {
            TransactionManager.currentOrNull()
        } catch (e: Exception) {
            logger.debug("No transaction manager available; publishing immediately: {}", e.message)
            null
        }

    /** Build (allocating the event id here, in commit order) and publish immediately. */
    private fun publishNow(pending: PendingApiEvent) {
        val event =
            eventBus.buildEvent(
                pending.eventType,
                itemId = pending.itemId,
                modifiedAt = pending.modifiedAt,
                newRole = pending.newRole,
            )
        // Identical to the pre-deferral call: an empty affectedRoots means the producer could not
        // resolve them, which is precisely what rootsResolved=false denotes on the bus.
        eventBus.publish(
            event,
            affectedRoots = pending.affectedRoots,
            rootsResolved = pending.affectedRoots.isNotEmpty(),
        )
    }

    /** Drain [transaction]'s buffer and publish in enqueue order. */
    private fun flush(transaction: Transaction) {
        val buffer = synchronized(buffers) { buffers[transaction] } ?: return
        val drained =
            synchronized(buffer) {
                if (buffer.pending.isEmpty()) {
                    emptyList()
                } else {
                    val snapshot = buffer.pending.toList()
                    buffer.pending.clear()
                    snapshot
                }
            }
        for (pending in drained) {
            try {
                publishNow(pending)
            } catch (e: Exception) {
                // One bad event must not strand the rest of the committed batch.
                logger.warn("Failed to publish deferred event {}: {}", pending.eventType, e.message)
            }
        }
    }

    /** Drop [transaction]'s buffer without publishing anything. */
    private fun discard(transaction: Transaction) {
        val buffer = synchronized(buffers) { buffers.remove(transaction) } ?: return
        val dropped =
            synchronized(buffer) {
                val n = buffer.pending.size
                buffer.pending.clear()
                n
            }
        if (dropped > 0) {
            logger.debug("Discarded {} deferred event(s) on transaction rollback", dropped)
        }
    }

    private inner class FlushOnCommitInterceptor : StatementInterceptor {
        override fun afterCommit(transaction: Transaction) = flush(transaction)

        override fun afterRollback(transaction: Transaction) = discard(transaction)
    }
}

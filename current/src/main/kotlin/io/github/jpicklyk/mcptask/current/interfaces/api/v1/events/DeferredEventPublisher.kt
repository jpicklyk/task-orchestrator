package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventCommitListener
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import org.slf4j.LoggerFactory

/**
 * The commit signal from the event recorder to the SSE projection ([ApiEventBus]).
 *
 * The recorder appends `events` rows inside the writing unit of work and, once that unit has COMMITTED, calls
 * [committed] with the rows (an append made outside any unit is reported right away). A rolled-back unit never
 * signals, so nothing it wrote can stream. This class only forwards the signal and isolates the projection: a
 * failure to fan out is logged and never reaches the write that committed. Rows the signal misses (another
 * process's writes, a failed fan-out) are picked up by the bus's poll, because the table, not this signal, is
 * the source of truth.
 *
 * Before the events table this class buffered unbuilt events per Exposed transaction and published them on
 * commit; that buffering is now the unit of work's own `afterCommit` hook. P15 deletes this class together with
 * the decorator.
 */
class DeferredEventPublisher(
    private val eventBus: ApiEventBus,
) : EventCommitListener {
    private val logger = LoggerFactory.getLogger(DeferredEventPublisher::class.java)

    override suspend fun committed(records: List<EventRecord>) {
        try {
            eventBus.committed(records)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("SSE fan-out of {} committed event row(s) failed; the poll will retry: {}", records.size, e.message)
        }
    }
}

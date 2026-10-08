package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent

/**
 * Where a unit of work records its typed [DomainEvent]s ([WriteScope.events]). Each call appends one `events`
 * row per event, in order, inside the caller's unit: the rows commit or roll back with the change they describe.
 */
interface EventSink {
    /** Appends one row per event of [events], in order; returns the rows with their assigned seq. */
    suspend fun record(events: List<DomainEvent>): List<EventRecord>

    /** Appends one row for [event]. */
    suspend fun record(event: DomainEvent): EventRecord? = record(listOf(event)).firstOrNull()

    /**
     * Records [event], a `*.rejected` row, so that it survives the caller's unit: exactly one row whether that unit
     * commits or rolls back (a keyed call's element unit rolls back on the very rejection it records). The default
     * is a plain [record], for sinks with no transaction to escape.
     */
    suspend fun recordRejection(event: DomainEvent) {
        record(event)
    }
}

/**
 * Told about rows once the unit that appended them has COMMITTED (or right away, for an append made outside any
 * unit). The SSE projection implements it to wake its tail; nothing else should.
 */
fun interface EventCommitListener {
    suspend fun committed(records: List<EventRecord>)

    companion object {
        /** No listener: the API (and with it the SSE projection) is off. */
        val NONE: EventCommitListener = EventCommitListener { }
    }
}

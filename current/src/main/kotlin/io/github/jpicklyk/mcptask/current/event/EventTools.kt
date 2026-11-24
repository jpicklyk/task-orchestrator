package io.github.jpicklyk.mcptask.current.event

import io.github.jpicklyk.mcptask.current.event.internal.wire.ItemsReadHistory

/**
 * The event slice's contribution to the composite `items_read` tool: the selects the server lists, so the server names
 * no `event.internal` type for tools.
 */
object EventTools {
    /** `items_read` select `history`: the transition audit trail read from the event log. */
    val itemsReadHistory = ItemsReadHistory.select
}

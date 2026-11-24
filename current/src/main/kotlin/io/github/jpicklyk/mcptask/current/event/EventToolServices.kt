package io.github.jpicklyk.mcptask.current.event

import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.platform.port.EventStore

/** The services `items_read` `history` takes as `env`. Building the holder touches no store. */
class EventToolServices(
    private val items: () -> WorkItemRepository,
    private val events: () -> EventStore
) : ItemLookup {
    override fun workItemRepository(): WorkItemRepository = items()

    fun eventStore(): EventStore = events()
}

package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services the item reads of `items_read` (`get`, `list`, `overview`) take as `env`. A lazy member stays unbuilt
 * until a handler reads it, and building the holder touches no store.
 */
class ItemToolServices(
    private val items: () -> WorkItemRepository,
    val unitOfWork: UnitOfWork<ItemStoreProvider>,
    val overviewQueries: OverviewQueries,
    private val schemaPin: Lazy<PinViews>
) : ItemLookup {
    val pinViews: PinViews by schemaPin

    override fun workItemRepository(): WorkItemRepository = items()
}

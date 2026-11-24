package io.github.jpicklyk.mcptask.current.application.port

/** The stores the item slice reads and writes. [RepositoryProvider] implements it. */
interface ItemStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun itemStore(): ItemStore

    fun hierarchyStore(): HierarchyStore
}

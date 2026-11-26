package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.claim.ClaimStoreProvider

/** The stores the item slice reads and writes. `RepositoryProvider` implements it. */
interface ItemStoreProvider : ClaimStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun itemStore(): ItemStore

    fun hierarchyStore(): HierarchyStore
}

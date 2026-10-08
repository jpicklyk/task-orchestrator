package io.github.jpicklyk.mcptask.current.application.port

/**
 * The composite of the four work-item stores. It declares no members of its own: callers that
 * still take a [WorkItemRepository] get every [ItemStore], [HierarchyStore], [ClaimStore] and
 * [SearchIndex] method, and new code takes the narrow store it needs from
 * [RepositoryProvider.itemStore] / [RepositoryProvider.hierarchyStore] /
 * [RepositoryProvider.claimStore] / [RepositoryProvider.searchIndex].
 */
interface WorkItemRepository :
    ItemStore,
    HierarchyStore,
    ClaimStore,
    SearchIndex

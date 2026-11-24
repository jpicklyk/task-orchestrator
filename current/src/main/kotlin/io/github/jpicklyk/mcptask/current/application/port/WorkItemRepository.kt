package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.claim.ClaimItems
import io.github.jpicklyk.mcptask.current.claim.ClaimStore

/**
 * The composite of the three work-item stores. It declares no members of its own: callers that
 * still take a [WorkItemRepository] get every [ItemStore], [HierarchyStore] and [ClaimStore]
 * method, and new code takes the narrow store it needs from [RepositoryProvider.itemStore] /
 * [RepositoryProvider.hierarchyStore] / [RepositoryProvider.claimStore]. Full-text search is not
 * part of the composite: it is the separate [SearchIndex] port, from [RepositoryProvider.searchIndex].
 */
interface WorkItemRepository :
    ItemStore,
    HierarchyStore,
    ClaimStore,
    ClaimItems

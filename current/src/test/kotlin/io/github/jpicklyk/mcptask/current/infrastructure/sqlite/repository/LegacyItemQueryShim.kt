package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.ItemFetchResult
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.application.port.ItemSort
import io.github.jpicklyk.mcptask.current.application.port.ItemSortField
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.SortDirection
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// TEST-ONLY shim: the four pre-Q5 query members of ItemStore (findByFilters, countByFilters,
// findInScope, countInScope), kept as extensions over ItemStore.find/count so the existing
// repository and route tests keep their call sites and assertions. Production code calls
// find/count directly.
//
// Mapping, exactly as the old SqliteItemStore did it:
// - sortBy resolves through ItemSortField.parse (aliases included); null or unresolved -> createdAt.
// - sortOrder "asc" (any case) -> ascending; anything else, null included -> descending.
// - offset is coerced to >= 0; a limit below 1 selects nothing (SQL LIMIT 0).
// - the dropped `query` parameter is accepted and ignored, as the old store ignored it.
// - rootIds = emptySet() is an empty result (AccessScope with no roots).
// ---------------------------------------------------------------------------------------------

private fun legacySort(
    sortBy: String?,
    sortOrder: String?,
): ItemSort =
    ItemSort(
        sortBy?.let { ItemSortField.parse(it) } ?: ItemSortField.CREATED_AT,
        if (sortOrder?.lowercase() == "asc") SortDirection.ASC else SortDirection.DESC,
    )

@Suppress("LongParameterList")
private fun legacyFilter(
    parentId: UUID?,
    depth: Int?,
    role: Role?,
    priority: Priority?,
    tags: List<String>?,
    createdAfter: Instant?,
    createdBefore: Instant?,
    modifiedAfter: Instant?,
    modifiedBefore: Instant?,
    roleChangedAfter: Instant?,
    roleChangedBefore: Instant?,
    type: String?,
    claimStatus: ClaimStatus?,
): ItemFilter =
    ItemFilter(
        parentId = parentId,
        depth = depth,
        role = role,
        priority = priority,
        tagsAny = tags,
        type = type,
        createdAfter = createdAfter,
        createdBefore = createdBefore,
        modifiedAfter = modifiedAfter,
        modifiedBefore = modifiedBefore,
        roleChangedAfter = roleChangedAfter,
        roleChangedBefore = roleChangedBefore,
        claimStatus = claimStatus,
    )

private suspend fun ItemStore.legacyFind(
    filter: ItemFilter,
    scope: AccessScope,
    sortBy: String?,
    sortOrder: String?,
    limit: Int,
    offset: Int,
): List<WorkItem> {
    if (limit < 1) return emptyList()
    return find(filter, scope, ItemPage(limit, offset.coerceAtLeast(0)), legacySort(sortBy, sortOrder))
}

@Suppress("LongParameterList", "UNUSED_PARAMETER")
suspend fun ItemStore.findByFilters(
    parentId: UUID? = null,
    depth: Int? = null,
    role: Role? = null,
    priority: Priority? = null,
    tags: List<String>? = null,
    query: String? = null,
    createdAfter: Instant? = null,
    createdBefore: Instant? = null,
    modifiedAfter: Instant? = null,
    modifiedBefore: Instant? = null,
    roleChangedAfter: Instant? = null,
    roleChangedBefore: Instant? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    limit: Int = 50,
    offset: Int = 0,
    type: String? = null,
    claimStatus: ClaimStatus? = null,
): ItemFetchResult {
    val filter =
        legacyFilter(
            parentId,
            depth,
            role,
            priority,
            tags,
            createdAfter,
            createdBefore,
            modifiedAfter,
            modifiedBefore,
            roleChangedAfter,
            roleChangedBefore,
            type,
            claimStatus,
        )
    return ItemFetchResult(legacyFind(filter, AccessScope.unrestricted(), sortBy, sortOrder, limit, offset), skipped = 0)
}

@Suppress("LongParameterList", "UNUSED_PARAMETER")
suspend fun ItemStore.countByFilters(
    parentId: UUID? = null,
    depth: Int? = null,
    role: Role? = null,
    priority: Priority? = null,
    tags: List<String>? = null,
    query: String? = null,
    createdAfter: Instant? = null,
    createdBefore: Instant? = null,
    modifiedAfter: Instant? = null,
    modifiedBefore: Instant? = null,
    roleChangedAfter: Instant? = null,
    roleChangedBefore: Instant? = null,
    type: String? = null,
    claimStatus: ClaimStatus? = null,
): Int =
    count(
        legacyFilter(
            parentId,
            depth,
            role,
            priority,
            tags,
            createdAfter,
            createdBefore,
            modifiedAfter,
            modifiedBefore,
            roleChangedAfter,
            roleChangedBefore,
            type,
            claimStatus,
        ),
    )

@Suppress("LongParameterList", "UNUSED_PARAMETER")
suspend fun ItemStore.findInScope(
    rootIds: Set<UUID>,
    parentId: UUID? = null,
    depth: Int? = null,
    role: Role? = null,
    priority: Priority? = null,
    tags: List<String>? = null,
    query: String? = null,
    createdAfter: Instant? = null,
    createdBefore: Instant? = null,
    modifiedAfter: Instant? = null,
    modifiedBefore: Instant? = null,
    roleChangedAfter: Instant? = null,
    roleChangedBefore: Instant? = null,
    sortBy: String? = null,
    sortOrder: String? = null,
    limit: Int = 50,
    offset: Int = 0,
    type: String? = null,
    claimStatus: ClaimStatus? = null,
): List<WorkItem> {
    val filter =
        legacyFilter(
            parentId,
            depth,
            role,
            priority,
            tags,
            createdAfter,
            createdBefore,
            modifiedAfter,
            modifiedBefore,
            roleChangedAfter,
            roleChangedBefore,
            type,
            claimStatus,
        )
    return legacyFind(filter, AccessScope.principal(rootIds, emptySet()), sortBy, sortOrder, limit, offset)
}

@Suppress("LongParameterList", "UNUSED_PARAMETER")
suspend fun ItemStore.countInScope(
    rootIds: Set<UUID>,
    parentId: UUID? = null,
    depth: Int? = null,
    role: Role? = null,
    priority: Priority? = null,
    tags: List<String>? = null,
    query: String? = null,
    createdAfter: Instant? = null,
    createdBefore: Instant? = null,
    modifiedAfter: Instant? = null,
    modifiedBefore: Instant? = null,
    roleChangedAfter: Instant? = null,
    roleChangedBefore: Instant? = null,
    type: String? = null,
    claimStatus: ClaimStatus? = null,
): Int =
    count(
        legacyFilter(
            parentId,
            depth,
            role,
            priority,
            tags,
            createdAfter,
            createdBefore,
            modifiedAfter,
            modifiedBefore,
            roleChangedAfter,
            roleChangedBefore,
            type,
            claimStatus,
        ),
        AccessScope.principal(rootIds, emptySet()),
    )

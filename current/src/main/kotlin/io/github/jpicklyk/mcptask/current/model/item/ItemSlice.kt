package io.github.jpicklyk.mcptask.current.model.item

import io.github.jpicklyk.mcptask.current.model.item.WorkItem

/**
 * One page of items plus [total]: the exact number of items the query selects across all pages,
 * counted in the same transaction as [items].
 */
data class ItemSlice(
    val items: List<WorkItem>,
    val total: Int,
)

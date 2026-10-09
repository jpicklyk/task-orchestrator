package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DependencyType

/**
 * A `create_work_tree` dependency in ref terms (resolved to item ids inside the tree's write unit), after the
 * dependency write policy normalized it (IS_BLOCKED_BY becomes BLOCKS with its refs swapped).
 */
data class TreeDepSpec(
    val fromRef: String,
    val toRef: String,
    val type: DependencyType,
    val unblockAt: String?
)

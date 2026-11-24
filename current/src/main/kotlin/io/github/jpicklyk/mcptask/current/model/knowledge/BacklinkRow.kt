package io.github.jpicklyk.mcptask.current.model.knowledge

import io.github.jpicklyk.mcptask.current.model.dependency.DependencyType
import java.util.UUID

/**
 * A reverse-direction dependency edge: another item points *at* a given item.
 *
 * Returned by [io.github.jpicklyk.mcptask.current.dependency.DependencyStore.backlinks]
 * to represent items that hold an outbound edge pointing at a queried item.
 *
 * @property fromItemId UUID of the item that holds the dependency edge.
 * @property type       Dependency type on that edge.
 * @property fromTitle  Title of [fromItemId] (JOIN-fetched for convenience).
 */
data class BacklinkRow(
    val fromItemId: UUID,
    val type: DependencyType,
    val fromTitle: String,
)

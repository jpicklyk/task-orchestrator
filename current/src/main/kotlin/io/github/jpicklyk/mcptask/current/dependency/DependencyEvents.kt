package io.github.jpicklyk.mcptask.current.dependency

import io.github.jpicklyk.mcptask.current.model.dependency.Dependency
import io.github.jpicklyk.mcptask.current.model.event.DeleteCause
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import java.util.UUID

/** `dependency.added` for [dep] under [root] (the from item's root). */
internal fun dependencyAddedEvent(
    dep: Dependency,
    root: UUID
): DomainEvent = DomainEvent.DependencyAdded(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt)

/** `dependency.removed` for [dep] under [root]. */
internal fun dependencyRemovedEvent(
    dep: Dependency,
    root: UUID,
    cause: DeleteCause
): DomainEvent = DomainEvent.DependencyRemoved(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt, cause)

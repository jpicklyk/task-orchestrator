package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/** Outcome of [WorkItemPlacementService.checkReparent]. */
sealed interface ReparentCheck {
    data object Ok : ReparentCheck

    data class ParentNotFound(
        val parentId: UUID
    ) : ReparentCheck

    data object SelfParent : ReparentCheck

    data object DescendantCycle : ReparentCheck

    /** The ancestor lookup itself failed; callers MUST treat this as a rejection (fail closed). */
    data class LookupFailed(
        val message: String
    ) : ReparentCheck
}

/** Outcome of [WorkItemPlacementService.create] / [WorkItemPlacementService.update]. */
sealed interface PlacedWriteOutcome {
    data class Written(
        val item: WorkItem
    ) : PlacedWriteOutcome

    data class ParentNotFound(
        val parentId: UUID
    ) : PlacedWriteOutcome

    data class BuildFailed(
        val message: String
    ) : PlacedWriteOutcome

    data class WriteFailed(
        val error: RepositoryError
    ) : PlacedWriteOutcome

    data class CascadeFailed(
        val message: String
    ) : PlacedWriteOutcome
}

/**
 * Single home of the hierarchy guard rules and the placement-aware create / reparent write
 * pipeline shared by the MCP `manage_items` handlers and the REST `POST/PATCH /items` routes.
 *
 * Guard reads ([parentExists], [checkReparent]) never open a transaction. The writes
 * ([create], [update]) open exactly ONE top-level transaction when a parent is involved, holding
 * placement resolution (AR-19), the item write and (for updates) the descendant depth/rootId
 * cascade, so a cascade failure rolls back the item's own write too.
 *
 * Callers own request parsing, scope checks, ETag/idempotency handling and event-actor scoping;
 * this service knows nothing about them.
 */
class WorkItemPlacementService(
    private val repo: WorkItemRepository,
    private val hierarchyValidator: ItemHierarchyValidator = ItemHierarchyValidator()
) {
    /** True when [parentId] resolves to an existing item. */
    suspend fun parentExists(parentId: UUID): Boolean = repo.getById(parentId) is Result.Success

    /**
     * Guard for reparenting [itemId] under [newParentId]: the parent must exist, must not be the
     * item itself, and must not lie in the item's own subtree. A failed ancestor lookup fails
     * CLOSED ([ReparentCheck.LookupFailed]).
     */
    suspend fun checkReparent(
        itemId: UUID,
        newParentId: UUID
    ): ReparentCheck {
        if (repo.getById(newParentId) !is Result.Success) return ReparentCheck.ParentNotFound(newParentId)
        if (newParentId == itemId) return ReparentCheck.SelfParent
        return when (val chains = repo.findAncestorChains(setOf(newParentId))) {
            is Result.Error -> ReparentCheck.LookupFailed(chains.error.message)
            is Result.Success -> {
                val chain = chains.data[newParentId] ?: emptyList()
                if (chain.any { it.id == itemId }) ReparentCheck.DescendantCycle else ReparentCheck.Ok
            }
        }
    }

    /**
     * Creates an item. With a null [parentId] no transaction is opened; otherwise placement
     * resolution, [build] and the insert share one transaction.
     */
    suspend fun create(
        itemId: UUID,
        parentId: UUID?,
        build: (depth: Int, rootId: UUID) -> WorkItem
    ): PlacedWriteOutcome {
        if (parentId == null) {
            val item =
                try {
                    build(0, itemId)
                } catch (e: Exception) {
                    return PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                }
            return repo.create(item).toOutcome()
        }

        var outcome: PlacedWriteOutcome? = null
        repo.inTransaction {
            when (val placementResult = repo.resolveChildPlacement(parentId)) {
                is Result.Error -> outcome = PlacedWriteOutcome.ParentNotFound(parentId)
                is Result.Success -> {
                    val placement = placementResult.data
                    val item =
                        try {
                            build(placement.depth, placement.rootId)
                        } catch (e: Exception) {
                            outcome = PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                            return@inTransaction
                        }
                    outcome = repo.create(item).toOutcome()
                }
            }
        }
        return outcome!!
    }

    /**
     * Updates [existing]. When [parentChanged] is false the write is a plain update with the
     * item's current placement. When true, placement (or move-to-root when [newParentId] is
     * null), the item write and the descendant cascade run in ONE transaction; a cascade failure
     * aborts it and is reported as [PlacedWriteOutcome.CascadeFailed].
     */
    suspend fun update(
        existing: WorkItem,
        newParentId: UUID?,
        parentChanged: Boolean,
        build: (depth: Int, rootId: UUID?) -> WorkItem
    ): PlacedWriteOutcome {
        if (!parentChanged) {
            val item =
                try {
                    build(existing.depth, existing.rootId)
                } catch (e: Exception) {
                    return PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                }
            return repo.update(item).toOutcome()
        }

        var outcome: PlacedWriteOutcome? = null
        try {
            repo.inTransaction {
                val newDepth: Int
                val newRootId: UUID
                if (newParentId != null) {
                    when (val placementResult = repo.resolveChildPlacement(newParentId)) {
                        is Result.Success -> {
                            newDepth = placementResult.data.depth
                            newRootId = placementResult.data.rootId
                        }
                        is Result.Error -> {
                            outcome = PlacedWriteOutcome.ParentNotFound(newParentId)
                            return@inTransaction
                        }
                    }
                } else {
                    newDepth = 0
                    newRootId = existing.id
                }

                val updated =
                    try {
                        build(newDepth, newRootId)
                    } catch (e: Exception) {
                        outcome = PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                        return@inTransaction
                    }

                val txResult = repo.update(updated)
                outcome = txResult.toOutcome()
                if (txResult is Result.Success) {
                    when (
                        val cascade =
                            hierarchyValidator.recomputeDescendantDepths(
                                existing.id,
                                newDepth - existing.depth,
                                newRootId,
                                repo
                            )
                    ) {
                        is Result.Success -> {}
                        is Result.Error -> throw CascadeAbort(cascade.error.message)
                    }
                }
            }
        } catch (e: CascadeAbort) {
            // The transaction rolled back; no partial writes remain.
            return PlacedWriteOutcome.CascadeFailed(e.message ?: "cascade failed")
        }
        return outcome!!
    }

    private fun Result<WorkItem>.toOutcome(): PlacedWriteOutcome =
        when (this) {
            is Result.Success -> PlacedWriteOutcome.Written(data)
            is Result.Error -> PlacedWriteOutcome.WriteFailed(error)
        }

    private class CascadeAbort(
        message: String
    ) : Exception(message)
}

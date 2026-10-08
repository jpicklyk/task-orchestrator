package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
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
 * Guard reads ([parentExists], [checkReparent]) never open a unit. Each write ([create], [update]) is
 * ONE write unit (joined when the caller already runs one) holding placement resolution (AR-19), the
 * item write and (for reparenting updates) the descendant depth/rootId cascade, so a cascade failure
 * rolls back the item's own write too.
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
     * Creates an item in one write unit of [unitOfWork]. With a non-null [parentId], placement resolution, [build]
     * and the insert share that unit.
     */
    suspend fun create(
        unitOfWork: UnitOfWork,
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
            return placedWrite(unitOfWork, "WorkItemPlacementService.create") { decide(repo.create(item)) }
        }

        return placedWrite(unitOfWork, "WorkItemPlacementService.create") {
            when (val placementResult = repo.resolveChildPlacement(parentId)) {
                is Result.Error -> UnitResult.Rollback(PlacedWriteOutcome.ParentNotFound(parentId))
                is Result.Success -> {
                    val placement = placementResult.data
                    val item =
                        try {
                            build(placement.depth, placement.rootId)
                        } catch (e: Exception) {
                            return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed"))
                        }
                    decide(repo.create(item))
                }
            }
        }
    }

    /**
     * Updates [existing] in one write unit of [unitOfWork]. When [parentChanged] is false the write is a plain update
     * with the item's current placement. When true, placement (or move-to-root when [newParentId] is
     * null), the item write and the descendant cascade share that unit; a cascade failure rolls it
     * back and is reported as [PlacedWriteOutcome.CascadeFailed].
     */
    suspend fun update(
        unitOfWork: UnitOfWork,
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
            return placedWrite(unitOfWork, "WorkItemPlacementService.update") { decide(repo.update(item)) }
        }

        return placedWrite(unitOfWork, "WorkItemPlacementService.update") {
            val newDepth: Int
            val newRootId: UUID
            if (newParentId != null) {
                when (val placementResult = repo.resolveChildPlacement(newParentId)) {
                    is Result.Success -> {
                        newDepth = placementResult.data.depth
                        newRootId = placementResult.data.rootId
                    }
                    is Result.Error -> return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.ParentNotFound(newParentId))
                }
            } else {
                newDepth = 0
                newRootId = existing.id
            }

            val updated =
                try {
                    build(newDepth, newRootId)
                } catch (e: Exception) {
                    return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed"))
                }

            when (val txResult = repo.update(updated)) {
                is Result.Error -> UnitResult.Rollback(PlacedWriteOutcome.WriteFailed(txResult.error))
                is Result.Success ->
                    when (
                        val cascade =
                            hierarchyValidator.recomputeDescendantDepths(
                                existing.id,
                                newDepth - existing.depth,
                                newRootId,
                                repo
                            )
                    ) {
                        is Result.Success -> UnitResult.Commit(PlacedWriteOutcome.Written(txResult.data))
                        // The unit rolls back; no partial writes remain.
                        is Result.Error -> UnitResult.Rollback(PlacedWriteOutcome.CascadeFailed(cascade.error.message))
                    }
            }
        }
    }

    /**
     * One write unit for a placed write: [UnitResult.Rollback] discards the unit's writes, and a fault of
     * the unit itself (translated at its boundary) becomes [PlacedWriteOutcome.WriteFailed].
     */
    private suspend fun placedWrite(
        unitOfWork: UnitOfWork,
        op: String,
        block: suspend () -> UnitResult<PlacedWriteOutcome>
    ): PlacedWriteOutcome =
        unitOfWork.writeUnit(op, onFault = { PlacedWriteOutcome.WriteFailed(LegacyFaults.toRepositoryError(it)) }) { block() }

    /** A store write result inside a unit: a failure never continues to a commit. */
    private fun decide(result: Result<WorkItem>): UnitResult<PlacedWriteOutcome> =
        when (result) {
            is Result.Success -> UnitResult.Commit(PlacedWriteOutcome.Written(result.data))
            is Result.Error -> UnitResult.Rollback(PlacedWriteOutcome.WriteFailed(result.error))
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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

    /** The write failed: [error] is the translated fault (`version_conflict` for a lost optimistic lock), or not_found for a vanished row. */
    data class WriteFailed(
        val error: DomainError
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
    suspend fun parentExists(parentId: UUID): Boolean = repo.getById(parentId) != null

    /**
     * Guard for reparenting [itemId] under [newParentId]: the parent must exist, must not be the
     * item itself, and must not lie in the item's own subtree. A failed ancestor lookup fails
     * CLOSED ([ReparentCheck.LookupFailed]).
     */
    suspend fun checkReparent(
        itemId: UUID,
        newParentId: UUID
    ): ReparentCheck {
        if (repo.getById(newParentId) == null) return ReparentCheck.ParentNotFound(newParentId)
        if (newParentId == itemId) return ReparentCheck.SelfParent
        val chains =
            try {
                repo.findAncestorChains(setOf(newParentId))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return ReparentCheck.LookupFailed(LegacyFaults.message(e))
            }
        val chain = chains[newParentId] ?: emptyList()
        return if (chain.any { it.id == itemId }) ReparentCheck.DescendantCycle else ReparentCheck.Ok
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
                    e.rethrowIfCancellation()
                    return PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                }
            return placedWrite(
                unitOfWork,
                "WorkItemPlacementService.create"
            ) { UnitResult.Commit(PlacedWriteOutcome.Written(repo.create(item))) }
        }

        return placedWrite(unitOfWork, "WorkItemPlacementService.create") {
            val placement =
                repo.resolveChildPlacement(parentId)
                    ?: return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.ParentNotFound(parentId))
            val item =
                try {
                    build(placement.depth, placement.rootId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed"))
                }
            UnitResult.Commit(PlacedWriteOutcome.Written(repo.create(item)))
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
                    e.rethrowIfCancellation()
                    return PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed")
                }
            return placedWrite(unitOfWork, "WorkItemPlacementService.update") { decideUpdate(item.id, repo.update(item)) }
        }

        return placedWrite(unitOfWork, "WorkItemPlacementService.update") {
            val newDepth: Int
            val newRootId: UUID
            if (newParentId != null) {
                val placement =
                    repo.resolveChildPlacement(newParentId)
                        ?: return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.ParentNotFound(newParentId))
                newDepth = placement.depth
                newRootId = placement.rootId
            } else {
                newDepth = 0
                newRootId = existing.id
            }

            val updated =
                try {
                    build(newDepth, newRootId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return@placedWrite UnitResult.Rollback(PlacedWriteOutcome.BuildFailed(e.message ?: "Validation failed"))
                }

            val written = repo.update(updated) ?: return@placedWrite UnitResult.Rollback(missing(updated.id))
            val cascadeFailure =
                hierarchyValidator.recomputeDescendantDepths(
                    existing.id,
                    newDepth - existing.depth,
                    newRootId,
                    repo
                )
            if (cascadeFailure == null) {
                UnitResult.Commit(PlacedWriteOutcome.Written(written))
            } else {
                // The unit rolls back; no partial writes remain.
                UnitResult.Rollback(PlacedWriteOutcome.CascadeFailed(cascadeFailure))
            }
        }
    }

    /**
     * One write unit for a placed write: [UnitResult.Rollback] discards the unit's writes, and a store fault
     * (translated at the unit boundary, or thrown and mapped by [LegacyFaults.fault]) becomes
     * [PlacedWriteOutcome.WriteFailed].
     */
    private suspend fun placedWrite(
        unitOfWork: UnitOfWork,
        op: String,
        block: suspend () -> UnitResult<PlacedWriteOutcome>
    ): PlacedWriteOutcome = unitOfWork.writeUnit(op, onFault = { PlacedWriteOutcome.WriteFailed(it) }) { block() }

    /** An update result inside a unit: a vanished row (null) rolls back as not_found. */
    private fun decideUpdate(
        id: UUID,
        written: WorkItem?
    ): UnitResult<PlacedWriteOutcome> =
        if (written ==
            null
        ) {
            UnitResult.Rollback(missing(id))
        } else {
            UnitResult.Commit(PlacedWriteOutcome.Written(written))
        }

    private fun missing(id: UUID): PlacedWriteOutcome.WriteFailed =
        PlacedWriteOutcome.WriteFailed(
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "WorkItem not found with id: $id",
                detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
                fixArgs = mapOf("kind" to "item", "id" to id.toString())
            )
        )
}

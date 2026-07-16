package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/** Logical dependency specification using ref names (resolved to UUIDs at execution time). */
data class TreeDepSpec(
    val fromRef: String,
    val toRef: String,
    val type: DependencyType,
    val unblockAt: String?
)

/**
 * Identifies the stashed [io.github.jpicklyk.mcptask.current.domain.model.PlanDocument] a
 * `create_work_tree` call materializes `noteAnchors` from, and the item that adopts it.
 *
 * [adoptingItemId] is resolved by the caller BEFORE execution (both the create-mode root's
 * freshly-generated id and the attach-mode existing root's id are already known at that point —
 * see [io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool]), so the
 * executor never needs to look it up via ref.
 */
data class DocRefSpec(
    val rootItemId: UUID,
    val slug: String,
    val adoptingItemId: UUID
)

/** Input bundle for a single work-tree creation. Items must be ordered root-first. */
data class WorkTreeInput(
    val items: List<WorkItem>,
    val refToItem: Map<String, WorkItem>,
    val deps: List<TreeDepSpec>,
    val notes: List<Note>,
    /**
     * When set, [io.github.jpicklyk.mcptask.current.infrastructure.service.SQLiteWorkTreeService]
     * marks the referenced plan document ADOPTED as the LAST step of the same transaction that
     * inserts [items]/[deps]/[notes] — see [DocRefSpec]. Note bodies sliced from the document are
     * expected to already be present in [notes] by the time this reaches the executor (slicing
     * itself happens in the tool, before any DB writes, so an anchor miss fails loud with zero
     * items created); this field's sole job is the atomic adopt-or-rollback.
     */
    val docRef: DocRefSpec? = null
)

/** Result returned after successful work-tree creation. */
data class WorkTreeResult(
    val items: List<WorkItem>,
    val refToId: Map<String, UUID>,
    val deps: List<Dependency>,
    val notes: List<Note>
)

/**
 * Atomically creates a work tree: root item, child items, dependencies, and optional notes.
 * Implementations must ensure all-or-nothing semantics — a failure at any step rolls back
 * all prior inserts.
 */
interface WorkTreeExecutor {
    suspend fun execute(input: WorkTreeInput): WorkTreeResult
}

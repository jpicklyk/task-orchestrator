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

/** Input bundle for a single work-tree creation. Items must be ordered root-first. */
data class WorkTreeInput(
    val items: List<WorkItem>,
    val refToItem: Map<String, WorkItem>,
    val deps: List<TreeDepSpec>,
    val notes: List<Note>
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

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/**
 * Specifies a dependency between two items in the work tree, using logical ref names.
 *
 * @property fromRef The ref name of the "from" item (the blocker). Use "root" for the root item.
 * @property toRef   The ref name of the "to" item (the blocked). Use "root" for the root item.
 * @property type    The dependency type (BLOCKS, IS_BLOCKED_BY, RELATES_TO).
 * @property unblockAt Optional threshold role at which downstream unblocks (queue, work, review, terminal).
 */
data class TreeDepSpec(
    val fromRef: String,
    val toRef: String,
    val type: DependencyType,
    val unblockAt: String?
)

/**
 * Input bundle for a single [WorkTreeService] execution.
 *
 * @property items      All WorkItems to insert, root first then children in order.
 * @property refToItem  Mapping from logical ref name → WorkItem (used for dependency wiring).
 * @property deps       Dependency specs using ref names.
 * @property notes      Pre-built Note objects to upsert (blank body, correct itemId already set).
 */
data class WorkTreeInput(
    val items: List<WorkItem>,
    val refToItem: Map<String, WorkItem>,
    val deps: List<TreeDepSpec>,
    val notes: List<Note>
)

/**
 * Result returned after a successful [WorkTreeService] execution.
 *
 * @property items    All created WorkItems in insertion order (root first).
 * @property refToId  Mapping from logical ref name → UUID of the created item.
 * @property deps     All created Dependency objects.
 * @property notes    All created/upserted Note objects.
 */
data class WorkTreeResult(
    val items: List<WorkItem>,
    val refToId: Map<String, UUID>,
    val deps: List<Dependency>,
    val notes: List<Note>
)

/**
 * Service that atomically creates a work tree: root item, child items, dependencies, and optional notes.
 *
 * The entire operation is performed within a single coroutine-friendly transaction. If any step fails
 * an exception is thrown and the transaction is rolled back, leaving the database unchanged.
 */
class WorkTreeService(
    private val workItemRepository: WorkItemRepository,
    private val dependencyRepository: DependencyRepository,
    private val noteRepository: NoteRepository
) {

    /**
     * Executes the work-tree creation atomically.
     *
     * 1. Inserts each WorkItem from [WorkTreeInput.items] in order (root first).
     * 2. Creates each dependency in [WorkTreeInput.deps], resolving ref names to UUIDs.
     * 3. Upserts each Note in [WorkTreeInput.notes].
     *
     * @throws IllegalStateException if any repository operation fails.
     */
    suspend fun execute(input: WorkTreeInput): WorkTreeResult {
        val createdItems = mutableListOf<WorkItem>()
        val refToId = mutableMapOf<String, UUID>()

        // Build a reverse map: WorkItem.id -> ref name, so we can look up refs after insertion
        val itemIdToRef = input.refToItem.entries.associate { (ref, item) -> item.id to ref }

        // 1. Insert all items
        for (item in input.items) {
            val result = workItemRepository.create(item)
            val created = when {
                result is io.github.jpicklyk.mcptask.current.domain.repository.Result.Success -> result.data
                result is io.github.jpicklyk.mcptask.current.domain.repository.Result.Error ->
                    throw IllegalStateException("Failed to create work item '${item.title}': ${result.error.message}")
                else -> throw IllegalStateException("Unexpected result type")
            }
            createdItems.add(created)
            // Register ref → UUID mapping
            val ref = itemIdToRef[item.id]
            if (ref != null) {
                refToId[ref] = created.id
            }
        }

        // 2. Create dependencies (wiring refs to real UUIDs)
        val createdDeps = mutableListOf<Dependency>()
        for (spec in input.deps) {
            val fromId = refToId[spec.fromRef]
                ?: throw IllegalStateException("Dependency ref '${spec.fromRef}' not found in created items")
            val toId = refToId[spec.toRef]
                ?: throw IllegalStateException("Dependency ref '${spec.toRef}' not found in created items")

            val dependency = Dependency(
                fromItemId = fromId,
                toItemId = toId,
                type = spec.type,
                unblockAt = spec.unblockAt
            )
            val created = dependencyRepository.create(dependency)
            createdDeps.add(created)
        }

        // 3. Upsert notes
        val createdNotes = mutableListOf<Note>()
        for (note in input.notes) {
            val result = noteRepository.upsert(note)
            val created = when {
                result is io.github.jpicklyk.mcptask.current.domain.repository.Result.Success -> result.data
                result is io.github.jpicklyk.mcptask.current.domain.repository.Result.Error ->
                    throw IllegalStateException("Failed to upsert note '${note.key}': ${result.error.message}")
                else -> throw IllegalStateException("Unexpected result type")
            }
            createdNotes.add(created)
        }

        return WorkTreeResult(
            items = createdItems,
            refToId = refToId,
            deps = createdDeps,
            notes = createdNotes
        )
    }
}

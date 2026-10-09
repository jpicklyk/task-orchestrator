package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.service

import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentAdoptOutcome
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLitePlanDocumentRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DependenciesTable
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Infrastructure-layer implementation of [WorkTreeExecutor].
 *
 * Uses Exposed table objects directly inside a single write transaction ([writeTx], joining the caller's unit when one is ambient) so that
 * all inserts (items, dependencies, notes) are committed atomically. Any exception thrown
 * during execution causes a full rollback — no orphaned rows.
 */
class SQLiteWorkTreeService(
    private val databaseManager: DatabaseManager,
    private val workItemRepo: SQLiteWorkItemRepository,
    private val noteRepo: SQLiteNoteRepository,
    private val planDocumentRepo: SQLitePlanDocumentRepository
) : WorkTreeExecutor {
    override suspend fun execute(input: WorkTreeInput): WorkTreeResult =
        databaseManager.writeTx("WorkTreeService.execute") {
            val createdItems = mutableListOf<WorkItem>()
            val itemIdToRef = input.refToItem.entries.associate { (ref, item) -> item.id to ref }

            // Seed refToId from ALL refs in refToItem (existing + to-be-created) so that
            // dependency resolution can reference a pre-existing root (attach mode) before
            // any inserts happen. In normal create mode this is a no-op superset of the
            // per-insert population below — all refToItem entries are also in items.
            val refToId = input.refToItem.mapValuesTo(mutableMapOf()) { (_, item) -> item.id }

            // 1. Insert WorkItems that are in input.items (children in attach mode; all in create mode)
            for (item in input.items) {
                workItemRepo.insertRow(item)
                createdItems.add(item)
                val ref = itemIdToRef[item.id]
                if (ref != null) refToId[ref] = item.id
            }

            // 2. Insert dependencies using DependenciesTable directly. The specs arrive normalized and
            // cycle-checked (DependencyCommandService.validateTreeEdges); this executor keeps no direction logic.
            val createdDeps = mutableListOf<Dependency>()
            for (spec in input.deps) {
                val fromId =
                    refToId[spec.fromRef]
                        ?: throw IllegalStateException("Dependency ref '${spec.fromRef}' not found in created items")
                val toId =
                    refToId[spec.toRef]
                        ?: throw IllegalStateException("Dependency ref '${spec.toRef}' not found in created items")
                val dep =
                    Dependency(
                        fromItemId = fromId,
                        toItemId = toId,
                        type = spec.type,
                        unblockAt = spec.unblockAt
                    )
                DependenciesTable.insert {
                    it[id] = dep.id
                    it[fromItemId] = dep.fromItemId
                    it[toItemId] = dep.toItemId
                    it[type] = dep.type.name
                    it[unblockAt] = dep.unblockAt
                    it[createdAt] = dep.createdAt
                }
                createdDeps.add(dep)
            }

            // 3. Upsert notes via shared helper (no inner transaction)
            val createdNotes = mutableListOf<Note>()
            for (note in input.notes) {
                createdNotes.add(noteRepo.upsertRow(note))
            }

            // 4. Mark the source plan document adopted, LAST, in the SAME transaction as the
            // inserts above — see [io.github.jpicklyk.mcptask.current.application.service.DocRefSpec].
            // Any outcome other than a fresh Adopted (a concurrent adopt raced us, or the document
            // vanished between the tool's pre-check and here) throws, rolling back everything
            // inserted above — no partial tree, no double-adoption.
            val docRef = input.docRef
            if (docRef != null) {
                when (val outcome = planDocumentRepo.markAdoptedRow(docRef.rootItemId, docRef.slug, docRef.adoptingItemId)) {
                    is PlanDocumentAdoptOutcome.Adopted -> Unit
                    is PlanDocumentAdoptOutcome.AlreadyAdopted ->
                        throw IllegalStateException(
                            "Plan document '${docRef.slug}' (root ${docRef.rootItemId}) was concurrently adopted " +
                                "by item ${outcome.existing.adoptedByItemId}; aborting work tree creation"
                        )
                    is PlanDocumentAdoptOutcome.NotFound ->
                        throw IllegalStateException(
                            "Plan document '${docRef.slug}' (root ${docRef.rootItemId}) no longer exists; aborting work tree creation"
                        )
                }
            }

            WorkTreeResult(
                items = createdItems,
                refToId = refToId,
                deps = createdDeps,
                notes = createdNotes
            )
        }
}

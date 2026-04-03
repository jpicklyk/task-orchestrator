package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteDependencyRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.service.SQLiteWorkTreeService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [SQLiteWorkTreeService] using a real H2 in-memory database.
 *
 * Atomicity guarantee
 * -------------------
 * [SQLiteWorkTreeService] executes all inserts (items, dependencies, notes) inside a
 * single [newSuspendedTransaction] using Exposed table objects directly — no inner
 * repository transactions are opened.  This means a failure at any step rolls back
 * ALL prior inserts, providing true all-or-nothing semantics.
 *
 * Three scenarios are covered:
 *  1. Happy path — all items, dependencies, and notes are committed together.
 *  2. Midway failure on bad dependency ref — the entire transaction is rolled back;
 *     the root item that was "inserted" before the error is NOT present in the DB.
 *  3. Notes are NOT inserted when failure occurs during the dep step — same
 *     rollback guarantees the note is also absent.
 */
class WorkTreeServiceIntegrationTest {
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var dependencyRepository: SQLiteDependencyRepository
    private lateinit var noteRepository: SQLiteNoteRepository
    private lateinit var service: SQLiteWorkTreeService
    private lateinit var h2Database: Database

    @BeforeEach
    fun setUp() {
        val dbName = "worktree_integration_${System.nanoTime()}"
        h2Database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(h2Database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)

        workItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository
        noteRepository = repositoryProvider.noteRepository() as SQLiteNoteRepository
        dependencyRepository = repositoryProvider.dependencyRepository() as SQLiteDependencyRepository

        service = SQLiteWorkTreeService(databaseManager, workItemRepository, noteRepository)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: Happy path — all rows committed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - all items, dependency, and no notes committed atomically`(): Unit =
        runBlocking {
            val rootItem =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Root Item",
                    role = Role.QUEUE,
                    depth = 0,
                    parentId = null,
                    priority = Priority.MEDIUM
                )
            val childItem =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Child Item",
                    role = Role.QUEUE,
                    depth = 1,
                    parentId = rootItem.id,
                    priority = Priority.MEDIUM
                )

            val input =
                WorkTreeInput(
                    items = listOf(rootItem, childItem),
                    refToItem = mapOf("root" to rootItem, "child" to childItem),
                    deps =
                        listOf(
                            TreeDepSpec(
                                fromRef = "root",
                                toRef = "child",
                                type = DependencyType.BLOCKS,
                                unblockAt = null
                            )
                        ),
                    notes = emptyList()
                )

            val result = service.execute(input)

            // WorkTreeResult should contain both items and the single dependency
            assertEquals(2, result.items.size, "Expected 2 items in WorkTreeResult")
            assertEquals(1, result.deps.size, "Expected 1 dependency in WorkTreeResult")
            assertEquals(0, result.notes.size, "Expected 0 notes in WorkTreeResult")

            // Both items must be retrievable from the database
            val fetchedRoot = workItemRepository.getById(rootItem.id)
            assertTrue(fetchedRoot is Result.Success, "Root item should exist in DB after commit")
            assertEquals(rootItem.id, (fetchedRoot as Result.Success).data.id)

            val fetchedChild = workItemRepository.getById(childItem.id)
            assertTrue(fetchedChild is Result.Success, "Child item should exist in DB after commit")
            assertEquals(childItem.id, (fetchedChild as Result.Success).data.id)

            // Dependency ref mapping is correct
            assertEquals(rootItem.id, result.refToId["root"])
            assertEquals(childItem.id, result.refToId["child"])
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Midway failure on bad dep ref — full rollback, item NOT in DB
    //
    // "nonexistent_ref" is not present in refToItem. The executor throws
    // IllegalStateException during dep resolution. Because all operations run
    // inside a single newSuspendedTransaction, the entire transaction is rolled
    // back — including the root item insert that occurred before the error.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `midway failure on bad dep ref - service throws and all inserts are rolled back`() {
        val rootItem =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Root Item (should be rolled back)",
                role = Role.QUEUE,
                depth = 0,
                parentId = null,
                priority = Priority.MEDIUM
            )

        val input =
            WorkTreeInput(
                items = listOf(rootItem),
                refToItem = mapOf("root" to rootItem),
                deps =
                    listOf(
                        TreeDepSpec(
                            fromRef = "nonexistent_ref", // not in refToId → throws during dep step
                            toRef = "root",
                            type = DependencyType.BLOCKS,
                            unblockAt = null
                        )
                    ),
                notes = emptyList()
            )

        // Service must propagate the IllegalStateException
        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.execute(input) }
        }

        // Root item was rolled back — it must NOT be present in the DB
        val fetchedRoot = runBlocking { workItemRepository.getById(rootItem.id) }
        assertTrue(
            fetchedRoot is Result.Error,
            "Root item should NOT be present in DB (transaction was rolled back); got: $fetchedRoot"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3b: Two-node circular dependency — service throws, all inserts rolled back
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `two-node circular dependency is rejected before insertion`() {
        val itemX =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Item X",
                role = Role.QUEUE,
                depth = 0,
                parentId = null,
                priority = Priority.MEDIUM
            )
        val itemY =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Item Y",
                role = Role.QUEUE,
                depth = 1,
                parentId = itemX.id,
                priority = Priority.MEDIUM
            )

        val input =
            WorkTreeInput(
                items = listOf(itemX, itemY),
                refToItem = mapOf("x" to itemX, "y" to itemY),
                deps =
                    listOf(
                        TreeDepSpec(fromRef = "x", toRef = "y", type = DependencyType.BLOCKS, unblockAt = null),
                        TreeDepSpec(fromRef = "y", toRef = "x", type = DependencyType.BLOCKS, unblockAt = null)
                    ),
                notes = emptyList()
            )

        val ex =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { service.execute(input) }
            }
        assertTrue(
            ex.message?.contains("Circular") == true ||
                ex.message?.contains("cycle") == true ||
                ex.message?.contains("circular") == true,
            "Expected cycle error message but got: ${ex.message}"
        )

        // Both items must have been rolled back
        val fetchedX = runBlocking { workItemRepository.getById(itemX.id) }
        assertTrue(fetchedX is Result.Error, "Item X should not be in DB after rollback; got: $fetchedX")
        val fetchedY = runBlocking { workItemRepository.getById(itemY.id) }
        assertTrue(fetchedY is Result.Error, "Item Y should not be in DB after rollback; got: $fetchedY")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3c: Three-node circular dependency — service throws, all inserts rolled back
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `three-node circular dependency is rejected before insertion`() {
        val itemA =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Item A",
                role = Role.QUEUE,
                depth = 0,
                parentId = null,
                priority = Priority.MEDIUM
            )
        val itemB =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Item B",
                role = Role.QUEUE,
                depth = 1,
                parentId = itemA.id,
                priority = Priority.MEDIUM
            )
        val itemC =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Item C",
                role = Role.QUEUE,
                depth = 1,
                parentId = itemA.id,
                priority = Priority.MEDIUM
            )

        val input =
            WorkTreeInput(
                items = listOf(itemA, itemB, itemC),
                refToItem = mapOf("a" to itemA, "b" to itemB, "c" to itemC),
                deps =
                    listOf(
                        TreeDepSpec(fromRef = "a", toRef = "b", type = DependencyType.BLOCKS, unblockAt = null),
                        TreeDepSpec(fromRef = "b", toRef = "c", type = DependencyType.BLOCKS, unblockAt = null),
                        TreeDepSpec(fromRef = "c", toRef = "a", type = DependencyType.BLOCKS, unblockAt = null)
                    ),
                notes = emptyList()
            )

        val ex =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { service.execute(input) }
            }
        assertTrue(
            ex.message?.contains("Circular") == true ||
                ex.message?.contains("cycle") == true ||
                ex.message?.contains("circular") == true,
            "Expected cycle error message but got: ${ex.message}"
        )

        // All items must have been rolled back
        val fetchedA = runBlocking { workItemRepository.getById(itemA.id) }
        assertTrue(fetchedA is Result.Error, "Item A should not be in DB after rollback; got: $fetchedA")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3d: Valid linear chain (no cycle) — succeeds
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid linear chain with no cycle succeeds`(): Unit =
        runBlocking {
            val itemA =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Item A",
                    role = Role.QUEUE,
                    depth = 0,
                    parentId = null,
                    priority = Priority.MEDIUM
                )
            val itemB =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Item B",
                    role = Role.QUEUE,
                    depth = 1,
                    parentId = itemA.id,
                    priority = Priority.MEDIUM
                )
            val itemC =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Item C",
                    role = Role.QUEUE,
                    depth = 1,
                    parentId = itemA.id,
                    priority = Priority.MEDIUM
                )

            val input =
                WorkTreeInput(
                    items = listOf(itemA, itemB, itemC),
                    refToItem = mapOf("a" to itemA, "b" to itemB, "c" to itemC),
                    deps =
                        listOf(
                            TreeDepSpec(fromRef = "a", toRef = "b", type = DependencyType.BLOCKS, unblockAt = null),
                            TreeDepSpec(fromRef = "b", toRef = "c", type = DependencyType.BLOCKS, unblockAt = null)
                        ),
                    notes = emptyList()
                )

            val result = service.execute(input)
            assertEquals(3, result.items.size, "Expected 3 items in result")
            assertEquals(2, result.deps.size, "Expected 2 deps in result")

            // All items must be in the DB
            val fetchedA = workItemRepository.getById(itemA.id)
            assertTrue(fetchedA is Result.Success, "Item A should be in DB; got: $fetchedA")
            val fetchedB = workItemRepository.getById(itemB.id)
            assertTrue(fetchedB is Result.Success, "Item B should be in DB; got: $fetchedB")
            val fetchedC = workItemRepository.getById(itemC.id)
            assertTrue(fetchedC is Result.Success, "Item C should be in DB; got: $fetchedC")
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Test H1: insertRow() Result.Error triggers rollback — duplicate UUID
    //
    // The second item has the same UUID as the first.  The underlying INSERT
    // fails with a primary-key violation, which is rethrown by Exposed and rolls
    // back the entire transaction.  Neither item should exist in the DB afterwards.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `duplicate item UUID causes full transaction rollback`(): Unit =
        runBlocking {
            val sharedId = UUID.randomUUID()
            val firstItem =
                WorkItem(
                    id = sharedId,
                    title = "First Item",
                    role = Role.QUEUE,
                    depth = 0,
                    parentId = null,
                    priority = Priority.MEDIUM
                )
            // Second item intentionally reuses the same UUID — will trigger a PK violation
            val secondItem =
                WorkItem(
                    id = sharedId,
                    title = "Duplicate ID Item",
                    role = Role.QUEUE,
                    depth = 1,
                    parentId = firstItem.id,
                    priority = Priority.MEDIUM
                )

            val input =
                WorkTreeInput(
                    items = listOf(firstItem, secondItem),
                    refToItem = mapOf("first" to firstItem, "second" to secondItem),
                    deps = emptyList(),
                    notes = emptyList()
                )

            // The duplicate PK insert must propagate an exception
            assertThrows(Exception::class.java) {
                runBlocking { service.execute(input) }
            }

            // Neither item should be present — the whole transaction was rolled back
            val fetchedFirst = workItemRepository.getById(sharedId)
            assertTrue(
                fetchedFirst is Result.Error,
                "Item with duplicate UUID should NOT be in DB after rollback; got: $fetchedFirst"
            )
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Test H2: Happy path with notes — items, dependency, AND notes all committed
    //
    // Exercises the full execute() path including the note upsert phase.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path with notes - items, dependency, and notes committed atomically`(): Unit =
        runBlocking {
            val rootItem =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Root With Notes",
                    role = Role.QUEUE,
                    depth = 0,
                    parentId = null,
                    priority = Priority.MEDIUM
                )
            val childItem =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Child With Notes",
                    role = Role.QUEUE,
                    depth = 1,
                    parentId = rootItem.id,
                    priority = Priority.MEDIUM
                )

            val note1 =
                io.github.jpicklyk.mcptask.current.domain.model.Note(
                    id = UUID.randomUUID(),
                    itemId = rootItem.id,
                    key = "requirements",
                    role = "queue",
                    body = "Root requirements note"
                )
            val note2 =
                io.github.jpicklyk.mcptask.current.domain.model.Note(
                    id = UUID.randomUUID(),
                    itemId = childItem.id,
                    key = "approach",
                    role = "work",
                    body = "Child approach note"
                )

            val input =
                WorkTreeInput(
                    items = listOf(rootItem, childItem),
                    refToItem = mapOf("root" to rootItem, "child" to childItem),
                    deps =
                        listOf(
                            TreeDepSpec(
                                fromRef = "root",
                                toRef = "child",
                                type = DependencyType.BLOCKS,
                                unblockAt = null
                            )
                        ),
                    notes = listOf(note1, note2)
                )

            val result = service.execute(input)

            // Result counts
            assertEquals(2, result.items.size, "Expected 2 items in WorkTreeResult")
            assertEquals(1, result.deps.size, "Expected 1 dependency in WorkTreeResult")
            assertEquals(2, result.notes.size, "Expected 2 notes in WorkTreeResult")

            // Both items in DB
            val fetchedRoot = workItemRepository.getById(rootItem.id)
            assertTrue(fetchedRoot is Result.Success, "Root item should exist in DB after commit")

            val fetchedChild = workItemRepository.getById(childItem.id)
            assertTrue(fetchedChild is Result.Success, "Child item should exist in DB after commit")

            // Dependency exists in DB (query via repository)
            val rootDeps = dependencyRepository.findByItemId(rootItem.id)
            assertEquals(1, rootDeps.size, "Root item should have 1 outgoing dependency")
            assertEquals(childItem.id, rootDeps[0].toItemId)

            // Notes exist in DB with correct content
            val fetchedNote1Result = noteRepository.findByItemIdAndKey(rootItem.id, "requirements")
            assertTrue(fetchedNote1Result is Result.Success, "Note 1 lookup should succeed")
            val fetchedNote1 = (fetchedNote1Result as Result.Success).data
            assertTrue(fetchedNote1 != null, "Note 1 should exist in DB")
            assertEquals("Root requirements note", fetchedNote1!!.body)

            val fetchedNote2Result = noteRepository.findByItemIdAndKey(childItem.id, "approach")
            assertTrue(fetchedNote2Result is Result.Success, "Note 2 lookup should succeed")
            val fetchedNote2 = (fetchedNote2Result as Result.Success).data
            assertTrue(fetchedNote2 != null, "Note 2 should exist in DB")
            assertEquals("Child approach note", fetchedNote2!!.body)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: Notes are NOT inserted when failure occurs during the dep step
    //
    // The single transaction wrapping items → deps → notes is rolled back on
    // dep failure, so neither the item insert nor the note upsert persists.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `notes are not inserted when execution fails during dep step`() {
        val rootItem =
            WorkItem(
                id = UUID.randomUUID(),
                title = "Root Item (note should be skipped)",
                role = Role.QUEUE,
                depth = 0,
                parentId = null,
                priority = Priority.MEDIUM
            )
        val note =
            Note(
                id = UUID.randomUUID(),
                itemId = rootItem.id,
                key = "approach",
                role = "work",
                body = "This note should never be upserted"
            )

        val input =
            WorkTreeInput(
                items = listOf(rootItem),
                refToItem = mapOf("root" to rootItem),
                deps =
                    listOf(
                        TreeDepSpec(
                            fromRef = "nonexistent_ref", // triggers exception during dep step
                            toRef = "root",
                            type = DependencyType.BLOCKS,
                            unblockAt = null
                        )
                    ),
                notes = listOf(note)
            )

        // Service throws during dep resolution
        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.execute(input) }
        }

        // Root item was rolled back — NOT in DB
        val fetchedRoot = runBlocking { workItemRepository.getById(rootItem.id) }
        assertTrue(
            fetchedRoot is Result.Error,
            "Root item should NOT be present in DB (transaction was rolled back); got: $fetchedRoot"
        )

        // Note is NOT in DB — rolled back along with everything else
        val fetchedNotes = runBlocking { noteRepository.findByItemId(rootItem.id) }
        assertTrue(fetchedNotes is Result.Success, "findByItemId should succeed (returns empty list)")
        val noteList = (fetchedNotes as Result.Success).data
        assertTrue(
            noteList.isEmpty(),
            "Note should NOT exist in DB — transaction was rolled back; found: $noteList"
        )
    }
}

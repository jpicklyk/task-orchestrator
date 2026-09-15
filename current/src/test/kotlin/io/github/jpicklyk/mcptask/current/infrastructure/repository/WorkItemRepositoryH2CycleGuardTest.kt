package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.domain.model.AncestorChain
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle-guard regression tests for the H2 BFS fallback path on [SQLiteWorkItemRepository] —
 * the code that runs in this test environment because H2 gets neither the V7
 * `work_items_cycle_check*` triggers nor FTS5 (see
 * [DirectDatabaseSchemaManager.updateSchema]'s H2 dialect gate).
 *
 * Item 71bc3d09 test-plan (S2, S3b, S6-S9, S11b).
 *
 * The corruption seam here is [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.update]
 * itself: it writes `parent_id` verbatim, and [WorkItem.validate] never rejects `parentId == id`
 * or a mutual pair — so `repository.update(item.copy(parentId = ...))` alone is enough to
 * introduce a corrupt row on H2, unlike the SQLite suite which must first remove a DB trigger.
 *
 * S7 uses a different corruption: a domain-invalid row (blank title) in place of a missing one,
 * per the test-plan's "(or domain-invalid so `toWorkItemOrNull` drops it)" — a dangling
 * `parent_id` would violate the table's declared foreign key, so a domain-invalid *existing* row
 * is the only way to reproduce "ancestor absent from the caller's point of view" without also
 * exercising unrelated FK-violation behavior.
 *
 * Every scenario that can loop or cascade on a cyclic fixture carries a [Timeout].
 */
class WorkItemRepositoryH2CycleGuardTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "cycle_guard_h2_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        check(DirectDatabaseSchemaManager().updateSchema()) { "fixture setup: schema creation failed" }
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1
    ): WorkItem {
        val result = repository.create(WorkItem(title = title, parentId = parentId, depth = depth))
        assertIs<Result.Success<WorkItem>>(result, "fixture setup: failed to create '$title'")
        return result.data
    }

    /** Bypass [WorkItem.validate]'s title-blank rejection to make an existing row domain-invalid. */
    private fun corruptTitleBlank(itemId: UUID) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.title] = ""
            }
        }
    }

    // ── S2: self-parent, H2 BFS ─────────────────────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findDescendants on a self-parent cycle terminates with DatabaseError and accumulates nothing`(): Unit =
        runBlocking {
            val root = createItem("S2 root")
            val a = createItem("S2 child", parentId = root.id, depth = 1)

            val corrupted = repository.update(a.copy(parentId = a.id, depth = 1))
            assertIs<Result.Success<WorkItem>>(corrupted, "fixture setup: self-parent update must be accepted verbatim on H2")

            val result = repository.findDescendants(a.id)
            assertIs<Result.Error>(result, "expected the self-parent cycle to be reported, not hang or silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)

            // "Nothing accumulates" — the aborted walk must not have grown the table as a
            // side effect (pre-fix, an unbounded BFS could keep appending the same row).
            val countResult = repository.count()
            assertIs<Result.Success<Long>>(countResult)
            assertEquals(2L, countResult.data, "only root and the self-parent item should exist")
        }

    // ── S3b: two-node mutual cycle, H2 BFS ──────────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findDescendants on a two-node mutual cycle terminates with DatabaseError`(): Unit =
        runBlocking {
            val root = createItem("S3b root")
            val a = createItem("S3b A", parentId = root.id, depth = 1)
            val b = createItem("S3b B", parentId = a.id, depth = 2)

            // b.parent_id is already a.id; forcing a.parent_id := b.id closes the mutual cycle.
            val corrupted = repository.update(a.copy(parentId = b.id, depth = 1))
            assertIs<Result.Success<WorkItem>>(corrupted)

            val result = repository.findDescendants(a.id)
            assertIs<Result.Error>(result, "expected the mutual cycle to be reported, not hang or silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)
        }

    // ── S6: findAncestorChainsDetailed on a cycle reports truncated/"cycle" ─

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findAncestorChainsDetailed on a mutual cycle reports truncated with reason cycle`(): Unit =
        runBlocking {
            val a = createItem("S6 A")
            val b = createItem("S6 B")

            assertIs<Result.Success<WorkItem>>(repository.update(a.copy(parentId = b.id, depth = 1)))
            assertIs<Result.Success<WorkItem>>(repository.update(b.copy(parentId = a.id, depth = 1)))

            val detailed = repository.findAncestorChainsDetailed(setOf(a.id))
            assertIs<Result.Success<Map<UUID, AncestorChain>>>(detailed)
            val chain = detailed.data[a.id]
            assertTrue(chain != null, "chain entry must exist for the requested item")
            assertTrue(chain.truncated, "a cyclic ancestor walk must report truncated=true")
            assertEquals(AncestorChain.REASON_CYCLE, chain.truncationReason)

            // Legacy findAncestorChains is defined as findAncestorChainsDetailed with the flag
            // dropped — same ancestors, same order, for the same (still cyclic) fixture.
            val legacy = repository.findAncestorChains(setOf(a.id))
            assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(legacy)
            assertEquals(chain.ancestors.map { it.id }, legacy.data[a.id]?.map { it.id })
        }

    // ── S7: missing / domain-invalid ancestor ───────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findAncestorChainsDetailed on a domain-invalid ancestor reports truncated with reason missing-ancestor`(): Unit =
        runBlocking {
            val root = createItem("S7 root")
            val p = createItem("S7 P", parentId = root.id, depth = 1)
            val c = createItem("S7 C", parentId = p.id, depth = 2)

            // Corrupt P's row in place so it fails WorkItem.validate() on read — the row mapper
            // must drop it, making it indistinguishable from a genuinely absent ancestor.
            corruptTitleBlank(p.id)

            val detailed = repository.findAncestorChainsDetailed(setOf(c.id))
            assertIs<Result.Success<Map<UUID, AncestorChain>>>(detailed)
            val chain = detailed.data[c.id]
            assertTrue(chain != null, "chain entry must exist for the requested item")
            assertTrue(chain.truncated, "a missing/invalid ancestor must report truncated=true")
            assertEquals(AncestorChain.REASON_MISSING_ANCESTOR, chain.truncationReason)
            assertTrue(chain.ancestors.none { it.id == p.id }, "the domain-invalid ancestor must not appear in the chain")
        }

    // ── S8: control — valid 3-level chain reports truncated=false ──────────

    @Test
    fun `findAncestorChainsDetailed on a valid 3-level chain reports untruncated root-first ancestors`(): Unit =
        runBlocking {
            val root = createItem("S8 root")
            val middle = createItem("S8 middle", parentId = root.id, depth = 1)
            val leaf = createItem("S8 leaf", parentId = middle.id, depth = 2)

            val detailed = repository.findAncestorChainsDetailed(setOf(leaf.id))
            assertIs<Result.Success<Map<UUID, AncestorChain>>>(detailed)
            val chain = detailed.data[leaf.id]
            assertTrue(chain != null, "chain entry must exist for the requested item")
            assertFalse(chain.truncated, "a genuinely shallow chain must report truncated=false")
            assertNull(chain.truncationReason)
            assertEquals(2, chain.ancestors.size)
            assertEquals(root.id, chain.ancestors[0].id, "ancestors must be root-first")
            assertEquals(middle.id, chain.ancestors[1].id)
        }

    // ── S9: recomputeDescendantDepths does not spin on a cyclic fixture ────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `recomputeDescendantDepths on a self-parent cycle terminates with Error and writes nothing`(): Unit =
        runBlocking {
            val root = createItem("S9 root")
            val a = createItem("S9 child", parentId = root.id, depth = 1)
            assertIs<Result.Success<WorkItem>>(repository.update(a.copy(parentId = a.id, depth = 1)))

            val sibling = createItem("S9 sibling")
            val siblingBefore = repository.getById(sibling.id)
            assertIs<Result.Success<WorkItem>>(siblingBefore)
            val versionBefore = siblingBefore.data.version

            val result =
                ItemHierarchyValidator().recomputeDescendantDepths(
                    itemId = a.id,
                    delta = 1,
                    newRootId = UUID.randomUUID(),
                    repo = repository,
                )
            assertIs<Result.Error>(result, "the descendant fetch inside recompute must surface the cycle, not hang")

            val siblingAfter = repository.getById(sibling.id)
            assertIs<Result.Success<WorkItem>>(siblingAfter)
            assertEquals(versionBefore, siblingAfter.data.version, "an unrelated item must be untouched by the aborted cascade")
        }

    // ── S11b: H2 control — findInScope / countInScope visit each node once ──

    @Test
    fun `findInScope and countInScope on a branching non-cyclic tree visit each node exactly once`(): Unit =
        runBlocking {
            val root = createItem("S11b root")
            val childA = createItem("S11b A", parentId = root.id, depth = 1)
            val childB = createItem("S11b B", parentId = root.id, depth = 1)
            val grandA1 = createItem("S11b A1", parentId = childA.id, depth = 2)
            val grandA2 = createItem("S11b A2", parentId = childA.id, depth = 2)

            val expectedIds = setOf(root.id, childA.id, childB.id, grandA1.id, grandA2.id)

            val scoped = repository.findInScope(rootIds = setOf(root.id))
            assertIs<Result.Success<List<WorkItem>>>(scoped)
            assertEquals(expectedIds.size, scoped.data.size, "no node should be visited more than once")
            assertEquals(expectedIds, scoped.data.map { it.id }.toSet())

            val count = repository.countInScope(rootIds = setOf(root.id))
            assertIs<Result.Success<Int>>(count)
            assertEquals(expectedIds.size, count.data)
        }
}

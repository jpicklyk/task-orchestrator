package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.BaseFts5RepositoryTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cycle-guard regression tests for the SQLite recursive-CTE traversal paths on
 * [io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository]:
 * [WorkItemRepository.findDescendants], [WorkItemRepository.findInScope],
 * [WorkItemRepository.countInScope] and [WorkItemRepository.ftsSearch].
 *
 * Item 71bc3d09 test-plan (S1, S3a, S4, S5, S10, S11a). Extends [BaseFts5RepositoryTest],
 * whose hand-rolled schema already omits the V7 `work_items_cycle_check*` triggers — exactly
 * the "harness omits work_items_cycle_check*" seam the test-plan calls for — while still
 * providing the real FTS5 virtual tables S10 needs. [forceParentId] then writes a corrupt
 * `parent_id` directly (bypassing [WorkItem.validate], which never rejects `parentId == id`
 * or a mutual pair), simulating pre-guard / pre-V7 data.
 *
 * Every scenario that can loop on a cyclic fixture carries a [Timeout] so a regression that
 * removes the traversal bound fails the suite instead of hanging it. The bulk chain scenarios
 * (S4, S5) get a longer ceiling — that is a safety margin for sequential inserts, not a
 * performance target.
 *
 * Call-target note (documented per the test-authoring skill's ambiguity-arbitration step):
 * a pure self-parent or two-node mutual cycle, once formed, can never remain reachable as a
 * "descendant" of a separate, untouched root — the corrupted node's single `parent_id` slot is
 * consumed by the cycle, so it cannot simultaneously still point at that root. [findDescendants]
 * is therefore called directly on the corrupted node itself (matching the existing
 * `SQLiteWorkItemRepositoryAncestorCycleTest` precedent for the analogous ancestor-walk bug),
 * not on a disconnected seed root. This preserves the test-plan's scenario (self-parent /
 * mutual-cycle) and oracle (bounded `Result.Error` naming the bound) exactly; only the literal
 * call-target identifier was resolved this way, out of necessity.
 */
class SQLiteWorkItemRepositoryCycleGuardTest : BaseFts5RepositoryTest() {
    private val repository: WorkItemRepository by lazy { repositoryProvider.workItemRepository() }

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1
    ): WorkItem {
        val result = repository.create(WorkItem(title = title, parentId = parentId, depth = depth))
        assertIs<Result.Success<WorkItem>>(result, "fixture setup: failed to create '$title'")
        return result.data
    }

    /** Directly overwrite parent_id, bypassing [WorkItem.validate] and any DB trigger. */
    private fun forceParentId(
        itemId: UUID,
        newParentId: UUID
    ) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.parentId] = newParentId
            }
        }
    }

    // ── S1: self-parent, SQLite recursive CTE ──────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findDescendants on a self-parent cycle terminates with a bound-naming DatabaseError`(): Unit =
        runBlocking {
            val root = createItem("S1 root")
            val a = createItem("S1 child", parentId = root.id, depth = 1)

            // Corrupt: a.parent_id := a.id (self-reference), simulating pre-guard data.
            forceParentId(a.id, a.id)

            val result = repository.findDescendants(a.id)
            assertIs<Result.Error>(result, "expected the self-parent cycle to be reported, not hang or silently succeed")
            val error = result.error
            assertIs<RepositoryError.DatabaseError>(error)
            assertTrue(
                error.message.contains(MAX_TRAVERSAL_DEPTH.toString()),
                "expected the error to name the traversal bound ($MAX_TRAVERSAL_DEPTH), got: ${error.message}"
            )

            // Replay probe: the same corrupt fixture queried again must produce the same
            // kind of outcome, not flip between error/hang/success across calls.
            val replay = repository.findDescendants(a.id)
            assertIs<Result.Error>(replay)
            assertIs<RepositoryError.DatabaseError>(replay.error)
        }

    // ── S3a: two-node mutual cycle, SQLite recursive CTE ───────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findDescendants on a two-node mutual cycle terminates with a DatabaseError`(): Unit =
        runBlocking {
            val root = createItem("S3a root")
            val a = createItem("S3a A", parentId = root.id, depth = 1)
            val b = createItem("S3a B", parentId = a.id, depth = 2)

            // b.parent_id is already a.id; forcing a.parent_id := b.id closes the mutual cycle.
            forceParentId(a.id, b.id)

            val result = repository.findDescendants(a.id)
            assertIs<Result.Error>(result, "expected the mutual cycle to be reported, not hang or silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)
        }

    // ── S4: deep legitimate chain — the cap must not regress a real workload ──

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `findDescendants on a legitimate 200-node chain returns all 199 descendants`(): Unit =
        runBlocking {
            var parentId: UUID? = null
            var depth = 0
            var rootId: UUID? = null
            repeat(200) { i ->
                val item = createItem("S4 node $i", parentId = parentId, depth = depth)
                if (i == 0) rootId = item.id
                parentId = item.id
                depth++
            }

            val result = repository.findDescendants(rootId!!)
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(199, result.data.size, "root must be excluded from its own descendant list")
            assertEquals(
                199,
                result.data
                    .map { it.id }
                    .toSet()
                    .size,
                "no descendant should be reported twice"
            )
        }

    // ── S5: cap boundary — exactly MAX_TRAVERSAL_DEPTH nodes vs. one node over ──

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `findDescendants on a chain of exactly MAX_TRAVERSAL_DEPTH nodes succeeds`(): Unit =
        runBlocking {
            var parentId: UUID? = null
            var depth = 0
            var rootId: UUID? = null
            repeat(MAX_TRAVERSAL_DEPTH) { i ->
                val item = createItem("S5 at-cap node $i", parentId = parentId, depth = depth)
                if (i == 0) rootId = item.id
                parentId = item.id
                depth++
            }

            val result = repository.findDescendants(rootId!!)
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(MAX_TRAVERSAL_DEPTH - 1, result.data.size, "a chain of exactly the bound's node count must not be capped")
        }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `findDescendants on a chain of MAX_TRAVERSAL_DEPTH plus one node hits the cap`(): Unit =
        runBlocking {
            var parentId: UUID? = null
            var depth = 0
            var rootId: UUID? = null
            repeat(MAX_TRAVERSAL_DEPTH + 1) { i ->
                val item = createItem("S5 over-cap node $i", parentId = parentId, depth = depth)
                if (i == 0) rootId = item.id
                parentId = item.id
                depth++
            }

            val result = repository.findDescendants(rootId!!)
            assertIs<Result.Error>(result, "a chain one node over the bound must not silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)
        }

    // ── S10: ftsSearch subtree-scope CTE on a cycle (SQLite only — H2 has no FTS5) ──

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ftsSearch with an ancestor scope rooted at a cyclic node returns bounded results, not an error`(): Unit =
        runBlocking {
            val root = createItem("S10 root")
            val a = createItem("S10 A", parentId = root.id, depth = 1)
            val b = createItem("CycleScopeProbeQx19", parentId = a.id, depth = 2)

            // b.parent_id is already a.id; forcing a.parent_id := b.id closes the mutual cycle.
            // b is still a first-hop child of the scope root a, so a correct bounded walk must
            // still find it even though continuing the walk would otherwise loop forever.
            forceParentId(a.id, b.id)

            val result =
                repository.ftsSearch(
                    sanitizedFtsQuery = "CycleScopeProbeQx19",
                    scope = SearchScope(ancestorId = a.id),
                )

            assertTrue(
                result.hits.any { it.itemId == b.id },
                "expected the scoped search to still find the reachable descendant despite the cycle"
            )
        }

    // ── S11a: findInScope / countInScope on cyclic SQLite data ─────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findInScope on a cyclic root reports DatabaseError instead of hanging`(): Unit =
        runBlocking {
            val root = createItem("S11a root")
            val a = createItem("S11a A", parentId = root.id, depth = 1)
            val b = createItem("S11a B", parentId = a.id, depth = 2)
            forceParentId(a.id, b.id)

            val result = repository.findInScope(rootIds = setOf(a.id))
            assertIs<Result.Error>(result, "expected the cyclic scope to be reported, not hang or silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)
        }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `countInScope on a cyclic root reports DatabaseError instead of hanging`(): Unit =
        runBlocking {
            val root = createItem("S11a-count root")
            val a = createItem("S11a-count A", parentId = root.id, depth = 1)
            val b = createItem("S11a-count B", parentId = a.id, depth = 2)
            forceParentId(a.id, b.id)

            val result = repository.countInScope(rootIds = setOf(a.id))
            assertIs<Result.Error>(result, "expected the cyclic scope to be reported, not hang or silently succeed")
            assertIs<RepositoryError.DatabaseError>(result.error)
        }
}

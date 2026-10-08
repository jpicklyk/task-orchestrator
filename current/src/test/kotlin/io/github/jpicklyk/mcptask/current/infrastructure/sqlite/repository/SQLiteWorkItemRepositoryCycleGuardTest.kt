package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.application.port.SearchScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.domain.model.AncestorChain
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.sqlite.CycleTriggers
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle-guard regression tests for the SQLite recursive-CTE traversal paths on
 * [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository]:
 * [WorkItemRepository.findDescendants], [WorkItemRepository.findInScope],
 * [WorkItemRepository.countInScope] and [WorkItemRepository.ftsSearch].
 *
 * Item 71bc3d09 test-plan (S1, S3a, S4, S5, S10, S11a). Runs on a migrated SQLite database (SqliteTestDatabase); CycleTriggers drops the V7 `work_items_cycle_check*` triggers — exactly
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
class SQLiteWorkItemRepositoryCycleGuardTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = sqliteDb.repositoryProvider()
    private val database get() = sqliteDb.database

    private val repository: WorkItemRepository by lazy { repositoryProvider.workItemRepository() }

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1
    ): WorkItem {
        val result = repository.create(WorkItem(title = title, parentId = parentId, depth = depth))
        assertNotNull(result, "fixture setup: failed to create '$title'")
        return result
    }

    /** Directly overwrite parent_id, bypassing [WorkItem.validate] and any DB trigger. */
    private fun forceParentId(
        itemId: UUID,
        newParentId: UUID
    ) {
        // The V7 cycle triggers abort cycle writes on real SQLite; drop them to simulate pre-guard data.
        CycleTriggers.drop(database)
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

            val result =
                assertFailsWith<IllegalStateException>(
                    message = "expected the self-parent cycle to be reported, not hang or silently succeed"
                ) {
                    repository.findDescendants(a.id)
                }
            val error = result
            assertTrue(
                error.message.orEmpty().contains(MAX_TRAVERSAL_DEPTH.toString()),
                "expected the error to name the traversal bound ($MAX_TRAVERSAL_DEPTH), got: ${error.message}"
            )

            // Replay probe: the same corrupt fixture queried again must produce the same
            // kind of outcome, not flip between error/hang/success across calls.
            val replay = assertFailsWith<IllegalStateException> { repository.findDescendants(a.id) }
            assertIs<IllegalStateException>(replay)
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

            val result =
                assertFailsWith<IllegalStateException>(message = "expected the mutual cycle to be reported, not hang or silently succeed") {
                    repository.findDescendants(a.id)
                }
            assertIs<IllegalStateException>(result)
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
            assertNotNull(result)
            assertEquals(199, result.size, "root must be excluded from its own descendant list")
            assertEquals(
                199,
                result
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
            assertNotNull(result)
            assertEquals(MAX_TRAVERSAL_DEPTH - 1, result.size, "a chain of exactly the bound's node count must not be capped")
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

            val result =
                assertFailsWith<IllegalStateException>(message = "a chain one node over the bound must not silently succeed") {
                    repository.findDescendants(rootId!!)
                }
            assertIs<IllegalStateException>(result)
        }

    // ── S10: ftsSearch subtree-scope CTE on a cycle (SQLite only) ──

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

            val result =
                assertFailsWith<IllegalStateException>(message = "expected the cyclic scope to be reported, not hang or silently succeed") {
                    repository.findInScope(rootIds = setOf(a.id))
                }
            assertIs<IllegalStateException>(result)
        }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `countInScope on a cyclic root reports DatabaseError instead of hanging`(): Unit =
        runBlocking {
            val root = createItem("S11a-count root")
            val a = createItem("S11a-count A", parentId = root.id, depth = 1)
            val b = createItem("S11a-count B", parentId = a.id, depth = 2)
            forceParentId(a.id, b.id)

            val result =
                assertFailsWith<IllegalStateException>(message = "expected the cyclic scope to be reported, not hang or silently succeed") {
                    repository.countInScope(rootIds = setOf(a.id))
                }
            assertIs<IllegalStateException>(result)
        }

    // ── Ported from the retired in-memory cycle-guard suite (dialect-neutral scenarios, now on SQLite) ──

    /** Bypass [WorkItem.validate] title-blank rejection to make an existing row domain-invalid. */
    private fun corruptTitleBlank(itemId: UUID) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.title] = ""
            }
        }
    }

    // ── S6: findAncestorChainsDetailed on a cycle reports truncated/"cycle" ─

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findAncestorChainsDetailed on a mutual cycle reports truncated with reason cycle`(): Unit =
        runBlocking {
            // Both items are created at depth 1 (the retired in-memory original set depth = 1 in its update): a depth-0 row
            // with a parent would fail domain validation on read and be reported as a missing ancestor.
            val root = createItem("S6 root")
            val a = createItem("S6 A", parentId = root.id, depth = 1)
            val b = createItem("S6 B", parentId = root.id, depth = 1)

            forceParentId(a.id, b.id)
            forceParentId(b.id, a.id)

            val detailed = repository.findAncestorChainsDetailed(setOf(a.id))
            assertNotNull(detailed)
            val chain = detailed[a.id]
            assertTrue(chain != null, "chain entry must exist for the requested item")
            assertTrue(chain.truncated, "a cyclic ancestor walk must report truncated=true")
            assertEquals(AncestorChain.REASON_CYCLE, chain.truncationReason)

            // Legacy findAncestorChains is defined as findAncestorChainsDetailed with the flag
            // dropped - same ancestors, same order, for the same (still cyclic) fixture.
            val legacy = repository.findAncestorChains(setOf(a.id))
            assertNotNull(legacy)
            assertEquals(chain.ancestors.map { it.id }, legacy[a.id]?.map { it.id })
        }

    // ── S7: domain-invalid ancestor ─────────────────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `findAncestorChainsDetailed returns a domain-invalid ancestor with its diagnostics and does not truncate`(): Unit =
        runBlocking {
            val root = createItem("S7 root")
            val p = createItem("S7 P", parentId = root.id, depth = 1)
            val c = createItem("S7 C", parentId = p.id, depth = 2)

            // Corrupt P's row in place so it fails WorkItem.validate() on read. The row mapper is total: the
            // row is returned (with its violations in diagnostics), not dropped as if it were absent.
            corruptTitleBlank(p.id)

            val detailed = repository.findAncestorChainsDetailed(setOf(c.id))
            assertNotNull(detailed)
            val chain = detailed[c.id]
            assertTrue(chain != null, "chain entry must exist for the requested item")
            assertFalse(chain.truncated, "an invalid ancestor is still an ancestor: the walk reaches the root")
            assertNull(chain.truncationReason)
            assertEquals(listOf(root.id, p.id), chain.ancestors.map { it.id }, "root-first, including the invalid ancestor")
            val invalid = chain.ancestors.single { it.id == p.id }
            assertTrue(invalid.diagnostics.orEmpty().any { "Title must not be blank" in it }, "the violation rides on the row")
        }

    // ── S8: control - valid 3-level chain reports truncated=false ──────────

    @Test
    fun `findAncestorChainsDetailed on a valid 3-level chain reports untruncated root-first ancestors`(): Unit =
        runBlocking {
            val root = createItem("S8 root")
            val middle = createItem("S8 middle", parentId = root.id, depth = 1)
            val leaf = createItem("S8 leaf", parentId = middle.id, depth = 2)

            val detailed = repository.findAncestorChainsDetailed(setOf(leaf.id))
            assertNotNull(detailed)
            val chain = detailed[leaf.id]
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
            forceParentId(a.id, a.id)

            val sibling = createItem("S9 sibling")
            val siblingBefore = repository.getById(sibling.id)
            assertNotNull(siblingBefore)
            val versionBefore = siblingBefore.version

            val result =
                ItemHierarchyValidator().recomputeDescendantDepths(
                    itemId = a.id,
                    delta = 1,
                    newRootId = UUID.randomUUID(),
                    repo = repository,
                )
            assertNotNull(result, "the descendant fetch inside recompute must surface the cycle, not hang")

            val siblingAfter = repository.getById(sibling.id)
            assertNotNull(siblingAfter)
            assertEquals(versionBefore, siblingAfter.version, "an unrelated item must be untouched by the aborted cascade")
        }

    // ── S11b: control - findInScope / countInScope visit each node once ──

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
            assertNotNull(scoped)
            assertEquals(expectedIds.size, scoped.size, "no node should be visited more than once")
            assertEquals(expectedIds, scoped.map { it.id }.toSet())

            val count = repository.countInScope(rootIds = setOf(root.id))
            assertNotNull(count)
            assertEquals(expectedIds.size, count)
        }
}

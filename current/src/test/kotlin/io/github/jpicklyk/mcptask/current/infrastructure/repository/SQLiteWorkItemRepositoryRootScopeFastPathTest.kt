package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Root-scope fast-path tests for item `09205394` (root_id (V9) written but never used in any
 * WHERE clause).
 *
 * Item `09205394` test-plan, scenarios S1-S8. None of the seven consumer signatures changed
 * ([WorkItemRepository.findByRole], [WorkItemRepository.findForNextItem],
 * [WorkItemRepository.findClaimable], [WorkItemRepository.countByClaimStatus],
 * [WorkItemRepository.findInScope], [WorkItemRepository.countInScope],
 * [WorkItemRepository.countInScopeByRole]) -- the fix is a private guard inside
 * [SQLiteWorkItemRepository]: when every requested scope id is a genuine stamped depth-0 root
 * (`parent_id IS NULL AND root_id == id`), the query filters `root_id = ?` directly (O(1) binds
 * per root, not O(subtree)); otherwise it falls back to the existing recursive CTE, unchanged.
 *
 * Extends [SQLiteRepositoryTestBase] so the real Exposed `WorkItemsTable` (with the `root_id`
 * column and its index) backs every test via [SQLiteRepositoryTestBase.repositoryProvider] --
 * not a hand-rolled schema copy. A fixture must pass `rootId` explicitly on construction; the
 * `WorkItem` default (`rootId = null`) never reaches the fast path (item's diagnosis note, part D).
 *
 * S1 is the headline: a stamped root with 32,767 children, bulk-inserted by ONE raw
 * `WITH RECURSIVE` `INSERT ... SELECT` (not 32,767 repository calls) so the fixture itself stays
 * sub-second. Pre-fix, `countInScope`/`findInScope` on this fixture throw sqlite-jdbc's
 * "too many SQL variables" (`SQLITE_MAX_VARIABLE_NUMBER` = 32,766) as `Result.Error`; post-fix
 * they succeed because the fast path binds exactly one parameter regardless of subtree size.
 */
class SQLiteWorkItemRepositoryRootScopeFastPathTest : SQLiteRepositoryTestBase() {
    private val repository: WorkItemRepository by lazy { repositoryProvider.workItemRepository() }

    // ────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Creates a depth-0 item. When [stamped], `rootId` is its own id (fast-path eligible). */
    private fun createRoot(
        title: String,
        id: UUID = UUID.randomUUID(),
        stamped: Boolean = true,
    ): WorkItem =
        runBlocking {
            val result = repository.create(WorkItem(id = id, title = title, depth = 0, rootId = if (stamped) id else null))
            assertIs<Result.Success<WorkItem>>(result, "fixture setup: failed to create root '$title'")
            result.data
        }

    /**
     * Creates a child of [parent]. When [stamped], `rootId` is inherited via the same
     * `parent.rootId ?: parent.id` idiom the application layer uses on create (diagnosis note,
     * part D / CreateItemHandler.kt:94-104). When not [stamped], `rootId` stays null, simulating
     * a pre-backfill / directly-constructed row that never reaches the fast path.
     */
    private fun createChild(
        title: String,
        parent: WorkItem,
        depth: Int = parent.depth + 1,
        stamped: Boolean = true,
    ): WorkItem =
        runBlocking {
            val rootId = if (stamped) (parent.rootId ?: parent.id) else null
            val result = repository.create(WorkItem(title = title, parentId = parent.id, depth = depth, rootId = rootId))
            assertIs<Result.Success<WorkItem>>(result, "fixture setup: failed to create '$title'")
            result.data
        }

    /** Directly overwrites parent_id, bypassing [WorkItem.validate] -- forces a cycle for S8. */
    private fun forceParentId(
        itemId: UUID,
        newParentId: UUID,
    ) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.parentId] = newParentId
            }
        }
    }

    /** Big-endian 16-byte layout of a [UUID] (most-significant then least-significant long) --
     * the same byte order Exposed's `javaUUID` column writes/reads, per
     * `V9RootIdMigrationTest.uuidToBytes`. Used to embed a literal `X'...'` blob in raw SQL. */
    private fun uuidHex(id: UUID): String {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array().joinToString("") { "%02x".format(it) }
    }

    /**
     * Bulk-inserts [childCount] direct children of [root], all stamped `root_id = root.id`, in
     * ONE raw `WITH RECURSIVE` `INSERT ... SELECT` -- not one repository call per row, per the
     * test-plan's S1 recipe. `created_at`/`modified_at`/`role_changed_at` are embedded as
     * `java.sql.Timestamp.toString()` text (the format the bundled sqlite-jdbc driver round-trips
     * for TIMESTAMP columns -- a plain ISO-8601 or `datetime('now')` string does not parse back
     * through Exposed's mapper, per `V9RootIdMigrationTest.insertRawItem`'s comment). `role`,
     * `priority`, `summary`, `requires_verification` and `version` are left to their table
     * defaults, which match `WorkItem`'s own Kotlin defaults (QUEUE / MEDIUM / "" / false / 1).
     */
    private fun bulkInsertStampedChildren(
        root: WorkItem,
        childCount: Int,
    ) {
        val rootHex = uuidHex(root.id)
        val nowText = Timestamp.from(Instant.now()).toString()
        transaction(db = database) {
            exec(
                """
                WITH RECURSIVE gen(i) AS (
                    SELECT 1
                    UNION ALL
                    SELECT i + 1 FROM gen WHERE i < $childCount
                )
                INSERT INTO work_items (id, parent_id, root_id, title, depth, created_at, modified_at, role_changed_at)
                SELECT randomblob(16), X'$rootHex', X'$rootHex', 'S1-child-' || i, 1, '$nowText', '$nowText', '$nowText'
                FROM gen
                """.trimIndent(),
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S1 (happy, headline) -- 32,767-child stamped root: fast path binds O(1), not O(subtree)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `countInScope and findInScope succeed on a stamped root with 32767 children`(): Unit =
        runBlocking {
            val root = createRoot("S1 root")
            bulkInsertStampedChildren(root, 32_767)

            // Oracle: SQLITE_MAX_VARIABLE_NUMBER = 32,766 (sqlite-jdbc 3.49.1.0). Pre-fix, the
            // CTE-expanded `id IN (?,...)` binds 32,768 params here and throws "too many SQL
            // variables" -> Result.Error(DatabaseError). The fast path binds exactly 1.
            val countResult = repository.countInScope(rootIds = setOf(root.id))
            assertIs<Result.Success<Int>>(countResult, "expected Success, not a bound-overflow DatabaseError")
            assertEquals(32_768, countResult.data, "32,767 children + the root itself")

            val pageResult = repository.findInScope(rootIds = setOf(root.id), limit = 50)
            assertIs<Result.Success<List<WorkItem>>>(pageResult)
            assertEquals(50, pageResult.data.size)

            // Paging composes identically on the fast path (diagnosis: "ordering/limit/offset
            // /filters compose identically"): an offset near the tail returns just the remainder.
            val tailResult = repository.findInScope(rootIds = setOf(root.id), limit = 10, offset = 32_760)
            assertIs<Result.Success<List<WorkItem>>>(tailResult)
            assertEquals(8, tailResult.data.size, "32,768 total - 32,760 offset = 8 remaining")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S2 (happy, equivalence) -- fast path (stamped) vs. CTE fallback (legacy) agree, all 7 methods
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `stamped fast path and legacy CTE fallback agree across all seven scope-aware methods`(): Unit =
        runBlocking {
            // Stamped tree -- root_id set throughout -> fast path (root_id = R.id).
            val r = createRoot("S2 stamped root")
            val c1 = createChild("S2 c1", r)
            val c2 = createChild("S2 c2", r)
            createChild("S2 c3", r)
            createChild("S2 gc1", c1)
            createChild("S2 gc2", c2)
            val stampedIds =
                repository.findInScope(rootIds = setOf(r.id)).let {
                    assertIs<Result.Success<List<WorkItem>>>(it)
                    it.data
                        .map { w ->
                            w.id
                        }.toSet()
                }
            assertEquals(6, stampedIds.size, "fixture sanity: root + 3 children + 2 grandchildren")

            // Legacy tree, identical shape, root_id left NULL throughout -> CTE fallback
            // (diagnosis part D: a directly-constructed / unbackfilled item never reaches the
            // fast path; V9's comment: unbackfilled rows stay NULL and are equally unreachable
            // by the CTE from anywhere but their own root).
            val ru = createRoot("S2 legacy root", stamped = false)
            val cu1 = createChild("S2 legacy c1", ru, stamped = false)
            val cu2 = createChild("S2 legacy c2", ru, stamped = false)
            createChild("S2 legacy c3", ru, stamped = false)
            createChild("S2 legacy gc1", cu1, stamped = false)
            createChild("S2 legacy gc2", cu2, stamped = false)
            val legacyIds =
                repository.findInScope(rootIds = setOf(ru.id)).let {
                    assertIs<Result.Success<List<WorkItem>>>(it)
                    it.data
                        .map { w ->
                            w.id
                        }.toSet()
                }
            assertEquals(6, legacyIds.size, "fixture sanity: legacy root + 3 children + 2 grandchildren")

            val stampedByRole = repository.findByRole(role = Role.QUEUE, rootIds = setOf(r.id))
            val legacyByRole = repository.findByRole(role = Role.QUEUE, rootIds = setOf(ru.id))
            assertIs<Result.Success<List<WorkItem>>>(stampedByRole)
            assertIs<Result.Success<List<WorkItem>>>(legacyByRole)
            assertEquals(stampedIds, stampedByRole.data.map { it.id }.toSet(), "findByRole (fast path)")
            assertEquals(legacyIds, legacyByRole.data.map { it.id }.toSet(), "findByRole (CTE fallback)")

            val stampedNext = repository.findForNextItem(role = Role.QUEUE, rootIds = setOf(r.id))
            val legacyNext = repository.findForNextItem(role = Role.QUEUE, rootIds = setOf(ru.id))
            assertIs<Result.Success<List<WorkItem>>>(stampedNext)
            assertIs<Result.Success<List<WorkItem>>>(legacyNext)
            assertEquals(stampedIds, stampedNext.data.map { it.id }.toSet(), "findForNextItem (fast path)")
            assertEquals(legacyIds, legacyNext.data.map { it.id }.toSet(), "findForNextItem (CTE fallback)")

            val stampedClaimable = repository.findClaimable(role = Role.QUEUE, rootIds = setOf(r.id))
            val legacyClaimable = repository.findClaimable(role = Role.QUEUE, rootIds = setOf(ru.id))
            assertIs<Result.Success<List<WorkItem>>>(stampedClaimable)
            assertIs<Result.Success<List<WorkItem>>>(legacyClaimable)
            assertEquals(stampedIds, stampedClaimable.data.map { it.id }.toSet(), "findClaimable (fast path)")
            assertEquals(legacyIds, legacyClaimable.data.map { it.id }.toSet(), "findClaimable (CTE fallback)")

            val stampedClaimStatus = repository.countByClaimStatus(rootIds = setOf(r.id))
            val legacyClaimStatus = repository.countByClaimStatus(rootIds = setOf(ru.id))
            assertIs<Result.Success<ClaimStatusCounts>>(stampedClaimStatus)
            assertIs<Result.Success<ClaimStatusCounts>>(legacyClaimStatus)
            assertEquals(
                ClaimStatusCounts(active = 0, expired = 0, unclaimed = 6),
                stampedClaimStatus.data,
                "countByClaimStatus (fast path)"
            )
            assertEquals(
                ClaimStatusCounts(active = 0, expired = 0, unclaimed = 6),
                legacyClaimStatus.data,
                "countByClaimStatus (CTE fallback)"
            )

            val stampedCount = repository.countInScope(rootIds = setOf(r.id))
            val legacyCount = repository.countInScope(rootIds = setOf(ru.id))
            assertIs<Result.Success<Int>>(stampedCount)
            assertIs<Result.Success<Int>>(legacyCount)
            assertEquals(6, stampedCount.data, "countInScope (fast path)")
            assertEquals(6, legacyCount.data, "countInScope (CTE fallback)")

            val stampedByRoleCount = repository.countInScopeByRole(rootIds = setOf(r.id))
            val legacyByRoleCount = repository.countInScopeByRole(rootIds = setOf(ru.id))
            assertIs<Result.Success<Map<Role, Int>>>(stampedByRoleCount)
            assertIs<Result.Success<Map<Role, Int>>>(legacyByRoleCount)
            assertEquals(mapOf(Role.QUEUE to 6), stampedByRoleCount.data, "countInScopeByRole (fast path)")
            assertEquals(mapOf(Role.QUEUE to 6), legacyByRoleCount.data, "countInScopeByRole (CTE fallback)")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S3 (edge, below-root) -- scoping at a non-root stamped descendant stays on the CTE
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `scoping at a non-root stamped descendant still falls back to the CTE, unaffected by the fast path`(): Unit =
        runBlocking {
            val r = createRoot("S3 root")
            val b = createChild("S3 B", r)
            val bChild1 = createChild("S3 B child 1", b)
            val bChild2 = createChild("S3 B child 2", b)
            val sibling = createChild("S3 sibling", r)

            // B has parent_id = r.id (not NULL) -> guard fails -> CTE, exactly as before the fix.
            val result = repository.findInScope(rootIds = setOf(b.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertEquals(setOf(b.id, bChild1.id, bChild2.id), ids, "below-root scope: B and its own descendants only")
            assertTrue(r.id !in ids && sibling.id !in ids, "R and B's sibling must be excluded")

            val count = repository.countInScope(rootIds = setOf(b.id))
            assertIs<Result.Success<Int>>(count)
            assertEquals(3, count.data)
        }

    // ────────────────────────────────────────────────────────────────────────
    // S5 (failure, guard) -- a mixed root/non-root scope must fall back to the CTE union
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `mixed root and non-root ids in the same scope fall back to the CTE union, not just a subset`(): Unit =
        runBlocking {
            val r = createRoot("S5 R")
            val rChild = createChild("S5 R child", r)

            val r2 = createRoot("S5 R2")
            val r2Child = createChild("S5 R2 child", r2)
            val r2Grandchild = createChild("S5 R2 grandchild", r2Child)

            // rootIds = {R2 (a genuine stamped root), rChild (a depth-1 NON-root under R)}.
            // A wrongly-taken fast path would filter `root_id IN (R2.id, rChild.id)` -- but
            // rChild's OWN descendants (it has none here, but the mechanism matters) are stamped
            // root_id = R.id, not rChild.id, so a wrong fast path would drop them. The guard must
            // see rChild fails "parent_id IS NULL" and send the WHOLE call to the CTE union.
            val result = repository.findInScope(rootIds = setOf(r2.id, rChild.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertEquals(setOf(r2.id, r2Child.id, r2Grandchild.id, rChild.id), ids)
            assertTrue(r.id !in ids, "R itself was never in the requested scope")

            // Probe: same-tree overlap {R, child-of-R} -- subtree(rChild) is a subset of
            // subtree(R), so the CTE union must equal exactly subtree(R): no duplicates, nothing
            // dropped.
            val overlapResult = repository.findInScope(rootIds = setOf(r.id, rChild.id))
            assertIs<Result.Success<List<WorkItem>>>(overlapResult)
            assertEquals(setOf(r.id, rChild.id), overlapResult.data.map { it.id }.toSet())
        }

    // ────────────────────────────────────────────────────────────────────────
    // S6 (edge, reparent) -- the fast path follows a subtree moved to a different stamped root
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `reparenting a subtree onto a different stamped root moves it on the fast path`(): Unit =
        runBlocking {
            val validator = ItemHierarchyValidator()
            val r = createRoot("S6 R")
            val b = createChild("S6 B", r)
            val g = createChild("S6 B grandchild", b)

            val r2 = createRoot("S6 R2")

            // Reparent B under R2: same depth (1), new root -- mirrors UpdateItemHandler.kt:189 /
            // ItemHierarchyValidator.kt:97-118 (diagnosis part D / DECLARATIONS section A/D).
            val movedB = b.update { item -> item.copy(parentId = r2.id, depth = 1, rootId = r2.id) }
            val updateResult = repository.update(movedB)
            assertIs<Result.Success<WorkItem>>(updateResult)

            val cascadeResult = validator.recomputeDescendantDepths(b.id, 0, r2.id, repository)
            assertIs<Result.Success<Unit>>(cascadeResult)

            val underR2 = repository.findInScope(rootIds = setOf(r2.id))
            assertIs<Result.Success<List<WorkItem>>>(underR2)
            assertEquals(setOf(r2.id, b.id, g.id), underR2.data.map { it.id }.toSet(), "R2 must now own B and G")

            val underR = repository.findInScope(rootIds = setOf(r.id))
            assertIs<Result.Success<List<WorkItem>>>(underR)
            assertEquals(setOf(r.id), underR.data.map { it.id }.toSet(), "R must lose B and G after the move")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S7 (edge, isolation) -- two identically shaped stamped roots don't leak into each other
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `countInScopeByRole isolates one stamped root's tree from an identically shaped sibling root`(): Unit =
        runBlocking {
            val r1 = createRoot("S7 R1")
            createChild("S7 R1 child", r1)
            createChild("S7 R1 child 2", r1)

            val r2 = createRoot("S7 R2")
            createChild("S7 R2 child", r2)
            createChild("S7 R2 child 2", r2)

            val result = repository.countInScopeByRole(rootIds = setOf(r1.id))
            assertIs<Result.Success<Map<Role, Int>>>(result)
            assertEquals(mapOf(Role.QUEUE to 3), result.data, "R1 + its 2 children only; R2's identical tree excluded")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S8 (failure, declared delta) -- a cycle under a stamped root no longer surfaces as an error
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `a cycle beneath a stamped root returns rows on the fast path instead of a traversal DatabaseError`(): Unit =
        runBlocking {
            val r = createRoot("S8 R")
            val a = createChild("S8 A", r)
            val b = createChild("S8 B", a)

            // b.parent_id is already a.id; forcing a.parent_id := b.id closes a mutual cycle
            // beneath R (pattern: SQLiteWorkItemRepositoryCycleGuardTest's S11a). R itself is
            // untouched -- still a genuine stamped root.
            forceParentId(a.id, b.id)

            // Declared behaviour delta (diagnosis / DECLARATIONS section B): the fast path
            // filters `root_id = R.id` directly and does no parent_id traversal at all, so the
            // cyclic edge below R must not surface as a MAX_TRAVERSAL_DEPTH DatabaseError -- it
            // must return the (still root_id-stamped) rows.
            val result = repository.findInScope(rootIds = setOf(r.id))
            assertIs<Result.Success<List<WorkItem>>>(
                result,
                "fast path does no traversal; a cyclic parent_id edge below a stamped root must not error",
            )
            assertEquals(setOf(r.id, a.id, b.id), result.data.map { it.id }.toSet())
        }
}

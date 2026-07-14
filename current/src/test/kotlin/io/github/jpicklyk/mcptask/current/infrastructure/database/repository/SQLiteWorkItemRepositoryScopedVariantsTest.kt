package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the subtree-scoped (`rootIds`) variants of [SQLiteWorkItemRepository.findForNextItem],
 * [SQLiteWorkItemRepository.findClaimable], [SQLiteWorkItemRepository.findByRole], and
 * [SQLiteWorkItemRepository.countByClaimStatus].
 *
 * Follows the coverage pattern of `SQLiteWorkItemRepositoryFindInScopeTest`: sibling-root
 * isolation, unscoped (null) parity with pre-scoping behavior, empty-set scope yielding no
 * rows, and deep-tree (3+ levels) inclusion. Uses H2 (via [DirectDatabaseSchemaManager]) as
 * the rest of this package's repository tests do — the SQLite-vs-H2 CTE/BFS resolution paths
 * inside `resolveScopeIds` are already covered directly by `SQLiteWorkItemRepositoryFindInScopeTest`,
 * so these tests focus on scope composition with each method's own filter logic.
 */
class SQLiteWorkItemRepositoryScopedVariantsTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "scoped_variants_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun newItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = 0,
        role: Role = Role.QUEUE,
        claimed: Boolean = false,
        claimExpired: Boolean = false,
    ): WorkItem {
        val now = Instant.now()
        return if (claimed) {
            WorkItem(
                title = title,
                parentId = parentId,
                depth = depth,
                role = role,
                claimedBy = "agent-1",
                claimedAt = now.minusSeconds(60),
                originalClaimedAt = now.minusSeconds(60),
                claimExpiresAt = if (claimExpired) now.minusSeconds(1) else now.plusSeconds(3600),
            )
        } else {
            WorkItem(title = title, parentId = parentId, depth = depth, role = role)
        }
    }

    private fun create(item: WorkItem): WorkItem =
        runBlocking {
            val result = repository.create(item)
            assertIs<Result.Success<WorkItem>>(result, "Expected item '${item.title}' to be created")
            result.data
        }

    // ────────────────────────────────────────────────────────────────────────
    // findByRole
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findByRole unscoped (null) is unchanged - returns matching items across all trees`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.WORK))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.WORK))

            val result = repository.findByRole(role = Role.WORK, limit = 50)
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(c1.id in ids)
            assertTrue(c2.id in ids)
            assertEquals(2, result.data.size)
        }

    @Test
    fun `findByRole scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.WORK))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.WORK))

            val result = repository.findByRole(role = Role.WORK, limit = 50, rootIds = setOf(r1.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(c1.id in ids, "R1's WORK child must be included")
            assertTrue(c2.id !in ids, "R2's WORK child must be excluded when scoped to R1")
            assertEquals(1, result.data.size)
        }

    @Test
    fun `findByRole with empty rootIds returns nothing`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.WORK))

            val result = repository.findByRole(role = Role.WORK, limit = 50, rootIds = emptySet())
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty(), "Empty rootIds must yield no rows")
        }

    @Test
    fun `findByRole scoped includes deep-tree descendants (3+ levels)`(): Unit =
        runBlocking {
            val root = create(newItem("root"))
            val l1 = create(newItem("level-1", parentId = root.id, depth = 1))
            val l2 = create(newItem("level-2", parentId = l1.id, depth = 2))
            val l3 = create(newItem("level-3", parentId = l2.id, depth = 3, role = Role.WORK))

            val unrelatedRoot = create(newItem("unrelated"))
            create(newItem("unrelated-child", parentId = unrelatedRoot.id, depth = 1, role = Role.WORK))

            val result = repository.findByRole(role = Role.WORK, limit = 50, rootIds = setOf(root.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(l3.id in ids, "Depth-3 descendant must be included via deep-tree scoping")
            assertEquals(1, result.data.size)
        }

    // ────────────────────────────────────────────────────────────────────────
    // findForNextItem
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findForNextItem unscoped (null) is unchanged`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.QUEUE))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.QUEUE))

            val result = repository.findForNextItem(role = Role.QUEUE, excludeActiveClaims = false, limit = 50)
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(c1.id in ids)
            assertTrue(c2.id in ids)
        }

    @Test
    fun `findForNextItem scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.QUEUE))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.QUEUE))

            val result =
                repository.findForNextItem(
                    role = Role.QUEUE,
                    excludeActiveClaims = false,
                    limit = 50,
                    rootIds = setOf(r1.id),
                )
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(r1.id in ids, "R1 root (also QUEUE role) must be included")
            assertTrue(c1.id in ids)
            assertTrue(r2.id !in ids, "R2's root must be excluded when scoped to R1")
            assertTrue(c2.id !in ids, "R2's child must be excluded when scoped to R1")
            assertEquals(2, result.data.size, "R1 root + c1 = 2 (both QUEUE role)")
        }

    @Test
    fun `findForNextItem with empty rootIds returns nothing`(): Unit =
        runBlocking {
            create(newItem("root-1", role = Role.QUEUE))

            val result =
                repository.findForNextItem(
                    role = Role.QUEUE,
                    excludeActiveClaims = false,
                    limit = 50,
                    rootIds = emptySet(),
                )
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty())
        }

    @Test
    fun `findForNextItem scoped includes deep-tree descendants (3+ levels)`(): Unit =
        runBlocking {
            val root = create(newItem("root", role = Role.QUEUE))
            val l1 = create(newItem("level-1", parentId = root.id, depth = 1, role = Role.QUEUE))
            val l2 = create(newItem("level-2", parentId = l1.id, depth = 2, role = Role.QUEUE))
            val l3 = create(newItem("level-3", parentId = l2.id, depth = 3, role = Role.QUEUE))

            val unrelatedRoot = create(newItem("unrelated", role = Role.QUEUE))
            create(newItem("unrelated-child", parentId = unrelatedRoot.id, depth = 1, role = Role.QUEUE))

            val result =
                repository.findForNextItem(
                    role = Role.QUEUE,
                    excludeActiveClaims = false,
                    limit = 50,
                    rootIds = setOf(root.id),
                )
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(l3.id in ids, "Depth-3 descendant must be included via deep-tree scoping")
            assertEquals(4, result.data.size, "root + 3 levels = 4 items")
        }

    // ────────────────────────────────────────────────────────────────────────
    // findClaimable
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findClaimable unscoped (null) is unchanged`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.QUEUE))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.QUEUE))

            val result = repository.findClaimable(role = Role.QUEUE, limit = 50)
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(c1.id in ids)
            assertTrue(c2.id in ids)
        }

    @Test
    fun `findClaimable scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1, role = Role.QUEUE))
            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1, role = Role.QUEUE))

            val result = repository.findClaimable(role = Role.QUEUE, limit = 50, rootIds = setOf(r1.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(r1.id in ids, "R1 root (also QUEUE role) must be included")
            assertTrue(c1.id in ids)
            assertTrue(r2.id !in ids, "R2's root must be excluded when scoped to R1")
            assertTrue(c2.id !in ids, "R2's child must be excluded when scoped to R1")
            assertEquals(2, result.data.size, "R1 root + c1 = 2 (both QUEUE role)")
        }

    @Test
    fun `findClaimable with empty rootIds returns nothing`(): Unit =
        runBlocking {
            create(newItem("root-1", role = Role.QUEUE))

            val result = repository.findClaimable(role = Role.QUEUE, limit = 50, rootIds = emptySet())
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty())
        }

    @Test
    fun `findClaimable scoped includes deep-tree descendants (3+ levels)`(): Unit =
        runBlocking {
            val root = create(newItem("root", role = Role.QUEUE))
            val l1 = create(newItem("level-1", parentId = root.id, depth = 1, role = Role.QUEUE))
            val l2 = create(newItem("level-2", parentId = l1.id, depth = 2, role = Role.QUEUE))
            val l3 = create(newItem("level-3", parentId = l2.id, depth = 3, role = Role.QUEUE))

            val unrelatedRoot = create(newItem("unrelated", role = Role.QUEUE))
            create(newItem("unrelated-child", parentId = unrelatedRoot.id, depth = 1, role = Role.QUEUE))

            val result = repository.findClaimable(role = Role.QUEUE, limit = 50, rootIds = setOf(root.id))
            assertIs<Result.Success<List<WorkItem>>>(result)
            val ids = result.data.map { it.id }.toSet()
            assertTrue(l3.id in ids, "Depth-3 descendant must be included via deep-tree scoping")
            assertEquals(4, result.data.size, "root + 3 levels = 4 items")
        }

    // ────────────────────────────────────────────────────────────────────────
    // countByClaimStatus
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `countByClaimStatus unscoped (null) is unchanged`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            create(newItem("c1-active", parentId = r1.id, depth = 1, claimed = true))
            val r2 = create(newItem("root-2"))
            create(newItem("c2-active", parentId = r2.id, depth = 1, claimed = true))

            val result = repository.countByClaimStatus()
            assertIs<Result.Success<ClaimStatusCounts>>(result)
            assertEquals(2, result.data.active)
        }

    @Test
    fun `countByClaimStatus scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            create(newItem("c1-active", parentId = r1.id, depth = 1, claimed = true))
            create(newItem("c1-expired", parentId = r1.id, depth = 1, claimed = true, claimExpired = true))
            create(newItem("c1-unclaimed", parentId = r1.id, depth = 1))

            val r2 = create(newItem("root-2"))
            create(newItem("c2-active", parentId = r2.id, depth = 1, claimed = true))

            val result = repository.countByClaimStatus(rootIds = setOf(r1.id))
            assertIs<Result.Success<ClaimStatusCounts>>(result)
            assertEquals(1, result.data.active, "Only R1's active claim should be counted")
            assertEquals(1, result.data.expired)
            assertEquals(2, result.data.unclaimed, "R1 root (unclaimed) + c1-unclaimed = 2")
        }

    @Test
    fun `countByClaimStatus with empty rootIds returns all-zero counts`(): Unit =
        runBlocking {
            create(newItem("root-1"))
            create(newItem("c1-active", claimed = true))

            val result = repository.countByClaimStatus(rootIds = emptySet())
            assertIs<Result.Success<ClaimStatusCounts>>(result)
            assertEquals(0, result.data.active)
            assertEquals(0, result.data.expired)
            assertEquals(0, result.data.unclaimed)
        }

    @Test
    fun `countByClaimStatus scoped includes deep-tree descendants (3+ levels)`(): Unit =
        runBlocking {
            val root = create(newItem("root"))
            val l1 = create(newItem("level-1", parentId = root.id, depth = 1))
            val l2 = create(newItem("level-2", parentId = l1.id, depth = 2))
            create(newItem("level-3-active", parentId = l2.id, depth = 3, claimed = true))

            val unrelatedRoot = create(newItem("unrelated"))
            create(newItem("unrelated-active", parentId = unrelatedRoot.id, depth = 1, claimed = true))

            val result = repository.countByClaimStatus(rootIds = setOf(root.id))
            assertIs<Result.Success<ClaimStatusCounts>>(result)
            assertEquals(1, result.data.active, "Only the depth-3 claimed descendant within scope must be counted")
            assertEquals(3, result.data.unclaimed, "root + level-1 + level-2 = 3 unclaimed")
        }

    @Test
    fun `countByClaimStatus composes rootIds and parentId with AND`(): Unit =
        runBlocking {
            val root = create(newItem("root"))
            val directChildActive = create(newItem("direct-child-active", parentId = root.id, depth = 1, claimed = true))
            val grandchild = create(newItem("grandchild", parentId = directChildActive.id, depth = 2))
            create(newItem("grandchild-active", parentId = grandchild.id, depth = 3, claimed = true))

            // Scope to root's subtree AND require direct children of root only.
            val result = repository.countByClaimStatus(parentId = root.id, rootIds = setOf(root.id))
            assertIs<Result.Success<ClaimStatusCounts>>(result)
            assertEquals(1, result.data.active, "Only the direct child (not the deeper grandchild) should match parentId AND rootIds")
        }
}

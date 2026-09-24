package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wraps a real [ResourceLeaseRepository], failing [releaseAllForItem] with
 * [LeaseReleaseResult.DBError] for exactly one holder id; every other member delegates to
 * [delegate] unchanged. Mirrors [DeleteItemHandlerAtomicityTest]'s `FailOnIdWorkItemRepository`
 * seam, applied to the lease repository — the seam the test-plan names for this item.
 */
private class LeaseFailOnIdResourceLeaseRepository(
    private val delegate: ResourceLeaseRepository,
    private val failingHolderId: UUID,
) : ResourceLeaseRepository by delegate {
    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult =
        if (holderItemId == failingHolderId) {
            LeaseReleaseResult.DBError(RuntimeException("Simulated lease release failure for $holderItemId"))
        } else {
            delegate.releaseAllForItem(holderItemId)
        }
}

/**
 * Wraps a real [RepositoryProvider], substituting [failingLeaseRepo] for [resourceLeaseRepository].
 * Named distinctly from [DeleteItemHandlerAtomicityTest]'s own `FailOnIdRepositoryProvider` —
 * top-level classes collide by simple name within a package even when both are file-private.
 */
private class LeaseFailOnIdRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failingLeaseRepo: ResourceLeaseRepository,
) : RepositoryProvider by delegate {
    override fun resourceLeaseRepository(): ResourceLeaseRepository = failingLeaseRepo
}

/**
 * Independent test authorship for item 2cef6ca4 (needs-test-author) — MCP delete surface
 * (S4/S5/S7/S9/S10/S11 + probes). The REST surface (S6/S8) lives in
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ItemDeleteLeaseReleaseRouteTest].
 * Parser-level lifecycle scenarios (S1-S3) live in
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.LifecycleAutoReopenRemovalTest].
 *
 * Runs on real SQLite ([SQLiteRepositoryTestBase]) because the lease repository's SQL uses
 * `datetime()`, which the H2 database [DeleteItemHandlerAtomicityTest] uses does not support —
 * the harness the test-plan names for these scenarios.
 *
 * Oracles (frozen in test-plan note b9109cc0 / diagnosis note ca116121, before implementation was
 * read):
 *  [V16] `resource_lease_history` migration: every interval is closed exactly once, on release.
 *  [RK] `ResourceLeaseRepository`/`ResourceLeaseInterval` KDoc + close-reason vocabulary
 *       ("released", "expired") pinned by [io.github.jpicklyk.mcptask.current.infrastructure.database.repository.SQLiteResourceLeaseRepositoryHistoryTest].
 *  [AT] `DeleteItemHandler` KDoc / [DeleteItemHandlerAtomicityTest]: recursive delete is
 *       all-or-nothing per requested root id.
 *  [DX] diagnosis: release-before-delete happens inside the same transaction as the row delete;
 *       fail closed — a release DBError rolls back the whole subtree (recursive) or leaves a
 *       per-id failure with the row kept (non-recursive); a refused non-recursive delete (item
 *       has children) must NOT release the parent's lease.
 */
class DeleteItemLeaseReleaseTest : SQLiteRepositoryTestBase() {
    private val handler = DeleteItemHandler()
    private lateinit var context: ToolExecutionContext

    @org.junit.jupiter.api.BeforeEach
    fun setUpContext() {
        context = ToolExecutionContext(repositoryProvider)
    }

    private fun idsArray(vararg ids: UUID) = JsonArray(ids.map { JsonPrimitive(it.toString()) })

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = 0,
    ): WorkItem {
        val item = WorkItem(parentId = parentId, depth = depth, title = title)
        val result = repositoryProvider.workItemRepository().create(item)
        assertTrue(result is Result.Success, "fixture creation of '$title' failed: $result")
        return (result as Result.Success).data
    }

    private suspend fun exists(id: UUID): Boolean = repositoryProvider.workItemRepository().getById(id).let { it is Result.Success }

    private data class Tree(
        val root: WorkItem,
        val child: WorkItem,
        val grandchild: WorkItem,
    )

    private suspend fun threeLevelTree(): Tree {
        val root = createItem("Root")
        val child = createItem("Child", parentId = root.id, depth = 1)
        val grandchild = createItem("Grandchild", parentId = child.id, depth = 2)
        return Tree(root, child, grandchild)
    }

    /**
     * Backdates the lease for (resourceKey, holderItemId) to an already-expired expires_at in
     * BOTH the live table and the open history interval — mirrors
     * [io.github.jpicklyk.mcptask.current.infrastructure.database.repository.SQLiteResourceLeaseRepositoryHistoryTest]'s
     * `expireLease` helper (production keeps the two tables' expiry in agreement, so simulating
     * time-passage must age them together).
     */
    private fun expireLease(
        resourceKey: String,
        holderItemId: UUID,
    ) {
        transaction(db = database) {
            val uuidType = UUIDColumnType()
            val keyType = VarCharColumnType(255)
            exec(
                """
                UPDATE resource_leases
                   SET expires_at = datetime('now', '-10 seconds')
                 WHERE resource_key = ? AND holder_item_id = ?
                """.trimIndent(),
                args = listOf(keyType to resourceKey, uuidType to holderItemId),
            )
            exec(
                """
                UPDATE resource_lease_history
                   SET expires_at = datetime('now', '-10 seconds')
                 WHERE resource_key = ? AND holder_item_id = ? AND released_at IS NULL
                """.trimIndent(),
                args = listOf(keyType to resourceKey, uuidType to holderItemId),
            )
        }
    }

    // ──────────────────────────────────────────────
    // HAPPY
    // ──────────────────────────────────────────────

    @Test
    fun `S4 non-recursive delete of an item with an unexpired lease closes its interval as released`(): Unit =
        runBlocking {
            val item = createItem("Leased Leaf")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(item.id, "agent-a", listOf("k-s4" to 900)))

            val response = handler.execute(idsArray(item.id), false, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)

            val interval = leaseRepo.findRecentIntervals("k-s4", 10).single()
            assertEquals("released", interval.releaseReason)
            assertNotNull(interval.releasedAt)
        }

    @Test
    fun `S5 recursive delete of a three-level tree with distinct lease keys closes all three intervals`(): Unit =
        runBlocking {
            val tree = threeLevelTree()
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(tree.root.id, "agent-a", listOf("k-root" to 900)))
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(tree.child.id, "agent-a", listOf("k-child" to 900)))
            assertIs<LeaseAcquireResult.Success>(
                leaseRepo.acquireAll(tree.grandchild.id, "agent-a", listOf("k-grandchild" to 900)),
            )

            val response = handler.execute(idsArray(tree.root.id), true, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(3, data["deleted"]!!.jsonPrimitive.int)

            for (key in listOf("k-root", "k-child", "k-grandchild")) {
                val interval = leaseRepo.findRecentIntervals(key, 10).single()
                assertEquals("released", interval.releaseReason, "key $key must be closed")
                assertNotNull(interval.releasedAt, "key $key must have a releasedAt")
            }
        }

    // ──────────────────────────────────────────────
    // FAILURE — atomicity (AT, DX)
    // ──────────────────────────────────────────────

    @Test
    fun `S7 a lease-release DBError for the grandchild rolls back the whole recursive delete`(): Unit =
        runBlocking {
            val tree = threeLevelTree()
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(tree.root.id, "agent-a", listOf("k-root7" to 900)))
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(tree.child.id, "agent-a", listOf("k-child7" to 900)))
            assertIs<LeaseAcquireResult.Success>(
                leaseRepo.acquireAll(tree.grandchild.id, "agent-a", listOf("k-grandchild7" to 900)),
            )

            val failing = LeaseFailOnIdResourceLeaseRepository(leaseRepo, tree.grandchild.id)
            val failingContext = ToolExecutionContext(LeaseFailOnIdRepositoryProvider(repositoryProvider, failing))

            val response = handler.execute(idsArray(tree.root.id), true, failingContext) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            assertTrue(exists(tree.root.id), "root must remain — a release failure rolls back the whole subtree")
            assertTrue(exists(tree.child.id), "child must remain")
            assertTrue(exists(tree.grandchild.id), "grandchild must remain")

            for (key in listOf("k-root7", "k-child7", "k-grandchild7")) {
                val interval = leaseRepo.findRecentIntervals(key, 10).single()
                assertNull(interval.releaseReason, "key $key must remain open — rollback")
                assertNull(interval.releasedAt, "key $key must remain open — rollback")
            }
        }

    // ──────────────────────────────────────────────
    // EDGE
    // ──────────────────────────────────────────────

    @Test
    fun `S9 deleting A leaves unrelated holder Bs open interval untouched`(): Unit =
        runBlocking {
            val a = createItem("A")
            val b = createItem("B")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(a.id, "agent-a", listOf("k-a9" to 900)))
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(b.id, "agent-b", listOf("k-b9" to 900)))

            val response = handler.execute(idsArray(a.id), false, context) as JsonObject
            assertEquals(1, (response["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)

            val bInterval = leaseRepo.findRecentIntervals("k-b9", 10).single()
            assertNull(bInterval.releasedAt, "B's interval must stay open — A's delete must not touch it")
        }

    @Test
    fun `S9b deleting a lease-free item adds no history rows`(): Unit =
        runBlocking {
            val leaf = createItem("No Lease")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()

            val response = handler.execute(idsArray(leaf.id), false, context) as JsonObject
            assertEquals(1, (response["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)

            assertTrue(leaseRepo.findAllActive().isEmpty())
            assertTrue(
                leaseRepo.findRecentIntervals(null, 100).isEmpty(),
                "no lease history rows for an item that never held one",
            )
        }

    @Test
    fun `S10 non-recursive delete of an item holding an already-expired lease closes it with reason expired`(): Unit =
        runBlocking {
            val item = createItem("Expired Holder")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(item.id, "agent-a", listOf("k-s10" to 900)))
            expireLease("k-s10", item.id)

            val response = handler.execute(idsArray(item.id), false, context) as JsonObject
            assertEquals(1, (response["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)

            val interval = leaseRepo.findRecentIntervals("k-s10", 10).single()
            assertEquals("expired", interval.releaseReason)
            assertNotNull(interval.releasedAt)
        }

    /**
     * Guard scenario (test-plan S11): passes with or without the fix, since the refusal path
     * returns before any release call is reached regardless. Kept for regression coverage; no
     * red-proof is expected or required here (test-plan: "green pre-fix, reviewer accepts no-red").
     */
    @Test
    fun `S11 a non-recursive delete refused for having children does not release the parent's lease`(): Unit =
        runBlocking {
            val root = createItem("Parent")
            val child = createItem("Child", parentId = root.id, depth = 1)
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(root.id, "agent-a", listOf("k-s11" to 900)))

            val response = handler.execute(idsArray(root.id), false, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            assertTrue(exists(root.id))
            assertTrue(exists(child.id))

            val interval = leaseRepo.findRecentIntervals("k-s11", 10).single()
            assertNull(interval.releasedAt, "the refused parent's lease must remain open")
        }

    // ──────────────────────────────────────────────
    // ADVERSARIAL PROBES
    // ──────────────────────────────────────────────

    @Test
    fun `probe replay — deleting the same leased item twice reports not-found the second time and leaves releasedAt unchanged`(): Unit =
        runBlocking {
            val item = createItem("Replay Target")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(item.id, "agent-a", listOf("k-replay" to 900)))

            val first = handler.execute(idsArray(item.id), false, context) as JsonObject
            assertEquals(1, (first["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)
            val firstReleasedAt = leaseRepo.findRecentIntervals("k-replay", 10).single().releasedAt

            val second = handler.execute(idsArray(item.id), false, context) as JsonObject
            val secondData = second["data"] as JsonObject
            assertEquals(0, secondData["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, secondData["failed"]!!.jsonPrimitive.int)

            val secondReleasedAt = leaseRepo.findRecentIntervals("k-replay", 10).single().releasedAt
            assertEquals(firstReleasedAt, secondReleasedAt, "a not-found replay must not touch the already-closed interval")
        }

    @Test
    fun `probe duplicate id in the same batch releases the lease exactly once`(): Unit =
        runBlocking {
            val item = createItem("Duplicate Target")
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(leaseRepo.acquireAll(item.id, "agent-a", listOf("k-dup" to 900)))

            val response = handler.execute(idsArray(item.id, item.id), true, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            val intervals = leaseRepo.findRecentIntervals("k-dup", 10)
            assertEquals(1, intervals.size, "exactly one history row — no double release")
            assertEquals("released", intervals.single().releaseReason)
        }

    @Test
    fun `probe empty itemIds array touches no leases`(): Unit =
        runBlocking {
            val response = handler.execute(idsArray(), true, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            assertTrue(repositoryProvider.resourceLeaseRepository().findAllActive().isEmpty())
        }
}

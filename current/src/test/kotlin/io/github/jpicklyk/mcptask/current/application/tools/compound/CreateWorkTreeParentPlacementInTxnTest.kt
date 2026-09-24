package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `3da296d8` (needs-test-author): `create_work_tree`
 * create-mode and attach-mode reparent-in-transaction coverage — S6, S7, S11 of the frozen
 * `test-plan` note (queue phase, read before this file existed), plus a probe.
 *
 * Oracles:
 * - O1 `child.depth == parent.depth + 1`, `child.rootId == (parent.rootId ?: parent.id)`, parent
 *   (the create-mode `parentId` anchor, or the attach-mode `root.id` anchor) read AS PERSISTED at
 *   assert time. For create mode the new work-tree's own root derives its placement from the
 *   anchor exactly as `manage_items create` would; each child then derives from that (correctly
 *   live-derived) new root, an already-in-memory object — no second read is needed for children to
 *   inherit a correct value once the root itself is correct.
 * - O3 a missing anchor produces an error response (`success=false`, `error.message` contains
 *   "not found" — precedent `CreateWorkTreeToolTest` "nonexistent parentId returns error response" /
 *   "attach mode - missing root id returns RESOURCE_NOT_FOUND error"), and nothing is persisted.
 *
 * SEAM: same [MutateOnFirstTransactionRepository] technique as
 * `ManageItemsParentPlacementInTxnTest` (see that file's class KDoc for the `by delegate` /
 * `resolveChildPlacement` forwarding subtlety), injected through a [RepositoryProvider] wrapper
 * that overrides only `workItemRepository()` — `workTreeExecutor()` is left to delegate to the
 * REAL executor (matching `CreateWorkTreeToolIntegrationTest`'s H2 harness convention), so the
 * actual insert path under test is exercised for real, not mocked.
 *
 * BLINDNESS: authored from `diagnosis`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions in `CreateWorkTreeToolIntegrationTest.kt` / `CreateWorkTreeToolTest.kt` (read for
 * conventions only — not edited; declared as the orchestrator's file to repair). No `src/main`
 * file, diff, or commit was read.
 */
class CreateWorkTreeParentPlacementInTxnTest {
    /** See `ManageItemsParentPlacementInTxnTest.MutateOnFirstTransactionRepository` KDoc. */
    private class MutateOnFirstTransactionRepository(
        private val delegate: WorkItemRepository,
        private val mutate: suspend (WorkItemRepository) -> Unit
    ) : WorkItemRepository by delegate {
        private var hasFired = false

        override suspend fun inTransaction(block: suspend () -> Unit) {
            delegate.inTransaction {
                if (!hasFired) {
                    hasFired = true
                    mutate(delegate)
                }
                block()
            }
        }

        override suspend fun resolveChildPlacement(parentId: UUID): Result<ChildPlacement> =
            when (val parent = getById(parentId)) {
                is Result.Success ->
                    Result.Success(
                        ChildPlacement(
                            parentId = parent.data.id,
                            depth = parent.data.depth + 1,
                            rootId =
                                parent.data.rootId ?: parent.data.id
                        )
                    )
                is Result.Error -> Result.Error(parent.error)
            }
    }

    /** [RepositoryProvider] delegate substituting [workItemRepo] for `workItemRepository()`; every other accessor (notably `workTreeExecutor()`) forwards to [delegate] unchanged. */
    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private val tool = CreateWorkTreeTool()

    @BeforeEach
    fun setUp() {
        val dbName = "create_work_tree_placement_txn_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
    }

    private suspend fun create(item: WorkItem): WorkItem = (repositoryProvider.workItemRepository().create(item) as Result.Success).data

    private suspend fun stampSelfRoot(item: WorkItem): WorkItem =
        (repositoryProvider.workItemRepository().update(item.copy(rootId = item.id)) as Result.Success).data

    /** R (root, self-rooted) -> A (depth1) -> P (depth2); Q is a second, unrelated self-rooted root. */
    private data class Tree(
        val root: WorkItem,
        val a: WorkItem,
        val p: WorkItem,
        val q: WorkItem
    )

    private suspend fun threeLevelTreeWithAlternateRoot(): Tree {
        val root = stampSelfRoot(create(WorkItem(title = "R", depth = 0)))
        val a = create(WorkItem(title = "A", parentId = root.id, depth = 1, rootId = root.id))
        val p = create(WorkItem(title = "P", parentId = a.id, depth = 2, rootId = root.id))
        val q = stampSelfRoot(create(WorkItem(title = "Q", depth = 0)))
        return Tree(root, a, p, q)
    }

    private fun contextWith(workItemRepo: WorkItemRepository) =
        ToolExecutionContext(WorkItemRepoOverrideProvider(repositoryProvider, workItemRepo))

    // ─────────────────────────────────────────────────────────────────────────
    // S6 — create mode: root anchored at parentId=P, P concurrently reparented inside the txn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 create mode anchored at P reflects P's placement as of the write transaction, not a pre-transaction snapshot`() =
        runBlocking {
            val tree = threeLevelTreeWithAlternateRoot()
            val wrapped =
                MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d ->
                    d.update(tree.p.copy(parentId = tree.q.id, depth = 1, rootId = tree.q.id))
                }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("New Root S6")) })
                    put("parentId", JsonPrimitive(tree.p.id.toString()))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Child S6"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            val rootJson = data["root"] as JsonObject
            assertEquals(2, rootJson["depth"]!!.jsonPrimitive.int, "O1: new root must be at P's LIVE depth (1) + 1")

            val childJson = (data["children"] as JsonArray)[0].jsonObject
            assertEquals(3, childJson["depth"]!!.jsonPrimitive.int, "child derives from the (correctly live) new root's own depth")

            // O1 is a property of the PERSISTED row (test-plan: "P as PERSISTED at assert time");
            // the create_work_tree response item does not carry a rootId field (arbitration,
            // 3da296d8), so rootId is read back through the UNDERLYING repository instead.
            val rootId = UUID.fromString(rootJson["id"]!!.jsonPrimitive.content)
            val persistedRoot = repositoryProvider.workItemRepository().getById(rootId)
            assertTrue(persistedRoot is Result.Success)
            assertEquals(2, (persistedRoot as Result.Success).data.depth)
            assertEquals(tree.q.id, persistedRoot.data.rootId, "O1: new root must inherit P's LIVE rootId (Q)")

            val childId = UUID.fromString(childJson["id"]!!.jsonPrimitive.content)
            val persistedChild = repositoryProvider.workItemRepository().getById(childId)
            assertTrue(persistedChild is Result.Success)
            assertEquals(tree.q.id, (persistedChild as Result.Success).data.rootId)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S7 — attach mode: root.id=A (existing item), A concurrently reparented inside the txn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 attach mode under A reflects A's placement as of the write transaction, not a pre-transaction snapshot`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S7", depth = 0)))
            val a = create(WorkItem(title = "A S7", parentId = root.id, depth = 1, rootId = root.id))
            val q = stampSelfRoot(create(WorkItem(title = "Q S7", depth = 0)))
            val wrapped =
                MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d ->
                    d.update(a.copy(parentId = q.id, depth = 1, rootId = q.id))
                }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(a.id.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Child S7"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            val childJson = (data["children"] as JsonArray)[0].jsonObject
            assertEquals(2, childJson["depth"]!!.jsonPrimitive.int, "O1: child must be at A's LIVE depth (1) + 1")

            // rootId is not carried on the response item (arbitration, 3da296d8) — read the
            // persisted row through the underlying repository instead. depth alone (1+1=2) is
            // coincidentally the same pre- and post-mutation here, so rootId is the discriminating
            // assertion for this scenario.
            val childId = UUID.fromString(childJson["id"]!!.jsonPrimitive.content)
            val persistedChild = repositoryProvider.workItemRepository().getById(childId)
            assertTrue(persistedChild is Result.Success)
            assertEquals(
                q.id,
                (persistedChild as Result.Success).data.rootId,
                "O1: child must inherit A's LIVE rootId (Q), not the pre-transaction rootId (R)"
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S11 — create mode anchored at P, P concurrently DELETED inside the write transaction (O3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S11 create mode anchored at P whose parent is deleted inside the write transaction fails without persisting anything`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S11", depth = 0)))
            val p = create(WorkItem(title = "P S11 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id))
            val wrapped = MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d -> d.delete(p.id) }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Orphan Root S11")) })
                    put("parentId", JsonPrimitive(p.id.toString()))
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject

            assertTrue(!result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val errorMsg = result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(errorMsg.contains("not found"), "O3: actual: $errorMsg")

            val all = repositoryProvider.workItemRepository().findByFilters()
            assertTrue(all is Result.Success)
            val orphan = (all as Result.Success).data.items.filter { it.title == "Orphan Root S11" }
            assertTrue(orphan.isEmpty(), "O3: a parent deleted inside the write transaction must leave NO orphan root row: $orphan")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Adversarial probe: replay — a second, independent work tree anchored at the same live P
    // still gets correct placement (no state leaked across calls)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe replay — two independent work trees anchored at the same parent both get correct, matching placement`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R Replay", depth = 0)))
            val p = create(WorkItem(title = "P Replay", parentId = root.id, depth = 1, rootId = root.id))
            val context = ToolExecutionContext(repositoryProvider)

            fun params(title: String) =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive(title)) })
                    put("parentId", JsonPrimitive(p.id.toString()))
                }

            val first = tool.execute(params("Replay Root 1"), context) as JsonObject
            val second = tool.execute(params("Replay Root 2"), context) as JsonObject

            for (r in listOf(first, second)) {
                val rootJson = (r["data"] as JsonObject)["root"] as JsonObject
                assertEquals(2, rootJson["depth"]!!.jsonPrimitive.int, "actual: $r")
                // rootId is not carried on the response item (arbitration, 3da296d8) — read the
                // persisted row through the underlying repository instead.
                val newRootId = UUID.fromString(rootJson["id"]!!.jsonPrimitive.content)
                val persisted = repositoryProvider.workItemRepository().getById(newRootId)
                assertTrue(persisted is Result.Success)
                assertEquals(root.id, (persisted as Result.Success).data.rootId, "actual: $r")
            }
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix, alternate separators, encoded/UNC forms, mixed case: N/A - anchors are
    //   UUIDs identifying existing rows, not a path/string surface.
    // - empty vs absent vs null: not re-authored here — pre-existing `CreateWorkTreeToolTest`
    //   coverage already exercises no-anchor (depth 0) creation, unaffected by this fix.
    // - duplicates/ordering: N/A for this item's scope (multi-child depth/rootId derivation from a
    //   single, already-in-memory correct root is not specific to this fix).
    // - replay/idempotency: covered above ("probe replay...").
}

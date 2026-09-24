package io.github.jpicklyk.mcptask.current.application.tools.items

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `3da296d8` (needs-test-author): `manage_items`
 * create/update reparent-in-transaction coverage — S2, S3, S9, S12, S13-S16 of the frozen
 * `test-plan` note (queue phase, read before this file existed), plus adversarial probes.
 *
 * Oracles (test-plan / diagnosis, both frozen before implementation was read):
 * - O1 `child.depth == parent.depth + 1` and `child.rootId == (parent.rootId ?: parent.id)`,
 *   the parent read AS PERSISTED at assert time.
 * - O2 the parent read and the stamped write happen in the SAME `inTransaction` block
 *   (diagnosis "Chosen (e)": `resolveChildPlacement` "MUST be called inside `inTransaction` with
 *   the write it stamps").
 * - O3 a parent that no longer exists by write time resolves to the path's existing
 *   parent-not-found failure entry, and no row is written for that item.
 *
 * SEAM (test-plan "Seam W", adapted to the actual call surface): `WorkItemRepository by delegate`,
 * overriding `inTransaction` to run a one-shot mutation of the watched parent (a concurrent
 * reparent, via [MutateOnFirstTransactionRepository]) as the FIRST thing inside the real
 * transaction — before the wrapped block (which contains whatever `resolveChildPlacement`/`create`/
 * `update` calls the operation under test makes) runs. This reproduces the diagnosis's
 * interleaving precisely: a placement READ taken before this `inTransaction` call even begins
 * (the pre-fix code path, which reads the parent during validation, in a separate transaction)
 * observes the OLD parent state; a placement read taken INSIDE this `inTransaction` call (the
 * fix) observes the mutation that "landed" the instant the transaction opened. Kotlin's `by`
 * delegation forwards a default-bodied interface member (`resolveChildPlacement`) to the DELEGATE
 * as a whole rather than re-dispatching through this wrapper's own overrides, so where a scenario
 * needs the mutation visible specifically through `resolveChildPlacement`'s own internal read,
 * that method is also overridden directly (see [MutateOnFirstTransactionRepository] KDoc).
 *
 * Fixtures are built through the PLAIN (unwrapped) `repositoryProvider` so the wrapper's one-shot
 * hook fires only during the operation actually under test, never during setup.
 *
 * NEW-SURFACE narrowest-revert recipe (S12, S13): keep `ChildPlacement`/`resolveChildPlacement`
 * defined; revert only `CreateItemHandler`/`UpdateItemHandler`'s call sites back to computing
 * depth/rootId via a plain `getById` at validation time, outside any `inTransaction` block. Under
 * that revert, `resolveChildPlacement` is never invoked, which is a behavioral (not compile) red
 * for S12/S13's `resolveChildPlacementOrdinal`/`placementCalls` assertions.
 *
 * BLINDNESS: authored from `diagnosis`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and the
 * existing H2 harness conventions in `ManageItemsToolTest.kt` / `DeleteItemHandlerAtomicityTest.kt`.
 * No `src/main` file, diff, or commit was read.
 */
class ManageItemsParentPlacementInTxnTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private val tool = ManageItemsTool()

    @BeforeEach
    fun setUp() {
        val dbName = "manage_items_placement_txn_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
    }

    // ──────────────────────────────────────────────
    // Test-only seams
    // ──────────────────────────────────────────────

    /**
     * Wraps a real [WorkItemRepository]. The FIRST time [inTransaction] is entered, runs [mutate]
     * against [delegate] (bypassing any interception) before running the caller's `block` — placing
     * a "concurrent" write immediately at transaction-open time, before any read the wrapped `block`
     * performs. Every other member delegates to [delegate] unchanged, EXCEPT [resolveChildPlacement],
     * which is re-implemented here to call `this.getById` rather than `delegate.getById` — Kotlin's
     * `by delegate` clause forwards a default-bodied interface member to the delegate as a single
     * unit, so without this override a call to `resolveChildPlacement` would bypass this wrapper
     * entirely and read through [delegate] directly, defeating the seam.
     */
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

    /**
     * Wraps a real [WorkItemRepository], recording — for [resolveChildPlacement], [create], and
     * [update] — the ordinal of the top-level [inTransaction] call each was invoked under. Ordinals
     * increment only on entry from depth 0 (a genuinely new top-level transaction); a NESTED
     * `inTransaction` re-entry (e.g. `create`'s own internal wrapping while already inside the
     * caller's transaction) keeps the same ordinal. Proves O2: `resolveChildPlacement` and the
     * write it feeds must share one ordinal.
     */
    private class InTransactionOrdinalSpy(
        private val delegate: WorkItemRepository
    ) : WorkItemRepository by delegate {
        private var depth = 0
        private var instanceCounter = 0
        var topLevelEntryCount: Int = 0
            private set
        var resolveChildPlacementOrdinal: Int? = null
            private set
        var createOrdinal: Int? = null
            private set
        var updateOrdinal: Int? = null
            private set

        override suspend fun inTransaction(block: suspend () -> Unit) {
            if (depth == 0) {
                instanceCounter++
                topLevelEntryCount++
            }
            depth++
            try {
                delegate.inTransaction { block() }
            } finally {
                depth--
            }
        }

        override suspend fun resolveChildPlacement(parentId: UUID): Result<ChildPlacement> {
            resolveChildPlacementOrdinal = instanceCounter
            return delegate.resolveChildPlacement(parentId)
        }

        override suspend fun create(item: WorkItem): Result<WorkItem> {
            createOrdinal = instanceCounter
            return delegate.create(item)
        }

        override suspend fun update(item: WorkItem): Result<WorkItem> {
            updateOrdinal = instanceCounter
            return delegate.update(item)
        }
    }

    /** [RepositoryProvider] delegate substituting [workItemRepo] for `workItemRepository()`. */
    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    // ──────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────

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

    private fun mutateOnFirstTxn(mutate: suspend (WorkItemRepository) -> Unit) =
        MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository(), mutate)

    private fun contextWith(workItemRepo: WorkItemRepository) =
        ToolExecutionContext(WorkItemRepoOverrideProvider(repositoryProvider, workItemRepo))

    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    private fun createParams(
        title: String,
        parentId: UUID
    ) = params(
        "operation" to JsonPrimitive("create"),
        "items" to
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive(title))
                        put("parentId", JsonPrimitive(parentId.toString()))
                    }
                )
            )
    )

    private fun updateParentParams(
        itemId: UUID,
        parentId: UUID?
    ) = params(
        "operation" to JsonPrimitive("update"),
        "items" to
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        if (parentId !=
                            null
                        ) {
                            put("parentId", JsonPrimitive(parentId.toString()))
                        } else {
                            put("parentId", kotlinx.serialization.json.JsonNull)
                        }
                    }
                )
            )
    )

    // ──────────────────────────────────────────────
    // S2 — create under P while P is concurrently reparented (create path)
    // ──────────────────────────────────────────────

    @Test
    fun `S2 create under P reflects P's placement as of the write transaction, not a pre-transaction snapshot`() =
        runBlocking {
            val tree = threeLevelTreeWithAlternateRoot()
            val wrapped =
                mutateOnFirstTxn { d ->
                    // "Concurrent" reparent of P: moves from R (depth2) to directly under Q (depth1).
                    d.update(tree.p.copy(parentId = tree.q.id, depth = 1, rootId = tree.q.id))
                }

            val result = tool.execute(createParams("Child of P", tree.p.id), contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            assertEquals(1, data["created"]!!.jsonPrimitive.int, "actual: $result")
            val child = data["items"]!!.jsonArray[0] as JsonObject
            assertEquals(2, child["depth"]!!.jsonPrimitive.int, "O1: must be P's LIVE depth (1) + 1, not the pre-transaction depth (2) + 1")
            assertEquals(
                tree.q.id.toString(),
                child["rootId"]?.jsonPrimitive?.content,
                "O1: must be P's LIVE rootId (Q), not the pre-transaction rootId (R)"
            )
        }

    // ──────────────────────────────────────────────
    // S3 — update/reparent X under P while P is concurrently reparented
    // ──────────────────────────────────────────────

    @Test
    fun `S3 reparenting X under P reflects P's placement as of the write transaction, not a pre-transaction snapshot`() =
        runBlocking {
            val tree = threeLevelTreeWithAlternateRoot()
            val x = stampSelfRoot(create(WorkItem(title = "X", depth = 0)))
            val wrapped =
                mutateOnFirstTxn { d ->
                    d.update(tree.p.copy(parentId = tree.q.id, depth = 1, rootId = tree.q.id))
                }

            val result = tool.execute(updateParentParams(x.id, tree.p.id), contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals(1, (result["data"] as JsonObject)["updated"]!!.jsonPrimitive.int)

            val persisted = (repositoryProvider.workItemRepository().getById(x.id) as Result.Success).data
            assertEquals(2, persisted.depth, "O1: X must land at P's LIVE depth (1) + 1")
            assertEquals(tree.q.id, persisted.rootId, "O1: X must inherit P's LIVE rootId (Q)")
        }

    // ──────────────────────────────────────────────
    // S9 — create under P while P is concurrently DELETED (failure path, O3)
    // ──────────────────────────────────────────────

    @Test
    fun `S9 create under P whose parent is deleted inside the write transaction fails without an orphan row`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S9", depth = 0)))
            val p = create(WorkItem(title = "P S9 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id))
            val wrapped = mutateOnFirstTxn { d -> d.delete(p.id) }

            val result = tool.execute(createParams("Orphan Child S9", p.id), contextWith(wrapped)) as JsonObject

            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "top-level manage_items call still reports success=true envelope: $result"
            )
            val data = result["data"] as JsonObject
            assertEquals(0, data["created"]!!.jsonPrimitive.int, "actual: $result")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int, "actual: $result")
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"), "actual: $failure")

            val all = repositoryProvider.workItemRepository().findByFilters()
            assertTrue(all is Result.Success)
            val orphan = (all as Result.Success).data.items.filter { it.title == "Orphan Child S9" }
            assertTrue(orphan.isEmpty(), "O3: a parent deleted inside the write transaction must leave NO orphan row: $orphan")
        }

    // ──────────────────────────────────────────────
    // S12 — O2: resolveChildPlacement and the write it feeds share one inTransaction ordinal
    // ──────────────────────────────────────────────

    @Test
    fun `S12a create under an existing parent resolves placement and writes inside the same inTransaction call`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S12a", depth = 0)))
            val p = create(WorkItem(title = "P S12a", parentId = root.id, depth = 1, rootId = root.id))
            val spy = InTransactionOrdinalSpy(repositoryProvider.workItemRepository())

            val result = tool.execute(createParams("Child S12a", p.id), contextWith(spy)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertNotNull(spy.resolveChildPlacementOrdinal, "O2/NEW-SURFACE: resolveChildPlacement must be called for a parented create")
            assertNotNull(spy.createOrdinal)
            assertEquals(
                spy.resolveChildPlacementOrdinal,
                spy.createOrdinal,
                "O2: the placement read and the write must share the SAME top-level inTransaction call"
            )
        }

    @Test
    fun `S12b reparenting X under P resolves placement and writes inside the same inTransaction call`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S12b", depth = 0)))
            val p = create(WorkItem(title = "P S12b", parentId = root.id, depth = 1, rootId = root.id))
            val x = stampSelfRoot(create(WorkItem(title = "X S12b", depth = 0)))
            val spy = InTransactionOrdinalSpy(repositoryProvider.workItemRepository())

            val result = tool.execute(updateParentParams(x.id, p.id), contextWith(spy)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertNotNull(spy.resolveChildPlacementOrdinal, "O2/NEW-SURFACE: resolveChildPlacement must be called for a reparent")
            assertNotNull(spy.updateOrdinal)
            assertEquals(
                spy.resolveChildPlacementOrdinal,
                spy.updateOrdinal,
                "O2: the placement read and the write must share the SAME top-level inTransaction call"
            )
        }

    // ──────────────────────────────────────────────
    // S13 — edge: root create (no parent) never calls resolveChildPlacement
    // ──────────────────────────────────────────────

    @Test
    fun `S13 root create with no parentId is depth 0, rootId self, and never calls resolveChildPlacement`() =
        runBlocking {
            val spy = InTransactionOrdinalSpy(repositoryProvider.workItemRepository())
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to JsonArray(listOf(buildJsonObject { put("title", JsonPrimitive("Root S13")) }))
                    ),
                    contextWith(spy)
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val item = (result["data"] as JsonObject)["items"]!!.jsonArray[0] as JsonObject
            assertEquals(0, item["depth"]!!.jsonPrimitive.int)
            assertEquals(item["id"]!!.jsonPrimitive.content, item["rootId"]?.jsonPrimitive?.content)
            assertEquals(null, spy.resolveChildPlacementOrdinal, "a parentless create must never resolve a child placement")
        }

    // ──────────────────────────────────────────────
    // S14 — edge: legacy parent with no stamped rootId
    // ──────────────────────────────────────────────

    @Test
    fun `S14 create under a legacy parent with no stamped rootId falls back to the parent's own id`() =
        runBlocking {
            val legacyParent = create(WorkItem(title = "Legacy Parent S14", depth = 0))
            assertNull(legacyParent.rootId, "fixture precondition: no stamped rootId")

            val result = tool.execute(createParams("Child S14", legacyParent.id), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val child = (result["data"] as JsonObject)["items"]!!.jsonArray[0] as JsonObject
            assertEquals(legacyParent.id.toString(), child["rootId"]?.jsonPrimitive?.content, "O1 fallback: rootId ?: id")
        }

    // ──────────────────────────────────────────────
    // S15 — edge: update parentId to null moves to root
    // ──────────────────────────────────────────────

    @Test
    fun `S15 updating parentId to null moves the item to root with depth 0 and rootId self`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S15", depth = 0)))
            val x = create(WorkItem(title = "X S15", parentId = root.id, depth = 1, rootId = root.id))

            val result = tool.execute(updateParentParams(x.id, null), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val persisted = (repositoryProvider.workItemRepository().getById(x.id) as Result.Success).data
            assertEquals(null, persisted.parentId)
            assertEquals(0, persisted.depth)
            assertEquals(x.id, persisted.rootId)
        }

    // ──────────────────────────────────────────────
    // S16 — edge: same-depth move to another root restamps rootId on item and descendant
    // ──────────────────────────────────────────────

    @Test
    fun `S16 moving X to a different root restamps rootId on X and its descendant`() =
        runBlocking {
            val rootA = stampSelfRoot(create(WorkItem(title = "RootA S16", depth = 0)))
            val x = create(WorkItem(title = "X S16", parentId = rootA.id, depth = 1, rootId = rootA.id))
            val y = create(WorkItem(title = "Y S16 (child of X)", parentId = x.id, depth = 2, rootId = rootA.id))
            val rootB = stampSelfRoot(create(WorkItem(title = "RootB S16", depth = 0)))

            val result = tool.execute(updateParentParams(x.id, rootB.id), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val persistedX = (repositoryProvider.workItemRepository().getById(x.id) as Result.Success).data
            val persistedY = (repositoryProvider.workItemRepository().getById(y.id) as Result.Success).data
            assertEquals(rootB.id, persistedX.rootId, "X must be restamped to RootB")
            assertEquals(rootB.id, persistedY.rootId, "Y (X's descendant) must cascade-restamp to RootB too")
        }

    // ──────────────────────────────────────────────
    // Adversarial probes (skill §6)
    // ──────────────────────────────────────────────

    @Test
    fun `probe replay — creating two children under the same parent in sequence both get correct, matching placement`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R Replay", depth = 0)))
            val p = create(WorkItem(title = "P Replay", parentId = root.id, depth = 1, rootId = root.id))

            val first = tool.execute(createParams("Child Replay 1", p.id), context) as JsonObject
            val second = tool.execute(createParams("Child Replay 2", p.id), context) as JsonObject

            for (r in listOf(first, second)) {
                val child = (r["data"] as JsonObject)["items"]!!.jsonArray[0] as JsonObject
                assertEquals(2, child["depth"]!!.jsonPrimitive.int, "actual: $r")
                assertEquals(root.id.toString(), child["rootId"]?.jsonPrimitive?.content, "actual: $r")
            }
        }

    @Test
    fun `probe batch of 2 under the same shared parentId both get correct placement`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R Batch", depth = 0)))
            val p = create(WorkItem(title = "P Batch", parentId = root.id, depth = 1, rootId = root.id))

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "parentId" to JsonPrimitive(p.id.toString()),
                        "items" to
                            JsonArray(
                                listOf(
                                    buildJsonObject { put("title", JsonPrimitive("Batch Child A")) },
                                    buildJsonObject { put("title", JsonPrimitive("Batch Child B")) }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val items = (result["data"] as JsonObject)["items"]!!.jsonArray
            assertEquals(2, items.size)
            for (child in items) {
                val obj = child as JsonObject
                assertEquals(2, obj["depth"]!!.jsonPrimitive.int, "actual: $obj")
                assertEquals(root.id.toString(), obj["rootId"]?.jsonPrimitive?.content, "actual: $obj")
            }
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix, alternate separators, encoded/UNC forms, mixed case: N/A - parentId is a
    //   UUID identifying an existing row, not a path/string surface.
    // - empty vs absent vs null: covered by S13 (absent parentId) and S15 (explicit null parentId).
    // - duplicates/ordering: covered above ("probe batch of 2...").
    // - replay/idempotency: covered above ("probe replay...").
    // - self-parent rejection: NOT re-authored here — already covered pre-existing
    //   (`ManageItemsToolTest` "create with self-parent parentId is rejected" / "reparent under own
    //   descendant..."), and self-parent is rejected by an identity check before any parent read,
    //   so it is unaffected by this fix's in-transaction-read change. Recorded as N/A: no new
    //   finding, pre-existing coverage is sufficient for this surface.
}

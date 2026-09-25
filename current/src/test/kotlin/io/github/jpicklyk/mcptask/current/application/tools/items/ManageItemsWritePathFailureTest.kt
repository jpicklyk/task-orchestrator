package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
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
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `af37c467` (needs-test-author, stream IW): MCP
 * `manage_items` create/update failure-path coverage — S9, S11, S13 of the frozen `test-plan` note
 * (queue phase, read before this file existed).
 *
 * CHARACTERIZATION-FIRST (dispatch contract, `plans/decompose-complexity-hotspots.md`): these
 * tests drive the CURRENT, pre-refactor `CreateItemHandler.execute` / `UpdateItemHandler.execute`
 * and must be green on base `dd26e9e2`. They are the safety net for an upcoming decomposition of
 * those two handlers.
 *
 * Oracles: dispatch declarations (#339 intent: a parent placement read that no longer resolves by
 * write time fails the existing parent-not-found path, leaving no orphan row; a descendant-cascade
 * write failure rolls back the whole item's update, not just the failing descendant), and
 * `current/docs/api-reference.md` (`failures` shape `{id, error}` / `{index, error}`, `priority`
 * must be one of `high`/`medium`/`low`, `complexity` 1-10, `role` enum
 * `queue`/`work`/`review`/`blocked`/`terminal`).
 *
 * SEAMS (own file, H2 — same technique as `ManageItemsParentPlacementInTxnTest`, whose
 * `MutateOnFirstTransactionRepository` / `WorkItemRepoOverrideProvider` pattern this file mirrors
 * for its own seams):
 * - [MutateOnFirstTransactionRepository] deletes a parent as the first thing inside the real write
 *   transaction, for S9.
 * - [UpdateFailsForIdRepository] fails `update()` for one target id — per the dispatch
 *   declarations' "Behavioral seams" note ("failing `update()` for D's id triggers S11/S12") — to
 *   force the descendant-depth-cascade to roll back for S11.
 *
 * BLINDNESS: authored from `task-scope`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions in this package (`ManageItemsParentPlacementInTxnTest.kt`, `ManageItemsToolTest.kt`).
 * No `src/main` file, diff, or commit was read.
 */
class ManageItemsWritePathFailureTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private val tool = ManageItemsTool()

    @BeforeEach
    fun setUp() {
        val dbName = "manage_items_write_failure_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
    }

    // ──────────────────────────────────────────────
    // Test-only seams
    // ──────────────────────────────────────────────

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
                            rootId = parent.data.rootId ?: parent.data.id
                        )
                    )
                is Result.Error -> Result.Error(parent.error)
            }
    }

    /** Wraps a real [WorkItemRepository]; fails `update()` only for [failFor]'s writes. */
    private class UpdateFailsForIdRepository(
        private val delegate: WorkItemRepository,
        private val failFor: UUID
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): Result<WorkItem> =
            if (item.id == failFor) {
                Result.Error(RepositoryError.ConflictError("simulated descendant cascade failure for $failFor"))
            } else {
                delegate.update(item)
            }
    }

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

    private fun mutateOnFirstTxn(mutate: suspend (WorkItemRepository) -> Unit) =
        MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository(), mutate)

    private fun contextWith(workItemRepo: WorkItemRepository) =
        ToolExecutionContext(WorkItemRepoOverrideProvider(repositoryProvider, workItemRepo))

    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

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
                        if (parentId != null) {
                            put("parentId", JsonPrimitive(parentId.toString()))
                        } else {
                            put("parentId", kotlinx.serialization.json.JsonNull)
                        }
                    }
                )
            )
    )

    // ──────────────────────────────────────────────
    // S9 — update X's parentId to P, P deleted inside the write transaction
    // ──────────────────────────────────────────────

    @Test
    fun `S9 update X under P whose parent is deleted inside the write transaction fails without mutating X`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "R S9", depth = 0)))
            val p = create(WorkItem(title = "P S9 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id))
            val x = stampSelfRoot(create(WorkItem(title = "X S9", depth = 0)))
            val wrapped = mutateOnFirstTxn { d -> d.delete(p.id) }

            val result = tool.execute(updateParentParams(x.id, p.id), contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            assertEquals(0, data["updated"]!!.jsonPrimitive.int, "actual: $result")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int, "actual: $result")
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertEquals(x.id.toString(), failure["id"]!!.jsonPrimitive.content, "actual: $failure")
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"), "actual: $failure")

            val persisted = (repositoryProvider.workItemRepository().getById(x.id) as Result.Success).data
            assertEquals(null, persisted.parentId, "X must be left unchanged")
            assertEquals(0, persisted.depth, "X must be left unchanged")
            assertEquals(x.id, persisted.rootId, "X must be left unchanged")
        }

    // ──────────────────────────────────────────────
    // S11 — reparent X to a different root; descendant D's cascade update fails
    // ──────────────────────────────────────────────

    @Test
    fun `S11 reparenting X to a different root fails without mutating X or its descendant when the cascade update fails`() =
        runBlocking {
            val r = stampSelfRoot(create(WorkItem(title = "R S11", depth = 0)))
            val x = create(WorkItem(title = "X S11", parentId = r.id, depth = 1, rootId = r.id))
            val d = create(WorkItem(title = "D S11 (child of X)", parentId = x.id, depth = 2, rootId = r.id))
            val q = stampSelfRoot(create(WorkItem(title = "Q S11", depth = 0)))
            val wrapped = UpdateFailsForIdRepository(repositoryProvider.workItemRepository(), failFor = d.id)

            val result = tool.execute(updateParentParams(x.id, q.id), contextWith(wrapped)) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            assertEquals(0, data["updated"]!!.jsonPrimitive.int, "actual: $result")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int, "actual: $result")
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertEquals(x.id.toString(), failure["id"]!!.jsonPrimitive.content, "actual: $failure")

            val persistedX = (repositoryProvider.workItemRepository().getById(x.id) as Result.Success).data
            assertEquals(r.id, persistedX.parentId, "X must be left unchanged")
            assertEquals(r.id, persistedX.rootId, "X must be left unchanged")
            val persistedD = (repositoryProvider.workItemRepository().getById(d.id) as Result.Success).data
            assertEquals(2, persistedD.depth, "D must be left unchanged")
            assertEquals(r.id, persistedD.rootId, "D must be left unchanged")
        }

    // ──────────────────────────────────────────────
    // S13 — batch create with three invalid specs
    // ──────────────────────────────────────────────

    @Test
    fun `S13 a batch create with three invalid specs creates only the valid one`() =
        runBlocking {
            val createParams =
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(
                                buildJsonObject { put("title", JsonPrimitive("Valid S13")) },
                                buildJsonObject {
                                    put("title", JsonPrimitive("Bad priority S13"))
                                    put("priority", JsonPrimitive("urgent"))
                                },
                                buildJsonObject {
                                    put("title", JsonPrimitive("Bad complexity S13"))
                                    put("complexity", JsonPrimitive(11))
                                },
                                buildJsonObject {
                                    put("title", JsonPrimitive("Bad role S13"))
                                    put("role", JsonPrimitive("bogus"))
                                }
                            )
                        )
                )

            val result = tool.execute(createParams, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            assertEquals(1, data["created"]!!.jsonPrimitive.int, "actual: $result")
            assertEquals(3, data["failed"]!!.jsonPrimitive.int, "actual: $result")
            val failures = data["failures"]!!.jsonArray.map { it as JsonObject }
            assertEquals(
                setOf(1, 2, 3),
                failures.map { it["index"]!!.jsonPrimitive.int }.toSet(),
                "actual: $failures"
            )

            val all = repositoryProvider.workItemRepository().findByFilters()
            assertTrue(all is Result.Success)
            val titles = (all as Result.Success).data.items.map { it.title }
            assertTrue(titles.contains("Valid S13"), "actual: $titles")
            assertTrue(!titles.contains("Bad priority S13"), "actual: $titles")
            assertTrue(!titles.contains("Bad complexity S13"), "actual: $titles")
            assertTrue(!titles.contains("Bad role S13"), "actual: $titles")
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix: covered by S13's complexity=11 (one above the 1-10 limit).
    // - alternate separators / encoded / UNC forms / mixed case: N/A - parentId/itemId are UUIDs
    //   identifying existing rows, not a path/string surface; priority/role are fixed enums, not
    //   free text subject to encoding variance.
    // - empty vs absent vs null: not re-authored here — the no-parent / null-parentId paths are
    //   pre-existing coverage in `ManageItemsParentPlacementInTxnTest` (S13, S15) and unaffected
    //   by this triage's failure-path additions.
    // - duplicates/ordering: covered by S13 (three distinct invalid specs in one batch, each
    //   reported at its own index; only the valid one persists).
    // - replay/idempotency: `manage_items` create/update has no idempotency-key surface at the MCP
    //   layer; N/A here, covered on the REST surface by `ItemWriteRoutesFailurePathTest`'s
    //   Idempotency-Key probes (existing coverage, `ItemWriteRoutesParentPlacementInTxnTest`).
}

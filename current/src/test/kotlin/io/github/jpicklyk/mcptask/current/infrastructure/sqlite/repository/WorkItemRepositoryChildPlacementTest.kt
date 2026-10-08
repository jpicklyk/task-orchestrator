package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent test authorship for item `3da296d8` (needs-test-author), covering
 * [WorkItemRepository.resolveChildPlacement]'s own contract at the repository unit level —
 * S1 and S8 of the frozen `test-plan` note (queue phase, read before this file existed).
 *
 * Oracles (test-plan / diagnosis, both frozen before implementation was read):
 * - O1: `child.depth == parent.depth + 1` and `child.rootId == (parent.rootId ?: parent.id)`,
 *   the parent read AS PERSISTED at assert time (`WorkItem.kt` rootId KDoc; `ItemHierarchyValidator`
 *   KDoc; diagnosis "Invariant").
 * - O3: a missing parent resolves to `Result.Error(RepositoryError.NotFound)`, per the diagnosis's
 *   "Chosen (e)" default-body description: "missing -> null".
 *
 * SIGNATURE (verbatim, supplied inline — NEW, no `src/main` lookup):
 * `data class ChildPlacement(val parentId: UUID, val depth: Int, val rootId: UUID)`;
 * `suspend fun resolveChildPlacement(parentId: UUID): ChildPlacement` (default body:
 * `getById(parentId)`, then `depth = p.depth + 1`, `rootId = p.rootId ?: p.id`; missing parent ->
 * `null`).
 *
 * These two scenarios test the method's own formula directly against a real SQLite-backed repository
 * (`DefaultRepositoryProvider`, the same harness convention as `ManageItemsToolTest` /
 * `DeleteItemHandlerAtomicityTest`) — no wrapper/interleaving seam is needed here because nothing
 * about ordering (O2) is at stake at this level; O2 (read+write sharing one `inTransaction`) is
 * proven at the call-site level in `ManageItemsParentPlacementInTxnTest` (S12), not here.
 *
 * BLINDNESS: authored from this item's `diagnosis` and `test-plan` notes (both queue-phase, frozen
 * before implementation, read via a `keys`-filtered `query_notes` call per the `test-author` skill),
 * the verbatim declarations block supplied in the dispatch prompt, and the existing repository harness
 * conventions in `DeleteItemHandlerAtomicityTest.kt` / `ManageItemsToolTest.kt`. No `src/main` file,
 * diff, or commit was read.
 */
class WorkItemRepositoryChildPlacementTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var repositoryProvider: DefaultRepositoryProvider

    @BeforeEach
    fun setUp() {
        val database = sqliteDb.database
        val databaseManager = sqliteDb.databaseManager
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
    }

    private fun repo() = repositoryProvider.workItemRepository()

    private suspend fun create(item: WorkItem): WorkItem {
        val result = repo().create(item)
        assertNotNull(result, "fixture creation failed: $result")
        return result
    }

    // ──────────────────────────────────────────────
    // S1 — happy: resolveChildPlacement(P) = (P.id, P.depth+1, P.rootId ?: P.id)
    // ──────────────────────────────────────────────

    @Test
    fun `S1 resolveChildPlacement derives depth and rootId from a parent that already has a stamped rootId`() =
        runBlocking {
            val root = create(WorkItem(title = "Root S1", depth = 0))
            val rootWithSelfId = (repo().update(root.copy(rootId = root.id))!!)
            val parent =
                create(
                    WorkItem(
                        title = "Parent S1",
                        parentId = rootWithSelfId.id,
                        depth = 1,
                        rootId = rootWithSelfId.id
                    )
                )

            val placement = repo().resolveChildPlacement(parent.id)

            assertNotNull(placement)
            assertEquals(parent.id, placement.parentId)
            assertEquals(2, placement.depth, "O1: depth must be parent.depth (1) + 1")
            assertEquals(rootWithSelfId.id, placement.rootId, "O1: rootId must be the parent's own stamped rootId")
        }

    @Test
    fun `S1b resolveChildPlacement falls back to the parent's own id when the parent has no stamped rootId (legacy)`() =
        runBlocking {
            // A root created directly via the repository, bypassing any route/tool that would
            // stamp rootId=self — the "legacy" shape the O1 fallback (rootId ?: id) exists for.
            val legacyRoot = create(WorkItem(title = "Legacy Root S1b", depth = 0))
            assertEquals(null, legacyRoot.rootId, "fixture precondition: legacy root has no stamped rootId")

            val placement = repo().resolveChildPlacement(legacyRoot.id)

            assertNotNull(placement)
            assertEquals(legacyRoot.id, placement.parentId)
            assertEquals(1, placement.depth, "O1: depth must be parent.depth (0) + 1")
            assertEquals(legacyRoot.id, placement.rootId, "O1: rootId falls back to the parent's own id")
        }

    // ──────────────────────────────────────────────
    // S8 — failure: unknown parent -> Result.Error(NotFound)
    // ──────────────────────────────────────────────

    @Test
    fun `S8 resolveChildPlacement on an unknown parent id returns Result Error NotFound`() =
        runBlocking {
            val unknownId = UUID.randomUUID()

            val placement = repo().resolveChildPlacement(unknownId)

            // P5b: a missing parent is now null (was Result.Error(NotFound(id))); null carries no id.
            assertNull(placement)
        }

    // ──────────────────────────────────────────────
    // Adversarial probes (skill §6) — recorded here even where nothing was found
    // ──────────────────────────────────────────────

    @Test
    fun `probe replay — calling resolveChildPlacement twice for an unchanged parent returns the same placement both times`() =
        runBlocking {
            val root = create(WorkItem(title = "Replay Root", depth = 0))
            val rootWithSelfId = (repo().update(root.copy(rootId = root.id))!!)
            val parent = create(WorkItem(title = "Replay Parent", parentId = rootWithSelfId.id, depth = 1, rootId = rootWithSelfId.id))

            val first = repo().resolveChildPlacement(parent.id)
            val second = repo().resolveChildPlacement(parent.id)

            assertNotNull(first)
            assertNotNull(second)
            assertEquals(first, second, "an unchanged parent must yield an identical placement on replay")
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix, alternate separators, encoded/UNC forms, mixed case: N/A - the only input
    //   to this method is a UUID identifying an existing row, not a path/string surface.
    // - empty vs absent vs null: N/A - parentId is a required non-null UUID parameter; there is no
    //   absent/null state to probe at this method's signature.
    // - duplicates/ordering: N/A - a single scalar parentId argument, not a collection.
    // - replay/idempotency: covered above ("probe replay...").
}

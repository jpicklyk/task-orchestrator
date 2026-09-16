package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.ItemFetchResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchResult
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for bug 97aa5855 — `query_items`'s `limit` parameter description claimed a single
 * default of 50 for every mode, but the true behaviour is per-mode: 20 in FTS search, 50 in list
 * mode and in global/anchored overview, a 100 cap in the two search modes only, no cap in overview,
 * and scoped overview (`itemId`) ignoring the parameter entirely.
 *
 * Harness mirrors [QueryItemsToolFtsDecoratorDispatchTest] — [MockRepositoryProvider] + MockK
 * `coEvery`/`coVerify`/`slot`, no FTS5/H2 dependency. Every scenario is EXISTING-SURFACE per the
 * frozen `test-plan` note; S1-S8 pin the per-mode default/cap behaviour (already correct at HEAD —
 * the fix here is documentation-only, so these are contract-pinning, not regression, tests). S9 is
 * the one scenario that is red on a plain revert of the `limit` description string.
 *
 * Oracle for S1-S8 is `current/docs/api-reference.md` at base `768f7f3` (L201, L224, L237, L238,
 * L369), per the frozen `test-plan`. Negative `limit` on anchored overview (`List.take(-1)` crash)
 * is out of scope for this item (diagnosis C3) and is deliberately NOT tested here.
 */
class QueryItemsLimitContractTest {
    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    // ──────────────────────────────────────────────
    // S1 / S2 — FTS search mode (`query` set): default 20, cap 100
    // ──────────────────────────────────────────────

    @Test
    fun `S1 FTS search omitted limit defaults to 20`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val limitSlot = slot<Int>()
            val sentinel = SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)

            coEvery {
                mocks.workItemRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = capture(limitSlot),
                    offset = any(),
                )
            } returns sentinel

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(20, limitSlot.captured, "FTS-mode default must be 20 (L201)")
        }

    @Test
    fun `S2 FTS search limit above 100 is capped at 100`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val limitSlot = slot<Int>()
            val sentinel = SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)

            coEvery {
                mocks.workItemRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = capture(limitSlot),
                    offset = any(),
                )
            } returns sentinel

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "limit" to JsonPrimitive(500),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(100, limitSlot.captured, "FTS-mode cap must be 100 (L201)")
        }

    // ──────────────────────────────────────────────
    // S3 — FTS search mode, limit=0 is rejected (validation is search-arm only; unchanged by this fix)
    // ──────────────────────────────────────────────

    @Test
    fun `S3 FTS search limit=0 is rejected as VALIDATION_ERROR`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val tool = QueryItemsTool()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "limit" to JsonPrimitive(0),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            val error = result["error"]!!.jsonObject
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
            assertTrue(
                "limit must be at least 1" in error["message"]!!.jsonPrimitive.content,
                "expected the validateParams message, got: ${error["message"]}",
            )
            coVerify(exactly = 0) {
                mocks.workItemRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any(),
                )
            }
        }

    // ──────────────────────────────────────────────
    // S4 / S5 — search, list mode (no `query`): default 50, cap 100
    // ──────────────────────────────────────────────

    @Test
    fun `S4 list mode omitted limit defaults to 50`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val limitSlot = slot<Int>()

            coEvery {
                mocks.workItemRepo.findByFilters(
                    parentId = any(),
                    depth = any(),
                    role = any(),
                    priority = any(),
                    tags = any(),
                    query = any(),
                    createdAfter = any(),
                    createdBefore = any(),
                    modifiedAfter = any(),
                    modifiedBefore = any(),
                    roleChangedAfter = any(),
                    roleChangedBefore = any(),
                    sortBy = any(),
                    sortOrder = any(),
                    limit = capture(limitSlot),
                    offset = any(),
                    type = any(),
                    claimStatus = any(),
                )
            } returns Result.Success(ItemFetchResult(items = emptyList(), skipped = 0))
            coEvery {
                mocks.workItemRepo.countByFilters(
                    parentId = any(),
                    depth = any(),
                    role = any(),
                    priority = any(),
                    tags = any(),
                    query = any(),
                    createdAfter = any(),
                    createdBefore = any(),
                    modifiedAfter = any(),
                    modifiedBefore = any(),
                    roleChangedAfter = any(),
                    roleChangedBefore = any(),
                    type = any(),
                    claimStatus = any(),
                )
            } returns Result.Success(0)

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(50, limitSlot.captured, "list-mode default must be 50 (L224)")
        }

    @Test
    fun `S5 list mode limit above 100 is capped at 100`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val limitSlot = slot<Int>()

            coEvery {
                mocks.workItemRepo.findByFilters(
                    parentId = any(),
                    depth = any(),
                    role = any(),
                    priority = any(),
                    tags = any(),
                    query = any(),
                    createdAfter = any(),
                    createdBefore = any(),
                    modifiedAfter = any(),
                    modifiedBefore = any(),
                    roleChangedAfter = any(),
                    roleChangedBefore = any(),
                    sortBy = any(),
                    sortOrder = any(),
                    limit = capture(limitSlot),
                    offset = any(),
                    type = any(),
                    claimStatus = any(),
                )
            } returns Result.Success(ItemFetchResult(items = emptyList(), skipped = 0))
            coEvery {
                mocks.workItemRepo.countByFilters(
                    parentId = any(),
                    depth = any(),
                    role = any(),
                    priority = any(),
                    tags = any(),
                    query = any(),
                    createdAfter = any(),
                    createdBefore = any(),
                    modifiedAfter = any(),
                    modifiedBefore = any(),
                    roleChangedAfter = any(),
                    roleChangedBefore = any(),
                    type = any(),
                    claimStatus = any(),
                )
            } returns Result.Success(0)

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "limit" to JsonPrimitive(500),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(100, limitSlot.captured, "list-mode cap must be 100 (L224)")
        }

    // ──────────────────────────────────────────────
    // S6 — scoped overview (`itemId`): `limit` is ignored entirely
    // ──────────────────────────────────────────────

    @Test
    fun `S6 scoped overview ignores limit and returns all direct children`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val parentId = UUID.randomUUID()
            val parent = WorkItem(id = parentId, title = "Parent")
            val children = (1..5).map { i -> WorkItem(parentId = parentId, title = "Child $i", depth = 1) }

            coEvery { mocks.workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { mocks.workItemRepo.findChildren(parentId) } returns Result.Success(children)
            coEvery { mocks.workItemRepo.countChildrenByRole(any()) } returns Result.Success(emptyMap())
            coEvery { mocks.workItemRepo.countInScopeByRole(any()) } returns Result.Success(emptyMap())
            coEvery {
                mocks.workItemRepo.countByClaimStatus(parentId = any(), rootIds = any())
            } returns Result.Success(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0))

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "itemId" to JsonPrimitive(parentId.toString()),
                        "limit" to JsonPrimitive(2),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(
                5,
                data["children"]!!.jsonArray.size,
                "scoped overview (itemId) ignores `limit`; all 5 direct children must be returned (L238)",
            )
        }

    // ──────────────────────────────────────────────
    // S7 — anchored overview (`anchorId`): default 50, no cap, `limit` applied in-memory
    // ──────────────────────────────────────────────

    @Test
    fun `S7 anchored overview applies limit in-memory without capping`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val anchorId = UUID.randomUUID()
            val anchor = WorkItem(id = anchorId, title = "Anchor")
            val children = (1..5).map { i -> WorkItem(parentId = anchorId, title = "Child $i", depth = 1) }

            coEvery { mocks.workItemRepo.getById(anchorId) } returns Result.Success(anchor)
            coEvery { mocks.workItemRepo.findChildren(anchorId) } returns Result.Success(children)
            coEvery { mocks.workItemRepo.countChildrenByRole(any()) } returns Result.Success(emptyMap())
            coEvery { mocks.workItemRepo.countInScopeByRole(any()) } returns Result.Success(emptyMap())
            coEvery {
                mocks.workItemRepo.countByClaimStatus(parentId = any(), rootIds = any())
            } returns Result.Success(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0))

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "anchorId" to JsonPrimitive(anchorId.toString()),
                        "limit" to JsonPrimitive(2),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["items"]!!.jsonArray.size, "anchored overview must apply `limit` (L237)")
            assertEquals(
                5,
                data["total"]!!.jsonPrimitive.int,
                "anchored overview `total` must reflect the full child count, not the paged size (L237)",
            )
        }

    // ──────────────────────────────────────────────
    // S8 — global overview (no `itemId`/`anchorId`): default 50, offset 0, excludeTerminal false
    // ──────────────────────────────────────────────

    @Test
    fun `S8 global overview omitted limit defaults to 50 with offset 0 and excludeTerminal false`() =
        runBlocking {
            val mocks = MockRepositoryProvider()

            coEvery {
                mocks.workItemRepo.findRootItems(limit = 50, offset = 0, excludeTerminal = false)
            } returns Result.Success(ItemFetchResult(items = emptyList(), skipped = 0))
            coEvery { mocks.workItemRepo.countRootItems(any()) } returns Result.Success(0L)

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            coVerify(exactly = 1) {
                mocks.workItemRepo.findRootItems(limit = 50, offset = 0, excludeTerminal = false)
            }
        }

    // ──────────────────────────────────────────────
    // S9 — the one scenario red on a plain revert of the `limit` description string
    // ──────────────────────────────────────────────

    @Test
    fun `S9 limit parameterSchema description states per-mode defaults, cap, and scoped-overview exemption`() {
        // Facts only, never whole-string equality (change-detector). Pre-fix string was:
        // "Max results (default: 50)".
        val description =
            (QueryItemsTool().parameterSchema.properties as JsonObject)["limit"]!!
                .jsonObject["description"]!!
                .jsonPrimitive.content

        assertTrue("20" in description, "must state the FTS-mode default of 20 — got: $description")
        assertTrue("100" in description, "must state the search-mode cap of 100 — got: $description")
        assertTrue(
            description.lowercase().contains("scoped overview"),
            "must state that scoped overview ignores limit — got: $description",
        )
    }
}

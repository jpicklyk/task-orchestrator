package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Tests for [QueryItemsTool] claim-status filter and tiered claim disclosure.
 *
 * Uses a real H2 in-memory database for SQL condition testing. Claim fields are populated
 * via direct WorkItem.create() to bypass SQLite-specific claim SQL (which uses HEX() and
 * datetime('now') — H2-incompatible). The filter logic under test (buildFilteredQuery)
 * uses Exposed DSL which is dialect-agnostic.
 *
 * Tiered disclosure contract under test:
 * - `claimedBy` MUST NEVER appear in `query_items` search results (even for claimed items)
 * - `isClaimed` boolean MAY appear in search results when `claimStatus` filter is provided
 * - `claimSummary` counts appear in overview results (no identity)
 */
class QueryItemsToolClaimStatusTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool
    private lateinit var manageTool: ManageItemsTool
    private lateinit var workItemRepo: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "test_claim_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        workItemRepo = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryItemsTool()
        manageTool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /** Create a work item via ManageItemsTool and return its UUID. */
    private suspend fun createItemId(
        title: String,
        parentId: UUID? = null
    ): UUID {
        val itemObj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
            }
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(itemObj))
                ),
                context
            ) as JsonObject
        val idStr =
            (result["data"] as JsonObject)["items"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive.content
        return UUID.fromString(idStr)
    }

    /**
     * Set an item as actively claimed (non-expired) by directly updating via repository.
     * Bypasses SQLite-specific claim SQL to keep tests H2-compatible.
     */
    private suspend fun setActiveClaim(
        itemId: UUID,
        agentId: String
    ) {
        val item = (workItemRepo.getById(itemId) as Result.Success).data
        val now = Instant.now()
        workItemRepo.update(
            item.copy(
                claimedBy = agentId,
                claimedAt = now,
                claimExpiresAt = now.plusSeconds(900),
                originalClaimedAt = now,
                version = item.version
            )
        )
    }

    /**
     * Set an item with an expired claim (TTL already elapsed).
     */
    private suspend fun setExpiredClaim(
        itemId: UUID,
        agentId: String
    ) {
        val item = (workItemRepo.getById(itemId) as Result.Success).data
        val past = Instant.now().minusSeconds(3600) // 1 hour ago
        workItemRepo.update(
            item.copy(
                claimedBy = agentId,
                claimedAt = past.minusSeconds(900),
                claimExpiresAt = past, // expired
                originalClaimedAt = past.minusSeconds(900),
                version = item.version
            )
        )
    }

    // ──────────────────────────────────────────────
    // Section 1: claimStatus=claimed filter
    // ──────────────────────────────────────────────

    @Test
    fun `search with claimStatus=claimed returns only actively claimed items`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A")
            val itemB = createItemId("Item B")
            createItemId("Item C") // unclaimed

            setActiveClaim(itemA, "agent-1")
            setActiveClaim(itemB, "agent-2")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "claimStatus" to JsonPrimitive("claimed")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray
            assertEquals(2, data["total"]!!.jsonPrimitive.int, "Should return 2 claimed items")
            assertEquals(2, items.size)

            val titles = items.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()
            assertTrue("Item A" in titles)
            assertTrue("Item B" in titles)
            assertFalse("Item C" in titles)
        }

    // ──────────────────────────────────────────────
    // Section 2: claimStatus=unclaimed filter
    // ──────────────────────────────────────────────

    @Test
    fun `search with claimStatus=unclaimed returns only never-claimed items`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A")
            createItemId("Item B") // unclaimed
            createItemId("Item C") // unclaimed

            setActiveClaim(itemA, "agent-1")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "claimStatus" to JsonPrimitive("unclaimed")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["total"]!!.jsonPrimitive.int, "Should return 2 unclaimed items")

            val titles =
                data["items"]!!
                    .jsonArray
                    .map { it.jsonObject["title"]!!.jsonPrimitive.content }
                    .toSet()
            assertTrue("Item B" in titles)
            assertTrue("Item C" in titles)
            assertFalse("Item A" in titles, "Claimed item A must not appear in unclaimed results")
        }

    // ──────────────────────────────────────────────
    // Section 3: claimStatus=expired filter
    // ──────────────────────────────────────────────

    @Test
    fun `search with claimStatus=expired returns only items with past-TTL claims`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A") // expired claim
            val itemB = createItemId("Item B") // active claim
            createItemId("Item C") // unclaimed

            setExpiredClaim(itemA, "stale-agent")
            setActiveClaim(itemB, "active-agent")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "claimStatus" to JsonPrimitive("expired")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["total"]!!.jsonPrimitive.int, "Should return exactly 1 expired-claim item")
            assertEquals(
                "Item A",
                data["items"]!!
                    .jsonArray[0]
                    .jsonObject["title"]!!
                    .jsonPrimitive.content
            )
        }

    // ──────────────────────────────────────────────
    // Section 4: isClaimed boolean when claimStatus filter is provided
    // ──────────────────────────────────────────────

    @Test
    fun `search with claimStatus filter adds isClaimed boolean to each result`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A")
            setActiveClaim(itemA, "agent-1")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "claimStatus" to JsonPrimitive("claimed")
                    ),
                    context
                ) as JsonObject

            val items = (result["data"] as JsonObject)["items"]!!.jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject

            assertNotNull(item["isClaimed"], "isClaimed must be present when claimStatus filter is used")
            assertTrue(item["isClaimed"]!!.jsonPrimitive.boolean, "isClaimed should be true for an actively claimed item")
        }

    @Test
    fun `search without claimStatus filter does NOT add isClaimed field`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A")
            setActiveClaim(itemA, "agent-1")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    context
                ) as JsonObject

            val items = (result["data"] as JsonObject)["items"]!!.jsonArray
            assertFalse(items.isEmpty())
            val item = items[0].jsonObject

            assertNull(item["isClaimed"], "isClaimed must NOT appear when claimStatus filter is not used")
        }

    // ──────────────────────────────────────────────
    // Section 5: SECURITY — claimedBy MUST NEVER appear in search results
    // ──────────────────────────────────────────────

    @Test
    fun `claimedBy identity NEVER appears in search results regardless of filter (security)`(): Unit =
        runBlocking {
            val itemA = createItemId("Item A")
            setActiveClaim(itemA, "very-secret-agent-identity")

            // Search without filter
            val resultNoFilter =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    context
                ) as JsonObject
            val serializedNoFilter = (resultNoFilter["data"] as JsonObject).toString()
            assertFalse(
                "very-secret-agent-identity" in serializedNoFilter,
                "claimedBy identity must NOT appear in search results (no filter)"
            )

            // Search with claimStatus=claimed filter
            val resultWithFilter =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "claimStatus" to JsonPrimitive("claimed")
                    ),
                    context
                ) as JsonObject
            val serializedWithFilter = (resultWithFilter["data"] as JsonObject).toString()
            assertFalse(
                "very-secret-agent-identity" in serializedWithFilter,
                "claimedBy identity must NOT appear in search results (with claimStatus filter)"
            )

            // Deep structural check: assert no 'claimedBy' key anywhere in item results
            val resultItems = (resultWithFilter["data"] as JsonObject)["items"]!!.jsonArray
            for (item in resultItems) {
                val itemObj = item.jsonObject
                assertNull(itemObj["claimedBy"], "Unexpected claimedBy field in search result item")
                // Also check no 'claimedAt', 'claimExpiresAt' identity fields leaking
                assertNull(itemObj["claimedAt"], "Unexpected claimedAt in search result")
                assertNull(itemObj["claimExpiresAt"], "Unexpected claimExpiresAt in search result")
            }
        }

    // ──────────────────────────────────────────────
    // Section 6: Validation
    // ──────────────────────────────────────────────

    @Test
    fun `validateParams throws for invalid claimStatus value`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("search"),
                    "claimStatus" to JsonPrimitive("invalid-value")
                )
            )
        }
    }

    @Test
    fun `validateParams throws for claimStatus with wrong case`() {
        // Only lowercase "claimed", "unclaimed", "expired" are valid
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("search"),
                    "claimStatus" to JsonPrimitive("CLAIMED")
                )
            )
        }
    }

    @Test
    fun `validateParams passes for all valid claimStatus values`() {
        for (valid in listOf("claimed", "unclaimed", "expired")) {
            // Should not throw
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("search"),
                    "claimStatus" to JsonPrimitive(valid)
                )
            )
        }
    }

    // ──────────────────────────────────────────────
    // Section 7: overview includes claimSummary
    // ──────────────────────────────────────────────

    @Test
    fun `overview includes claimSummary with correct counts per root item`(): Unit =
        runBlocking {
            val rootId = createItemId("Root Item")
            val child1Id = createItemId("Child 1", parentId = rootId)
            val child2Id = createItemId("Child 2", parentId = rootId)

            // Claim child1 (active), leave child2 unclaimed
            setActiveClaim(child1Id, "agent-overview")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val items = (result["data"] as JsonObject)["items"]!!.jsonArray

            val rootEntry =
                items
                    .firstOrNull {
                        it.jsonObject["id"]?.jsonPrimitive?.content == rootId.toString()
                    }?.jsonObject
            assertNotNull(rootEntry, "Root item should be in overview")

            val claimSummary = rootEntry["claimSummary"]?.jsonObject
            assertNotNull(claimSummary, "claimSummary must be present in overview item")
            assertEquals(1, claimSummary["active"]!!.jsonPrimitive.int, "Should have 1 active claim (child1)")
            assertEquals(0, claimSummary["expired"]!!.jsonPrimitive.int, "Should have 0 expired claims")
            // unclaimed = child2 only (countByClaimStatus scoped to direct children of root)
            assertEquals(1, claimSummary["unclaimed"]!!.jsonPrimitive.int, "Should have 1 unclaimed (child2)")
        }

    @Test
    fun `overview claimSummary is scoped per root item not global`(): Unit =
        runBlocking {
            // Root A with 1 claimed child
            val rootAId = createItemId("Root A")
            val childAId = createItemId("Child of A", parentId = rootAId)
            setActiveClaim(childAId, "agent-a")

            // Root B with 0 claimed children
            val rootBId = createItemId("Root B")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            val items = (result["data"] as JsonObject)["items"]!!.jsonArray

            val rootAClaimSummary =
                items
                    .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == rootAId.toString() }
                    ?.jsonObject
                    ?.get("claimSummary")
                    ?.jsonObject

            val rootBClaimSummary =
                items
                    .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == rootBId.toString() }
                    ?.jsonObject
                    ?.get("claimSummary")
                    ?.jsonObject

            assertNotNull(rootAClaimSummary)
            assertNotNull(rootBClaimSummary)

            // Root A has 1 active claim (its child), Root B has 0
            assertEquals(1, rootAClaimSummary["active"]!!.jsonPrimitive.int)
            assertEquals(0, rootBClaimSummary["active"]!!.jsonPrimitive.int)
        }

    @Test
    fun `overview claimSummary does NOT contain claimedBy identity`(): Unit =
        runBlocking {
            val rootId = createItemId("Root")
            val childId = createItemId("Child", parentId = rootId)
            setActiveClaim(childId, "super-secret-identity-in-overview")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            val serialized = result.toString()
            assertFalse(
                "super-secret-identity-in-overview" in serialized,
                "claimedBy identity must NEVER appear in overview response"
            )
        }

    // TEST-I5: overview response must not leak the "claimedBy" JSON key at all —
    // even when an item IS actively claimed internally.
    @Test
    fun `TEST-I5 overview response does not leak claimedBy key in serialized output`(): Unit =
        runBlocking {
            // Create an item that IS claimed (so claimedBy exists internally on the domain object)
            val rootId = createItemId("Root")
            val childId = createItemId("Claimed Child", parentId = rootId)
            setActiveClaim(childId, "super-secret-holder-agent")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            // Key scan (stronger than value scan): assert the "claimedBy" JSON key is absent from the
            // entire serialized overview response. A value scan would only block a specific known value;
            // a key scan catches any field named "claimedBy" regardless of its value (including null or
            // a renamed variant like "claimedByAgent" that might also start with "claimedBy").
            val serialized = result.toString()
            assertFalse(
                "\"claimedBy\"" in serialized,
                "Overview response must NOT contain the \"claimedBy\" JSON key anywhere — " +
                    "holder identity must never be disclosed via overview (tiered-disclosure contract). " +
                    "Got: $serialized"
            )
        }
}

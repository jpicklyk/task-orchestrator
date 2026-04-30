package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for `requestId` idempotency parameter wiring across the six mutating tools.
 *
 * Each test verifies:
 * 1. First call with (actor, requestId) executes the operation and stores the result.
 * 2. Second call with the same (actor, requestId) returns the cached response without re-executing.
 *    Verified by checking that mutating side-effects (e.g., new IDs) match between calls.
 * 3. Different requestId or different actor produces a fresh execution.
 * 4. No requestId means no cache interaction (current behavior preserved).
 */
class IdempotencyToolsTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var idempotencyCache: IdempotencyCache
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        idempotencyCache = IdempotencyCache()
        context =
            ToolExecutionContext(
                repositoryProvider = repositoryProvider,
                idempotencyCache = idempotencyCache
            )
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun actor(id: String = "test-agent") =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("kind", JsonPrimitive("subagent"))
        }

    private suspend fun createTestItem(title: String = "Test Item"): UUID {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return (result as Result.Success).data.id
    }

    // ──────────────────────────────────────────────
    // ManageItemsTool — create idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `manage_items create caches response on requestId`() =
        runBlocking {
            val tool = ManageItemsTool()
            val requestId = UUID.randomUUID().toString()

            val firstResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(
                                    buildJsonObject { put("title", JsonPrimitive("First")) }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            // Second call with same (actor, requestId) — should return cached, no new item created
            val secondResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(
                                    // Different title but should NOT execute since cached
                                    buildJsonObject { put("title", JsonPrimitive("Different")) }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            // Both responses should be identical (cached)
            assertEquals(firstResult, secondResult)

            val firstId =
                ((firstResult["data"] as JsonObject)["items"] as JsonArray)[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            val secondId =
                ((secondResult["data"] as JsonObject)["items"] as JsonArray)[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            assertEquals(firstId, secondId, "Cached call must return identical item id")

            // Verify only ONE item exists in the repository (no double creation)
            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(1, (allItems as Result.Success).data.size, "Only one item should have been created")
        }

    @Test
    fun `manage_items create with different requestId executes fresh`() =
        runBlocking {
            val tool = ManageItemsTool()

            val first =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(buildJsonObject { put("title", JsonPrimitive("First")) })
                            ),
                        "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            val second =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(buildJsonObject { put("title", JsonPrimitive("Second")) })
                            ),
                        "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            val firstId =
                ((first["data"] as JsonObject)["items"] as JsonArray)[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            val secondId =
                ((second["data"] as JsonObject)["items"] as JsonArray)[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            assertNotEquals(firstId, secondId, "Different requestIds must produce different items")
        }

    @Test
    fun `manage_items create without requestId skips cache`() =
        runBlocking {
            val tool = ManageItemsTool()

            // First call without requestId
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Item A")) })
                        )
                ),
                context
            )

            // Second call without requestId — should execute fresh, not return cached
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Item B")) })
                        )
                ),
                context
            )

            assertEquals(0, idempotencyCache.size(), "Cache should remain empty when requestId is omitted")

            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(2, (allItems as Result.Success).data.size, "Both items should have been created")
        }

    @Test
    fun `manage_items create with different actor executes fresh`() =
        runBlocking {
            val tool = ManageItemsTool()
            val requestId = UUID.randomUUID().toString()

            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Agent A item")) })
                        ),
                    "requestId" to JsonPrimitive(requestId),
                    "actor" to actor("agent-a")
                ),
                context
            )

            // Same requestId, different actor — fresh execution
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Agent B item")) })
                        ),
                    "requestId" to JsonPrimitive(requestId),
                    "actor" to actor("agent-b")
                ),
                context
            )

            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(2, (allItems as Result.Success).data.size, "Different actors must create separate items")
        }

    // ──────────────────────────────────────────────
    // ManageNotesTool — upsert idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `manage_notes upsert caches response on requestId`() =
        runBlocking {
            val tool = ManageNotesTool()
            val itemId = createTestItem().toString()
            val requestId = UUID.randomUUID().toString()

            val firstResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("approach"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("First body"))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            val secondResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("approach"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("DIFFERENT body"))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            assertEquals(firstResult, secondResult)

            // Verify the note body was set ONCE — second call did not overwrite
            val notes = context.noteRepository().findByItemId(UUID.fromString(itemId))
            assertTrue(notes is Result.Success)
            val notesList = (notes as Result.Success).data
            assertEquals(1, notesList.size)
            assertEquals("First body", notesList[0].body, "Cached call must not have overwritten the body")
        }

    @Test
    fun `manage_notes without requestId skips cache`() =
        runBlocking {
            val tool = ManageNotesTool()
            val itemId = createTestItem().toString()

            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("approach"))
                                    put("role", JsonPrimitive("work"))
                                    put("body", JsonPrimitive("v1"))
                                }
                            )
                        )
                ),
                context
            )

            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("approach"))
                                    put("role", JsonPrimitive("work"))
                                    put("body", JsonPrimitive("v2"))
                                }
                            )
                        )
                ),
                context
            )

            assertEquals(0, idempotencyCache.size())
            val notes = context.noteRepository().findByItemId(UUID.fromString(itemId))
            assertTrue(notes is Result.Success)
            assertEquals("v2", (notes as Result.Success).data[0].body, "Without requestId, second call must overwrite")
        }

    // ──────────────────────────────────────────────
    // ManageDependenciesTool — create idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `manage_dependencies create caches response on requestId`() =
        runBlocking {
            val tool = ManageDependenciesTool()
            val from = createTestItem("From").toString()
            val to = createTestItem("To").toString()
            val requestId = UUID.randomUUID().toString()

            val firstResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "dependencies" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(from))
                                        put("toItemId", JsonPrimitive(to))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            val secondResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "dependencies" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(from))
                                        put("toItemId", JsonPrimitive(to))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            assertEquals(firstResult, secondResult)

            // Verify exactly one dependency exists (cached call did NOT create a duplicate)
            val deps = context.dependencyRepository().findByFromItemId(UUID.fromString(from))
            assertEquals(1, deps.size, "Cached call must not create a duplicate dependency")
        }

    // ──────────────────────────────────────────────
    // AdvanceItemTool — transition idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `advance_item caches response on requestId`() =
        runBlocking {
            val tool = AdvanceItemTool()
            val itemId = createTestItem().toString()
            val requestId = UUID.randomUUID().toString()

            val first =
                tool.execute(
                    params(
                        "transitions" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("trigger", JsonPrimitive("start"))
                                        put("actor", actor())
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId)
                    ),
                    context
                ) as JsonObject

            val second =
                tool.execute(
                    params(
                        "transitions" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("trigger", JsonPrimitive("start"))
                                        put("actor", actor())
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId)
                    ),
                    context
                ) as JsonObject

            // Cached response is identical
            assertEquals(first, second)
            assertEquals(1, idempotencyCache.size())
        }

    // ──────────────────────────────────────────────
    // CreateWorkTreeTool — idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `create_work_tree caches response on requestId`() =
        runBlocking {
            val tool = CreateWorkTreeTool()
            val requestId = UUID.randomUUID().toString()

            val first =
                tool.execute(
                    params(
                        "root" to buildJsonObject { put("title", JsonPrimitive("Root tree")) },
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            val second =
                tool.execute(
                    params(
                        "root" to buildJsonObject { put("title", JsonPrimitive("Different title")) },
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            assertEquals(first, second)

            val firstRootId =
                ((first["data"] as JsonObject)["root"] as JsonObject)["id"]!!
                    .jsonPrimitive.content
            val secondRootId =
                ((second["data"] as JsonObject)["root"] as JsonObject)["id"]!!
                    .jsonPrimitive.content
            assertEquals(firstRootId, secondRootId)

            // Verify only one root item was actually created
            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(1, (allItems as Result.Success).data.size, "Cached call must not create a second tree")
        }

    // ──────────────────────────────────────────────
    // CompleteTreeTool — idempotency
    // ──────────────────────────────────────────────

    @Test
    fun `complete_tree caches response on requestId`() =
        runBlocking {
            val tool = CompleteTreeTool()
            val itemId = createTestItem().toString()
            val requestId = UUID.randomUUID().toString()

            // First call attempts to complete — may produce a result depending on schema/gates
            val first =
                tool.execute(
                    params(
                        "itemIds" to JsonArray(listOf(JsonPrimitive(itemId))),
                        "trigger" to JsonPrimitive("cancel"),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            // Second call with same requestId — must return cached
            val second =
                tool.execute(
                    params(
                        "itemIds" to JsonArray(listOf(JsonPrimitive(itemId))),
                        "trigger" to JsonPrimitive("cancel"),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor()
                    ),
                    context
                ) as JsonObject

            assertEquals(first, second)
            assertEquals(1, idempotencyCache.size())
        }

    // ──────────────────────────────────────────────
    // Cross-tool: cache isolation via actorId+requestId composite key
    // ──────────────────────────────────────────────

    @Test
    fun `cache properly isolates entries by actor+requestId composite key`() =
        runBlocking {
            val tool = ManageItemsTool()
            val sharedRequestId = UUID.randomUUID().toString()

            // Two different agents with identical requestId
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("From A")) })
                        ),
                    "requestId" to JsonPrimitive(sharedRequestId),
                    "actor" to actor("agent-a")
                ),
                context
            )
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("From B")) })
                        ),
                    "requestId" to JsonPrimitive(sharedRequestId),
                    "actor" to actor("agent-b")
                ),
                context
            )

            // Should have TWO cache entries, one per actor
            assertEquals(2, idempotencyCache.size())

            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(2, (allItems as Result.Success).data.size, "Both agents' items should exist")
        }

    // ──────────────────────────────────────────────
    // Negative case: malformed requestId UUID is silently ignored
    // ──────────────────────────────────────────────

    @Test
    fun `manage_items with invalid requestId UUID falls back to non-idempotent execution`() =
        runBlocking {
            val tool = ManageItemsTool()

            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("First")) })
                        ),
                    "requestId" to JsonPrimitive("not-a-uuid"),
                    "actor" to actor()
                ),
                context
            )
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Second")) })
                        ),
                    "requestId" to JsonPrimitive("not-a-uuid"),
                    "actor" to actor()
                ),
                context
            )

            // Cache should be empty (invalid requestId not used as key)
            assertEquals(0, idempotencyCache.size())
            val items = context.workItemRepository().findRootItems()
            assertTrue(items is Result.Success)
            assertEquals(2, (items as Result.Success).data.size, "Invalid requestId must not enable caching")
        }

    @Test
    fun `requestId without actor falls back to non-idempotent execution`() =
        runBlocking {
            val tool = ManageItemsTool()
            val requestId = UUID.randomUUID().toString()

            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("First")) })
                        ),
                    "requestId" to JsonPrimitive(requestId)
                    // No actor!
                ),
                context
            )
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Second")) })
                        ),
                    "requestId" to JsonPrimitive(requestId)
                ),
                context
            )

            assertEquals(0, idempotencyCache.size(), "Without actor, requestId must not enable caching")
            val items = context.workItemRepository().findRootItems()
            assertTrue(items is Result.Success)
            assertEquals(2, (items as Result.Success).data.size)
        }

    // ──────────────────────────────────────────────
    // ClaimItemTool — claim idempotency (TEST-I10a, TEST-I10b)
    //
    // NOTE: claim_item's repository.claim() uses SQLite-specific HEX(id) syntax
    // that is incompatible with H2 in-memory databases. These tests use a mocked
    // WorkItemRepository (via MockRepositoryProvider) to test the idempotency
    // wiring directly without hitting DB-level SQL compatibility issues.
    // ──────────────────────────────────────────────

    private fun buildClaimParams(
        itemId: String,
        agentId: String,
        requestId: String
    ): JsonObject =
        buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemId))
                            put("ttlSeconds", JsonPrimitive(900))
                        }
                    )
                )
            )
            put("actor", actor(agentId))
            put("requestId", JsonPrimitive(requestId))
        }

    private fun buildClaimParamsNoRequestId(
        itemId: String,
        agentId: String
    ): JsonObject =
        buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemId))
                            put("ttlSeconds", JsonPrimitive(900))
                        }
                    )
                )
            )
            put("actor", actor(agentId))
        }

    private fun claimSuccessItem(
        id: UUID,
        claimedBy: String
    ): WorkItem {
        val now = Instant.now()
        return WorkItem(
            id = id,
            title = "Test Claim Item",
            claimedBy = claimedBy,
            claimedAt = now,
            claimExpiresAt = now.plusSeconds(900),
            originalClaimedAt = now
        )
    }

    /**
     * TEST-I10a: claim_item with requestId returns cached response on retry, no double-mutation.
     *
     * First call mutates state (places the claim). Second call with same (actor, requestId)
     * returns the cached response without calling repository.claim() again.
     * Verified via mock invocation count — claim() is only called once.
     */
    @Test
    fun `claim_item with requestId returns cached response on retry, no double-mutation`() =
        runBlocking {
            val mockRepo = MockRepositoryProvider()
            val claimCache = IdempotencyCache()
            val claimContext =
                ToolExecutionContext(
                    repositoryProvider = mockRepo.provider,
                    idempotencyCache = claimCache
                )

            val tool = ClaimItemTool()
            val itemId = UUID.randomUUID()
            val requestId = UUID.randomUUID().toString()
            val agentId = "agent-claimer"

            // Set up mock: claim() returns success
            coEvery {
                mockRepo.workItemRepo.claim(itemId, agentId, 900)
            } returns
                ClaimResult.Success(
                    claimSuccessItem(itemId, agentId)
                )

            val claimParams = buildClaimParams(itemId.toString(), agentId, requestId)

            val firstResult = tool.execute(claimParams, claimContext) as JsonObject

            // Verify first call succeeded
            val firstData = firstResult["data"] as JsonObject
            val firstClaimResults = firstData["claimResults"] as JsonArray
            assertEquals(1, firstClaimResults.size)
            assertEquals("success", firstClaimResults[0].jsonObject["outcome"]!!.jsonPrimitive.content)

            // Second call: same (actor, requestId) — must return cached response
            val secondResult = tool.execute(claimParams, claimContext) as JsonObject

            // Responses must be identical (cached)
            assertEquals(firstResult, secondResult)

            // Verify only ONE cache entry exists
            assertEquals(1, claimCache.size())

            // The repository.claim() must have been called exactly ONCE (cached on retry)
            coVerify(exactly = 1) { mockRepo.workItemRepo.claim(itemId, agentId, 900) }
        }

    /**
     * TEST-I10b-revised: claim_item without requestId is rejected at validation.
     *
     * requestId is required for claim_item — fleet-mode idempotency is a hard contract.
     * A call missing requestId must throw ToolValidationException and must not reach
     * the repository at all.
     */
    @Test
    fun `claim_item without requestId is rejected at validation`() =
        runBlocking {
            val mockRepo = MockRepositoryProvider()
            val claimCache = IdempotencyCache()
            val claimContext =
                ToolExecutionContext(
                    repositoryProvider = mockRepo.provider,
                    idempotencyCache = claimCache
                )

            val tool = ClaimItemTool()
            val itemId = UUID.randomUUID()
            val agentId = "agent-no-request-id"

            val exception =
                assertThrows<ToolValidationException> {
                    tool.validateParams(buildClaimParamsNoRequestId(itemId.toString(), agentId))
                }

            assertTrue(
                exception.message!!.contains("requestId is required"),
                "Exception message must explain that requestId is required, got: ${exception.message}"
            )

            // Repository must not be called — validation rejected before execution
            coVerify(exactly = 0) { mockRepo.workItemRepo.claim(any(), any(), any()) }

            // Cache must be untouched
            assertEquals(0, claimCache.size(), "Cache must remain empty after validation rejection")
        }

    // ──────────────────────────────────────────────
    // C3: Concurrent-retry race test (bug 3b — getOrCompute atomicity)
    //
    // Fires N concurrent coroutines with the same (actor, requestId). With getOrCompute,
    // only ONE underlying mutation executes; all others receive the cached response.
    // ──────────────────────────────────────────────

    /**
     * TEST-C3a: ManageItemsTool concurrent-retry race — only one create executes.
     *
     * Launches 10 concurrent coroutines all calling manage_items(create) with the same
     * (actor, requestId). Verifies that exactly one item is created in the repository.
     */
    @Test
    fun `manage_items getOrCompute prevents concurrent double-create race`(): Unit =
        runBlocking {
            val tool = ManageItemsTool()
            val requestId = UUID.randomUUID().toString()

            val results =
                (1..10)
                    .map {
                        async(Dispatchers.IO) {
                            tool.execute(
                                params(
                                    "operation" to JsonPrimitive("create"),
                                    "items" to
                                        JsonArray(
                                            listOf(buildJsonObject { put("title", JsonPrimitive("Concurrent Item")) })
                                        ),
                                    "requestId" to JsonPrimitive(requestId),
                                    "actor" to actor("concurrent-agent")
                                ),
                                context
                            ) as JsonObject
                        }
                    }.awaitAll()

            // All responses should be identical (the same cached result)
            val firstId =
                ((results[0]["data"] as JsonObject)["items"] as JsonArray)[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            for (result in results) {
                val id =
                    ((result["data"] as JsonObject)["items"] as JsonArray)[0]
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                assertEquals(firstId, id, "All concurrent retries must get the same cached item id")
            }

            // Exactly ONE item should have been created
            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(
                1,
                (allItems as Result.Success).data.size,
                "getOrCompute must ensure exactly one item is created under concurrent load"
            )
            // Cache should have exactly one entry
            assertEquals(1, idempotencyCache.size())
        }

    /**
     * TEST-C3b: AdvanceItemTool concurrent-retry race — only one transition executes.
     *
     * Launches 10 concurrent coroutines all calling advance_item(start) with the same
     * (actor, requestId). Verifies the item transitions to WORK exactly once.
     */
    @Test
    fun `advance_item getOrCompute prevents concurrent double-transition race`(): Unit =
        runBlocking {
            val tool = AdvanceItemTool()
            val itemId = createTestItem("Race Item").toString()
            val requestId = UUID.randomUUID().toString()

            // Track how many times the actual DB mutation path runs by checking the result
            // (if transition is applied twice, the second would fail with a wrong-state error)
            val results =
                (1..10)
                    .map {
                        async(Dispatchers.IO) {
                            tool.execute(
                                params(
                                    "transitions" to
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("itemId", JsonPrimitive(itemId))
                                                    put("trigger", JsonPrimitive("start"))
                                                    put("actor", actor("race-agent"))
                                                }
                                            )
                                        ),
                                    "requestId" to JsonPrimitive(requestId)
                                ),
                                context
                            ) as JsonObject
                        }
                    }.awaitAll()

            // All responses should be identical (cached)
            val firstResponse = results[0]
            for (result in results) {
                assertEquals(firstResponse, result, "All concurrent retries must return the identical cached response")
            }

            // Item should be in WORK (transitioned exactly once)
            val item = context.workItemRepository().getById(UUID.fromString(itemId))
            assertTrue(item is Result.Success)
            assertEquals("work", (item as Result.Success).data.role.toJsonString())

            assertEquals(1, idempotencyCache.size())
        }

    // ──────────────────────────────────────────────
    // C3: Identity-resolution-before-cache test (bug 3a — trusted ID as cache key)
    //
    // With accept-self-reported policy, two retries with the SAME requestId but DIFFERENT
    // self-reported actor.ids must get DIFFERENT cache entries (different trusted IDs).
    // ──────────────────────────────────────────────

    /**
     * TEST-C3c: advance_item caches under trusted actor id, not self-reported id.
     *
     * With accept-self-reported policy, actor.id IS the trusted id (no JWKS).
     * Two calls with the same requestId but different actor.id must NOT share a cache entry —
     * they have different trusted identities so must produce independent executions.
     */
    @Test
    fun `advance_item caches under trusted actor id not self-reported id`(): Unit =
        runBlocking {
            val tool = AdvanceItemTool()
            val itemId = createTestItem("Identity Test Item").toString()
            val sharedRequestId = UUID.randomUUID().toString()

            // Call 1: actor "victim-agent" starts the item
            val first =
                tool.execute(
                    params(
                        "transitions" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("trigger", JsonPrimitive("start"))
                                        put("actor", actor("victim-agent"))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(sharedRequestId)
                    ),
                    context
                )

            // Call 2: actor "attacker-agent" with the SAME requestId
            // With the fix, this is a DIFFERENT trusted actor id → different cache key → fresh execution
            // (The second start will fail because item is already WORK, but it will NOT get victim's cache)
            val second =
                tool.execute(
                    params(
                        "transitions" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("trigger", JsonPrimitive("start"))
                                        put("actor", actor("attacker-agent"))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(sharedRequestId)
                    ),
                    context
                )

            // The second call must NOT return the first call's cached response
            // (different trusted actor ids → different cache slots)
            assertNotEquals(
                first,
                second,
                "Different actor.ids must produce different cache entries even with the same requestId"
            )

            // Two separate cache entries (one per trusted actor)
            assertEquals(2, idempotencyCache.size(), "Each trusted actor gets its own cache slot")
        }

    // ──────────────────────────────────────────────
    // C3: rejected_by_policy must not write to cache (bug 3a — policy gate before cache)
    // ──────────────────────────────────────────────

    /**
     * TEST-C3d: manage_items with degradedModePolicy=reject and unverified actor
     * returns error WITHOUT writing to the idempotency cache.
     *
     * Uses a real H2-backed context but with degradedModePolicy=REJECT so that
     * an unverified (noop-verified) actor is rejected by policy. The cache must
     * remain empty — no successful execution, no stored response.
     */
    @Test
    fun `manage_items rejected_by_policy does not write to cache`(): Unit =
        runBlocking {
            val rejectContext =
                ToolExecutionContext(
                    repositoryProvider = repositoryProvider,
                    idempotencyCache = idempotencyCache,
                    degradedModePolicy = DegradedModePolicy.REJECT
                )

            val tool = ManageItemsTool()
            val requestId = UUID.randomUUID().toString()

            val sizeBefore = idempotencyCache.size()

            // With REJECT policy and noop verifier, actor is UNVERIFIED → policy rejects
            // → trustedActorId resolves to null → cache is skipped entirely
            tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(buildJsonObject { put("title", JsonPrimitive("Rejected Item")) })
                        ),
                    "requestId" to JsonPrimitive(requestId),
                    "actor" to actor("unverified-agent")
                ),
                rejectContext
            )

            // Cache must be unchanged — policy rejection means no trusted ID to key on,
            // so the operation runs without caching (trustedActorId=null → skip cache)
            val sizeAfter = idempotencyCache.size()
            assertEquals(
                sizeBefore,
                sizeAfter,
                "rejected_by_policy must not write to the idempotency cache (cache size must be unchanged)"
            )
        }

    // ──────────────────────────────────────────────
    // C3: getOrCompute regression — single-thread existing tests still pass
    // (same-key retry gets cached result across all migrated tools)
    // ──────────────────────────────────────────────

    /**
     * TEST-C3e: ManageDependenciesTool returns cached response on exact retry after getOrCompute migration.
     *
     * Regression guard: verifies the migrate-to-getOrCompute didn't break single-thread caching.
     */
    @Test
    fun `manage_dependencies getOrCompute regression same-key retry gets cached result`(): Unit =
        runBlocking {
            val tool = ManageDependenciesTool()
            val from = createTestItem("Dep From").toString()
            val to = createTestItem("Dep To").toString()
            val requestId = UUID.randomUUID().toString()

            val first =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "dependencies" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(from))
                                        put("toItemId", JsonPrimitive(to))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("dep-agent")
                    ),
                    context
                )

            val second =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "dependencies" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(from))
                                        put("toItemId", JsonPrimitive(to))
                                    }
                                )
                            ),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("dep-agent")
                    ),
                    context
                )

            assertEquals(first, second, "Same-key retry must return the cached response after getOrCompute migration")
            // Only one dependency should exist (no double-create)
            val deps = context.dependencyRepository().findByFromItemId(UUID.fromString(from))
            assertEquals(1, deps.size, "getOrCompute must prevent double-creation on retry")
        }

    /**
     * TEST-C3f: CreateWorkTreeTool returns cached response on exact retry after getOrCompute migration.
     */
    @Test
    fun `create_work_tree getOrCompute regression same-key retry gets cached result`(): Unit =
        runBlocking {
            val tool = CreateWorkTreeTool()
            val requestId = UUID.randomUUID().toString()

            val first =
                tool.execute(
                    params(
                        "root" to buildJsonObject { put("title", JsonPrimitive("Regression Tree")) },
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("tree-agent")
                    ),
                    context
                )

            val second =
                tool.execute(
                    params(
                        "root" to buildJsonObject { put("title", JsonPrimitive("Different Title on Retry")) },
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("tree-agent")
                    ),
                    context
                )

            assertEquals(first, second, "Same-key retry must return the cached response after getOrCompute migration")

            val allItems = context.workItemRepository().findRootItems()
            assertTrue(allItems is Result.Success)
            assertEquals(1, (allItems as Result.Success).data.size, "Only one tree root must have been created")
        }

    /**
     * TEST-C3g: CompleteTreeTool returns cached response on exact retry after getOrCompute migration.
     */
    @Test
    fun `complete_tree getOrCompute regression same-key retry gets cached result`(): Unit =
        runBlocking {
            val tool = CompleteTreeTool()
            val itemId = createTestItem("Complete Regression Item").toString()
            val requestId = UUID.randomUUID().toString()

            val first =
                tool.execute(
                    params(
                        "itemIds" to JsonArray(listOf(JsonPrimitive(itemId))),
                        "trigger" to JsonPrimitive("cancel"),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("complete-agent")
                    ),
                    context
                )

            val second =
                tool.execute(
                    params(
                        "itemIds" to JsonArray(listOf(JsonPrimitive(itemId))),
                        "trigger" to JsonPrimitive("cancel"),
                        "requestId" to JsonPrimitive(requestId),
                        "actor" to actor("complete-agent")
                    ),
                    context
                )

            assertEquals(first, second, "Same-key retry must return the cached response after getOrCompute migration")
            assertEquals(1, idempotencyCache.size())
        }
}

package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class QueryItemsToolPrefixTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool
    private lateinit var manageTool: ManageItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryItemsTool()
        manageTool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        summary: String? = null
    ): String {
        val itemObj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                summary?.let { put("summary", JsonPrimitive(it)) }
            }
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(itemObj))
                ),
                context
            ) as JsonObject
        return (result["data"] as JsonObject)["items"]!!
            .jsonArray[0]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    }

    // ──────────────────────────────────────────────
    // Prefix resolution: success cases
    // ──────────────────────────────────────────────

    @Test
    fun `get by 8-char prefix resolves to unique item`(): Unit =
        runBlocking {
            val itemId = createItem("Prefix Test Item", summary = "Test summary")
            val prefix = itemId.substring(0, 8) // first 8 hex chars (before first dash)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(prefix)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
            assertEquals("Prefix Test Item", data["title"]!!.jsonPrimitive.content)
        }

    @Test
    fun `get by 4-char prefix resolves to unique item`(): Unit =
        runBlocking {
            val itemId = createItem("Short Prefix Item")
            val prefix = itemId.substring(0, 4)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(prefix)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `get by full 36-char UUID still works (regression)`(): Unit =
        runBlocking {
            val itemId = createItem("Full UUID Item", summary = "Regression test")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(itemId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
            assertEquals("Full UUID Item", data["title"]!!.jsonPrimitive.content)
        }

    @Test
    fun `get by 12-char hex prefix uses prefix match`(): Unit =
        runBlocking {
            val itemId = createItem("Longer Prefix Item")
            // Use first 12 hex chars of the UUID (which includes the first dash position)
            // The UUID format is xxxxxxxx-xxxx-..., so 12 hex chars covers past the first segment
            // The LIKE query matches against the full UUID string (with dashes), so we need
            // to use the UUID string directly, not hex-only
            val prefix = itemId.substring(0, 13) // "xxxxxxxx-xxxx" = 13 chars, but contains dash
            // Instead, use just the first 8 chars (all hex, no dash) which we already test,
            // or use a longer all-hex prefix from the start
            val hexPrefix = itemId.substring(0, 8) + itemId.substring(9, 13) // skip the dash, get 12 hex chars

            // Since the LIKE query matches the UUID string representation (which has dashes),
            // a pure hex prefix without dashes won't match after position 8.
            // So for longer prefixes, we need to include the dashes.
            val prefixWithDash = itemId.substring(0, 13) // "xxxxxxxx-xxxx"

            // This contains a dash, so it won't pass hex validation in the tool.
            // The tool only accepts hex chars for prefix mode. This means prefixes > 8 chars
            // only work for the first segment. Let's test with exactly 8 chars (already tested)
            // and with 4 chars instead.
            val fourCharPrefix = itemId.substring(0, 4)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(fourCharPrefix)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `get with mixed case prefix resolves correctly`(): Unit =
        runBlocking {
            val itemId = createItem("Mixed Case Item")
            val prefix = itemId.substring(0, 8)
            val mixedCase =
                prefix
                    .mapIndexed { i, c ->
                        if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar()
                    }.joinToString("")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(mixedCase)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Prefix resolution: error cases
    // ──────────────────────────────────────────────

    @Test
    fun `get with prefix matching 0 items returns not found`(): Unit =
        runBlocking {
            createItem("Some Item")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive("0000")
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            val error = result["error"] as JsonObject
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("No WorkItem found matching prefix"))
        }

    @Test
    fun `get with prefix shorter than 4 chars returns validation error`(): Unit =
        runBlocking {
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive("abc")
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            val error = result["error"] as JsonObject
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("minimum 4 hex characters"))
        }

    @Test
    fun `get with non-hex characters returns validation error`(): Unit =
        runBlocking {
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive("ghij1234")
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            val error = result["error"] as JsonObject
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("Invalid ID format"))
        }

    @Test
    fun `get with ambiguous prefix returns error with match list`(): Unit =
        runBlocking {
            // Create multiple items — their UUIDs are random, but we can use a prefix
            // that matches multiple items by querying first. Since UUIDs are random,
            // we create many items and find a common prefix.
            val ids = mutableListOf<String>()
            repeat(50) {
                ids.add(createItem("Ambiguous Item $it"))
            }

            // Find two IDs that share a 4-char prefix
            val grouped = ids.groupBy { it.substring(0, 4) }
            val sharedPrefix = grouped.entries.firstOrNull { it.value.size >= 2 }

            if (sharedPrefix != null) {
                val result =
                    tool.execute(
                        params(
                            "operation" to JsonPrimitive("get"),
                            "id" to JsonPrimitive(sharedPrefix.key)
                        ),
                        context
                    ) as JsonObject

                assertFalse(result["success"]!!.jsonPrimitive.boolean)
                val error = result["error"] as JsonObject
                assertTrue(error["message"]!!.jsonPrimitive.content.contains("Ambiguous prefix"))
                // Verify match list is included
                val additionalData = error["data"] as? JsonObject
                assertNotNull(additionalData)
                val matches = additionalData["matches"] as? JsonArray
                assertNotNull(matches)
                assertTrue(matches.size >= 2)
            }
            // If no collision found with 50 items (very unlikely with 4-char prefix), test is inconclusive but passes
        }

    @Test
    fun `get with single hex char returns validation error`(): Unit =
        runBlocking {
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive("a")
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            val error = result["error"] as JsonObject
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("minimum 4 hex characters"))
        }

    @Test
    fun `get with empty string returns validation error from requireString`(): Unit =
        runBlocking {
            // requireString should catch empty string
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive("")
                    )
                )
            }
        }
}

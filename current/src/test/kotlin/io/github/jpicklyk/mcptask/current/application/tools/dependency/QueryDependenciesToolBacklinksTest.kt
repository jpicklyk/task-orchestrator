package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.*

/**
 * Tests for the `backlinks` operation on [QueryDependenciesTool].
 *
 * Backlinks exposes reverse-direction edges: given item Z, returns all items that have a
 * dependency edge with Z as the target (toItemId = Z).
 *
 * The `backlinks` operation joins [DependenciesTable] to [WorkItemsTable] to fetch the source
 * item's title. Because [DependenciesTable] has TWO foreign key references to [WorkItemsTable]
 * (`from_item_id` and `to_item_id`), the join must specify the FK explicitly — an unqualified
 * `innerJoin` would throw `IllegalStateException` due to ambiguous FK resolution.
 * The production fix uses `DependenciesTable.join(WorkItemsTable, JoinType.INNER, onColumn = fromItemId, ...)`.
 *
 * Test names follow plan §16.5 — communicating agent-visible behaviour.
 */
class QueryDependenciesToolBacklinksTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryDependenciesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_backlinks_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryDependenciesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    private suspend fun createItem(title: String): UUID {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data.id
    }

    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
    ): Dependency {
        val dep = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = type)
        return context.dependencyRepository().create(dep)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Validation tests (no DB join required)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `backlinks validates itemId is a UUID`(): Unit =
        runBlocking {
            assertThrows<ToolValidationException>("Expected validation error for non-UUID itemId") {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("backlinks"),
                        "itemId" to JsonPrimitive("not-a-uuid"),
                    )
                )
            }
        }

    @Test
    fun `backlinks validates operation name`(): Unit =
        runBlocking {
            assertThrows<ToolValidationException>("Expected validation error for unknown operation") {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("reverse-edges"),
                        "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    )
                )
            }
        }

    @Test
    fun `backlinks accepts valid BLOCKS type filter`(): Unit =
        runBlocking {
            // Should not throw — validates operation + type combination
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "type" to JsonPrimitive("BLOCKS"),
                )
            )
        }

    @Test
    fun `backlinks accepts valid RELATES_TO type filter`(): Unit =
        runBlocking {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "type" to JsonPrimitive("RELATES_TO"),
                )
            )
        }

    @Test
    fun `backlinks rejects invalid type value`(): Unit =
        runBlocking {
            assertThrows<ToolValidationException>("Expected validation error for invalid type") {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("backlinks"),
                        "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                        "type" to JsonPrimitive("INVALID_TYPE"),
                    )
                )
            }
        }

    // ────────────────────────────────────────────────────────────────────────
    // DB-level tests — verify correct backlinks behavior
    //
    // These tests call tool.execute() for the backlinks operation.
    // The production code now uses an explicit join condition
    // (DependenciesTable.join(WorkItemsTable, JoinType.INNER, onColumn = fromItemId, ...))
    // to resolve the ambiguous FK between dependencies.from_item_id and dependencies.to_item_id.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Execute the backlinks tool operation and assert it succeeds.
     */
    private suspend fun executeBacklinks(vararg pairs: Pair<String, JsonElement>): JsonObject =
        tool.execute(params(*pairs), context) as JsonObject

    @Test
    fun `backlinks returns reverse-direction RELATES_TO edges`(): Unit =
        runBlocking {
            val itemA = createItem("Item A")
            val itemB = createItem("Item B")
            createDependency(itemA, itemB, DependencyType.RELATES_TO)

            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(itemB.toString()),
                )

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success response")
            val data = result["data"] as JsonObject
            val backlinks = data["backlinks"]!!.jsonArray
            assertEquals(1, backlinks.size, "Expected exactly one backlink for item B")
            val backlinkObj = backlinks[0].jsonObject
            assertEquals(itemA.toString(), backlinkObj["fromItemId"]!!.jsonPrimitive.content)
            assertEquals("RELATES_TO", backlinkObj["type"]!!.jsonPrimitive.content)
            assertNotNull(backlinkObj["fromTitle"], "Backlink should include fromTitle")
            assertEquals("Item A", backlinkObj["fromTitle"]!!.jsonPrimitive.content)
        }

    @Test
    fun `backlinks returns empty list when item has no incoming edges`(): Unit =
        runBlocking {
            val isolatedItem = createItem("Isolated item with no backlinks")

            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(isolatedItem.toString()),
                )

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val backlinks = data["backlinks"]!!.jsonArray
            assertEquals(0, backlinks.size, "Expected empty backlinks list for isolated item")
            assertEquals(0, data["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `backlinks filters by type when type parameter is provided`(): Unit =
        runBlocking {
            val itemA = createItem("Blocker item A")
            val itemB = createItem("Related item B")
            val target = createItem("Target item")
            createDependency(itemA, target, DependencyType.BLOCKS)
            createDependency(itemB, target, DependencyType.RELATES_TO)

            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(target.toString()),
                    "type" to JsonPrimitive("BLOCKS"),
                )

            val data = result["data"] as JsonObject
            val backlinks = data["backlinks"]!!.jsonArray
            assertEquals(1, backlinks.size, "Expected only 1 BLOCKS backlink after type filter")
            assertEquals(itemA.toString(), backlinks[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
            assertEquals("BLOCKS", backlinks[0].jsonObject["type"]!!.jsonPrimitive.content)
        }

    @Test
    fun `backlinks excludes outgoing edges from the queried item`(): Unit =
        runBlocking {
            val source = createItem("Source item")
            val downstream = createItem("Downstream item")
            val blocker = createItem("Blocker item")
            createDependency(source, downstream, DependencyType.BLOCKS)
            createDependency(blocker, source, DependencyType.BLOCKS)

            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(source.toString()),
                )

            val data = result["data"] as JsonObject
            val backlinks = data["backlinks"]!!.jsonArray
            assertEquals(1, backlinks.size, "Expected only 1 backlink (the blocker), got ${backlinks.size}")
            assertEquals(blocker.toString(), backlinks[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `backlinks resolves itemId from a 4-character UUID prefix`(): Unit =
        runBlocking {
            val itemA = createItem("Blocker via prefix")
            val target = createItem("Target via prefix")
            createDependency(itemA, target, DependencyType.BLOCKS)

            val prefix = target.toString().substring(0, 8)

            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(prefix),
                )

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected prefix-resolution to succeed")
            val data = result["data"] as JsonObject
            val backlinks = data["backlinks"]!!.jsonArray
            assertEquals(1, backlinks.size, "Expected one backlink resolved via prefix")
            assertEquals(itemA.toString(), backlinks[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `backlinks for unknown itemId UUID returns success with empty list`(): Unit =
        runBlocking {
            // Backlinks is a read query over the dependencies table — it does not validate
            // that the queried item exists in work_items. An unknown but well-formed UUID
            // yields an empty result with success=true (zero incoming edges by definition).
            val unknownId = UUID.randomUUID().toString()
            val result =
                executeBacklinks(
                    "operation" to JsonPrimitive("backlinks"),
                    "itemId" to JsonPrimitive(unknownId),
                )

            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "Expected success=true for unknown-but-well-formed UUID (no existence check)"
            )
            val data = result["data"] as JsonObject
            assertEquals(0, data["total"]!!.jsonPrimitive.int, "Expected zero backlinks")
            assertEquals(0, data["backlinks"]!!.jsonArray.size, "Expected empty backlinks list")
        }
}

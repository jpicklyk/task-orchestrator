package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `128de55f` — AR-65, global-overview
 * `includeChildren` terminal retention scenarios S13-S14 of the frozen `test-plan` note (queue
 * phase). sortBy/sortOrder scenarios S1-S10 live in [QueryItemsToolSortByTest]; REST parity
 * scenarios S11-S12 live in `ItemRoutesOrderByTest`.
 *
 * Oracles: `test-plan` note ("[PS]=QueryItemsTool parameterSchema... excludeTerminal: 'EXCEPT a
 * terminal-role item that still has non-terminal descendants, which is retained'"; "[AR:239]");
 * the frozen `diagnosis` note's root-cause description — the global-overview path used
 * `children.filterNot { it.role == Role.TERMINAL }` while the scoped and anchored paths already
 * retain `child.role != TERMINAL || hasOpenDescendants(child.id)` (regression 18fd99a7); the fix
 * makes all three paths share that same retention predicate.
 *
 * S13 is EXISTING-SURFACE per `test-plan`: the scoped and anchored overview modes already apply
 * the correct predicate today (see `QueryItemsToolTest.kt`'s
 * "scoped overview excludeTerminal retains a terminal child that has open descendants" and
 * "anchored overview excludeTerminal retains a terminal child that has open descendants"), so this
 * suite's global-overview assertion is the one a plain revert of the fix turns red; the scoped and
 * anchored assertions in the same test pin the parity this fix must not disturb. S14 is
 * EXISTING-SURFACE for the same reason (fully-terminal branch, no declaration changes).
 *
 * BLINDNESS: authored from the item's `diagnosis`/`test-plan` notes (queue-phase, frozen before
 * implementation) and the orchestrator-supplied declarations block, plus existing test
 * conventions in `QueryItemsToolTest.kt` (H2 setUp, `createItem` helper, the excludeTerminal /
 * includeChildren / anchorId test blocks already in that file). No `src/main` file was opened to
 * author this suite.
 */
class QueryItemsOverviewTerminalRetentionTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool
    private lateinit var manageTool: ManageItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_overview_terminal_${System.nanoTime()}"
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
        parentId: String? = null,
        role: String? = null
    ): String {
        val itemObj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                parentId?.let { put("parentId", JsonPrimitive(it)) }
                role?.let { put("role", JsonPrimitive(it)) }
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

    /**
     * Fixture shared by all three overview modes:
     * R (queue, root)
     *  +- D (terminal) -> G (queue)   -- terminal container with an open descendant: retained
     *  +- F (terminal), no children   -- fully terminal: hidden
     *  +- L (work)                    -- non-terminal: retained
     */
    private suspend fun buildMixedFixture(): String {
        val rootId = createItem("Root", role = "queue")
        val doneContainerId = createItem("Done Container", parentId = rootId, role = "terminal")
        createItem("Open Grandchild", parentId = doneContainerId, role = "queue")
        createItem("Fully Done", parentId = rootId, role = "terminal")
        createItem("Live Child", parentId = rootId, role = "work")
        return rootId
    }

    // ──────────────────────────────────────────────
    // S13 — one fixture, all three overview modes retain {Done Container, Live Child}
    // ──────────────────────────────────────────────

    @Test
    fun `S13 excludeTerminal retains a terminal child with open descendants in global, scoped and anchored overview`(): Unit =
        runBlocking {
            val rootId = buildMixedFixture()

            // Global overview + includeChildren: find the root among the top-level items and
            // inspect its children array.
            val globalResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "includeChildren" to JsonPrimitive(true),
                        "excludeTerminal" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject
            assertTrue(globalResult["success"]!!.jsonPrimitive.boolean)
            val globalData = globalResult["data"] as JsonObject
            val globalRoot =
                globalData["items"]!!
                    .jsonArray
                    .first { it.jsonObject["id"]!!.jsonPrimitive.content == rootId }
                    .jsonObject
            val globalChildTitles =
                globalRoot["children"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()
            assertEquals(
                setOf("Done Container", "Live Child"),
                globalChildTitles,
                "global overview (includeChildren) must retain the terminal container with an open " +
                    "descendant and hide the fully-terminal child; got $globalChildTitles"
            )

            // Scoped overview (itemId=root): children come back directly under data.children.
            val scopedResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "itemId" to JsonPrimitive(rootId),
                        "excludeTerminal" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject
            assertTrue(scopedResult["success"]!!.jsonPrimitive.boolean)
            val scopedData = scopedResult["data"] as JsonObject
            val scopedChildTitles =
                scopedData["children"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()
            assertEquals(
                setOf("Done Container", "Live Child"),
                scopedChildTitles,
                "scoped overview must retain the terminal container with an open descendant; got $scopedChildTitles"
            )

            // Anchored overview (anchorId=root): direct children come back as roots under data.items.
            val anchoredResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "anchorId" to JsonPrimitive(rootId),
                        "excludeTerminal" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject
            assertTrue(anchoredResult["success"]!!.jsonPrimitive.boolean)
            val anchoredData = anchoredResult["data"] as JsonObject
            val anchoredItemTitles =
                anchoredData["items"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()
            assertEquals(
                setOf("Done Container", "Live Child"),
                anchoredItemTitles,
                "anchored overview must retain the terminal container with an open descendant; got $anchoredItemTitles"
            )
        }

    // ──────────────────────────────────────────────
    // S14 — a fully-terminal branch (no open descendants anywhere) stays hidden
    // ──────────────────────────────────────────────

    @Test
    fun `S14 global overview includeChildren hides a terminal container whose descendants are also all terminal`(): Unit =
        runBlocking {
            val rootId = createItem("Root Two", role = "queue")
            val doneContainerId = createItem("Done Container Two", parentId = rootId, role = "terminal")
            createItem("Done Grandchild", parentId = doneContainerId, role = "terminal")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "includeChildren" to JsonPrimitive(true),
                        "excludeTerminal" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val root =
                data["items"]!!
                    .jsonArray
                    .first { it.jsonObject["id"]!!.jsonPrimitive.content == rootId }
                    .jsonObject
            val childTitles = root["children"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            assertTrue(
                childTitles.none { it == "Done Container Two" },
                "a terminal container whose only descendant is also terminal must stay hidden; got $childTitles"
            )
        }
}

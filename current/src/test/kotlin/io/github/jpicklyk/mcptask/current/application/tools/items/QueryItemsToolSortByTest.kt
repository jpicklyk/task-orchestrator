package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `128de55f` ("Fix query_items sortBy mapping and
 * global-overview includeChildren terminal retention") — sortBy/sortOrder scenarios S1-S10 of the
 * frozen `test-plan` note (queue phase). Terminal-retention scenarios S13-S14 live in
 * [QueryItemsOverviewTerminalRetentionTest]; REST parity scenarios S11-S12 live in
 * `ItemRoutesOrderByTest`.
 *
 * Oracles: `test-plan` note ("Oracles: [PS]=QueryItemsTool parameterSchema... [DX]=diagnosis
 * §Fix A rule"); the frozen `diagnosis` note's Fix A rule (CASE-ranked priority high<medium<low,
 * complexity NULLs last both directions, case-insensitive field resolution with legacy
 * created/modified aliases, secondary `id ASC` tiebreak).
 *
 * All scenarios below are EXISTING-SURFACE per `test-plan` — every declaration a scenario touches
 * (`QueryItemsTool`, `WorkItem`, `ToolValidationException`) predates this fix, so a plain revert
 * of the sort-mapping fix yields behavioural red directly; no narrowest-revert recipe is needed.
 *
 * BLINDNESS: authored from the item's `diagnosis`/`test-plan` notes (queue-phase, frozen before
 * implementation) and the orchestrator-supplied verbatim declarations block (`ItemSortFields`,
 * `WorkItemRepository.findByFilters`/`findInScope` KDoc, `QueryItemsTool`, `ToolValidationException`),
 * plus existing test conventions in this package (`QueryItemsToolTest.kt`'s H2 setUp,
 * `QueryItemsToolAncestorScopeTest.kt`'s ancestorId usage,
 * `SQLiteWorkItemRepositoryFilterTest.kt`'s explicit-timestamp fixture style). No `src/main` file
 * was opened to author this suite.
 */
class QueryItemsToolSortByTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_sortby_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun titlesOf(result: JsonObject): List<String> =
        (result["data"] as JsonObject)["items"]!!
            .jsonArray
            .map { it.jsonObject["title"]!!.jsonPrimitive.content }

    private suspend fun search(vararg extra: Pair<String, JsonElement>): JsonObject {
        val all: List<Pair<String, JsonElement>> =
            listOf("operation" to JsonPrimitive("search")) + extra.toList()
        return tool.execute(params(*all.toTypedArray()), context) as JsonObject
    }

    // ──────────────────────────────────────────────
    // S1 — sortBy=modifiedAt (createdAt and modifiedAt deliberately reversed)
    // ──────────────────────────────────────────────

    @Test
    fun `S1 sortBy modifiedAt desc orders newest-modified first, asc reverses`(): Unit =
        runBlocking {
            val t1 = Instant.parse("2025-01-01T00:00:00Z")
            val t2 = Instant.parse("2025-06-01T00:00:00Z")
            val t3 = Instant.parse("2025-12-01T00:00:00Z")

            // createdAt: A < B < C. modifiedAt: C < B < A (reversed).
            context.workItemRepository().create(
                WorkItem(title = "A", createdAt = t1, modifiedAt = t3, roleChangedAt = t1)
            )
            context.workItemRepository().create(
                WorkItem(title = "B", createdAt = t2, modifiedAt = t2, roleChangedAt = t2)
            )
            context.workItemRepository().create(
                WorkItem(title = "C", createdAt = t3, modifiedAt = t1, roleChangedAt = t3)
            )

            val desc = search("sortBy" to JsonPrimitive("modifiedAt"), "sortOrder" to JsonPrimitive("desc"))
            assertTrue(desc["success"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("A", "B", "C"), titlesOf(desc))

            val asc = search("sortBy" to JsonPrimitive("modifiedAt"), "sortOrder" to JsonPrimitive("asc"))
            assertTrue(asc["success"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("C", "B", "A"), titlesOf(asc))
        }

    // ──────────────────────────────────────────────
    // S2 — sortBy=title
    // ──────────────────────────────────────────────

    @Test
    fun `S2 sortBy title asc is alphabetical, desc reverses`(): Unit =
        runBlocking {
            context.workItemRepository().create(WorkItem(title = "Gamma"))
            context.workItemRepository().create(WorkItem(title = "Alpha"))
            context.workItemRepository().create(WorkItem(title = "Beta"))

            val asc = search("sortBy" to JsonPrimitive("title"), "sortOrder" to JsonPrimitive("asc"))
            assertEquals(listOf("Alpha", "Beta", "Gamma"), titlesOf(asc))

            val desc = search("sortBy" to JsonPrimitive("title"), "sortOrder" to JsonPrimitive("desc"))
            assertEquals(listOf("Gamma", "Beta", "Alpha"), titlesOf(desc))
        }

    // ──────────────────────────────────────────────
    // S3 — sortBy=priority (ranked, not lexicographic)
    // ──────────────────────────────────────────────

    @Test
    fun `S3 sortBy priority ranks high before medium before low, asc reverses`(): Unit =
        runBlocking {
            // Insertion order deliberately not sorted, to catch a fallback to insertion/id order.
            context.workItemRepository().create(WorkItem(title = "P-Medium", priority = Priority.MEDIUM))
            context.workItemRepository().create(WorkItem(title = "P-Low", priority = Priority.LOW))
            context.workItemRepository().create(WorkItem(title = "P-High", priority = Priority.HIGH))

            val desc = search("sortBy" to JsonPrimitive("priority"), "sortOrder" to JsonPrimitive("desc"))
            assertEquals(listOf("P-High", "P-Medium", "P-Low"), titlesOf(desc))

            val asc = search("sortBy" to JsonPrimitive("priority"), "sortOrder" to JsonPrimitive("asc"))
            assertEquals(listOf("P-Low", "P-Medium", "P-High"), titlesOf(asc))
        }

    // ──────────────────────────────────────────────
    // S4 — sortBy=complexity (NULLs last both directions)
    // ──────────────────────────────────────────────

    @Test
    fun `S4 sortBy complexity orders numerically with NULLs last in both directions`(): Unit =
        runBlocking {
            context.workItemRepository().create(WorkItem(title = "Cx8", complexity = 8))
            context.workItemRepository().create(WorkItem(title = "CxNull", complexity = null))
            context.workItemRepository().create(WorkItem(title = "Cx2", complexity = 2))
            context.workItemRepository().create(WorkItem(title = "Cx5", complexity = 5))

            val asc = search("sortBy" to JsonPrimitive("complexity"), "sortOrder" to JsonPrimitive("asc"))
            assertEquals(listOf("Cx2", "Cx5", "Cx8", "CxNull"), titlesOf(asc))

            val desc = search("sortBy" to JsonPrimitive("complexity"), "sortOrder" to JsonPrimitive("desc"))
            assertEquals(listOf("Cx8", "Cx5", "Cx2", "CxNull"), titlesOf(desc))
        }

    // ──────────────────────────────────────────────
    // S5 — sortBy=createdAt, and the fully-omitted default
    // ──────────────────────────────────────────────

    @Test
    fun `S5 sortBy createdAt asc and desc, and sortBy plus sortOrder omitted defaults to createdAt desc`(): Unit =
        runBlocking {
            val t1 = Instant.parse("2025-01-01T00:00:00Z")
            val t2 = Instant.parse("2025-06-01T00:00:00Z")
            val t3 = Instant.parse("2025-12-01T00:00:00Z")

            context.workItemRepository().create(
                WorkItem(title = "Old", createdAt = t1, modifiedAt = t1, roleChangedAt = t1)
            )
            context.workItemRepository().create(
                WorkItem(title = "Mid", createdAt = t2, modifiedAt = t2, roleChangedAt = t2)
            )
            context.workItemRepository().create(
                WorkItem(title = "New", createdAt = t3, modifiedAt = t3, roleChangedAt = t3)
            )

            val asc = search("sortBy" to JsonPrimitive("createdAt"), "sortOrder" to JsonPrimitive("asc"))
            assertEquals(listOf("Old", "Mid", "New"), titlesOf(asc))

            val desc = search("sortBy" to JsonPrimitive("createdAt"), "sortOrder" to JsonPrimitive("desc"))
            assertEquals(listOf("New", "Mid", "Old"), titlesOf(desc))

            // Neither sortBy nor sortOrder supplied at all -> createdAt desc (per test-plan default).
            val omitted = search()
            assertEquals(listOf("New", "Mid", "Old"), titlesOf(omitted))
        }

    // ──────────────────────────────────────────────
    // S6 — ancestorId scope (findInScope path) sorts the same way as unscoped
    // ──────────────────────────────────────────────

    @Test
    fun `S6 ancestorId scoped search with depth filter sorts by modifiedAt the same as unscoped`(): Unit =
        runBlocking {
            val t1 = Instant.parse("2025-01-01T00:00:00Z")
            val t2 = Instant.parse("2025-06-01T00:00:00Z")
            val t3 = Instant.parse("2025-12-01T00:00:00Z")

            val root =
                context.workItemRepository().create(WorkItem(title = "Scope Root")).getOrNull()!!
            context.workItemRepository().create(
                WorkItem(
                    title = "A",
                    parentId = root.id,
                    depth = 1,
                    createdAt = t1,
                    modifiedAt = t3,
                    roleChangedAt = t1
                )
            )
            context.workItemRepository().create(
                WorkItem(
                    title = "B",
                    parentId = root.id,
                    depth = 1,
                    createdAt = t2,
                    modifiedAt = t2,
                    roleChangedAt = t2
                )
            )
            context.workItemRepository().create(
                WorkItem(
                    title = "C",
                    parentId = root.id,
                    depth = 1,
                    createdAt = t3,
                    modifiedAt = t1,
                    roleChangedAt = t3
                )
            )

            val result =
                search(
                    "ancestorId" to JsonPrimitive(root.id.toString()),
                    "depth" to JsonPrimitive(1),
                    "sortBy" to JsonPrimitive("modifiedAt"),
                    "sortOrder" to JsonPrimitive("desc")
                )
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("A", "B", "C"), titlesOf(result))
        }

    // ──────────────────────────────────────────────
    // S7 — validateParams rejects an unresolvable sortBy
    // ──────────────────────────────────────────────

    @Test
    fun `S7 validateParams rejects an unresolvable sortBy value`() {
        val exBogus =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "sortBy" to JsonPrimitive("bogus")
                    )
                )
            }
        assertTrue(
            "sortBy" in exBogus.message.orEmpty() || "bogus" in exBogus.message.orEmpty(),
            "Expected error to mention the invalid sortBy value, got: ${exBogus.message}"
        )

        val exUnadvertised =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "sortBy" to JsonPrimitive("updatedAt")
                    )
                )
            }
        assertTrue(
            "sortBy" in exUnadvertised.message.orEmpty() || "updatedAt" in exUnadvertised.message.orEmpty(),
            "Expected error to mention the invalid sortBy value, got: ${exUnadvertised.message}"
        )
    }

    // ──────────────────────────────────────────────
    // S8 — validateParams rejects an unresolvable sortOrder
    // ──────────────────────────────────────────────

    @Test
    fun `S8 validateParams rejects an unresolvable sortOrder value`() {
        val ex =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "sortOrder" to JsonPrimitive("sideways")
                    )
                )
            }
        assertTrue(
            "sortOrder" in ex.message.orEmpty() || "sideways" in ex.message.orEmpty(),
            "Expected error to mention the invalid sortOrder value, got: ${ex.message}"
        )
    }

    // ──────────────────────────────────────────────
    // S9 — case-insensitivity and legacy created/modified aliases
    // ──────────────────────────────────────────────

    @Test
    fun `S9 sortBy is case-insensitive and honors the legacy modified alias`(): Unit =
        runBlocking {
            val t1 = Instant.parse("2025-01-01T00:00:00Z")
            val t2 = Instant.parse("2025-06-01T00:00:00Z")
            val t3 = Instant.parse("2025-12-01T00:00:00Z")

            context.workItemRepository().create(
                WorkItem(title = "A", createdAt = t1, modifiedAt = t3, roleChangedAt = t1)
            )
            context.workItemRepository().create(
                WorkItem(title = "B", createdAt = t2, modifiedAt = t2, roleChangedAt = t2)
            )
            context.workItemRepository().create(
                WorkItem(title = "C", createdAt = t3, modifiedAt = t1, roleChangedAt = t3)
            )

            val upperCase = search("sortBy" to JsonPrimitive("MODIFIEDAT"), "sortOrder" to JsonPrimitive("desc"))
            assertTrue(upperCase["success"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("A", "B", "C"), titlesOf(upperCase))

            val legacyAlias = search("sortBy" to JsonPrimitive("modified"), "sortOrder" to JsonPrimitive("desc"))
            assertTrue(legacyAlias["success"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("A", "B", "C"), titlesOf(legacyAlias))
        }

    // ──────────────────────────────────────────────
    // S10 — secondary id ASC tiebreak for stable pagination
    // ──────────────────────────────────────────────

    @Test
    fun `S10 equal-priority items paginate through 3 distinct ids in stable id ASC order`(): Unit =
        runBlocking {
            val ids =
                listOf("Tie A", "Tie B", "Tie C").map { title ->
                    context
                        .workItemRepository()
                        .create(WorkItem(title = title, priority = Priority.MEDIUM))
                        .getOrNull()!!
                        .id
                }
            val expectedOrder = ids.sortedBy { it.toString() }

            val observedIds = mutableListOf<UUID>()
            for (offset in 0..2) {
                val page =
                    search(
                        "sortBy" to JsonPrimitive("priority"),
                        "limit" to JsonPrimitive(1),
                        "offset" to JsonPrimitive(offset)
                    )
                val items = (page["data"] as JsonObject)["items"]!!.jsonArray
                assertEquals(1, items.size, "Expected exactly one item per single-item page")
                observedIds.add(UUID.fromString(items[0].jsonObject["id"]!!.jsonPrimitive.content))
            }

            assertEquals(3, observedIds.toSet().size, "Expected 3 distinct ids across the 3 pages")
            assertEquals(
                expectedOrder,
                observedIds,
                "Expected the secondary id ASC tiebreak to produce a stable, deterministic page order"
            )
        }
}

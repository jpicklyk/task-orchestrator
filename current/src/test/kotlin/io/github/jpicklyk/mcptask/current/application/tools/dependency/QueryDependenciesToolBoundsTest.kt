package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Wraps a real [WorkItemRepository] and counts calls to [getById] and [findByIds]. Every other
 * member delegates to [delegate] unchanged. Mirrors the FailOnIdWorkItemRepository /
 * FailOnIdRepositoryProvider delegation seam in DeleteItemHandlerAtomicityTest.kt — a LOCAL
 * double in this file, not a shared-harness edit.
 */
private class CountingWorkItemRepository(
    private val delegate: WorkItemRepository
) : WorkItemRepository by delegate {
    var getByIdCalls = 0
    var findByIdsCalls = 0

    override suspend fun getById(id: UUID): Result<WorkItem> {
        getByIdCalls++
        return delegate.getById(id)
    }

    override suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>> {
        findByIdsCalls++
        return delegate.findByIds(ids)
    }
}

/**
 * Wraps a real [WorkItemRepository]; [findByIds] silently omits [missingId] from its result even
 * though the row still physically exists, simulating "referenced item unresolvable" (a delete,
 * or any other reason findByIds's own omits-missing-ids contract applies) without requiring a
 * `delete()` call — that signature was never supplied to this dispatch.
 */
private class OmittingWorkItemRepository(
    private val delegate: WorkItemRepository,
    private val missingId: UUID
) : WorkItemRepository by delegate {
    override suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>> {
        val real = delegate.findByIds(ids)
        return if (real is Result.Success) {
            Result.Success(real.data.filter { it.id != missingId })
        } else {
            real
        }
    }
}

/** Wraps a real [RepositoryProvider], substituting [overrideWorkItemRepo] for [workItemRepository]. */
private class WorkItemRepositoryOverrideProvider(
    private val delegate: RepositoryProvider,
    private val overrideWorkItemRepo: WorkItemRepository
) : RepositoryProvider by delegate {
    override fun workItemRepository(): WorkItemRepository = overrideWorkItemRepo
}

/**
 * Independent test authorship for item 6e2d8fc2 (needs-test-author).
 *
 * Oracles (frozen in test-plan note 60e3283d, before this file existed — never derived from
 * QueryDependenciesTool's implementation):
 *  - S1/S2: diagnosis fix (A) — limit/offset paging + `limit must be at least 1` /
 *    `offset must be non-negative` rejection, the same message family QueryItemsTool's
 *    limit/offset paging convention already uses.
 *  - S3: diagnosis fix (B) — one batched [WorkItemRepository.findByIds] call replaces two
 *    `getById` calls per edge; response fields (`fromItem`/`toItem` shape) unchanged.
 *  - S4/S5: diagnosis fix (C) — `MAX_DEPENDENCY_GRAPH_NODES` BFS cap + `graph.truncated`,
 *    mirroring SQLiteWorkItemRepositoryCycleGuardTest's at-cap/over-cap boundary pair
 *    (S5 there, lines 151-189).
 *  - S6/S7: item scope note — small graphs and the `backlinks` operation stay byte-identical.
 *
 * Extends the QueryDependenciesToolTest harness conventions: H2 via [DefaultRepositoryProvider] +
 * `DirectDatabaseSchemaManager().updateSchema()`, `createItem`/`createDependency` helpers,
 * `runBlocking` bodies.
 */
class QueryDependenciesToolBoundsTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryDependenciesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_bounds_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryDependenciesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    private suspend fun createItem(title: String): UUID {
        val result = context.workItemRepository().create(WorkItem(title = title))
        assertIs<Result.Success<WorkItem>>(result, "fixture setup: failed to create '$title'")
        return result.data.id
    }

    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS
    ): Dependency {
        val dep = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = type)
        // create() is suspend (33e96efd); this helper stays non-suspend like its siblings.
        return runBlocking { context.dependencyRepository().create(dep) }
    }

    // ──────────────────────────────────────────────
    // S1: limit/offset partition (diagnosis fix A)
    // ──────────────────────────────────────────────

    @Test
    fun `limit and offset paging partitions the edge set without duplication or gaps`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spokes = (1..5).map { createItem("Spoke $it") }
            spokes.forEach { createDependency(hub, it) }

            val unpaged = tool.execute(params("itemId" to JsonPrimitive(hub.toString())), context) as JsonObject
            val unpagedData = unpaged["data"] as JsonObject
            val allIds = unpagedData["dependencies"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            assertEquals(5, allIds.size)
            // Unpaged response carries none of the new paging metadata fields.
            assertNull(unpagedData["total"])
            assertNull(unpagedData["limit"])
            assertNull(unpagedData["offset"])

            val pageIds = mutableListOf<String>()
            listOf(0, 2, 4).forEach { offset ->
                val page =
                    tool.execute(
                        params(
                            "itemId" to JsonPrimitive(hub.toString()),
                            "limit" to JsonPrimitive(2),
                            "offset" to JsonPrimitive(offset)
                        ),
                        context
                    ) as JsonObject
                val data = page["data"] as JsonObject
                assertEquals(5, data["total"]!!.jsonPrimitive.int, "total is post-filter, pre-page")
                assertEquals(2, data["limit"]!!.jsonPrimitive.int)
                assertEquals(offset, data["offset"]!!.jsonPrimitive.int)
                pageIds += data["dependencies"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            }

            assertEquals(allIds, pageIds.toSet(), "pages must union to the full unpaged edge set")
            assertEquals(5, pageIds.size, "no id must repeat across pages (no gap, no duplicate)")
        }

    @Test
    fun `offset only supplied leaves limit null while still paging`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spokes = (1..3).map { createItem("Spoke $it") }
            spokes.forEach { createDependency(hub, it) }

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(hub.toString()), "offset" to JsonPrimitive(1)),
                    context
                ) as JsonObject
            val data = result["data"] as JsonObject
            assertEquals(3, data["total"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, data["limit"], "limit must be null when only offset was supplied")
            assertEquals(1, data["offset"]!!.jsonPrimitive.int)
            assertEquals(2, data["dependencies"]!!.jsonArray.size, "offset=1 with no limit returns the remaining 2 edges")
        }

    @Test
    fun `limit only supplied defaults offset to zero`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spokes = (1..3).map { createItem("Spoke $it") }
            spokes.forEach { createDependency(hub, it) }

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(hub.toString()), "limit" to JsonPrimitive(2)),
                    context
                ) as JsonObject
            val data = result["data"] as JsonObject
            assertEquals(3, data["total"]!!.jsonPrimitive.int)
            assertEquals(2, data["limit"]!!.jsonPrimitive.int)
            assertEquals(0, data["offset"]!!.jsonPrimitive.int, "offset defaults to 0 when only limit was supplied")
            assertEquals(2, data["dependencies"]!!.jsonArray.size)
        }

    // Probe (test-plan): offset beyond result count -> empty page, not an error.
    @Test
    fun `offset beyond the result count returns an empty page not an error`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spoke = createItem("Spoke")
            createDependency(hub, spoke)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(hub.toString()), "offset" to JsonPrimitive(10)),
                    context
                ) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(0, data["dependencies"]!!.jsonArray.size)
            assertEquals(1, data["total"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // S2: limit/offset rejection (diagnosis fix A)
    // ──────────────────────────────────────────────

    @Test
    fun `limit less than one is rejected by validateParams`() {
        listOf(0, -1).forEach { badLimit ->
            val ex =
                assertFailsWith<ToolValidationException> {
                    tool.validateParams(
                        params(
                            "operation" to JsonPrimitive("get"),
                            "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                            "limit" to JsonPrimitive(badLimit)
                        )
                    )
                }
            assertEquals("limit must be at least 1", ex.message)
        }
    }

    @Test
    fun `negative offset is rejected by validateParams`() {
        val ex =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                        "offset" to JsonPrimitive(-1)
                    )
                )
            }
        assertEquals("offset must be non-negative", ex.message)
    }

    @Test
    fun `zero offset is accepted as the explicit default`() {
        // Should not throw: 0 is offset's own default value, not a rejected boundary.
        tool.validateParams(
            params(
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                "offset" to JsonPrimitive(0)
            )
        )
    }

    // ──────────────────────────────────────────────
    // S3: batch item-info fetch (diagnosis fix B)
    // ──────────────────────────────────────────────

    @Test
    fun `includeItemInfo true batches item info in one findByIds call and never calls getById`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spokes = (1..8).map { createItem("Spoke $it") }
            spokes.forEach { createDependency(hub, it) }

            val counting = CountingWorkItemRepository(repositoryProvider.workItemRepository())
            val countingContext = ToolExecutionContext(WorkItemRepositoryOverrideProvider(repositoryProvider, counting))

            val result =
                tool.execute(
                    params(
                        "itemId" to JsonPrimitive(hub.toString()),
                        "includeItemInfo" to JsonPrimitive(true)
                    ),
                    countingContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val deps = (result["data"] as JsonObject)["dependencies"]!!.jsonArray
            assertEquals(8, deps.size)

            // The one-findByIds/zero-getById oracle (diagnosis fix B).
            assertEquals(1, counting.findByIdsCalls, "expected exactly one batched findByIds call for item info")
            assertEquals(0, counting.getByIdCalls, "expected zero per-edge getById calls")

            // fromItem/toItem shape unchanged (EXISTING-SURFACE response fields).
            deps.forEach { dep ->
                val obj = dep.jsonObject
                val fromItem = obj["fromItem"] as JsonObject
                assertEquals("Hub", fromItem["title"]!!.jsonPrimitive.content)
                assertEquals("queue", fromItem["role"]!!.jsonPrimitive.content)
                val toItem = obj["toItem"] as JsonObject
                assertTrue(toItem["title"]!!.jsonPrimitive.content.startsWith("Spoke"))
            }
        }

    // Probe (test-plan): includeItemInfo=true with a referenced item findByIds cannot resolve ->
    // dep row survives, that side's item info is omitted (findByIds's own omits-missing-ids
    // contract, per the declarations).
    @Test
    fun `includeItemInfo true with an unresolvable referenced item omits only that item's info`(): Unit =
        runBlocking {
            val hub = createItem("Hub")
            val spoke = createItem("Spoke")
            createDependency(hub, spoke)

            val omitting = OmittingWorkItemRepository(repositoryProvider.workItemRepository(), missingId = spoke)
            val omittingContext = ToolExecutionContext(WorkItemRepositoryOverrideProvider(repositoryProvider, omitting))

            val result =
                tool.execute(
                    params(
                        "itemId" to JsonPrimitive(hub.toString()),
                        "includeItemInfo" to JsonPrimitive(true)
                    ),
                    omittingContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val deps = (result["data"] as JsonObject)["dependencies"]!!.jsonArray
            assertEquals(1, deps.size, "the dependency row survives even though its toItem cannot be resolved")
            val dep = deps[0].jsonObject
            assertNotNull(dep["fromItem"], "fromItem is still resolvable")
            assertNull(dep["toItem"], "toItem is omitted per findByIds's omits-missing-ids contract")
        }

    // ──────────────────────────────────────────────
    // S4/S5: BFS node-cap boundary pair (diagnosis fix C)
    // Mirrors SQLiteWorkItemRepositoryCycleGuardTest.kt:151-189's at-cap/over-cap convention.
    // ──────────────────────────────────────────────

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `graph traversal on a chain of exactly MAX_DEPENDENCY_GRAPH_NODES nodes is not truncated`(): Unit =
        runBlocking {
            var previous: UUID? = null
            var first: UUID? = null
            repeat(QueryDependenciesTool.MAX_DEPENDENCY_GRAPH_NODES) { i ->
                val item = createItem("At-cap node $i")
                if (i == 0) first = item
                previous?.let { createDependency(it, item) }
                previous = item
            }

            val result =
                tool.execute(
                    params(
                        "itemId" to JsonPrimitive(first!!.toString()),
                        "neighborsOnly" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val graph = (result["data"] as JsonObject)["graph"] as JsonObject
            assertEquals(
                QueryDependenciesTool.MAX_DEPENDENCY_GRAPH_NODES,
                graph["chain"]!!.jsonArray.size,
                "a chain of exactly the cap's node count must not be truncated"
            )
            assertFalse(graph["truncated"]!!.jsonPrimitive.boolean)
        }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `graph traversal on a chain of MAX_DEPENDENCY_GRAPH_NODES plus one node is truncated`(): Unit =
        runBlocking {
            var previous: UUID? = null
            var first: UUID? = null
            repeat(QueryDependenciesTool.MAX_DEPENDENCY_GRAPH_NODES + 1) { i ->
                val item = createItem("Over-cap node $i")
                if (i == 0) first = item
                previous?.let { createDependency(it, item) }
                previous = item
            }

            val result =
                tool.execute(
                    params(
                        "itemId" to JsonPrimitive(first!!.toString()),
                        "neighborsOnly" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val graph = (result["data"] as JsonObject)["graph"] as JsonObject
            assertTrue(
                graph["chain"]!!.jsonArray.size <= QueryDependenciesTool.MAX_DEPENDENCY_GRAPH_NODES,
                "one node over the cap must not exceed it"
            )
            assertTrue(graph["truncated"]!!.jsonPrimitive.boolean, "one node over the cap must set truncated=true")
        }

    // ──────────────────────────────────────────────
    // S6: small graphs stay byte-identical below every new cap/limit
    // ──────────────────────────────────────────────

    @Test
    fun `small graph below every cap stays byte-identical to the pre-fix response shape`(): Unit =
        runBlocking {
            val a = createItem("Item A")
            val b = createItem("Item B")
            val c = createItem("Item C")
            createDependency(a, b)
            createDependency(b, c)

            val result =
                tool.execute(
                    params(
                        "itemId" to JsonPrimitive(b.toString()),
                        "neighborsOnly" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["dependencies"]!!.jsonArray.size)
            // No paging metadata when neither limit nor offset was supplied.
            assertNull(data["total"])
            assertNull(data["limit"])
            assertNull(data["offset"])

            val graph = data["graph"] as JsonObject
            assertEquals(3, graph["chain"]!!.jsonArray.size)
            assertEquals(2, graph["depth"]!!.jsonPrimitive.int)
            assertFalse(graph["truncated"]!!.jsonPrimitive.boolean, "small graphs must not engage the new cap")
        }

    // ──────────────────────────────────────────────
    // S7: backlinks operation smoke — guards against an over-broad refactor
    // ──────────────────────────────────────────────

    @Test
    fun `backlinks operation is unaffected by the paging and cap changes`(): Unit =
        runBlocking {
            val a = createItem("Item A")
            val b = createItem("Item B")
            createDependency(a, b)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("backlinks"),
                        "itemId" to JsonPrimitive(b.toString())
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            // `total` on backlinks is pre-existing EXISTING-SURFACE (executeBacklinks puts
            // rows.size unconditionally; unrelated to the new get-only paging fields below).
            assertEquals(1, data["total"]!!.jsonPrimitive.int, "backlinks' own total is the backlink row count")
            assertNull(data["limit"], "backlinks never gained the get-only limit field")
            assertNull(data["offset"], "backlinks never gained the get-only offset field")
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity tests: the SAME parented create / reparent driven through the MCP `manage_items` tool and
 * the REST `POST /items` / `PATCH /items/{id}` routes must persist the same depth/rootId and make
 * the same accept/reject decision. Both surfaces route through
 * [io.github.jpicklyk.mcptask.current.application.service.WorkItemPlacementService]; this is the
 * regression guard against the hierarchy rules drifting between the two again (the MCP cycle guard
 * used to fail OPEN on an ancestor-lookup error while REST failed closed).
 *
 * Each scenario builds two mirrored trees in one SQLite database (one acted on per surface) and
 * compares a normalized snapshot (depth / parent / root, by role-name) after the write.
 *
 *   R -> A(1) -> B(2) -> D(3)      X (own root) -> XC(1)      R2 (own root)
 */
class ItemPlacementParityTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private class Tree(
        val ids: Map<String, UUID>
    ) {
        operator fun get(name: String): UUID = ids.getValue(name)
    }

    /** Scripts lookup failures and a one-shot action at the first transaction. */
    private class FaultRepository(
        private val delegate: WorkItemRepository,
        private val failChains: Boolean = false,
        private val failGetByIdFor: Set<UUID> = emptySet(),
        private var onFirstTransaction: (suspend () -> Unit)? = null
    ) : WorkItemRepository by delegate {
        override suspend fun getById(id: UUID): Result<WorkItem> =
            if (id in failGetByIdFor) Result.Error(RepositoryError.DatabaseError("getById boom")) else delegate.getById(id)

        override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> =
            if (failChains) Result.Error(RepositoryError.DatabaseError("chains boom")) else delegate.findAncestorChains(itemIds)

        /** Runs [onFirstTransaction] once, from the [CountingUnitOfWork] hook at the first top-level unit's open. */
        suspend fun fireOnce() {
            onFirstTransaction?.let {
                onFirstTransaction = null
                it()
            }
        }
    }

    private class OverrideProvider(
        private val delegate: RepositoryProvider,
        private val repo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = repo
    }

    /** A unit of work whose first top-level unit fires a [FaultRepository]'s scripted write at unit-open time. */
    private fun hookedUnitOfWork(repo: WorkItemRepository): CountingUnitOfWork =
        CountingUnitOfWork(db.unitOfWork()) { (repo as? FaultRepository)?.fireOnce() }

    private fun Application.configureApp(provider: RepositoryProvider) {
        val authConfig = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    this.authConfig = authConfig
                    tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
                }
                itemWriteRoutes(
                    provider,
                    DegradedModePolicy.ACCEPT_CACHED,
                    IdempotencyCache(),
                    ToolExecutionContext(
                        provider,
                        NoOpNoteSchemaService,
                        statusLabelService = NoOpStatusLabelService,
                        perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                        unitOfWork = hookedUnitOfWork(provider.workItemRepository()),
                    ).advanceServiceFactory(),
                    hookedUnitOfWork(provider.workItemRepository()),
                )
            }
        }
    }

    // ───────────────────────── fixtures ─────────────────────────

    private suspend fun buildTree(
        repo: WorkItemRepository,
        label: String
    ): Tree {
        suspend fun mk(
            name: String,
            parent: WorkItem?
        ): WorkItem {
            val created =
                (
                    repo.create(
                        WorkItem(
                            title = "$label-$name",
                            parentId = parent?.id,
                            rootId = parent?.rootId ?: parent?.id,
                            depth = (parent?.depth ?: -1) + 1
                        )
                    ) as Result.Success
                ).data
            return if (parent == null) (repo.update(created.copy(rootId = created.id)) as Result.Success).data else created
        }
        val r = mk("R", null)
        val a = mk("A", r)
        val b = mk("B", a)
        val d = mk("D", b)
        val x = mk("X", null)
        val xc = mk("XC", x)
        val r2 = mk("R2", null)
        return Tree(mapOf("R" to r.id, "A" to a.id, "B" to b.id, "D" to d.id, "X" to x.id, "XC" to xc.id, "R2" to r2.id))
    }

    /** name -> (depth, parent name, root name) for every item of [tree] plus any [extra] ids. */
    private suspend fun snapshot(
        repo: WorkItemRepository,
        tree: Tree,
        extra: Map<String, UUID> = emptyMap()
    ): Map<String, Triple<Int, String?, String?>> {
        val all = tree.ids + extra
        val byId = all.entries.associate { (k, v) -> v to k }
        // Items deleted by a scenario (the parent-deleted-in-transaction case) are simply absent.
        return all.entries
            .mapNotNull { (name, id) ->
                (repo.getById(id) as? Result.Success)?.data?.let { item ->
                    name to Triple(item.depth, item.parentId?.let { byId[it] }, item.rootId?.let { byId[it] })
                }
            }.toMap()
    }

    private fun etagFor(item: WorkItem): String = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    // ───────────────────────── surface drivers ─────────────────────────

    private class Result2(
        val accepted: Boolean,
        val detail: String,
        val createdId: UUID? = null
    )

    private suspend fun mcpUpdate(
        repo: WorkItemRepository,
        provider: RepositoryProvider,
        itemId: UUID,
        parent: JsonElement
    ): Result2 {
        val params =
            JsonObject(
                mapOf(
                    "operation" to JsonPrimitive("update"),
                    "items" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("parentId", parent)
                                }
                            )
                        )
                )
            )
        val data =
            (
                ManageItemsTool().execute(
                    params,
                    ToolExecutionContext(OverrideProvider(provider, repo), unitOfWork = hookedUnitOfWork(repo))
                ) as JsonObject
            )["data"]!!.jsonObject
        val ok = data["updated"]!!.jsonPrimitive.int == 1
        val detail =
            if (ok) {
                ""
            } else {
                data["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            }
        return Result2(ok, detail)
    }

    private suspend fun mcpCreate(
        repo: WorkItemRepository,
        provider: RepositoryProvider,
        title: String,
        parentId: UUID
    ): Result2 {
        val params =
            JsonObject(
                mapOf(
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("title", JsonPrimitive(title))
                                    put("parentId", JsonPrimitive(parentId.toString()))
                                }
                            )
                        )
                )
            )
        val data =
            (
                ManageItemsTool().execute(
                    params,
                    ToolExecutionContext(OverrideProvider(provider, repo), unitOfWork = hookedUnitOfWork(repo))
                ) as JsonObject
            )["data"]!!.jsonObject
        val ok = data["created"]!!.jsonPrimitive.int == 1
        return if (ok) {
            Result2(
                true,
                "",
                UUID.fromString(
                    data["items"]!!
                        .jsonArray[0]
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                )
            )
        } else {
            Result2(
                false,
                data["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            )
        }
    }

    private suspend fun restPatch(
        repo: WorkItemRepository,
        provider: RepositoryProvider,
        itemId: UUID,
        bodyJson: String
    ): Pair<HttpStatusCode, String> {
        val existing = (repo.getById(itemId) as Result.Success).data
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { configureApp(OverrideProvider(provider, repo)) }
            val r =
                client.patch("/api/v1/items/$itemId") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(existing))
                    contentType(ContentType.Application.Json)
                    setBody(bodyJson)
                }
            out = r.status to r.bodyAsText()
        }
        return out!!
    }

    private suspend fun restPost(
        repo: WorkItemRepository,
        provider: RepositoryProvider,
        title: String,
        parentId: UUID
    ): Pair<HttpStatusCode, String> {
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { configureApp(OverrideProvider(provider, repo)) }
            val r =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"$title","parentId":"$parentId"}""")
                }
            out = r.status to r.bodyAsText()
        }
        return out!!
    }

    private fun parentJson(id: UUID?): JsonElement = if (id == null) JsonNull else JsonPrimitive(id.toString())

    private fun restParentBody(id: UUID?): String = if (id == null) """{"parentId":null}""" else """{"parentId":"$id"}"""

    private fun restError(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content ?: ""

    /**
     * Runs the same reparent of [mover] -> [target] (names) on a mirrored tree per surface, with
     * optionally faulty repositories, and returns both surfaces' results and snapshots.
     */
    private class ReparentRun(
        val mcp: Result2,
        val rest: Pair<HttpStatusCode, String>,
        val mcpSnap: Map<String, Triple<Int, String?, String?>>,
        val restSnap: Map<String, Triple<Int, String?, String?>>
    )

    private fun reparent(
        mover: String,
        target: String?,
        faults: ((WorkItemRepository, Tree) -> WorkItemRepository)? = null
    ): ReparentRun =
        runBlocking {
            val provider = db.repositoryProvider()
            val plain = provider.workItemRepository()
            val mcpTree = buildTree(plain, "mcp")
            val restTree = buildTree(plain, "rest")
            val mcpRepo = faults?.invoke(plain, mcpTree) ?: plain
            val restRepo = faults?.invoke(plain, restTree) ?: plain
            val mcp = mcpUpdate(mcpRepo, provider, mcpTree[mover], parentJson(target?.let { mcpTree[it] }))
            val rest = restPatch(restRepo, provider, restTree[mover], restParentBody(target?.let { restTree[it] }))
            ReparentRun(mcp, rest, snapshot(plain, mcpTree), snapshot(plain, restTree))
        }

    private fun assertAcceptedParity(run: ReparentRun) {
        assertTrue(run.mcp.accepted, "MCP must accept: ${run.mcp.detail}")
        assertEquals(HttpStatusCode.OK, run.rest.first, "REST must accept: ${run.rest.second}")
        assertEquals(run.mcpSnap, run.restSnap, "persisted placement must match across surfaces")
    }

    private fun assertRejectedParity(
        run: ReparentRun,
        restStatus: HttpStatusCode
    ) {
        assertEquals(false, run.mcp.accepted, "MCP must reject")
        assertEquals(restStatus, run.rest.first, "REST status: ${run.rest.second}")
        assertEquals(run.mcpSnap, run.restSnap, "a rejection must leave both trees identical (nothing written)")
    }

    // ───────────────────────── scenarios ─────────────────────────

    @Test
    fun `parented create stamps the same depth and rootId on both surfaces`(): Unit =
        runBlocking {
            val provider = db.repositoryProvider()
            val plain = provider.workItemRepository()
            val mcpTree = buildTree(plain, "mcp")
            val restTree = buildTree(plain, "rest")

            val mcp = mcpCreate(plain, provider, "N", mcpTree["B"])
            val rest = restPost(plain, provider, "N", restTree["B"])

            assertTrue(mcp.accepted, mcp.detail)
            assertEquals(HttpStatusCode.Created, rest.first, rest.second)
            val restId =
                UUID.fromString(
                    Json
                        .parseToJsonElement(rest.second)
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                )
            val mcpSnap = snapshot(plain, mcpTree, mapOf("N" to mcp.createdId!!))
            val restSnap = snapshot(plain, restTree, mapOf("N" to restId))
            assertEquals(mcpSnap, restSnap)
            assertEquals(Triple(3, "B", "R"), mcpSnap.getValue("N"))
        }

    @Test
    fun `reparent with a depth change cascades to descendants identically`() {
        val run = reparent("X", "D")
        assertAcceptedParity(run)
        assertEquals(Triple(4, "D", "R"), run.mcpSnap.getValue("X"))
        assertEquals(Triple(5, "X", "R"), run.mcpSnap.getValue("XC"))
    }

    @Test
    fun `reparent to a different root at the same depth restamps rootIds identically`() {
        val run = reparent("A", "R2")
        assertAcceptedParity(run)
        assertEquals(Triple(1, "R2", "R2"), run.mcpSnap.getValue("A"))
        assertEquals(Triple(3, "B", "R2"), run.mcpSnap.getValue("D"))
    }

    @Test
    fun `move to root stamps depth 0 and cascades identically`() {
        val run = reparent("A", null)
        assertAcceptedParity(run)
        assertEquals(Triple(0, null, "A"), run.mcpSnap.getValue("A"))
        assertEquals(Triple(2, "B", "A"), run.mcpSnap.getValue("D"))
    }

    @Test
    fun `self-parent is rejected on both surfaces with nothing written`() {
        val run = reparent("A", "A")
        assertRejectedParity(run, HttpStatusCode.BadRequest)
        assertTrue(run.mcp.detail.contains("cannot be its own parent"), run.mcp.detail)
    }

    @Test
    fun `reparent under a descendant is rejected on both surfaces with nothing written`() {
        val run = reparent("A", "D")
        assertRejectedParity(run, HttpStatusCode.BadRequest)
        assertTrue(run.mcp.detail.contains("circular hierarchy"), run.mcp.detail)
        assertEquals("validation_error", restError(run.rest.second))
    }

    @Test
    fun `ancestor lookup failure rejects on both surfaces with nothing written (fail closed)`() {
        // X under D is legal and non-cyclic, so only a fail-OPEN guard would let it through.
        val run =
            reparent("X", "D") { plain, tree ->
                FaultRepository(plain, failChains = true, failGetByIdFor = setOf(tree["B"]))
            }
        assertRejectedParity(run, HttpStatusCode.InternalServerError)
        assertTrue(run.mcp.detail.contains("failed to verify hierarchy"), run.mcp.detail)
        assertEquals("db_error", restError(run.rest.second))
        assertEquals(Triple(0, null, "X"), run.mcpSnap.getValue("X"))
    }

    @Test
    fun `parent deleted inside the write transaction is reported not-found with nothing written on update`() {
        // D is a leaf; it is deleted at transaction open, after the guard reads have passed.
        val run =
            reparent("X", "D") { plain, tree ->
                FaultRepository(plain, onFirstTransaction = { plain.delete(tree["D"]) })
            }
        assertEquals(false, run.mcp.accepted)
        assertTrue(run.mcp.detail.contains("not found"), run.mcp.detail)
        assertEquals(HttpStatusCode.BadRequest, run.rest.first, run.rest.second)
        assertEquals("not_found", restError(run.rest.second))
        assertEquals(Triple(0, null, "X"), run.mcpSnap.getValue("X"))
        assertEquals(Triple(0, null, "X"), run.restSnap.getValue("X"))
    }

    @Test
    fun `parent deleted inside the write transaction is reported not-found with nothing written on create`(): Unit =
        runBlocking {
            val provider = db.repositoryProvider()
            val plain = provider.workItemRepository()
            val mcpTree = buildTree(plain, "mcp")
            val restTree = buildTree(plain, "rest")
            val mcpRepo = FaultRepository(plain, onFirstTransaction = { plain.delete(mcpTree["D"]) })
            val restRepo = FaultRepository(plain, onFirstTransaction = { plain.delete(restTree["D"]) })

            val mcp = mcpCreate(mcpRepo, provider, "N", mcpTree["D"])
            val rest = restPost(restRepo, provider, "N", restTree["D"])

            assertEquals(false, mcp.accepted)
            assertTrue(mcp.detail.contains("not found"), mcp.detail)
            assertEquals(HttpStatusCode.BadRequest, rest.first, rest.second)
            assertEquals("not_found", restError(rest.second))
        }
}

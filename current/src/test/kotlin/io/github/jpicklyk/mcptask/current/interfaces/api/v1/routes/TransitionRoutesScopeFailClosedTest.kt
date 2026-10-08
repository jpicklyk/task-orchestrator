package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Fail-closed scope filtering for `GET /api/v1/transitions`.
 *
 * Oracles (frozen in the item test-plan):
 * - O1 api-rest.md GET /transitions: scoped tokens only see transitions for items within their scope.
 * - O2 AuthorizationPlugin KDoc: scope helpers fail CLOSED on a lookup failure (deny rather than leak).
 * - O3 rootIds == null is unrestricted and needs no scope lookups.
 * - O4 diagnosis decision A: a lookup failure yields 200 with rows dropped, not 500.
 */

/** Wraps a real [WorkItemRepository], failing the selected batch lookups with a database error. */
private class TransitionScopeFailingWorkItemRepository(
    private val delegate: WorkItemRepository,
    private val failFindByIds: Boolean,
    private val failFindAncestorChains: Boolean,
) : WorkItemRepository by delegate {
    override suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>> =
        if (failFindByIds) {
            Result.Error(RepositoryError.DatabaseError("Simulated findByIds failure"))
        } else {
            delegate.findByIds(ids)
        }

    override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> =
        if (failFindAncestorChains) {
            Result.Error(RepositoryError.DatabaseError("Simulated findAncestorChains failure"))
        } else {
            delegate.findAncestorChains(itemIds)
        }
}

/** Wraps a real [RepositoryProvider], substituting [failingWorkItemRepo] for [workItemRepository]. */
private class TransitionScopeFailingRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failingWorkItemRepo: WorkItemRepository,
) : RepositoryProvider by delegate {
    override fun workItemRepository(): WorkItemRepository = failingWorkItemRepo
}

class TransitionRoutesScopeFailClosedTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private class Fixture(
        val rootR: WorkItem,
        val rootQ: WorkItem,
        val a: WorkItem,
        val b: WorkItem,
    )

    private fun transition(itemId: UUID) = RoleTransition(itemId = itemId, fromRole = "queue", toRole = "work", trigger = "start")

    /** Roots R and Q (depth 0); A child of R, B child of Q; one transition each. */
    private fun buildFixture(
        repo: RepositoryProvider,
        tags: String = "",
    ): Fixture =
        runBlocking {
            val w = repo.workItemRepository()
            val r = w.create(WorkItem(title = "Root R", depth = 0)).getOrNull()!!
            val q = w.create(WorkItem(title = "Root Q", depth = 0)).getOrNull()!!
            val a = w.create(WorkItem(title = "Item A", parentId = r.id, depth = 1, tags = tags)).getOrNull()!!
            val b = w.create(WorkItem(title = "Item B", parentId = q.id, depth = 1, tags = tags)).getOrNull()!!
            repo.roleTransitionRepository().create(transition(a.id))
            repo.roleTransitionRepository().create(transition(b.id))
            Fixture(r, q, a, b)
        }

    private fun failing(
        repo: RepositoryProvider,
        failFindByIds: Boolean,
        failFindAncestorChains: Boolean,
    ): RepositoryProvider =
        TransitionScopeFailingRepositoryProvider(
            repo,
            TransitionScopeFailingWorkItemRepository(repo.workItemRepository(), failFindByIds, failFindAncestorChains),
        )

    private fun itemIds(body: String): List<String> =
        Json
            .parseToJsonElement(body)
            .jsonObject["items"]!!
            .jsonArray
            .map { it.jsonObject["itemId"]!!.jsonPrimitive.content }

    private fun runGet(
        provider: RepositoryProvider,
        authConfig: ApiAuthConfig,
        path: String = "/api/v1/transitions",
        block: (HttpStatusCode, String) -> Unit,
    ) = testApplication {
        application { configureTestApp(authConfig) { transitionRoutes(provider) } }
        val response = client.get(path) { header("Authorization", "Bearer $TEST_TOKEN") }
        block(response.status, response.bodyAsText())
    }

    // S1 (red on base): findByIds failure must not leak out-of-scope rows.
    @Test
    fun `S1 root-scoped token with failing findByIds sees no out-of-scope row`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(failing(repo, failFindByIds = true, failFindAncestorChains = false), makeTestAuthConfig(setOf(f.rootR.id))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            val ids = itemIds(body)
            assertFalse(f.b.id.toString() in ids, "out-of-scope item B leaked: $body")
            assertTrue(f.a.id.toString() in ids, "in-scope item A must remain visible: $body")
        }
    }

    // S2: ancestor-chain lookup failure denies everything.
    @Test
    fun `S2 root-scoped token with failing findAncestorChains sees zero rows`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(failing(repo, failFindByIds = false, failFindAncestorChains = true), makeTestAuthConfig(setOf(f.rootR.id))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(emptyList(), itemIds(body), body)
        }
    }

    // S3: root + tag scope, findByIds failing (tag evaluation cannot run) -> zero rows.
    @Test
    fun `S3 root and tag scope with failing findByIds sees zero rows`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo, tags = "alpha")
        runGet(
            failing(repo, failFindByIds = true, failFindAncestorChains = false),
            makeTestAuthConfig(setOf(f.rootR.id), setOf("alpha")),
        ) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(emptyList(), itemIds(body), body)
        }
    }

    // S4: happy path, no failures.
    @Test
    fun `S4 root-scoped token sees only in-scope rows when lookups succeed`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(repo, makeTestAuthConfig(setOf(f.rootR.id))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(listOf(f.a.id.toString()), itemIds(body), body)
        }
    }

    // S5: root + tag combined narrows to the one item satisfying both.
    @Test
    fun `S5 root and tag scope combine to the single matching item`() {
        val repo = db.repositoryProvider()
        val items =
            runBlocking {
                val w = repo.workItemRepository()
                val r = w.create(WorkItem(title = "Root R", depth = 0)).getOrNull()!!
                val q = w.create(WorkItem(title = "Root Q", depth = 0)).getOrNull()!!
                val x1 = w.create(WorkItem(title = "A1", parentId = r.id, depth = 1, tags = "alpha")).getOrNull()!!
                val x2 = w.create(WorkItem(title = "A2", parentId = r.id, depth = 1, tags = "beta")).getOrNull()!!
                val y = w.create(WorkItem(title = "B", parentId = q.id, depth = 1, tags = "alpha")).getOrNull()!!
                listOf(x1, x2, y).forEach { repo.roleTransitionRepository().create(transition(it.id)) }
                listOf(r, x1, x2, y)
            }
        runGet(repo, makeTestAuthConfig(setOf(items[0].id), setOf("alpha"))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(listOf(items[1].id.toString()), itemIds(body), body)
        }
    }

    // S6 (red on base): filtering must precede pagination when the lookup fails.
    @Test
    fun `S6 failing findByIds with pageSize 1 yields an empty page when only out-of-scope rows exist`() {
        val repo = db.repositoryProvider()
        val rootR =
            runBlocking {
                val w = repo.workItemRepository()
                val r = w.create(WorkItem(title = "Root R", depth = 0)).getOrNull()!!
                val q = w.create(WorkItem(title = "Root Q", depth = 0)).getOrNull()!!
                val bItem = w.create(WorkItem(title = "Item B", parentId = q.id, depth = 1)).getOrNull()!!
                repeat(2) { repo.roleTransitionRepository().create(transition(bItem.id)) }
                r
            }
        runGet(
            failing(repo, failFindByIds = true, failFindAncestorChains = false),
            makeTestAuthConfig(setOf(rootR.id)),
            "/api/v1/transitions?pageSize=1",
        ) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(emptyList(), itemIds(body), body)
            assertFalse(
                Json
                    .parseToJsonElement(body)
                    .jsonObject["hasMore"]!!
                    .jsonPrimitive.boolean,
                body
            )
        }
    }

    // S7: unrestricted token needs no scope lookups, so failing lookups do not matter.
    @Test
    fun `S7 unrestricted token is unaffected by failing lookups`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(failing(repo, failFindByIds = true, failFindAncestorChains = true), makeTestAuthConfig()) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(setOf(f.a.id.toString(), f.b.id.toString()), itemIds(body).toSet(), body)
        }
    }

    // S8: nothing in the window, both lookups failing -> still a clean empty 200.
    @Test
    fun `S8 empty window with failing lookups returns 200 and no rows`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(
            failing(repo, failFindByIds = true, failFindAncestorChains = true),
            makeTestAuthConfig(setOf(f.rootR.id)),
            "/api/v1/transitions?since=2099-01-01T00:00:00Z",
        ) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(emptyList(), itemIds(body), body)
        }
    }

    // Probe: a scope root that is itself the item id makes that item visible.
    @Test
    fun `probe rootIds containing the item own id makes it visible`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runGet(repo, makeTestAuthConfig(setOf(f.b.id))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(listOf(f.b.id.toString()), itemIds(body), body)
        }
    }

    // Probe: empty-but-non-null rootIds is a scope that matches nothing.
    @Test
    fun `probe empty non-null rootIds yields zero rows`() {
        val repo = db.repositoryProvider()
        buildFixture(repo)
        runGet(repo, makeTestAuthConfig(emptySet())) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(emptyList(), itemIds(body), body)
        }
    }

    // Probe: duplicate transitions for one in-scope item are all retained.
    @Test
    fun `probe duplicate transitions for one in-scope item are all kept`() {
        val repo = db.repositoryProvider()
        val f = buildFixture(repo)
        runBlocking { repo.roleTransitionRepository().create(transition(f.a.id)) }
        runGet(repo, makeTestAuthConfig(setOf(f.rootR.id))) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertEquals(List(2) { f.a.id.toString() }, itemIds(body), body)
        }
    }
}

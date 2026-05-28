package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for dependency-read routes.
 *
 * Test coverage:
 * - `GET /items/{id}/dependencies` — correct bucket categorization (blocks/blockedBy/related)
 * - `GET /items/{id}/backlinks` — items referencing this one
 * - Scope filter applied on item-level access check
 */
class DependencyRoutesTest {

    @Test
    fun `GET items id dependencies returns empty buckets when no deps`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Isolated", depth = 0)).getOrNull()!!
            }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${item.id}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"blocks\""), "Expected blocks bucket: $body")
            assertTrue(body.contains("\"blockedBy\""), "Expected blockedBy bucket: $body")
            assertTrue(body.contains("\"related\""), "Expected related bucket: $body")
        }

    @Test
    fun `GET items id dependencies blocks bucket populated when item blocks another`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (blocker, blocked) = runBlocking {
                val a = repo.workItemRepository().create(WorkItem(title = "Blocker", depth = 0)).getOrNull()!!
                val b = repo.workItemRepository().create(WorkItem(title = "Blocked", depth = 0)).getOrNull()!!
                repo.dependencyRepository().create(
                    Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS)
                )
                Pair(a, b)
            }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${blocker.id}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(blocked.id.toString()), "Expected blocked item ID in blocks: $body")
        }

    @Test
    fun `GET items id dependencies blockedBy bucket populated`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (blocker, blocked) = runBlocking {
                val a = repo.workItemRepository().create(WorkItem(title = "Blocker", depth = 0)).getOrNull()!!
                val b = repo.workItemRepository().create(WorkItem(title = "Blocked", depth = 0)).getOrNull()!!
                repo.dependencyRepository().create(
                    Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS)
                )
                Pair(a, b)
            }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${blocked.id}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(blocker.id.toString()), "Expected blocker ID in blockedBy: $body")
        }

    @Test
    fun `GET items id dependencies related bucket populated`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking {
                val x = repo.workItemRepository().create(WorkItem(title = "Item A", depth = 0)).getOrNull()!!
                val y = repo.workItemRepository().create(WorkItem(title = "Item B", depth = 0)).getOrNull()!!
                repo.dependencyRepository().create(
                    Dependency(fromItemId = x.id, toItemId = y.id, type = DependencyType.RELATES_TO)
                )
                Pair(x, y)
            }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${a.id}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(b.id.toString()), "Expected related item ID: $body")
        }

    @Test
    fun `GET items id backlinks returns referencing items`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (referencing, target) = runBlocking {
                val t = repo.workItemRepository().create(WorkItem(title = "Target", depth = 0)).getOrNull()!!
                val r = repo.workItemRepository().create(WorkItem(title = "Referencing", depth = 0)).getOrNull()!!
                repo.dependencyRepository().create(
                    Dependency(fromItemId = r.id, toItemId = t.id, type = DependencyType.BLOCKS)
                )
                Pair(r, t)
            }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${target.id}/backlinks") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(referencing.id.toString()), "Expected referencing ID: $body")
            assertTrue(body.contains("Referencing"), "Expected referencing title: $body")
        }

    @Test
    fun `GET items id dependencies returns 404 for missing item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${UUID.randomUUID()}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET items id dependencies returns 403 outside scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Out of scope dep", depth = 0)).getOrNull()!!
            }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${item.id}/dependencies") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
}

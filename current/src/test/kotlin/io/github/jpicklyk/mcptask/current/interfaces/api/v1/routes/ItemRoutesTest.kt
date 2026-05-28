package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
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
import kotlin.test.assertFalse

/**
 * Integration tests for item-read routes.
 *
 * Uses an H2-backed [io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider].
 *
 * Test coverage:
 * - Happy path for each item endpoint
 * - 404 for genuinely missing items
 * - Scope filter: token scoped to R1 cannot access R2 items (returns 403 not 404)
 * - Pagination: `hasMore` flag computed correctly
 * - Query-param filters: role, priority, tag
 * - `?include=notes,deps,children` inlining
 */
class ItemRoutesTest {

    // ─── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `GET items returns 200 with list`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = runBlocking {
                repo.workItemRepository().create(
                    WorkItem(title = "Root item", depth = 0)
                ).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Root item"), "Expected item title in response: $body")
            assertTrue(body.contains("\"page\""), "Expected pagination in response: $body")
        }

    @Test
    fun `GET items roots returns 200 with root items`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Root A", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "Root B", depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/roots") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Root A") || body.contains("Root B"), "Expected roots: $body")
        }

    @Test
    fun `GET items id returns 200 for existing item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "My Item", depth = 0)).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("My Item"), "Expected title: $body")
            assertTrue(body.contains("\"etag\""), "Expected etag field: $body")
        }

    @Test
    fun `GET items id returns 404 for missing item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${UUID.randomUUID()}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET items id returns 400 for invalid UUID`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/not-a-uuid") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET items id tree returns 200 with descendants`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                repo.workItemRepository().create(WorkItem(title = "Child", parentId = r.id, depth = 1))
                r
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${root.id}/tree") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Root") || body.contains("Child"), "Expected tree: $body")
        }

    @Test
    fun `GET items id breadcrumbs returns ancestor chain`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val child = runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                repo.workItemRepository().create(WorkItem(title = "Child", parentId = r.id, depth = 1)).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${child.id}/breadcrumbs") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Root"), "Expected root in breadcrumbs: $body")
            assertTrue(body.contains("Child"), "Expected child in breadcrumbs: $body")
        }

    @Test
    fun `GET items id children returns direct children paginated`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                repo.workItemRepository().create(WorkItem(title = "Child1", parentId = r.id, depth = 1))
                repo.workItemRepository().create(WorkItem(title = "Child2", parentId = r.id, depth = 1))
                r
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${root.id}/children") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Child1") || body.contains("Child2"), "Expected children: $body")
            assertTrue(body.contains("\"hasMore\""), "Expected hasMore: $body")
        }

    // ─── Scope enforcement ───────────────────────────────────────────────────

    @Test
    fun `GET items id returns 403 for item outside scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val r1 = UUID.randomUUID()
            val itemOutsideScope = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Outside Scope Item", depth = 0)).getOrNull()!!
            }
            // Token scoped to r1 (a random UUID not matching itemOutsideScope's id)
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(r1))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${itemOutsideScope.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET items id returns 200 for item within scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "In Scope Root", depth = 0)).getOrNull()!!
            }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(root.id))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${root.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── Pagination ──────────────────────────────────────────────────────────

    @Test
    fun `GET items hasMore is true when more items exist`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repeat(3) { i -> repo.workItemRepository().create(WorkItem(title = "Item $i", depth = 0)) }
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items?pageSize=2") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"hasMore\":true"), "Expected hasMore=true: $body")
        }

    @Test
    fun `GET items hasMore is false when on last page`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repeat(2) { i -> repo.workItemRepository().create(WorkItem(title = "Small $i", depth = 0)) }
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items?pageSize=10") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"hasMore\":false"), "Expected hasMore=false: $body")
        }

    // ─── Filter params ───────────────────────────────────────────────────────

    @Test
    fun `GET items filters by role`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Queue item", role = Role.QUEUE, depth = 0))
                repo.workItemRepository().create(WorkItem(title = "Work item", role = Role.WORK, depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items?role=work") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Work item"), "Expected work item: $body")
            assertFalse(body.contains("Queue item"), "Should not contain queue item: $body")
        }

    @Test
    fun `GET items filters by priority`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "High prio", priority = Priority.HIGH, depth = 0))
                repo.workItemRepository().create(WorkItem(title = "Low prio", priority = Priority.LOW, depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items?priority=high") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("High prio"), "Expected high prio: $body")
            assertFalse(body.contains("Low prio"), "Should not contain low prio: $body")
        }

    @Test
    fun `GET items filters by tag`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Tagged item", tags = "bug,urgent", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "Untagged item", depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items?tagAny=bug") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Tagged item"), "Expected tagged item: $body")
        }

    // ─── ?include= inlining ─────────────────────────────────────────────────

    @Test
    fun `GET items id with include notes inlines notes`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                val i = repo.workItemRepository().create(WorkItem(title = "Noted", depth = 0)).getOrNull()!!
                repo.noteRepository().upsert(
                    io.github.jpicklyk.mcptask.current.domain.model.Note(
                        itemId = i.id, key = "spec", role = "queue", body = "This is a note"
                    )
                )
                i
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${item.id}?include=notes") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"notes\""), "Expected notes field: $body")
            assertTrue(body.contains("This is a note"), "Expected note body: $body")
        }

    @Test
    fun `GET items id with include children inlines children`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Parent", depth = 0)).getOrNull()!!
                repo.workItemRepository().create(WorkItem(title = "Inline Child", parentId = r.id, depth = 1))
                r
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${root.id}?include=children") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Inline Child"), "Expected child: $body")
        }

    @Test
    fun `GET items returns 401 without auth header`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

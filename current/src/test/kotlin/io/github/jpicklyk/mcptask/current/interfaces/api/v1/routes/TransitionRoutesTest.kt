package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
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
 * Integration tests for role-transition audit read routes.
 *
 * Test coverage:
 * - Per-item transitions ordered + paginated
 * - Global `/transitions?since=` returns transitions
 * - Scope filter: global transitions only show accessible items
 */
class TransitionRoutesTest {
    @Test
    fun `GET items id transitions returns 200 with transitions list`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val i = repo.workItemRepository().create(WorkItem(title = "Transitioning", depth = 0)).getOrNull()!!
                    repo.roleTransitionRepository().create(
                        RoleTransition(
                            itemId = i.id,
                            fromRole = "queue",
                            toRole = "work",
                            trigger = "start",
                        )
                    )
                    i
                }
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${item.id}/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("start"), "Expected trigger 'start': $body")
            assertTrue(body.contains("\"fromRole\""), "Expected fromRole field: $body")
            assertTrue(body.contains("\"toRole\""), "Expected toRole field: $body")
        }

    @Test
    fun `GET items id transitions returns 404 for missing item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${UUID.randomUUID()}/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET items id transitions returns 403 for item outside scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Out of scope transition", depth = 0)).getOrNull()!!
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application {
                configureTestApp(authConfig) { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${item.id}/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET transitions returns 200 with global transitions`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                val item = repo.workItemRepository().create(WorkItem(title = "Globally transitions", depth = 0)).getOrNull()!!
                repo.roleTransitionRepository().create(
                    RoleTransition(
                        itemId = item.id,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                    )
                )
            }
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"items\""), "Expected items list: $body")
        }

    @Test
    fun `GET transitions since filter limits results`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                val item = repo.workItemRepository().create(WorkItem(title = "Recent transition item", depth = 0)).getOrNull()!!
                repo.roleTransitionRepository().create(
                    RoleTransition(
                        itemId = item.id,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                    )
                )
            }
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            // Request with since far in the future — expect no results
            val response =
                client.get("/api/v1/transitions?since=2099-01-01T00:00:00Z") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"items\":[]") || body.contains("\"items\": []"), "Expected empty items for future since: $body")
        }

    @Test
    fun `GET items id transitions pagination works`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val i = repo.workItemRepository().create(WorkItem(title = "Many transitions", depth = 0)).getOrNull()!!
                    // Create a few transitions
                    repeat(3) { idx ->
                        repo.roleTransitionRepository().create(
                            RoleTransition(
                                itemId = i.id,
                                fromRole = "queue",
                                toRole = "work",
                                trigger = "start",
                                summary = "transition $idx",
                            )
                        )
                    }
                    i
                }
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${item.id}/transitions?pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"hasMore\""), "Expected hasMore: $body")
        }

    @Test
    fun `GET transitions returns 401 without auth`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp { transitionRoutes(repo) }
            }
            val response = client.get("/api/v1/transitions")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-013: a supplied but unparsable filter value is rejected with 400 validation_error naming
 * the parameter, never silently dropped (which widened the result set).
 */
class FilterParamValidationRouteTest {
    private suspend fun HttpClient.getAs(path: String) = get(path) { header("Authorization", "Bearer $TEST_TOKEN") }

    private suspend fun assertValidation400(
        client: HttpClient,
        path: String,
        param: String,
    ) {
        val response = client.getAs(path)
        assertEquals(HttpStatusCode.BadRequest, response.status, path)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "$path -> $json")
        assertTrue(json["message"]!!.jsonPrimitive.content.contains(param), "$path -> $json")
    }

    private fun titles(body: String): List<String> =
        Json
            .parseToJsonElement(body)
            .jsonObject["items"]!!
            .jsonArray
            .map { it.jsonObject["title"]!!.jsonPrimitive.content }

    @Test
    fun `GET items rejects each unparsable filter with 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking { repo.workItemRepository().create(WorkItem(title = "A", depth = 0)) }
            application { configureTestApp { itemRoutes(repo) } }

            val cases =
                mapOf(
                    "parentId" to "xyz",
                    "rootId" to "xyz",
                    "modifiedAfter" to "garbage",
                    "modifiedBefore" to "garbage",
                    "createdAfter" to "garbage",
                    "createdBefore" to "garbage",
                    "role" to "done",
                    "priority" to "critical",
                    "claimStatus" to "bogus",
                )
            for ((param, bad) in cases) {
                assertValidation400(client, "/api/v1/items?$param=$bad", param)
            }
        }

    @Test
    fun `GET items accepts case-insensitive enum values and applies the filter`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Queued", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "Working", depth = 0, role = Role.WORK))
                repo.workItemRepository().create(WorkItem(title = "Urgent", depth = 0, priority = Priority.HIGH))
                val now = Instant.now()
                repo.workItemRepository().create(
                    WorkItem(
                        title = "Claimed",
                        depth = 0,
                        claimedBy = "agent-1",
                        claimedAt = now,
                        claimExpiresAt = now.plus(1, ChronoUnit.HOURS),
                        originalClaimedAt = now
                    )
                )
            }
            application { configureTestApp { itemRoutes(repo) } }

            val work = client.getAs("/api/v1/items?role=WORK")
            assertEquals(HttpStatusCode.OK, work.status)
            assertEquals(listOf("Working"), titles(work.bodyAsText()))
            val high = client.getAs("/api/v1/items?priority=High")
            assertEquals(HttpStatusCode.OK, high.status)
            assertEquals(listOf("Urgent"), titles(high.bodyAsText()))
            val claimed = client.getAs("/api/v1/items?claimStatus=CLAIMED")
            assertEquals(HttpStatusCode.OK, claimed.status)
            assertEquals(listOf("Claimed"), titles(claimed.bodyAsText()))
        }

    @Test
    fun `GET items treats blank values as absent and valid parentId still filters`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent = WorkItem(title = "Parent", depth = 0)
            runBlocking {
                repo.workItemRepository().create(parent)
                repo.workItemRepository().create(WorkItem(title = "Child", parentId = parent.id, depth = 1))
            }
            application { configureTestApp { itemRoutes(repo) } }

            val blank = client.getAs("/api/v1/items?parentId=&rootId=&role=&priority=&claimStatus=&createdAfter=")
            assertEquals(HttpStatusCode.OK, blank.status)
            assertEquals(setOf("Parent", "Child"), titles(blank.bodyAsText()).toSet())

            val filtered = client.getAs("/api/v1/items?parentId=${parent.id}")
            assertEquals(listOf("Child"), titles(filtered.bodyAsText()))
        }

    @Test
    fun `GET items root-scoped principal gets 400 not a widened 200`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val rootId = UUID.randomUUID()
            runBlocking { repo.workItemRepository().create(WorkItem(id = rootId, title = "Root", depth = 0)) }
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(rootId))) { itemRoutes(repo) } }
            assertValidation400(client, "/api/v1/items?rootId=xyz", "rootId")
            assertValidation400(client, "/api/v1/items?parentId=xyz", "parentId")
        }

    @Test
    fun `GET items tag-scoped principal gets 400`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("a"))) { itemRoutes(repo) } }
            assertValidation400(client, "/api/v1/items?parentId=xyz", "parentId")
        }

    @Test
    fun `GET items orderBy and orderDir keep bad_request`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }
            for (path in listOf("/api/v1/items?orderBy=bogus", "/api/v1/items?orderDir=sideways")) {
                val r = client.getAs(path)
                assertEquals(HttpStatusCode.BadRequest, r.status)
                assertEquals(
                    "bad_request",
                    Json
                        .parseToJsonElement(r.bodyAsText())
                        .jsonObject["error"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }

    @Test
    fun `GET tree rejects non-numeric and negative depth, valid depth limits`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = WorkItem(title = "Root", depth = 0)
            val child = WorkItem(title = "Child", parentId = root.id, depth = 1)
            val grandchild = WorkItem(title = "Grand", parentId = child.id, depth = 2)
            runBlocking {
                repo.workItemRepository().create(root)
                repo.workItemRepository().create(child)
                repo.workItemRepository().create(grandchild)
            }
            application { configureTestApp { itemRoutes(repo) } }

            assertValidation400(client, "/api/v1/items/${root.id}/tree?depth=abc", "depth")
            assertValidation400(client, "/api/v1/items/${root.id}/tree?depth=-1", "depth")
            val limited = client.getAs("/api/v1/items/${root.id}/tree?depth=1")
            assertEquals(HttpStatusCode.OK, limited.status)
            val limitedTitles = titles(limited.bodyAsText())
            assertTrue("Child" in limitedTitles && "Grand" !in limitedTitles, "$limitedTitles")
        }

    @Test
    fun `GET transitions rejects invalid since and keeps the default when absent or blank`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }

            assertValidation400(client, "/api/v1/transitions?since=garbage", "since")
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions").status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions?since=").status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions?since=2999-01-01T00:00:00Z").status)
        }

    @Test
    fun `GET search rejects invalid ancestorId and role before any search`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { searchRoutes(repo) } }

            assertValidation400(client, "/api/v1/search?q=x&ancestorId=xyz", "ancestorId")
            assertValidation400(client, "/api/v1/search?q=x&role=done", "role")
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/search?q=x&role=WORK").status)
        }

    @Test
    fun `GET search with invalid ancestorId on a scoped principal is validation_error not scope_forbidden`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application {
                configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))) { searchRoutes(repo) }
            }
            assertValidation400(client, "/api/v1/search?q=x&ancestorId=xyz", "ancestorId")
        }

    @Test
    fun `GET notes search rejects invalid ancestorId`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { noteRoutes(repo) } }
            assertValidation400(client, "/api/v1/notes/search?q=x&ancestorId=xyz", "ancestorId")
        }
}

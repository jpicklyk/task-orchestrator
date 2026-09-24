package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test authorship for item fc8f3748 (needs-test-author) -- REST
 * `POST /api/v1/dependencies` duplicate-edge parity fix.
 *
 * Uses H2 (buildH2RepositoryProvider) + configureWriteTestApp, per the test-plan note's Harness
 * section ("Dependency tests: buildH2RepositoryProvider()").
 *
 * Oracles (frozen in test-plan note 826560f2 / diagnosis note 5237085a, before this file was
 * written):
 *  [DX] diagnosis "REST contract" section -- an identical (from, to, type) edge returns 409
 *       duplicate_dependency; a cycle (reverse BLOCKS) still returns 400 cycle_detected; a
 *       reverse RELATES_TO is unrelated to blocking semantics and succeeds.
 */
class DependencyDuplicateRouteTest {
    private suspend fun createPair(
        repo: io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider,
        titleA: String = "A",
        titleB: String = "B",
    ): Pair<WorkItem, WorkItem> {
        val a = repo.workItemRepository().create(WorkItem(title = titleA, depth = 0)).getOrNull()!!
        val b = repo.workItemRepository().create(WorkItem(title = titleB, depth = 0)).getOrNull()!!
        return Pair(a, b)
    }

    // -----------------------------------------------------------------------
    // S4 -- happy: a duplicate blocks edge is rejected, exactly 1 edge exists
    // -----------------------------------------------------------------------

    @Test
    fun `S4 POST blocks A to B twice returns 201 then 409 duplicate_dependency, exactly one edge exists`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking { createPair(repo) }
            application { configureWriteTestApp(repo) }

            val first =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.Created, first.status, "body: ${first.bodyAsText()}")

            val second =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.Conflict, second.status, "body: ${second.bodyAsText()}")
            assertTrue(second.bodyAsText().contains("duplicate_dependency"), "body: ${second.bodyAsText()}")

            val edges =
                runBlocking { repo.dependencyRepository().findByItemId(a.id) }
                    .filter { it.fromItemId == a.id && it.toItemId == b.id && it.type == DependencyType.BLOCKS }
            assertEquals(1, edges.size, "exactly one BLOCKS edge must exist between A and B")
        }

    // -----------------------------------------------------------------------
    // S11 -- failure: a duplicate relates_to edge is rejected the same way
    // -----------------------------------------------------------------------

    @Test
    fun `S11 POST relates_to A to B twice returns 201 then 409 duplicate_dependency`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking { createPair(repo, "A11", "B11") }
            application { configureWriteTestApp(repo) }

            val first =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"relates_to"}""")
                }
            assertEquals(HttpStatusCode.Created, first.status, "body: ${first.bodyAsText()}")

            val second =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"relates_to"}""")
                }
            assertEquals(HttpStatusCode.Conflict, second.status, "body: ${second.bodyAsText()}")
            assertTrue(second.bodyAsText().contains("duplicate_dependency"), "body: ${second.bodyAsText()}")
        }

    // -----------------------------------------------------------------------
    // S16a -- a reverse BLOCKS is a cycle (400), a same-direction RELATES_TO is unrelated (201)
    // -----------------------------------------------------------------------

    @Test
    fun `S16a given A blocks B, reverse blocks is a cycle but same-direction relates_to succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking { createPair(repo, "A16a", "B16a") }
            runBlocking {
                repo.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
            }
            application { configureWriteTestApp(repo) }

            val reverseBlocks =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, reverseBlocks.status, "body: ${reverseBlocks.bodyAsText()}")
            assertTrue(reverseBlocks.bodyAsText().contains("cycle_detected"), "body: ${reverseBlocks.bodyAsText()}")

            val sameDirectionRelatesTo =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"relates_to"}""")
                }
            assertEquals(
                HttpStatusCode.Created,
                sameDirectionRelatesTo.status,
                "body: ${sameDirectionRelatesTo.bodyAsText()}",
            )
        }

    // -----------------------------------------------------------------------
    // S16b -- given A relates_to B, the reverse relates_to is unrelated to blocking and succeeds
    // -----------------------------------------------------------------------

    @Test
    fun `S16b given A relates_to B, reverse relates_to succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking { createPair(repo, "A16b", "B16b") }
            runBlocking {
                repo.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.RELATES_TO))
            }
            application { configureWriteTestApp(repo) }

            val reverseRelatesTo =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"relates_to"}""")
                }
            assertEquals(HttpStatusCode.Created, reverseRelatesTo.status, "body: ${reverseRelatesTo.bodyAsText()}")
        }

    // -----------------------------------------------------------------------
    // Probe -- duplicate re-affirmed against the enum's canonical BLOCKS spelling (test-plan
    // states 409 as the expected outcome for this probe; oracle taken as given, not derived)
    // -----------------------------------------------------------------------

    @Test
    fun `probe duplicate dependency with type BLOCKS returns 409`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) = runBlocking { createPair(repo, "Aprobe", "Bprobe") }
            runBlocking {
                repo.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
            }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"BLOCKS"}""")
                }
            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("duplicate_dependency"), "body: ${response.bodyAsText()}")
        }
}

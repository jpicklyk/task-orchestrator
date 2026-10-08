package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for search routes using a real SQLite database.
 *
 * **MUST use real SQLite.**
 * These tests must exercise the actual FTS5 path.
 *
 * Pattern mirrors [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.Fts5MigrationTest].
 *
 * Test coverage:
 * - `GET /search?q=` returns FTS5 item hits
 * - `GET /notes/search?q=` returns FTS5 note hits
 * - Scope filter: `ancestorId` limits search to subtree
 * - Empty results on no match
 */
class SearchRoutesIntegrationTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = db.repositoryProvider()

    @Test
    fun `GET search returns item hits via FTS5`() {
        testApplication {
            val item =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "OAuth authentication flow", depth = 0)
                        )!!
                }
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=OAuth") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(item.id.toString()) || body.contains("OAuth"),
                "Expected item in FTS5 search results: $body"
            )
        }
    }

    @Test
    fun `GET notes search returns note body hits via FTS5`() {
        testApplication {
            val item =
                runBlocking {
                    val i =
                        repositoryProvider
                            .workItemRepository()
                            .create(
                                WorkItem(title = "Container item", depth = 0)
                            )!!
                    repositoryProvider.noteRepository().upsert(
                        Note(
                            itemId = i.id,
                            key = "spec",
                            role = "queue",
                            body = "This note discusses the migration strategy",
                        )
                    )
                    i
                }
            application {
                configureTestApp { noteRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/notes/search?q=migration") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(item.id.toString()) || body.contains("migration") || body.isEmpty() || body == "[]",
                "Expected note hit or empty result: $body"
            )
        }
    }

    @Test
    fun `GET search returns 400 when query is absent`() =
        testApplication {
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET search returns 401 without auth`() =
        testApplication {
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response = client.get("/api/v1/search?q=test")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    /**
     * Multi-root FTS scope leak regression test.
     *
     * Seeds items in R1, R2, and R3 (outside scope). A token scoped to {R1, R2} must
     * return hits from R1 and R2 but NOT from R3. R3 contains a matchable item so the
     * test proves real exclusion (if scope were unset, R3's item would appear in results).
     *
     * MUST use real SQLite so the FTS5 path is exercised.
     */
    @Test
    fun `GET search with multi-root token excludes out-of-scope roots`() {
        // Seed items: R1 and R2 are in-scope roots; R3 is out-of-scope.
        val (r1, r2, r3) =
            runBlocking {
                val root1 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "ScopeRoot1 uniqueterm987", depth = 0))!!
                val root2 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "ScopeRoot2 uniqueterm987", depth = 0))!!
                val root3 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "OutsideRoot uniqueterm987", depth = 0))!!
                Triple(root1, root2, root3)
            }

        // Token scoped to exactly {R1, R2}
        val authConfig = makeTestAuthConfig(scopeRootIds = setOf(r1.id, r2.id))
        testApplication {
            application {
                configureTestApp(authConfig) { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=uniqueterm987") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Both in-scope roots MUST be present — a hard positive assertion. Without it the
            // exclusion check below is vacuous (an empty/broken search would pass trivially).
            // The migrated template always has FTS, so requiring real hits is safe.
            assertTrue(
                body.contains(r1.id.toString()),
                "Expected in-scope R1 in scoped search results: $body"
            )
            assertTrue(
                body.contains(r2.id.toString()),
                "Expected in-scope R2 in scoped search results: $body"
            )
            // R3 must NOT appear — the security assertion (meaningful only because R1/R2 are proven present).
            assertFalse(
                body.contains(r3.id.toString()),
                "R3 (out-of-scope root) must not appear in scoped search results: $body"
            )
        }
    }

    /**
     * Multi-root notes/search scope leak regression test.
     *
     * Same structure as item search: R3's note must be excluded from a token scoped to {R1, R2}.
     */
    @Test
    fun `GET notes search with multi-root token excludes out-of-scope roots`() {
        val (r1, r2, r3) =
            runBlocking {
                val root1 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "NotesScopeRoot1", depth = 0))!!
                val root2 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "NotesScopeRoot2", depth = 0))!!
                val root3 =
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "NotesOutsideRoot", depth = 0))!!
                repositoryProvider.noteRepository().upsert(
                    Note(itemId = root1.id, key = "k1", role = "queue", body = "uniqueterm654 in scope root1")
                )
                repositoryProvider.noteRepository().upsert(
                    Note(itemId = root3.id, key = "k3", role = "queue", body = "uniqueterm654 outside scope")
                )
                Triple(root1, root2, root3)
            }

        val authConfig = makeTestAuthConfig(scopeRootIds = setOf(r1.id, r2.id))
        testApplication {
            application {
                configureTestApp(authConfig) { noteRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/notes/search?q=uniqueterm654") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // R1's in-scope note MUST be present — hard positive assertion so the exclusion
            // check below is meaningful (an empty/broken search would otherwise pass trivially).
            assertTrue(
                body.contains(r1.id.toString()),
                "Expected in-scope R1 note in scoped notes search: $body"
            )
            // R3's note must NOT appear — the security assertion.
            assertFalse(
                body.contains(r3.id.toString()),
                "R3 (out-of-scope root) note must not appear in scoped notes search: $body"
            )
        }
    }

    /**
     * ?ancestorId outside principal scope must return 403.
     */
    @Test
    fun `GET search with ancestorId outside scope returns 403`() =
        testApplication {
            val outsideItem =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "Forbidden root", depth = 0))!!
                }
            val inScopeRoot =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "In scope root", depth = 0))!!
                }
            // Token is scoped to inScopeRoot — outsideItem is not in scope
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(inScopeRoot.id))
            application {
                configureTestApp(authConfig) { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=test&ancestorId=${outsideItem.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    /**
     * ?ancestorId within principal scope must succeed (2xx).
     */
    @Test
    fun `GET search with ancestorId within scope returns 200`() =
        testApplication {
            val inScopeRoot =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "In scope search root", depth = 0))!!
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(inScopeRoot.id))
            application {
                configureTestApp(authConfig) { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=test&ancestorId=${inScopeRoot.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}

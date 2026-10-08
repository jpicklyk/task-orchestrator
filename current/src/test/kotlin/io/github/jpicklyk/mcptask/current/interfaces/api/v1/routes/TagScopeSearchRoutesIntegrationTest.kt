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
 * Independent regression coverage for the `tags_include` scope gap on the FTS5-backed search
 * routes: `GET /search` (S4) and `GET /notes/search` (S5).
 *
 * Authored per the item `ffa12a2f-8028-4a46-8882-3f84fa629ee9` `test-plan` note (oracle O1:
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope] KDoc -- `tagsInclude`
 * applies to the item itself; a tag-scoped principal must never receive a search hit for an
 * item outside its tag allowlist).
 *
 * **MUST use real SQLite** (the migrated fixture) so the FTS5 path is actually exercised.
 */
class TagScopeSearchRoutesIntegrationTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = db.repositoryProvider()

    @Test
    fun `S4 GET search with tagsInclude alpha excludes beta-tagged item's hit`() {
        testApplication {
            val (itemAlpha, itemBeta) =
                runBlocking {
                    val a =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "TagScopeUniqueTerm741 Alpha", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "TagScopeUniqueTerm741 Beta", tags = "beta", depth = 0))
                            .getOrNull()!!
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=TagScopeUniqueTerm741") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Hard positive assertion first -- without it the exclusion check below would be
            // vacuous (a broken/empty search would trivially "exclude" everything).
            assertTrue(
                body.contains(itemAlpha.id.toString()),
                "Expected alpha-tagged item's hit in scoped search results: $body",
            )
            assertFalse(
                body.contains(itemBeta.id.toString()),
                "Beta-tagged item's hit must be excluded from an alpha-scoped search: $body",
            )
        }
    }

    /**
     * S5: `GET /notes/search` -- same tag-scope exclusion, but for a note hit keyed by its
     * parent item's tags rather than the item's own row.
     */
    @Test
    fun `S5 GET notes search with tagsInclude alpha excludes beta-tagged item's note hit`() {
        testApplication {
            val (itemAlpha, itemBeta) =
                runBlocking {
                    val a =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaNoteContainer", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "BetaNoteContainer", tags = "beta", depth = 0))
                            .getOrNull()!!
                    repositoryProvider.noteRepository().upsert(
                        Note(itemId = a.id, key = "spec", role = "queue", body = "TagScopeNoteTerm852 in alpha item")
                    )
                    repositoryProvider.noteRepository().upsert(
                        Note(itemId = b.id, key = "spec", role = "queue", body = "TagScopeNoteTerm852 in beta item")
                    )
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { noteRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/notes/search?q=TagScopeNoteTerm852") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(itemAlpha.id.toString()),
                "Expected alpha item's note hit in scoped notes search results: $body",
            )
            assertFalse(
                body.contains(itemBeta.id.toString()),
                "Beta item's note hit must be excluded from an alpha-scoped notes search: $body",
            )
        }
    }
}

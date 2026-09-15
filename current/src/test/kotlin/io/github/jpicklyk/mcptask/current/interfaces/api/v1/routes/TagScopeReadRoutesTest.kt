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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent regression coverage for the `tags_include` scope gap on H2-backed REST read
 * routes: `GET /items`, `GET /items/roots`, and `GET /transitions`.
 *
 * Authored per the item `ffa12a2f-8028-4a46-8882-3f84fa629ee9` `test-plan` note (oracles
 * O1-O3: [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope] KDoc,
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem] KDoc,
 * `BearerTokenStore.parseScope` exact-CSV-membership semantics). A principal scoped by
 * `tagsInclude` must never see an item (or a transition/root belonging to an item) that does
 * not carry one of the allow-listed tags.
 *
 * `GET /items/{id}/breadcrumbs` (S13) and case-sensitivity of tag matching (S12) are
 * deliberately NOT asserted here -- per `test-plan` these are escalate-only; see the
 * `test-manifest` note for the arbitration record. `?include=children` inline coverage is
 * likewise omitted -- `test-plan`'s SCENARIOS section does not assign it an S-id, and the
 * bugwave conflict rule gives the frozen `test-plan` priority over the item description's
 * surface list; also recorded in `test-manifest`.
 */
class TagScopeReadRoutesTest {
    // ─── S1: GET /items ────────────────────────────────────────────────────

    @Test
    fun `S1 GET items with tagsInclude alpha returns only alpha-tagged item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "ItemAlphaS1", tags = "alpha", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "ItemBetaS1", tags = "beta", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("ItemAlphaS1"), "Expected alpha-tagged item in scoped list: $body")
            assertFalse(body.contains("ItemBetaS1"), "Beta-tagged item must be excluded from scoped list: $body")
        }

    // ─── S2: GET /items/roots ──────────────────────────────────────────────

    @Test
    fun `S2 GET items roots with tagsInclude alpha returns only alpha-tagged root`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "RootAlphaS2", tags = "alpha", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "RootBetaS2", tags = "beta", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/roots") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("RootAlphaS2"), "Expected alpha-tagged root: $body")
            assertFalse(body.contains("RootBetaS2"), "Beta-tagged root must be excluded: $body")
        }

    // ─── S3: GET /transitions ──────────────────────────────────────────────

    @Test
    fun `S3 GET transitions with tagsInclude alpha returns only alpha item's transitions`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (itemA, itemB) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "TransAlphaS3", tags = "alpha", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "TransBetaS3", tags = "beta", depth = 0)).getOrNull()!!
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = a.id, fromRole = "queue", toRole = "work", trigger = "start")
                    )
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = b.id, fromRole = "queue", toRole = "work", trigger = "start")
                    )
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // RoleTransitionDto does not serialize `summary` (see Dtos.kt / api-rest.md
            // transitions section: id, itemId, fromRole, toRole, trigger, occurredAt only) --
            // assert on `itemId`, the field that is actually on the wire.
            assertTrue(body.contains(itemA.id.toString()), "Expected alpha item's transition (by itemId): $body")
            assertFalse(body.contains(itemB.id.toString()), "Beta item's transition must be excluded (by itemId): $body")
        }

    // ─── S6: regression -- empty tagsInclude changes nothing ──────────────

    @Test
    fun `S6 regression - empty tagsInclude returns both tags across items, roots and transitions`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (itemA, itemB) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "RegAlphaS6", tags = "alpha", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "RegBetaS6", tags = "beta", depth = 0)).getOrNull()!!
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = a.id, fromRole = "queue", toRole = "work", trigger = "start")
                    )
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = b.id, fromRole = "queue", toRole = "work", trigger = "start")
                    )
                    a to b
                }
            // Default makeTestAuthConfig(): rootIds = null, tagsInclude = emptySet() -- no constraint.
            // Single configureTestApp call -- it already installs ContentNegotiation/SSE/routing
            // once; calling it twice in the same Application throws DuplicatePluginException.
            application {
                configureTestApp {
                    itemRoutes(repo)
                    transitionRoutes(repo)
                }
            }
            val itemsResponse =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, itemsResponse.status)
            val itemsBody = itemsResponse.bodyAsText()
            assertTrue(itemsBody.contains("RegAlphaS6"), "Unscoped token must see alpha item: $itemsBody")
            assertTrue(itemsBody.contains("RegBetaS6"), "Unscoped token must see beta item: $itemsBody")

            val rootsResponse =
                client.get("/api/v1/items/roots") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, rootsResponse.status)
            val rootsBody = rootsResponse.bodyAsText()
            assertTrue(rootsBody.contains("RegAlphaS6"), "Unscoped token must see alpha root: $rootsBody")
            assertTrue(rootsBody.contains("RegBetaS6"), "Unscoped token must see beta root: $rootsBody")

            val transitionsResponse =
                client.get("/api/v1/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, transitionsResponse.status)
            val transitionsBody = transitionsResponse.bodyAsText()
            // Same RoleTransitionDto field limitation as S3 -- assert by itemId, not summary.
            assertTrue(transitionsBody.contains(itemA.id.toString()), "Unscoped token must see alpha transition: $transitionsBody")
            assertTrue(transitionsBody.contains(itemB.id.toString()), "Unscoped token must see beta transition: $transitionsBody")
        }

    // ─── S7: no-match tagsInclude -- empty collection, not 403/500 ─────────

    @Test
    fun `S7 GET items with tagsInclude gamma and no matching items returns 200 with empty list`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "NoMatchAlphaS7", tags = "alpha", depth = 0))
                repo.workItemRepository().create(WorkItem(title = "NoMatchBetaS7", tags = "beta", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("gamma"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            // 403 is a per-item response (enforceScopeForItem); a collection with zero matches
            // is not "forbidden", per O2 -- it is a valid, empty result set.
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("NoMatchAlphaS7"), "No item should match gamma scope: $body")
            assertFalse(body.contains("NoMatchBetaS7"), "No item should match gamma scope: $body")
            assertTrue(
                body.contains("\"items\":[]") || body.contains("\"items\": []"),
                "Expected an empty items collection, not an error: $body",
            )
        }

    // ─── S8: tagsInclude AND rootIds combined ──────────────────────────────

    @Test
    fun `S8 GET items with tagsInclude and rootIds excludes wrong-tag-in-scope and right-tag-out-of-scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val rootR =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "RootRS8", depth = 0)).getOrNull()!!
                    // In R, wrong tag -- must be excluded despite being in scope's root.
                    repo.workItemRepository().create(WorkItem(title = "InRWrongTagS8", tags = "beta", parentId = r.id, depth = 1))
                    // In R, right tag -- positive control, must be included.
                    repo.workItemRepository().create(WorkItem(title = "InRRightTagS8", tags = "alpha", parentId = r.id, depth = 1))
                    // Outside R, right tag -- must be excluded despite matching the tag.
                    repo.workItemRepository().create(WorkItem(title = "OutsideRRightTagS8", tags = "alpha", depth = 0))
                    r
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(rootR.id), tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("InRRightTagS8"), "Item satisfying both root and tag scope must be included: $body")
            assertFalse(body.contains("InRWrongTagS8"), "Item in scope root but wrong tag must be excluded: $body")
            assertFalse(body.contains("OutsideRRightTagS8"), "Item with right tag but outside scope root must be excluded: $body")
        }

    // ─── S9: tags null or "" -- excluded ────────────────────────────────────

    @Test
    fun `S9 GET items excludes items with null or empty tags under tagsInclude`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                // No tags argument -- tags column is null.
                repo.workItemRepository().create(WorkItem(title = "NullTagsS9", depth = 0))
                // Explicit empty-string tags column.
                repo.workItemRepository().create(WorkItem(title = "EmptyTagsS9", tags = "", depth = 0))
                // Positive control -- must still appear so the exclusion checks are meaningful.
                repo.workItemRepository().create(WorkItem(title = "AlphaControlS9", tags = "alpha", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("AlphaControlS9"), "Expected the matching control item to be visible: $body")
            assertFalse(body.contains("NullTagsS9"), "Item with null tags must be excluded: $body")
            assertFalse(body.contains("EmptyTagsS9"), "Item with empty-string tags must be excluded: $body")
        }

    // ─── S10: CSV trimming ──────────────────────────────────────────────────

    @Test
    fun `S10 GET items includes item whose tags CSV has surrounding whitespace`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "SpacedTagsS10", tags = " alpha , beta ", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("SpacedTagsS10"), "Whitespace-padded CSV tag must still match after trim: $body")
        }

    // ─── S11: exact membership, not prefix/substring ───────────────────────

    @Test
    fun `S11 GET items excludes tag that is a superstring of the scope tag`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "SuperstringTagS11", tags = "alpha-beta", depth = 0))
                // Positive control -- must still appear so the exclusion check is meaningful.
                repo.workItemRepository().create(WorkItem(title = "ExactAlphaControlS11", tags = "alpha", depth = 0))
            }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("ExactAlphaControlS11"), "Expected exact-match control item to be visible: $body")
            assertFalse(
                body.contains("SuperstringTagS11"),
                "Tag 'alpha-beta' must NOT satisfy exact scope membership for 'alpha': $body",
            )
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for MCP item `e6967195` ("Close REST scope escapes: root
 * create/reparent-to-root and /children paging before tag filter"), scenarios S11-S16 and probe
 * P7 of the frozen `test-plan` note (queue phase, `planning-seat:e6967195`). S1-S10 and P1-P6
 * (the root-placement half of this item) live in `RootPlacementScopeTest.kt`.
 *
 * Oracles (per `test-plan`):
 *  - O2 `current/docs/api-rest.md` sec.7 — `items.size == min(pageSize, totalItems - offset)`.
 *  - O3 `ItemRoutes.kt` KDoc — `/items/{id}/children` filters by `tagsInclude` BEFORE paginating,
 *    so `totalItems` must count only the tag-visible children, never the raw row count.
 *
 * Decisions (frozen `diagnosis` note, `planning-seat:e6967195`):
 *  - D5: a tag-scoped `/children` request fetches a bounded candidate window
 *    (`findByFilters(parentId=id, limit=TAG_SCOPE_SCAN_LIMIT, offset=0)`), applies
 *    `filterByTagScope`, then pages the SURVIVORS in memory (`drop(offset).take(pageSize)`);
 *    `totalItems` is the filtered survivor count, not the raw candidate-window size.
 *  - D6: an UNSCOPED or `rootIds`-only caller keeps the pre-existing SQL `LIMIT`/`OFFSET` +
 *    `countByFilters` path untouched by this item — S15/S16 are guards proving the two paths
 *    stay disjoint (only a non-empty `tagsInclude` selects D5's path).
 *
 * BLINDNESS: authored from this item's `diagnosis` and `test-plan` notes (both queue-phase,
 * frozen before implementation, per the `test-author` skill's blindness rule), the verbatim
 * public declarations supplied inline in the dispatch prompt (`ItemRoutes.kt`'s
 * `TAG_SCOPE_SCAN_LIMIT` constant and class/route KDoc only — no route-handler body;
 * `PageParams`/`PageDto`/`buildPageDto`'s verbatim `hasMore` formula; `ApiScope`/
 * `allowsItemTags`/`hasTagScope`/`filterByTagScope` signatures and KDoc), and the existing test
 * conventions in this package (`ApiTestHelper.kt`, `TagScopeReadRoutesTest.kt`'s tag-scoped READ
 * pattern, `ItemRoutesTest.kt`'s `?include=` and roots-pagination JSON-parsing conventions via
 * `kotlinx.serialization.json`, and its pre-existing, unmodified `GET items id children with
 * tag-scoped token excludes children without required tag` test at line 556 — confirmed no
 * naming/behavior collision with this file). No `src/main` file, diff, or commit was read to
 * author this suite.
 *
 * ARBITRATION (recorded in full in `test-manifest`): S14 cross-checks `/children`'s filtered
 * total against `GET /items?parentId=<id>`'s total for the SAME tag-scoped principal. The exact
 * query-parameter name for a parent filter on `GET /items` was not among the supplied
 * declarations (only `role`, `priority`, `tagAny`, `pageSize` were observed as existing query
 * params in this package's other tests, and those do not all mirror an internal field name
 * one-for-one). `parentId` was chosen as the most likely name — it is the literal field name
 * shared verbatim by `WorkItem`, `ItemCreateDto`, and `ItemPatchDto` for this exact concept, and
 * no other supplied evidence suggests a different spelling. If this assumption is wrong, S14 will
 * fail in a way that is easy to distinguish from a real defect (`GET /items?parentId=...` would
 * return an unfiltered or empty list rather than the tag-scoped 3), and is reported as such rather
 * than adjusted to match observed behavior.
 */
class ChildrenTagScopePaginationTest {
    // ─────────────────────────────────────────────────────────────────────
    // S11 — REGRESSION (red pre-fix): tag-scoped /children pages the FILTERED set, not the raw
    // candidate window; beta siblings must never appear and must not inflate totalItems
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S11 tag-scoped GET children pages filtered results ignoring beta siblings`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "S11 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "S11 Alpha1", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S11 Alpha2", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S11 Alpha3", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S11 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S11 Beta2", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val page1 =
                client.get("/api/v1/items/${parent.id}/children?page=1&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, page1.status, "Page 1 must succeed: ${page1.bodyAsText()}")
            val page1Json = Json.parseToJsonElement(page1.bodyAsText()).jsonObject
            assertEquals(2, page1Json["items"]!!.jsonArray.size, "Page 1 of pageSize=2 must return exactly 2 items")
            assertEquals(3L, page1Json["totalItems"]!!.jsonPrimitive.long, "totalItems must count only the 3 alpha-tagged children")
            assertTrue(page1Json["hasMore"]!!.jsonPrimitive.boolean, "3 filtered children over pageSize=2 must have more pages")
            val page1Titles = page1Json["items"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            assertTrue(page1Titles.none { it.startsWith("S11 Beta") }, "Page 1 must not contain a beta-tagged title: $page1Titles")

            val page2 =
                client.get("/api/v1/items/${parent.id}/children?page=2&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, page2.status, "Page 2 must succeed: ${page2.bodyAsText()}")
            val page2Json = Json.parseToJsonElement(page2.bodyAsText()).jsonObject
            assertEquals(1, page2Json["items"]!!.jsonArray.size, "Page 2 must return the last remaining alpha child")
            assertEquals(3L, page2Json["totalItems"]!!.jsonPrimitive.long)
            assertFalse(page2Json["hasMore"]!!.jsonPrimitive.boolean, "Page 2 is the last page of 3 filtered children")
            val page2Titles = page2Json["items"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            assertTrue(page2Titles.none { it.startsWith("S11 Beta") }, "Page 2 must not contain a beta-tagged title: $page2Titles")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S12 — edge: a page beyond the filtered total is empty, not an error, and total is stable
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S12 tag-scoped GET children page beyond the filtered total returns an empty page`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "S12 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "S12 Alpha1", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S12 Alpha2", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S12 Alpha3", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S12 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S12 Beta2", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val page3 =
                client.get("/api/v1/items/${parent.id}/children?page=3&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, page3.status, "A page past the filtered total must still be 200: ${page3.bodyAsText()}")
            val page3Json = Json.parseToJsonElement(page3.bodyAsText()).jsonObject
            assertEquals(0, page3Json["items"]!!.jsonArray.size, "Page 3 (past 3 filtered children at pageSize=2) must be empty")
            assertEquals(3L, page3Json["totalItems"]!!.jsonPrimitive.long)
            assertFalse(page3Json["hasMore"]!!.jsonPrimitive.boolean)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S13 — edge: zero matching children -> empty page and totalItems 0, not the raw sibling count
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S13 tag-scoped GET children with no matching children reports totalItems 0`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "S13 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "S13 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S13 Beta2", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${parent.id}/children") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A beta-only child set under an alpha-scoped token must still be 200: ${response.bodyAsText()}",
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, json["items"]!!.jsonArray.size, "No alpha-tagged children means an empty page")
            assertEquals(0L, json["totalItems"]!!.jsonPrimitive.long, "totalItems must be 0, not the 2 raw (unfiltered) children")
            assertFalse(json["hasMore"]!!.jsonPrimitive.boolean)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S14 — /children's filtered total must agree with the equivalently tag-scoped GET /items
    // total for the same parent (api-rest.md sec.3)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S14 tag-scoped children total matches tag-scoped GET items parentId total`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "S14 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "S14 Alpha1", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S14 Alpha2", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S14 Alpha3", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S14 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S14 Beta2", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val childrenResponse =
                client.get("/api/v1/items/${parent.id}/children") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, childrenResponse.status)
            val childrenTotal =
                Json
                    .parseToJsonElement(childrenResponse.bodyAsText())
                    .jsonObject["totalItems"]!!
                    .jsonPrimitive.long

            val listResponse =
                client.get("/api/v1/items?parentId=${parent.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(
                HttpStatusCode.OK,
                listResponse.status,
                "GET items with a parentId filter must succeed: ${listResponse.bodyAsText()}",
            )
            val listTotal =
                Json
                    .parseToJsonElement(listResponse.bodyAsText())
                    .jsonObject["totalItems"]!!
                    .jsonPrimitive.long

            assertEquals(3L, childrenTotal, "children endpoint must report 3 alpha-tagged children")
            assertEquals(
                childrenTotal,
                listTotal,
                "/children and the equivalently-scoped/filtered GET /items must agree on the visible total",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // S15 — guard: an UNSCOPED caller keeps the pre-existing SQL LIMIT/OFFSET path (D6),
    // unaffected by tag filtering
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S15 unscoped GET children still uses the SQL LIMIT OFFSET path over all 5 children`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "S15 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "S15 Alpha1", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S15 Alpha2", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S15 Alpha3", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S15 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "S15 Beta2", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            // Default TEST_TOKEN via makeTestAuthConfig()'s default: unscoped (rootIds=null, tagsInclude=empty).
            application { configureTestApp { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${parent.id}/children?pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, json["items"]!!.jsonArray.size)
            assertEquals(
                5L,
                json["totalItems"]!!.jsonPrimitive.long,
                "Unscoped total must count all 5 children, not just the 3 alpha ones",
            )
            assertTrue(json["hasMore"]!!.jsonPrimitive.boolean)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S16 — guard: a rootIds-ONLY caller (no tagsInclude) also keeps the pre-existing path (D6)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S16 rootIds-only scoped GET children total is unaffected by tag filtering`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p0 = repo.workItemRepository().create(WorkItem(title = "S16 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    val p = repo.workItemRepository().update(p0.copy(rootId = p0.id)).getOrNull()!!
                    repo.workItemRepository().create(
                        WorkItem(title = "S16 Alpha1", tags = "alpha", parentId = p.id, depth = 1, rootId = p.id),
                    )
                    repo.workItemRepository().create(
                        WorkItem(title = "S16 Alpha2", tags = "alpha", parentId = p.id, depth = 1, rootId = p.id),
                    )
                    repo.workItemRepository().create(
                        WorkItem(title = "S16 Alpha3", tags = "alpha", parentId = p.id, depth = 1, rootId = p.id),
                    )
                    repo.workItemRepository().create(
                        WorkItem(title = "S16 Beta1", tags = "beta", parentId = p.id, depth = 1, rootId = p.id),
                    )
                    repo.workItemRepository().create(
                        WorkItem(title = "S16 Beta2", tags = "beta", parentId = p.id, depth = 1, rootId = p.id),
                    )
                    p
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(parent.id))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${parent.id}/children") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A rootIds-scoped, in-scope parent must be readable: ${response.bodyAsText()}",
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(5L, json["totalItems"]!!.jsonPrimitive.long, "rootIds-only scope must not apply the tag filter (D6)")
        }

    // ─────────────────────────────────────────────────────────────────────
    // P7 — probe: pageSize exactly matching the filtered total -> no more pages
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P7 tag-scoped GET children with pageSize exactly matching the filtered total has no more pages`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "P7 Parent", tags = "alpha", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "P7 Alpha1", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "P7 Alpha2", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "P7 Alpha3", tags = "alpha", parentId = p.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "P7 Beta1", tags = "beta", parentId = p.id, depth = 1))
                    p
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application { configureTestApp(authConfig) { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${parent.id}/children?pageSize=3") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(3, json["items"]!!.jsonArray.size, "All 3 alpha children must fit in one pageSize=3 page")
            assertEquals(3L, json["totalItems"]!!.jsonPrimitive.long)
            assertFalse(json["hasMore"]!!.jsonPrimitive.boolean, "An exactly-full last page must not claim hasMore")
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `67ba3514` ([Q5]) -- the REST half of S15: `GET /api/v1/items` after the
 * route stops resolving `rootId` and the token's tag scope in Kotlin and hands both to the store. NEW-SURFACE for the
 * behaviour change, EXISTING-SURFACE for the route, its query parameters and the response envelope.
 *
 * Oracles:
 *  - `task-scope` D2: `rootId` becomes `filter.ancestorId`, AND-ed with the token scope -- a `rootId` that is nested
 *    inside the token's root scope returns that subtree (it used to return an empty page because it was intersected with
 *    the scope as a set of roots). A `rootId` outside the scope never yields rows outside the scope. Tag-scoped callers
 *    get SQL-filtered pages and exact totals (the scan limit leaves `GET /items`). An inverted date range answers
 *    400 `validation_error`.
 *  - Response shape: `items`, `page`, `pageSize`, `totalItems`, `hasMore` as pinned by `ChildrenTagScopePaginationTest`
 *    and `PaginationBoundsTest`; the REST page size is capped at 200 (`PaginationBoundsTest` S9).
 *  - Query-parameter names `rootId`, `createdAfter`, `createdBefore`, `modifiedAfter`, `modifiedBefore`, `tagAny`:
 *    public evidence in `FilterParamValidationRouteTest` and `ItemRoutesTest` (the names were not in the declarations).
 *  - The 400 body: `error == "validation_error"` and a `message` naming the offending parameter, as pinned by
 *    `FilterParamValidationRouteTest` (F-013).
 *
 * ARBITRATION (recorded in `test-manifest`): the status for a `rootId` that lies OUTSIDE the token scope is not stated by
 * the notes (an empty 200 or a scope error are both consistent with "never yields rows outside the scope"). The test
 * asserts the contract that is stated -- no row of the outside tree is returned and the server does not fail -- and does
 * not pin the status.
 *
 * BLINDNESS: authored from the frozen notes, the declarations block and the pre-existing route tests. No `src/main`
 * file, implementer diff or implementer note was opened.
 */
class ItemListRouteScopeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private class Tree(
        val a: WorkItem,
        val b: WorkItem,
        val c: WorkItem,
        val d: WorkItem,
        val x: WorkItem,
        val y: WorkItem,
    )

    /** A(root) > B > {C, D}; X(root) > Y. When [stamped], every row carries its root's id as rootId. */
    private fun seedTree(stamped: Boolean): Tree =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()

            suspend fun root(title: String): WorkItem {
                val r0 = repo.create(WorkItem(title = title, depth = 0))
                return if (stamped) repo.update(r0.copy(rootId = r0.id))!! else r0
            }

            suspend fun child(
                title: String,
                parent: WorkItem,
                rootOf: WorkItem,
            ): WorkItem =
                repo.create(
                    WorkItem(title = title, parentId = parent.id, depth = parent.depth + 1, rootId = if (stamped) rootOf.id else null),
                )

            val a = root("A")
            val b = child("B", a, a)
            val c = child("C", b, a)
            val d = child("D", b, a)
            val x = root("X")
            val y = child("Y", x, x)
            Tree(a, b, c, d, x, y)
        }

    private suspend fun HttpClient.getAs(path: String): HttpResponse = get(path) { header("Authorization", "Bearer $TEST_TOKEN") }

    private fun JsonObject.titles(): List<String> = this["items"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }

    private suspend fun HttpClient.page(path: String): JsonObject {
        val response = getAs(path)
        assertEquals(HttpStatusCode.OK, response.status, "$path -> ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ============================================================================ rootId inside the scope

    @Test
    fun `S15 a rootId nested inside the token's root scope returns that subtree`() =
        testApplication {
            val t = seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.a.id))) { itemRoutes(repo) } }

            val nested = client.page("/api/v1/items?rootId=${t.b.id}")
            assertEquals(setOf("B", "C", "D"), nested.titles().toSet())
            assertEquals(3L, nested["totalItems"]!!.jsonPrimitive.long)

            val leaf = client.page("/api/v1/items?rootId=${t.c.id}")
            assertEquals(listOf("C"), leaf.titles())

            val same = client.page("/api/v1/items?rootId=${t.a.id}")
            assertEquals(setOf("A", "B", "C", "D"), same.titles().toSet())
            assertEquals(4L, same["totalItems"]!!.jsonPrimitive.long)
        }

    @Test
    fun `S15 a stamped tree gives the same nested-rootId result`() =
        testApplication {
            val t = seedTree(stamped = true)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.a.id))) { itemRoutes(repo) } }

            val nested = client.page("/api/v1/items?rootId=${t.b.id}")
            assertEquals(setOf("B", "C", "D"), nested.titles().toSet())
            assertEquals(3L, nested["totalItems"]!!.jsonPrimitive.long)
            assertEquals(listOf("C"), client.page("/api/v1/items?rootId=${t.c.id}").titles())
        }

    @Test
    fun `S15 without a rootId a root-scoped principal sees its own tree only`() =
        testApplication {
            val t = seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.a.id))) { itemRoutes(repo) } }

            val all = client.page("/api/v1/items")
            assertEquals(setOf("A", "B", "C", "D"), all.titles().toSet())
            assertEquals(4L, all["totalItems"]!!.jsonPrimitive.long)
        }

    // ============================================================================ rootId outside the scope

    @Test
    fun `S15 a rootId outside the token's scope never returns rows outside the scope`() =
        testApplication {
            val t = seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.a.id))) { itemRoutes(repo) } }

            for (outside in listOf(t.x.id, t.y.id)) {
                val response = client.getAs("/api/v1/items?rootId=$outside")
                val body = response.bodyAsText()
                assertTrue(response.status.value < 500, "the server must not fail: ${response.status} $body")
                assertFalse(body.contains("\"title\":\"X\""), "X leaked: $body")
                assertFalse(body.contains("\"title\":\"Y\""), "Y leaked: $body")
            }
        }

    @Test
    fun `S15 an unscoped caller can name any rootId`() =
        testApplication {
            val t = seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }

            assertEquals(setOf("B", "C", "D"), client.page("/api/v1/items?rootId=${t.b.id}").titles().toSet())
            assertEquals(setOf("X", "Y"), client.page("/api/v1/items?rootId=${t.x.id}").titles().toSet())
        }

    // ============================================================================ tag scope

    private fun seedTagged(
        alpha: Int,
        other: Int,
    ) = runBlocking {
        val repo = db.repositoryProvider().workItemRepository()
        val t0 = Instant.parse("2025-01-01T00:00:00Z")
        repeat(alpha) {
            repo.create(
                WorkItem(
                    title = "v$it",
                    depth = 0,
                    tags =
                        if (it % 2 ==
                            0
                        ) {
                            "alpha"
                        } else {
                            "x, alpha"
                        },
                    createdAt = t0.plusSeconds(it.toLong())
                ),
            )
        }
        // Newer than every visible row, so a window taken before the tag filter would lose visible rows.
        repeat(other) {
            repo.create(
                WorkItem(
                    title = "n$it",
                    depth = 0,
                    tags = listOf("beta", "alpha-2", "alphabet", "", null)[it % 5],
                    createdAt = t0.plusSeconds((alpha + 100 + it).toLong()),
                ),
            )
        }
    }

    @Test
    fun `S15 a tag-scoped principal pages through exactly its visible rows with an exact total`() =
        testApplication {
            seedTagged(alpha = 7, other = 6)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("alpha"))) { itemRoutes(repo) } }

            val seen = mutableListOf<String>()
            val expectedSizes = listOf(3, 3, 1)
            val expectedMore = listOf(true, true, false)
            for (p in 1..3) {
                val json = client.page("/api/v1/items?page=$p&pageSize=3")
                assertEquals(7L, json["totalItems"]!!.jsonPrimitive.long, "totalItems on page $p")
                assertEquals(expectedSizes[p - 1], json.titles().size, "size of page $p")
                assertEquals(expectedMore[p - 1], json["hasMore"]!!.jsonPrimitive.boolean, "hasMore on page $p")
                assertEquals(p, json["page"]!!.jsonPrimitive.int)
                seen += json.titles()
            }
            assertEquals(7, seen.toSet().size, "no overlap, no gap")
            assertTrue(seen.all { it.startsWith("v") }, "only visible rows: $seen")
        }

    @Test
    fun `S15 with more than a thousand visible rows every page is reachable and the total is exact`() =
        testApplication {
            seedTagged(alpha = 1005, other = 6)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("alpha"))) { itemRoutes(repo) } }

            val seen = mutableSetOf<String>()
            for (p in 1..6) {
                val json = client.page("/api/v1/items?page=$p&pageSize=200")
                assertEquals(1005L, json["totalItems"]!!.jsonPrimitive.long, "totalItems on page $p")
                val titles = json.titles()
                assertEquals(if (p < 6) 200 else 5, titles.size, "size of page $p")
                assertEquals(p < 6, json["hasMore"]!!.jsonPrimitive.boolean, "hasMore on page $p")
                assertTrue(titles.all { it.startsWith("v") }, "only visible rows on page $p")
                seen += titles
            }
            assertEquals(1005, seen.size, "every visible row reached exactly once")
            assertEquals(emptyList(), client.page("/api/v1/items?page=7&pageSize=200").titles())
        }

    @Test
    fun `S15 the tag scope and the root scope both apply`() =
        testApplication {
            val repo = db.repositoryProvider()
            val wr = repo.workItemRepository()
            val inside =
                runBlocking {
                    val root = wr.create(WorkItem(title = "R", depth = 0, tags = "alpha"))
                    val ok = wr.create(WorkItem(title = "inside-alpha", parentId = root.id, depth = 1, tags = "alpha"))
                    wr.create(WorkItem(title = "inside-beta", parentId = root.id, depth = 1, tags = "beta"))
                    wr.create(WorkItem(title = "outside-alpha", depth = 0, tags = "alpha"))
                    root to ok
                }
            application {
                configureTestApp(
                    makeTestAuthConfig(scopeRootIds = setOf(inside.first.id), tagsInclude = setOf("alpha"))
                ) { itemRoutes(repo) }
            }

            val json = client.page("/api/v1/items")
            assertEquals(setOf("R", "inside-alpha"), json.titles().toSet())
            assertEquals(2L, json["totalItems"]!!.jsonPrimitive.long)
            val filtered = client.page("/api/v1/items?rootId=${inside.second.id}")
            assertEquals(listOf("inside-alpha"), filtered.titles())
        }

    @Test
    fun `S15 an unscoped caller still filters by tagAny`() =
        testApplication {
            seedTagged(alpha = 4, other = 5)
            val repo = db.repositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }

            val json = client.page("/api/v1/items?tagAny=beta")
            assertEquals(listOf("n0"), json.titles())
        }

    // ============================================================================ inverted ranges

    private suspend fun assertValidation400(
        client: HttpClient,
        path: String,
        mention: String,
    ) {
        val response = client.getAs(path)
        assertEquals(HttpStatusCode.BadRequest, response.status, "$path -> ${response.bodyAsText()}")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "$path -> $json")
        assertTrue(
            json["message"]!!.jsonPrimitive.content.contains(mention, ignoreCase = true),
            "the message must name the range ('$mention'): $json",
        )
    }

    @Test
    fun `S15 an inverted created or modified range is a 400 validation_error naming the range`() =
        testApplication {
            seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }

            assertValidation400(client, "/api/v1/items?createdAfter=2025-12-01T00:00:00Z&createdBefore=2025-01-01T00:00:00Z", "created")
            assertValidation400(client, "/api/v1/items?modifiedAfter=2025-12-01T00:00:00Z&modifiedBefore=2025-01-01T00:00:00Z", "modified")
        }

    @Test
    fun `S15 an inverted range is still a 400 for a scoped principal and leaks no rows`() =
        testApplication {
            val t = seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.a.id))) { itemRoutes(repo) } }

            assertValidation400(client, "/api/v1/items?createdAfter=2999-01-01T00:00:00Z&createdBefore=2000-01-01T00:00:00Z", "created")
            assertValidation400(
                client,
                "/api/v1/items?rootId=${t.b.id}&modifiedAfter=2999-01-01T00:00:00Z&modifiedBefore=2000-01-01T00:00:00Z",
                "modified"
            )
        }

    @Test
    fun `S15 equal bounds and ordered ranges are accepted`() =
        testApplication {
            seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }

            assertEquals(
                HttpStatusCode.OK,
                client.getAs("/api/v1/items?createdAfter=2025-06-01T00:00:00Z&createdBefore=2025-06-01T00:00:00Z").status
            )
            val ordered = client.page("/api/v1/items?createdAfter=2000-01-01T00:00:00Z&createdBefore=2999-01-01T00:00:00Z")
            assertEquals(6, ordered.titles().size, "an all-covering ordered range keeps every row")
            val single = client.page("/api/v1/items?createdAfter=2999-01-01T00:00:00Z")
            assertEquals(emptyList(), single.titles(), "createdAfter in the far future keeps nothing")
        }

    // ============================================================================ regression guards

    @Test
    fun `S16 the default listing and orderBy keep working for an unscoped principal`() =
        testApplication {
            seedTree(stamped = false)
            val repo = db.repositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }

            val all = client.page("/api/v1/items?orderBy=title&orderDir=asc")
            assertEquals(listOf("A", "B", "C", "D", "X", "Y"), all.titles())
            assertEquals(6L, all["totalItems"]!!.jsonPrimitive.long)
            val desc = client.page("/api/v1/items?orderBy=title&orderDir=desc&pageSize=2&page=2")
            assertEquals(listOf("D", "C"), desc.titles())
            assertTrue(desc["hasMore"]!!.jsonPrimitive.boolean)
        }
}

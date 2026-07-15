package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            val root =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Root item", depth = 0)
                        ).getOrNull()!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
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
            val response =
                client.get("/api/v1/items/roots") {
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
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "My Item", depth = 0)).getOrNull()!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${item.id}") {
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
            val response =
                client.get("/api/v1/items/${UUID.randomUUID()}") {
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
            val response =
                client.get("/api/v1/items/not-a-uuid") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET items id tree returns 200 with descendants`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "Child", parentId = r.id, depth = 1))
                    r
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}/tree") {
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
            val child =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "Child", parentId = r.id, depth = 1)).getOrNull()!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${child.id}/breadcrumbs") {
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
            val root =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Root", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "Child1", parentId = r.id, depth = 1))
                    repo.workItemRepository().create(WorkItem(title = "Child2", parentId = r.id, depth = 1))
                    r
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}/children") {
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
            val itemOutsideScope =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Outside Scope Item", depth = 0)).getOrNull()!!
                }
            // Token scoped to r1 (a random UUID not matching itemOutsideScope's id)
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(r1))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${itemOutsideScope.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET items id returns 200 for item within scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "In Scope Root", depth = 0)).getOrNull()!!
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(root.id))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    /**
     * Opt-in unauthenticated mode ([ApiAuthConfig.Unauthenticated]): a request with NO
     * Authorization header at all is attached the synthetic ADMIN/unrestricted principal and
     * must reach this capability-gated (`requireCapability(READ)`), scope-checked
     * (`enforceScopeForItem`) route successfully — proving both checks pass unchanged for the
     * synthetic principal.
     */
    @Test
    fun `GET items id succeeds with no Authorization header in unauthenticated mode`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Unauth Item", depth = 0)).getOrNull()!!
                }
            application {
                configureTestApp(ApiAuthConfig.Unauthenticated) { itemRoutes(repo) }
            }
            val response = client.get("/api/v1/items/${root.id}")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Unauth Item"), "Expected item title in response: $body")
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
            val response =
                client.get("/api/v1/items?pageSize=2") {
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
            val response =
                client.get("/api/v1/items?pageSize=10") {
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
            val response =
                client.get("/api/v1/items?role=work") {
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
            val response =
                client.get("/api/v1/items?priority=high") {
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
            val response =
                client.get("/api/v1/items?tagAny=bug") {
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
            val item =
                runBlocking {
                    val i = repo.workItemRepository().create(WorkItem(title = "Noted", depth = 0)).getOrNull()!!
                    repo.noteRepository().upsert(
                        io.github.jpicklyk.mcptask.current.domain.model.Note(
                            itemId = i.id,
                            key = "spec",
                            role = "queue",
                            body = "This is a note"
                        )
                    )
                    i
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${item.id}?include=notes") {
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
            val root =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Parent", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "Inline Child", parentId = r.id, depth = 1))
                    r
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}?include=children") {
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

    // ─── Breadcrumbs scope truncation ────────────────────────────────────────

    /**
     * Token scoped to mid-tree item S: breadcrumbs for a leaf under S must start at S,
     * not expose ancestor A above S.
     */
    @Test
    fun `GET items id breadcrumbs truncates ancestors above scope root`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (scopeRoot, leaf) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "GrandParentA", depth = 0)).getOrNull()!!
                    val s = repo.workItemRepository().create(WorkItem(title = "ScopeRootS", parentId = a.id, depth = 1)).getOrNull()!!
                    val leaf = repo.workItemRepository().create(WorkItem(title = "LeafUnderS", parentId = s.id, depth = 2)).getOrNull()!!
                    s to leaf
                }
            // Token scoped to S — should not see GrandParentA
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(scopeRoot.id))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${leaf.id}/breadcrumbs") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("ScopeRootS"), "Expected scope root in chain: $body")
            assertTrue(body.contains("LeafUnderS"), "Expected leaf in chain: $body")
            assertFalse(body.contains("GrandParentA"), "Ancestor above scope root must be excluded: $body")
        }

    /**
     * Unscoped token (null rootIds) gets the full ancestor chain.
     */
    @Test
    fun `GET items id breadcrumbs returns full chain for unscoped token`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val leaf =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "TopA", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "MidB", parentId = a.id, depth = 1)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "LeafC", parentId = b.id, depth = 2)).getOrNull()!!
                }
            // Default TEST_TOKEN has null scopeRootIds — unrestricted
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${leaf.id}/breadcrumbs") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("TopA"), "Expected root in full chain: $body")
            assertTrue(body.contains("MidB"), "Expected mid in full chain: $body")
            assertTrue(body.contains("LeafC"), "Expected leaf in full chain: $body")
        }

    // ─── Tag-scope post-filtering on /tree and /children (Leak #3 regression) ────

    /**
     * A tag-scoped token requesting the tree of a tag-matching root must NOT see descendants
     * that do not carry the required tag.
     */
    @Test
    fun `GET items id tree with tag-scoped token excludes descendants without required tag`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, child1, child2) =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "SecRoot", tags = "security,api", depth = 0)).getOrNull()!!
                    // child1 does NOT have the 'security' tag
                    val c1 =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(title = "ApiOnlyChild", tags = "api", parentId = r.id, depth = 1)
                            ).getOrNull()!!
                    // child2 HAS the 'security' tag
                    val c2 =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(title = "SecChild", tags = "security", parentId = r.id, depth = 1)
                            ).getOrNull()!!
                    Triple(r, c1, c2)
                }
            // Token with tagsInclude = { "security" } — sees root and child2 but NOT child1
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("security"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}/tree") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("SecRoot"), "Expected root in tree: $body")
            assertTrue(body.contains("SecChild"), "Expected security-tagged child in tree: $body")
            assertFalse(body.contains("ApiOnlyChild"), "Must NOT expose non-security-tagged descendant: $body")
        }

    /**
     * A tag-scoped token requesting children of a tag-matching parent must NOT see children
     * that do not carry the required tag.
     */
    @Test
    fun `GET items id children with tag-scoped token excludes children without required tag`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, _, _) =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "SecParent", tags = "security", depth = 0)).getOrNull()!!
                    val c1 =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(title = "ApiChild", tags = "api", parentId = r.id, depth = 1)
                            ).getOrNull()!!
                    val c2 =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(title = "SecChild2", tags = "security,api", parentId = r.id, depth = 1)
                            ).getOrNull()!!
                    Triple(r, c1, c2)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("security"))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}/children") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("SecChild2"), "Expected security-tagged child: $body")
            assertFalse(body.contains("ApiChild"), "Must NOT expose non-security-tagged child: $body")
        }

    /**
     * An unscoped token (null rootIds, empty tagsInclude) must still see all descendants
     * of a tree — regression guard against over-filtering.
     */
    @Test
    fun `GET items id tree unscoped token returns all descendants regardless of tags`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "AllRoot", tags = "security", depth = 0)).getOrNull()!!
                    repo.workItemRepository().create(WorkItem(title = "UntaggedChild", depth = 1, parentId = r.id))
                    repo.workItemRepository().create(WorkItem(title = "TaggedChild", tags = "security", depth = 1, parentId = r.id))
                    r
                }
            // Default TEST_TOKEN has no scope restrictions
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${root.id}/tree") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("UntaggedChild"), "Unscoped token must see all descendants: $body")
            assertTrue(body.contains("TaggedChild"), "Unscoped token must see all descendants: $body")
        }

    // ─── /items/roots scoped fetch ────────────────────────────────────────────

    /**
     * Scoped token sees only its own roots, not all global roots.
     */
    @Test
    fun `GET items roots with scoped token returns only principal roots`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (inScopeRoot, outScopeRoot) =
                runBlocking {
                    val r1 = repo.workItemRepository().create(WorkItem(title = "InScopeRoot", depth = 0)).getOrNull()!!
                    val r2 = repo.workItemRepository().create(WorkItem(title = "OutOfScopeRoot", depth = 0)).getOrNull()!!
                    r1 to r2
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(inScopeRoot.id))
            application {
                configureTestApp(authConfig) { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/roots") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("InScopeRoot"), "Expected in-scope root: $body")
            assertFalse(body.contains("OutOfScopeRoot"), "Out-of-scope root must be excluded: $body")
        }

    // ─── /items/roots accuracy: true total, real pagination, skipped visibility ─

    /**
     * Directly blanks a row's title via Exposed, bypassing [WorkItem.validate]. Simulates a
     * corrupt/legacy row that the repository's row mapper must drop rather than fail the whole
     * query. Same technique as `SQLiteWorkItemRepositoryFilterTest.forceBlankTitle`.
     */
    private fun forceBlankTitle(
        database: Database,
        itemId: UUID,
    ) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.title] = ""
            }
        }
    }

    @Test
    fun `GET items roots unscoped reports true total and pages through all roots`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repeat(5) { i -> repo.workItemRepository().create(WorkItem(title = "Root $i", depth = 0)) }
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }

            val page1 =
                client.get("/api/v1/items/roots?page=1&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, page1.status)
            val page1Json = Json.parseToJsonElement(page1.bodyAsText()).jsonObject
            assertEquals(5L, page1Json["totalItems"]!!.jsonPrimitive.long, "totalItems must be the true root count")
            assertEquals(2, page1Json["items"]!!.jsonArray.size, "page 1 of pageSize=2 must return exactly 2 items")
            assertTrue(page1Json["hasMore"]!!.jsonPrimitive.boolean, "5 roots over pageSize=2 must have more pages")

            val page3 =
                client.get("/api/v1/items/roots?page=3&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, page3.status)
            val page3Json = Json.parseToJsonElement(page3.bodyAsText()).jsonObject
            assertEquals(5L, page3Json["totalItems"]!!.jsonPrimitive.long)
            assertFalse(page3Json["hasMore"]!!.jsonPrimitive.boolean, "last page (5 roots, offset 4) has no more")
        }

    @Test
    fun `GET items roots unscoped surfaces skipped for a row that fails domain validation`() =
        testApplication {
            val (repo, database) = buildH2RepositoryProviderWithDatabase()
            val good1 =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Good root 1", depth = 0)).getOrNull()!!
                }
            runBlocking { repo.workItemRepository().create(WorkItem(title = "Good root 2", depth = 0)) }
            val corrupt =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Will be corrupted", depth = 0)).getOrNull()!!
                }
            forceBlankTitle(database, corrupt.id)

            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/roots") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(3L, json["totalItems"]!!.jsonPrimitive.long, "corrupt row still counted in totalItems")
            assertEquals(1, json["skipped"]!!.jsonPrimitive.int)
            val body = response.bodyAsText()
            assertTrue(body.contains(good1.title), "Expected good root present: $body")
            assertFalse(body.contains("Will be corrupted"), "Corrupt root must be dropped from items: $body")
        }

    @Test
    fun `GET items roots omits skipped field when nothing was dropped`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Clean root", depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/roots") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNull(json["skipped"], "skipped must be omitted when nothing was dropped")
        }

    @Test
    fun `GET items surfaces skipped for a row that fails domain validation`() =
        testApplication {
            val (repo, database) = buildH2RepositoryProviderWithDatabase()
            runBlocking { repo.workItemRepository().create(WorkItem(title = "Good item", depth = 0)) }
            val corrupt =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Will be corrupted", depth = 0)).getOrNull()!!
                }
            forceBlankTitle(database, corrupt.id)

            application {
                configureTestApp { itemRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L, json["totalItems"]!!.jsonPrimitive.long, "corrupt row still counted in totalItems")
            assertEquals(1, json["skipped"]!!.jsonPrimitive.int)
        }
}

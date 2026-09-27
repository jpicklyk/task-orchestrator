package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wraps a real [WorkItemRepository], failing [delete] for exactly one id -- returning
 * [Result.Error] rather than throwing. Mirrors
 * [io.github.jpicklyk.mcptask.current.application.tools.items.DeleteItemLeaseReleaseTest]'s
 * `DeleteFailOnIdWorkItemRepository` idiom; renamed to avoid the same-package top-level name
 * collision with [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ItemDeleteLeaseReleaseRouteTest]'s
 * `FailOnIdResourceLeaseRepository`/`FailOnIdRepositoryProvider` (top-level `private` classes
 * still collide by simple name within a package).
 */
private class DeleteRouteFailOnIdWorkItemRepository(
    private val delegate: WorkItemRepository,
    private val failingId: UUID,
) : WorkItemRepository by delegate {
    override suspend fun delete(id: UUID): Result<Boolean> =
        if (id == failingId) {
            Result.Error(RepositoryError.DatabaseError("Simulated delete failure for $id"))
        } else {
            delegate.delete(id)
        }
}

/** Wraps a real [RepositoryProvider], substituting [failingWorkItemRepo] for [workItemRepository]. */
private class DeleteRouteFailOnIdRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failingWorkItemRepo: WorkItemRepository,
) : RepositoryProvider by delegate {
    override fun workItemRepository(): WorkItemRepository = failingWorkItemRepo
}

/**
 * A write-route app wired directly against `itemWriteRoutes` (not [configureWriteTestApp], which
 * is pinned to [io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider])
 * so a [RepositoryProvider] wrapper -- [DeleteRouteFailOnIdRepositoryProvider] -- can be installed.
 * Mirrors [ItemDeleteLeaseReleaseRouteTest]'s `configureDeleteLeaseTestApp`, renamed for the same
 * same-package top-level collision reason as the classes above.
 */
private fun Application.configureDeleteRouteFailTestApp(provider: RepositoryProvider) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    val authConfig = makeWriteAuthConfig()
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    authConfig.tokens.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    }
            }
            itemWriteRoutes(
                provider,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyCache(),
                ToolExecutionContext(
                    provider,
                    NoOpNoteSchemaService,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                ).advanceServiceFactory(),
            )
        }
    }
}

/**
 * Independent test authorship for item fc8f3748 (needs-test-author) -- REST DELETE /items/{id}
 * recursive/409 parity fixes.
 *
 * Runs on real SQLite (SQLiteRepositoryTestBase) per the item's declared harness requirement:
 * the H2 database used by the other route tests in this package has no parent_id foreign key,
 * which would hide the original 500 this item fixes.
 *
 * Oracles (frozen in test-plan note 826560f2 / diagnosis note 5237085a, before this file was
 * written):
 *  [DX] diagnosis "REST contract" section -- check order id -> recursive -> 404 -> 403 -> If-Match
 *       412 -> deletion; recursive trimmed + case-insensitive; has_children counts DIRECT
 *       children only; a recursive delete also cascades descendants notes and dependency edges
 *       and releases every deleted row resource leases.
 *  [AR] api-rest.md :1009 cascade promise, :1017 recursive case-insensitivity.
 *  [MR] the MCP DeleteItemHandler "has N child item(s)" message, carried into the REST body.
 *
 * S-ids below are the test-plan note's numbering; probes are the note's Probes section.
 */
class ItemDeleteRecursiveSqliteRouteTest : SQLiteRepositoryTestBase() {
    private suspend fun createRoot(title: String): WorkItem {
        val created = repositoryProvider.workItemRepository().create(WorkItem(title = title, depth = 0)).getOrNull()!!
        return repositoryProvider.workItemRepository().update(created.copy(rootId = created.id)).getOrNull()!!
    }

    private suspend fun createChild(
        parent: WorkItem,
        title: String,
        depth: Int,
    ): WorkItem =
        repositoryProvider
            .workItemRepository()
            .create(
                WorkItem(
                    title = title,
                    parentId = parent.id,
                    rootId = parent.rootId ?: parent.id,
                    depth = depth,
                ),
            ).getOrNull()!!

    private suspend fun exists(id: UUID): Boolean = repositoryProvider.workItemRepository().getById(id) is Result.Success

    // -----------------------------------------------------------------------
    // S1 -- happy: DELETE a parent with no query param -> 409 has_children, nothing deleted
    // -----------------------------------------------------------------------

    @Test
    fun `S1 DELETE parent with no recursive param returns 409 has_children and deletes nothing`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val (p, c, g) =
                runBlocking {
                    val p = createRoot("P")
                    val c = createChild(p, "C", 1)
                    val g = createChild(c, "G", 2)
                    Triple(p, c, g)
                }

            val response =
                client.delete("/api/v1/items/${c.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("has_children", json["error"]?.jsonPrimitive?.content)
            assertTrue(
                json["message"]?.jsonPrimitive?.content?.contains("has 1 child item(s)") == true,
                "body: ${response.bodyAsText()}",
            )
            assertEquals(
                1,
                json["details"]
                    ?.jsonObject
                    ?.get("childCount")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt()
            )

            runBlocking {
                assertTrue(exists(p.id), "P must persist")
                assertTrue(exists(c.id), "C must persist")
                assertTrue(exists(g.id), "G must persist")
            }
        }

    // -----------------------------------------------------------------------
    // S2 -- happy: recursive=true cascades the subtree, its notes and its dependency edges,
    // leaving an item outside the subtree untouched
    // -----------------------------------------------------------------------

    @Test
    fun `S2 DELETE recursive=true deletes the whole subtree, its notes and dependency edges, leaves outside items`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            lateinit var p: WorkItem
            lateinit var c: WorkItem
            lateinit var g: WorkItem
            lateinit var x: WorkItem
            runBlocking {
                p = createRoot("P")
                c = createChild(p, "C", 1)
                g = createChild(c, "G", 2)
                x = createRoot("X")
                repositoryProvider.noteRepository().upsert(
                    Note(itemId = g.id, key = "spec", role = "queue", body = "G note"),
                )
                repositoryProvider.dependencyRepository().create(
                    Dependency(fromItemId = c.id, toItemId = x.id, type = DependencyType.BLOCKS),
                )
            }

            val response =
                client.delete("/api/v1/items/${p.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(p.id.toString(), json["id"]?.jsonPrimitive?.content)
            assertEquals(3, json["deleted"]?.jsonPrimitive?.content?.toInt())
            assertEquals(2, json["descendantsDeleted"]?.jsonPrimitive?.content?.toInt())

            runBlocking {
                assertFalse(exists(p.id), "P must be gone")
                assertFalse(exists(c.id), "C must be gone")
                assertFalse(exists(g.id), "G must be gone")
                assertTrue(exists(x.id), "X (outside the subtree) must persist")

                val note = repositoryProvider.noteRepository().findByItemIdAndKey(g.id, "spec")
                assertIs<Result.Success<*>>(note)
                assertNull((note as Result.Success<*>).data, "G note must be gone")

                val xDeps = repositoryProvider.dependencyRepository().findByItemId(x.id)
                assertTrue(xDeps.none { it.fromItemId == c.id }, "the C->X dependency edge must be gone")
            }
        }

    // -----------------------------------------------------------------------
    // S3 -- happy: DELETE a leaf with no query param -> 204, unchanged from before
    // -----------------------------------------------------------------------

    @Test
    fun `S3 DELETE a leaf with no recursive param returns 204 and removes the row`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            val response =
                client.delete("/api/v1/items/${leaf.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            runBlocking { assertFalse(exists(leaf.id), "leaf must be gone") }
        }

    // -----------------------------------------------------------------------
    // S6 -- failure: explicit recursive=false on a parent behaves like the absent case
    // -----------------------------------------------------------------------

    @Test
    fun `S6 DELETE parent with explicit recursive=false returns 409 and deletes nothing`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val (p, c) =
                runBlocking {
                    val p = createRoot("P")
                    val c = createChild(p, "C", 1)
                    Pair(p, c)
                }

            val response =
                client.delete("/api/v1/items/${p.id}?recursive=false") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            runBlocking {
                assertTrue(exists(p.id), "P must persist")
                assertTrue(exists(c.id), "C must persist")
            }
        }

    // -----------------------------------------------------------------------
    // S7 -- failure: an unrecognized recursive value is a 400, not a silent false
    // -----------------------------------------------------------------------

    @Test
    fun `S7 unrecognized recursive value returns 400 validation_error and the leaf persists`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            for (raw in listOf("1", "yes", "")) {
                val response =
                    client.delete("/api/v1/items/${leaf.id}?recursive=$raw") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                    }
                assertEquals(
                    HttpStatusCode.BadRequest,
                    response.status,
                    "recursive=$raw body: ${response.bodyAsText()}",
                )
                assertTrue(response.bodyAsText().contains("validation_error"), "recursive=$raw")
            }

            runBlocking { assertTrue(exists(leaf.id), "leaf must persist through every rejected value") }
        }

    // -----------------------------------------------------------------------
    // S8 -- failure: recursive=true still honors If-Match
    // -----------------------------------------------------------------------

    @Test
    fun `S8 DELETE recursive=true with a mismatched If-Match returns 412 and the subtree persists`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val (p, c, g) =
                runBlocking {
                    val p = createRoot("P")
                    val c = createChild(p, "C", 1)
                    val g = createChild(c, "G", 2)
                    Triple(p, c, g)
                }

            val response =
                client.delete("/api/v1/items/${p.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // stale
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status, "body: ${response.bodyAsText()}")
            runBlocking {
                assertTrue(exists(p.id), "P must persist")
                assertTrue(exists(c.id), "C must persist")
                assertTrue(exists(g.id), "G must persist")
            }
        }

    // -----------------------------------------------------------------------
    // S9 -- failure: recursive=true on an unknown id is a 404, not a silent no-op success
    // -----------------------------------------------------------------------

    @Test
    fun `S9 DELETE recursive=true on an unknown UUID returns 404`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }

            val response =
                client.delete("/api/v1/items/${UUID.randomUUID()}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.NotFound, response.status, "body: ${response.bodyAsText()}")
        }

    // -----------------------------------------------------------------------
    // S12 -- edge: recursive=true on a leaf is legal and reports 0 descendants
    // -----------------------------------------------------------------------

    @Test
    fun `S12 DELETE a leaf with recursive=true returns 200 deleted=1 descendantsDeleted=0`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            val response =
                client.delete("/api/v1/items/${leaf.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, json["deleted"]?.jsonPrimitive?.content?.toInt())
            assertEquals(0, json["descendantsDeleted"]?.jsonPrimitive?.content?.toInt())
            runBlocking { assertFalse(exists(leaf.id)) }
        }

    // -----------------------------------------------------------------------
    // S13 -- edge: recursive is trimmed and case-insensitive
    // -----------------------------------------------------------------------

    @Test
    fun `S13 recursive=TRUE and recursive=True both succeed with 200`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leafUpper = runBlocking { createRoot("Leaf TRUE") }
            val leafMixed = runBlocking { createRoot("Leaf True") }

            val responseUpper =
                client.delete("/api/v1/items/${leafUpper.id}?recursive=TRUE") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, responseUpper.status, "body: ${responseUpper.bodyAsText()}")

            val responseMixed =
                client.delete("/api/v1/items/${leafMixed.id}?recursive=True") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, responseMixed.status, "body: ${responseMixed.bodyAsText()}")
        }

    // -----------------------------------------------------------------------
    // S14 -- edge: deleting a non-root subtree deletes exactly that subtree, parent survives
    // -----------------------------------------------------------------------

    @Test
    fun `S14 DELETE C recursive=true deletes 2 and leaves P persisting with 0 children`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val (p, c, g) =
                runBlocking {
                    val p = createRoot("P")
                    val c = createChild(p, "C", 1)
                    val g = createChild(c, "G", 2)
                    Triple(p, c, g)
                }

            val response =
                client.delete("/api/v1/items/${c.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, json["deleted"]?.jsonPrimitive?.content?.toInt())
            assertEquals(1, json["descendantsDeleted"]?.jsonPrimitive?.content?.toInt())

            runBlocking {
                assertTrue(exists(p.id), "P must persist")
                assertFalse(exists(c.id))
                assertFalse(exists(g.id))
            }

            val childrenResponse =
                client.get("/api/v1/items/${p.id}/children") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, childrenResponse.status)
            val childrenJson = Json.parseToJsonElement(childrenResponse.bodyAsText()).jsonObject
            assertEquals(0L, childrenJson["totalItems"]?.jsonPrimitive?.content?.toLong())
        }

    // -----------------------------------------------------------------------
    // S15 -- edge: has_children childCount is DIRECT children only, not the whole subtree
    // -----------------------------------------------------------------------

    @Test
    fun `S15 has_children childCount counts direct children only`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val p =
                runBlocking {
                    val p = createRoot("P")
                    val c1 = createChild(p, "C1", 1)
                    createChild(p, "C2", 1)
                    createChild(p, "C3", 1)
                    createChild(c1, "G1", 2)
                    p
                }

            val response =
                client.delete("/api/v1/items/${p.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                3,
                json["details"]
                    ?.jsonObject
                    ?.get("childCount")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt()
            )
        }

    // -----------------------------------------------------------------------
    // S18 -- a recursive delete releases every deleted row resource leases (2cef6ca4 diagnosis)
    // -----------------------------------------------------------------------

    @Test
    fun `S18 DELETE recursive=true releases and closes a descendant active lease`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val (p, c, g) =
                runBlocking {
                    val p = createRoot("P")
                    val c = createChild(p, "C", 1)
                    val g = createChild(c, "G", 2)
                    Triple(p, c, g)
                }
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(
                runBlocking { leaseRepo.acquireAll(g.id, "agent-a", listOf("k-s18" to 900)) },
            )

            val response =
                client.delete("/api/v1/items/${p.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            runBlocking {
                assertTrue(leaseRepo.findActiveForItem(g.id).isEmpty(), "G must have no active lease")
            }
            val interval = runBlocking { leaseRepo.findRecentIntervals("k-s18", 10) }.single()
            assertNotNull(interval.releasedAt, "G lease history interval must be closed")
            assertEquals("released", interval.releaseReason)
        }

    // -----------------------------------------------------------------------
    // B1a-REST -- REST analogue of 2cef6ca4's B1a: a successful release followed by a FAILING
    // row delete must roll the release back too, on the non-recursive leaf path (a leaf so the
    // fc8f3748 HasChildren pre-check does not intercept it first). Oracle: fc8f3748 diagnosis
    // "release and delete commit or roll back together" + 2cef6ca4 diagnosis "an open interval is
    // closed exactly once, only when its holder row is actually deleted".
    // -----------------------------------------------------------------------

    @Test
    fun `B1a-REST DELETE of a leased leaf whose row delete fails after a successful release rolls back too`(): Unit =
        testApplication {
            val leaf = runBlocking { createRoot("Will Fail") }
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(
                runBlocking { leaseRepo.acquireAll(leaf.id, "agent-a", listOf("k-b1a-rest" to 900)) },
            )
            val failingWorkItemRepo = DeleteRouteFailOnIdWorkItemRepository(repositoryProvider.workItemRepository(), leaf.id)
            application {
                configureDeleteRouteFailTestApp(DeleteRouteFailOnIdRepositoryProvider(repositoryProvider, failingWorkItemRepo))
            }

            val response =
                client.delete("/api/v1/items/${leaf.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content)

            runBlocking { assertTrue(exists(leaf.id), "the row delete failed -- the item must remain") }

            val interval = runBlocking { leaseRepo.findRecentIntervals("k-b1a-rest", 10) }.single()
            assertNull(interval.releaseReason, "the release must be rolled back together with the failed delete")
            assertNull(interval.releasedAt, "the release must be rolled back together with the failed delete")
            assertEquals(
                1,
                runBlocking { leaseRepo.findActiveForItem(leaf.id) }.size,
                "the lease must still be ACTIVE, not just history-open",
            )
        }

    // -----------------------------------------------------------------------
    // Probes
    // -----------------------------------------------------------------------

    @Test
    fun `probe percent-encoded recursive value percent-74-r-u-e decodes to true`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            val response =
                client.delete("/api/v1/items/${leaf.id}?recursive=%74rue") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            runBlocking { assertFalse(exists(leaf.id)) }
        }

    @Test
    fun `probe repeated recursive query param does not 500`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            val response =
                client.delete("/api/v1/items/${leaf.id}?recursive=true&recursive=false") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertNotEquals(
                HttpStatusCode.InternalServerError,
                response.status,
                "repeated recursive param must not crash the route: ${response.bodyAsText()}",
            )
        }

    @Test
    fun `probe a 4-level chain deletes all 5 items`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val p0 =
                runBlocking {
                    val n0 = createRoot("N0")
                    val n1 = createChild(n0, "N1", 1)
                    val n2 = createChild(n1, "N2", 2)
                    val n3 = createChild(n2, "N3", 3)
                    createChild(n3, "N4", 4)
                    n0
                }

            val response =
                client.delete("/api/v1/items/${p0.id}?recursive=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(5, json["deleted"]?.jsonPrimitive?.content?.toInt())
            assertEquals(4, json["descendantsDeleted"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `probe repeating the same DELETE returns 404 the second time`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val leaf = runBlocking { createRoot("Leaf") }

            val first =
                client.delete("/api/v1/items/${leaf.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, first.status)

            val second =
                client.delete("/api/v1/items/${leaf.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, second.status, "body: ${second.bodyAsText()}")
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent test author coverage for MCP item 544ae4b9 — PATCH /items/{id} re-parent must run
 * scope enforcement on the NEW parent, exactly as POST /items rejects an out-of-scope parent.
 *
 * Scenarios S1..S10 per the item's `test-plan` note (frozen at queue phase). Oracles:
 *   O1 current/docs/api-rest.md sec.3 + error table — writes return 403 scope_forbidden when
 *      the target is outside scope.
 *   O2 current/docs/api-rest.md POST /items — "403 scope_forbidden — parent outside scope"; a
 *      re-parent's new parent is the same authorization object, so PATCH must reject alike. The
 *      existing POST /items branch (ItemWriteRoutes.kt, `enforceScopeForItem(call, parentId, ...)`
 *      before the not_found check... actually not_found is checked FIRST, then scope, responding
 *      `errorCaptured(HttpStatusCode.Forbidden, "scope_forbidden", "Access denied for parent $id")`)
 *      is the precedent this file mirrors.
 *   O3 ApiPrincipal.kt (ApiScope KDoc) / AuthorizationPlugin.kt (`enforceScopeForItem` KDoc) —
 *      `rootIds` walks the ancestor chain (item's own id counts), `tagsInclude` is item-only
 *      (no ancestor walk), an empty/null scope is unrestricted.
 *   O4 RFC 7396 sec.2 — an absent key leaves the field unchanged; an explicit `null` removes it
 *      (a `parentId: null` patch moves the item to root). Per item `e6967195`'s D3, a move-to-root
 *      is now scope-checked against the ITEM'S OWN id (after the move its ancestor chain is just
 *      `{id}`) whenever `rootIds` is non-null -- see the updated S7 below.
 *
 * Blindness: this file was authored from the item's `test-plan` note, the public signature of
 * `enforceScopeForItem`/`allowsItemTags`/`ApiScope`/`ApiPrincipal`, the existing POST /items scope
 * branch (unchanged by this item, already covered by `WriteScopeEnforcementTest` for the read-side
 * analogue), and existing PATCH-route test conventions in WriteRoutesTest.kt. It does NOT read the
 * implementer's changed PATCH new-parent branch body, `implementation-notes`, or `session-tracking`.
 */
class PatchReparentScopeTest {
    // ─────────────────────────────────────────────────────────────────────
    // S1 — happy: rootIds scope, old and new parent both inside scope
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 reparent within rootIds scope succeeds and recomputes depth and rootId`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, p) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root R", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    val pItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "P", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    Triple(r, xItem, pItem)
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "In-scope reparent must succeed: ${response.bodyAsText()}")

            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(p.id, persisted.data.parentId, "X must be re-parented to P")
            assertEquals(2, persisted.data.depth, "X's depth must be recomputed from P's depth")
            assertEquals(root.id, persisted.data.rootId, "X's rootId must remain R (P is under the same root)")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S2 — happy: unscoped principal, no rootIds/tagsInclude restriction
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 reparent with unscoped principal succeeds regardless of new parent`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, q) =
                runBlocking {
                    val xItem = repo.workItemRepository().create(WorkItem(title = "X Unscoped", depth = 0)).getOrNull()!!
                    val qItem = repo.workItemRepository().create(WorkItem(title = "Q Unscoped", depth = 0)).getOrNull()!!
                    Pair(xItem, qItem)
                }
            // makeWriteAuthConfig() with no scopeRootIds -> rootIds = null -> unrestricted.
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig()) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "Unscoped principal must be able to reparent anywhere: ${response.bodyAsText()}"
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(q.id, (persisted as Result.Success).data.parentId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S3 — happy: parentId absent (title-only patch) under scope -> no parent scope check at all
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 title-only patch under rootIds scope succeeds without a parent scope check`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root For S3", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(x.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Title Only Update"}""") // parentId absent
                }

            assertEquals(HttpStatusCode.OK, response.status, "Title-only patch under scope must succeed: ${response.bodyAsText()}")
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals("Title Only Update", (persisted as Result.Success).data.title)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S4 — REGRESSION (red pre-fix): rootIds scope, new parent under a different root
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 reparent to a new parent outside rootIds scope is rejected 403 scope_forbidden`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, q) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root R S4", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X S4", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    // Q is its own, unrelated root — NOT under R.
                    val q0 = repo.workItemRepository().create(WorkItem(title = "Q S4", depth = 0)).getOrNull()!!
                    val qItem = repo.workItemRepository().update(q0.copy(rootId = q0.id)).getOrNull()!!
                    Triple(r, xItem, qItem)
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status, "Reparent under an out-of-scope root must be rejected")
            val body = response.bodyAsText()
            assertTrue(body.contains("scope_forbidden"), "Should report scope_forbidden like POST /items does: $body")

            // Hard negative: X's parentId/depth/rootId must be unchanged.
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(root.id, persisted.data.parentId, "X's parentId must be unchanged after a rejected reparent")
            assertEquals(1, persisted.data.depth, "X's depth must be unchanged after a rejected reparent")
            assertEquals(root.id, persisted.data.rootId, "X's rootId must be unchanged after a rejected reparent")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S5 — failure: tagsInclude scope, new parent outside the tag allowlist
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 reparent to a new parent outside tagsInclude scope is rejected 403 scope_forbidden`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, q) =
                runBlocking {
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X S5", depth = 0, tags = "alpha"))
                            .getOrNull()!!
                    val qItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Q S5", depth = 0, tags = "beta"))
                            .getOrNull()!!
                    Pair(xItem, qItem)
                }

            // makeWriteAuthConfig has no tagsInclude parameter (per test-plan note) — build the
            // tag-scoped WRITE principal locally, reusing the base config's other tokens.
            val tagScopedToken = "integration-write-token-tagscope-s5"
            val tagScopedPrincipal =
                ApiPrincipal(
                    tokenId = "test-write-tagscope-s5",
                    scope = ApiScope(rootIds = null, tagsInclude = setOf("alpha")),
                    capabilities =
                        setOf(
                            ApiCapability.READ,
                            ApiCapability.WRITE_ITEMS,
                            ApiCapability.WRITE_NOTES,
                            ApiCapability.ADVANCE,
                            ApiCapability.MANAGE_DEPENDENCIES,
                            ApiCapability.WRITE_CONFIG,
                        ),
                    authMode = ApiAuthMode.BEARER,
                )
            val base = makeWriteAuthConfig()
            val authConfig =
                ApiAuthConfig.Bearer(
                    tokens = base.tokens + (HashBytes(sha256(tagScopedToken)) to tagScopedPrincipal),
                )
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $tagScopedToken")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status, "Reparent under a tag-excluded parent must be rejected")
            val body = response.bodyAsText()
            assertTrue(body.contains("scope_forbidden"), "Should report scope_forbidden: $body")

            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(null, (persisted as Result.Success).data.parentId, "X's parentId must be unchanged (still root)")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S6 — failure: new parent id doesn't exist -> 400 not_found (existence before scope)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 reparent to a nonexistent new parent returns 400 not_found before any scope check`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x = runBlocking { repo.workItemRepository().create(WorkItem(title = "X S6", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig()) }

            val missingParentId = UUID.randomUUID()
            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"$missingParentId"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "Nonexistent new parent must be a 400, not a 403")
            val body = response.bodyAsText()
            assertTrue(body.contains("not_found"), "Should report not_found (existence checked before scope): $body")
            assertFalse(body.contains("scope_forbidden"), "Must not report scope_forbidden for a missing parent: $body")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S7 — edge: parentId:null under rootIds scope (move to root, no parent object to check)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 reparent parentId null under rootIds scope where the item's own id is out of scope is rejected 403 scope_forbidden`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root S7", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X S7", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    Pair(r, xItem)
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }

            // Updated per D3 (item e6967195): a move-to-root is now scope-checked against the
            // item's OWN id, since after the move its ancestor chain is just {id}. Here the scope
            // is rootIds={root.id}, not {x.id}, so X leaving its scoped subtree to become an
            // unscoped root is rejected -- closing exactly the scope-escape vector the prior
            // version of this test flagged as an ESCALATE-during-review note.
            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Move-to-root must be rejected when the item's own id is not itself a scope member: ${response.bodyAsText()}"
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(root.id, persisted.data.parentId, "X's parentId must be unchanged after a rejected move-to-root")
            assertEquals(1, persisted.data.depth, "X's depth must be unchanged after a rejected move-to-root")
            assertEquals(root.id, persisted.data.rootId, "X's rootId must be unchanged after a rejected move-to-root")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S8 — edge: self-parent / descendant-as-parent must not be accepted as 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8a reparent to itself is rejected with a 4xx client error`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x = runBlocking { repo.workItemRepository().create(WorkItem(title = "X S8a", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig()) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${x.id}"}""")
                }

            assertNotEquals(HttpStatusCode.OK, response.status, "Self-parenting must not succeed: ${response.bodyAsText()}")
            assertTrue(
                response.status.value in 400..499,
                "Self-parenting must be rejected as a client error (4xx), got ${response.status}: ${response.bodyAsText()}",
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(null, (persisted as Result.Success).data.parentId, "X must not become its own parent")
        }

    @Test
    fun `S8b reparent to its own descendant is rejected with a 4xx client error`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, child) =
                runBlocking {
                    val xItem = repo.workItemRepository().create(WorkItem(title = "X S8b", depth = 0)).getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Child of X S8b", parentId = xItem.id, depth = 1))
                            .getOrNull()!!
                    Pair(xItem, c)
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig()) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${child.id}"}""")
                }

            assertNotEquals(
                HttpStatusCode.OK,
                response.status,
                "Reparenting under one's own descendant must not succeed: ${response.bodyAsText()}"
            )
            assertTrue(
                response.status.value in 400..499,
                "Descendant-as-parent must be rejected as a client error (4xx), got ${response.status}: ${response.bodyAsText()}",
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(null, (persisted as Result.Success).data.parentId, "X must not become a descendant of its own child")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S9 — a rejected reparent must not touch modifiedAt or cascade descendant depth
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 reparent rejected for scope does not touch modifiedAt or cascade descendant depth`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, descendant, q) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root S9", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X S9", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    val d =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Descendant of X S9", parentId = xItem.id, depth = 2, rootId = r.id))
                            .getOrNull()!!
                    val q0 = repo.workItemRepository().create(WorkItem(title = "Q S9", depth = 0)).getOrNull()!!
                    val qItem = repo.workItemRepository().update(q0.copy(rootId = q0.id)).getOrNull()!!
                    listOf(r, xItem, d, qItem)
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status, "Out-of-scope reparent must be rejected: ${response.bodyAsText()}")

            val persistedX = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persistedX)
            assertEquals(x.modifiedAt, persistedX.data.modifiedAt, "A 403-rejected PATCH must not touch X's modifiedAt")

            val persistedDescendant = runBlocking { repo.workItemRepository().getById(descendant.id) }
            assertIs<Result.Success<WorkItem>>(persistedDescendant)
            assertEquals(2, persistedDescendant.data.depth, "Descendant depth must not cascade when the reparent is rejected")
            assertEquals(root.id, persistedDescendant.data.rootId, "Descendant rootId must not cascade when the reparent is rejected")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S10 — Idempotency-Key replay of a 403'd re-parent replays 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 reparent rejected for scope replays the same 403 on Idempotency-Key replay`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, q) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "Root S10", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "X S10", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    val q0 = repo.workItemRepository().create(WorkItem(title = "Q S10", depth = 0)).getOrNull()!!
                    val qItem = repo.workItemRepository().update(q0.copy(rootId = q0.id)).getOrNull()!!
                    Triple(r, xItem, qItem)
                }
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))
            application { configureWriteTestApp(repo, authConfig = authConfig) }

            val idempotencyKey = UUID.randomUUID().toString()
            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val makeRequest: suspend () -> io.ktor.client.statement.HttpResponse = {
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }
            }

            val first = makeRequest()
            val second = makeRequest()

            assertEquals(HttpStatusCode.Forbidden, first.status, "First rejected reparent must be 403: ${first.bodyAsText()}")
            assertEquals(
                HttpStatusCode.Forbidden,
                second.status,
                "Idempotency-Key replay of a 403 must replay 403, not silently succeed: ${second.bodyAsText()}"
            )
            assertTrue(
                second.bodyAsText().contains("scope_forbidden"),
                "Replayed response should still report scope_forbidden: ${second.bodyAsText()}"
            )

            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(
                root.id,
                (persisted as Result.Success).data.parentId,
                "X must never have been reparented across both replayed attempts"
            )
        }
}

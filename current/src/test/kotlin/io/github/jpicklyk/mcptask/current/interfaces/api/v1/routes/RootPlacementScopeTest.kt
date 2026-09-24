package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for MCP item `e6967195` ("Close REST scope escapes: root
 * create/reparent-to-root and /children paging before tag filter"), scenarios S1-S10 and probes
 * P1-P6 of the frozen `test-plan` note (queue phase, `planning-seat:e6967195`). Scenarios S11-S16
 * and probe P7 (the `/children` tag-scoped pagination half of this item) live in
 * `ChildrenTagScopePaginationTest.kt`.
 *
 * Oracles (per `test-plan`):
 *  - O1 `current/docs/api-rest.md` sec.3 + [ApiScope] KDoc — an item is accessible only if its
 *    ancestor chain, including itself, satisfies a non-null `rootIds` scope; `tagsInclude` is
 *    item-only, trimmed, exact-match, never ancestor-walked.
 *  - O4 `current/docs/api-rest.md` sec.6 error table — `scope_forbidden` maps to HTTP 403.
 *
 * Decisions (frozen `diagnosis` note, `planning-seat:e6967195`):
 *  - D1: `POST /items` producing a depth-0 item (`parentId` absent OR an explicit `null`) by a
 *    principal with a non-null `rootIds` scope -> 403 `scope_forbidden`, nothing persisted. A new
 *    root's ancestor chain is only its own server-generated id, which can never be a member of a
 *    fixed, pre-existing `rootIds` set.
 *  - D2: a tag-only principal (`rootIds == null`, non-empty `tagsInclude`) may create a root iff
 *    the NEW item's own stored tags satisfy `principal.allowsItemTags(<stored CSV>)` — a root is
 *    its own scope anchor, mirroring the existing parent-tag check `POST /items` already runs for
 *    a non-null `parentId`.
 *  - D3: `PATCH /items/{id}` moving `parentId` from non-null to `null` (move-to-root) -> 403 iff
 *    `principal.scope.rootIds != null && id !in principal.scope.rootIds`. After the move the
 *    item's own chain is just `{id}`, so it stays in scope exactly when its own id is a scope
 *    member. No DB lookup; the tag half of scope was already checked pre-fix at the item's
 *    current position.
 *  - D7: the 403 envelope is `ErrorDto("scope_forbidden", <message>)`; the checks sit where the
 *    existing parent-scope check already sits, so 400/412/415 precedence is unchanged.
 *
 * BLINDNESS: authored from this item's `diagnosis` and `test-plan` notes (both queue-phase,
 * frozen before implementation, per the `test-author` skill's blindness rule), the verbatim
 * public declarations supplied inline in the dispatch prompt (`ItemWriteRoutes.kt` signature and
 * KDoc only — no branch bodies; `ApiPrincipal`/`ApiScope`/`ApiCapability`/`ApiAuthMode`;
 * `WorkItem`/`Priority`/`Role`; `WorkItemRepository`; `ItemCreateDto`/`ItemPatchDto`/`ItemDto`/
 * `PageDto`/`ErrorDto`), and the existing test conventions in this package (`ApiTestHelper.kt`,
 * `WriteRoutesTest.kt`'s `configureWriteTestApp`, `PatchReparentScopeTest.kt`'s fixture and
 * tag-scoped-principal patterns). No `src/main` file, diff, or commit was read to author this
 * suite.
 *
 * ARBITRATION (recorded in full in `test-manifest`): P2's plan wording ("case: [ALPHA] vs
 * {alpha}") is ambiguous about which side of the comparison carries the uppercase value.
 * `WorkItem.validateTags`'s `TAG_PATTERN` (`^[a-z0-9][a-z0-9-]*$`, supplied verbatim) makes an
 * uppercase ITEM tag unconstructible — `WorkItem`'s own `init` block rejects it before any scope
 * check can run. Resolved by putting the uppercase side on the principal's `tagsInclude` (a plain
 * `Set<String>`, not subject to `WorkItem` validation) against a validly-stored, lowercase item
 * tag `"alpha"` — this still exercises O1's case-sensitive exact match, the property P2 names.
 */
class RootPlacementScopeTest {
    private fun parseId(body: String): UUID =
        UUID.fromString(
            Json
                .parseToJsonElement(body)
                .jsonObject["id"]!!
                .jsonPrimitive.content
        )

    private fun writeScopedPrincipal(
        tokenId: String,
        rootIds: Set<UUID>?,
        tagsInclude: Set<String>,
    ): ApiPrincipal =
        ApiPrincipal(
            tokenId = tokenId,
            scope = ApiScope(rootIds = rootIds, tagsInclude = tagsInclude),
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

    private fun authConfigWith(
        token: String,
        principal: ApiPrincipal,
    ): ApiAuthConfig.Bearer {
        val base = makeWriteAuthConfig()
        return ApiAuthConfig.Bearer(tokens = base.tokens + (HashBytes(sha256(token)) to principal))
    }

    // ─────────────────────────────────────────────────────────────────────
    // S1 — happy (guard): unscoped principal creates a root; unaffected by the fix
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 unscoped POST items creates root with depth 0 and self rootId`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S1 Root"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status, "Unscoped root create must succeed: ${response.bodyAsText()}")
            val id = parseId(response.bodyAsText())
            val persisted = runBlocking { repo.workItemRepository().getById(id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(0, persisted.data.depth, "A parentless create must be depth 0")
            assertEquals(id, persisted.data.rootId, "A root's rootId must be its own id")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S2 — happy (guard): rootIds scope, POST with an in-scope parentId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 rootIds scoped POST items with in-scope parentId succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "S2 Root", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S2 Child","parentId":"${root.id}"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status, "In-scope parented create must succeed: ${response.bodyAsText()}")
            val id = parseId(response.bodyAsText())
            val persisted = runBlocking { repo.workItemRepository().getById(id) }
            assertEquals(root.id, (persisted as Result.Success).data.parentId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S3 — happy: tag-only scope, new root's own tags satisfy the allowlist
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 tag-only scoped POST items creating a root with an allowed tag succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val tagToken = "integration-write-token-s3"
            val principal = writeScopedPrincipal("test-write-s3", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(tagToken, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $tagToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S3 Root","tags":["alpha"]}""")
                }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "Root create with an allowed tag must succeed: ${response.bodyAsText()}",
            )
            val id = parseId(response.bodyAsText())
            val persisted = runBlocking { repo.workItemRepository().getById(id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(0, persisted.data.depth)
            assertTrue(persisted.data.tagList().contains("alpha"), "Created root must carry the alpha tag: ${persisted.data.tags}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S4 — happy: rootIds={X}; X sits under an out-of-scope ancestor P; move-to-root succeeds
    // because X's own id, not P's, is the scope member (an item is its own anchor)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 reparent to root succeeds when the item's own id is the rootIds scope member`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x =
                runBlocking {
                    val p0 = repo.workItemRepository().create(WorkItem(title = "S4 Out-of-scope P", depth = 0)).getOrNull()!!
                    val p = repo.workItemRepository().update(p0.copy(rootId = p0.id)).getOrNull()!!
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "S4 X", parentId = p.id, depth = 1, rootId = p.id))
                        .getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(x.id))) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "Move-to-root must succeed when the item's own id is in rootIds: ${response.bodyAsText()}",
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(null, persisted.data.parentId)
            assertEquals(0, persisted.data.depth)
            assertEquals(x.id, persisted.data.rootId)

            val getResponse =
                client.get("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, getResponse.status, "The moved item must still be readable: ${getResponse.bodyAsText()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S5 — happy: tag-only scope, X carries the allowed tag; move-to-root succeeds
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 tag-only scoped reparent to root succeeds when the item's own tags are allowed`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x =
                runBlocking {
                    val p0 = repo.workItemRepository().create(WorkItem(title = "S5 P", depth = 0, tags = "alpha")).getOrNull()!!
                    val p = repo.workItemRepository().update(p0.copy(rootId = p0.id)).getOrNull()!!
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "S5 X", parentId = p.id, depth = 1, rootId = p.id, tags = "alpha"))
                        .getOrNull()!!
                }
            val tagToken = "integration-write-token-s5"
            val principal = writeScopedPrincipal("test-write-s5", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(tagToken, principal)) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $tagToken")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "Move-to-root must succeed for an allowed-tag item: ${response.bodyAsText()}",
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(null, persisted.data.parentId)
            assertEquals(0, persisted.data.depth)
            assertEquals(x.id, persisted.data.rootId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S6 — REGRESSION (red pre-fix): rootIds scope, parentId absent -> 403, not persisted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 rootIds scoped POST items with parentId absent is rejected 403 scope_forbidden`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "S6 Root Anchor", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S6 Should Not Exist"}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "A scoped principal must not create an unreachable root: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (all as Result.Success).data.items.filter { it.title == "S6 Should Not Exist" }
            assertTrue(matching.isEmpty(), "Rejected root create must not persist anything")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S7 — REGRESSION (red pre-fix): rootIds scope, explicit parentId:null -> 403, not persisted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 rootIds scoped POST items with explicit parentId null is rejected 403 scope_forbidden`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "S7 Root Anchor", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S7 Should Not Exist","parentId":null}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Explicit parentId:null must be rejected the same as absent: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (all as Result.Success).data.items.filter { it.title == "S7 Should Not Exist" }
            assertTrue(matching.isEmpty(), "Rejected root create must not persist anything")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S8 — REGRESSION (red pre-fix): mixed rootIds+tagsInclude scope, root create rejected
    // regardless of a matching tag — D1's rootIds check applies to root creation independent
    // of tag scope
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 rootIds plus tagsInclude scoped POST items creating a root is rejected 403 despite an allowed tag`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "S8 Root Anchor", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            val token = "integration-write-token-s8"
            val principal = writeScopedPrincipal("test-write-s8", rootIds = setOf(root.id), tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S8 Should Not Exist","tags":["alpha"]}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Mixed scope must not exempt root creation: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (all as Result.Success).data.items.filter { it.title == "S8 Should Not Exist" }
            assertTrue(matching.isEmpty(), "Rejected root create must not persist anything")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S9 — REGRESSION (red pre-fix): tag-only scope, new root's own tags do NOT satisfy the
    // allowlist (a disallowed tag, or no tags at all) -> 403, not persisted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S9a tag-only scoped POST items creating a root with a disallowed tag is rejected 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-s9a"
            val principal = writeScopedPrincipal("test-write-s9a", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S9a Should Not Exist","tags":["beta"]}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "A disallowed-tag root must be rejected: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(
                (all as Result.Success).data.items.none { it.title == "S9a Should Not Exist" },
                "Rejected root create must not persist anything",
            )
        }

    @Test
    fun `S9b tag-only scoped POST items creating a root with no tags is rejected 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-s9b"
            val principal = writeScopedPrincipal("test-write-s9b", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S9b Should Not Exist"}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "An untagged root under tag-only scope must be rejected: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(
                (all as Result.Success).data.items.none { it.title == "S9b Should Not Exist" },
                "Rejected root create must not persist anything",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // S10 — REGRESSION (red pre-fix): rootIds scope, PATCH move-to-root rejected; X and its
    // descendant D must be left completely untouched (no partial write, no cascade)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 rootIds scoped move-to-root PATCH is rejected 403 leaving X and its descendant untouched`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, d) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "S10 Root", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "S10 X", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    val dItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "S10 D", parentId = xItem.id, depth = 2, rootId = r.id))
                            .getOrNull()!!
                    Triple(r, xItem, dItem)
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null,"title":"S10 Modified"}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Move-to-root out of rootIds scope must be rejected: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"), "Should report scope_forbidden: ${response.bodyAsText()}")

            val persistedX = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persistedX)
            assertEquals(root.id, persistedX.data.parentId, "X's parentId must be unchanged")
            assertEquals(1, persistedX.data.depth, "X's depth must be unchanged")
            assertEquals(root.id, persistedX.data.rootId, "X's rootId must be unchanged")
            assertEquals("S10 X", persistedX.data.title, "X's title must be unchanged (rejected patch touches nothing)")
            assertEquals(x.modifiedAt, persistedX.data.modifiedAt, "A rejected PATCH must not touch modifiedAt")

            val persistedD = runBlocking { repo.workItemRepository().getById(d.id) }
            assertIs<Result.Success<WorkItem>>(persistedD)
            assertEquals(2, persistedD.data.depth, "Descendant depth must not cascade when the reparent is rejected")
            assertEquals(root.id, persistedD.data.rootId, "Descendant rootId must not cascade when the reparent is rejected")
        }

    // ─────────────────────────────────────────────────────────────────────
    // P1 — probe: empty / blank-only tags on a tag-only scoped root create -> 403 [D2]
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P1a tag-only scoped POST items with empty tags list is rejected 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-p1a"
            val principal = writeScopedPrincipal("test-write-p1a", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P1a Should Not Exist","tags":[]}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "An empty-tags root under tag-only scope must be rejected: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
        }

    @Test
    fun `P1b tag-only scoped POST items with blank-only tags is rejected 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-p1b"
            val principal = writeScopedPrincipal("test-write-p1b", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P1b Should Not Exist","tags":["", " "]}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Blank-only tags must not satisfy the allowlist: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
        }

    // ─────────────────────────────────────────────────────────────────────
    // P2 — probe: case-sensitive exact tag matching [O1 exact]. See file KDoc arbitration note:
    // the uppercase side is placed on tagsInclude (unconstrained by WorkItem.validateTags), the
    // item's own tag is a validly-lowercase "alpha".
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P2 tagsInclude case mismatch against a validly-lowercase item tag is rejected 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-p2"
            val principal = writeScopedPrincipal("test-write-p2", rootIds = null, tagsInclude = setOf("ALPHA"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P2 Should Not Exist","tags":["alpha"]}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "Case-mismatched tag scope must not authorize root creation: ${response.bodyAsText()}",
            )
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
        }

    // ─────────────────────────────────────────────────────────────────────
    // P3 — probe: whitespace around an otherwise-matching tag is trimmed before comparison [O1]
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P3 tag-only scoped POST items with a whitespace-padded matching tag succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-p3"
            val principal = writeScopedPrincipal("test-write-p3", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P3 Root","tags":[" alpha "]}""")
                }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "A whitespace-padded matching tag must be trimmed before comparison: ${response.bodyAsText()}",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // P4 — probe: order/duplicates -- a matching tag anywhere in the list satisfies the allowlist
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P4 tag-only scoped POST items with the allowed tag not first in the list succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val token = "integration-write-token-p4"
            val principal = writeScopedPrincipal("test-write-p4", rootIds = null, tagsInclude = setOf("alpha"))
            application { configureWriteTestApp(repo, authConfig = authConfigWith(token, principal)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P4 Root","tags":["beta","alpha"]}""")
                }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "Any matching tag in the list must satisfy the allowlist: ${response.bodyAsText()}",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // P5 — probe: Idempotency-Key replay of a scope-rejected write replays the same 403, with
    // zero additional writes [§5]
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P5a Idempotency-Key replay of a rejected root create replays 403 with zero writes`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "P5a Root Anchor", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val idempotencyKey = UUID.randomUUID().toString()
            val makeRequest: suspend () -> HttpResponse = {
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"P5a Should Not Exist"}""")
                }
            }

            val first = makeRequest()
            val second = makeRequest()

            assertEquals(HttpStatusCode.Forbidden, first.status, "First rejected create must be 403: ${first.bodyAsText()}")
            assertEquals(
                HttpStatusCode.Forbidden,
                second.status,
                "Replayed rejected create must still be 403: ${second.bodyAsText()}",
            )

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(
                (all as Result.Success).data.items.none { it.title == "P5a Should Not Exist" },
                "Neither the original nor the replayed request may have persisted anything",
            )
        }

    @Test
    fun `P5b Idempotency-Key replay of a rejected move-to-root PATCH replays 403 leaving X untouched`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x) =
                runBlocking {
                    val r0 = repo.workItemRepository().create(WorkItem(title = "P5b Root", depth = 0)).getOrNull()!!
                    val r = repo.workItemRepository().update(r0.copy(rootId = r0.id)).getOrNull()!!
                    val xItem =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "P5b X", parentId = r.id, depth = 1, rootId = r.id))
                            .getOrNull()!!
                    Pair(r, xItem)
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(root.id))) }

            val idempotencyKey = UUID.randomUUID().toString()
            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val makeRequest: suspend () -> HttpResponse = {
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }
            }

            val first = makeRequest()
            val second = makeRequest()

            assertEquals(HttpStatusCode.Forbidden, first.status, "First rejected move-to-root must be 403: ${first.bodyAsText()}")
            assertEquals(
                HttpStatusCode.Forbidden,
                second.status,
                "Replayed rejected move-to-root must still be 403: ${second.bodyAsText()}",
            )

            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals(
                root.id,
                (persisted as Result.Success).data.parentId,
                "X must never have been moved across both replayed attempts",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // P6 — probe: no-op move-to-root when the item is already a root and its own id is the
    // rootIds scope member -- D3's predicate is satisfied trivially, not skipped
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `P6 rootIds scoped PATCH re-affirming an already-root item as parentId null succeeds`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x =
                runBlocking {
                    val x0 = repo.workItemRepository().create(WorkItem(title = "P6 Root", depth = 0)).getOrNull()!!
                    repo.workItemRepository().update(x0.copy(rootId = x0.id)).getOrNull()!!
                }
            application { configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(x.id))) }

            val etag = "\"v1-${x.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null,"title":"P6 Updated"}""")
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A no-op move-to-root for an already-root, in-scope item must succeed: ${response.bodyAsText()}",
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(null, persisted.data.parentId)
            assertEquals(0, persisted.data.depth)
            assertEquals(x.id, persisted.data.rootId)
            assertEquals("P6 Updated", persisted.data.title)
        }
}

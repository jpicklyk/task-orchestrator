package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent scope-filtering coverage for `GET /items/{id}/backlinks` and
 * `GET /items/{id}/dependencies`, authored for item `72911c9f-c484-49e9-bc9f-0458b687ee2e`
 * ("GET /items/{id}/backlinks discloses fromTitle of out-of-scope items").
 *
 * Authored per the item's frozen `test-plan` note (oracles O1-O4):
 * - O1 [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope] KDoc -- `rootIds`
 *   walks the ancestor chain; `tagsInclude` is item-level exact CSV membership with no ancestor
 *   walk; empty/null on either field means no constraint from that field.
 * - O2 the new `allowedItemIdsForScope` helper's KDoc
 *   ([io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.AuthorizationPlugin]) -- the
 *   predicate every read route must apply to counterpart items not already pushed through
 *   `enforceScopeForItem`; fails CLOSED.
 * - O3 `current/docs/api-rest.md` §3 -- a collection endpoint never turns a scope mismatch into
 *   403; it returns 200 with the offending row dropped. 403 is reserved for the route's own
 *   directly-named subject item.
 * - O4 `DependencyWriteRoutes.kt` (~204-216) KDoc -- both endpoints of a dependency edge are
 *   scope-relevant, not only the `toItemId` side.
 *
 * Per `test-plan`'s PUBLIC SIGNATURE note, `allowedItemIdsForScope(principal, itemIds, repo)` is
 * never called directly by these tests -- coverage is exclusively through the HTTP surface,
 * exactly like the existing `DependencyRoutesTest` and wave-1's `TagScopeReadRoutesTest`.
 *
 * Two `test-plan` probes are deliberately not asserted here:
 * - P2 (mixed-case tag matching, e.g. `"Alpha"` vs `"alpha"`) is escalate-only per the wave-1
 *   `S12` precedent -- see the `test-manifest` note for the arbitration record.
 * - P4 (boundary zero-backlinks) has no dedicated test -- it is subsumed by `S9` below.
 * - P5 (path-encoding probes: alternate separators, encoded/UNC forms, replay) is N/A -- the
 *   only path parameter on either route is a UUID-guarded item id.
 */
class DependencyScopeReadRoutesTest {
    // ─── S1 + S2: backlinks, tagsInclude={alpha} keeps the alpha row, drops the beta row ────

    @Test
    fun `S1 S2 backlinks with tagsInclude alpha exposes only the alpha counterpart and nothing else`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB, alphaC) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS1", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBS1", tags = "beta", depth = 0))
                            .getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaCS1", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = c.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Triple(a, b, c)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            // S1: the alpha counterpart is fully present; the beta counterpart's id AND title
            // are both absent -- redacting only the title while leaving the id on the wire would
            // still be a disclosure.
            assertTrue(body.contains(alphaC.id.toString()), "Expected in-scope alpha counterpart id: $body")
            assertTrue(body.contains("AlphaCS1"), "Expected in-scope alpha counterpart title: $body")
            assertFalse(body.contains(betaB.id.toString()), "Out-of-scope beta counterpart id must not leak: $body")
            assertFalse(body.contains("BetaBS1"), "Out-of-scope beta counterpart title must not leak: $body")

            // S2: exactly one element -- the wrong row must be structurally absent from the
            // array, not merely have its title blanked out while a placeholder entry remains.
            val array = Json.parseToJsonElement(body).jsonArray
            assertEquals(1, array.size, "Expected exactly one backlink row (the alpha counterpart only): $body")
        }

    // ─── S3: backlinks, rootIds={R1} only -- backlink from a different root is dropped ─────

    @Test
    fun `S3 backlinks with rootIds only drops a backlink from a different root`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (rootR1, subjectA, otherRootB) =
                runBlocking {
                    val r1 = repo.workItemRepository().create(WorkItem(title = "RootR1S3", depth = 0)).getOrNull()!!
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS3", parentId = r1.id, depth = 1))
                            .getOrNull()!!
                    val b =
                        repo.workItemRepository().create(WorkItem(title = "OtherRootBS3", depth = 0)).getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Triple(r1, a, b)
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(rootR1.id))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains(otherRootB.id.toString()), "Backlink from a different root must not leak its id: $body")
            assertFalse(body.contains("OtherRootBS3"), "Backlink from a different root must not leak its title: $body")
            val array = Json.parseToJsonElement(body).jsonArray
            assertEquals(0, array.size, "Expected an empty backlinks array, not a partially-filtered one: $body")
        }

    // ─── S4: rootIds AND tagsInclude combined ───────────────────────────────────────────────

    @Test
    fun `S4 backlinks with rootIds and tagsInclude combined keeps only the doubly-in-scope counterpart`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (rootR1, subjectA, inScopeD, wrongTagE, wrongRootF) =
                runBlocking {
                    val r1 = repo.workItemRepository().create(WorkItem(title = "RootR1S4", depth = 0)).getOrNull()!!
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS4", tags = "alpha", parentId = r1.id, depth = 1))
                            .getOrNull()!!
                    val d =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "InScopeDS4", tags = "alpha", parentId = r1.id, depth = 1))
                            .getOrNull()!!
                    val e =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "WrongTagES4", tags = "beta", parentId = r1.id, depth = 1))
                            .getOrNull()!!
                    val f =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "WrongRootFS4", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = d.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = e.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = f.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    listOf(r1, a, d, e, f)
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(rootR1.id), tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(inScopeD.id.toString()), "Item satisfying both root and tag scope must be visible: $body")
            assertTrue(body.contains("InScopeDS4"), "Item satisfying both root and tag scope must be visible: $body")
            assertFalse(body.contains(wrongTagE.id.toString()), "Item in scope root but wrong tag must be dropped: $body")
            assertFalse(body.contains("WrongTagES4"), "Item in scope root but wrong tag must be dropped: $body")
            assertFalse(body.contains(wrongRootF.id.toString()), "Item with right tag but outside scope root must be dropped: $body")
            assertFalse(body.contains("WrongRootFS4"), "Item with right tag but outside scope root must be dropped: $body")
        }

    // ─── S5: regression -- unscoped token unaffected on backlinks ──────────────────────────

    @Test
    fun `S5 regression - unscoped token still sees both backlink rows with full titles`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB, alphaC) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS5", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBS5", tags = "beta", depth = 0))
                            .getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaCS5", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = c.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Triple(a, b, c)
                }
            // Default makeTestAuthConfig(): rootIds = null, tagsInclude = emptySet() -- unscoped.
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(betaB.id.toString()) && body.contains("BetaBS5"),
                "Unscoped token must still see the beta row's id and title: $body",
            )
            assertTrue(
                body.contains(alphaC.id.toString()) && body.contains("AlphaCS5"),
                "Unscoped token must still see the alpha row's id and title: $body",
            )
            val array = Json.parseToJsonElement(body).jsonArray
            assertEquals(2, array.size, "Unscoped token must see both backlink rows, exactly as pre-fix: $body")
        }

    // ─── S6: dependencies, blockedBy bucket -- alpha blocker kept, beta blocker dropped ─────

    @Test
    fun `S6 dependencies blockedBy bucket surfaces the alpha blocker and drops the beta blocker from every bucket`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB, alphaC) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS6", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBS6", tags = "beta", depth = 0))
                            .getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaCS6", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    // Both B and C block A -- A's blockedBy bucket is the bucket under test.
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = c.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Triple(a, b, c)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/dependencies") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val blocksStr = json["blocks"]!!.jsonArray.toString()
            val blockedByStr = json["blockedBy"]!!.jsonArray.toString()
            val relatedStr = json["related"]!!.jsonArray.toString()
            assertTrue(
                blockedByStr.contains(alphaC.id.toString()),
                "Expected the alpha blocker positively present inside blockedBy: $blockedByStr",
            )
            assertFalse(blockedByStr.contains(betaB.id.toString()), "Beta blocker's id must be absent from blockedBy: $blockedByStr")
            assertFalse(blocksStr.contains(betaB.id.toString()), "Beta blocker's id must be absent from blocks: $blocksStr")
            assertFalse(relatedStr.contains(betaB.id.toString()), "Beta blocker's id must be absent from related: $relatedStr")
        }

    // ─── S7: regression -- unscoped token unaffected on dependencies ───────────────────────

    @Test
    fun `S7 regression - unscoped token still sees both blockers in blockedBy unchanged`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB, alphaC) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS7", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBS7", tags = "beta", depth = 0))
                            .getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaCS7", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = c.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Triple(a, b, c)
                }
            application {
                configureTestApp { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/dependencies") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val blockedByArray = json["blockedBy"]!!.jsonArray
            val blockedByStr = blockedByArray.toString()
            assertTrue(blockedByStr.contains(betaB.id.toString()), "Unscoped token must still see the beta blocker: $blockedByStr")
            assertTrue(blockedByStr.contains(alphaC.id.toString()), "Unscoped token must still see the alpha blocker: $blockedByStr")
            assertEquals(2, blockedByArray.size, "blockedBy bucket must be unchanged for an unscoped token: $blockedByStr")
        }

    // ─── S8: subject itself out of scope -- both routes 403 scope_forbidden ────────────────

    @Test
    fun `S8 subject itself out of scope returns scope_forbidden 403 on both routes`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val subjectA =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "SubjectAS8", tags = "beta", depth = 0))
                        .getOrNull()!!
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val backlinksResponse =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, backlinksResponse.status)
            assertTrue(
                backlinksResponse.bodyAsText().contains("scope_forbidden"),
                "Expected scope_forbidden on backlinks: ${backlinksResponse.bodyAsText()}",
            )

            val dependenciesResponse =
                client.get("/api/v1/items/${subjectA.id}/dependencies") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, dependenciesResponse.status)
            assertTrue(
                dependenciesResponse.bodyAsText().contains("scope_forbidden"),
                "Expected scope_forbidden on dependencies: ${dependenciesResponse.bodyAsText()}",
            )
        }

    // ─── S9: every backlink out of scope -- 200 empty array, not 403/500 ───────────────────

    @Test
    fun `S9 backlinks with every counterpart out of scope returns 200 empty array not 403 or 500`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAS9", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBS9", tags = "beta", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    Pair(a, b)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains(betaB.id.toString()), "Sole backlink is out of scope, must not leak: $body")
            val array = Json.parseToJsonElement(body).jsonArray
            assertEquals(0, array.size, "Expected a 200 with an empty array, not 403/500: $body")
        }

    // ─── P1: null / empty tags parse to the empty set -- dropped under tag scope ───────────

    @Test
    fun `P1 backlinks drops counterparts with null or empty tags under tagsInclude scope`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, nullTagsG, emptyTagsH, alphaControlI) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAP1", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val g = repo.workItemRepository().create(WorkItem(title = "NullTagsGP1", depth = 0)).getOrNull()!!
                    val h =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "EmptyTagsHP1", tags = "", depth = 0))
                            .getOrNull()!!
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaControlIP1", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = g.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = h.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = i.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    listOf(a, g, h, i)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/backlinks") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(alphaControlI.id.toString()), "Expected the alpha control to remain visible: $body")
            assertFalse(body.contains(nullTagsG.id.toString()), "Null-tags counterpart (parses to empty set) must be dropped: $body")
            assertFalse(
                body.contains(emptyTagsH.id.toString()),
                "Empty-string-tags counterpart (parses to empty set) must be dropped: $body"
            )
            val array = Json.parseToJsonElement(body).jsonArray
            assertEquals(1, array.size, "Only the alpha control should remain: $body")
        }

    // ─── P3: two edges of different types between the same pair -- filtered consistently ──

    @Test
    fun `P3 dependencies filters an out-of-scope counterpart consistently across edge types`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (subjectA, betaB) =
                runBlocking {
                    val a =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "SubjectAP3", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "BetaBP3", tags = "beta", depth = 0))
                            .getOrNull()!!
                    // Two edges of different types between the same pair, in both directions.
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = b.id, toItemId = a.id, type = DependencyType.BLOCKS))
                    repo
                        .dependencyRepository()
                        .create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.RELATES_TO))
                    Pair(a, b)
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { dependencyRoutes(repo) }
            }
            val response =
                client.get("/api/v1/items/${subjectA.id}/dependencies") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            assertFalse(
                json["blockedBy"]!!.jsonArray.toString().contains(betaB.id.toString()),
                "BLOCKS edge from the beta counterpart must be dropped from blockedBy: $body",
            )
            assertFalse(
                json["related"]!!.jsonArray.toString().contains(betaB.id.toString()),
                "RELATES_TO edge to the beta counterpart must be dropped from related: $body",
            )
            assertFalse(
                json["blocks"]!!.jsonArray.toString().contains(betaB.id.toString()),
                "Beta counterpart must not appear in blocks either: $body",
            )
        }
}

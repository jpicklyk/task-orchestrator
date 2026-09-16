package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.MAX_PAGE
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
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `c471607b-003d-41a6-9cb7-24fc0591278e`
 * ("GET /transitions page parameter is uncapped, can overflow Int offset"), scenarios
 * S1-S12, S15-S17 of the frozen `test-plan` note (queue phase). S13-S14 (unit-level `PageParams`
 * / `buildPageDto` overflow checks) live in `PageParamsOverflowTest.kt`.
 *
 * Oracles: `api-rest.md` §6 error table (`validation_error` | 400 | "Invalid field value") and
 * §7 Pagination; `openapi.yaml:53-63`; the frozen `diagnosis` note's Decision block; the
 * `test-plan` note's exact scenario wording. Every 400 asserted below is a 200 pre-fix.
 *
 * All scenarios are EXISTING-SURFACE except S7, which is NEW-SURFACE (references the new
 * `MAX_PAGE` constant): per `test-plan`, a narrowest revert that keeps `const val MAX_PAGE` but
 * reverts only the validation branch still gives behavioural red for the `MAX_PAGE + 1` case.
 *
 * BLINDNESS: authored from the item's `diagnosis` and `test-plan` notes (both queue-phase,
 * frozen before implementation, explicitly permitted by the `test-author` skill's blindness
 * rule), the verbatim `PageParams`/`PageParamsResult`/`parsePageParams`/`pageParamsOrRespond`/
 * `TRANSITION_SCAN_LIMIT`/route declarations supplied by the orchestrator (bugwave4-2026-09
 * dispatch contract, "DECLARATIONS for c471607b"), a supplementary declaration for
 * `RoleTransitionRepository.findSince` supplied by the orchestrator on request (see
 * `test-manifest` for the exact text), and existing test conventions in this package
 * (`ApiTestHelper.kt`, `TransitionRoutesTest.kt`, `TagScopeReadRoutesTest.kt`,
 * `PatchReparentCycleGuardTest.kt`'s scripted-repository-override pattern, `ItemRoutesTest.kt`'s
 * JSON-body pagination assertions). No `src/main` file was opened to author this suite.
 */
class PaginationBoundsTest {
    /**
     * Wraps a real [RoleTransitionRepository], substituting a scripted result for `findSince`
     * that captures the `limit` argument it was called with instead of delegating — this keeps
     * S15 fast and independent of how many rows actually exist, and observable even if a
     * pre-fix unbounded limit would otherwise ask H2 for hundreds of millions of rows. Mirrors
     * [PatchReparentCycleGuardTest]'s `ScriptedWorkItemRepository` / `*OverrideProvider` pattern.
     */
    private class ScriptedRoleTransitionRepository(
        private val delegate: RoleTransitionRepository,
    ) : RoleTransitionRepository by delegate {
        var capturedLimit: Int? = null
            private set

        override suspend fun findSince(
            since: Instant,
            limit: Int,
        ): Result<List<RoleTransition>> {
            capturedLimit = limit
            return Result.Success(emptyList())
        }
    }

    /**
     * [RepositoryProvider] delegate that substitutes [roleTransitionRepo] for
     * `roleTransitionRepository()` while forwarding every other accessor to [delegate] unchanged.
     */
    private class RoleTransitionRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val roleTransitionRepo: RoleTransitionRepository,
    ) : RepositoryProvider by delegate {
        override fun roleTransitionRepository(): RoleTransitionRepository = roleTransitionRepo
    }

    private fun exactValidationError(
        body: String,
        expectedMessage: String,
    ) {
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "error code: $json")
        assertEquals(expectedMessage, json["message"]?.jsonPrimitive?.content, "error message: $json")
    }

    private val pageMessage = "page must be an integer between 1 and 100000"
    private val pageSizeMessage = "pageSize must be a positive integer"

    // ─── S1: no params -> defaults ─────────────────────────────────────────────

    @Test
    fun `S1 GET transitions with no params returns 200 with page 1 and pageSize 50`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, json["page"]!!.jsonPrimitive.int)
            assertEquals(50, json["pageSize"]!!.jsonPrimitive.int)
        }

    // ─── S2: page 2 of 5, pageSize 2 -> items 3-4, hasMore true ────────────────

    @Test
    fun `S2 GET transitions page 2 pageSize 2 of 5 returns items 3 and 4 with hasMore true`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val items =
                runBlocking {
                    (0 until 5).map { idx ->
                        val item = repo.workItemRepository().create(WorkItem(title = "S2 item $idx", depth = 0)).getOrNull()!!
                        repo.roleTransitionRepository().create(
                            RoleTransition(itemId = item.id, fromRole = "queue", toRole = "work", trigger = "start"),
                        )
                        item
                    }
                }
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=2&pageSize=2") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, json["page"]!!.jsonPrimitive.int)
            assertEquals(2, json["pageSize"]!!.jsonPrimitive.int)
            assertTrue(json["hasMore"]!!.jsonPrimitive.boolean, "page 2 of 5 at pageSize 2 must have more: $json")
            val body = response.bodyAsText()
            assertTrue(body.contains(items[2].id.toString()), "Expected item 3 (index 2, findSince order) on page 2: $body")
            assertTrue(body.contains(items[3].id.toString()), "Expected item 4 (index 3, findSince order) on page 2: $body")
            assertFalse(body.contains(items[0].id.toString()), "Item 1 (index 0) belongs to page 1, not page 2: $body")
            assertFalse(body.contains(items[4].id.toString()), "Item 5 (index 4) belongs to page 3, not page 2: $body")
        }

    // ─── S3: headline repro -- page beyond Int range ───────────────────────────

    @Test
    fun `S3 GET transitions with page beyond Int range returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=99999999999") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "pre-fix this silently served page 1 with 200")
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    // ─── S4: page=0 ─────────────────────────────────────────────────────────────

    @Test
    fun `S4 GET transitions with page 0 returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=0") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    // ─── S5: page=-5 ────────────────────────────────────────────────────────────

    @Test
    fun `S5 GET transitions with page negative 5 returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=-5") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "pre-fix this silently clamped to page 1")
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    // ─── S6: page=abc ───────────────────────────────────────────────────────────

    @Test
    fun `S6 GET transitions with non-integer page returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=abc") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    // ─── S7 (NEW-SURFACE): MAX_PAGE boundary ────────────────────────────────────

    @Test
    fun `S7 GET transitions at page MAX_PAGE returns 200 empty and MAX_PAGE plus 1 returns 400`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }

            val atMax =
                client.get("/api/v1/transitions?page=$MAX_PAGE") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, atMax.status, "page == MAX_PAGE must still be accepted: ${atMax.bodyAsText()}")
            val atMaxJson = Json.parseToJsonElement(atMax.bodyAsText()).jsonObject
            assertEquals(0, atMaxJson["items"]!!.jsonArray.size, "no data exists this far out, so items must be empty")

            val overMax =
                client.get("/api/v1/transitions?page=${MAX_PAGE + 1}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, overMax.status, "page == MAX_PAGE + 1 must be rejected")
            exactValidationError(overMax.bodyAsText(), pageMessage)
        }

    // ─── S8: pageSize=0 / pageSize=-1 ───────────────────────────────────────────

    @Test
    fun `S8 GET transitions with pageSize 0 or negative returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }

            val zero =
                client.get("/api/v1/transitions?pageSize=0") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, zero.status)
            exactValidationError(zero.bodyAsText(), pageSizeMessage)

            val negative =
                client.get("/api/v1/transitions?pageSize=-1") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, negative.status)
            exactValidationError(negative.bodyAsText(), pageSizeMessage)
        }

    // ─── S9: pageSize=99999 -> non-regression silent cap at 200 ────────────────

    @Test
    fun `S9 GET transitions with pageSize 99999 is silently capped at 200`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?pageSize=99999") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status, "pageSize cap is a silent clamp, not a 400 — must not regress")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(200, json["pageSize"]!!.jsonPrimitive.int)
        }

    // ─── S10: pageSize=abc ───────────────────────────────────────────────────────

    @Test
    fun `S10 GET transitions with non-integer pageSize returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?pageSize=abc") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageSizeMessage)
        }

    // ─── S11: present-but-blank params -> defaults, not errors ──────────────────

    @Test
    fun `S11 GET transitions with blank page and pageSize returns 200 with defaults`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=&pageSize=") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status, "blank (present but empty) params must fall back to defaults, not 400")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, json["page"]!!.jsonPrimitive.int)
            assertEquals(50, json["pageSize"]!!.jsonPrimitive.int)
        }

    // ─── S12: valid Int page, but beyond MAX_PAGE -> 400, not 500 ───────────────

    @Test
    fun `S12 GET transitions with page Int MAX_VALUE returns 400 not 500`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/transitions?page=2147483647") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "a valid Int that still exceeds MAX_PAGE must be a clean 400, never a 500 from a wrapped offset",
            )
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    // ─── S15: bounded fetch -- findSince limit never scales with page*pageSize ─

    @Test
    fun `S15 page 100000 pageSize 200 bounds the findSince fetch limit`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val scripted = ScriptedRoleTransitionRepository(repo.roleTransitionRepository())
            application {
                configureTestApp { transitionRoutes(RoleTransitionRepoOverrideProvider(repo, scripted)) }
            }
            val response =
                client.get("/api/v1/transitions?page=100000&pageSize=200") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status, "a bounded fetch must still succeed: ${response.bodyAsText()}")
            val limit = scripted.capturedLimit
            assertTrue(limit != null && limit > 0, "findSince must be called with a positive limit, got $limit")
            // Pre-fix: limit = pageSize * page + 1 = 200 * 100000 + 1 = 20_000_001 (and overflow-prone
            // for larger page). The bounded fetch (TRANSITION_SCAN_LIMIT = 1000) must never approach that.
            assertTrue(
                limit!! <= 1000,
                "findSince limit must be bounded by TRANSITION_SCAN_LIMIT (1000), got $limit " +
                    "(pre-fix unbounded value would be ${200L * 100000 + 1})",
            )
        }

    // ─── S16: non-regression -- tags_include scope survives explicit valid paging ─

    @Test
    fun `S16 GET transitions with tagsInclude and valid pagination still excludes non-matching items`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (itemA, itemB) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "TransAlphaS16", tags = "alpha", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "TransBetaS16", tags = "beta", depth = 0)).getOrNull()!!
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = a.id, fromRole = "queue", toRole = "work", trigger = "start"),
                    )
                    repo.roleTransitionRepository().create(
                        RoleTransition(itemId = b.id, fromRole = "queue", toRole = "work", trigger = "start"),
                    )
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { transitionRoutes(repo) }
            }
            val response =
                client.get("/api/v1/transitions?page=1&pageSize=50") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(itemA.id.toString()), "Expected alpha item's transition (by itemId): $body")
            assertFalse(
                body.contains(itemB.id.toString()),
                "Beta item's transition must stay excluded even with explicit valid page params (wave-1 filter, must not regress): $body",
            )
        }

    // ─── S17: cross-route -- the shared helper rejects every consuming route ────

    @Test
    fun `S17a GET items with page 0 returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }
            val response =
                client.get("/api/v1/items?page=0") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    @Test
    fun `S17b GET items roots with page abc returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureTestApp { itemRoutes(repo) } }
            val response =
                client.get("/api/v1/items/roots?page=abc") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    @Test
    fun `S17c GET items id tree with page beyond Int range returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "S17c tree root", depth = 0)).getOrNull()!!
                }
            application { configureTestApp { itemRoutes(repo) } }
            val response =
                client.get("/api/v1/items/${item.id}/tree?page=99999999999") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    @Test
    fun `S17d GET items id children with page negative 1 returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "S17d children root", depth = 0)).getOrNull()!!
                }
            application { configureTestApp { itemRoutes(repo) } }
            val response =
                client.get("/api/v1/items/${item.id}/children?page=-1") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }

    @Test
    fun `S17e GET items id transitions with page 0 returns 400 validation_error`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "S17e transitions item", depth = 0)).getOrNull()!!
                }
            application { configureTestApp { transitionRoutes(repo) } }
            val response =
                client.get("/api/v1/items/${item.id}/transitions?page=0") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            exactValidationError(response.bodyAsText(), pageMessage)
        }
}

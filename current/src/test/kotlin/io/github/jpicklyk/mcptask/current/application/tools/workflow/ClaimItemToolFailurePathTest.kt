package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Test author: test-author:b53007e0 (independent, blind to src/main per test-author skill §4).
 *
 * Characterization-first wave: [`decompose-complexity-hotspots`] item `b53007e0`
 * ("Decompose ClaimItemTool.executeClaimRelease"). Written against the CURRENT
 * (pre-decomposition) public surface at base `dd26e9e2`, from the frozen `test-plan` note and
 * the dispatch's supplied declarations block — no file under `src/main` was opened to write
 * these assertions. All scenarios are EXISTING-SURFACE; the fix this wave performs is a pure
 * decomposition, not a new surface, so red-proof is a plain revert of the targeted #339-#343 fix
 * hunk, orchestrator-run.
 *
 * Scenario ids S8-S15 (plus the actor-validation and batch-composition probes) match the item's
 * `test-plan` note. This file covers every scenario the plan marks "M" (mocked repository):
 * selector-mode failure branches (unresolvable parentId, recommend/explainEmpty db_error,
 * queue_empty vs none_eligible, TOCTOU between recommend and claim), ID-mode claimRef echoing,
 * actor validation, and claim/release batch composition ordering.
 *
 * Uses [MockRepositoryProvider] — matching the idiom in [ClaimItemToolTest] — since these
 * scenarios stub repository/recommender responses directly rather than exercising real SQL.
 */
class ClaimItemToolFailurePathTest {
    private lateinit var tool: ClaimItemTool
    private lateinit var mockRepo: MockRepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository

    private val agentId = "agent-failure-path-1"
    private val itemId1 = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tool = ClaimItemTool()
        mockRepo = MockRepositoryProvider()
        workItemRepo = mockRepo.workItemRepo
    }

    // -----------------------------------------------------------------------
    // JSON-building / context helpers (match the conventions in ClaimItemToolTest.kt).
    // -----------------------------------------------------------------------

    private fun actorJson(id: String = agentId): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    private fun claimEntry(
        itemId: UUID,
        ttlSeconds: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            ttlSeconds?.let { put("ttlSeconds", it) }
        }

    private fun claimEntryWithRef(
        itemId: UUID,
        claimRef: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            put("claimRef", claimRef)
        }

    private fun releaseEntry(itemId: UUID): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
        }

    private fun selectorEntry(
        selectorFields: JsonObject = buildJsonObject {},
        claimRef: String? = null
    ): JsonObject =
        buildJsonObject {
            put("selector", selectorFields)
            claimRef?.let { put("claimRef", it) }
        }

    private fun params(
        claims: List<JsonObject> = emptyList(),
        releases: List<JsonObject> = emptyList(),
        actorId: String = agentId,
        requestId: String = UUID.randomUUID().toString()
    ): JsonObject =
        buildJsonObject {
            if (claims.isNotEmpty()) {
                put("claims", buildJsonArray { claims.forEach { add(it) } })
            }
            if (releases.isNotEmpty()) {
                put("releases", buildJsonArray { releases.forEach { add(it) } })
            }
            put("actor", actorJson(actorId))
            put("requestId", requestId)
        }

    private fun defaultContext(): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = mockRepo.provider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        )

    private fun context(nextItemRecommender: NextItemRecommender): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = mockRepo.provider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            nextItemRecommender = nextItemRecommender,
        )

    private fun firstResult(
        result: JsonElement,
        arrayKey: String
    ): JsonObject {
        val data = (result as JsonObject)["data"] as JsonObject
        return (data[arrayKey] as JsonArray)[0] as JsonObject
    }

    private fun makeSuccessItem(
        id: UUID = itemId1,
        claimedBy: String = agentId
    ): WorkItem {
        val now = Instant.now()
        return WorkItem(
            id = id,
            title = "Test Item",
            claimedBy = claimedBy,
            claimedAt = now,
            claimExpiresAt = now.plusSeconds(900),
            originalClaimedAt = now.minusSeconds(300),
        )
    }

    // -----------------------------------------------------------------------
    // S8 — selector.parentId hex-prefix resolves to no items: error, recommend never called.
    // -----------------------------------------------------------------------

    @Test
    fun `S8 selector unresolvable parentId prefix returns error without ever calling recommend`(): Unit =
        runBlocking {
            coEvery { workItemRepo.findByIdPrefix("abcd1234", any()) } returns Result.Success(emptyList())
            val recommender = mockk<NextItemRecommender>()

            val selectorFields = buildJsonObject { put("parentId", "abcd1234") }
            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(selectorFields, claimRef = "s8ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("outcome", "error", "claimRef"), first.keys.toList())
            assertEquals(
                "Failed to resolve selector.parentId: abcd1234",
                first["error"]!!.jsonPrimitive.content
            )
            assertEquals("s8ref", first["claimRef"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { recommender.recommend(any(), any()) }
        }

    // -----------------------------------------------------------------------
    // S9 — selector recommend() fails: db_error before any claim attempt.
    // -----------------------------------------------------------------------

    @Test
    fun `S9 selector recommend database error surfaces as db_error before any claim attempt`(): Unit =
        runBlocking {
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns
                Result.Error(RepositoryError.DatabaseError("simulated recommend failure"))

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s9ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("outcome", "kind", "code", "message", "claimRef"), first.keys.toList())
            assertEquals("db_error", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals("db_error", first["code"]!!.jsonPrimitive.content)
            assertEquals(
                "Database error during selector recommendation",
                first["message"]!!.jsonPrimitive.content
            )
            assertEquals("s9ref", first["claimRef"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    // -----------------------------------------------------------------------
    // S10 — selector recommend() returns empty, explainEmpty() fails: db_error, never
    // queue_empty/none_eligible.
    // -----------------------------------------------------------------------

    @Test
    fun `S10 selector explainEmpty database error surfaces as db_error, never queue_empty or none_eligible`(): Unit =
        runBlocking {
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(emptyList())
            coEvery { recommender.explainEmpty(any()) } returns
                Result.Error(RepositoryError.DatabaseError("simulated explainEmpty failure"))

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s10ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("outcome", "kind", "code", "message", "claimRef"), first.keys.toList())
            val outcome = first["outcome"]!!.jsonPrimitive.content
            assertEquals("db_error", outcome)
            assertFalse(
                outcome == "queue_empty" || outcome == "none_eligible",
                "an explainEmpty failure must never be reported as queue_empty or none_eligible"
            )
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals("db_error", first["code"]!!.jsonPrimitive.content)
            assertEquals(
                "Database error while explaining empty selector result",
                first["message"]!!.jsonPrimitive.content
            )
            assertEquals("s10ref", first["claimRef"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S11 — selector TOCTOU: recommend() matches an item but the subsequent claim() fails.
    // -----------------------------------------------------------------------

    @Test
    fun `S11a selector TOCTOU claim NotFound after a recommend match`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.NotFound(itemId1)

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s11ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("itemId", "outcome", "claimRef"), first.keys.toList())
            assertEquals("not_found", first["outcome"]!!.jsonPrimitive.content)
            assertEquals(itemId1.toString(), first["itemId"]!!.jsonPrimitive.content)
            assertEquals("s11ref", first["claimRef"]!!.jsonPrimitive.content)
            assertNull(first["selectorResolved"], "a TOCTOU failure must not claim selectorResolved:true")
        }

    @Test
    fun `S11b selector TOCTOU claim TerminalItem after a recommend match`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.TerminalItem(itemId1)

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s11ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("itemId", "outcome", "claimRef"), first.keys.toList())
            assertEquals("terminal_item", first["outcome"]!!.jsonPrimitive.content)
            assertEquals(itemId1.toString(), first["itemId"]!!.jsonPrimitive.content)
            assertEquals("s11ref", first["claimRef"]!!.jsonPrimitive.content)
            assertNull(first["selectorResolved"])
        }

    @Test
    fun `S11c selector TOCTOU claim DBError after a recommend match does not leak exception text`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            val cause = SQLException("toctou db failure — must not leak into the response")
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.DBError(itemId1, cause)

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s11ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "kind", "code", "message", "contendedItemId", "claimRef"),
                first.keys.toList()
            )
            assertEquals("db_error", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals("db_error", first["code"]!!.jsonPrimitive.content)
            assertEquals("Database error during claim operation", first["message"]!!.jsonPrimitive.content)
            assertEquals(itemId1.toString(), first["contendedItemId"]!!.jsonPrimitive.content)
            assertEquals("s11ref", first["claimRef"]!!.jsonPrimitive.content)
            assertNull(first["selectorResolved"])

            val serialized = result.toString()
            assertFalse("toctou db failure" in serialized, "the underlying exception message must not leak")
        }

    // -----------------------------------------------------------------------
    // S12 — ID-mode: claimRef is echoed as the LAST key on every claim failure outcome.
    // -----------------------------------------------------------------------

    @Test
    fun `S12 ID-mode claimRef is echoed as the last key for every claim failure outcome`(): Unit =
        runBlocking {
            val outcomes: List<Pair<ClaimResult, String>> =
                listOf(
                    ClaimResult.NotFound(itemId1) to "not_found",
                    ClaimResult.TerminalItem(itemId1) to "terminal_item",
                    ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 5000L) to "already_claimed",
                    ClaimResult.DBError(itemId1, SQLException("boom")) to "db_error"
                )

            outcomes.forEach { (claimResult, expectedOutcome) ->
                coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns claimResult

                val result =
                    tool.execute(
                        params(claims = listOf(claimEntryWithRef(itemId1, claimRef = "s12ref"))),
                        defaultContext()
                    )

                val first = firstResult(result, "claimResults")
                assertEquals(
                    expectedOutcome,
                    first["outcome"]!!.jsonPrimitive.content,
                    "outcome mismatch for $claimResult"
                )
                assertEquals(
                    "claimRef",
                    first.keys.last(),
                    "claimRef must be the last key in the entry for $claimResult"
                )
                assertEquals("s12ref", first["claimRef"]!!.jsonPrimitive.content)
            }
        }

    // -----------------------------------------------------------------------
    // S13 — selector none_eligible: exclusion counts, fixed retryAfterMs, claimRef.
    // -----------------------------------------------------------------------

    @Test
    fun `S13 none_eligible reports exclusion counts, the fixed retry-after and claimRef`(): Unit =
        runBlocking {
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(emptyList())
            coEvery { recommender.explainEmpty(any()) } returns
                Result.Success(NextItemRecommender.ExclusionCounts(claimed = 2, ancestorClaimed = 1, dependencyBlocked = 3))

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "s13ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("outcome", "kind", "code", "retryAfterMs", "excluded", "claimRef"),
                first.keys.toList()
            )
            assertEquals("none_eligible", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals("none_eligible", first["code"]!!.jsonPrimitive.content)
            assertEquals(
                30000L,
                first["retryAfterMs"]!!.jsonPrimitive.long
            )
            val excluded = first["excluded"] as JsonObject
            assertEquals(2, excluded["claimed"]!!.jsonPrimitive.int)
            assertEquals(1, excluded["ancestorClaimed"]!!.jsonPrimitive.int)
            assertEquals(3, excluded["dependencyBlocked"]!!.jsonPrimitive.int)
            assertEquals("s13ref", first["claimRef"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S14 — actor validation: absent actor, missing kind, unrecognized kind.
    // -----------------------------------------------------------------------

    @Test
    fun `S14a actor absent is rejected with VALIDATION_ERROR and the repository is untouched`(): Unit =
        runBlocking {
            val p =
                buildJsonObject {
                    put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                    put("requestId", UUID.randomUUID().toString())
                    // no actor
                }

            val result = tool.execute(p, defaultContext())

            val error = (result as JsonObject)["error"] as JsonObject
            assertEquals(false, result["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
            assertEquals("actor is required for claim_item", error["message"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    @Test
    fun `S14b actor without kind is rejected with VALIDATION_ERROR`(): Unit =
        runBlocking {
            val p =
                buildJsonObject {
                    put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                    put("actor", buildJsonObject { put("id", agentId) })
                    put("requestId", UUID.randomUUID().toString())
                }

            val result = tool.execute(p, defaultContext())

            val error = (result as JsonObject)["error"] as JsonObject
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
            assertEquals("actor.kind is required", error["message"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    @Test
    fun `S14c actor with an unrecognized kind is rejected and the invalid kind is named`(): Unit =
        runBlocking {
            val p =
                buildJsonObject {
                    put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", agentId)
                            put("kind", "robot")
                        }
                    )
                    put("requestId", UUID.randomUUID().toString())
                }

            val result = tool.execute(p, defaultContext())

            val error = (result as JsonObject)["error"] as JsonObject
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
            assertEquals("Invalid actor.kind: robot", error["message"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    // -----------------------------------------------------------------------
    // S15 — batch composition: claim success plus three releases with distinct outcomes,
    // ordering preserved, no summary block.
    // -----------------------------------------------------------------------

    @Test
    fun `S15 batch composition preserves claimResults and releaseResults ordering with no summary block`(): Unit =
        runBlocking {
            val idA = UUID.randomUUID()
            val idB = UUID.randomUUID()
            val idC = UUID.randomUUID()
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())
            coEvery { workItemRepo.release(idA, agentId) } returns
                ReleaseResult.Success(WorkItem(id = idA, title = "Released A"))
            coEvery { workItemRepo.release(idB, agentId) } returns ReleaseResult.NotClaimedByYou(idB)
            coEvery { workItemRepo.release(idC, agentId) } returns
                ReleaseResult.DBError(idC, SQLException("release c failure"))

            val result =
                tool.execute(
                    params(
                        claims = listOf(claimEntry(itemId1)),
                        releases = listOf(releaseEntry(idA), releaseEntry(idB), releaseEntry(idC))
                    ),
                    defaultContext()
                )

            val data = (result as JsonObject)["data"] as JsonObject
            assertEquals(listOf("claimResults", "releaseResults"), data.keys.toList())
            assertNull(data["summary"], "summary block must be omitted")

            val claimResults = data["claimResults"] as JsonArray
            assertEquals(1, claimResults.size)
            assertEquals("success", (claimResults[0] as JsonObject)["outcome"]!!.jsonPrimitive.content)

            val releaseResults = data["releaseResults"] as JsonArray
            assertEquals(3, releaseResults.size)
            assertEquals("success", (releaseResults[0] as JsonObject)["outcome"]!!.jsonPrimitive.content)
            assertEquals("not_claimed_by_you", (releaseResults[1] as JsonObject)["outcome"]!!.jsonPrimitive.content)
            assertEquals("db_error", (releaseResults[2] as JsonObject)["outcome"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // F1 — review follow-up: selector/ID success key ORDER (refactor guard).
    // -----------------------------------------------------------------------

    private fun freshClaimedItem(
        id: UUID = itemId1,
        claimedBy: String = agentId
    ): WorkItem {
        val now = Instant.now()
        return WorkItem(
            id = id,
            title = "F1 Fresh Claim",
            role = Role.QUEUE,
            claimedBy = claimedBy,
            claimedAt = now,
            claimExpiresAt = now.plusSeconds(900),
            // originalClaimedAt EQUALS claimedAt: this is a fresh (first) claim, so the response
            // must omit originalClaimedAt entirely (test-plan S3 heartbeat is the only case it's present).
            originalClaimedAt = now,
        )
    }

    @Test
    fun `F1 selector success key order places selectorResolved before claimRef and omits originalClaimedAt when equal`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(freshClaimedItem())

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "f1ref"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "selectorResolved", "claimRef", "claimedBy", "claimedAt", "claimExpiresAt"),
                first.keys.toList()
            )
            assertEquals("success", first["outcome"]!!.jsonPrimitive.content)
            assertNull(first["originalClaimedAt"], "originalClaimedAt must be omitted when equal to claimedAt")
        }

    @Test
    fun `F1 selector success without claimRef omits claimRef but keeps selectorResolved`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(freshClaimedItem())

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry())),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "selectorResolved", "claimedBy", "claimedAt", "claimExpiresAt"),
                first.keys.toList()
            )
        }

    @Test
    fun `F1 ID-mode success key order lacks selectorResolved`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(freshClaimedItem())

            val result =
                tool.execute(
                    params(claims = listOf(claimEntryWithRef(itemId1, claimRef = "f1cref"))),
                    defaultContext()
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "claimRef", "claimedBy", "claimedAt", "claimExpiresAt"),
                first.keys.toList()
            )
            assertNull(first["selectorResolved"], "ID-mode success must never carry selectorResolved")
        }

    // -----------------------------------------------------------------------
    // F2 — review follow-up: selector-path AlreadyClaimed exact shape.
    // -----------------------------------------------------------------------

    @Test
    fun `F2 selector-path AlreadyClaimed exact shape lacks selectorResolved and claimedBy`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Matched Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 12000L)

            val result =
                tool.execute(
                    params(claims = listOf(selectorEntry(claimRef = "r1"))),
                    context(recommender)
                )

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "kind", "contendedItemId", "retryAfterMs", "claimRef"),
                first.keys.toList()
            )
            assertEquals("already_claimed", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals(itemId1.toString(), first["contendedItemId"]!!.jsonPrimitive.content)
            assertEquals(12000L, first["retryAfterMs"]!!.jsonPrimitive.long)
            assertEquals("r1", first["claimRef"]!!.jsonPrimitive.content)
            assertNull(first["selectorResolved"], "an AlreadyClaimed outcome must never carry selectorResolved")
            assertFalse(
                result.toString().contains("claimedBy"),
                "an AlreadyClaimed outcome must never leak claimedBy anywhere in the response"
            )
        }
}

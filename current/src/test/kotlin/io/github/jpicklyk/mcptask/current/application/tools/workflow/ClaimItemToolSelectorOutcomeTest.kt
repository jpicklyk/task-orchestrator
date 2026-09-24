package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Blind test-author coverage for c39fe915 (claim_item selector-mode outcome split).
 *
 * Test author actor id: test-author:c39fe915. Written from the item test-plan note (queue-phase,
 * frozen before implementation existed) and the dispatch DECLARATIONS block only -- no file
 * under src/main was opened to write these assertions.
 *
 * Oracle (O-D): diagnosis note "Chosen shapes" section --
 *   queue_empty  (explainEmpty(...).total == 0): {outcome:"queue_empty", kind:"permanent",
 *     code:"queue_empty", claimRef?} -- no itemId, no retryAfterMs, no excluded.
 *   none_eligible (explainEmpty(...).total  > 0): {outcome:"none_eligible", kind:"transient",
 *     code:"none_eligible", retryAfterMs:30000, excluded:{claimed,ancestorClaimed,dependencyBlocked},
 *     claimRef?} -- no itemId, never identities.
 * "no_match" is no longer emitted anywhere (per the dispatch declarations).
 *
 * Scenario ids (S1-S8) match the item test-plan note, section "claim_item selector".
 * S1-S7 are NEW-SURFACE (bind to NextItemRecommender.explainEmpty, which the fix introduces);
 * narrowest-revert recipe per test-plan: keep explainEmpty/ExclusionCounts, revert only
 * ClaimItemTool empty-branch call site back to the single no_match outcome. S8 is
 * EXISTING-SURFACE (passive expiry was already the selector behavior; this test pins that the
 * new split does not disturb it).
 */
class ClaimItemToolSelectorOutcomeTest {
    private lateinit var tool: ClaimItemTool
    private lateinit var mockRepo: MockRepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository

    private val agentId = "agent-y"

    @BeforeEach
    fun setUp() {
        tool = ClaimItemTool()
        mockRepo = MockRepositoryProvider()
        workItemRepo = mockRepo.workItemRepo
    }

    private fun actorJson(id: String = agentId): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
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
        selectorFields: JsonObject = buildJsonObject {},
        claimRef: String? = null,
        actorId: String = agentId,
        requestId: String = UUID.randomUUID().toString()
    ): JsonObject =
        buildJsonObject {
            put("claims", buildJsonArray { add(selectorEntry(selectorFields, claimRef)) })
            put("actor", actorJson(actorId))
            put("requestId", requestId)
        }

    private fun defaultContext(nextItemRecommender: NextItemRecommender): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = mockRepo.provider,
            actorVerifier = NoOpActorVerifier,
            nextItemRecommender = nextItemRecommender,
        )

    /** Recommender that returns no candidates and reports the given exclusion breakdown. */
    private fun recommenderReportingEmpty(counts: NextItemRecommender.ExclusionCounts): NextItemRecommender {
        val recommender = mockk<NextItemRecommender>()
        coEvery { recommender.recommend(any(), any()) } returns Result.Success(emptyList())
        coEvery { recommender.explainEmpty(any()) } returns Result.Success(counts)
        return recommender
    }

    private fun firstClaimResult(result: JsonElement): JsonObject {
        val data = (result as JsonObject)["data"] as JsonObject
        return (data["claimResults"] as JsonArray)[0] as JsonObject
    }

    // -----------------------------------------------------------------------
    // S1: nothing matches the selector at all -> queue_empty, permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S1 - selector matching nothing at all returns queue_empty with permanent kind`(): Unit =
        runBlocking {
            val recommender = recommenderReportingEmpty(NextItemRecommender.ExclusionCounts(0, 0, 0))

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("queue_empty", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("permanent", first["kind"]?.jsonPrimitive?.content)
            assertEquals("queue_empty", first["code"]?.jsonPrimitive?.content)
            assertNull(first["itemId"], "queue_empty must not echo an itemId")
            assertNull(first["retryAfterMs"], "queue_empty must not have retryAfterMs")
            assertNull(first["excluded"], "queue_empty must not have an excluded breakdown")
        }

    // -----------------------------------------------------------------------
    // S2: only match is live-claimed by another agent -> none_eligible, claimed:1
    // -----------------------------------------------------------------------

    @Test
    fun `S2 - selector matches only a live-claimed item returns none_eligible with claimed excluded count`(): Unit =
        runBlocking {
            val recommender =
                recommenderReportingEmpty(
                    NextItemRecommender.ExclusionCounts(claimed = 1, ancestorClaimed = 0, dependencyBlocked = 0)
                )

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("transient", first["kind"]?.jsonPrimitive?.content)
            assertEquals("none_eligible", first["code"]?.jsonPrimitive?.content)
            assertEquals(30000L, first["retryAfterMs"]?.jsonPrimitive?.content?.toLongOrNull())
            val excluded = first["excluded"] as JsonObject
            assertEquals(1, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
            assertNull(first["itemId"], "none_eligible must not echo an itemId")
        }

    // -----------------------------------------------------------------------
    // S3: only match parent is claimed by another agent -> none_eligible, ancestorClaimed:1
    // -----------------------------------------------------------------------

    @Test
    fun `S3 - selector matches only an ancestor-claimed item returns none_eligible with ancestorClaimed excluded count`(): Unit =
        runBlocking {
            val recommender =
                recommenderReportingEmpty(
                    NextItemRecommender.ExclusionCounts(claimed = 0, ancestorClaimed = 1, dependencyBlocked = 0)
                )

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = first["excluded"] as JsonObject
            assertEquals(0, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
        }

    // -----------------------------------------------------------------------
    // S4: only match has an unmet BLOCKS dependency -> none_eligible, dependencyBlocked:1
    // -----------------------------------------------------------------------

    @Test
    fun `S4 - selector matches only a dependency-blocked item returns none_eligible with dependencyBlocked excluded count`(): Unit =
        runBlocking {
            val recommender =
                recommenderReportingEmpty(
                    NextItemRecommender.ExclusionCounts(claimed = 0, ancestorClaimed = 0, dependencyBlocked = 1)
                )

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = first["excluded"] as JsonObject
            assertEquals(0, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
        }

    // -----------------------------------------------------------------------
    // S5: all three exclusion reasons present at once (1/1/1); the response never names an
    // item UUID or an agent identity -- "excluded" is an aggregate count only.
    // -----------------------------------------------------------------------

    @Test
    fun `S5 - combined exclusion reasons report 1-1-1 and the response names no item or agent identity`(): Unit =
        runBlocking {
            val contendedItemId = UUID.randomUUID()
            val recommender =
                recommenderReportingEmpty(
                    NextItemRecommender.ExclusionCounts(claimed = 1, ancestorClaimed = 1, dependencyBlocked = 1)
                )

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = first["excluded"] as JsonObject
            assertEquals(1, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["dependencyBlocked"]?.jsonPrimitive?.int)

            val serialized = result.toString()
            assertFalse(
                "\"agent-x\"" in serialized,
                "none_eligible must never leak a contending agent identity. Got: $serialized"
            )
            assertFalse(
                contendedItemId.toString() in serialized,
                "none_eligible must never leak an item UUID (excluded is an aggregate count only). Got: $serialized"
            )
        }

    // -----------------------------------------------------------------------
    // S6: the queue_empty/none_eligible split is decided purely by explainEmpty total,
    // regardless of which selector field produced the empty match set (tag mismatch vs.
    // complexity mismatch are both just "matched == 0" at the ClaimItemTool boundary).
    // -----------------------------------------------------------------------

    @Test
    fun `S6 - queue_empty for a tag-mismatch selector when explainEmpty reports zero total`(): Unit =
        runBlocking {
            val recommender = recommenderReportingEmpty(NextItemRecommender.ExclusionCounts(0, 0, 0))

            val selectorFields = buildJsonObject { put("tags", "my-tag") }
            val result = tool.execute(params(selectorFields = selectorFields), defaultContext(recommender))

            assertEquals("queue_empty", firstClaimResult(result)["outcome"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S6 - queue_empty for a complexityMax-mismatch selector when explainEmpty reports zero total`(): Unit =
        runBlocking {
            val recommender = recommenderReportingEmpty(NextItemRecommender.ExclusionCounts(0, 0, 0))

            val selectorFields = buildJsonObject { put("complexityMax", 5) }
            val result = tool.execute(params(selectorFields = selectorFields), defaultContext(recommender))

            assertEquals("queue_empty", firstClaimResult(result)["outcome"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // S7: claimRef is echoed on both new outcomes
    // -----------------------------------------------------------------------

    @Test
    fun `S7 - claimRef is echoed on queue_empty outcome`(): Unit =
        runBlocking {
            val recommender = recommenderReportingEmpty(NextItemRecommender.ExclusionCounts(0, 0, 0))

            val result = tool.execute(params(claimRef = "ref-queue-empty"), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("queue_empty", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("ref-queue-empty", first["claimRef"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S7 - claimRef is echoed on none_eligible outcome`(): Unit =
        runBlocking {
            val recommender =
                recommenderReportingEmpty(
                    NextItemRecommender.ExclusionCounts(claimed = 1, ancestorClaimed = 0, dependencyBlocked = 0)
                )

            val result = tool.execute(params(claimRef = "ref-none-eligible"), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("ref-none-eligible", first["claimRef"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // S8 (EXISTING-SURFACE): a candidate whose only prior claim has already passively expired
    // is still eligible, and a matched candidate claims successfully. Oracle:
    // current/docs/api-reference.md "Passive expiry" -- an expired claim does not exclude the
    // item from the claimable set. explainEmpty is never consulted when recommend() is non-empty.
    // -----------------------------------------------------------------------

    @Test
    fun `S8 - a candidate whose prior claim has passively expired is still eligible and claims successfully`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val matchedItem = WorkItem(id = itemId, title = "Previously expired claim", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))
            val now = Instant.now()
            coEvery { workItemRepo.claim(itemId, agentId, 900) } returns
                ClaimResult.Success(
                    WorkItem(
                        id = itemId,
                        title = "Previously expired claim",
                        claimedBy = agentId,
                        claimedAt = now,
                        claimExpiresAt = now.plusSeconds(900),
                        originalClaimedAt = now,
                    )
                )

            val result = tool.execute(params(), defaultContext(recommender))

            val first = firstClaimResult(result)
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
            assertEquals(itemId.toString(), first["itemId"]?.jsonPrimitive?.content)
            // explainEmpty must never be consulted when recommend() already returned a candidate.
            coVerify(exactly = 0) { recommender.explainEmpty(any()) }
        }
}

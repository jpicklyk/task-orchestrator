package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Blind test-author coverage for c39fe915 (claim_item selector-mode outcome split).
 *
 * Test author actor id: test-author:c39fe915. Written from the item test-plan note (queue-phase,
 * frozen before implementation existed), the dispatch DECLARATIONS block, and current/docs/
 * api-reference.md (project documentation, permitted reading) -- no file under src/main was
 * opened to write these assertions.
 *
 * RE-DISPATCH (review finding B1): the original version of this file mocked
 * NextItemRecommender and stubbed explainEmpty's return value directly, so S2-S6 never drove
 * the REAL NextItemRecommender.explainEmpty or the REAL SQLite countSelectorMatches override --
 * a dropped filter (diagnosis Risk 1: countSelectorMatches drifting from findClaimable's
 * condition builder) could not have failed those tests. This version extends
 * SQLiteRepositoryTestBase (real in-memory SQLite, matching the idiom in the sibling
 * ClaimItemToolBatchCompositionTest.kt) and drives ClaimItemTool.execute(...) against real rows
 * for every S2-S6 scenario, so the real explainEmpty/countSelectorMatches implementation runs
 * end-to-end. Only S8 (passive expiry, EXISTING-SURFACE, already covered against real DB
 * semantics by SQLiteWorkItemRepositoryClaimTest) keeps its original mock-based recommender,
 * since it exercises the tool's already-non-empty-recommend() branch, not explainEmpty.
 *
 * Oracle (O-D): diagnosis note "Chosen shapes" section, corroborated by current/docs/
 * api-reference.md's "Claim outcome codes per item" table and its Atomic Find-and-Claim /
 * "Ancestor-claim filtering in selector mode" prose --
 *   queue_empty  (explainEmpty(...).total == 0): {outcome:"queue_empty", kind:"permanent",
 *     code:"queue_empty", claimRef?} -- no itemId, no retryAfterMs, no excluded.
 *   none_eligible (explainEmpty(...).total  > 0): {outcome:"none_eligible", kind:"transient",
 *     code:"none_eligible", retryAfterMs:30000, excluded:{claimed,ancestorClaimed,dependencyBlocked},
 *     claimRef?} -- no itemId, never identities.
 * "no_match" is no longer emitted anywhere (per the dispatch declarations).
 *
 * Scenario ids (S1-S8) match the item test-plan note, section "claim_item selector".
 * S1-S7 are NEW-SURFACE (bind to NextItemRecommender.explainEmpty and
 * WorkItemRepository.countSelectorMatches, which the fix introduces); narrowest-revert recipe
 * per test-plan: keep explainEmpty/ExclusionCounts/countSelectorMatches, revert only
 * ClaimItemTool's empty-branch call site back to the single no_match outcome. S8 is
 * EXISTING-SURFACE (passive expiry was already the selector's behavior; this test pins that the
 * new split does not disturb it).
 */
class ClaimItemToolSelectorOutcomeTest : SQLiteRepositoryTestBase() {
    private lateinit var tool: ClaimItemTool
    private lateinit var repository: WorkItemRepository

    private val agentSelf = "agent-y"
    private val agentOther = "agent-x"

    @BeforeEach
    fun setUpTool() {
        tool = ClaimItemTool()
        repository = repositoryProvider.workItemRepository()
    }

    // -----------------------------------------------------------------------
    // JSON-building helpers (match the conventions proven out in ClaimItemToolTest.kt /
    // ClaimItemToolBatchCompositionTest.kt).
    // -----------------------------------------------------------------------

    private fun actorJson(id: String = agentSelf): JsonObject =
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
        actorId: String = agentSelf,
        requestId: String = UUID.randomUUID().toString()
    ): JsonObject =
        buildJsonObject {
            put("claims", buildJsonArray { add(selectorEntry(selectorFields, claimRef)) })
            put("actor", actorJson(actorId))
            put("requestId", requestId)
        }

    /** Real context: no NextItemRecommender override, so ToolExecutionContext wires the
     * production NextItemRecommender against the real (SQLite-backed) repositoryProvider. */
    private fun context(): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = repositoryProvider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        )

    private suspend fun createItem(
        title: String,
        role: Role = Role.QUEUE,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0,
        tags: String? = null,
        complexity: Int = 5,
    ): WorkItem {
        val result =
            repository.create(
                WorkItem(title = title, role = role, parentId = parentId, depth = depth, tags = tags, complexity = complexity)
            )
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private fun firstClaimResult(result: JsonElement): JsonObject {
        val data = (result as JsonObject)["data"] as JsonObject
        return (data["claimResults"] as JsonArray)[0] as JsonObject
    }

    private fun excludedOf(first: JsonObject): JsonObject = first["excluded"] as JsonObject

    // -----------------------------------------------------------------------
    // S1: nothing matches the selector at all (empty queue) -> queue_empty, permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S1 - an empty queue returns queue_empty with permanent kind`(): Unit =
        runBlocking {
            // No items created at all -- countSelectorMatches must report matched == 0.
            val result = tool.execute(params(), context())

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
    fun `S2 - a real live-claimed match returns none_eligible with claimed excluded count`(): Unit =
        runBlocking {
            val item = createItem("Claimed by agent-x")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 900))

            val result = tool.execute(params(), context())

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("transient", first["kind"]?.jsonPrimitive?.content)
            assertEquals("none_eligible", first["code"]?.jsonPrimitive?.content)
            assertEquals(30000L, first["retryAfterMs"]?.jsonPrimitive?.content?.toLongOrNull())
            val excluded = excludedOf(first)
            assertEquals(1, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
            assertNull(first["itemId"], "none_eligible must not echo an itemId")
        }

    // -----------------------------------------------------------------------
    // S3: only match's parent is claimed by another agent -> none_eligible, ancestorClaimed:1
    //
    // The parent is put in WORK role so it does not itself match the selector's default
    // role=QUEUE filter (isolating the ancestorClaimed count from the claimed count -- a claimed
    // QUEUE-role parent would ALSO match the selector and add to "claimed").
    // -----------------------------------------------------------------------

    @Test
    fun `S3 - a real ancestor-claimed match returns none_eligible with ancestorClaimed excluded count`(): Unit =
        runBlocking {
            val parent = createItem("Parent in WORK", role = Role.WORK)
            assertIs<ClaimResult.Success>(repository.claim(parent.id, agentOther, 900))
            createItem("Child under claimed parent", parentId = parent.id)

            val result = tool.execute(params(), context())

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = excludedOf(first)
            assertEquals(0, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
        }

    // -----------------------------------------------------------------------
    // S4: only match has an unmet BLOCKS dependency -> none_eligible, dependencyBlocked:1
    //
    // The blocker is put in WORK role (non-terminal, per the dispatch) so it does not itself
    // match the selector's default role=QUEUE filter.
    // -----------------------------------------------------------------------

    @Test
    fun `S4 - a real dependency-blocked match returns none_eligible with dependencyBlocked excluded count`(): Unit =
        runBlocking {
            val blocker = createItem("Non-terminal blocker", role = Role.WORK)
            val blocked = createItem("Blocked candidate")
            repositoryProvider.dependencyRepository().create(
                Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS)
            )

            val result = tool.execute(params(), context())

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = excludedOf(first)
            assertEquals(0, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(0, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["dependencyBlocked"]?.jsonPrimitive?.int)
        }

    // -----------------------------------------------------------------------
    // S5: all three exclusion reasons present at once (1/1/1), built from three independent
    // real chains; the response never names an item UUID or an agent identity that actually
    // exists in this fixture -- "excluded" is an aggregate count only.
    // -----------------------------------------------------------------------

    @Test
    fun `S5 - combined real exclusion reasons report 1-1-1 and leak no fixture identity`(): Unit =
        runBlocking {
            // Chain 1: a directly claimed QUEUE item.
            val claimedItem = createItem("Directly claimed")
            assertIs<ClaimResult.Success>(repository.claim(claimedItem.id, agentOther, 900))

            // Chain 2: an ancestor-claimed QUEUE child under a WORK-role claimed parent.
            val ancestorParent = createItem("Ancestor parent in WORK", role = Role.WORK)
            assertIs<ClaimResult.Success>(repository.claim(ancestorParent.id, agentOther, 900))
            val ancestorChild = createItem("Ancestor-claimed child", parentId = ancestorParent.id)

            // Chain 3: a dependency-blocked QUEUE item behind a WORK-role blocker.
            val blocker = createItem("Blocker in WORK", role = Role.WORK)
            val depBlocked = createItem("Dependency-blocked candidate")
            repositoryProvider.dependencyRepository().create(
                Dependency(fromItemId = blocker.id, toItemId = depBlocked.id, type = DependencyType.BLOCKS)
            )

            val result = tool.execute(params(), context())

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            val excluded = excludedOf(first)
            assertEquals(1, excluded["claimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["ancestorClaimed"]?.jsonPrimitive?.int)
            assertEquals(1, excluded["dependencyBlocked"]?.jsonPrimitive?.int)

            // Identity-leak probe: every UUID and agent id actually present in this fixture must
            // be absent from the serialized response -- these are real values, not placeholders,
            // so a leak here is a genuine finding rather than a vacuous string search.
            val serialized = result.toString()
            val fixtureItemIds =
                listOf(claimedItem.id, ancestorParent.id, ancestorChild.id, blocker.id, depBlocked.id)
            for (id in fixtureItemIds) {
                assertFalse(
                    id.toString() in serialized,
                    "none_eligible must never leak a fixture item UUID ($id). Got: $serialized"
                )
            }
            assertFalse(
                "\"$agentOther\"" in serialized,
                "none_eligible must never leak the contending agent id ($agentOther). Got: $serialized"
            )
        }

    // -----------------------------------------------------------------------
    // S6: filter parity -- a candidate that matches by role but fails ONE other selector filter
    // must NOT be recommended, and the outcome must be queue_empty (not none_eligible, since
    // nothing was excluded by claim/dependency state -- it simply never matched). This directly
    // exercises diagnosis Risk 1 (countSelectorMatches drifting from findClaimable's condition
    // builder): if either real implementation silently dropped the tags or complexityMax filter,
    // the candidate below would be recommended and claimed, flipping the outcome to "success"
    // and failing these assertions outright.
    // -----------------------------------------------------------------------

    @Test
    fun `S6 - a tag-mismatched candidate is not recommended and yields queue_empty`(): Unit =
        runBlocking {
            // Otherwise fully claimable: QUEUE role, unclaimed, no deps, no ancestor -- only the
            // tags filter should exclude it from the selector's match set.
            createItem("Tagged with an unrelated tag", tags = "other-tag")

            val selectorFields = buildJsonObject { put("tags", "my-tag") }
            val result = tool.execute(params(selectorFields = selectorFields), context())

            val first = firstClaimResult(result)
            assertEquals(
                "queue_empty",
                first["outcome"]?.jsonPrimitive?.content,
                "a tag-mismatched candidate must not be claimed and must yield queue_empty, not success"
            )
        }

    @Test
    fun `S6 - a complexityMax-mismatched candidate is not recommended and yields queue_empty`(): Unit =
        runBlocking {
            // Otherwise fully claimable; only its complexity should exclude it.
            createItem("Too complex", complexity = 9)

            val selectorFields = buildJsonObject { put("complexityMax", 5) }
            val result = tool.execute(params(selectorFields = selectorFields), context())

            val first = firstClaimResult(result)
            assertEquals(
                "queue_empty",
                first["outcome"]?.jsonPrimitive?.content,
                "a complexityMax-mismatched candidate must not be claimed and must yield queue_empty, not success"
            )
        }

    // -----------------------------------------------------------------------
    // S7: claimRef is echoed on both new outcomes, against real state.
    // -----------------------------------------------------------------------

    @Test
    fun `S7 - claimRef is echoed on a real queue_empty outcome`(): Unit =
        runBlocking {
            val result = tool.execute(params(claimRef = "ref-queue-empty"), context())

            val first = firstClaimResult(result)
            assertEquals("queue_empty", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("ref-queue-empty", first["claimRef"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S7 - claimRef is echoed on a real none_eligible outcome`(): Unit =
        runBlocking {
            val item = createItem("Claimed by agent-x")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 900))

            val result = tool.execute(params(claimRef = "ref-none-eligible"), context())

            val first = firstClaimResult(result)
            assertEquals("none_eligible", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("ref-none-eligible", first["claimRef"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // S8 (EXISTING-SURFACE): a candidate whose only prior claim has already passively expired
    // is still eligible, and a matched candidate claims successfully. Oracle:
    // current/docs/api-reference.md "Passive expiry" -- an expired claim does not exclude the
    // item from the claimable set. explainEmpty is never consulted when recommend() is non-empty.
    //
    // This scenario tests the tool's non-empty-recommend() branch, not explainEmpty/
    // countSelectorMatches -- a mock recommender (isolated from this test's real repositoryProvider)
    // is appropriate here, matching the sibling ClaimItemToolTest.kt's own mock-based coverage of
    // this same branch.
    // -----------------------------------------------------------------------

    @Test
    fun `S8 - a candidate whose prior claim has passively expired is still eligible and claims successfully`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val matchedItem = WorkItem(id = itemId, title = "Previously expired claim", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery { recommender.recommend(any(), any()) } returns Result.Success(listOf(matchedItem))

            val mockWorkItemRepo = mockk<WorkItemRepository>()
            val now = Instant.now()
            coEvery { mockWorkItemRepo.claim(itemId, agentSelf, 900) } returns
                ClaimResult.Success(
                    WorkItem(
                        id = itemId,
                        title = "Previously expired claim",
                        claimedBy = agentSelf,
                        claimedAt = now,
                        claimExpiresAt = now.plusSeconds(900),
                        originalClaimedAt = now,
                    )
                )
            val mockProvider = mockk<RepositoryProvider>()
            every { mockProvider.workItemRepository() } returns mockWorkItemRepo

            val mockContext =
                ToolExecutionContext(
                    repositoryProvider = mockProvider,
                    actorVerifier = NoOpActorVerifier,
                    nextItemRecommender = recommender,
                )

            val result = tool.execute(params(), mockContext)

            val first = firstClaimResult(result)
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
            assertEquals(itemId.toString(), first["itemId"]?.jsonPrimitive?.content)
            // explainEmpty must never be consulted when recommend() already returned a candidate.
            coVerify(exactly = 0) { recommender.explainEmpty(any()) }
        }
}

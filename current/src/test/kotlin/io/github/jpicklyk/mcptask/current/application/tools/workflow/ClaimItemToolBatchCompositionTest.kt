package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for [ClaimItemTool] batch composition against a real SQLite repository.
 *
 * The mock-based [ClaimItemToolTest] cannot exercise the interaction between Step 2 of the
 * canonical claim SQL (auto-release of prior claims by the same agent, fired atomically inside
 * `claim()`) and a subsequent explicit `release()` in the same batch. These tests close that gap
 * by wiring the tool to a real [SQLiteWorkItemRepository] so the auto-release semantic is
 * preserved across the tool's claims-then-releases processing order.
 */
class ClaimItemToolBatchCompositionTest : SQLiteRepositoryTestBase() {
    private lateinit var tool: ClaimItemTool
    private lateinit var repository: WorkItemRepository

    private val agentAlpha = "agent-alpha"

    @BeforeEach
    fun setUpTool() {
        tool = ClaimItemTool()
        repository = repositoryProvider.workItemRepository()
    }

    private fun context(): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = repositoryProvider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        )

    private fun actor(id: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "orchestrator")
        }

    private suspend fun createItem(title: String): WorkItem {
        val result = repository.create(WorkItem(title = title, role = Role.QUEUE))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    /**
     * Reproduces the manual-test plan T15 scenario.
     *
     * Pre-state: agent-alpha holds ITEM_C.
     * Batch: claims=[ITEM_MIXED], releases=[ITEM_C].
     *
     * The tool processes claims before releases ([ClaimItemTool.executeClaimRelease]). The
     * `claim(ITEM_MIXED)` call atomically auto-releases ITEM_C inside its SERIALIZABLE
     * transaction (Step 2 of the canonical claim SQL fires after Step 1 acquisition succeeds).
     * The subsequent explicit `release(ITEM_C)` therefore sees an unclaimed row and returns
     * `not_claimed_by_you`.
     *
     * This test pins the contract end-to-end: a regression that reordered the tool to process
     * releases first, or that broke the auto-release atomicity, would flip the observable
     * `releaseResults[0].outcome` and trip this assertion.
     */
    @Test
    fun `claim auto-releases item also in releases array — explicit release returns not_claimed_by_you`(): Unit =
        runBlocking {
            val itemC = createItem("Item C")
            val itemMixed = createItem("Item Mixed")

            // Pre-state: agent-alpha holds ITEM_C.
            assertIs<ClaimResult.Success>(repository.claim(itemC.id, agentAlpha, 900))

            val params =
                buildJsonObject {
                    put(
                        "claims",
                        buildJsonArray {
                            add(buildJsonObject { put("itemId", itemMixed.id.toString()) })
                        }
                    )
                    put(
                        "releases",
                        buildJsonArray {
                            add(buildJsonObject { put("itemId", itemC.id.toString()) })
                        }
                    )
                    put("actor", actor(agentAlpha))
                    put("requestId", UUID.randomUUID().toString())
                }

            val response = tool.execute(params, context()) as JsonObject
            val data = response["data"] as JsonObject

            // --- claimResults: ITEM_MIXED claimed successfully ---
            val claimResults = data["claimResults"] as JsonArray
            assertEquals(1, claimResults.size)
            val claimEntry = claimResults[0] as JsonObject
            assertEquals(itemMixed.id.toString(), claimEntry["itemId"]!!.jsonPrimitive.content)
            assertEquals("success", claimEntry["outcome"]!!.jsonPrimitive.content)
            assertEquals(agentAlpha, claimEntry["claimedBy"]!!.jsonPrimitive.content)

            // --- releaseResults: ITEM_C release reports not_claimed_by_you (auto-released by claim) ---
            val releaseResults = data["releaseResults"] as JsonArray
            assertEquals(1, releaseResults.size)
            val releaseEntry = releaseResults[0] as JsonObject
            assertEquals(itemC.id.toString(), releaseEntry["itemId"]!!.jsonPrimitive.content)
            assertEquals(
                "not_claimed_by_you",
                releaseEntry["outcome"]!!.jsonPrimitive.content,
                "Explicit release of an item already auto-released by claim() in the same batch must return not_claimed_by_you, not success."
            )

            // --- summary counts reflect the actual outcomes ---
            val summary = data["summary"] as JsonObject
            assertEquals(1, summary["claimsTotal"]!!.jsonPrimitive.intOrNull)
            assertEquals(1, summary["claimsSucceeded"]!!.jsonPrimitive.intOrNull)
            assertEquals(0, summary["claimsFailed"]!!.jsonPrimitive.intOrNull)
            assertEquals(1, summary["releasesTotal"]!!.jsonPrimitive.intOrNull)
            assertEquals(0, summary["releasesSucceeded"]!!.jsonPrimitive.intOrNull)
            assertEquals(1, summary["releasesFailed"]!!.jsonPrimitive.intOrNull)

            // --- End-state: ITEM_C is unclaimed, ITEM_MIXED is claimed by agent-alpha ---
            val itemCAfter = repository.getById(itemC.id)
            assertIs<Result.Success<WorkItem>>(itemCAfter)
            assertNull(itemCAfter.data.claimedBy, "ITEM_C must be unclaimed after the batch")

            val itemMixedAfter = repository.getById(itemMixed.id)
            assertIs<Result.Success<WorkItem>>(itemMixedAfter)
            assertEquals(agentAlpha, itemMixedAfter.data.claimedBy)
            assertNotNull(itemMixedAfter.data.claimExpiresAt)
        }
}

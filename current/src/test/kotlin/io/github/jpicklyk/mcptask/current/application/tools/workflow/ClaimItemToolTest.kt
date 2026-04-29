package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [ClaimItemTool] — uses mocked repository, not a real DB.
 *
 * Covers: actor resolution, claim outcomes, release outcomes, tiered disclosure,
 * DegradedModePolicy.REJECT blocking, validation errors.
 */
class ClaimItemToolTest {
    private lateinit var tool: ClaimItemTool
    private lateinit var mockRepo: MockRepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository

    private val agentId = "agent-tool-test-1"
    private val itemId1 = UUID.randomUUID()
    private val itemId2 = UUID.randomUUID()

    /** Actor JSON object for test calls. */
    private fun actorJson(id: String = agentId): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    /** Build a claim request object. */
    private fun claimEntry(
        itemId: UUID,
        ttlSeconds: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            ttlSeconds?.let { put("ttlSeconds", it) }
        }

    /** Build a release request object. */
    private fun releaseEntry(itemId: UUID): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
        }

    /** Build a full tool params object. Always includes requestId (required for claim_item). */
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
            originalClaimedAt = now,
        )
    }

    @BeforeEach
    fun setUp() {
        tool = ClaimItemTool()
        mockRepo = MockRepositoryProvider()
        workItemRepo = mockRepo.workItemRepo
    }

    private fun defaultContext(degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = mockRepo.provider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = degradedModePolicy
        )

    // -----------------------------------------------------------------------
    // validateParams — error cases
    // -----------------------------------------------------------------------

    @Test
    fun `validateParams throws when requestId is absent`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                put("actor", actorJson())
                // no requestId
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assert(ex.message!!.contains("requestId is required"))
    }

    @Test
    fun `validateParams throws when requestId is not a valid UUID`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                put("actor", actorJson())
                put("requestId", "not-a-uuid")
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assert(ex.message!!.contains("valid UUID"))
    }

    @Test
    fun `validateParams throws when claims and releases are both absent`() {
        val p =
            buildJsonObject {
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        assertFailsWith<ToolValidationException> { tool.validateParams(p) }
    }

    @Test
    fun `validateParams throws when claims and releases are both empty arrays`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray {})
                put("releases", buildJsonArray {})
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        assertFailsWith<ToolValidationException> { tool.validateParams(p) }
    }

    @Test
    fun `validateParams throws when claims entry has no itemId`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(buildJsonObject { put("ttlSeconds", 900) }) })
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        assertFailsWith<ToolValidationException> { tool.validateParams(p) }
    }

    @Test
    fun `validateParams throws when claims entry ttlSeconds is zero`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("ttlSeconds", 0)
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        assertFailsWith<ToolValidationException> { tool.validateParams(p) }
    }

    @Test
    fun `validateParams passes with valid claims`() {
        val p = params(claims = listOf(claimEntry(itemId1, 900)))
        tool.validateParams(p) // should not throw
    }

    @Test
    fun `validateParams passes with valid releases`() {
        val p = params(releases = listOf(releaseEntry(itemId1)))
        tool.validateParams(p) // should not throw
    }

    // -----------------------------------------------------------------------
    // Execute — claim success
    // -----------------------------------------------------------------------

    @Test
    fun `execute single claim success returns claim metadata`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val claimResults = data["claimResults"] as JsonArray
            assertEquals(1, claimResults.size)
            val first = claimResults[0] as JsonObject
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
            assertEquals(agentId, first["claimedBy"]?.jsonPrimitive?.content)
            assertNotNull(first["claimExpiresAt"])
            assertNotNull(first["originalClaimedAt"])
        }

    @Test
    fun `execute claim with custom ttlSeconds forwards ttlSeconds to repository`(): Unit =
        runBlocking {
            val ttlSlot = slot<Int>()
            coEvery { workItemRepo.claim(itemId1, agentId, capture(ttlSlot)) } returns
                ClaimResult.Success(makeSuccessItem())

            tool.execute(params(claims = listOf(claimEntry(itemId1, 300))), defaultContext())

            assertEquals(300, ttlSlot.captured)
        }

    // -----------------------------------------------------------------------
    // Execute — claim failures
    // -----------------------------------------------------------------------

    @Test
    fun `execute claim already_claimed returns retryAfterMs without agent identity`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 45000L)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val claimResults = data["claimResults"] as JsonArray
            val first = claimResults[0] as JsonObject
            assertEquals("already_claimed", first["outcome"]?.jsonPrimitive?.content)
            assertEquals(45000L, first["retryAfterMs"]?.jsonPrimitive?.content?.toLongOrNull())
            // Tiered disclosure: no agent identity fields
            assertNull(first["claimedBy"])
        }

    @Test
    fun `execute claim not_found returns not_found outcome`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.NotFound(itemId1)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("not_found", first["outcome"]?.jsonPrimitive?.content)
        }

    @Test
    fun `execute claim terminal_item returns terminal_item outcome`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.TerminalItem(itemId1)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("terminal_item", first["outcome"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // Execute — release
    // -----------------------------------------------------------------------

    @Test
    fun `execute release success clears claim`(): Unit =
        runBlocking {
            val releasedItem = WorkItem(id = itemId1, title = "Released")
            coEvery { workItemRepo.release(itemId1, agentId) } returns ReleaseResult.Success(releasedItem)

            val result = tool.execute(params(releases = listOf(releaseEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val releaseResults = data["releaseResults"] as JsonArray
            assertEquals(1, releaseResults.size)
            val first = releaseResults[0] as JsonObject
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
        }

    @Test
    fun `execute release not_claimed_by_you returns appropriate outcome`(): Unit =
        runBlocking {
            coEvery { workItemRepo.release(itemId1, agentId) } returns ReleaseResult.NotClaimedByYou(itemId1)

            val result = tool.execute(params(releases = listOf(releaseEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["releaseResults"] as JsonArray)[0] as JsonObject
            assertEquals("not_claimed_by_you", first["outcome"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // Execute — summary counts
    // -----------------------------------------------------------------------

    @Test
    fun `execute summary reflects correct counts for mixed outcomes`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())
            coEvery { workItemRepo.claim(itemId2, agentId, 900) } returns ClaimResult.AlreadyClaimed(itemId2, null)
            coEvery { workItemRepo.release(itemId1, agentId) } returns ReleaseResult.Success(WorkItem(id = itemId1, title = "R"))

            val result =
                tool.execute(
                    params(
                        claims = listOf(claimEntry(itemId1), claimEntry(itemId2)),
                        releases = listOf(releaseEntry(itemId1))
                    ),
                    defaultContext()
                )

            val data = (result as JsonObject)["data"] as JsonObject
            val summary = data["summary"] as JsonObject
            assertEquals(2, summary["claimsTotal"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["claimsSucceeded"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["claimsFailed"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["releasesTotal"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["releasesSucceeded"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, summary["releasesFailed"]?.jsonPrimitive?.intOrNull)
        }

    // -----------------------------------------------------------------------
    // Execute — actor required
    // -----------------------------------------------------------------------

    @Test
    fun `execute without actor returns error`(): Unit =
        runBlocking {
            val p =
                buildJsonObject {
                    put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                    put("requestId", UUID.randomUUID().toString())
                    // no actor
                }

            val result = tool.execute(p, defaultContext())

            val resultObj = result as JsonObject
            assertEquals(false, resultObj["success"]?.jsonPrimitive?.booleanOrNull)
        }

    // -----------------------------------------------------------------------
    // Execute — DegradedModePolicy.REJECT blocks claim
    // -----------------------------------------------------------------------

    @Test
    fun `execute with REJECT policy and unverified actor returns error response`(): Unit =
        runBlocking {
            // Use a verifier that returns ABSENT (not VERIFIED)
            val absentVerifier =
                object : ActorVerifier {
                    override suspend fun verify(claim: ActorClaim): VerificationResult =
                        VerificationResult(status = VerificationStatus.ABSENT, verifier = "test")
                }

            val context =
                ToolExecutionContext(
                    repositoryProvider = mockRepo.provider,
                    actorVerifier = absentVerifier,
                    degradedModePolicy = DegradedModePolicy.REJECT
                )

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), context)

            // Should be an error response (policy rejected)
            val resultObj = result as JsonObject
            assertEquals(false, resultObj["success"]?.jsonPrimitive?.booleanOrNull)
            // Repository should not have been called
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    @Test
    fun `execute with REJECT policy and VERIFIED actor succeeds`(): Unit =
        runBlocking {
            val verifiedVerifier =
                object : ActorVerifier {
                    override suspend fun verify(claim: ActorClaim): VerificationResult =
                        VerificationResult(status = VerificationStatus.VERIFIED, verifier = "test-jwks")
                }

            val context =
                ToolExecutionContext(
                    repositoryProvider = mockRepo.provider,
                    actorVerifier = verifiedVerifier,
                    degradedModePolicy = DegradedModePolicy.REJECT
                )

            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), context)

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // TEST-C1: already_claimed response includes contendedItemId
    // -----------------------------------------------------------------------

    @Test
    fun `already_claimed response includes contendedItemId equal to the contested itemId`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 30000L)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("already_claimed", first["outcome"]?.jsonPrimitive?.content)
            val contendedItemId = first["contendedItemId"]?.jsonPrimitive?.content
            assertNotNull(contendedItemId, "contendedItemId must be present in already_claimed response")
            assertEquals(
                itemId1.toString(),
                contendedItemId,
                "contendedItemId must equal the contested item's UUID"
            )
        }

    // -----------------------------------------------------------------------
    // TEST-I9: already_claimed response serialized JSON has kind="transient"
    // -----------------------------------------------------------------------

    @Test
    fun `already_claimed response serialized JSON has kind equal to transient`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 45000L)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            val kind = first["kind"]?.jsonPrimitive?.content
            assertNotNull(kind, "kind must be present in already_claimed response")
            assertEquals("transient", kind, "kind must be \"transient\" for already_claimed contention errors")
        }

    // -----------------------------------------------------------------------
    // TEST-I6: already_claimed full serialized response does not contain "claimedBy" key
    // -----------------------------------------------------------------------

    @Test
    fun `already_claimed full serialized response does not contain claimedBy JSON key anywhere`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 45000L)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            // Serialize the entire response to JSON string and scan for the "claimedBy" key.
            // This is defense-in-depth: a structural leakage regression (e.g. claimedBy=null or
            // a renamed field like claimedByAgent) would introduce the JSON key without a value match.
            // JsonElement.toString() produces valid JSON in kotlinx.serialization.
            val serialized = result.toString()
            assertFalse(
                "\"claimedBy\"" in serialized,
                "Serialized already_claimed response must not contain \"claimedBy\" JSON key anywhere. " +
                    "Got: $serialized"
            )
        }

    // -----------------------------------------------------------------------
    // TEST-I11: claim with empty-string or whitespace-only agentId is rejected at validateParams
    // -----------------------------------------------------------------------

    @Test
    fun `validateParams throws ToolValidationException when actor id is empty string`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                put("actor", actorJson(id = ""))
            }
        assertFailsWith<ToolValidationException>(
            "Empty actor.id must be rejected at validateParams"
        ) { tool.validateParams(p) }
    }

    @Test
    fun `validateParams throws ToolValidationException when actor id is whitespace only`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(claimEntry(itemId1)) })
                put("actor", actorJson(id = "  "))
            }
        assertFailsWith<ToolValidationException>(
            "Whitespace-only actor.id must be rejected at validateParams"
        ) { tool.validateParams(p) }
    }

    // -----------------------------------------------------------------------
    // NICE-N3: large TTL (86400 seconds / 1 day) is accepted without rejection
    // -----------------------------------------------------------------------

    @Test
    fun `validateParams accepts large ttlSeconds of 86400 without rejection`() {
        // Verifies that no upper-bound validation rejects legitimately large TTLs.
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("ttlSeconds", 86400)
                            }
                        )
                    }
                )
                put("actor", actorJson())
            }
        // Must not throw — large TTLs are valid
        tool.validateParams(p)
    }
}

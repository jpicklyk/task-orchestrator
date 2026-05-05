package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ClaimItemTool] — uses mocked repository, not a real DB.
 *
 * Covers: actor resolution, claim outcomes, release outcomes, tiered disclosure,
 * DegradedModePolicy.REJECT blocking, validation errors, selector mode.
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

    /** Build a claim request object (ID mode). */
    private fun claimEntry(
        itemId: UUID,
        ttlSeconds: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            ttlSeconds?.let { put("ttlSeconds", it) }
        }

    /** Build a claim request object with claimRef (ID mode). */
    private fun claimEntryWithRef(
        itemId: UUID,
        claimRef: String,
        ttlSeconds: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            put("claimRef", claimRef)
            ttlSeconds?.let { put("ttlSeconds", it) }
        }

    /** Build a selector claim entry. */
    private fun selectorEntry(
        selectorFields: JsonObject = buildJsonObject {},
        claimRef: String? = null,
        ttlSeconds: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("selector", selectorFields)
            claimRef?.let { put("claimRef", it) }
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

    private fun defaultContext(
        degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        nextItemRecommender: NextItemRecommender? = null
    ): ToolExecutionContext =
        if (nextItemRecommender != null) {
            ToolExecutionContext(
                repositoryProvider = mockRepo.provider,
                actorVerifier = NoOpActorVerifier,
                degradedModePolicy = degradedModePolicy,
                nextItemRecommender = nextItemRecommender,
            )
        } else {
            ToolExecutionContext(
                repositoryProvider = mockRepo.provider,
                actorVerifier = NoOpActorVerifier,
                degradedModePolicy = degradedModePolicy,
            )
        }

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
                put("requestId", UUID.randomUUID().toString())
            }
        // Must not throw — large TTLs are valid
        tool.validateParams(p)
    }

    // -----------------------------------------------------------------------
    // TTL upper bound — 86400 s cap
    // -----------------------------------------------------------------------

    @Test
    fun `validateParams accepts ttlSeconds of 86400 boundary`() {
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
                put("requestId", UUID.randomUUID().toString())
            }
        // Exactly at the boundary — must not throw
        tool.validateParams(p)
    }

    @Test
    fun `validateParams throws when ttlSeconds is 86401`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("ttlSeconds", 86401)
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("must not exceed 86400 (24 hours), got 86401"),
            "Error message must mention the cap and the actual value. Got: ${ex.message}"
        )
    }

    @Test
    fun `validateParams throws when ttlSeconds is 999999`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("ttlSeconds", 999999)
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("must not exceed 86400 (24 hours), got 999999"),
            "Error message must mention the cap and the actual value. Got: ${ex.message}"
        )
    }

    // -----------------------------------------------------------------------
    // H1: DBError — unexpected database exception surfaces as db_error ToolError
    // -----------------------------------------------------------------------

    /**
     * H1-T1: claim path — repository returns DBError → tool emits db_error JSON shape.
     *
     * Expected JSON shape in claimResults[0]:
     *   { outcome: "db_error", kind: "transient", code: "db_error", contendedItemId: "<uuid>" }
     * The exception message must NOT appear in the response (internal detail leak prevention).
     */
    @Test
    fun `claim DBError surfaces as db_error outcome with transient kind and contendedItemId`(): Unit =
        runBlocking {
            val cause = SQLException("internal db failure — must not appear in response")
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.DBError(itemId1, cause)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject

            assertEquals("db_error", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("transient", first["kind"]?.jsonPrimitive?.content)
            assertEquals("db_error", first["code"]?.jsonPrimitive?.content)
            val contendedItemId = first["contendedItemId"]?.jsonPrimitive?.content
            assertNotNull(contendedItemId, "contendedItemId must be present for db_error")
            assertEquals(itemId1.toString(), contendedItemId)

            // Exception message must not appear anywhere in the response (internal detail)
            val serialized = result.toString()
            assertFalse(
                "internal db failure" in serialized,
                "Exception message must not leak into JSON response. Got: $serialized"
            )
            // retryAfterMs must be absent (not populated for db_error)
            assertNull(first["retryAfterMs"], "retryAfterMs must be absent for db_error")
        }

    /**
     * H1-T2: release path — repository returns DBError → tool emits db_error JSON shape.
     *
     * Expected JSON shape in releaseResults[0]:
     *   { outcome: "db_error", kind: "transient", code: "db_error", contendedItemId: "<uuid>" }
     */
    @Test
    fun `release DBError surfaces as db_error outcome with transient kind and contendedItemId`(): Unit =
        runBlocking {
            val cause = SQLException("release db failure — must not appear in response")
            coEvery { workItemRepo.release(itemId1, agentId) } returns ReleaseResult.DBError(itemId1, cause)

            val result = tool.execute(params(releases = listOf(releaseEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["releaseResults"] as JsonArray)[0] as JsonObject

            assertEquals("db_error", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("transient", first["kind"]?.jsonPrimitive?.content)
            assertEquals("db_error", first["code"]?.jsonPrimitive?.content)
            val contendedItemId = first["contendedItemId"]?.jsonPrimitive?.content
            assertNotNull(contendedItemId, "contendedItemId must be present for db_error on release")
            assertEquals(itemId1.toString(), contendedItemId)

            // Exception message must not appear anywhere in the response
            val serialized = result.toString()
            assertFalse(
                "release db failure" in serialized,
                "Exception message must not leak into JSON response. Got: $serialized"
            )
            assertNull(first["retryAfterMs"], "retryAfterMs must be absent for db_error on release")
        }

    /**
     * H1-T3: claim DBError increments claimsFailed (not claimsSucceeded) in summary.
     */
    @Test
    fun `claim DBError increments claimsFailed in summary`(): Unit =
        runBlocking {
            val cause = SQLException("db error for summary test")
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.DBError(itemId1, cause)

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), defaultContext())

            val data = (result as JsonObject)["data"] as JsonObject
            val summary = data["summary"] as JsonObject
            assertEquals(1, summary["claimsTotal"]?.jsonPrimitive?.intOrNull)
            assertEquals(0, summary["claimsSucceeded"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["claimsFailed"]?.jsonPrimitive?.intOrNull)
        }

    // -----------------------------------------------------------------------
    // H6: rejected_by_policy envelope shape
    // -----------------------------------------------------------------------

    /**
     * H6-P1: Explicit assertion of the `rejected_by_policy` envelope shape.
     *
     * When `degradedModePolicy=REJECT` and the actor verification returns ABSENT (not VERIFIED),
     * `buildRejectedByPolicyResponse` is called, which delegates to `errorResponse(ToolError.permanent(...))`.
     * The resulting JSON must have:
     *   - response.success == false
     *   - response.error.kind == "permanent"
     *   - response.error.code == "rejected_by_policy"
     *   - response.error.message is non-empty
     *
     * This test pins the exact JSON key-path so that a future refactor of the policy-rejection
     * branch is caught if it changes the envelope shape.
     */
    @Test
    fun `REJECT policy with unverified actor produces permanent rejected_by_policy error envelope`(): Unit =
        runBlocking {
            val absentVerifier =
                object : ActorVerifier {
                    override suspend fun verify(claim: ActorClaim): VerificationResult =
                        VerificationResult(status = VerificationStatus.ABSENT, verifier = "test-absent")
                }

            val context =
                ToolExecutionContext(
                    repositoryProvider = mockRepo.provider,
                    actorVerifier = absentVerifier,
                    degradedModePolicy = DegradedModePolicy.REJECT
                )

            val result = tool.execute(params(claims = listOf(claimEntry(itemId1))), context)

            val resultObj = result as JsonObject

            // Top-level: success must be false
            assertEquals(
                false,
                resultObj["success"]?.jsonPrimitive?.booleanOrNull,
                "success must be false for a policy-rejected request"
            )

            // error envelope must be present
            val errorObj = resultObj["error"] as? JsonObject
            assertNotNull(errorObj, "error object must be present in the response")

            // kind must be "permanent" (rejected_by_policy is not retryable)
            assertEquals(
                "permanent",
                errorObj["kind"]?.jsonPrimitive?.content,
                "error.kind must be \"permanent\" for rejected_by_policy"
            )

            // code must be exactly "rejected_by_policy"
            assertEquals(
                "rejected_by_policy",
                errorObj["code"]?.jsonPrimitive?.content,
                "error.code must be \"rejected_by_policy\""
            )

            // message must be non-empty (the policy reason)
            val message = errorObj["message"]?.jsonPrimitive?.content
            assertNotNull(message, "error.message must be present")
            assertTrue(message.isNotBlank(), "error.message must be non-empty for rejected_by_policy")

            // Repository must NOT have been called (policy rejection is a pre-DB guard)
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }

    // -----------------------------------------------------------------------
    // Selector validation tests
    // -----------------------------------------------------------------------

    @Test
    fun `validateParams throws when claim entry has both itemId and selector`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("selector", buildJsonObject {})
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("not both"),
            "Error must mention 'not both'. Got: ${ex.message}"
        )
    }

    @Test
    fun `validateParams throws when claim entry has neither itemId nor selector`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(buildJsonObject { put("ttlSeconds", 900) })
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("not neither") || ex.message!!.contains("neither"),
            "Error must mention 'neither'. Got: ${ex.message}"
        )
    }

    @Test
    fun `validateParams throws when claims has two selector entries`() {
        val selector = buildJsonObject {}
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(buildJsonObject { put("selector", selector) })
                        add(buildJsonObject { put("selector", selector) })
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("single claim per call") || ex.message!!.contains("Selector mode"),
            "Error must mention selector mode constraint. Got: ${ex.message}"
        )
    }

    @Test
    fun `validateParams throws when claims has one selector and one itemId entry`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(buildJsonObject { put("selector", buildJsonObject {}) })
                        add(buildJsonObject { put("itemId", itemId1.toString()) })
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(
            ex.message!!.contains("single claim per call") || ex.message!!.contains("Selector mode"),
            "Error must mention selector mode constraint. Got: ${ex.message}"
        )
    }

    @Test
    fun `validateParams accepts valid selector entry with empty selector object`() {
        val p =
            buildJsonObject {
                put("claims", buildJsonArray { add(selectorEntry()) })
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        // Must not throw
        tool.validateParams(p)
    }

    @Test
    fun `validateParams throws for selector with invalid priority`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "selector",
                                    buildJsonObject { put("priority", "CRITICAL") }
                                )
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(ex.message!!.contains("priority"), "Error must mention priority. Got: ${ex.message}")
    }

    @Test
    fun `validateParams throws for selector with complexityMax out of range`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "selector",
                                    buildJsonObject { put("complexityMax", 11) }
                                )
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(ex.message!!.contains("complexityMax"), "Error must mention complexityMax. Got: ${ex.message}")
    }

    @Test
    fun `validateParams throws for selector with invalid ISO 8601 timestamp`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "selector",
                                    buildJsonObject { put("createdAfter", "not-a-timestamp") }
                                )
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(ex.message!!.contains("createdAfter"), "Error must mention createdAfter. Got: ${ex.message}")
    }

    @Test
    fun `validateParams throws for selector with invalid orderBy value`() {
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "selector",
                                    buildJsonObject { put("orderBy", "random") }
                                )
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(ex.message!!.contains("orderBy"), "Error must mention orderBy. Got: ${ex.message}")
    }

    @Test
    fun `validateParams throws when claimRef exceeds 64 chars`() {
        val longRef = "a".repeat(65)
        val p =
            buildJsonObject {
                put(
                    "claims",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId1.toString())
                                put("claimRef", longRef)
                            }
                        )
                    }
                )
                put("actor", actorJson())
                put("requestId", UUID.randomUUID().toString())
            }
        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(p) }
        assertTrue(ex.message!!.contains("claimRef"), "Error must mention claimRef. Got: ${ex.message}")
    }

    // -----------------------------------------------------------------------
    // Selector execute tests
    // -----------------------------------------------------------------------

    private fun mockRecommender(items: List<WorkItem>): NextItemRecommender {
        val recommender = mockk<NextItemRecommender>()
        coEvery { recommender.recommend(any(), any()) } returns Result.Success(items)
        return recommender
    }

    @Test
    fun `selector with tags filter claims item when recommender returns match`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Tagged Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            coEvery {
                recommender.recommend(
                    match { it.tags == listOf("my-tag") },
                    1
                )
            } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val selectorFields = buildJsonObject { put("tags", "my-tag") }
            val result = tool.execute(
                params(claims = listOf(selectorEntry(selectorFields))),
                defaultContext(nextItemRecommender = recommender)
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
            assertEquals(itemId1.toString(), first["itemId"]?.jsonPrimitive?.content)
            assertEquals(true, first["selectorResolved"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `selector with priority filter passes correct criteria to recommender`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "High Priority Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            val criteriaSlot = slot<NextItemRecommender.Criteria>()
            coEvery { recommender.recommend(capture(criteriaSlot), 1) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val selectorFields = buildJsonObject { put("priority", "HIGH") }
            tool.execute(
                params(claims = listOf(selectorEntry(selectorFields))),
                defaultContext(nextItemRecommender = recommender)
            )

            assertEquals(Priority.HIGH, criteriaSlot.captured.priority)
        }

    @Test
    fun `selector with type filter passes correct type to recommender`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Typed Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            val criteriaSlot = slot<NextItemRecommender.Criteria>()
            coEvery { recommender.recommend(capture(criteriaSlot), 1) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val selectorFields = buildJsonObject { put("type", "feature-task") }
            tool.execute(
                params(claims = listOf(selectorEntry(selectorFields))),
                defaultContext(nextItemRecommender = recommender)
            )

            assertEquals("feature-task", criteriaSlot.captured.type)
        }

    @Test
    fun `selector with complexityMax filter passes correct value to recommender`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Simple Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            val criteriaSlot = slot<NextItemRecommender.Criteria>()
            coEvery { recommender.recommend(capture(criteriaSlot), 1) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val selectorFields = buildJsonObject { put("complexityMax", 3) }
            tool.execute(
                params(claims = listOf(selectorEntry(selectorFields))),
                defaultContext(nextItemRecommender = recommender)
            )

            assertEquals(3, criteriaSlot.captured.complexityMax)
        }

    @Test
    fun `selector with orderBy oldest passes OLDEST_FIRST order to recommender`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Oldest Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            val criteriaSlot = slot<NextItemRecommender.Criteria>()
            coEvery { recommender.recommend(capture(criteriaSlot), 1) } returns Result.Success(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val selectorFields = buildJsonObject { put("orderBy", "oldest") }
            tool.execute(
                params(claims = listOf(selectorEntry(selectorFields))),
                defaultContext(nextItemRecommender = recommender)
            )

            assertEquals(io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder.OLDEST_FIRST, criteriaSlot.captured.orderBy)
        }

    @Test
    fun `selector with no matches returns no_match outcome with kind permanent`(): Unit =
        runBlocking {
            val recommender = mockRecommender(emptyList())

            val result = tool.execute(
                params(claims = listOf(selectorEntry())),
                defaultContext(nextItemRecommender = recommender)
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("no_match", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("permanent", first["kind"]?.jsonPrimitive?.content)
            assertEquals("no_match", first["code"]?.jsonPrimitive?.content)
            // no retryAfterMs for no_match
            assertNull(first["retryAfterMs"], "no_match must not have retryAfterMs")
            // no itemId for no_match (nothing was resolved)
            assertNull(first["itemId"], "no_match must not have itemId")
            // claimsSucceeded should be 0
            val summary = data["summary"] as JsonObject
            assertEquals(0, summary["claimsSucceeded"]?.jsonPrimitive?.intOrNull)
            assertEquals(1, summary["claimsFailed"]?.jsonPrimitive?.intOrNull)
        }

    @Test
    fun `selector TOCTOU — recommender returns item but claim returns AlreadyClaimed`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Contested Item", role = Role.QUEUE)
            val recommender = mockRecommender(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 5000L)

            val result = tool.execute(
                params(claims = listOf(selectorEntry())),
                defaultContext(nextItemRecommender = recommender)
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("already_claimed", first["outcome"]?.jsonPrimitive?.content)
            // The resolved itemId must be echoed even on TOCTOU failure
            assertEquals(itemId1.toString(), first["itemId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `selector idempotency replay returns same itemId from cache`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "First Queued Item", role = Role.QUEUE)
            val recommender = mockk<NextItemRecommender>()
            val differentItem = WorkItem(id = itemId2, title = "Different Item", role = Role.QUEUE)
            // First call returns matchedItem; second call returns a different item (simulates queue state change)
            coEvery { recommender.recommend(any(), 1) } returnsMany listOf(
                Result.Success(listOf(matchedItem)),
                Result.Success(listOf(differentItem))
            )
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem(itemId1))

            val requestId = UUID.randomUUID().toString()
            val p = params(claims = listOf(selectorEntry()), requestId = requestId)

            // First call — resolves and claims itemId1
            val result1 = tool.execute(p, defaultContext(nextItemRecommender = recommender))
            val first1 = ((result1 as JsonObject)["data"] as JsonObject)["claimResults"].let {
                (it as JsonArray)[0] as JsonObject
            }
            assertEquals("success", first1["outcome"]?.jsonPrimitive?.content)
            assertEquals(itemId1.toString(), first1["itemId"]?.jsonPrimitive?.content)

            // Second call with same requestId — must replay the cache, NOT call recommender again
            val result2 = tool.execute(p, defaultContext(nextItemRecommender = recommender))
            val first2 = ((result2 as JsonObject)["data"] as JsonObject)["claimResults"].let {
                (it as JsonArray)[0] as JsonObject
            }
            // Replayed from cache — same itemId1, not itemId2
            assertEquals(itemId1.toString(), first2["itemId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `claimRef is echoed on success outcome`(): Unit =
        runBlocking {
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())

            val result = tool.execute(
                params(claims = listOf(claimEntryWithRef(itemId1, "task-abc-123"))),
                defaultContext()
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("success", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("task-abc-123", first["claimRef"]?.jsonPrimitive?.content)
        }

    @Test
    fun `claimRef is echoed on no_match outcome from selector`(): Unit =
        runBlocking {
            val recommender = mockRecommender(emptyList())

            val result = tool.execute(
                params(claims = listOf(selectorEntry(claimRef = "my-ref-42"))),
                defaultContext(nextItemRecommender = recommender)
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("no_match", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("my-ref-42", first["claimRef"]?.jsonPrimitive?.content)
        }

    @Test
    fun `claimRef is echoed on already_claimed outcome from selector`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Contested", role = Role.QUEUE)
            val recommender = mockRecommender(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns
                ClaimResult.AlreadyClaimed(itemId1, retryAfterMs = 1000L)

            val result = tool.execute(
                params(claims = listOf(selectorEntry(claimRef = "my-ref"))),
                defaultContext(nextItemRecommender = recommender)
            )

            val data = (result as JsonObject)["data"] as JsonObject
            val first = (data["claimResults"] as JsonArray)[0] as JsonObject
            assertEquals("already_claimed", first["outcome"]?.jsonPrimitive?.content)
            assertEquals("my-ref", first["claimRef"]?.jsonPrimitive?.content)
        }

    @Test
    fun `selector with multi-release in same call — selector resolves and both releases succeed`(): Unit =
        runBlocking {
            val matchedItem = WorkItem(id = itemId1, title = "Selector Resolved", role = Role.QUEUE)
            val recommender = mockRecommender(listOf(matchedItem))
            coEvery { workItemRepo.claim(itemId1, agentId, 900) } returns ClaimResult.Success(makeSuccessItem())
            coEvery { workItemRepo.release(itemId2, agentId) } returns
                ReleaseResult.Success(WorkItem(id = itemId2, title = "Released2"))
            val extraReleaseId = UUID.randomUUID()
            coEvery { workItemRepo.release(extraReleaseId, agentId) } returns
                ReleaseResult.Success(WorkItem(id = extraReleaseId, title = "Released3"))

            val p =
                buildJsonObject {
                    put("claims", buildJsonArray { add(selectorEntry()) })
                    put(
                        "releases",
                        buildJsonArray {
                            add(releaseEntry(itemId2))
                            add(releaseEntry(extraReleaseId))
                        }
                    )
                    put("actor", actorJson())
                    put("requestId", UUID.randomUUID().toString())
                }

            val result = tool.execute(p, defaultContext(nextItemRecommender = recommender))
            val data = (result as JsonObject)["data"] as JsonObject

            val claimResults = data["claimResults"] as JsonArray
            assertEquals(1, claimResults.size)
            val claimFirst = claimResults[0] as JsonObject
            assertEquals("success", claimFirst["outcome"]?.jsonPrimitive?.content)
            assertEquals(true, claimFirst["selectorResolved"]?.jsonPrimitive?.booleanOrNull)

            val releaseResults = data["releaseResults"] as JsonArray
            assertEquals(2, releaseResults.size)
            releaseResults.forEach { r ->
                assertEquals("success", (r as JsonObject)["outcome"]?.jsonPrimitive?.content)
            }

            val summary = data["summary"] as JsonObject
            assertEquals(1, summary["claimsSucceeded"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, summary["releasesSucceeded"]?.jsonPrimitive?.intOrNull)
        }
}

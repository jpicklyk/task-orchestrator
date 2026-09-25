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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test author: test-author:b53007e0 (independent, blind to src/main per test-author skill §4).
 *
 * Characterization-first wave: [`decompose-complexity-hotspots`] item `b53007e0`
 * ("Decompose ClaimItemTool.executeClaimRelease"). Written against the CURRENT
 * (pre-decomposition) public surface at base `dd26e9e2`, from the frozen `test-plan` note and
 * the dispatch's supplied declarations block — no file under `src/main` was opened to write
 * these assertions. All scenarios are EXISTING-SURFACE (per the trait's §2 labelling): the fix
 * this wave performs is a pure decomposition, not a new surface, so red-proof is a plain revert
 * of the targeted #339-#343 fix hunk, orchestrator-run.
 *
 * Scenario ids S1-S7 (plus the "R" probes) match the item's `test-plan` note. This file covers
 * every scenario the plan marks "R" (real-SQLite): claim/release contention, TTL expiry,
 * heartbeat re-claim, non-owner release, not-found paths, unresolvable id prefixes, and the
 * entry-level `agentId` field being ignored in favor of `actor.id`.
 *
 * Uses a real in-memory SQLite repository (via [SQLiteRepositoryTestBase]) because the claim SQL
 * relies on SQLite-specific `datetime('now', ...)` semantics that a mock cannot exercise
 * faithfully — matching the idiom already proven out in
 * [io.github.jpicklyk.mcptask.current.infrastructure.database.repository.SQLiteWorkItemRepositoryClaimTest]
 * and [ClaimItemToolSelectorOutcomeTest].
 */
class ClaimItemToolRealStateTest : SQLiteRepositoryTestBase() {
    private lateinit var tool: ClaimItemTool
    private lateinit var repository: WorkItemRepository

    private val agentOther = "agent-x"
    private val agentSelf = "agent-y"

    @BeforeEach
    fun setUpTool() {
        tool = ClaimItemTool()
        repository = repositoryProvider.workItemRepository()
    }

    // -----------------------------------------------------------------------
    // JSON-building helpers (match the conventions in ClaimItemToolTest.kt /
    // ClaimItemToolSelectorOutcomeTest.kt).
    // -----------------------------------------------------------------------

    private fun actorJson(id: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    private fun claimEntry(
        itemId: String,
        ttlSeconds: Int? = null,
        claimRef: String? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            ttlSeconds?.let { put("ttlSeconds", it) }
            claimRef?.let { put("claimRef", it) }
        }

    private fun releaseEntry(
        itemId: String,
        claimRef: String? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            claimRef?.let { put("claimRef", it) }
        }

    private fun params(
        claims: List<JsonObject> = emptyList(),
        releases: List<JsonObject> = emptyList(),
        actorId: String = agentSelf,
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

    private fun context(): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = repositoryProvider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
        )

    private fun firstResult(
        result: JsonElement,
        arrayKey: String
    ): JsonObject {
        val data = (result as JsonObject)["data"] as JsonObject
        return (data[arrayKey] as JsonArray)[0] as JsonObject
    }

    private suspend fun createItem(title: String): WorkItem {
        val result = repository.create(WorkItem(title = title, role = Role.QUEUE))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    // -----------------------------------------------------------------------
    // S1 — contention: holder keeps the item, contender gets a bounded retry hint,
    // no identity of the holder leaks.
    // -----------------------------------------------------------------------

    @Test
    fun `S1 contention returns already_claimed with bounded retryAfterMs and no identity leak`(): Unit =
        runBlocking {
            val item = createItem("Contended Item")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 900))

            val result = tool.execute(params(claims = listOf(claimEntry(item.id.toString()))), context())

            val first = firstResult(result, "claimResults")
            assertEquals(
                listOf("itemId", "outcome", "kind", "contendedItemId", "retryAfterMs"),
                first.keys.toList()
            )
            assertEquals("already_claimed", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content)
            assertEquals(item.id.toString(), first["itemId"]!!.jsonPrimitive.content)
            assertEquals(item.id.toString(), first["contendedItemId"]!!.jsonPrimitive.content)
            val retryAfterMs = first["retryAfterMs"]!!.jsonPrimitive.long
            assertTrue(retryAfterMs in 1..900000L, "retryAfterMs must be bounded by the TTL: got $retryAfterMs")

            val serialized = result.toString()
            assertFalse(agentOther in serialized, "the holder's identity must not leak into a contention response")
            assertFalse("\"claimedBy\"" in serialized, "no claimedBy key on an already_claimed response")

            val dbItem = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(dbItem)
            assertEquals(agentOther, dbItem.data.claimedBy, "the original holder's claim must be undisturbed")
        }

    // -----------------------------------------------------------------------
    // S2 — TTL passive expiry: a new claimer succeeds once the prior claim's TTL has elapsed;
    // a fresh claim omits originalClaimedAt.
    // -----------------------------------------------------------------------

    @Test
    fun `S2 passive TTL expiry allows new claimer and omits originalClaimedAt`(): Unit =
        runBlocking {
            val item = createItem("Expiring Item")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 1))
            Thread.sleep(2500)

            val result = tool.execute(params(claims = listOf(claimEntry(item.id.toString()))), context())

            val first = firstResult(result, "claimResults")
            assertEquals("success", first["outcome"]!!.jsonPrimitive.content)
            assertEquals(agentSelf, first["claimedBy"]!!.jsonPrimitive.content)
            assertNull(first["originalClaimedAt"], "a fresh claim must omit originalClaimedAt")
        }

    // -----------------------------------------------------------------------
    // S3 — heartbeat re-claim by the same agent preserves originalClaimedAt and extends the TTL.
    // -----------------------------------------------------------------------

    @Test
    fun `S3 heartbeat re-claim preserves originalClaimedAt and extends TTL to the new value`(): Unit =
        runBlocking {
            val item = createItem("Heartbeat Item")

            val firstResponse = tool.execute(params(claims = listOf(claimEntry(item.id.toString()))), context())
            val firstEntry = firstResult(firstResponse, "claimResults")
            assertEquals("success", firstEntry["outcome"]!!.jsonPrimitive.content)
            val firstClaimedAt = firstEntry["claimedAt"]!!.jsonPrimitive.content

            Thread.sleep(2100)

            val secondResponse =
                tool.execute(
                    params(claims = listOf(claimEntry(item.id.toString(), ttlSeconds = 1800))),
                    context()
                )
            val secondEntry = firstResult(secondResponse, "claimResults")
            assertEquals("success", secondEntry["outcome"]!!.jsonPrimitive.content)
            assertEquals(
                firstClaimedAt,
                secondEntry["originalClaimedAt"]!!.jsonPrimitive.content,
                "originalClaimedAt must be preserved from the first claim across a same-agent re-claim"
            )

            val claimedAt = Instant.parse(secondEntry["claimedAt"]!!.jsonPrimitive.content)
            val expiresAt = Instant.parse(secondEntry["claimExpiresAt"]!!.jsonPrimitive.content)
            assertEquals(1800L, expiresAt.epochSecond - claimedAt.epochSecond, "TTL must extend to the new value")
        }

    // -----------------------------------------------------------------------
    // S4 — release by a non-owner is rejected and does not disturb the holder's claim.
    // -----------------------------------------------------------------------

    @Test
    fun `S4 release by non-owner returns not_claimed_by_you and leaves the claim intact`(): Unit =
        runBlocking {
            val item = createItem("Owned Item")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 900))

            val result = tool.execute(params(releases = listOf(releaseEntry(item.id.toString()))), context())

            val first = firstResult(result, "releaseResults")
            assertEquals(listOf("itemId", "outcome"), first.keys.toList())
            assertEquals("not_claimed_by_you", first["outcome"]!!.jsonPrimitive.content)

            val dbItem = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(dbItem)
            assertEquals(agentOther, dbItem.data.claimedBy)
        }

    // -----------------------------------------------------------------------
    // S5 — releasing an unclaimed item, and claiming/releasing a random full UUID.
    // -----------------------------------------------------------------------

    @Test
    fun `S5a release of a never-claimed item returns not_claimed_by_you`(): Unit =
        runBlocking {
            val item = createItem("Never Claimed")

            val result = tool.execute(params(releases = listOf(releaseEntry(item.id.toString()))), context())

            val first = firstResult(result, "releaseResults")
            assertEquals("not_claimed_by_you", first["outcome"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S5b claiming a random full UUID that does not exist returns not_found`(): Unit =
        runBlocking {
            val randomId = UUID.randomUUID().toString()

            val result = tool.execute(params(claims = listOf(claimEntry(randomId))), context())

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("itemId", "outcome"), first.keys.toList())
            assertEquals("not_found", first["outcome"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S5c releasing a random full UUID that does not exist returns not_found`(): Unit =
        runBlocking {
            val randomId = UUID.randomUUID().toString()

            val result = tool.execute(params(releases = listOf(releaseEntry(randomId))), context())

            val first = firstResult(result, "releaseResults")
            assertEquals(listOf("itemId", "outcome"), first.keys.toList())
            assertEquals("not_found", first["outcome"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S6 — a hex prefix that resolves to no items produces the pinned "Failed to resolve
    // item ID" error string (byte-identical constraint from task-scope).
    // -----------------------------------------------------------------------

    @Test
    fun `S6a claim with an unresolvable id prefix returns not_found with the pinned error and echoes claimRef`(): Unit =
        runBlocking {
            val result =
                tool.execute(
                    params(claims = listOf(claimEntry("abcd1234", claimRef = "r1"))),
                    context()
                )

            val first = firstResult(result, "claimResults")
            assertEquals(listOf("itemId", "outcome", "error", "claimRef"), first.keys.toList())
            assertEquals("abcd1234", first["itemId"]!!.jsonPrimitive.content)
            assertEquals("not_found", first["outcome"]!!.jsonPrimitive.content)
            assertEquals("Failed to resolve item ID: abcd1234", first["error"]!!.jsonPrimitive.content)
            assertEquals("r1", first["claimRef"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S6b release with an unresolvable id prefix returns not_found with the pinned error`(): Unit =
        runBlocking {
            val result = tool.execute(params(releases = listOf(releaseEntry("abcd1234"))), context())

            val first = firstResult(result, "releaseResults")
            assertEquals(listOf("itemId", "outcome", "error"), first.keys.toList())
            assertEquals("Failed to resolve item ID: abcd1234", first["error"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S7 — an entry-level "agentId" field (not part of the documented shape) must not override
    // the actor identity used for the actual claim.
    // -----------------------------------------------------------------------

    @Test
    fun `S7 entry-level agentId field is ignored - claimedBy derives from actor, not the entry`(): Unit =
        runBlocking {
            val item = createItem("Impostor Field Item")
            val entryWithSpuriousAgentId =
                buildJsonObject {
                    put("itemId", item.id.toString())
                    put("agentId", "impostor")
                }

            val result = tool.execute(params(claims = listOf(entryWithSpuriousAgentId)), context())

            val first = firstResult(result, "claimResults")
            assertEquals("success", first["outcome"]!!.jsonPrimitive.content)
            assertEquals(agentSelf, first["claimedBy"]!!.jsonPrimitive.content)

            val dbItem = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(dbItem)
            assertEquals(agentSelf, dbItem.data.claimedBy)
        }

    // -----------------------------------------------------------------------
    // Probes (test-plan "Probes" line): absent claimRef, duplicate release entries,
    // and idempotent replay of a failing (contention) outcome.
    // -----------------------------------------------------------------------

    @Test
    fun `probe absent claimRef omits the claimRef key from a success response`(): Unit =
        runBlocking {
            val item = createItem("No ClaimRef Item")

            val result = tool.execute(params(claims = listOf(claimEntry(item.id.toString()))), context())

            val first = firstResult(result, "claimResults")
            assertFalse(first.containsKey("claimRef"), "claimRef must be absent when not supplied")
        }

    @Test
    fun `probe duplicate release itemId entries are each processed independently`(): Unit =
        runBlocking {
            val item = createItem("Duplicate Release Item")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentSelf, 900))

            val result =
                tool.execute(
                    params(
                        releases = listOf(releaseEntry(item.id.toString()), releaseEntry(item.id.toString()))
                    ),
                    context()
                )

            val data = (result as JsonObject)["data"] as JsonObject
            val releaseResults = data["releaseResults"] as JsonArray
            assertEquals(2, releaseResults.size, "each release entry must produce its own result, duplicates included")
            assertEquals("success", (releaseResults[0] as JsonObject)["outcome"]!!.jsonPrimitive.content)
            // The item is already released by the time the second (duplicate) entry is processed.
            assertEquals(
                "not_claimed_by_you",
                (releaseResults[1] as JsonObject)["outcome"]!!.jsonPrimitive.content
            )
        }

    @Test
    fun `probe replaying a contention request with the same requestId yields identical data`(): Unit =
        runBlocking {
            val item = createItem("Replay Contention Item")
            assertIs<ClaimResult.Success>(repository.claim(item.id, agentOther, 900))

            val requestId = UUID.randomUUID().toString()
            val sharedContext = context()
            val p = params(claims = listOf(claimEntry(item.id.toString())), requestId = requestId)

            val result1 = tool.execute(p, sharedContext)
            val result2 = tool.execute(p, sharedContext)

            assertEquals(
                (result1 as JsonObject)["data"],
                (result2 as JsonObject)["data"],
                "replaying the same request must not disturb or reinterpret unchanged contention state"
            )
        }
}

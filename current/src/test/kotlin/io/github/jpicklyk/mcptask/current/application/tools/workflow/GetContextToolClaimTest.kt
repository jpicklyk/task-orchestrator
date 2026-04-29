package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GetContextTool] tiered claim disclosure.
 *
 * Per the v1 disclosure matrix:
 * - Item mode: full claim detail (claimedBy, claimedAt, claimExpiresAt, isExpired) — diagnostic only
 * - Health-check mode: claimSummary { active, expired } — fleet health signal (no identity)
 * - Session-resume mode: no claim info
 *
 * Uses mocked repositories for precision — one-item tests need exact control over claim fields.
 */
class GetContextToolClaimTest {
    private lateinit var tool: GetContextTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = GetContextTool()
        workItemRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.dependencyRepository() } returns mockk()

        context = ToolExecutionContext(repoProvider, NoOpNoteSchemaService)
    }

    private fun params(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*pairs))

    private fun makeClaimedItem(
        id: UUID = UUID.randomUUID(),
        agentId: String = "test-agent-abc",
        expired: Boolean = false
    ): WorkItem {
        val now = Instant.now()
        val expiresAt = if (expired) now.minusSeconds(60) else now.plusSeconds(900)
        return WorkItem(
            id = id,
            title = "Claimed Item",
            role = Role.WORK,
            claimedBy = agentId,
            claimedAt = now.minusSeconds(300),
            claimExpiresAt = expiresAt,
            originalClaimedAt = now.minusSeconds(300)
        )
    }

    private fun makeUnclaimedItem(id: UUID = UUID.randomUUID()) = WorkItem(id = id, title = "Unclaimed Item", role = Role.WORK)

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    // Small helper — avoids casting ambiguity
    private suspend fun execute(vararg pairs: Pair<String, JsonPrimitive>) = tool.execute(JsonObject(mapOf(*pairs)), context)

    // ──────────────────────────────────────────────
    // Item mode — full claim detail when claimed
    // ──────────────────────────────────────────────

    @Test
    fun `item mode includes full claimDetail when item is claimed`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val claimed = makeClaimedItem(id = itemId, agentId = "my-agent-id")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(claimed)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val data = extractData(execute("itemId" to JsonPrimitive(itemId.toString())))

            val claimDetail = data["claimDetail"]?.jsonObject
            assertNotNull(claimDetail, "claimDetail must be present for a claimed item in item mode")
            assertEquals("my-agent-id", claimDetail["claimedBy"]?.jsonPrimitive?.content)
            assertNotNull(claimDetail["claimedAt"])
            assertNotNull(claimDetail["claimExpiresAt"])
            assertFalse(
                claimDetail["isExpired"]!!.jsonPrimitive.boolean,
                "isExpired should be false for a non-expired claim"
            )
        }

    @Test
    fun `item mode isExpired=true when claim is past TTL`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val expired = makeClaimedItem(id = itemId, agentId = "stale-agent", expired = true)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(expired)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val data = extractData(execute("itemId" to JsonPrimitive(itemId.toString())))

            val claimDetail = data["claimDetail"]?.jsonObject
            assertNotNull(claimDetail)
            assertTrue(
                claimDetail["isExpired"]!!.jsonPrimitive.boolean,
                "isExpired should be true when claimExpiresAt is in the past"
            )
        }

    @Test
    fun `item mode has NO claimDetail when item is unclaimed`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val unclaimed = makeUnclaimedItem(id = itemId)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(unclaimed)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val data = extractData(execute("itemId" to JsonPrimitive(itemId.toString())))

            assertNull(data["claimDetail"], "claimDetail must be absent when item is unclaimed")
        }

    @Test
    fun `item mode includes originalClaimedAt in claimDetail`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val originalTime = Instant.now().minusSeconds(3600) // 1 hour ago — first claim
            val item =
                WorkItem(
                    id = itemId,
                    title = "Re-claimed item",
                    role = Role.WORK,
                    claimedBy = "persistent-agent",
                    claimedAt = Instant.now().minusSeconds(300),
                    claimExpiresAt = Instant.now().plusSeconds(600),
                    originalClaimedAt = originalTime
                )

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val data = extractData(execute("itemId" to JsonPrimitive(itemId.toString())))

            val claimDetail = data["claimDetail"]?.jsonObject
            assertNotNull(claimDetail)
            assertNotNull(claimDetail["originalClaimedAt"], "originalClaimedAt should be present in claimDetail")
        }

    @Test
    fun `item mode claimDetail time fields serialize as UTC ISO-8601 with Z suffix`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val claimedAtInstant = Instant.parse("2025-06-15T10:30:00Z")
            val claimExpiresAtInstant = Instant.parse("2025-06-15T10:45:00Z")
            val item =
                WorkItem(
                    id = itemId,
                    title = "UTC round-trip item",
                    role = Role.WORK,
                    claimedBy = "agent-utc-check",
                    claimedAt = claimedAtInstant,
                    claimExpiresAt = claimExpiresAtInstant,
                    originalClaimedAt = claimedAtInstant
                )

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val data = extractData(execute("itemId" to JsonPrimitive(itemId.toString())))

            val claimDetail = data["claimDetail"]?.jsonObject
            assertNotNull(claimDetail, "claimDetail must be present for a claimed item")

            // claimExpiresAt assertions
            val expiresAtStr = claimDetail["claimExpiresAt"]?.jsonPrimitive?.content
            assertNotNull(expiresAtStr, "claimExpiresAt must be present")
            assertTrue(
                expiresAtStr.endsWith("Z"),
                "claimExpiresAt must end with 'Z' (UTC indicator), got: $expiresAtStr"
            )
            assertFalse(
                expiresAtStr.contains("+") || expiresAtStr.matches(Regex(".*[+-]\\d{2}:\\d{2}$")),
                "claimExpiresAt must not contain a local-tz offset, got: $expiresAtStr"
            )
            val parsedExpiresAt = Instant.parse(expiresAtStr) // throws if not valid ISO-8601
            assertEquals(
                claimExpiresAtInstant.epochSecond,
                parsedExpiresAt.epochSecond,
                "claimExpiresAt round-trip must match original value at seconds precision"
            )

            // claimedAt assertions
            val claimedAtStr = claimDetail["claimedAt"]?.jsonPrimitive?.content
            assertNotNull(claimedAtStr, "claimedAt must be present")
            assertTrue(
                claimedAtStr.endsWith("Z"),
                "claimedAt must end with 'Z' (UTC indicator), got: $claimedAtStr"
            )
            assertFalse(
                claimedAtStr.contains("+") || claimedAtStr.matches(Regex(".*[+-]\\d{2}:\\d{2}$")),
                "claimedAt must not contain a local-tz offset, got: $claimedAtStr"
            )
            val parsedClaimedAt = Instant.parse(claimedAtStr) // throws if not valid ISO-8601
            assertEquals(
                claimedAtInstant.epochSecond,
                parsedClaimedAt.epochSecond,
                "claimedAt round-trip must match original value at seconds precision"
            )
        }

    // ──────────────────────────────────────────────
    // Health-check mode — claimSummary (counts only)
    // ──────────────────────────────────────────────

    @Test
    fun `health-check mode includes claimSummary with active and expired counts`(): Unit =
        runBlocking {
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.BLOCKED, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.countByClaimStatus(null) } returns
                Result.Success(ClaimStatusCounts(active = 3, expired = 1, unclaimed = 42))

            val data = extractData(execute()) // no params → health-check mode

            val claimSummary = data["claimSummary"]?.jsonObject
            assertNotNull(claimSummary, "claimSummary must be present in health-check mode")
            assertEquals(3, claimSummary["active"]?.jsonPrimitive?.int)
            assertEquals(1, claimSummary["expired"]?.jsonPrimitive?.int)
        }

    @Test
    fun `health-check mode claimSummary does NOT include claimedBy identity`(): Unit =
        runBlocking {
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.BLOCKED, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.countByClaimStatus(null) } returns
                Result.Success(ClaimStatusCounts(active = 2, expired = 0, unclaimed = 10))

            val result = execute()
            val serialized = result.toString()

            assertFalse(
                "claimedBy" in serialized,
                "Health-check mode must NEVER expose claimedBy identity"
            )
        }

    @Test
    fun `health-check mode works when countByClaimStatus fails gracefully`(): Unit =
        runBlocking {
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.BLOCKED, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.countByClaimStatus(null) } returns
                Result.Error(RepositoryError.DatabaseError("simulated failure"))

            // Should still succeed — claim summary is additive, not critical
            val result = execute()
            val obj = result as JsonObject
            assertTrue(obj["success"]!!.jsonPrimitive.boolean)

            // claimSummary may be absent if the query failed
            val data = obj["data"] as JsonObject
            assertEquals("health-check", data["mode"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Session-resume mode — no claim info
    // ──────────────────────────────────────────────

    @Test
    fun `session-resume mode does NOT include claimSummary or claimDetail`(): Unit =
        runBlocking {
            val since = Instant.now().minusSeconds(3600)
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { roleTransitionRepo.findSince(any(), limit = any()) } returns Result.Success(emptyList())

            val data = extractData(execute("since" to JsonPrimitive(since.toString())))

            assertEquals("session-resume", data["mode"]!!.jsonPrimitive.content)
            assertNull(data["claimSummary"], "Session-resume mode must NOT include claimSummary")
            assertNull(data["claimDetail"], "Session-resume mode must NOT include claimDetail")

            // Verify the serialized response doesn't leak identity
            val serialized = data.toString()
            assertFalse("claimedBy" in serialized, "claimedBy must NOT appear in session-resume mode")
        }
}

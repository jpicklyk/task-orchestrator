package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.actorArg
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.claimOp
import io.github.jpicklyk.mcptask.current.test.envelopeOk
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sansReplay
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.transitionOp
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for per-element `idempotencyKey` wiring on the mutating tools (Q32: the 3.x `requestId` of advance_item and
 * claim_item is gone; their keyed paths are the `advance` tool's elements).
 *
 * Each test verifies:
 * 1. First call with (actor, requestId) executes the operation and stores the result.
 * 2. Second call with the same (actor, requestId) returns the cached response without re-executing.
 *    Verified by checking that mutating side-effects (e.g., new IDs) match between calls.
 * 3. Different requestId or different actor produces a fresh execution.
 * 4. No requestId means no cache interaction (current behavior preserved).
 */
class IdempotencyToolsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        repositoryProvider = db.repositoryProvider()
        context =
            ToolExecutionContext(
                repositoryProvider = repositoryProvider,
                unitOfWork = db.unitOfWork()
            )
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun actor(id: String = "test-agent") =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("kind", JsonPrimitive("subagent"))
        }

    private suspend fun createTestItem(title: String = "Test Item"): UUID {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return result.id
    }

    // ──────────────────────────────────────────────
    // notes_write — upsert idempotency (the per-element keyed path of the 4.0 write tools)
    // ──────────────────────────────────────────────

    @Test
    fun `notes_write upsert caches response on idempotencyKey`() =
        runBlocking {
            val itemId = createTestItem()
            val key = UUID.randomUUID().toString()

            val first =
                context.upsertNoteVia(
                    itemId,
                    "approach",
                    "work",
                    "First body",
                    actor = actorArg("test-agent"),
                    idempotencyKey = key
                )
            val second =
                context.upsertNoteVia(
                    itemId,
                    "approach",
                    "work",
                    "First body",
                    actor = actorArg("test-agent"),
                    idempotencyKey = key
                )

            assertTrue(envelopeOk(first), "first call: $first")
            assertTrue("\"replayed\":true" in second.toString(), "the second call is served from the stored record: $second")
            assertEquals(
                first["data"]!!.jsonObject["results"]!!.jsonArray[0].jsonObject["version"],
                second["data"]!!.jsonObject["results"]!!.jsonArray[0].jsonObject["version"],
                "a replay returns the stored version, it does not bump it"
            )

            // Verify the note body was set ONCE: the second call did not run
            val notes = context.noteRepository().findByItemId(itemId)
            assertEquals(1, notes.size)
            assertEquals("First body", notes[0].body, "Cached call must not have overwritten the body")
            assertEquals(1L, notes[0].version, "the replay did not write")
        }

    @Test
    fun `notes_write without idempotencyKey skips cache`() =
        runBlocking {
            val itemId = createTestItem()

            context.upsertNoteVia(itemId, "approach", "work", "v1")
            context.upsertNoteVia(itemId, "approach", "work", "v2")

            assertEquals(0, db.idempotencyRecordCount())
            val notes = context.noteRepository().findByItemId(itemId)
            assertEquals("v2", notes[0].body, "Without a key, second call must overwrite")
            assertEquals(2L, notes[0].version, "each unkeyed write bumps the version")
        }

    // ──────────────────────────────────────────────
    // advance — transition and claim idempotency (per-element idempotencyKey; the 3.x requestId is gone)
    // ──────────────────────────────────────────────

    private fun keyed(key: String) = "idempotencyKey" to JsonPrimitive(key)

    @Test
    fun `advance transition caches the element on idempotencyKey`() =
        runBlocking {
            val itemId = createTestItem()
            val key = UUID.randomUUID().toString()

            val first = context.advanceTool(transitionOp(itemId, "start", keyed(key)), actor = actor())
            val second = context.advanceTool(transitionOp(itemId, "start", keyed(key)), actor = actor())

            assertTrue(envelopeOk(first), "first call: $first")
            assertTrue("\"replayed\":true" in second.toString(), "the second call is served from the stored record: $second")
            // Cached response is identical
            assertEquals(first.sansReplay(), second.sansReplay())
            assertEquals(1, db.idempotencyRecordCount())
            assertEquals(Role.WORK, context.workItemRepository().getById(itemId)!!.role, "the start ran once")
        }

    /**
     * TEST-I10a: a keyed claim returns the stored element on retry, no double-mutation. The claim instant of the first
     * call survives the retry (a re-executed claim by the holder would be a refresh).
     */
    @Test
    fun `advance claim with idempotencyKey returns the stored element on retry, no double-mutation`() =
        runBlocking {
            val itemId = createTestItem("Test Claim Item")
            val key = UUID.randomUUID().toString()
            val agentId = "agent-claimer"

            val first = context.advanceTool(claimOp(itemId, "ttlSeconds" to JsonPrimitive(900), keyed(key)), actor = actor(agentId))
            assertTrue(first.okResult(0).isNotEmpty(), "first call succeeds: $first")
            val claimedAt = context.workItemRepository().getById(itemId)!!.claimedAt

            val second = context.advanceTool(claimOp(itemId, "ttlSeconds" to JsonPrimitive(900), keyed(key)), actor = actor(agentId))

            assertEquals(first.sansReplay(), second.sansReplay(), "the retry is the stored element")
            assertTrue("\"replayed\":true" in second.toString(), "$second")
            assertEquals(1, db.idempotencyRecordCount())
            assertEquals(claimedAt, context.workItemRepository().getById(itemId)!!.claimedAt, "the claim was placed exactly once")
        }

    // ──────────────────────────────────────────────
    // C3: Concurrent-retry race test (bug 3b — getOrCompute atomicity)
    //
    // Fires N concurrent coroutines with the same (actor, key). Only ONE underlying mutation executes; all others receive
    // the stored element.
    // ──────────────────────────────────────────────

    /** TEST-C3b: concurrent keyed retries of one `advance` transition: only one transition executes. */
    @Test
    fun `advance keyed element prevents a concurrent double-transition race`(): Unit =
        runBlocking {
            val itemId = createTestItem("Race Item")
            val key = UUID.randomUUID().toString()

            val results =
                (1..10)
                    .map {
                        async(Dispatchers.IO) {
                            context.advanceTool(transitionOp(itemId, "start", keyed(key)), actor = actor("race-agent"))
                        }
                    }.awaitAll()

            // All responses should be identical (stored)
            val firstResponse = results[0]
            for (result in results) {
                assertEquals(
                    firstResponse.sansReplay(),
                    result.sansReplay(),
                    "All concurrent retries must return the identical stored element"
                )
            }

            // Item should be in WORK (transitioned exactly once)
            val item = context.workItemRepository().getById(itemId)
            assertNotNull(item)
            assertEquals(Role.WORK, item.role)

            assertEquals(1, db.idempotencyRecordCount())
        }

    // ──────────────────────────────────────────────
    // C3: Identity-resolution-before-cache test (bug 3a — principal id as the record key)
    //
    // Two calls with the SAME key but DIFFERENT actors must get DIFFERENT records.
    // ──────────────────────────────────────────────

    /** TEST-C3c: the element record is keyed under the call's actor, so two actors never share one. */
    @Test
    fun `advance keyed elements are stored per actor, never shared across actors`(): Unit =
        runBlocking {
            val itemId = createTestItem("Identity Test Item")
            val sharedKey = UUID.randomUUID().toString()

            val first = context.advanceTool(transitionOp(itemId, "start", keyed(sharedKey)), actor = actor("victim-agent"))
            // The same key under another actor executes afresh (the item is already in WORK, so this start moves it on).
            val second = context.advanceTool(transitionOp(itemId, "start", keyed(sharedKey)), actor = actor("attacker-agent"))

            assertNotEquals(
                first.sansReplay(),
                second.sansReplay(),
                "Different actor ids must produce different records even with the same key"
            )
            assertTrue("\"replayed\":true" !in second.toString(), "the second actor is not served the first actor's record: $second")

            // Two separate records (one per actor)
            assertEquals(2, db.idempotencyRecordCount(), "Each actor gets its own record")
        }
}

package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sansReplay
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item 71dea46e (frozen test-plan note `b703941a`):
 * a malformed `requestId` on the remaining 3.x mutating tool (advance_item; manage_items, manage_notes,
 * manage_dependencies, complete_tree and create_work_tree were replaced by items_write, notes_write and deps_write,
 * which take a per-element idempotencyKey instead) must be rejected with a
 * validation error naming `requestId`, with NOTHING written and the idempotency cache left
 * empty. A valid `requestId` must still replay idempotently. (The `create_work_tree` requestId-without-actor and
 * malformed-actor guards, S4/S5, left with that tool in Q21.)
 *
 * Oracles (see test-plan for full citations):
 * - O1 api-reference.md Idempotency section (as revised: "rejected at validation", not "silently
 *   ignored").
 * - O2 `ClaimItemTool.kt` existing malformed/missing requestId messages (untouched by this fix).
 * - O5 `UUID.fromString` javadoc contract (8-4-4-4-12 hex, case-insensitive; rejects braces,
 *   `urn:` prefixes, and wrong lengths).
 *
 * Scenarios S1-S10 live here. S11 needed an `operation` parameter, which no remaining 3.x write tool has; S12 (the REST
 * `Idempotency-Key` parity) went with the removed REST item and note write routes.
 *
 * Per the test-author blindness rule, these tests drive the NEW
 * `BaseToolDefinition.validateRequestIdParam` exclusively through the public `validateParams` /
 * `execute` surface — it is `protected` and is never called directly.
 */
class RequestIdValidationTest {
    // Dynamic tests of one @TestFactory share a single @BeforeEach, so each freshContext() opens its own
    // isolated database (one per dynamic test, as before) and @AfterEach closes them all.
    private val openDatabases = mutableListOf<SqliteTestDatabase>()
    private val databaseOf = java.util.IdentityHashMap<ToolExecutionContext, SqliteTestDatabase>()

    @AfterEach
    fun closeDatabases() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixture plumbing
    // ──────────────────────────────────────────────────────────────────

    /**
     * One of the six mutating tools under test, paired with a builder that produces:
     * 1. a fully valid `params` object (minus `requestId`) for that tool's minimal happy path,
     *    including a top-level `actor` (or, for `advance_item`, a per-transition actor) built
     *    from the given `actorId`, and
     * 2. a `mutated()` probe — a fresh read against the SAME [ToolExecutionContext] that reports
     *    whether the operation's characteristic side effect has now happened. Calling it before
     *    and after an attempted call is how each scenario proves "nothing was written" or "the
     *    call actually executed" without ever peeking at the tool's own return value as an oracle.
     */
    private data class ToolCase(
        val label: String,
        val tool: BaseToolDefinition,
        val build: suspend (ToolExecutionContext, String) -> Pair<JsonObject, suspend () -> Boolean>
    )

    private fun actorJson(id: String): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("kind", JsonPrimitive("subagent"))
        }

    private suspend fun createItem(
        context: ToolExecutionContext,
        title: String
    ): UUID {
        val result = context.workItemRepository().create(WorkItem(title = title))
        return result.id
    }

    /** Returns a copy of this object with `requestId` replaced (or removed, if [value] is null). */
    private fun JsonObject.withRequestId(value: JsonElement?): JsonObject =
        buildJsonObject {
            for ((k, v) in this@withRequestId) {
                if (k != "requestId") put(k, v)
            }
            if (value != null) put("requestId", value)
        }

    /** Returns a copy of this object with the `actor` key removed entirely. */
    private fun JsonObject.withoutActor(): JsonObject =
        buildJsonObject {
            for ((k, v) in this@withoutActor) {
                if (k != "actor") put(k, v)
            }
        }

    /** A fresh, isolated SQLite-backed [ToolExecutionContext] — mirrors [IdempotencyToolsTest]'s setUp. */
    private fun freshContext(): ToolExecutionContext {
        val db = SqliteTestDatabase.open().also { openDatabases += it }
        return ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork()).also { databaseOf[it] = db }
    }

    private fun toolCases(): List<ToolCase> =
        listOf(
            ToolCase("advance_item", AdvanceItemTool()) { context, actorId ->
                val itemId = createItem(context, "Advance Target ${UUID.randomUUID()}")
                val params =
                    buildJsonObject {
                        put(
                            "transitions",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId.toString()))
                                        put("trigger", JsonPrimitive("start"))
                                        put("actor", actorJson(actorId))
                                    }
                                )
                            )
                        )
                    }
                val check: suspend () -> Boolean = {
                    val res = context.workItemRepository().getById(itemId)
                    res?.role != Role.QUEUE
                }
                params to check
            }
        )

    // ──────────────────────────────────────────────────────────────────
    // S1 — happy path: valid requestId + valid actor replays idempotently
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S1 valid requestId and actor replays cached response on identical retry`(): List<DynamicTest> =
        toolCases().map { case ->
            dynamicTest(case.label) {
                runBlocking {
                    val context = freshContext()
                    val (baseParams, mutated) = case.build(context, "agent-1")
                    val params = baseParams.withRequestId(JsonPrimitive(UUID.randomUUID().toString()))
                    assertFalse(mutated(), "${case.label}: precondition — nothing mutated before the call")

                    val first = case.tool.execute(params, context)
                    assertTrue(mutated(), "${case.label}: first call must have executed the mutation")

                    val second = case.tool.execute(params, context)
                    assertEquals(
                        first.sansReplay(),
                        second.sansReplay(),
                        "${case.label}: an identical retry with the same requestId must replay the cached response verbatim"
                    )
                    assertEquals(
                        1,
                        databaseOf.getValue(context).idempotencyRecordCount(),
                        "${case.label}: exactly one cache entry — the retry must not re-execute"
                    )
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S2 — failure: malformed requestId is rejected; nothing written
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S2 malformed requestId is rejected naming the field and nothing is written`(): List<DynamicTest> =
        toolCases().map { case ->
            dynamicTest(case.label) {
                runBlocking {
                    val context = freshContext()
                    val (baseParams, mutated) = case.build(context, "agent-1")
                    val params = baseParams.withRequestId(JsonPrimitive("not-a-uuid"))

                    val ex = assertThrows<ToolValidationException> { case.tool.validateParams(params) }
                    assertTrue(
                        ex.message!!.contains("requestId"),
                        "${case.label}: message must name requestId, got: ${ex.message}"
                    )
                    assertFalse(mutated(), "${case.label}: a rejected call must not have written anything")
                    assertEquals(
                        0,
                        databaseOf.getValue(context).idempotencyRecordCount(),
                        "${case.label}: cache must stay empty on rejection"
                    )
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S3 — happy path: requestId absent -> fresh execute, no error, cache stays empty
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S3 absent requestId executes fresh with no error and no cache entry`(): List<DynamicTest> =
        toolCases().map { case ->
            dynamicTest(case.label) {
                runBlocking {
                    val context = freshContext()
                    val (baseParams, mutated) = case.build(context, "agent-1")
                    // baseParams intentionally carries no "requestId" key at all.
                    assertFalse(mutated(), "${case.label}: precondition — nothing mutated before the call")

                    case.tool.execute(baseParams, context)

                    assertTrue(mutated(), "${case.label}: call without requestId must still execute")
                    assertEquals(
                        0,
                        databaseOf.getValue(context).idempotencyRecordCount(),
                        "${case.label}: absent requestId must never populate the cache"
                    )
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S6 — regression (claim_item, untouched by this fix): existing messages unchanged
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `S6 claim_item malformed requestId message is unchanged (regression)`() {
        val tool = ClaimItemTool()
        val params =
            buildJsonObject {
                put(
                    "claims",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(UUID.randomUUID().toString()))
                                put("ttlSeconds", JsonPrimitive(900))
                            }
                        )
                    )
                )
                put("actor", actorJson("agent-1"))
                put("requestId", JsonPrimitive("not-a-uuid"))
            }

        val ex = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(
            ex.message!!.contains("requestId must be a valid UUID"),
            "claim_item's existing malformed-requestId message must be unchanged, got: ${ex.message}"
        )
    }

    @Test
    fun `S6 claim_item missing requestId message is unchanged (regression)`() {
        val tool = ClaimItemTool()
        val params =
            buildJsonObject {
                put(
                    "claims",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(UUID.randomUUID().toString()))
                                put("ttlSeconds", JsonPrimitive(900))
                            }
                        )
                    )
                )
                put("actor", actorJson("agent-1"))
            }

        val ex = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(
            ex.message!!.contains("requestId is required"),
            "claim_item's existing missing-requestId message must be unchanged, got: ${ex.message}"
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // S7 — edge (xT6): UPPERCASE UUID accepted; lowercase replay hits the SAME cache entry
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S7 case-insensitive requestId replay hits the same cache entry`(): List<DynamicTest> =
        toolCases().map { case ->
            dynamicTest(case.label) {
                runBlocking {
                    val context = freshContext()
                    val (baseParams, _) = case.build(context, "agent-1")
                    val raw = UUID.randomUUID().toString()
                    val upper = raw.uppercase()
                    val lower = raw.lowercase()

                    val first = case.tool.execute(baseParams.withRequestId(JsonPrimitive(upper)), context)
                    val second = case.tool.execute(baseParams.withRequestId(JsonPrimitive(lower)), context)

                    assertEquals(
                        first.sansReplay(),
                        second.sansReplay(),
                        "${case.label}: an uppercase requestId and its lowercase spelling must hit the same cache slot"
                    )
                    assertEquals(
                        1,
                        databaseOf.getValue(context).idempotencyRecordCount(),
                        "${case.label}: only one cache entry for the pair"
                    )
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S8 — edge (xT6): structurally-invalid UUID forms are rejected
    // ──────────────────────────────────────────────────────────────────

    private fun malformedUuidForms(valid: String): List<Pair<String, String>> =
        listOf(
            "braced" to "{$valid}",
            "urn-prefixed" to "urn:uuid:$valid",
            "hyphen-stripped-32-char" to valid.replace("-", ""),
            "35-char-truncated" to valid.dropLast(1),
            "37-char-padded" to "${valid}0"
        )

    @TestFactory
    fun `S8 structurally-invalid UUID forms are rejected`(): List<DynamicTest> {
        val valid = UUID.randomUUID().toString()
        val forms = malformedUuidForms(valid)
        return toolCases().flatMap { case ->
            forms.map { (formLabel, value) ->
                dynamicTest("${case.label} / $formLabel") {
                    runBlocking {
                        val context = freshContext()
                        val (baseParams, mutated) = case.build(context, "agent-1")
                        val params = baseParams.withRequestId(JsonPrimitive(value))

                        val ex = assertThrows<ToolValidationException> { case.tool.validateParams(params) }
                        assertTrue(
                            ex.message!!.contains("requestId"),
                            "${case.label}/$formLabel: message must name requestId, got: ${ex.message}"
                        )
                        assertFalse(mutated(), "${case.label}/$formLabel: must not write")
                        assertEquals(
                            0,
                            databaseOf.getValue(context).idempotencyRecordCount(),
                            "${case.label}/$formLabel: cache must stay empty"
                        )
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // S9 — edge (xT6): blank / whitespace-only requestId rejected as malformed, not absent
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S9 blank and whitespace-only requestId are rejected as malformed, not treated as absent`(): List<DynamicTest> =
        toolCases().flatMap { case ->
            listOf("" to "empty-string", "   " to "whitespace-only").map { (value, formLabel) ->
                dynamicTest("${case.label} / $formLabel") {
                    runBlocking {
                        val context = freshContext()
                        val (baseParams, mutated) = case.build(context, "agent-1")
                        val params = baseParams.withRequestId(JsonPrimitive(value))

                        val ex = assertThrows<ToolValidationException> { case.tool.validateParams(params) }
                        assertTrue(
                            ex.message!!.contains("requestId"),
                            "${case.label}/$formLabel: message must name requestId, got: ${ex.message}"
                        )
                        assertFalse(mutated(), "${case.label}/$formLabel: must not write")
                        assertEquals(
                            0,
                            databaseOf.getValue(context).idempotencyRecordCount(),
                            "${case.label}/$formLabel: cache must stay empty"
                        )
                    }
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S10 — edge (advance_item): non-string JSON shapes for requestId are all rejected
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S10 advance_item rejects non-string requestId JSON shapes`(): List<DynamicTest> {
        val case = toolCases().first { it.label == "advance_item" }
        val forms: List<Pair<String, JsonElement>> =
            listOf(
                "number" to JsonPrimitive(123),
                "json-null" to JsonNull,
                "empty-object" to JsonObject(emptyMap()),
                "empty-array" to JsonArray(emptyList())
            )
        return forms.map { (formLabel, value) ->
            dynamicTest(formLabel) {
                runBlocking {
                    val context = freshContext()
                    val (baseParams, mutated) = case.build(context, "agent-1")
                    val params = baseParams.withRequestId(value)

                    val ex = assertThrows<ToolValidationException> { case.tool.validateParams(params) }
                    assertTrue(
                        ex.message!!.contains("requestId"),
                        "$formLabel: message must name requestId, got: ${ex.message}"
                    )
                    assertFalse(mutated(), "$formLabel: must not write")
                    assertEquals(0, databaseOf.getValue(context).idempotencyRecordCount(), "$formLabel: cache must stay empty")
                }
            }
        }
    }
}

package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item 71dea46e (frozen test-plan note `b703941a`):
 * a malformed `requestId` on any of the six mutating tools (manage_items, manage_notes,
 * manage_dependencies, advance_item, complete_tree, create_work_tree) must be rejected with a
 * validation error naming `requestId`, with NOTHING written and the idempotency cache left
 * empty. A valid `requestId` must still replay idempotently. `requestId`-without-actor and the
 * `create_work_tree` malformed-actor contract are UNCHANGED (guard regressions, not new gates).
 *
 * Oracles (see test-plan for full citations):
 * - O1 api-reference.md Idempotency section (as revised: "rejected at validation", not "silently
 *   ignored").
 * - O2 `ClaimItemTool.kt` existing malformed/missing requestId messages (untouched by this fix).
 * - O5 `UUID.fromString` javadoc contract (8-4-4-4-12 hex, case-insensitive; rejects braces,
 *   `urn:` prefixes, and wrong lengths).
 *
 * Scenarios S1-S11 live here; S12 (REST `Idempotency-Key` parity) lives in
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.IdempotencyKeyRejectionTest].
 *
 * Per the test-author blindness rule, these tests drive the NEW
 * `BaseToolDefinition.validateRequestIdParam` exclusively through the public `validateParams` /
 * `execute` surface — it is `protected` and is never called directly.
 */
class RequestIdValidationTest {
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
        return (result as Result.Success).data.id
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

    private val dbCounter = AtomicInteger(0)

    /** A fresh, isolated H2-backed [ToolExecutionContext] — mirrors [IdempotencyToolsTest]'s setUp. */
    private fun freshContext(): ToolExecutionContext {
        val dbName = "test_ridval_${System.nanoTime()}_${dbCounter.incrementAndGet()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        return ToolExecutionContext(repositoryProvider = DefaultRepositoryProvider(databaseManager))
    }

    private fun toolCases(): List<ToolCase> =
        listOf(
            ToolCase("manage_items", ManageItemsTool()) { context, actorId ->
                val baseline = (context.workItemRepository().findRootItems() as Result.Success).data.items.size
                val params =
                    buildJsonObject {
                        put("operation", JsonPrimitive("create"))
                        put(
                            "items",
                            JsonArray(
                                listOf(buildJsonObject { put("title", JsonPrimitive("RID-${UUID.randomUUID()}")) })
                            )
                        )
                        put("actor", actorJson(actorId))
                    }
                val check: suspend () -> Boolean = {
                    val count = (context.workItemRepository().findRootItems() as Result.Success).data.items.size
                    count > baseline
                }
                params to check
            },
            ToolCase("manage_notes", ManageNotesTool()) { context, actorId ->
                val itemId = createItem(context, "Notes Target ${UUID.randomUUID()}")
                val params =
                    buildJsonObject {
                        put("operation", JsonPrimitive("upsert"))
                        put(
                            "notes",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId.toString()))
                                        put("key", JsonPrimitive("approach"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("body"))
                                    }
                                )
                            )
                        )
                        put("actor", actorJson(actorId))
                    }
                val check: suspend () -> Boolean = {
                    val notes = context.noteRepository().findByItemId(itemId)
                    (notes as? Result.Success)?.data?.isNotEmpty() == true
                }
                params to check
            },
            ToolCase("manage_dependencies", ManageDependenciesTool()) { context, actorId ->
                val from = createItem(context, "Dep From ${UUID.randomUUID()}")
                val to = createItem(context, "Dep To ${UUID.randomUUID()}")
                val params =
                    buildJsonObject {
                        put("operation", JsonPrimitive("create"))
                        put(
                            "dependencies",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(from.toString()))
                                        put("toItemId", JsonPrimitive(to.toString()))
                                    }
                                )
                            )
                        )
                        put("actor", actorJson(actorId))
                    }
                val check: suspend () -> Boolean = {
                    context.dependencyRepository().findByFromItemId(from).isNotEmpty()
                }
                params to check
            },
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
                    (res as? Result.Success)?.data?.role != Role.QUEUE
                }
                params to check
            },
            ToolCase("complete_tree", CompleteTreeTool()) { context, actorId ->
                val itemId = createItem(context, "Complete Target ${UUID.randomUUID()}")
                val params =
                    buildJsonObject {
                        put("itemIds", JsonArray(listOf(JsonPrimitive(itemId.toString()))))
                        put("trigger", JsonPrimitive("cancel"))
                        put("actor", actorJson(actorId))
                    }
                val check: suspend () -> Boolean = {
                    val res = context.workItemRepository().getById(itemId)
                    (res as? Result.Success)?.data?.role != Role.QUEUE
                }
                params to check
            },
            ToolCase("create_work_tree", CreateWorkTreeTool()) { context, actorId ->
                val baseline = (context.workItemRepository().findRootItems() as Result.Success).data.items.size
                val params =
                    buildJsonObject {
                        put("root", buildJsonObject { put("title", JsonPrimitive("Tree Root ${UUID.randomUUID()}")) })
                        put("actor", actorJson(actorId))
                    }
                val check: suspend () -> Boolean = {
                    val count = (context.workItemRepository().findRootItems() as Result.Success).data.items.size
                    count > baseline
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
                        first,
                        second,
                        "${case.label}: an identical retry with the same requestId must replay the cached response verbatim"
                    )
                    assertEquals(
                        1,
                        context.idempotencyCache.size(),
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
                    assertEquals(0, context.idempotencyCache.size(), "${case.label}: cache must stay empty on rejection")
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
                        context.idempotencyCache.size(),
                        "${case.label}: absent requestId must never populate the cache"
                    )
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S4 — guard (manage_items, create_work_tree): valid requestId, actor ABSENT -> unchanged
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S4 requestId without actor does not enable idempotency (guard unchanged)`(): List<DynamicTest> =
        listOf("manage_items", "create_work_tree").map { label ->
            dynamicTest(label) {
                runBlocking {
                    val context = freshContext()
                    val case = toolCases().first { it.label == label }
                    val (baseParams, _) = case.build(context, "agent-1")
                    val paramsNoActor = baseParams.withoutActor()
                    val requestId = UUID.randomUUID().toString()
                    val params = paramsNoActor.withRequestId(JsonPrimitive(requestId))

                    case.tool.execute(params, context)
                    case.tool.execute(params, context)

                    val allItems = (context.workItemRepository().findRootItems() as Result.Success).data.items
                    assertEquals(
                        2,
                        allItems.size,
                        "$label: without an actor, requestId must NOT enable idempotency — both calls must execute"
                    )
                    assertEquals(0, context.idempotencyCache.size(), "$label: cache must stay empty without an actor")
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S5 — guard (create_work_tree): valid requestId + MALFORMED actor -> succeeds, idempotency off
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `S5 create_work_tree with malformed actor succeeds twice with idempotency disabled`() =
        runBlocking {
            val context = freshContext()
            val case = toolCases().first { it.label == "create_work_tree" }
            val (baseParams, _) = case.build(context, "agent-1")
            // A structurally-present actor object with an invalid `kind` enum value — malformed,
            // but NOT absent, per the parameterSchema's documented actor contract ("kind (required:
            // orchestrator|subagent|user|external)"). Not-covered: this test does not independently
            // verify the "attribution drops to null" half of the O4 citation, since observing note
            // attribution requires createNotes=true, which is outside this scenario's minimal shape.
            val paramsMalformedActor =
                buildJsonObject {
                    for ((k, v) in baseParams) {
                        if (k != "actor") put(k, v)
                    }
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", JsonPrimitive("agent-1"))
                            put("kind", JsonPrimitive("not-a-real-kind"))
                        }
                    )
                }
            val requestId = UUID.randomUUID().toString()
            val params = paramsMalformedActor.withRequestId(JsonPrimitive(requestId))

            case.tool.execute(params, context)
            case.tool.execute(params, context)

            val allItems = (context.workItemRepository().findRootItems() as Result.Success).data.items
            assertEquals(
                2,
                allItems.size,
                "create_work_tree: a malformed actor must disable idempotency — both calls must succeed and create a tree"
            )
            assertEquals(0, context.idempotencyCache.size(), "create_work_tree: cache must stay empty with a malformed actor")
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
                        first,
                        second,
                        "${case.label}: an uppercase requestId and its lowercase spelling must hit the same cache slot"
                    )
                    assertEquals(1, context.idempotencyCache.size(), "${case.label}: only one cache entry for the pair")
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
                        assertEquals(0, context.idempotencyCache.size(), "${case.label}/$formLabel: cache must stay empty")
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
                        assertEquals(0, context.idempotencyCache.size(), "${case.label}/$formLabel: cache must stay empty")
                    }
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // S10 — edge (manage_items): non-string JSON shapes for requestId are all rejected
    // ──────────────────────────────────────────────────────────────────

    @TestFactory
    fun `S10 manage_items rejects non-string requestId JSON shapes`(): List<DynamicTest> {
        val case = toolCases().first { it.label == "manage_items" }
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
                    assertEquals(0, context.idempotencyCache.size(), "$formLabel: cache must stay empty")
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // S11 — edge (manage_items): requestId is validated before operation (ordering)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `S11 manage_items validates requestId before operation`() =
        runBlocking {
            val case = toolCases().first { it.label == "manage_items" }
            val context = freshContext()
            val (baseParams, mutated) = case.build(context, "agent-1")
            val withBadOperation =
                buildJsonObject {
                    for ((k, v) in baseParams) {
                        if (k != "operation") put(k, v)
                    }
                    put("operation", JsonPrimitive("not-a-real-operation"))
                }
            val params = withBadOperation.withRequestId(JsonPrimitive("not-a-uuid"))

            val ex = assertThrows<ToolValidationException> { case.tool.validateParams(params) }
            assertTrue(
                ex.message!!.contains("requestId"),
                "the requestId check must run before the operation check, got: ${ex.message}"
            )
            assertFalse(
                ex.message!!.contains("Invalid operation"),
                "must report the requestId error, not the operation error, got: ${ex.message}"
            )
            assertFalse(mutated(), "must not write when rejected")
        }
}

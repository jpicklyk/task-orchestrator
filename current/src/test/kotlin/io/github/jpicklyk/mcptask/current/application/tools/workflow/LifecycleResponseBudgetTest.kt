package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Token-efficiency regression guard for per-call response size (see the "MCP
 * Token-Efficiency Program" work referenced in [io.github.jpicklyk.mcptask.current.application.tools.ToolTokenBudgetTest]).
 * That test guards the `tools/list` payload (what a client downloads once per session); this
 * test guards the recurring cost — what a client's context window pays on every tool *call* —
 * by driving a full item lifecycle end-to-end against a real H2 database and measuring exactly
 * what [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter] hands back to an MCP
 * client: the `userSummary` text plus the compact-JSON `structuredContent` payload. See
 * [measureCall] — it deliberately mirrors `McpToolAdapter.registerToolWithServer`'s isError /
 * userSummary / extractDataPayload-or-extractErrorPayload logic exactly, so a change to either
 * side (the adapter or this test) that drifts from the other would be caught by
 * [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapterTest] rather than silently
 * under- or over-counting here.
 *
 * The lifecycle: create_work_tree (root + 2 children + a blocking dep) -> get_context
 * health-check -> get_context item -> advance start (gated FAILURE — queue note unfilled) ->
 * manage_notes (fill queue note) -> advance start (-> work) -> manage_notes (fill both work
 * notes) -> advance start (-> review) -> manage_notes (fill review note) -> advance start
 * (-> terminal) -> get_next_item -> query_items overview -> query_items get -> query_notes list.
 * The note schema (1 queue + 2 work + 1 review notes, each with guidance text) mirrors a
 * realistic feature-task schema, per the "Reference for the flow (Python original, for shapes
 * only)" drive_workflow.py script this test's call sequence is modeled on.
 *
 * BUDGET PHILOSOPHY: per-call ceilings below are measured-in-this-repo * 1.15, rounded up to
 * the nearest 50 (same policy as ToolTokenBudgetTest — see that file for the full rationale
 * on when it's OK to raise a ceiling vs. when a failure means "fix the bloat instead"). The
 * four `assertTrue(... < N ...)` hard caps are a second, independent guard: they pin the
 * *original pre-slimming* sizes from the token-efficiency audit as regression sentinels, so a
 * revert of that work fails loudly even if someone also loosened the per-call ceiling table.
 */
class LifecycleResponseBudgetTest {
    private lateinit var context: ToolExecutionContext

    private val createWorkTreeTool = CreateWorkTreeTool()
    private val getContextTool = GetContextTool()
    private val advanceItemTool = AdvanceItemTool()
    private val manageNotesTool = ManageNotesTool()
    private val getNextItemTool = GetNextItemTool()
    private val queryItemsTool = QueryItemsTool()
    private val queryNotesTool = QueryNotesTool()

    /** Insertion-ordered label -> measured response char count, populated by [measureCall]. */
    private val measured = linkedMapOf<String, Int>()

    @BeforeEach
    fun setUp() {
        val dbName = "lifecycle_response_budget_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)

        // "feature-task" schema: 1 queue note + 2 work notes + 1 review note, each with guidance
        // text — mirrors a realistic feature-task work item (see drive_workflow.py reference).
        val featureTaskSchema =
            WorkItemSchema(
                type = "feature-task",
                lifecycleMode = LifecycleMode.AUTO,
                notes =
                    listOf(
                        NoteSchemaEntry(
                            key = "task-scope",
                            role = Role.QUEUE,
                            required = true,
                            description = "Problem statement, approach, and acceptance criteria for this task.",
                            guidance = "State the scope crisply: what changes, what doesn't, and how success is verified."
                        ),
                        NoteSchemaEntry(
                            key = "implementation-notes",
                            role = Role.WORK,
                            required = true,
                            description = "Context handoff for downstream agents.",
                            guidance = "Document deviations, surprises, and decisions for downstream agents."
                        ),
                        NoteSchemaEntry(
                            key = "session-tracking",
                            role = Role.WORK,
                            required = true,
                            description = "Outcome, files changed, deviations, friction, and test results for this session.",
                            guidance = "Summarize what happened this session so the next agent doesn't repeat discovery work."
                        ),
                        NoteSchemaEntry(
                            key = "review-checklist",
                            role = Role.REVIEW,
                            required = true,
                            description = "Quality gate: scope alignment, test quality, and side-effect review.",
                            guidance = "Confirm scope alignment, edge-case test coverage, and no unintended side effects."
                        )
                    )
            )

        val schemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

                override fun getSchemaForType(type: String?): WorkItemSchema? =
                    if (type == "feature-task") featureTaskSchema else null
            }

        context = ToolExecutionContext(repositoryProvider, schemaService)
        measured.clear()
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun actor(id: String = "test-actor") =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("kind", JsonPrimitive("orchestrator"))
        }

    /**
     * Executes [tool] and records the exact char count an MCP client would receive for this
     * call: `userSummary` text + compact-JSON `structuredContent`. Mirrors
     * McpToolAdapter.registerToolWithServer's isError / userSummary / extractDataPayload-or-
     * extractErrorPayload logic exactly (see class doc).
     */
    private suspend fun measureCall(
        label: String,
        tool: ToolDefinition,
        params: JsonElement
    ): JsonObject {
        val result = tool.execute(params, context)
        val resultObj = result as JsonObject
        val isError = ResponseUtil.isErrorResponse(resultObj)
        val summary = tool.userSummary(params, result, isError)
        val structuredData =
            if (isError) {
                ResponseUtil.extractErrorPayload(resultObj)
            } else {
                ResponseUtil.extractDataPayload(resultObj) as? JsonObject
            }
        val structuredLen = structuredData?.toString()?.length ?: 0
        measured[label] = summary.length + structuredLen
        return resultObj
    }

    private fun transitionParams(
        itemId: String,
        trigger: String
    ) = buildJsonObject {
        put(
            "transitions",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("trigger", JsonPrimitive(trigger))
                        put("actor", actor())
                    }
                )
            }
        )
    }

    // Note fills deliberately omit per-note `actor` — it's optional, and attaching it would
    // pull in an `actor` + `verification` block (NoOpActorVerifier always stamps one; see
    // ServerComposition.kt, this is the real deployed default, not a test artifact) on every
    // note, which is attribution metadata orthogonal to what this test measures (lifecycle
    // response-payload leanness). Keeping notes actor-free here isolates that measurement.
    private fun upsertNoteParams(vararg notes: Triple<String, String, Pair<String, String>>) =
        // Triple(itemId, key, Pair(role, body))
        buildJsonObject {
            put("operation", JsonPrimitive("upsert"))
            put(
                "notes",
                buildJsonArray {
                    notes.forEach { (itemId, key, roleAndBody) ->
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId))
                                put("key", JsonPrimitive(key))
                                put("role", JsonPrimitive(roleAndBody.first))
                                put("body", JsonPrimitive(roleAndBody.second))
                            }
                        )
                    }
                }
            )
        }

    // ──────────────────────────────────────────────
    // BUDGET PHILOSOPHY — see class doc. Update via the same process as ToolTokenBudgetTest.
    // ──────────────────────────────────────────────
    private val perCallCeilings: Map<String, Int> =
        mapOf(
            "create_work_tree" to 2400,
            "get_context(health-check)" to 200,
            "get_context(item t1)" to 750,
            "advance_item(start, gated FAILURE)" to 550,
            "manage_notes(fill queue note)" to 400,
            "advance_item(start -> work)" to 850,
            "manage_notes(fill work notes)" to 550,
            "advance_item(start -> review)" to 550,
            "manage_notes(fill review note)" to 400,
            "advance_item(start -> terminal)" to 500,
            "get_next_item" to 350,
            "query_items(overview)" to 400,
            "query_items(get t1)" to 350,
            "query_notes(list t1)" to 950,
        )

    @Test
    fun `full item lifecycle response sizes stay within budget`(): Unit =
        runBlocking {
            // ── 1. create_work_tree: root (container) + 2 feature-task children + blocking dep ──
            val createParams =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Add CSV export to reports"))
                            put("priority", JsonPrimitive("high"))
                            put("summary", JsonPrimitive("Users need to export report data as CSV from the reports page."))
                        }
                    )
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("t1"))
                                    put("title", JsonPrimitive("Implement CSV serializer"))
                                    put("type", JsonPrimitive("feature-task"))
                                    put("priority", JsonPrimitive("high"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("t2"))
                                    put("title", JsonPrimitive("Wire export button to serializer"))
                                    put("type", JsonPrimitive("feature-task"))
                                    put("priority", JsonPrimitive("medium"))
                                }
                            )
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("from", JsonPrimitive("t2"))
                                    put("to", JsonPrimitive("t1"))
                                    put("type", JsonPrimitive("IS_BLOCKED_BY"))
                                }
                            )
                        }
                    )
                    put("createNotes", JsonPrimitive(true))
                    put("actor", actor())
                    put("requestId", JsonPrimitive(java.util.UUID.randomUUID().toString()))
                }
            val createResult = measureCall("create_work_tree", createWorkTreeTool, createParams)
            val createData = createResult["data"] as JsonObject
            val childrenArr = createData["children"]!!.jsonArray
            val t1Id =
                childrenArr
                    .first { it.jsonObject["ref"]!!.jsonPrimitive.content == "t1" }
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            // ── 2. get_context health-check (session bootstrap) ──
            measureCall("get_context(health-check)", getContextTool, buildJsonObject {})

            // ── 3. get_context item mode on t1 ──
            measureCall(
                "get_context(item t1)",
                getContextTool,
                buildJsonObject { put("itemId", JsonPrimitive(t1Id)) }
            )

            // ── 4. advance start -> expect gate failure (task-scope unfilled) ──
            measureCall(
                "advance_item(start, gated FAILURE)",
                advanceItemTool,
                transitionParams(t1Id, "start")
            )

            // ── 5. manage_notes: fill the required queue note ──
            measureCall(
                "manage_notes(fill queue note)",
                manageNotesTool,
                upsertNoteParams(
                    Triple(
                        t1Id,
                        "task-scope",
                        "queue" to
                            "Build a CsvSerializer class in reports/serializers.py that converts ReportRow lists to " +
                            "RFC-4180 CSV. Acceptance: given rows with commas/quotes/newlines, output round-trips " +
                            "through csv.reader. Constraint: no new dependencies."
                    )
                )
            )

            // ── 6. advance start -> work ──
            measureCall("advance_item(start -> work)", advanceItemTool, transitionParams(t1Id, "start"))

            // ── 7. manage_notes: fill both required work notes ──
            measureCall(
                "manage_notes(fill work notes)",
                manageNotesTool,
                upsertNoteParams(
                    Triple(
                        t1Id,
                        "implementation-notes",
                        "work" to
                            "Implemented CsvSerializer with csv.writer + StringIO. Deviation: used csv module " +
                            "dialect=excel rather than manual escaping. No API surprises."
                    ),
                    Triple(
                        t1Id,
                        "session-tracking",
                        "work" to
                            "Outcome: success. Files changed: reports/serializers.py, tests/test_csv.py. " +
                            "Deviations: none. Friction: none. Test results: 8 pass."
                    )
                )
            )

            // ── 8. advance start -> review ──
            measureCall("advance_item(start -> review)", advanceItemTool, transitionParams(t1Id, "start"))

            // ── 9. manage_notes: fill the required review note ──
            measureCall(
                "manage_notes(fill review note)",
                manageNotesTool,
                upsertNoteParams(
                    Triple(
                        t1Id,
                        "review-checklist",
                        "review" to
                            "Scope alignment: matches task-scope. Tests: cover commas/quotes/newlines + round-trip. " +
                            "No side effects outside reports/."
                    )
                )
            )

            // ── 10. advance start -> terminal ──
            measureCall("advance_item(start -> terminal)", advanceItemTool, transitionParams(t1Id, "start"))

            // ── 11. get_next_item (t2 should now be unblocked) ──
            measureCall(
                "get_next_item",
                getNextItemTool,
                buildJsonObject { put("includeDetails", JsonPrimitive(true)) }
            )

            // ── 12. query_items overview ──
            measureCall(
                "query_items(overview)",
                queryItemsTool,
                buildJsonObject { put("operation", JsonPrimitive("overview")) }
            )

            // ── 13. query_items get t1 ──
            measureCall(
                "query_items(get t1)",
                queryItemsTool,
                buildJsonObject {
                    put("operation", JsonPrimitive("get"))
                    put("itemId", JsonPrimitive(t1Id))
                }
            )

            // ── 14. query_notes list t1 (default includeBody=false) ──
            measureCall(
                "query_notes(list t1)",
                queryNotesTool,
                buildJsonObject {
                    put("operation", JsonPrimitive("list"))
                    put("itemId", JsonPrimitive(t1Id))
                }
            )

            // ── Print the measured-size table ──
            println("── lifecycle per-call measured response sizes (userSummary + structuredContent) ──")
            var total = 0
            measured.forEach { (label, size) ->
                total += size
                val ceiling = perCallCeilings[label]
                println("  %-38s %6d chars   (ceiling %s)".format(label, size, ceiling?.toString() ?: "MISSING"))
            }
            println("  %-38s %6d chars".format("TOTAL", total))

            // ── Per-call ceilings: see BUDGET PHILOSOPHY in the class doc ──
            val failures = mutableListOf<String>()
            for ((label, size) in measured) {
                val ceiling = perCallCeilings[label]
                if (ceiling == null) {
                    failures.add("$label: no ceiling registered in perCallCeilings — add one")
                } else if (size > ceiling) {
                    failures.add("$label: rendered $size chars, exceeds ceiling of $ceiling chars")
                }
            }
            if (failures.isNotEmpty()) {
                kotlin.test.fail(
                    "Lifecycle call(s) grew past their response char budget:\n" +
                        failures.joinToString("\n") { "  - $it" }
                )
            }

            // ── Hard-cap regression sentinels: original (pre-token-efficiency-work) sizes ──
            assertTrue(
                measured["create_work_tree"]!! < 3000,
                "create_work_tree response is ${measured["create_work_tree"]} chars, " +
                    "regressed toward the pre-slimming size (was 8,861 chars)"
            )
            assertTrue(
                measured["get_context(item t1)"]!! < 1500,
                "get_context(item) response is ${measured["get_context(item t1)"]} chars, " +
                    "regressed toward the pre-slimming size (was 2,968 chars)"
            )
            assertTrue(
                measured["advance_item(start -> work)"]!! < 1100,
                "advance_item(start -> work) response is ${measured["advance_item(start -> work)"]} chars, " +
                    "regressed toward the pre-slimming size (was 2,153 chars)"
            )
            assertTrue(
                measured["query_notes(list t1)"]!! < 900,
                "query_notes(list) response is ${measured["query_notes(list t1)"]} chars, " +
                    "regressed toward the pre-slimming size (was 2,002 chars with bodies)"
            )
        }
}

package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertTrue

/**
 * Token-efficiency regression guard for per-call response size. The tool registry's `tools/list` payload (what a client
 * downloads once per session) is guarded by ToolSurfaceGuardsTest S15; this test guards the recurring cost -- what a
 * client's context window pays on every tool *call* -- by driving a full item lifecycle end-to-end against a real SQLite
 * database and measuring the envelope body an MCP client receives as `structuredContent` (the 4.0 tools have no separate
 * summary text; Q32 retired the 3.x advance measurement, which mirrored the removed 3.x adapter).
 *
 * The lifecycle: a tree fixture (root + 2 children + a blocking dep, through the 4.0 `items_write` `create_tree`, not
 * measured here: Q21 retired the 3.x `create_work_tree` this call used to measure) -> items_read context
 * health view -> items_read context item -> advance start (gated FAILURE — queue note unfilled) ->
 * fill the queue note -> advance start (-> work) -> fill both work notes -> advance start (-> review) ->
 * fill the review note -> advance start (-> terminal) -> items_read next.
 * The note fills are fixture writes (`putNote`): the note tools are budgeted by their own tests.
 * The note schema (1 queue + 2 work + 1 review notes, each with guidance text) mirrors a
 * realistic feature-task schema, per the "Reference for the flow (Python original, for shapes
 * only)" drive_workflow.py script this test's call sequence is modeled on.
 *
 * BUDGET PHILOSOPHY: per-call ceilings below are measured-in-this-repo * 1.15, rounded up to
 * the nearest 50 (raise a ceiling only for a deliberate, reviewed response change; otherwise a failure means "fix the
 * bloat instead"). The
 * two `assertTrue(... < N ...)` hard caps are a second, independent guard: they pin the
 * *original pre-slimming* sizes from the token-efficiency audit as regression sentinels, so a
 * revert of that work fails loudly even if someone also loosened the per-call ceiling table.
 */
class LifecycleResponseBudgetTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext

    private val contextProbe = ItemContextProbe("context")
    private val nextProbe = ItemContextProbe("next")

    /** Insertion-ordered label -> measured response char count, populated by [measureCall]. */
    private val measured = linkedMapOf<String, Int>()

    @BeforeEach
    fun setUp() {
        val repositoryProvider = db.repositoryProvider()

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
            TestConfigResolver.withoutTypeValidation(
                TestConfigResolver.resolver(TestConfigResolver.documentOf(mapOf("feature-task" to featureTaskSchema)))
            )

        context = ToolExecutionContext(repositoryProvider, configResolver = schemaService, unitOfWork = db.unitOfWork())
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

    /** Runs one `advance` transition of [itemId] and records the char count of the envelope body (`structuredContent`). */
    private suspend fun measureAdvance(
        label: String,
        itemId: String,
        trigger: String
    ): JsonObject {
        val body = context.advanceTool(transitionOp(itemId, trigger), actor = actor())
        measured[label] = body.toString().length
        return body
    }

    /**
     * Calls the `items_read` select of [probe] through the production dispatcher and records the char count of the whole
     * envelope body an MCP client receives as `structuredContent` (the 4.0 tools have no separate summary text).
     */
    private suspend fun measureEnvelope(
        label: String,
        probe: ItemContextProbe,
        params: JsonObject
    ): JsonObject {
        val body = probe.envelope(context, params)
        measured[label] = body.toString().length
        return body
    }

    // ──────────────────────────────────────────────
    // BUDGET PHILOSOPHY — see class doc.
    // ──────────────────────────────────────────────
    private val perCallCeilings: Map<String, Int> =
        mapOf(
            "items_read(context health)" to 500,
            "items_read(context item t1)" to 950,
            "advance(start, gated FAILURE)" to 650,
            "advance(start -> work)" to 550,
            "advance(start -> review)" to 400,
            "advance(start -> terminal)" to 400,
            "items_read(next)" to 300,
        )

    @Test
    fun `full item lifecycle response sizes stay within budget`(): Unit =
        runBlocking {
            // ── 1. fixture: root (container) + 2 feature-task children + blocking dep (items_write create_tree) ──
            val createParams =
                buildJsonObject {
                    put("op", JsonPrimitive("create_tree"))
                    put(
                        "root",
                        buildJsonObject {
                            put("type", JsonPrimitive("test-item"))
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
                                    put("from", JsonPrimitive("t1"))
                                    put("to", JsonPrimitive("t2"))
                                    put("type", JsonPrimitive("blocks"))
                                }
                            )
                        }
                    )
                    put("createNotes", JsonPrimitive(true))
                }
            val created = context.itemsWrite(createParams, actor = actor()).okResult(0)
            val t1Id =
                created["items"]!!
                    .jsonArray
                    .first { it.jsonObject["ref"]!!.jsonPrimitive.content == "t1" }
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            // ── 2. items_read context, health view (session bootstrap) ──
            measureEnvelope("items_read(context health)", contextProbe, buildJsonObject {})

            // ── 3. items_read context, item mode on t1 ──
            measureEnvelope(
                "items_read(context item t1)",
                contextProbe,
                buildJsonObject { put("itemId", JsonPrimitive(t1Id)) }
            )

            // ── 4. advance start -> expect gate failure (task-scope unfilled) ──
            measureAdvance("advance(start, gated FAILURE)", t1Id, "start")

            // ── 5. fill the required queue note (fixture write: the note tools have their own budget) ──
            context.putNote(
                UUID.fromString(t1Id),
                "task-scope",
                "queue",
                "Build a CsvSerializer class in reports/serializers.py that converts ReportRow lists to " +
                    "RFC-4180 CSV. Acceptance: given rows with commas/quotes/newlines, output round-trips " +
                    "through csv.reader. Constraint: no new dependencies."
            )

            // ── 6. advance start -> work ──
            measureAdvance("advance(start -> work)", t1Id, "start")

            // ── 7. fill both required work notes ──
            context.putNote(
                UUID.fromString(t1Id),
                "implementation-notes",
                "work",
                "Implemented CsvSerializer with csv.writer + StringIO. Deviation: used csv module " +
                    "dialect=excel rather than manual escaping. No API surprises."
            )
            context.putNote(
                UUID.fromString(t1Id),
                "session-tracking",
                "work",
                "Outcome: success. Files changed: reports/serializers.py, tests/test_csv.py. " +
                    "Deviations: none. Friction: none. Test results: 8 pass."
            )

            // ── 8. advance start -> review ──
            measureAdvance("advance(start -> review)", t1Id, "start")

            // ── 9. fill the required review note ──
            context.putNote(
                UUID.fromString(t1Id),
                "review-checklist",
                "review",
                "Scope alignment: matches task-scope. Tests: cover commas/quotes/newlines + round-trip. " +
                    "No side effects outside reports/."
            )

            // ── 10. advance start -> terminal ──
            measureAdvance("advance(start -> terminal)", t1Id, "start")

            // ── 11. items_read next (t2 should now be unblocked) ──
            measureEnvelope(
                "items_read(next)",
                nextProbe,
                buildJsonObject { put("fields", buildJsonArray { add(JsonPrimitive("summary")) }) }
            )

            // ── Print the measured-size table ──
            println("── lifecycle per-call measured response sizes (structuredContent) ──")
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
                measured["items_read(context item t1)"]!! < 1500,
                "items_read(context item) response is ${measured["items_read(context item t1)"]} chars, " +
                    "regressed toward the pre-slimming size (get_context was 2,968 chars)"
            )
            assertTrue(
                measured["advance(start -> work)"]!! < 1100,
                "advance(start -> work) response is ${measured["advance(start -> work)"]} chars, " +
                    "regressed toward the pre-slimming size (was 2,153 chars)"
            )
        }
}

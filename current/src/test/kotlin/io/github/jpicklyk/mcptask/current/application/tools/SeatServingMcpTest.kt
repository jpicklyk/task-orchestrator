package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`task-scope-addendum`/`test-plan` notes on
 * item `79cd4f0c-10aa-484e-b378-d4d5c0f10430` (A1, stage A1b) — scenarios S10, S12 (MCP side only;
 * the REST-side parity leg of S12 is A1c's test author's responsibility, not this file's), and S13.
 *
 * HARNESS (task-scope-addendum "Harness rule", pattern: `SeatlessResponseGoldenTest`): every capture
 * runs the REAL [ServerComposition.build] over an SQLite DB and executes the REAL tool classes
 * ([GetContextTool], [QueryItemsTool], [AdvanceItemTool]) against the resulting
 * `composition.toolContext` — never a hand-built `ToolExecutionContext` replica. The fixture config
 * is supplied as the GLOBAL layer only (a real temp `.taskorchestrator/config.yaml` read by
 * `GlobalConfigFile`, exactly as `SeatlessResponseGoldenTest.materializeGlobalConfig` does) — no
 * per-root push is exercised here because per-root layering precedence (S7, S8) is A1a's test
 * author's scope (`LayeredConfigSeatTest`), not this stage's; A1b is about whether the MCP tools
 * correctly SERVE whatever `EffectiveConfigResolver` resolves, which a global-only config already
 * exercises fully.
 *
 * FIXTURE SCHEMA (`SEAT_AWARE_CONFIG`) mirrors `task-scope` section 2's own worked example: a
 * `bug-fix` schema with a `queue` seat (`planner`), two `work` seats (`implementer`, entering;
 * `orchestrator`), a `review` seat (`reviewer`), and trait `needs-test-author` contributing a
 * `work` seat (`test-author`) plus a `work`-phase dispatch profile with per-seat overrides. Merged
 * seat order (base schema seats, then trait seats — task-scope section 4 "Merged seats" row) is
 * therefore `[planner, implementer, orchestrator, reviewer, test-author]`; the WORK-phase subset of
 * that order is `[implementer, orchestrator, test-author]`. `SEATLESS_CONFIG`'s `plain-task` schema
 * declares no `seats:` key at all, so `WorkItemSchema.isSeatAware()` is false for it by construction
 * (oracle: task-scope section 5 `isSeatAware(): Boolean = seats.isNotEmpty()`).
 *
 * Oracle for the dispatch-by-seat values below: task-scope section 4's per-seat dispatch rule
 * ("Effective profile = override applied over the phase default ... field in `cleared` -> null;
 * else override field ?: phase-default field") applied BY HAND to this fixture's own YAML, not read
 * off any implementation output:
 * - `implementer` override sets only `model: sonnet` -> agent inherited from the phase default
 *   (`task-orchestrator:implementer`), model from the override (`sonnet`), effort absent (neither
 *   side sets it).
 * - `orchestrator` has no seat override at all -> the phase default itself, unmodified
 *   (`{agent: task-orchestrator:implementer}`).
 * - `test-author` override sets its own `agent` and `model` -> both come from the override, effort
 *   still absent (neither side sets it).
 */
class SeatServingMcpTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    companion object {
        private val ROOT_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000000")
        private val SEAT_AWARE_WORK_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000001")
        private val SEAT_AWARE_TERMINAL_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000002")
        private val SEAT_AWARE_QUEUE_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000003")
        private val SEATLESS_WORK_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000004")
        private val UNOWNED_NOTE_WORK_ID: UUID = UUID.fromString("a1b00000-0000-4000-8000-000000000005")

        // Mirrors task-scope section 2's own worked YAML example (seats + note `seat` + per-seat dispatch).
        private const val GLOBAL_CONFIG = """
work_item_schemas:
  bug-fix:
    default_traits: [needs-test-author]
    seats:
      - { name: planner, phase: queue }
      - { name: implementer, phase: work, enters: true }
      - { name: orchestrator, phase: work }
      - { name: reviewer, phase: review }
    notes:
      - { key: diagnosis, role: queue, required: true, seat: planner }
      - { key: implementation-notes, role: work, required: true, seat: implementer }
      - { key: session-tracking, role: work, required: true, seat: orchestrator }
  plain-task:
    notes:
      - { key: plan, role: queue, required: true }
      - { key: outcome, role: work, required: true }
  seat-aware-unowned:
    seats:
      - { name: implementer, phase: work, enters: true }
    notes:
      - { key: owned-note, role: work, required: true, seat: implementer }
      - { key: free-note, role: work, required: true }
traits:
  needs-test-author:
    seats:
      - { name: test-author, phase: work, after: [implementer] }
    dispatch:
      work:
        agent: task-orchestrator:implementer
        seats:
          implementer: { model: sonnet }
          test-author: { agent: task-orchestrator:test-author, model: sonnet }
    notes:
      - { key: test-plan, role: queue, required: true, seat: planner }
      - { key: test-manifest, role: work, required: true, seat: test-author }
"""
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixture wiring — REAL ServerComposition.build over SQLite, global-file-only config.
    // ─────────────────────────────────────────────────────────────────────

    private fun buildDatabaseManager(): DatabaseManager = db.databaseManager

    private fun materializeGlobalConfig(tempDir: Path): Path {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.write(file, GLOBAL_CONFIG.toByteArray(Charsets.UTF_8))
        return file
    }

    private fun newToolContext(tempDir: Path): ToolExecutionContext {
        materializeGlobalConfig(tempDir)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        val composition =
            ServerComposition(
                appConfig = appConfig,
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = ShutdownCoordinator(),
            ).build()

        val repo = composition.toolContext.repositoryProvider
        runBlocking {
            repo
                .workItemRepository()
                .create(
                    WorkItem(id = ROOT_ID, title = "A1b seat-serving root", type = "project", depth = 0),
                ) ?: error("fixture: root item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = SEAT_AWARE_WORK_ID,
                        title = "A1b seat-aware WORK item",
                        type = "bug-fix",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: seat-aware WORK item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = SEAT_AWARE_TERMINAL_ID,
                        title = "A1b seat-aware TERMINAL item",
                        type = "bug-fix",
                        role = Role.TERMINAL,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: seat-aware TERMINAL item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = SEAT_AWARE_QUEUE_ID,
                        title = "A1b seat-aware QUEUE item",
                        type = "bug-fix",
                        role = Role.QUEUE,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: seat-aware QUEUE item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = SEATLESS_WORK_ID,
                        title = "A1b seat-less WORK item",
                        type = "plain-task",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: seat-less WORK item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = UNOWNED_NOTE_WORK_ID,
                        title = "A1b seat-aware WORK item with an unowned note",
                        type = "seat-aware-unowned",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: unowned-note WORK item creation failed")
        }

        return composition.toolContext
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    // ─────────────────────────────────────────────────────────────────────
    // S10 — get_context: current-phase seats, flat dispatchBySeat, per-entry seat, missingBySeat
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 - get_context on a WORK-phase seat-aware item exposes current-phase seats, flat dispatchBySeat, and gateStatus missingBySeat`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)
            val result =
                GetContextTool().execute(
                    buildJsonObject { put("itemId", JsonPrimitive(SEAT_AWARE_WORK_ID.toString())) },
                    toolContext,
                )
            val data = extractData(result)

            // Top-level `seats` — CURRENT PHASE (work) ONLY, in merged-seat order restricted to work:
            // [implementer, orchestrator, test-author]. (Oracle: task-scope section 6 get_context row.)
            val seats = data["seats"]!!.jsonArray
            assertEquals(
                listOf("implementer", "orchestrator", "test-author"),
                seats.map { it.jsonObject["name"]!!.jsonPrimitive.content },
                "current-phase (work) seats only, in merged-seat order",
            )
            val implementerSeat = seats[0].jsonObject
            assertEquals("work", implementerSeat["phase"]!!.jsonPrimitive.content)
            assertTrue(implementerSeat["enters"]!!.jsonPrimitive.boolean, "implementer seat declares enters: true")
            val orchestratorSeat = seats[1].jsonObject
            assertFalse(orchestratorSeat.containsKey("enters"), "enters omitted when false")
            val testAuthorSeat = seats[2].jsonObject
            assertEquals(
                listOf("implementer"),
                testAuthorSeat["after"]!!.jsonArray.map { it.jsonPrimitive.content },
                "test-author's after:[implementer] is served",
            )

            // Flat dispatchBySeat for the current (work) phase only: {seat: profile}.
            val dispatchBySeat = data["dispatchBySeat"]!!.jsonObject
            assertEquals(setOf("implementer", "orchestrator", "test-author"), dispatchBySeat.keys)
            assertEquals(
                buildJsonObject {
                    put("agent", JsonPrimitive("task-orchestrator:implementer"))
                    put("model", JsonPrimitive("sonnet"))
                },
                dispatchBySeat["implementer"],
                "implementer keeps the phase-default agent, override supplies model",
            )
            assertEquals(
                buildJsonObject { put("agent", JsonPrimitive("task-orchestrator:implementer")) },
                dispatchBySeat["orchestrator"],
                "orchestrator has no seat override -> unmodified phase default",
            )
            assertEquals(
                buildJsonObject {
                    put("agent", JsonPrimitive("task-orchestrator:test-author"))
                    put("model", JsonPrimitive("sonnet"))
                },
                dispatchBySeat["test-author"],
                "test-author's own override fields both apply",
            )

            // Every schema[] entry gains `seat` when the resolved schema is seat-aware (all phases,
            // since schema[] itself is not phase-filtered — established convention, GetContextToolTest
            // "item mode includes trait notes in schema when type has default_traits").
            val schemaEntries = data["schema"]!!.jsonArray
            assertTrue(schemaEntries.isNotEmpty())
            val implementationNotesEntry =
                schemaEntries
                    .first { it.jsonObject["key"]!!.jsonPrimitive.content == "implementation-notes" }
                    .jsonObject
            assertEquals("implementer", implementationNotesEntry["seat"]!!.jsonPrimitive.content)
            assertEquals(
                setOf("key", "role", "required", "exists", "filled", "seat"),
                implementationNotesEntry.keys,
                "item-mode schema entries stay keys-only, gaining exactly one new key: seat",
            )

            // gateStatus.missingBySeat: implementation-notes (implementer), session-tracking
            // (orchestrator), test-manifest (test-author, from the needs-test-author trait) are all
            // missing (no notes filled) — bucket order = merged-seat order restricted to seats that
            // actually own a missing key.
            val gateStatus = data["gateStatus"]!!.jsonObject
            assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
            val missingBySeat = gateStatus["missingBySeat"]!!.jsonObject
            assertEquals(listOf("implementer", "orchestrator", "test-author"), missingBySeat.keys.toList())
            assertEquals(listOf("implementation-notes"), missingBySeat["implementer"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("session-tracking"), missingBySeat["orchestrator"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("test-manifest"), missingBySeat["test-author"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `S10 - get_context on a TERMINAL item omits seats, dispatchBySeat, and gateStatus missingBySeat`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)
            val result =
                GetContextTool().execute(
                    buildJsonObject { put("itemId", JsonPrimitive(SEAT_AWARE_TERMINAL_ID.toString())) },
                    toolContext,
                )
            val data = extractData(result)

            assertNull(data["seats"], "terminal items must not report current-phase seats")
            assertNull(data["dispatchBySeat"], "terminal items must not report dispatchBySeat")
            val gateStatus = data["gateStatus"]!!.jsonObject
            assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "terminal items never canAdvance")
            assertNull(gateStatus["missingBySeat"], "terminal items must not report gateStatus.missingBySeat")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S12 (MCP side) — get_context.gateStatus.missingBySeat == advance_item(start).missingBySeat
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S12 - get_context and advance_item start report identical missingBySeat for the same QUEUE-phase gate failure`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)

            val contextResult =
                GetContextTool().execute(
                    buildJsonObject { put("itemId", JsonPrimitive(SEAT_AWARE_QUEUE_ID.toString())) },
                    toolContext,
                )
            val contextData = extractData(contextResult)
            val contextGateStatus = contextData["gateStatus"]!!.jsonObject
            assertFalse(contextGateStatus["canAdvance"]!!.jsonPrimitive.boolean)
            val contextMissingBySeat = contextGateStatus["missingBySeat"]!!.jsonObject

            // Sanity: the queue-phase gate is missing diagnosis (planner) and test-plan (planner,
            // from the needs-test-author trait) — both owned by the same seat.
            assertEquals(
                mapOf("planner" to listOf("diagnosis", "test-plan")),
                contextMissingBySeat.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } },
            )

            val advanceResult =
                AdvanceItemTool().execute(
                    buildJsonObject {
                        put(
                            "transitions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(SEAT_AWARE_QUEUE_ID.toString()))
                                        put("trigger", JsonPrimitive("start"))
                                    },
                                )
                            },
                        )
                    },
                    toolContext,
                )
            val advanceData = extractData(advanceResult)
            val transitionResult = advanceData["results"]!!.jsonArray[0].jsonObject
            assertFalse(transitionResult["applied"]!!.jsonPrimitive.boolean, "queue gate must block the start trigger")
            val advanceMissingBySeat = transitionResult["missingBySeat"]!!.jsonObject

            assertEquals(
                contextMissingBySeat,
                advanceMissingBySeat,
                "get_context and advance_item must report the identical missingBySeat for the same gate state",
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // S13 — features == ["seats","dispatchBySeat"] on query_items(schema), type AND item paths,
    // including a SEAT-LESS config.
    // ─────────────────────────────────────────────────────────────────────

    private val expectedFeatures = listOf("seats", "dispatchBySeat", "independent_of", "rules")

    @Test
    fun `S13 - query_items schema by type reports features on both a seat-aware and a seat-less schema`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)

            val seatAwareResult =
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("type", JsonPrimitive("bug-fix"))
                    },
                    toolContext,
                )
            val seatAwareData = extractData(seatAwareResult)
            assertEquals(
                expectedFeatures,
                seatAwareData["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "features is always [\"seats\",\"dispatchBySeat\"] (task-scope section 5 ServerFeatures.ADVERTISED)",
            )
            // Incidental wiring check: a seat-aware type path also serves seats/notes[].seat through
            // the same ItemSchemaView builder S13 is characterizing.
            assertTrue(seatAwareData.containsKey("seats"), "seat-aware type path also serves seats")
            val implementerNote =
                seatAwareData["notes"]!!
                    .jsonArray
                    .first { it.jsonObject["key"]!!.jsonPrimitive.content == "implementation-notes" }
                    .jsonObject
            assertEquals("implementer", implementerNote["seat"]!!.jsonPrimitive.content)

            val seatlessResult =
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("type", JsonPrimitive("plain-task"))
                    },
                    toolContext,
                )
            val seatlessData = extractData(seatlessResult)
            assertEquals(
                expectedFeatures,
                seatlessData["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "features is advertised even for a seat-less config (server capability, not per-schema)",
            )
            assertFalse(seatlessData.containsKey("seats"), "seat-less schema must not emit a seats key")
            assertFalse(seatlessData.containsKey("dispatchBySeat"), "seat-less schema must not emit a dispatchBySeat key")
        }

    @Test
    fun `S13 - query_items schema by itemId reports features on both a seat-aware and a seat-less item`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)

            val seatAwareResult =
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("itemId", JsonPrimitive(SEAT_AWARE_WORK_ID.toString()))
                    },
                    toolContext,
                )
            assertEquals(
                expectedFeatures,
                extractData(seatAwareResult)["features"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val seatlessResult =
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("itemId", JsonPrimitive(SEATLESS_WORK_ID.toString()))
                    },
                    toolContext,
                )
            val seatlessData = extractData(seatlessResult)
            assertEquals(expectedFeatures, seatlessData["features"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(seatlessData.containsKey("seats"), "seat-less item path must not emit a seats key")
        }

    // ─────────────────────────────────────────────────────────────────────
    // SF5 — an unowned required note (no `seat:` at all) serializes `seat: null` (present, JSON
    // null), never an absent key, in both get_context's schema[] and query_items(schema)'s notes[].
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SF5 - a note with no owning seat serializes seat as JSON null (present) in get_context schema and query_items schema notes`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)

            val contextResult =
                GetContextTool().execute(
                    buildJsonObject { put("itemId", JsonPrimitive(UNOWNED_NOTE_WORK_ID.toString())) },
                    toolContext,
                )
            val contextEntries = extractData(contextResult)["schema"]!!.jsonArray
            val contextFreeNote = contextEntries.first { it.jsonObject["key"]!!.jsonPrimitive.content == "free-note" }.jsonObject
            assertTrue(contextFreeNote.containsKey("seat"), "the 'seat' key must be present even when unowned: $contextFreeNote")
            assertEquals(JsonNull, contextFreeNote["seat"], "an unowned note's seat must serialize as JSON null, not be omitted")
            val contextOwnedNote = contextEntries.first { it.jsonObject["key"]!!.jsonPrimitive.content == "owned-note" }.jsonObject
            assertEquals(JsonPrimitive("implementer"), contextOwnedNote["seat"], "sanity: the owned note keeps its seat")

            val queryResult =
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("itemId", JsonPrimitive(UNOWNED_NOTE_WORK_ID.toString()))
                    },
                    toolContext,
                )
            val queryNotes = extractData(queryResult)["notes"]!!.jsonArray
            val queryFreeNote = queryNotes.first { it.jsonObject["key"]!!.jsonPrimitive.content == "free-note" }.jsonObject
            assertTrue(queryFreeNote.containsKey("seat"), "the 'seat' key must be present even when unowned: $queryFreeNote")
            assertEquals(JsonNull, queryFreeNote["seat"], "an unowned note's seat must serialize as JSON null, not be omitted")
        }

    // ─────────────────────────────────────────────────────────────────────
    // SF6 — get_context gateStatus.missingBySeat == {} with canAdvance true once every required note
    // is filled; advance_item(complete) with BOTH queue-phase and work-phase notes missing draws
    // missingBySeat buckets from both phases in one map.
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun upsertNote(
        toolContext: ToolExecutionContext,
        itemId: UUID,
        key: String,
        role: String,
        body: String,
    ) {
        val result =
            ManageNotesTool().execute(
                buildJsonObject {
                    put("operation", JsonPrimitive("upsert"))
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("key", JsonPrimitive(key))
                                    put("role", JsonPrimitive(role))
                                    put("body", JsonPrimitive(body))
                                },
                            )
                        },
                    )
                },
                toolContext,
            )
        assertTrue((result as JsonObject)["success"]!!.jsonPrimitive.boolean, "note upsert must succeed: $result")
    }

    @Test
    fun `SF6 - get_context reports gateStatus missingBySeat as an empty object with canAdvance true once every required note is filled`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)
            upsertNote(toolContext, SEAT_AWARE_WORK_ID, "implementation-notes", "work", "done")
            upsertNote(toolContext, SEAT_AWARE_WORK_ID, "session-tracking", "work", "done")
            upsertNote(toolContext, SEAT_AWARE_WORK_ID, "test-manifest", "work", "done")

            val result =
                GetContextTool().execute(
                    buildJsonObject { put("itemId", JsonPrimitive(SEAT_AWARE_WORK_ID.toString())) },
                    toolContext,
                )
            val gateStatus = extractData(result)["gateStatus"]!!.jsonObject
            assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "every required note is filled: $gateStatus")
            assertTrue(gateStatus.containsKey("missingBySeat"), "missingBySeat must be present, not omitted, once satisfied: $gateStatus")
            assertEquals(
                buildJsonObject { },
                gateStatus["missingBySeat"],
                "missingBySeat must be an empty object, not null/absent, once every required note is filled",
            )
        }

    @Test
    fun `SF6 - advance_item complete with queue and work notes missing draws missingBySeat buckets from both phases`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir)

            val result =
                AdvanceItemTool().execute(
                    buildJsonObject {
                        put(
                            "transitions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(SEAT_AWARE_WORK_ID.toString()))
                                        put("trigger", JsonPrimitive("complete"))
                                    },
                                )
                            },
                        )
                    },
                    toolContext,
                )
            val data = extractData(result)
            val transition = data["results"]!!.jsonArray[0].jsonObject
            assertFalse(transition["applied"]!!.jsonPrimitive.boolean, "the schema's queue+work notes are all missing")
            val missingBySeat = transition["missingBySeat"]!!.jsonObject

            // planner (queue: diagnosis, test-plan) and implementer/orchestrator/test-author (work)
            // must ALL appear in one map -- buckets drawn from both phases, not just the current one.
            assertEquals(
                listOf("planner", "implementer", "orchestrator", "test-author"),
                missingBySeat.keys.toList(),
                "bucket order = merged-seat order across BOTH phases: $missingBySeat",
            )
            assertEquals(
                listOf("diagnosis", "test-plan"),
                missingBySeat["planner"]!!.jsonArray.map { it.jsonPrimitive.content },
                "planner's queue-phase notes must be present even though the item is already in WORK",
            )
            assertEquals(listOf("implementation-notes"), missingBySeat["implementer"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("session-tracking"), missingBySeat["orchestrator"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("test-manifest"), missingBySeat["test-author"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
}

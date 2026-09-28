package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen task-scope/test-plan/task-scope-addendum notes on item
 * 09cd604f (stage A2a) -- the MCP subset of S7 ("EVERY MCP gate path"): advance_item start,
 * get_context gateStatus, and S1's REJECT-mode no-independent_of case, S3's fail-closed variants
 * routed through advance_item/get_context, S6's mode-OFF bypass, S8's non-start/complete triggers
 * bypassing the check entirely, and S9's MCP-side parity (get_context.gateStatus.violations ==
 * advance_item(start)'s violations for the same state).
 *
 * Explicitly OUT OF SCOPE for this file (stage A2b, per the dispatch contract): REST /gate, REST
 * advance 422/200 mapping, the phase-guard hook. Also out of scope, and NOT covered by this
 * dispatch (see test-manifest arbitration record): terminal/start CASCADE violations
 * (AdvanceCascadeEvent.violations) and complete_tree's per-item violations mapping -- neither
 * CompleteTreeTool.kt nor the cascade-path additions to AdvanceItemTool.kt/GetContextTool.kt were
 * present in the mechanically-extracted declarations supplied to this dispatch, so their exact JSON
 * shape for `violations` is unverified; the non-cascade paths below establish that the independence
 * check fires correctly on the underlying AdvanceService.checkGate/blocksAdvance path all gate
 * callers share.
 *
 * HARNESS (task-scope-addendum "Harness rule", pattern: SeatServingMcpTest): every capture runs the
 * REAL [ServerComposition.build] over an H2 in-memory DB and executes the REAL tool classes
 * ([GetContextTool], [AdvanceItemTool], [ManageNotesTool]) against the resulting
 * `composition.toolContext` -- never a hand-built ToolExecutionContext replica. Actor-bearing notes
 * are written via [ManageNotesTool]'s public `actor: {id, kind}` upsert parameter (NoOpActorVerifier
 * default -- exercised in ManageNotesToolTest's "actor claim includes actor" case), never by
 * constructing a Note directly against the repository.
 *
 * FIXTURE SCHEMA (`schemaYaml`): a WORK-phase pair matching the item's own worked example --
 * `implementer` (S, enters:true) / `test-author` (N, `test-manifest`, `independent_of:
 * [implementer]`) -- plus a REVIEW-phase `reviewer` seat with its own note (`review-checklist`, no
 * independent_of) so the schema HAS a review phase and `start` on a WORK item resolves to REVIEW,
 * exactly as `test-plan` S2's own worked example describes ("trigger start; schema has review
 * phase, so work->review"). `plain-work` is a second, independent_of-free schema for S1's addendum
 * case. `independence:` mode is injected per test via [globalConfig].
 *
 * NEW-SURFACE: the `violations` field on AdvanceResult/GateBlocked/gateStatus and the whole
 * independence gate-check wiring are introduced by this item. Per the dispatch contract's Test
 * author protocol rule 7, red-proof is orchestrator-run against the addendum's
 * M2/M5/M6/M13/M14 mutation recipes; this file keeps every new declaration and attempts no revert
 * of its own.
 */
class IndependenceGateMcpTest {
    private companion object {
        const val SCHEMA_YAML = """
work_item_schemas:
  indep-gate:
    seats:
      - { name: implementer, phase: work, enters: true }
      - { name: test-author, phase: work }
      - { name: reviewer, phase: review }
    notes:
      - { key: implementation-notes, role: work, required: true, seat: implementer }
      - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }
      - { key: review-checklist, role: review, required: true, seat: reviewer }
  plain-work:
    notes:
      - { key: only-note, role: work, required: true }
"""
    }

    // The mode value is YAML-quoted: SafeConstructor (YAML 1.1) would otherwise coerce an unquoted
    // "off" to the Boolean false before YamlSchemaParser ever sees a String to match against
    // IndependenceMode -- an unquoted off silently falls back to warn (see also
    // IndependenceConfigParseTest's dedicated probe for this gotcha).
    private fun globalConfig(mode: String): String = "independence:\n  mode: \"$mode\"\n$SCHEMA_YAML"

    // ─────────────────────────────────────────────────────────────────────
    // Fixture wiring -- REAL ServerComposition.build over H2, global-file-only config.
    // ─────────────────────────────────────────────────────────────────────

    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "a2a_indep_gate_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    private fun materializeGlobalConfig(
        tempDir: Path,
        content: String
    ): Path {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.write(file, content.toByteArray(Charsets.UTF_8))
        return file
    }

    private fun newToolContext(
        tempDir: Path,
        config: String
    ): ToolExecutionContext {
        materializeGlobalConfig(tempDir, config)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        val composition =
            ServerComposition(
                appConfig = appConfig,
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null
            ).build()
        return composition.toolContext
    }

    private suspend fun createRoot(toolContext: ToolExecutionContext): UUID =
        toolContext.repositoryProvider
            .workItemRepository()
            .create(WorkItem(title = "A2a indep-gate root", type = "project", depth = 0))
            .getOrNull()!!
            .id

    private suspend fun createItem(
        toolContext: ToolExecutionContext,
        rootId: UUID,
        type: String,
        role: Role = Role.WORK
    ): WorkItem =
        toolContext.repositoryProvider
            .workItemRepository()
            .create(
                WorkItem(
                    title = "A2a indep-gate item ($type)",
                    type = type,
                    role = role,
                    parentId = rootId,
                    rootId = rootId,
                    depth = 1
                )
            ).getOrNull() ?: error("fixture: item creation failed for type=$type")

    private suspend fun upsertNote(
        toolContext: ToolExecutionContext,
        itemId: UUID,
        key: String,
        role: String,
        body: String,
        actorId: String?
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
                                    if (actorId != null) {
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", JsonPrimitive(actorId))
                                                put("kind", JsonPrimitive("subagent"))
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                },
                toolContext
            )
        assertTrue((result as JsonObject)["success"]!!.jsonPrimitive.boolean, "note upsert must succeed: $result")
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    private suspend fun getContext(
        toolContext: ToolExecutionContext,
        itemId: UUID
    ): JsonObject = extractData(GetContextTool().execute(buildJsonObject { put("itemId", JsonPrimitive(itemId.toString())) }, toolContext))

    private suspend fun advance(
        toolContext: ToolExecutionContext,
        itemId: UUID,
        trigger: String
    ): JsonObject {
        val result =
            AdvanceItemTool().execute(
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("trigger", JsonPrimitive(trigger))
                                }
                            )
                        }
                    )
                },
                toolContext
            )
        return extractData(result)["results"]!!.jsonArray[0].jsonObject
    }

    // ─────────────────────────────────────────────────────────────────────
    // S1-addendum -- REJECT mode, a schema with no independent_of anywhere -> no `violations` key.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S1-addendum REJECT mode with no independent_of declared anywhere -- no violations key on get_context or advance_item`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "plain-work")
            upsertNote(toolContext, item.id, "only-note", "work", "filled", actorId = "same-agent")

            val context = getContext(toolContext, item.id)
            assertFalse(context["gateStatus"]!!.jsonObject.containsKey("violations"), "gateStatus: ${context["gateStatus"]}")

            val transition = advance(toolContext, item.id, "start")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "transition: $transition")
            assertFalse(transition.containsKey("violations"), "transition: $transition")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S2 -- same actor on N and its declared S seat.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 same actor id on test-manifest and implementation-notes -- WARN applies with violations reported, REJECT blocks`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            suspend fun fixture(toolContext: ToolExecutionContext): WorkItem {
                val root = createRoot(toolContext)
                val item = createItem(toolContext, root, "indep-gate")
                upsertNote(toolContext, item.id, "implementation-notes", "work", "impl details", actorId = "sentinel-actor-Z9")
                upsertNote(toolContext, item.id, "test-manifest", "work", "test details", actorId = "sentinel-actor-Z9")
                return item
            }

            val warnContext = newToolContext(tempDir.resolve("warn"), globalConfig("warn"))
            val warnItem = fixture(warnContext)
            val warnTransition = advance(warnContext, warnItem.id, "start")
            assertTrue(warnTransition["applied"]!!.jsonPrimitive.boolean, "WARN must still apply the transition: $warnTransition")
            val warnViolations = warnTransition["violations"]!!.jsonArray
            assertEquals(1, warnViolations.size, "violations: $warnViolations")
            val warnEntry = warnViolations[0].jsonObject
            assertEquals("test-manifest", warnEntry["key"]!!.jsonPrimitive.content)
            assertEquals("same_actor", warnEntry["constraint"]!!.jsonPrimitive.content)
            assertEquals("implementer", warnEntry["conflictingSeat"]!!.jsonPrimitive.content)

            val rejectContext = newToolContext(tempDir.resolve("reject"), globalConfig("reject"))
            val rejectItem = fixture(rejectContext)
            val rejectTransition = advance(rejectContext, rejectItem.id, "start")
            assertFalse(rejectTransition["applied"]!!.jsonPrimitive.boolean, "REJECT must block: $rejectTransition")
            assertEquals("gate_blocked", rejectTransition["errorCode"]!!.jsonPrimitive.content)
            assertEquals(0, rejectTransition["missingNotes"]!!.jsonArray.size, "a violations-only block carries no missing notes")
            val rejectViolations = rejectTransition["violations"]!!.jsonArray
            assertEquals(warnViolations, rejectViolations, "the computed violations must be identical in WARN and REJECT")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S3 -- fail-closed missing-actor variants, REJECT mode.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 fail-closed -- N actor-less blocks REJECT with missing_actor, distinct present ids do not block`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))

            val root = createRoot(toolContext)
            val blockedItem = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, blockedItem.id, "implementation-notes", "work", "impl", actorId = "s-agent")
            upsertNote(toolContext, blockedItem.id, "test-manifest", "work", "tm", actorId = null)

            val blockedContext = getContext(toolContext, blockedItem.id)
            assertFalse(blockedContext["gateStatus"]!!.jsonObject["canAdvance"]!!.jsonPrimitive.boolean)
            val blockedViolations = blockedContext["gateStatus"]!!.jsonObject["violations"]!!.jsonArray
            assertEquals(1, blockedViolations.size, "violations: $blockedViolations")
            assertEquals("missing_actor", blockedViolations[0].jsonObject["constraint"]!!.jsonPrimitive.content)

            val blockedTransition = advance(toolContext, blockedItem.id, "start")
            assertFalse(blockedTransition["applied"]!!.jsonPrimitive.boolean)

            val cleanItem = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, cleanItem.id, "implementation-notes", "work", "impl", actorId = "agent-s")
            upsertNote(toolContext, cleanItem.id, "test-manifest", "work", "tm", actorId = "agent-n")
            val cleanContext = getContext(toolContext, cleanItem.id)
            val cleanGateStatus = cleanContext["gateStatus"]!!.jsonObject
            assertTrue(cleanGateStatus["canAdvance"]!!.jsonPrimitive.boolean, "gateStatus: $cleanGateStatus")
            assertEquals(0, cleanGateStatus["violations"]!!.jsonArray.size)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S6 -- mode OFF bypasses the check even for a same-actor fixture that would REJECT-block.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 mode OFF applies a same-actor transition with no violations key at all`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("off"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "same-agent")
            upsertNote(toolContext, item.id, "test-manifest", "work", "tm", actorId = "same-agent")

            val context = getContext(toolContext, item.id)
            assertFalse(context["gateStatus"]!!.jsonObject.containsKey("violations"))

            val transition = advance(toolContext, item.id, "start")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "transition: $transition")
            assertFalse(transition.containsKey("violations"), "transition: $transition")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S8 -- a trigger other than start/complete bypasses the independence check entirely, REJECT.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 a same-actor fixture under REJECT still applies on the cancel trigger, no violations key`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "same-agent")
            upsertNote(toolContext, item.id, "test-manifest", "work", "tm", actorId = "same-agent")

            val transition = advance(toolContext, item.id, "cancel")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "cancel must bypass the gate entirely: $transition")
            assertFalse(transition.containsKey("violations"), "transition: $transition")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S9 (MCP subset) -- get_context.gateStatus.violations == advance_item(start)'s violations for
    // the identical fixture state, in both WARN and REJECT.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 get_context and advance_item start report identical violations for the same fixture state`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            listOf("warn", "reject").forEach { mode ->
                val toolContext = newToolContext(tempDir.resolve(mode), globalConfig(mode))
                val root = createRoot(toolContext)

                val contextItem = createItem(toolContext, root, "indep-gate")
                upsertNote(toolContext, contextItem.id, "implementation-notes", "work", "impl", actorId = "shared-actor")
                upsertNote(toolContext, contextItem.id, "test-manifest", "work", "tm", actorId = "shared-actor")
                val contextViolations = getContext(toolContext, contextItem.id)["gateStatus"]!!.jsonObject["violations"]!!.jsonArray

                val advanceItem = createItem(toolContext, root, "indep-gate")
                upsertNote(toolContext, advanceItem.id, "implementation-notes", "work", "impl", actorId = "shared-actor")
                upsertNote(toolContext, advanceItem.id, "test-manifest", "work", "tm", actorId = "shared-actor")
                val advanceViolations = advance(toolContext, advanceItem.id, "start")["violations"]!!.jsonArray

                assertEquals(contextViolations, advanceViolations, "mode=$mode: get_context and advance_item(start) must agree")
            }
        }
}

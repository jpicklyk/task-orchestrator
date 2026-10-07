package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
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
 * get_context gateStatus, S1's REJECT-mode no-independent_of case, S3's fail-closed variants
 * routed through advance_item/get_context, S6's mode-OFF bypass, S8's non-start/complete triggers
 * bypassing the check entirely, S9's MCP-side parity (get_context.gateStatus.violations ==
 * advance_item(start)'s violations for the same state), complete_tree, and both the REJECT-mode
 * (suppressed) and WARN-mode (applied, violations still reported) start/terminal cascade paths
 * (AdvanceCascadeEvent.violations) -- the cascade/complete_tree declarations were appended to
 * decl-a2a.md by the orchestrator after the first arbitration round (class skeletons for
 * CompleteTreeTool.kt/AdvanceItemTool.kt/GetContextTool.kt/AdvanceService.kt). The two WARN-mode
 * cascade tests were briefly removed in an earlier round after NPE'ing on a missing cascadeEvents
 * key; a second arbitration ruling confirmed that was an IMPLEMENTATION gap (an applied WARN-mode
 * cascade event omitted violations, now fixed) and they are restored here with their original
 * oracle unchanged.
 *
 * Explicitly OUT OF SCOPE for this file (stage A2b, per the dispatch contract): REST /gate, REST
 * advance 422/200 mapping, the phase-guard hook.
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
  indep-cascade:
    notes:
      - { key: spec-a, role: queue, required: true, seat: alpha }
      - { key: spec-b, role: queue, required: true, seat: beta, independent_of: [alpha] }
"""
    }

    // The mode value is YAML-quoted: SafeConstructor (YAML 1.1) would otherwise coerce an unquoted
    // "off" to the Boolean false before YamlSchemaParser ever sees a String to match against
    // IndependenceMode -- an unquoted off silently falls back to warn (see also
    // IndependenceConfigParseTest's dedicated probe for this gotcha).
    private fun globalConfig(
        mode: String,
        requireVerified: Boolean = false
    ): String = "independence:\n  mode: \"$mode\"\n  require_verified: $requireVerified\n$SCHEMA_YAML"

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
                shutdownCoordinator = ShutdownCoordinator()
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

    // For cascade fixtures: an explicit parentId/depth so a PARENT (depth 1, child of root) can
    // itself own a CHILD (depth 2), distinct from createItem's root-level children.
    private suspend fun createChild(
        toolContext: ToolExecutionContext,
        rootId: UUID,
        parentId: UUID,
        type: String,
        role: Role,
        depth: Int
    ): WorkItem =
        toolContext.repositoryProvider
            .workItemRepository()
            .create(
                WorkItem(
                    title = "A2a indep-gate cascade child ($type)",
                    type = type,
                    role = role,
                    parentId = parentId,
                    rootId = rootId,
                    depth = depth
                )
            ).getOrNull() ?: error("fixture: child item creation failed for type=$type")

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

    private suspend fun completeTree(
        toolContext: ToolExecutionContext,
        itemId: UUID,
        trigger: String = "complete"
    ): JsonObject {
        val result =
            CompleteTreeTool().execute(
                buildJsonObject {
                    put(
                        "itemIds",
                        buildJsonArray { add(JsonPrimitive(itemId.toString())) }
                    )
                    put("trigger", JsonPrimitive(trigger))
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
    // Orchestrator red-proof finding: mutant M11b survived (the waiver also waived N's OWN
    // missing_actor entry). N itself is actor-less, N's body still opens with the temporal-only
    // marker, and ordering is otherwise valid -- the waiver must never waive missing_actor, so
    // REJECT must still block via advance_item.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 waiver never waives N's own missing_actor entry -- REJECT still blocks via advance_item`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "s-agent")
            upsertNote(
                toolContext,
                item.id,
                "test-manifest",
                "work",
                "independence: temporal-only\nfilled details",
                actorId = null
            )

            val transition = advance(toolContext, item.id, "start")
            assertFalse(
                transition["applied"]!!.jsonPrimitive.boolean,
                "REJECT must still block on an unwaived missing_actor entry: $transition"
            )
            val violations = transition["violations"]!!.jsonArray
            assertEquals(1, violations.size, "violations: $violations")
            val entry = violations[0].jsonObject
            assertEquals("test-manifest", entry["key"]!!.jsonPrimitive.content)
            assertEquals("missing_actor", entry["constraint"]!!.jsonPrimitive.content)
            assertFalse(entry["waived"]?.jsonPrimitive?.boolean ?: false, "must never be marked waived: $entry")
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

    // S7 -- complete_tree: REJECT blocks on the item's own independence violations. CompleteTreeTool
    // DOES expose a structured `violations` array on the per-item result (confirmed empirically
    // this round, not guessed: the prior round's speculative `gateErrors`-only assertion failed
    // with `gateErrors: []` alongside a populated `violations` array and a top-level `error`
    // string), mirroring AdvanceItemTool/GetContextTool's convention -- `gateErrors` stays empty
    // for a violations-only block, exactly like AdvanceItemTool's missingNotes=[] convention.

    @Test
    fun `S7 complete_tree -- REJECT blocks a same-actor item, the violations array names the note`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "same-agent")
            upsertNote(toolContext, item.id, "test-manifest", "work", "tm", actorId = "same-agent")

            val r = completeTree(toolContext, item.id, trigger = "start")
            assertFalse(r["applied"]!!.jsonPrimitive.boolean, "REJECT must block complete_tree on an independence violation: $r")
            val violations = r["violations"]!!.jsonArray
            assertEquals(1, violations.size, "violations: $violations")
            val entry = violations[0].jsonObject
            assertEquals("test-manifest", entry["key"]!!.jsonPrimitive.content)
            assertEquals("same_actor", entry["constraint"]!!.jsonPrimitive.content)
            assertEquals("implementer", entry["conflictingSeat"]!!.jsonPrimitive.content)
        }

    // B1 (orchestrator review follow-up): the APPLIED complete_tree entry in WARN mode must carry
    // the exact `violations` entry, not just apply silently -- mirrors advance_item's own
    // WARN-mode convention (AdvanceResult.violations on a successful transition).
    @Test
    fun `S7 complete_tree -- WARN proceeds despite the same-actor fixture, the applied entry carries the exact violations entry`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("warn"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "same-agent")
            upsertNote(toolContext, item.id, "test-manifest", "work", "tm", actorId = "same-agent")

            val r = completeTree(toolContext, item.id, trigger = "start")
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "WARN must still apply the transition: $r")
            val violations = r["violations"]!!.jsonArray
            assertEquals(1, violations.size, "violations: $violations")
            val entry = violations[0].jsonObject
            assertEquals("test-manifest", entry["key"]!!.jsonPrimitive.content)
            assertEquals("test-author", entry["seat"]!!.jsonPrimitive.content)
            assertEquals("same_actor", entry["constraint"]!!.jsonPrimitive.content)
            assertEquals("implementer", entry["conflictingSeat"]!!.jsonPrimitive.content)
        }

    // S7 -- start cascade: child.start (QUEUE->WORK) attempts to ALSO cascade the parent
    // (QUEUE->WORK). REJECT suppresses the parent cascade on the PARENT's OWN independence
    // violation while the child's own (independently-actored, non-violating) advance still stands.

    @Test
    fun `S7 start cascade -- REJECT suppresses the parent cascade on the parent's own independence violation`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val parent = createChild(toolContext, root, root, "indep-cascade", Role.QUEUE, depth = 1)
            upsertNote(toolContext, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
            upsertNote(toolContext, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
            val child = createChild(toolContext, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
            upsertNote(toolContext, child.id, "spec-a", "queue", "a", actorId = "c-agent-1")
            upsertNote(toolContext, child.id, "spec-b", "queue", "b", actorId = "c-agent-2")

            val transition = advance(toolContext, child.id, "start")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "the child's own advance must stand: $transition")

            val cascades = transition["cascadeEvents"]!!.jsonArray
            assertEquals(1, cascades.size, "cascadeEvents: $cascades")
            val cascade = cascades[0].jsonObject
            assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertFalse(cascade["applied"]!!.jsonPrimitive.boolean, "the parent cascade must be suppressed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
            assertEquals("same_actor", cascadeViolations[0].jsonObject["constraint"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S7 start cascade -- WARN proceeds the parent cascade, reporting violations on the cascade event`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("warn"))
            val root = createRoot(toolContext)
            val parent = createChild(toolContext, root, root, "indep-cascade", Role.QUEUE, depth = 1)
            upsertNote(toolContext, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
            upsertNote(toolContext, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
            val child = createChild(toolContext, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
            upsertNote(toolContext, child.id, "spec-a", "queue", "a", actorId = "c-agent-1")
            upsertNote(toolContext, child.id, "spec-b", "queue", "b", actorId = "c-agent-2")

            val transition = advance(toolContext, child.id, "start")
            val cascade = transition["cascadeEvents"]!!.jsonArray[0].jsonObject
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "WARN must let the parent cascade proceed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
        }

    // S7 -- terminal cascade: child.complete (WORK->TERMINAL) attempts to ALSO cascade the parent
    // to TERMINAL. REJECT suppresses the parent cascade on the parent's own violation.

    @Test
    fun `S7 terminal cascade -- REJECT suppresses the parent cascade on the parent's own independence violation`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val parent = createChild(toolContext, root, root, "indep-cascade", Role.WORK, depth = 1)
            upsertNote(toolContext, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
            upsertNote(toolContext, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
            val child = createChild(toolContext, root, parent.id, "indep-cascade", Role.WORK, depth = 2)
            upsertNote(toolContext, child.id, "spec-a", "queue", "a", actorId = "c-agent-1")
            upsertNote(toolContext, child.id, "spec-b", "queue", "b", actorId = "c-agent-2")

            val transition = advance(toolContext, child.id, "complete")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "the child's own advance must stand: $transition")

            val cascades = transition["cascadeEvents"]!!.jsonArray
            assertEquals(1, cascades.size, "cascadeEvents: $cascades")
            val cascade = cascades[0].jsonObject
            assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertFalse(cascade["applied"]!!.jsonPrimitive.boolean, "the parent terminal cascade must be suppressed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
            assertEquals("same_actor", cascadeViolations[0].jsonObject["constraint"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S7 terminal cascade -- WARN proceeds the parent cascade, reporting violations on the cascade event`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("warn"))
            val root = createRoot(toolContext)
            val parent = createChild(toolContext, root, root, "indep-cascade", Role.WORK, depth = 1)
            upsertNote(toolContext, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
            upsertNote(toolContext, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
            val child = createChild(toolContext, root, parent.id, "indep-cascade", Role.WORK, depth = 2)
            upsertNote(toolContext, child.id, "spec-a", "queue", "a", actorId = "c-agent-1")
            upsertNote(toolContext, child.id, "spec-b", "queue", "b", actorId = "c-agent-2")

            val transition = advance(toolContext, child.id, "complete")
            val cascade = transition["cascadeEvents"]!!.jsonArray[0].jsonObject
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "WARN must let the parent terminal cascade proceed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
        }

    // ─────────────────────────────────────────────────────────────────────
    // A2 review follow-ups (orchestrator, HEAD 0b2633ac round). Oracle: the addendum's JSON
    // emission rule -- gateStatus (get_context) emits `violations` whenever non-null INCLUDING [];
    // advance_item success results, cascade events, and advance_item/complete_tree
    // failure-AND-applied entries emit it ONLY WHEN NON-EMPTY; absent when mode off or no
    // independent_of declared.
    // ─────────────────────────────────────────────────────────────────────

    // F1 -- a clean fixture (independent_of declared, distinct actors, no violations) across every
    // MCP gate surface this file touches: get_context carries `violations: []` (present, empty);
    // advance_item's success result, a cascade event, and complete_tree's applied entry all OMIT
    // the key entirely.
    @Test
    fun `F1 clean fixture -- get_context violations is an empty array, every other surface omits the key`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)

            // get_context: violations: [] -- present, non-null, empty.
            val ctxItem = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, ctxItem.id, "implementation-notes", "work", "impl", actorId = "agent-s")
            upsertNote(toolContext, ctxItem.id, "test-manifest", "work", "tm", actorId = "agent-n")
            val gateStatus = getContext(toolContext, ctxItem.id)["gateStatus"]!!.jsonObject
            assertTrue(gateStatus.containsKey("violations"), "gateStatus must carry the key even when empty: $gateStatus")
            assertEquals(0, gateStatus["violations"]!!.jsonArray.size)

            // advance_item success result: no violations key at all.
            val advItem = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, advItem.id, "implementation-notes", "work", "impl", actorId = "agent-s")
            upsertNote(toolContext, advItem.id, "test-manifest", "work", "tm", actorId = "agent-n")
            val transition = advance(toolContext, advItem.id, "start")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean)
            assertFalse(transition.containsKey("violations"), "transition: $transition")

            // cascade event: no violations key -- parent and child both clean, indep-cascade schema.
            val parent = createChild(toolContext, root, root, "indep-cascade", Role.QUEUE, depth = 1)
            upsertNote(toolContext, parent.id, "spec-a", "queue", "a", actorId = "p-agent-1")
            upsertNote(toolContext, parent.id, "spec-b", "queue", "b", actorId = "p-agent-2")
            val child = createChild(toolContext, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
            upsertNote(toolContext, child.id, "spec-a", "queue", "a", actorId = "c-agent-1")
            upsertNote(toolContext, child.id, "spec-b", "queue", "b", actorId = "c-agent-2")
            val cascadeTransition = advance(toolContext, child.id, "start")
            val cascade = cascadeTransition["cascadeEvents"]!!.jsonArray[0].jsonObject
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "cascade: $cascade")
            assertFalse(cascade.containsKey("violations"), "cascade: $cascade")

            // complete_tree applied entry: no violations key.
            val ctItem = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, ctItem.id, "implementation-notes", "work", "impl", actorId = "agent-s")
            upsertNote(toolContext, ctItem.id, "test-manifest", "work", "tm", actorId = "agent-n")
            val ctApplied = completeTree(toolContext, ctItem.id, trigger = "start")
            assertTrue(ctApplied["applied"]!!.jsonPrimitive.boolean)
            assertFalse(ctApplied.containsKey("violations"), "complete_tree applied: $ctApplied")

            // complete_tree failure entry, unrelated to independence (unfilled notes): no
            // violations key either -- independence is never evaluated when N itself isn't FILLED.
            val failItem = createItem(toolContext, root, "indep-gate")
            val ctFailed = completeTree(toolContext, failItem.id, trigger = "start")
            assertFalse(ctFailed["applied"]!!.jsonPrimitive.boolean)
            assertFalse(ctFailed.containsKey("violations"), "complete_tree failure: $ctFailed")
        }

    // F2 -- the primary `complete` trigger (test-plan S7(ii)): evaluates ALL phases, including a
    // QUEUE-phase N declaring independent_of, regardless of the item's current role. Uses
    // `indep-cascade` (queue-only notes) on a WORK-role item so the evaluation is unambiguously
    // driven by the trigger's ALL-phase scope, not by currentRole happening to equal QUEUE.
    @Test
    fun `F2 primary complete trigger evaluates queue-phase independent_of -- REJECT blocks, WARN applies with violations`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            listOf("warn", "reject").forEach { mode ->
                val toolContext = newToolContext(tempDir.resolve(mode), globalConfig(mode))
                val root = createRoot(toolContext)
                val item = createItem(toolContext, root, "indep-cascade", Role.WORK)
                upsertNote(toolContext, item.id, "spec-a", "queue", "a", actorId = "same-agent")
                upsertNote(toolContext, item.id, "spec-b", "queue", "b", actorId = "same-agent")

                val transition = advance(toolContext, item.id, "complete")
                val violations = transition["violations"]!!.jsonArray
                assertEquals(1, violations.size, "mode=$mode violations: $violations")
                assertEquals("same_actor", violations[0].jsonObject["constraint"]!!.jsonPrimitive.content)
                if (mode == "reject") {
                    assertFalse(transition["applied"]!!.jsonPrimitive.boolean, "REJECT must block on complete: $transition")
                } else {
                    assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "WARN must apply on complete: $transition")
                }
            }
        }

    // F3 (gate-path half; the predicate-level half is IndependencePredicateTest's own F3) -- N
    // itself lacking VERIFIED status under require_verified produces its own unverified entry (no
    // conflictingSeat), reachable through the real advance_item path. Notes written via
    // ManageNotesTool default to the NoOp verifier (no `verification` at all), which is itself
    // "!= VERIFIED" per the addendum's rule (2).
    @Test
    fun `F3 gate-path -- N itself unverified under require_verified produces its own unverified entry`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("warn", requireVerified = true))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "agent-s")
            upsertNote(toolContext, item.id, "test-manifest", "work", "tm", actorId = "agent-n")

            val transition = advance(toolContext, item.id, "start")
            val violations = transition["violations"]!!.jsonArray
            assertTrue(
                violations.any {
                    it.jsonObject["key"]!!.jsonPrimitive.content == "test-manifest" &&
                        it.jsonObject["constraint"]!!.jsonPrimitive.content == "unverified" &&
                        it.jsonObject["conflictingSeat"] == null
                },
                "expected N's own unverified entry (no conflictingSeat): $violations"
            )
        }

    // F4 -- the temporal-only waiver at the gate-path level: `waived: true` present in the JSON
    // entry (both on get_context.gateStatus and on the advance_item transition result), REJECT
    // still applies, and get_context.canAdvance is true. Real sequential upserts (S then N, with a
    // real sleep) establish genuine DB-ordered createdAt values -- no fabricated timestamps.
    @Test
    fun `F4 waiver at gate-path -- waived true in the JSON entry, REJECT still applies, canAdvance true`(
        @TempDir tempDir: Path
    ): Unit =
        runBlocking {
            val toolContext = newToolContext(tempDir, globalConfig("reject"))
            val root = createRoot(toolContext)
            val item = createItem(toolContext, root, "indep-gate")
            upsertNote(toolContext, item.id, "implementation-notes", "work", "impl", actorId = "same-agent")
            Thread.sleep(1500)
            upsertNote(
                toolContext,
                item.id,
                "test-manifest",
                "work",
                "independence: temporal-only\nfilled details",
                actorId = "same-agent"
            )

            val gateStatus = getContext(toolContext, item.id)["gateStatus"]!!.jsonObject
            assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "gateStatus: $gateStatus")
            val ctxViolations = gateStatus["violations"]!!.jsonArray
            assertEquals(1, ctxViolations.size, "violations: $ctxViolations")
            assertTrue(ctxViolations[0].jsonObject["waived"]!!.jsonPrimitive.boolean)

            val transition = advance(toolContext, item.id, "start")
            assertTrue(transition["applied"]!!.jsonPrimitive.boolean, "REJECT must apply on a fully-waived violation list: $transition")
            val violations = transition["violations"]!!.jsonArray
            assertEquals(1, violations.size, "violations: $violations")
            assertTrue(violations[0].jsonObject["waived"]!!.jsonPrimitive.boolean)
        }
}

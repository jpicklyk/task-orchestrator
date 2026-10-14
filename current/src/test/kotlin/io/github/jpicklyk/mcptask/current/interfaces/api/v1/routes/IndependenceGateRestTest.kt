package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.ServerComposition
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope` / `test-plan` / `task-scope-addendum`
 * notes on item `09cd604f` (stage A2b) -- the REST half of S7 (vii/viii: REST /gate `canAdvance`
 * and REST advance 422/200 `violations` mapping, including cascade events), S9 (MCP<->REST
 * parity), S10 (REST redaction -- no actor id ever leaves the REST surface), and the addendum's
 * "seat-less / no independent_of" REST-unchanged guarantee (mirrors S1). The MCP-only halves of
 * S1-S9 (get_context/advance_item internals, predicate-level S2-S6) are IndependencePredicateTest
 * / IndependenceGateMcpTest's job (stage A2a) -- not repeated here except as the MCP-side leg of a
 * parity assertion.
 *
 * NEW-SURFACE: every declaration this file touches on the REST side (`IndependenceViolationDto`,
 * `GateStatusDto.violations`, `AdvanceResponseDto.violations`, `CascadeEventDto.violations`) is
 * introduced by this item. Per the dispatch contract's Test author protocol rule 7, red-proof is
 * orchestrator-run against the addendum's M9/M17/M18 mutation recipes (M9: buildGateBlockedDetails
 * omits violations; M17: REST DTO mapping leaks a conflicting actor id; M18: AdvanceResult.toDto
 * omits violations); this file keeps every new declaration and attempts no revert of its own.
 *
 * Oracle: `task-scope` "Semantics"/"Consumers"/"Redaction" and `task-scope-addendum` "Frozen
 * semantics" + S7/S9/S10 "Scenario detail" -- applied by hand below, never read from the REST
 * mapping/route source.
 *
 * HARNESS (task-scope-addendum "Harness rule", pattern SeatGateParityRestTest / IndependenceGateMcpTest):
 * a single REAL [ServerComposition.build] over a SQLite test DB supplies the
 * [io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext] used for BOTH the MCP
 * tool calls (the `items_read` context probe) and the REST surface, wired through the REAL
 * [installRestApiRoutes] -- never a hand-built replica, and never two independently constructed
 * contexts that could drift. Actor-bearing notes are written via [ManageNotesTool]'s public
 * `actor: {id, kind}` upsert parameter (pattern: IndependenceGateMcpTest's `upsertNote`), never by
 * constructing a Note directly against the repository.
 *
 * FIXTURE SCHEMA: the SAME `indep-gate` / `indep-cascade` / `plain-work` shapes IndependenceGateMcpTest
 * uses (worked example from `task-scope`: `implementer` (S, enters:true) / `test-author` (N,
 * `test-manifest`, `independent_of: [implementer]`) plus a REVIEW-phase `reviewer` seat so `start`
 * on a WORK item resolves to REVIEW). Duplicated as a local YAML literal rather than imported --
 * this file owns no shared fixture with IndependenceGateMcpTest, per the dispatch contract's file
 * ownership (NEW files only).
 */
class IndependenceGateRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

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
      - key: only-note
        role: work
        required: true
  indep-cascade:
    notes:
      - key: spec-a
        role: queue
        required: true
        seat: alpha
      - key: spec-b
        role: queue
        required: true
        seat: beta
        independent_of: [alpha]
"""
        const val SENTINEL_ACTOR = "sentinel-actor-Z9"
    }

    private fun globalConfig(mode: String): String = "independence:\n  mode: \"$mode\"\n$SCHEMA_YAML"

    // ─────────────────────────────────────────────────────────────────────
    // Fixture wiring -- REAL ServerComposition.build over SQLite, global-file-only config, REAL
    // installRestApiRoutes with ContentNegotiation installed first (task-scope-addendum harness rule).
    // ─────────────────────────────────────────────────────────────────────

    private fun materializeGlobalConfig(
        tempDir: Path,
        content: String,
    ) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), content.toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(
        tempDir: Path,
        globalConfig: String,
    ): CompositionResult {
        materializeGlobalConfig(tempDir, globalConfig)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build().also { runBlocking { it.dataStepRunner.run() } }
    }

    private fun Application.configureProductionRestApp(
        composition: CompositionResult,
        serverName: String,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        val authConfig = makeWriteAuthConfig()
        val tokenEntries = authConfig.tokens
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntries,
            allowQueryToken = false,
            serverName = serverName,
            serverVersion = "1.0.0",
            actorAuthEnabled = composition.actorAuthEnabled,
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            appConfig = AppConfig.fromEnv(),
        )
    }

    private suspend fun createRoot(composition: CompositionResult): UUID =
        composition.toolContext.repositoryProvider
            .workItemRepository()
            .create(WorkItem(title = "A2b indep-rest root", type = "project", depth = 0))!!
            .id

    private suspend fun createItem(
        composition: CompositionResult,
        rootId: UUID,
        type: String,
        role: Role = Role.WORK,
    ): WorkItem =
        composition.toolContext.repositoryProvider
            .workItemRepository()
            .create(
                WorkItem(
                    title = "A2b indep-rest item ($type)",
                    type = type,
                    role = role,
                    parentId = rootId,
                    rootId = rootId,
                    depth = 1,
                ),
            ) ?: error("fixture: item creation failed for type=$type")

    private suspend fun createChild(
        composition: CompositionResult,
        rootId: UUID,
        parentId: UUID,
        type: String,
        role: Role,
        depth: Int,
    ): WorkItem =
        composition.toolContext.repositoryProvider
            .workItemRepository()
            .create(
                WorkItem(
                    title = "A2b indep-rest cascade child ($type)",
                    type = type,
                    role = role,
                    parentId = parentId,
                    rootId = rootId,
                    depth = depth,
                ),
            ) ?: error("fixture: child item creation failed for type=$type")

    private suspend fun upsertNote(
        composition: CompositionResult,
        itemId: UUID,
        key: String,
        role: String,
        body: String,
        actorId: String?,
    ) {
        composition.toolContext.putNote(itemId, key, role, body, actor = actorId?.let { ActorClaim(it, ActorKind.SUBAGENT) })
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    private suspend fun getContextMcp(
        composition: CompositionResult,
        itemId: UUID,
    ): JsonObject =
        extractData(
            ItemContextProbe().execute(buildJsonObject { put("itemId", JsonPrimitive(itemId.toString())) }, composition.toolContext),
        )

    private fun parseJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    // ─────────────────────────────────────────────────────────────────────
    // R1 (S7 vii/viii) -- REST /gate canAdvance + REST advance 422 details.violations, REJECT mode.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // R2 (S7 warn / S2) -- REST advance 200 success carries `violations` in WARN mode.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // R4 (S7 cascade) -- REST advance start cascade: REJECT suppresses the parent cascade on the
    // parent's own violation; WARN lets it proceed while reporting violations on the cascade event.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // R5 (S10) -- REST redaction: violations carry seat names only, never an actor id; identical
    // for admin and non-admin callers.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R5 REST context violations are byte-identical for admin and non-admin callers and never contain the actor id`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-r5") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = SENTINEL_ACTOR)
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = SENTINEL_ACTOR)
                    i
                }

            val nonAdminResponse = client.restContext(item.id, TEST_TOKEN)
            val adminResponse = client.restContext(item.id, ADMIN_TOKEN)
            assertEquals(HttpStatusCode.OK, nonAdminResponse.status)
            assertEquals(HttpStatusCode.OK, adminResponse.status)

            val nonAdminBody = nonAdminResponse.bodyAsText()
            val adminBody = adminResponse.bodyAsText()
            assertFalse(nonAdminBody.contains(SENTINEL_ACTOR), "non-admin body must never contain the actor id: $nonAdminBody")
            assertFalse(adminBody.contains(SENTINEL_ACTOR), "admin body must never contain the actor id: $adminBody")

            val nonAdminViolations = nonAdminBody.contextGate()["violations"]!!.jsonArray
            val adminViolations = adminBody.contextGate()["violations"]!!.jsonArray
            assertEquals(1, nonAdminViolations.size, "violations: $nonAdminViolations")
            assertEquals(nonAdminViolations, adminViolations, "violations must be byte-identical for admin and non-admin callers")
            // Sanity: the entry names the SEAT, not the actor -- the field that would leak identity
            // (conflictingSeat) carries a seat name, never the sentinel actor id used above.
            assertEquals("implementer", nonAdminViolations[0].jsonObject["conflictingSeat"]!!.jsonPrimitive.content)
        }

    // ─────────────────────────────────────────────────────────────────────
    // R6 (S1 addendum) -- a schema with no `independent_of` anywhere: REST /gate and REST advance
    // carry no `violations` key at all, even in REJECT mode, even with a same-actor fixture.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // F1 (orchestrator review follow-up, 2026-09-28, HEAD 0b2633ac) -- JSON emission rule per the
    // addendum's "Frozen semantics": REST /gate `gateStatus.violations` is emitted whenever
    // non-null, INCLUDING an empty list; REST advance 200 (`AdvanceResponseDto.violations`),
    // `CascadeEventDto.violations`, and the 422 `details.violations` are emitted ONLY WHEN
    // NON-EMPTY. A clean (distinct-actor) fixture with `independent_of` declared exercises the
    // "non-null but empty" case these earlier R1-R6 scenarios never triggered: R1/R2/R3/R5 all use
    // a same-actor (non-empty) fixture, and R6 uses a schema with no `independent_of` at all
    // (violations is null there, not an empty list) -- neither covers "computed to [] " on the
    // clean/distinct branch.
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // R5 (S10 continued) -- redaction structural guarantee: since violations never carry an actor
    // id BY CONSTRUCTION (task-scope "Redaction"), toggling the redaction env var cannot make one
    // appear. N5 (orchestrator follow-up): no lever exists in this harness to override
    // API_REDACT_NOTE_ATTRIBUTION for a REAL installRestApiRoutes-wired process -- AppConfig.fromEnv()
    // is read directly inside production wiring here (unlike AGENT_CONFIG_DIR, which
    // configureProductionRestApp/buildComposition already override for other reasons), and no
    // existing src/test harness (NoteRoutesTest included, read per the blindness rule's "everything
    // under src/test" allowance) demonstrates overriding it for a real Ktor-wired route under test.
    // Setting the real JVM process env var is not available without an OS-level env change outside
    // this test's control. Skipped as NOT CHEAP rather than attempted with an unreliable workaround;
    // R5/R5b already establish the structural guarantee (no actor id in the body) under the
    // environment's default value.
    // ─────────────────────────────────────────────────────────────────────
}

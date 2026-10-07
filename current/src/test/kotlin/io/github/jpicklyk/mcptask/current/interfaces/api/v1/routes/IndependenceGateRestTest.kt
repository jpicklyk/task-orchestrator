package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext] used for BOTH the MCP
 * tool calls ([GetContextTool], [AdvanceItemTool]) and the REST surface, wired through the REAL
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
        ).build()
    }

    private fun Application.configureProductionRestApp(
        composition: CompositionResult,
        serverName: String,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        val authConfig = makeWriteAuthConfig()
        val tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntries,
            allowQueryToken = false,
            serverName = serverName,
            serverVersion = "1.0.0",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
        )
    }

    private suspend fun createRoot(composition: CompositionResult): UUID =
        composition.toolContext.repositoryProvider
            .workItemRepository()
            .create(WorkItem(title = "A2b indep-rest root", type = "project", depth = 0))
            .getOrNull()!!
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
            ).getOrNull() ?: error("fixture: item creation failed for type=$type")

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
            ).getOrNull() ?: error("fixture: child item creation failed for type=$type")

    private suspend fun upsertNote(
        composition: CompositionResult,
        itemId: UUID,
        key: String,
        role: String,
        body: String,
        actorId: String?,
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
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
                composition.toolContext,
            )
        assertTrue((result as JsonObject)["success"]!!.jsonPrimitive.boolean, "note upsert must succeed: $result")
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
            GetContextTool().execute(buildJsonObject { put("itemId", JsonPrimitive(itemId.toString())) }, composition.toolContext),
        )

    private suspend fun advanceMcp(
        composition: CompositionResult,
        itemId: UUID,
        trigger: String,
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
                                },
                            )
                        },
                    )
                },
                composition.toolContext,
            )
        return extractData(result)["results"]!!.jsonArray[0].jsonObject
    }

    private fun parseJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    // ─────────────────────────────────────────────────────────────────────
    // R1 (S7 vii/viii) -- REST /gate canAdvance + REST advance 422 details.violations, REJECT mode.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R1 REST gate reports canAdvance false and violations, REST advance 422 carries the same violations in REJECT mode`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-r1") }

            val (root, item) =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = SENTINEL_ACTOR)
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = SENTINEL_ACTOR)
                    r to i
                }

            val gateResponse =
                client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.OK, gateResponse.status)
            val gateJson = parseJson(gateResponse.bodyAsText())
            val gateStatus = gateJson["gateStatus"]!!.jsonObject
            assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "gateStatus: $gateStatus")
            val gateViolations = gateStatus["violations"]!!.jsonArray
            assertEquals(1, gateViolations.size, "violations: $gateViolations")
            val gateEntry = gateViolations[0].jsonObject
            assertEquals("test-manifest", gateEntry["key"]!!.jsonPrimitive.content)
            assertEquals("same_actor", gateEntry["constraint"]!!.jsonPrimitive.content)
            assertEquals("implementer", gateEntry["conflictingSeat"]!!.jsonPrimitive.content)

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, advanceResponse.status)
            val advanceJson = parseJson(advanceResponse.bodyAsText())
            assertEquals("gate_blocked", advanceJson["error"]!!.jsonPrimitive.content, "body: $advanceJson")
            val details = advanceJson["details"]!!.jsonObject
            assertEquals(gateViolations, details["violations"]!!.jsonArray, "REST advance 422 details.violations must match REST /gate")

            assertTrue(root == item.rootId, "sanity: fixture item's rootId is the created root")
        }

    // ─────────────────────────────────────────────────────────────────────
    // R2 (S7 warn / S2) -- REST advance 200 success carries `violations` in WARN mode.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R2 REST advance 200 success carries violations when WARN mode applies the transition despite same-actor notes`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("warn"))
            application { configureProductionRestApp(composition, "indep-rest-r2") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = SENTINEL_ACTOR)
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = SENTINEL_ACTOR)
                    i
                }

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, advanceResponse.status, "WARN must still apply: ${advanceResponse.bodyAsText()}")
            val advanceJson = parseJson(advanceResponse.bodyAsText())
            val violations = advanceJson["violations"]!!.jsonArray
            assertEquals(1, violations.size, "violations: $violations")
            val entry = violations[0].jsonObject
            assertEquals("test-manifest", entry["key"]!!.jsonPrimitive.content)
            assertEquals("same_actor", entry["constraint"]!!.jsonPrimitive.content)
            assertEquals("implementer", entry["conflictingSeat"]!!.jsonPrimitive.content)
        }

    // ─────────────────────────────────────────────────────────────────────
    // R3 (S9) -- MCP<->REST parity: get_context, REST /gate, advance_item(start), REST advance all
    // agree on the identical violations array for the same fixture state, in both WARN and REJECT.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R3 get_context, REST gate, advance_item start, and REST advance all report identical violations -- WARN and REJECT`(
        @TempDir tempDir: Path,
    ) {
        // Each mode runs its OWN testApplication: Ktor's TestApplication only accepts a single
        // `application { }` configuration per instance (a second call after the server has
        // already started throws IllegalStateException, confirmed empirically this round after
        // the shared-testApplication-with-two-application-calls version failed compile-green but
        // red at runtime) -- so WARN and REJECT each get a fresh instance rather than sharing one
        // across a loop.
        listOf("warn", "reject").forEach { mode -> runR3(mode, tempDir.resolve(mode)) }
    }

    private fun runR3(
        mode: String,
        tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig(mode))
            application { configureProductionRestApp(composition, "indep-rest-r3-$mode") }

            // Three independently-seeded items, identical fixture state, so each surface is
            // exercised on its OWN item and no surface's write mutates state the next reads.
            val contextItem =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = "shared-actor")
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = "shared-actor")
                    i
                }
            val mcpContextViolations =
                runBlocking { getContextMcp(composition, contextItem.id) }["gateStatus"]!!.jsonObject["violations"]!!.jsonArray

            val gateResponse =
                client.get("/api/v1/items/${contextItem.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
            val restGateViolations = parseJson(gateResponse.bodyAsText())["gateStatus"]!!.jsonObject["violations"]!!.jsonArray
            assertEquals(mcpContextViolations, restGateViolations, "mode=$mode: REST /gate must match get_context")

            val advanceItem =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = "shared-actor")
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = "shared-actor")
                    i
                }
            val restAdvanceViolations =
                if (mode == "reject") {
                    val resp =
                        client.post("/api/v1/items/${advanceItem.id}/advance") {
                            header("Authorization", "Bearer $WRITE_TOKEN")
                            contentType(ContentType.Application.Json)
                            setBody("""{"trigger":"start"}""")
                        }
                    assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
                    parseJson(resp.bodyAsText())["details"]!!.jsonObject["violations"]!!.jsonArray
                } else {
                    val resp =
                        client.post("/api/v1/items/${advanceItem.id}/advance") {
                            header("Authorization", "Bearer $WRITE_TOKEN")
                            contentType(ContentType.Application.Json)
                            setBody("""{"trigger":"start"}""")
                        }
                    assertEquals(HttpStatusCode.OK, resp.status)
                    parseJson(resp.bodyAsText())["violations"]!!.jsonArray
                }

            val mcpAdvanceItem =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = "shared-actor")
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = "shared-actor")
                    i
                }
            val mcpAdvanceTransition = runBlocking { advanceMcp(composition, mcpAdvanceItem.id, "start") }
            val mcpAdvanceViolations = mcpAdvanceTransition["violations"]!!.jsonArray

            assertEquals(
                mcpAdvanceViolations,
                restAdvanceViolations,
                "mode=$mode: REST advance violations must match advance_item(start) violations for the identical fixture",
            )
            assertEquals(mcpContextViolations, mcpAdvanceViolations, "mode=$mode: sanity -- identical fixtures agree end to end")
        }

    // ─────────────────────────────────────────────────────────────────────
    // R4 (S7 cascade) -- REST advance start cascade: REJECT suppresses the parent cascade on the
    // parent's own violation; WARN lets it proceed while reporting violations on the cascade event.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R4a REST advance start cascade -- REJECT suppresses the parent cascade, cascadeEvents violations populated`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-r4a") }

            val child =
                runBlocking {
                    val root = createRoot(composition)
                    val parent = createChild(composition, root, root, "indep-cascade", Role.QUEUE, depth = 1)
                    upsertNote(composition, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
                    upsertNote(composition, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
                    val c = createChild(composition, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
                    upsertNote(composition, c.id, "spec-a", "queue", "a", actorId = "c-agent-1")
                    upsertNote(composition, c.id, "spec-b", "queue", "b", actorId = "c-agent-2")
                    c
                }

            val response =
                client.post("/api/v1/items/${child.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, "the child's own advance must stand: ${response.bodyAsText()}")
            val json = parseJson(response.bodyAsText())
            val cascades = json["cascadeEvents"]!!.jsonArray
            assertEquals(1, cascades.size, "cascadeEvents: $cascades")
            val cascade = cascades[0].jsonObject
            assertFalse(cascade["applied"]!!.jsonPrimitive.boolean, "the parent cascade must be suppressed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
            assertEquals("same_actor", cascadeViolations[0].jsonObject["constraint"]!!.jsonPrimitive.content)
        }

    @Test
    fun `R4b REST advance start cascade -- WARN lets the parent cascade proceed, reporting violations on the cascade event`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("warn"))
            application { configureProductionRestApp(composition, "indep-rest-r4b") }

            val child =
                runBlocking {
                    val root = createRoot(composition)
                    val parent = createChild(composition, root, root, "indep-cascade", Role.QUEUE, depth = 1)
                    upsertNote(composition, parent.id, "spec-a", "queue", "a", actorId = "p-agent")
                    upsertNote(composition, parent.id, "spec-b", "queue", "b", actorId = "p-agent")
                    val c = createChild(composition, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
                    upsertNote(composition, c.id, "spec-a", "queue", "a", actorId = "c-agent-1")
                    upsertNote(composition, c.id, "spec-b", "queue", "b", actorId = "c-agent-2")
                    c
                }

            val response =
                client.post("/api/v1/items/${child.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val cascade = parseJson(response.bodyAsText())["cascadeEvents"]!!.jsonArray[0].jsonObject
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "WARN must let the parent cascade proceed: $cascade")
            val cascadeViolations = cascade["violations"]!!.jsonArray
            assertEquals(1, cascadeViolations.size, "cascade violations: $cascadeViolations")
        }

    // ─────────────────────────────────────────────────────────────────────
    // R5 (S10) -- REST redaction: violations carry seat names only, never an actor id; identical
    // for admin and non-admin callers.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R5 REST gate violations are byte-identical for admin and non-admin callers and never contain the actor id`(
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

            val nonAdminResponse =
                client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            val adminResponse =
                client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $ADMIN_TOKEN") }
            assertEquals(HttpStatusCode.OK, nonAdminResponse.status)
            assertEquals(HttpStatusCode.OK, adminResponse.status)

            val nonAdminBody = nonAdminResponse.bodyAsText()
            val adminBody = adminResponse.bodyAsText()
            assertFalse(nonAdminBody.contains(SENTINEL_ACTOR), "non-admin body must never contain the actor id: $nonAdminBody")
            assertFalse(adminBody.contains(SENTINEL_ACTOR), "admin body must never contain the actor id: $adminBody")

            val nonAdminViolations = parseJson(nonAdminBody)["gateStatus"]!!.jsonObject["violations"]!!.jsonArray
            val adminViolations = parseJson(adminBody)["gateStatus"]!!.jsonObject["violations"]!!.jsonArray
            assertEquals(1, nonAdminViolations.size, "violations: $nonAdminViolations")
            assertEquals(nonAdminViolations, adminViolations, "violations must be byte-identical for admin and non-admin callers")
            // Sanity: the entry names the SEAT, not the actor -- the field that would leak identity
            // (conflictingSeat) carries a seat name, never the sentinel actor id used above.
            assertEquals("implementer", nonAdminViolations[0].jsonObject["conflictingSeat"]!!.jsonPrimitive.content)
        }

    @Test
    fun `R5b REST advance 200 success body never contains the actor id`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("warn"))
            application { configureProductionRestApp(composition, "indep-rest-r5b") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = SENTINEL_ACTOR)
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = SENTINEL_ACTOR)
                    i
                }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains(SENTINEL_ACTOR), "REST advance success body must never contain the actor id: $body")
            assertEquals(1, parseJson(body)["violations"]!!.jsonArray.size)
        }

    // ─────────────────────────────────────────────────────────────────────
    // R6 (S1 addendum) -- a schema with no `independent_of` anywhere: REST /gate and REST advance
    // carry no `violations` key at all, even in REJECT mode, even with a same-actor fixture.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R6 REST gate and REST advance omit the violations key entirely for a schema with no independent_of, REJECT mode`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-r6") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "plain-work")
                    upsertNote(composition, i.id, "only-note", "work", "filled", actorId = "same-agent")
                    i
                }

            val gateResponse =
                client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.OK, gateResponse.status)
            val gateJson = parseJson(gateResponse.bodyAsText())
            assertFalse(gateJson["gateStatus"]!!.jsonObject.containsKey("violations"), "gateStatus: ${gateJson["gateStatus"]}")

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(
                HttpStatusCode.OK,
                advanceResponse.status,
                "no independent_of anywhere -> nothing to block on: ${advanceResponse.bodyAsText()}",
            )
            val advanceJson = parseJson(advanceResponse.bodyAsText())
            assertFalse(advanceJson.containsKey("violations"), "advance response: $advanceJson")
        }

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

    @Test
    fun `F1 REST gate emits an empty violations array for a clean distinct-actor fixture, REST advance 200 omits it entirely`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-f1") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = "agent-s")
                    upsertNote(composition, i.id, "test-manifest", "work", "tm", actorId = "agent-n")
                    i
                }

            val gateResponse =
                client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.OK, gateResponse.status)
            val gateStatus = parseJson(gateResponse.bodyAsText())["gateStatus"]!!.jsonObject
            assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "gateStatus: $gateStatus")
            assertTrue(gateStatus.containsKey("violations"), "gateStatus must carry the key even when empty: $gateStatus")
            assertEquals(0, gateStatus["violations"]!!.jsonArray.size, "violations: ${gateStatus["violations"]}")

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, advanceResponse.status, "clean fixture must apply: ${advanceResponse.bodyAsText()}")
            val advanceJson = parseJson(advanceResponse.bodyAsText())
            assertFalse(
                advanceJson.containsKey("violations"),
                "REST advance 200 must OMIT violations when the computed list is empty: $advanceJson",
            )
        }

    @Test
    fun `F1b a 422 caused by missing notes only, independence clean -- details omits violations entirely`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-f1b") }

            val item =
                runBlocking {
                    val r = createRoot(composition)
                    val i = createItem(composition, r, "indep-gate")
                    // Only implementation-notes filled; test-manifest (the N note declaring
                    // independent_of) is left UNFILLED, so the missing-notes gate blocks the
                    // transition while the independence check never evaluates the unfilled N at
                    // all (task-scope-addendum: "Unfilled N is never evaluated").
                    upsertNote(composition, i.id, "implementation-notes", "work", "impl", actorId = "agent-s")
                    i
                }

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, advanceResponse.status)
            val advanceJson = parseJson(advanceResponse.bodyAsText())
            assertEquals("gate_blocked", advanceJson["error"]!!.jsonPrimitive.content, "body: $advanceJson")
            val details = advanceJson["details"]!!.jsonObject
            assertTrue(
                details["missingNotes"]!!.jsonArray.isNotEmpty(),
                "sanity: this 422 must be caused by missing notes, not independence: $details",
            )
            assertFalse(
                details.containsKey("violations"),
                "a 422 with an empty computed violations list must OMIT the key entirely: $details",
            )
        }

    @Test
    fun `F1c a clean start cascade omits violations on the cascade event entirely`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, globalConfig("reject"))
            application { configureProductionRestApp(composition, "indep-rest-f1c") }

            val child =
                runBlocking {
                    val root = createRoot(composition)
                    val parent = createChild(composition, root, root, "indep-cascade", Role.QUEUE, depth = 1)
                    upsertNote(composition, parent.id, "spec-a", "queue", "a", actorId = "p-agent-alpha")
                    upsertNote(composition, parent.id, "spec-b", "queue", "b", actorId = "p-agent-beta")
                    val c = createChild(composition, root, parent.id, "indep-cascade", Role.QUEUE, depth = 2)
                    upsertNote(composition, c.id, "spec-a", "queue", "a", actorId = "c-agent-1")
                    upsertNote(composition, c.id, "spec-b", "queue", "b", actorId = "c-agent-2")
                    c
                }

            val response =
                client.post("/api/v1/items/${child.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, "the child's own advance must stand: ${response.bodyAsText()}")
            val json = parseJson(response.bodyAsText())
            val cascades = json["cascadeEvents"]!!.jsonArray
            assertEquals(1, cascades.size, "cascadeEvents: $cascades")
            val cascade = cascades[0].jsonObject
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "a clean parent cascade must apply: $cascade")
            assertFalse(
                cascade.containsKey("violations"),
                "a clean cascade event must OMIT violations entirely, not report an empty array: $cascade",
            )
        }

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

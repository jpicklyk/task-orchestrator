package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * Independently authored against the frozen `task-scope` / `task-scope-addendum` notes on item
 * `79cd4f0c` (A1, stage A1c) -- S12 (`missingBySeat` parity across all four surfaces) and S13's REST
 * half (`features` on `GET /api/v1/info` and the well-known document; the MCP-tool half of S13 --
 * `query_items(schema)` type and item paths -- is already covered by `SeatServingMcpTest`, A1b).
 *
 * Harness (task-scope-addendum "Harness rule"): a single REAL [ServerComposition.build] over an H2
 * in-memory DB supplies the [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext]
 * used for BOTH the MCP tool calls ([GetContextTool], [AdvanceItemTool]) and the REST surface, which
 * is wired through the REAL [installRestApiRoutes] (mirrors `ItemGateRouteTest` S15 /
 * `EffectiveConfigRoutesTest` S22's production-topology pattern) -- never a hand-built replica, and
 * never two independently constructed contexts that could drift.
 */
class SeatGateParityRestTest {
    companion object {
        private val ROOT_ID: UUID = UUID.fromString("a1c00000-0000-4000-8000-000000000000")
        private val QUEUE_ITEM_ID: UUID = UUID.fromString("a1c00000-0000-4000-8000-000000000001")

        // A queue-phase seat-aware schema where a single seat (`planner`) owns both required queue
        // notes, so the gate failure produces exactly one non-empty missingBySeat bucket.
        private const val SEAT_AWARE_GLOBAL_CONFIG = """
work_item_schemas:
  seat-parity:
    seats:
      - { name: planner, phase: queue, enters: true }
    notes:
      - key: diagnosis
        role: queue
        required: true
        seat: planner
      - key: task-scope
        role: queue
        required: true
        seat: planner
"""

        private const val SEATLESS_GLOBAL_CONFIG = """
work_item_schemas:
  plain-seatless:
    notes:
      - key: plan
        role: queue
        required: true
"""

        // N1 -- a WORK-phase schema with TWO seats, each owning its own missing required work note,
        // so the gate failure produces two non-empty missingBySeat buckets (not just one, as S12's
        // queue-phase fixture does).
        private const val SEAT_AWARE_WORK_GLOBAL_CONFIG = """
work_item_schemas:
  seat-parity-work:
    seats:
      - { name: implementer, phase: work, enters: true }
      - { name: reviewer2, phase: work }
    notes:
      - key: impl-notes
        role: work
        required: true
        seat: implementer
      - key: extra-notes
        role: work
        required: true
        seat: reviewer2
"""
        private val WORK_ITEM_ID: UUID = UUID.fromString("a1c00000-0000-4000-8000-000000000002")
    }

    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "a1c_seat_parity_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

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
        return ServerComposition(appConfig = appConfig, databaseManager = buildDatabaseManager(), shutdownCoordinator = null).build()
    }

    private fun seedQueueItem(composition: CompositionResult): WorkItem {
        val repo = composition.toolContext.repositoryProvider
        return runBlocking {
            repo
                .workItemRepository()
                .create(WorkItem(id = ROOT_ID, title = "Seat parity root", type = "project", depth = 0))
                .getOrNull() ?: error("fixture: root creation failed")
            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = QUEUE_ITEM_ID,
                        title = "Seat parity queue item",
                        type = "seat-parity",
                        role = Role.QUEUE,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: item creation failed")
        }
    }

    private fun seedWorkItem(composition: CompositionResult): WorkItem {
        val repo = composition.toolContext.repositoryProvider
        return runBlocking {
            repo
                .workItemRepository()
                .create(WorkItem(id = ROOT_ID, title = "Seat parity root", type = "project", depth = 0))
                .getOrNull() ?: error("fixture: root creation failed")
            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = WORK_ITEM_ID,
                        title = "Seat parity work item",
                        type = "seat-parity-work",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: item creation failed")
        }
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
        routing { wellKnownRoutes(serverName = serverName, serverVersion = "1.0.0") }
    }

    private fun extractData(result: kotlinx.serialization.json.JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    // ─────────────────────────────────────────────────────────────────────
    // S12 -- missingBySeat identical across get_context, REST /gate, advance_item(start), REST advance 422
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S12 missingBySeat is identical across get_context, REST gate, advance_item start, and REST advance 422`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, SEAT_AWARE_GLOBAL_CONFIG)
            seedQueueItem(composition)
            application { configureProductionRestApp(composition, "seat-parity-s12") }

            // 1. get_context
            val contextResult =
                runBlocking {
                    GetContextTool().execute(
                        buildJsonObject { put("itemId", JsonPrimitive(QUEUE_ITEM_ID.toString())) },
                        composition.toolContext,
                    )
                }
            val contextData = extractData(contextResult)
            val contextGateStatus = contextData["gateStatus"]!!.jsonObject
            assertFalse(contextGateStatus["canAdvance"]!!.jsonPrimitive.boolean, "queue gate must be blocked")
            val contextMissingBySeat = contextGateStatus["missingBySeat"]!!.jsonObject

            // Sanity: the queue-phase gate is missing diagnosis and task-scope, both owned by planner.
            val asStringMap: (JsonObject) -> Map<String, List<String>> = { obj ->
                obj.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            }
            assertEquals(mapOf("planner" to listOf("diagnosis", "task-scope")), asStringMap(contextMissingBySeat))

            // 2. REST GET /gate
            val gateResponse =
                client.get("/api/v1/items/$QUEUE_ITEM_ID/gate") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, gateResponse.status)
            val gateJson = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject
            val gateMissingBySeat = gateJson["gateStatus"]!!.jsonObject["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, gateMissingBySeat, "REST /gate must match get_context")

            // 3. advance_item(start)
            val advanceMcpResult =
                runBlocking {
                    AdvanceItemTool().execute(
                        buildJsonObject {
                            put(
                                "transitions",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("itemId", JsonPrimitive(QUEUE_ITEM_ID.toString()))
                                            put("trigger", JsonPrimitive("start"))
                                        },
                                    )
                                },
                            )
                        },
                        composition.toolContext,
                    )
                }
            val advanceMcpData = extractData(advanceMcpResult)
            val advanceMcpTransition = advanceMcpData["results"]!!.jsonArray[0].jsonObject
            assertFalse(advanceMcpTransition["applied"]!!.jsonPrimitive.boolean, "queue gate must block the start trigger")
            val advanceMcpMissingBySeat = advanceMcpTransition["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, advanceMcpMissingBySeat, "advance_item(start) must match get_context")

            // 4. REST POST /advance
            val advanceRestResponse =
                client.post("/api/v1/items/$QUEUE_ITEM_ID/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, advanceRestResponse.status)
            val advanceRestJson = Json.parseToJsonElement(advanceRestResponse.bodyAsText()).jsonObject
            assertEquals("gate_blocked", advanceRestJson["error"]!!.jsonPrimitive.content, "body: $advanceRestJson")
            val advanceRestMissingBySeat = advanceRestJson["details"]!!.jsonObject["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, advanceRestMissingBySeat, "REST advance 422 must match get_context")
        }

    // ─────────────────────────────────────────────────────────────────────
    // N1 -- S12 parity repeated for a WORK-phase item with >= 2 missingBySeat buckets, not just
    // the single-bucket QUEUE-phase fixture S12 itself uses.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `N1 missingBySeat is identical across all four surfaces for a WORK-phase item with two buckets`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, SEAT_AWARE_WORK_GLOBAL_CONFIG)
            seedWorkItem(composition)
            application { configureProductionRestApp(composition, "seat-parity-n1") }

            val asStringMap: (JsonObject) -> Map<String, List<String>> = { obj ->
                obj.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            }

            // 1. get_context
            val contextResult =
                runBlocking {
                    GetContextTool().execute(
                        buildJsonObject { put("itemId", JsonPrimitive(WORK_ITEM_ID.toString())) },
                        composition.toolContext,
                    )
                }
            val contextData = extractData(contextResult)
            val contextGateStatus = contextData["gateStatus"]!!.jsonObject
            assertFalse(contextGateStatus["canAdvance"]!!.jsonPrimitive.boolean, "work gate must be blocked")
            val contextMissingBySeat = contextGateStatus["missingBySeat"]!!.jsonObject
            assertEquals(
                mapOf("implementer" to listOf("impl-notes"), "reviewer2" to listOf("extra-notes")),
                asStringMap(contextMissingBySeat),
                "sanity: two non-empty buckets, one per seat",
            )

            // 2. REST GET /gate
            val gateResponse =
                client.get("/api/v1/items/$WORK_ITEM_ID/gate") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, gateResponse.status)
            val gateJson = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject
            val gateMissingBySeat = gateJson["gateStatus"]!!.jsonObject["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, gateMissingBySeat, "REST /gate must match get_context")

            // 3. advance_item(complete)
            val advanceMcpResult =
                runBlocking {
                    AdvanceItemTool().execute(
                        buildJsonObject {
                            put(
                                "transitions",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("itemId", JsonPrimitive(WORK_ITEM_ID.toString()))
                                            put("trigger", JsonPrimitive("complete"))
                                        },
                                    )
                                },
                            )
                        },
                        composition.toolContext,
                    )
                }
            val advanceMcpData = extractData(advanceMcpResult)
            val advanceMcpTransition = advanceMcpData["results"]!!.jsonArray[0].jsonObject
            assertFalse(advanceMcpTransition["applied"]!!.jsonPrimitive.boolean, "work gate must block the complete trigger")
            val advanceMcpMissingBySeat = advanceMcpTransition["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, advanceMcpMissingBySeat, "advance_item(complete) must match get_context")

            // 4. REST POST /advance
            val advanceRestResponse =
                client.post("/api/v1/items/$WORK_ITEM_ID/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"complete"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, advanceRestResponse.status)
            val advanceRestJson = Json.parseToJsonElement(advanceRestResponse.bodyAsText()).jsonObject
            assertEquals("gate_blocked", advanceRestJson["error"]!!.jsonPrimitive.content, "body: $advanceRestJson")
            val advanceRestMissingBySeat = advanceRestJson["details"]!!.jsonObject["missingBySeat"]!!.jsonObject
            assertEquals(contextMissingBySeat, advanceRestMissingBySeat, "REST advance 422 must match get_context")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S13 (REST half) -- features == ["seats","dispatchBySeat"] on /info and the well-known doc
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S13 features are exactly seats and dispatchBySeat on GET api v1 info and the well-known document (seat-aware config)`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, SEAT_AWARE_GLOBAL_CONFIG)
            seedQueueItem(composition)
            application { configureProductionRestApp(composition, "seat-parity-s13-aware") }

            val infoResponse =
                client.get("/api/v1/info") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, infoResponse.status)
            val infoJson = Json.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
            assertEquals(
                listOf("seats", "dispatchBySeat", "independent_of", "rules"),
                infoJson["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "body: $infoJson",
            )

            val wellKnownResponse = client.get("/.well-known/mcp-task-orchestrator.json")
            assertEquals(HttpStatusCode.OK, wellKnownResponse.status)
            val wellKnownJson = Json.parseToJsonElement(wellKnownResponse.bodyAsText()).jsonObject
            assertEquals(
                listOf("seats", "dispatchBySeat", "independent_of", "rules"),
                wellKnownJson["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "body: $wellKnownJson",
            )
        }

    @Test
    fun `S13 features are exactly seats and dispatchBySeat on GET api v1 info and the well-known document (seat-less config)`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir, SEATLESS_GLOBAL_CONFIG)
            application { configureProductionRestApp(composition, "seat-parity-s13-seatless") }

            val infoResponse =
                client.get("/api/v1/info") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, infoResponse.status)
            val infoJson = Json.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
            assertEquals(
                listOf("seats", "dispatchBySeat", "independent_of", "rules"),
                infoJson["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "features must be advertised even for a seat-less config: $infoJson",
            )

            val wellKnownResponse = client.get("/.well-known/mcp-task-orchestrator.json")
            assertEquals(HttpStatusCode.OK, wellKnownResponse.status)
            val wellKnownJson = Json.parseToJsonElement(wellKnownResponse.bodyAsText()).jsonObject
            assertEquals(
                listOf("seats", "dispatchBySeat", "independent_of", "rules"),
                wellKnownJson["features"]!!.jsonArray.map { it.jsonPrimitive.content },
                "features must be advertised even for a seat-less config: $wellKnownJson",
            )
        }
}

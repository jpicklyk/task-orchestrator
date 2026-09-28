package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored (blind test author) against the frozen `task-scope` / `test-plan` /
 * `task-scope-addendum` notes on item `840e700a` (A3) -- the REST leg of S1, S4, S6, S8, plus S7
 * (scope enforcement) and probes P1 (REST PUT %2F ingestion) and P2 (hex-prefix rootId, REST
 * side). The MCP-side twins of these scenarios live in `QueryRulesToolTest.kt` -- per this item's
 * File ownership table (no shared harness file between the two).
 *
 * Harness (contract "Public-API rule" / "Harness rule"): every REST call runs through the REAL
 * [installRestApiRoutes] with `ContentNegotiation` installed first, wired from a REAL
 * [ServerComposition.build] over an H2 in-memory DB -- mirrors [ItemSchemaRouteTest] /
 * [SeatGateParityRestTest]'s production-topology pattern; never a bare `ruleRoutes(...)` call.
 */
class RuleRoutesTest {
    // --- Shared composition wiring (own copy) ---

    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "rule_routes_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    private fun materializeGlobalConfig(tempDir: Path) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(tempDir: Path): CompositionResult {
        materializeGlobalConfig(tempDir)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(appConfig = appConfig, databaseManager = buildDatabaseManager(), shutdownCoordinator = null).build()
    }

    private fun tokenEntriesFor(authConfig: ApiAuthConfig): Map<HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, principal) ->
            BearerTokenStore.TokenEntry(principal, expiresAt = null)
        } ?: emptyMap()

    private fun Application.configureProductionRuleApp(
        composition: CompositionResult,
        authConfig: ApiAuthConfig = makeTestAuthConfig(),
    ) {
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntriesFor(authConfig),
            allowQueryToken = false,
            serverName = "rule-routes-test",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
        )
    }

    private fun parseObj(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /** Fixture setup via the actual MCP stash surface -- direct-repo shortcuts are used only where the oracle doesn't name a surface. */
    private fun stashViaTool(
        context: io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext,
        rootId: UUID,
        slug: String,
        body: String,
    ) {
        val result =
            runBlocking {
                ManagePlanDocumentsTool().execute(
                    params(
                        "operation" to JsonPrimitive("stash"),
                        "rootId" to JsonPrimitive(rootId.toString()),
                        "slug" to JsonPrimitive(slug),
                        "body" to JsonPrimitive(body),
                    ),
                    context,
                )
            } as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "fixture stash failed: $result")
    }

    private fun stashDirect(
        repo: RepositoryProvider,
        rootId: UUID,
        slug: String,
        body: String,
    ) {
        runBlocking { repo.planDocumentRepository().stash(rootId, slug, body) }
    }

    companion object {
        /** Own copy of the S1 fixture body -- see `QueryRulesToolTest.FIXTURE_B` for the field-by-field rationale. */
        const val FIXTURE_B =
            "First line ends here.\r\nSecond line: em-dash —, e-acute é, rocket 🚀, and a\ttab.  "
    }

    // -----------------------------------------------------------------------
    // S1 (REST leg) -- GET .../rules/{key} serves the stored body byte-identical
    // -----------------------------------------------------------------------

    @Test
    fun `S1 GET roots rootId rules key serves the stashed body UTF-8 byte-identical`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val repo = composition.toolContext.repositoryProvider
            val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S1 REST Root", depth = 0)).getOrNull()!! }
            stashViaTool(composition.toolContext, root.id, "rule/protocol.entry-seat", FIXTURE_B)
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${root.id}/rules/protocol.entry-seat") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = parseObj(response.bodyAsText())
            val servedBody = body["body"]!!.jsonPrimitive.content
            assertEquals(FIXTURE_B, servedBody)
            assertContentEquals(FIXTURE_B.toByteArray(Charsets.UTF_8), servedBody.toByteArray(Charsets.UTF_8))
            assertEquals(root.id.toString(), body["rootId"]!!.jsonPrimitive.content)
            assertEquals("protocol.entry-seat", body["key"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // Probe P1 -- REST PUT with %2F ingestion lands slug rule/k, served by GET rules/k
    // -----------------------------------------------------------------------

    @Test
    fun `probe P1 - REST PUT roots rootId plans rule pct2F k ingests slug rule slash k and is served by GET roots rootId rules k`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root =
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "P1 Root", depth = 0)
                        ).getOrNull()!!
                }
            application { configureProductionRuleApp(composition, authConfig = makeWriteAuthConfig()) }

            val putResponse =
                client.put("/api/v1/roots/${root.id}/plans/rule%2Fp1-key") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("P1 rule body via REST PUT ingestion.")
                }
            // Record the outcome either way, per addendum P1 -- but if ingestion succeeded, the GET
            // below must serve exactly what was PUT.
            if (putResponse.status == HttpStatusCode.OK) {
                val getResponse =
                    client.get("/api/v1/roots/${root.id}/rules/p1-key") {
                        header("Authorization", "Bearer $TEST_TOKEN")
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status, "PUT with %2F-encoded slug succeeded; GET rules/p1-key must serve it")
                val body = parseObj(getResponse.bodyAsText())
                assertEquals("P1 rule body via REST PUT ingestion.", body["body"]!!.jsonPrimitive.content)
            } else {
                assertTrue(
                    putResponse.status.value in 400..499,
                    "if %2F ingestion is rejected it must be a client error, not a server error: ${putResponse.status}",
                )
            }
        }

    // -----------------------------------------------------------------------
    // S4 (REST leg) -- GET roots rootId rules lists only valid rule/ keys, sorted, no body
    // -----------------------------------------------------------------------

    @Test
    fun `S4 GET roots rootId rules lists only valid rule slugs sorted by key with no body`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val repo = composition.toolContext.repositoryProvider
            val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S4 REST Root", depth = 0)).getOrNull()!! }
            stashDirect(repo, root.id, "rule/test-author", "a")
            stashDirect(repo, root.id, "rule/protocol.entry-seat", "b")
            stashDirect(repo, root.id, "rules/x", "c")
            stashDirect(repo, root.id, "rule/", "d")
            stashDirect(repo, root.id, "rule/Bad Key", "e")
            stashDirect(repo, root.id, "plan-a", "f")
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${root.id}/rules") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = parseObj(response.bodyAsText())
            val rules = body["rules"]!!.jsonArray
            val keys = rules.map { it.jsonObject["key"]!!.jsonPrimitive.content }
            assertEquals(listOf("protocol.entry-seat", "test-author"), keys)
            rules.forEach { assertFalse(it.jsonObject.containsKey("body")) }
        }

    // -----------------------------------------------------------------------
    // S6 (REST leg) -- error envelope: unknown key, unknown root, non-depth-0, malformed rootId
    // -----------------------------------------------------------------------

    @Test
    fun `S6 GET roots rootId rules key returns 404 rule_not_found for an unknown key at a known root`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root =
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "S6 REST Root", depth = 0)
                        ).getOrNull()!!
                }
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${root.id}/rules/no-such-rule") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("rule_not_found"), "body: ${response.bodyAsText()}")
        }

    @Test
    fun `S6 GET roots rootId rules key returns 404 not_found for an unknown root`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${UUID.randomUUID()}/rules/some-key") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("not_found"))
        }

    @Test
    fun `S6 GET roots rootId rules key returns 422 validation_error for a non-depth-0 root`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val repo = composition.toolContext.repositoryProvider
            val (_, child) =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "S6 REST Root", depth = 0)).getOrNull()!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(title = "S6 REST Child", parentId = r.id, rootId = r.id, depth = 1)
                            ).getOrNull()!!
                    r to c
                }
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${child.id}/rules/some-key") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("validation_error"))
        }

    // -----------------------------------------------------------------------
    // S7 -- 403 scope_forbidden for both get and list, token scoped to a different root
    // -----------------------------------------------------------------------

    @Test
    fun `S7 GET roots rootId rules key and GET roots rootId rules both return 403 scope_forbidden for a token scoped to a different root`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val repo = composition.toolContext.repositoryProvider
            val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S7 Root", depth = 0)).getOrNull()!! }
            stashDirect(repo, root.id, "rule/protected", "protected body")
            val scopedAuthConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application { configureProductionRuleApp(composition, authConfig = scopedAuthConfig) }

            val getResponse =
                client.get("/api/v1/roots/${root.id}/rules/protected") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, getResponse.status)
            assertTrue(getResponse.bodyAsText().contains("scope_forbidden"))
            assertFalse(getResponse.bodyAsText().contains("protected body"), "a 403 must not leak the body")

            val listResponse =
                client.get("/api/v1/roots/${root.id}/rules") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, listResponse.status)
            assertTrue(listResponse.bodyAsText().contains("scope_forbidden"))
        }

    // -----------------------------------------------------------------------
    // S8 (REST leg) -- 400 bad_request for a malformed key
    // -----------------------------------------------------------------------

    @Test
    fun `S8 GET roots rootId rules key returns 400 bad_request for a key that fails the grammar`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root =
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "S8 REST Root", depth = 0)
                        ).getOrNull()!!
                }
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/${root.id}/rules/Bad-Key-Uppercase") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun `S8 GET roots rootId rules key returns 400 bad_request for a malformed rootId`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            application { configureProductionRuleApp(composition) }

            val response =
                client.get("/api/v1/roots/not-a-uuid/rules/some-key") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    // -----------------------------------------------------------------------
    // Probe P2 (REST side) -- REST rootId is parsed as a full UUID, no hex-prefix resolution
    // -----------------------------------------------------------------------

    @Test
    fun `probe P2 - an 8-hex prefix of a real rootId is 400 bad_request on REST (no hex-prefix resolution, unlike MCP)`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root =
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "P2 REST Root", depth = 0)
                        ).getOrNull()!!
                }
            application { configureProductionRuleApp(composition) }

            val hexPrefix =
                root.id
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
            val response =
                client.get("/api/v1/roots/$hexPrefix/rules/some-key") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }
}

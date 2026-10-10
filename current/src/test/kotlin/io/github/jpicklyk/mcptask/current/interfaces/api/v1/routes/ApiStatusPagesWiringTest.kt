package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored for item `0c07190d` (error catalog adoption), review finding N2: the StatusPages safety net
 * must be WIRED into the REST API by `installRestApiRoutes`, not merely work when a test installs
 * `installApiStatusPages()` by hand (that is `ApiStatusPagesTest`).
 *
 * Harness: the production topology used by `ItemSchemaRouteTest` / `SeatGateParityRestTest` -- the REAL
 * `ServerComposition.build()`, `ContentNegotiation`, then the REAL `installRestApiRoutes`. The test then adds one
 * route under `/api/v1` and one outside it, both of which throw, to the same application. Nothing here installs
 * StatusPages itself, so the 500 body can only come from the production wiring.
 *
 * Oracle: task-scope Build step 7 and AC4 -- an uncaught `/api/v1` exception returns 500
 * `{"error":"internal","message":"Internal server error"}` and never exception text; other paths keep Ktor's default
 * 500 (no ErrorDto body).
 *
 * Red proof (orchestrator-run, disposable copy): delete the `installApiStatusPages()` call from `installRestApiRoutes`
 * -> the /api/v1 test turns red (empty 500 body).
 */
class ApiStatusPagesWiringTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun buildComposition(tempDir: Path): CompositionResult {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun tokenEntriesFor(
        authConfig: ApiAuthConfig
    ): Map<io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, principal) ->
            BearerTokenStore.TokenEntry(principal, expiresAt = null)
        } ?: emptyMap()

    private fun Application.productionRestAppWithThrowingRoutes(composition: CompositionResult) {
        install(ContentNegotiation) { json(McpJson) }
        val authConfig = makeTestAuthConfig()
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntriesFor(authConfig),
            allowQueryToken = false,
            serverName = "status-pages-wiring-test",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy
        )
        routing {
            get("/api/v1/p16-wiring-boom") { throw RuntimeException("SELECT secret FROM tokens WHERE v = 'hunter2'") }
            get("/p16-outside-boom") { throw RuntimeException("SELECT outside the api") }
        }
    }

    @Test
    fun `N2 an uncaught exception in a route under api v1 returns the exact 500 internal body through installRestApiRoutes`(
        @TempDir tempDir: Path
    ) = testApplication {
        val composition = buildComposition(tempDir)
        application { productionRestAppWithThrowingRoutes(composition) }

        val response =
            client.get("/api/v1/p16-wiring-boom") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.InternalServerError, response.status, "body: $body")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("error", "message"), json.keys, "body: $body")
        assertEquals("internal", json["error"]?.jsonPrimitive?.content)
        assertEquals("Internal server error", json["message"]?.jsonPrimitive?.content)
        assertFalse(body.contains("SELECT") || body.contains("hunter2"), "the exception text must never reach the client: $body")
    }

    @Test
    fun `N2 probe a path outside api v1 keeps Ktor's default 500 with no ErrorDto body`(
        @TempDir tempDir: Path
    ) = testApplication {
        val composition = buildComposition(tempDir)
        application { productionRestAppWithThrowingRoutes(composition) }

        val response = client.get("/p16-outside-boom") { header("Authorization", "Bearer $TEST_TOKEN") }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.InternalServerError, response.status, "body: $body")
        assertTrue(!body.contains("\"error\":\"internal\""), "no ErrorDto outside /api/v1: $body")
    }
}

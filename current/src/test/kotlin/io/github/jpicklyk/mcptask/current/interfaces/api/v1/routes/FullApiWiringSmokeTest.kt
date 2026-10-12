package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors.configureCors
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals

/**
 * App-wiring / startup smoke test for the whole REST surface.
 *
 * Unlike the per-route integration tests (which each register a single route group in
 * isolation), this test registers the ENTIRE `/api/v1` surface the way [CurrentMcpServer]
 * wires it in production — all read, config, service, and write route groups under one
 * authenticated `/api/v1` block, plus the unauthenticated discovery + health endpoints.
 *
 * It is the regression guard for route-TOPOLOGY bugs that single-group tests cannot catch —
 * most notably the `requireCapability` `DuplicatePluginException` (multiple READ groups
 * installing the same route-scoped plugin onto a merged empty-path child node) which would
 * have failed real server startup but passed every per-route unit test. If the combined
 * surface fails to wire, the very first request below throws at application setup.
 *
 * The scenario runs twice: under the test fixture's [OutsideUnitPolicy.IMPLICIT] ([FullApiWiringSmokeTest])
 * and under the production [OutsideUnitPolicy.FAIL] ([FullApiWiringSmokeFailPolicyTest]), so a route write
 * made outside a unit of work fails CI instead of only failing in production.
 */
class FullApiWiringSmokeTest : FullApiWiringSmokeScenarios(OutsideUnitPolicy.IMPLICIT)

/** [FullApiWiringSmokeScenarios] under the production [OutsideUnitPolicy.FAIL]. */
class FullApiWiringSmokeFailPolicyTest : FullApiWiringSmokeScenarios(OutsideUnitPolicy.FAIL)

abstract class FullApiWiringSmokeScenarios(
    outsideUnitPolicy: OutsideUnitPolicy
) {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod(outsideUnitPolicy)

    /** Mirrors CurrentMcpServer's production `/api/v1` registration (same plugins + route order). */
    private fun Application.configureFullApi(repo: DefaultRepositoryProvider) {
        install(ContentNegotiation) { json(McpJson) }
        install(CORS) { configureCors(appConfig = AppConfig.fromEnv()) }
        install(SSE)

        val bus = ApiEventBus()
        // No store is decorated: every write service records its own events rows through the unit of work.
        val decorated = repo
        val authConfig = makeWriteAuthConfig() // TEST_TOKEN=read, WRITE_TOKEN=all-write, ADMIN_TOKEN=admin
        val tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }

        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    this.authConfig = authConfig
                    this.tokenEntries = tokenEntries
                }
                serviceRoutes(
                    repositoryProvider = decorated,
                    serverName = "smoke",
                    serverVersion = "test",
                    actorAuthEnabled = false,
                )
                // Phase 3 read
                itemRoutes(decorated)
                // Phase 4 config: only the published JSON Schema is a route (everything else is the `config` tool)
                configSchemaRoute()
            }
            // Phase 6 SSE — registered OUTSIDE the ApiBearerAuth block (sibling /api/v1 route)
            // so the header-only ApiBearerAuth plugin does not intercept it; the SSE route does
            // its own inline pre-flight auth. Mirrors CurrentMcpServer production wiring.
            route("/api/v1") {
                eventRoutes(bus, tokenEntries, allowQueryToken = false, authCheckIntervalSeconds = 60)
            }
            wellKnownRoutes(serverName = "smoke", serverVersion = "test")
        }
    }

    @Test
    fun `full api v1 surface boots and gates are active across the combined routing tree`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureFullApi(repo) }

            // Reaching ANY route proves the full surface wired without a DuplicatePluginException
            // (the requireCapability collision class) at application setup time.

            // Unauthenticated endpoints
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/health").status, "health is public")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/.well-known/mcp-task-orchestrator.json").status,
                "discovery doc is public",
            )

            // Read route: 401 without auth, 200 with a READ token — capability gate active
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/schema").status, "read requires auth")

            // Phase 4 config route reachable on the combined surface
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }.status,
                "config schema reachable with READ",
            )

            // Phase 6: SSE events route — proving it is WIRED. A request with NO auth is rejected
            // by the pre-flight inline auth BEFORE any stream is opened, so .status completes.
            // We deliberately do NOT open the stream with a valid token here: an SSE endpoint is an
            // infinite stream, so a plain GET-with-auth would hang the smoke test until the harness
            // timeout. The 401 below proves the route is mounted and its inline auth runs; the
            // streaming behaviour is covered by EventRoutesTest with a bounded SSE client.
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/events").status,
                "events route is wired and its inline auth rejects unauthenticated requests",
            )
        }
}

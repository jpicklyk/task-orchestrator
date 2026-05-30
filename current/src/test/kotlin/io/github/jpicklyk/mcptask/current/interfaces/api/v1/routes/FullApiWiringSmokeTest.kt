package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors.configureCors
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventPublishingRepositoryProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 */
class FullApiWiringSmokeTest {
    /** Mirrors CurrentMcpServer's production `/api/v1` registration (same plugins + route order). */
    private fun Application.configureFullApi(repo: DefaultRepositoryProvider) {
        install(ContentNegotiation) { json(McpJson) }
        install(CORS) { configureCors() }
        install(SSE)

        val bus = ApiEventBus()
        val decorated = EventPublishingRepositoryProvider(repo, bus)
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
                noteRoutes(decorated)
                dependencyRoutes(decorated)
                transitionRoutes(decorated)
                searchRoutes(decorated)
                // Phase 4 config
                configRoutes(NoOpNoteSchemaService)
                // Phase 5 write
                itemWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED, IdempotencyCache(), NoOpNoteSchemaService)
                noteWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED, IdempotencyCache())
                dependencyWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED)
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
            val repo = buildH2RepositoryProvider()
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
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/items").status, "read requires auth")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/items") { header("Authorization", "Bearer $TEST_TOKEN") }.status,
                "READ token reads items",
            )

            // Phase 4 config route reachable on the combined surface
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/config") { header("Authorization", "Bearer $TEST_TOKEN") }.status,
                "config reachable with READ",
            )

            // Write route: READ-only token is forbidden (capability gate distinguishes read vs write
            // even though both groups share the /api/v1 parent — the bug class this test guards against)
            val forbidden =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"should-not-create"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status, "READ token cannot write")

            // Write route: WRITE token succeeds — write path is wired and granted
            val created =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"smoke-created"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status, "WRITE token creates item")

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

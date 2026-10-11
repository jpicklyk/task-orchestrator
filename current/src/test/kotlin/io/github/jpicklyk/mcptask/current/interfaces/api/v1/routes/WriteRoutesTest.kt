package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

/**
 * Configures a test Ktor application with the shared bearer auth and the item READ routes.
 *
 * The REST write routes this helper used to mount are gone: Q13 removed `POST /items/{id}/advance` (now
 * the `advance` tool) and Q18 removed `POST /items`, `PATCH /items/{id}` and `DELETE /items/{id}` (now
 * `items_write`). Callers keep it for the authenticated read surface. The WRITE_TOKEN principal has all
 * write capabilities; the TEST_TOKEN principal has READ only.
 */
fun Application.configureWriteTestApp(
    repo: DefaultRepositoryProvider,
    authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig(),
    unitOfWork: UnitOfWork
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    authConfig.tokens.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    }
            }
            itemRoutes(repo)
        }
    }
}

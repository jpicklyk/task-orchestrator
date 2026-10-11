package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ServerFeatures
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Registers service meta-endpoints under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /api/v1/info` — authenticated; returns [ServiceInfoDto] with server metadata and
 *   the caller's resolved capabilities.
 * - `GET /api/v1/health` — unauthenticated; performs a lightweight DB probe and returns
 *   200 OK when the database is reachable or 503 when it is not.
 *
 * Auth note: `/health` intentionally bypasses the auth plugin — the route is registered
 * OUTSIDE the [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth]
 * plugin scope (see [CurrentMcpServer.runHttpTransport] for the routing topology).
 *
 * @param repositoryProvider Used by `/health` to probe the database. Optional to allow
 *   integration tests to omit it when testing routes that do not hit the DB.
 * @param serverName The server name as returned in [ServiceInfoDto.serverName].
 * @param serverVersion The server version as returned in [ServiceInfoDto.version].
 * @param actorAuthEnabled Whether the actor_authentication feature is enabled in the runtime config.
 * @param configService When given, `/info` reports the stored server config head's warnings as
 *   [ServiceInfoDto.configWarnings] (the reasons recorded when the global file was imported at boot). A server-head read
 *   fault answers 503 `config_unavailable`; `/health` never reads it.
 */
fun Route.serviceRoutes(
    repositoryProvider: RepositoryProvider? = null,
    serverName: String = "mcp-task-orchestrator-current",
    serverVersion: String = "unknown",
    actorAuthEnabled: Boolean = false,
    configService: ConfigService? = null,
) {
    // GET /api/v1/info — requires auth (auth plugin is installed on the parent route block)
    get("/info") {
        val principal = call.attributes.getOrNull(ApiPrincipalKey)
        val capabilities = principal?.capabilities?.map { it.name.lowercase().replace('_', '-') } ?: emptyList()
        val configWarnings =
            try {
                configService?.serverHead()?.warnings ?: emptyList()
            } catch (e: PerRootConfigUnavailableException) {
                call.respondError(LegacyRestCode.CONFIG_UNAVAILABLE, e.message)
                return@get
            }

        call.respond(
            HttpStatusCode.OK,
            ServiceInfoDto(
                serverName = serverName,
                version = serverVersion,
                apiVersion = "v1",
                capabilities = capabilities,
                claimModeAvailable = true,
                actorAuthenticationEnabled = actorAuthEnabled,
                features = ServerFeatures.ADVERTISED,
                configWarnings = configWarnings,
            ),
        )
    }

    // GET /api/v1/health — no auth required (registered outside the auth scope; see CurrentMcpServer)
    get("/health") {
        if (repositoryProvider == null) {
            // No repository available in test context — report healthy
            call.respond(HttpStatusCode.OK, HealthDto(status = "ok", dbReachable = true))
            return@get
        }
        val dbOk =
            try {
                repositoryProvider.itemStore().ping() // suspend call - Ktor route handlers are coroutines
                true
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                false
            }

        if (dbOk) {
            call.respond(HttpStatusCode.OK, HealthDto(status = "ok", dbReachable = true))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthDto(status = "degraded", dbReachable = false))
        }
    }
}

// DTO for GET /api/v1/info
@Serializable
data class ServiceInfoDto(
    val serverName: String,
    val version: String,
    val apiVersion: String,
    val capabilities: List<String>,
    val claimModeAvailable: Boolean,
    val actorAuthenticationEnabled: Boolean,
    // A1c: advertises server-wide optional-response features (e.g. "seats", "dispatchBySeat") a
    // client can look for regardless of whether any config on this server currently uses them —
    // see ServerFeatures.ADVERTISED.
    val features: List<String> = ServerFeatures.ADVERTISED,
    // The stored server config head's warnings (reason strings, the form push warnings use): what the boot import of
    // the global file recorded, e.g. one per legacy top-level key. Empty when there is no server config.
    val configWarnings: List<String> = emptyList(),
)

// DTO for GET /api/v1/health
@Serializable
data class HealthDto(
    val status: String,
    val dbReachable: Boolean,
)

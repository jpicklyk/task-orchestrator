package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ServerFeatures
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.apiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.envelopeReqId
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelopeError
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.ConfigSupport
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Registers service meta-endpoints under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /api/v1/info` — authenticated; answers the ok envelope whose `data` is [ServiceInfoDto] (server metadata
 *   and the caller's resolved capabilities).
 * - `GET /api/v1/health` — unauthenticated; performs a lightweight DB probe and returns
 *   200 OK when the database is reachable or 503 when it is not.
 *
 * Auth note: `/health` intentionally bypasses the auth plugin — the route is registered
 * OUTSIDE the [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth]
 * plugin scope (see `CurrentMcpServer.runHttpTransport` for the routing topology).
 *
 * @param repositoryProvider Used by `/health` to probe the database. Optional to allow
 *   integration tests to omit it when testing routes that do not hit the DB.
 * @param serverName The server name as returned in [ServiceInfoDto.serverName].
 * @param serverVersion The server version as returned in [ServiceInfoDto.version].
 * @param verifier The REST API credential verifier in force: `none` (unauthenticated mode), `bearer` or `jwks`.
 * @param maxStale With `jwks`, the oldest a cached key set may be, in seconds, and still be served after a failed
 *   refresh ([ServiceInfoDto.maxStale]); null otherwise.
 * @param configService When given, `/info` reports the stored server config head's warnings as
 *   [ServiceInfoDto.configWarnings] (the reasons recorded when the global file was imported at boot). A server-head read
 *   fault answers 503 `unavailable`, an unloadable head 422 `config_invalid`; `/health` never reads it.
 */
fun Route.serviceRoutes(
    repositoryProvider: RepositoryProvider? = null,
    serverName: String = "mcp-task-orchestrator-current",
    serverVersion: String = "unknown",
    configService: ConfigService? = null,
    verifier: String = "none",
    maxStale: Long? = null,
) {
    // GET /api/v1/info — requires auth (auth plugin is installed on the parent route block)
    get("/info") {
        val capabilities = call.apiPrincipal().capabilities.map { it.name.lowercase().replace('_', '-') }
        // A server-head fault answers as the config tool's typed head faults: unreadable -> unavailable, unloadable ->
        // config_invalid.
        val configWarnings =
            when (val head = ConfigSupport.guarded { Outcome.Ok(configService?.serverHead()?.warnings ?: emptyList()) }) {
                is Outcome.Ok -> head.value
                is Outcome.Err -> {
                    call.respondEnvelopeError(head.error)
                    return@get
                }
            }

        val info =
            ServiceInfoDto(
                serverName = serverName,
                version = serverVersion,
                apiVersion = "v1",
                capabilities = capabilities,
                claimModeAvailable = true,
                verifier = verifier,
                maxStale = maxStale,
                features = ServerFeatures.ADVERTISED,
                configWarnings = configWarnings,
            )
        call.respondEnvelope(
            EnvelopeEncoder.outcome(Outcome.Ok(info), call.envelopeReqId()) {
                infoJson.encodeToJsonElement(ServiceInfoDto.serializer(), it)
            }
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

/** Encodes [ServiceInfoDto] as the `data` of the `/info` envelope (every field, nulls omitted). */
private val infoJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

// DTO for GET /api/v1/info (the envelope `data`)
@Serializable
data class ServiceInfoDto(
    val serverName: String,
    val version: String,
    val apiVersion: String,
    val capabilities: List<String>,
    val claimModeAvailable: Boolean,
    // The REST API credential verifier in force: "none" | "bearer" | "jwks".
    val verifier: String = "none",
    // With the jwks verifier: max_stale_seconds, the oldest a cached key set may be and still be served; omitted otherwise.
    val maxStale: Long? = null,
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

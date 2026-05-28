package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Registers the `.well-known` discovery endpoint.
 *
 * This route is mounted at the **root** of the Ktor routing tree (not under `/api/v1`) and
 * requires **no authentication**. It provides a minimal service-metadata document so clients
 * can discover the API base URL and version without prior knowledge.
 *
 * Endpoint:
 * - `GET /.well-known/mcp-task-orchestrator.json` — returns [WellKnownDto] with public metadata.
 *
 * @param serverName The public service name. Defaults to the `MCP_SERVER_NAME` env variable.
 * @param serverVersion The build version string.
 */
fun Route.wellKnownRoutes(
    serverName: String = System.getenv("MCP_SERVER_NAME") ?: "mcp-task-orchestrator-current",
    serverVersion: String = "unknown",
) {
    get("/.well-known/mcp-task-orchestrator.json") {
        call.respond(
            HttpStatusCode.OK,
            WellKnownDto(
                name = serverName,
                version = serverVersion,
                apiVersion = "v1",
                apiUrl = "/api/v1",
            ),
        )
    }
}

// DTO for GET /.well-known/mcp-task-orchestrator.json
@Serializable
data class WellKnownDto(
    val name: String,
    val version: String,
    val apiVersion: String,
    val apiUrl: String,
)

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// Internal diagnostic routes -- present from Phase 1 through end of Phase 2 only.
//
// TODO: Remove this route at the end of Phase 2 once /api/v1/info becomes the real
//   principal-aware service endpoint. This route exists solely to support integration
//   tests during Phase 1 and Phase 2 development.
fun Routing.registerInternalRoutes() {
    route("/api/v1/_internal") {

        // GET /api/v1/_internal/whoami
        //
        // Returns the resolved ApiPrincipal as JSON. Used by integration tests to verify
        // that the authentication plugin correctly resolves and attaches the principal.
        //
        // Returns 401 if no principal is attached (auth plugin not installed or auth failed).
        get("/whoami") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey)
                    ?: run {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "unauthenticated"),
                        )
                        return@get
                    }

            call.respond(
                HttpStatusCode.OK,
                WhoAmIResponse(
                    tokenId = principal.tokenId,
                    authMode = principal.authMode.name,
                    capabilities = principal.capabilities.map { it.name },
                    scope =
                        ScopeResponse(
                            rootIds = principal.scope.rootIds?.map { it.toString() },
                            tagsInclude = principal.scope.tagsInclude.toList(),
                        ),
                ),
            )
        }
    }
}

@Serializable
data class WhoAmIResponse(
    val tokenId: String,
    val authMode: String,
    val capabilities: List<String>,
    val scope: ScopeResponse,
)

@Serializable
data class ScopeResponse(
    val rootIds: List<String>?,
    val tagsInclude: List<String>,
)

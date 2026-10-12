package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import org.slf4j.LoggerFactory

private val authzLogger = LoggerFactory.getLogger("ApiAuthorization")

// Configuration holder for a capability-check plugin instance.
private class CapabilityPluginConfig {
    var requiredCapability: ApiCapability = ApiCapability.READ
}

// Factory function that creates a route-scoped plugin requiring a given capability.
// Installed via Route.requireCapability DSL below.
private fun makeCapabilityPlugin(cap: ApiCapability) =
    createRouteScopedPlugin("RequireCapability-$cap", ::CapabilityPluginConfig) {
        pluginConfig.requiredCapability = cap
        onCall { call ->
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            if (principal == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "unauthenticated", "error_description" to "No authenticated principal"),
                )
                return@onCall
            }

            val allowed = principal.capabilities.grants(pluginConfig.requiredCapability)

            if (!allowed) {
                authzLogger.debug(
                    "Access denied: tokenId='{}' requires {} but has {}",
                    principal.tokenId,
                    pluginConfig.requiredCapability,
                    principal.capabilities,
                )
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf(
                        "error" to "insufficient_scope",
                        "error_description" to
                            "Required capability: ${pluginConfig.requiredCapability.name.lowercase().replace('_', '-')}",
                    ),
                )
                return@onCall
            }
        }
    }

// Transparent route selector that matches without consuming a path segment.
//
// Crucially relies on reference (identity) equality — each instance is distinct — so that
// [Route.createChild] always creates a NEW child node. This is what isolates each
// requireCapability scope: with `route("")` (empty path) Ktor MERGES the child into a
// shared node, so two requireCapability(sameCap) blocks under one parent (e.g. several
// READ route groups under /api/v1) would install the same route-scoped capability plugin
// twice on that node and throw DuplicatePluginException at wiring time. A fresh transparent
// child per call avoids the merge while keeping the URL unchanged.
private class CapabilityRouteSelector(
    private val cap: ApiCapability,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(capability:${cap.name.lowercase()})"
}

// Route-level DSL helper that enforces a required ApiCapability before delegating to build.
//
// Wraps build() in a fresh transparent child route carrying a route-scoped plugin that
// checks the resolved ApiPrincipal has cap (or ADMIN). Returns 403 Forbidden on failure,
// 401 when unauthenticated.
//
// Usage:
//   requireCapability(ApiCapability.READ) {
//       get("/items") { ... }
//   }
//
// Each call creates a DISTINCT child via createChild(CapabilityRouteSelector) so multiple
// groups requiring the same capability under one parent route do not collide on the plugin
// key — see CapabilityRouteSelector for why this matters.
fun Route.requireCapability(
    cap: ApiCapability,
    build: Route.() -> Unit,
): Route {
    val child = createChild(CapabilityRouteSelector(cap))
    child.install(makeCapabilityPlugin(cap))
    child.build()
    return child
}

/**
 * The ONE capability predicate, shared by the REST route wrapper and the tool dispatcher:
 * [ApiCapability.ADMIN] implies every capability, anything else must be listed.
 */
fun Set<ApiCapability>.grants(required: ApiCapability): Boolean = ApiCapability.ADMIN in this || required in this

/** The kebab-case spelling [ApiCapability.fromConfigString] parses (`write-items`, `read`, ...). */
fun ApiCapability.configName(): String = name.lowercase().replace('_', '-')

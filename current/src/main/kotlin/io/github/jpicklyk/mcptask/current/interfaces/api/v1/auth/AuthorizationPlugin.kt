package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

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

            val allowed =
                principal.capabilities.contains(ApiCapability.ADMIN) ||
                    principal.capabilities.contains(pluginConfig.requiredCapability)

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

// Route-level DSL helper that enforces a required ApiCapability before delegating to build.
//
// Wraps build() in an unnamed child route with a route-scoped plugin that checks the
// resolved ApiPrincipal has cap. Returns 403 Forbidden on failure.
//
// Usage (Phase 2):
//   requireCapability(ApiCapability.READ) {
//       get("/api/v1/items") { ... }
//   }
//
// Phase 2 will wire this into route definitions once the auth plugin is installed.
// The Ktor pattern chosen: createRouteScopedPlugin + install on a child route, which
// is the correct Ktor 3.x approach for route-scoped capability enforcement.
fun Route.requireCapability(
    cap: ApiCapability,
    build: Route.() -> Unit,
): Route =
    route("") {
        install(makeCapabilityPlugin(cap))
        build()
    }

// Scope-enforcement helper for individual item access.
//
// Checks whether itemId is accessible to the authenticated principal by walking its
// ancestor chain. Returns true when access is allowed, false when the route should
// respond with 403.
//
// Scope rules (section 4.5 of the plan):
// 1. If principal.scope.rootIds is null -- unrestricted; access always granted.
// 2. If principal.scope.rootIds is non-null -- the item (or any ancestor) must be one of
//    the listed root UUIDs.
// 3. If principal.scope.tagsInclude is non-empty -- the item must carry at least one of
//    those tags (tag scope does NOT walk ancestors).
//
// Routes should respond 403 (not 404) when this returns false -- deliberately leaking
// existence to avoid making scope a fuzzing oracle for callers with valid tokens.
suspend fun enforceScopeForItem(
    call: ApplicationCall,
    itemId: UUID,
    repo: WorkItemRepository,
): Boolean {
    val principal =
        call.attributes.getOrNull(ApiPrincipalKey)
            ?: run {
                authzLogger.warn("enforceScopeForItem called without authenticated principal for itemId={}", itemId)
                return false
            }

    val scope = principal.scope

    // rootIds check -- walk ancestor chain
    val rootIds = scope.rootIds
    if (rootIds != null) {
        val chainResult = repo.findAncestorChains(setOf(itemId))
        if (chainResult.isError()) {
            authzLogger.warn(
                "Failed to fetch ancestor chain for itemId={}: {}",
                itemId,
                (chainResult as Result.Error).error.message,
            )
            return false
        }
        val chain = chainResult.getOrNull()!!
        val ancestors = chain[itemId] ?: emptyList()
        // The chain is root-first; itemId itself is NOT in the chain, so we add it manually.
        val idsInChain = ancestors.map { it.id }.toSet() + itemId

        if (idsInChain.none { it in rootIds }) {
            authzLogger.debug(
                "Scope denied: itemId={} not under any of rootIds={} for tokenId='{}'",
                itemId,
                rootIds,
                principal.tokenId,
            )
            return false
        }
    }

    // Tag scope check (item only -- no ancestor walk)
    val tagsInclude = scope.tagsInclude
    if (tagsInclude.isNotEmpty()) {
        val itemResult = repo.getById(itemId)
        if (itemResult.isError()) {
            authzLogger.warn(
                "Failed to fetch item {} for tag scope check: {}",
                itemId,
                (itemResult as Result.Error).error.message,
            )
            return false
        }
        val item: WorkItem = itemResult.getOrNull()!!
        val itemTags =
            item.tags
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

        if (itemTags.none { it in tagsInclude }) {
            authzLogger.debug(
                "Tag scope denied: item {} has tags {} but principal requires one of {}",
                itemId,
                itemTags,
                tagsInclude,
            )
            return false
        }
    }

    return true
}

// Capability check helper for use inside route handlers without the DSL wrapper.
//
// Returns true when the authenticated principal has cap or ApiCapability.ADMIN.
// Routes should respond 403 when this returns false.
fun hasCapability(
    call: ApplicationCall,
    cap: ApiCapability,
): Boolean {
    val principal = call.attributes.getOrNull(ApiPrincipalKey) ?: return false
    return principal.capabilities.contains(ApiCapability.ADMIN) || principal.capabilities.contains(cap)
}

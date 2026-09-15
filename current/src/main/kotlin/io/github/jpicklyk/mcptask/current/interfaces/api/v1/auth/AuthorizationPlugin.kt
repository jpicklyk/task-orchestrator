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
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
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

// Parses a WorkItem's comma-separated `tags` column into a trimmed, non-empty tag set.
//
// Null/blank input yields the empty set. Whitespace around each element is trimmed
// (" alpha , beta " -> {"alpha", "beta"}) and empty elements are dropped, matching the
// CSV convention used everywhere else in the item layer.
private fun parseItemTagCsv(tags: String?): Set<String> =
    tags
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

/**
 * Single source of truth for the `tags_include` half of scope enforcement.
 *
 * Returns true when a principal with this scope is allowed to see an item whose `tags`
 * column is [tags]. The rule mirrors [ApiScope]: `tagsInclude` applies to the item itself
 * (it never walks the ancestor chain), membership is exact (no prefix/substring matching),
 * and an EMPTY `tagsInclude` means "no tag constraint" -- so an unscoped or null principal
 * always returns true.
 *
 * This is the predicate every read route must apply to items it did not already push through
 * [enforceScopeForItem]: list rows, tree/children descendants, breadcrumb ancestors, search
 * hits and transition subjects. Before it existed each route hand-rolled the same CSV split,
 * and five of them simply forgot to -- letting a tag-scoped token read out-of-scope data.
 */
fun ApiPrincipal?.allowsItemTags(tags: String?): Boolean {
    val tagsInclude = this?.scope?.tagsInclude ?: emptySet()
    if (tagsInclude.isEmpty()) return true
    return parseItemTagCsv(tags).any { it in tagsInclude }
}

/**
 * True when this principal carries a non-empty `tags_include` allowlist.
 *
 * Routes use this as the branch guard so that non-tag-scoped callers take exactly the code
 * path they took before tag filtering existed -- identical queries, identical totals.
 */
fun ApiPrincipal?.hasTagScope(): Boolean = !this?.scope?.tagsInclude.isNullOrEmpty()

/**
 * Drops every item the principal's `tags_include` allowlist excludes.
 *
 * A no-op (returns the receiver unchanged) when the principal has no tag scope.
 */
fun List<WorkItem>.filterByTagScope(principal: ApiPrincipal?): List<WorkItem> =
    if (!principal.hasTagScope()) this else filter { principal.allowsItemTags(it.tags) }

/**
 * Resolves which of [itemIds] the principal's tag scope admits.
 *
 * For surfaces that carry only an item id and not its tags -- search hits (`SearchHitDto`
 * exposes `itemId` alone) and transition rows -- so the tags have to be looked up before the
 * rows can be filtered.
 *
 * Returns [itemIds] unchanged, with no DB round-trip, when the principal has no tag scope.
 * Fails CLOSED on a lookup failure: an id whose tags could not be read is not returned, since
 * the alternative is handing out rows nobody verified.
 */
suspend fun allowedItemIdsForTagScope(
    principal: ApiPrincipal?,
    itemIds: Set<UUID>,
    repo: WorkItemRepository,
): Set<UUID> {
    if (!principal.hasTagScope() || itemIds.isEmpty()) return itemIds
    return when (val result = repo.findByIds(itemIds)) {
        is Result.Success ->
            result.data
                .filterByTagScope(principal)
                .map { it.id }
                .toSet()
        is Result.Error -> {
            authzLogger.warn(
                "Tag scope filter: failed to load {} items for tag lookup: {}",
                itemIds.size,
                result.error.message,
            )
            emptySet()
        }
    }
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

    // Tag scope check (item only -- no ancestor walk); delegates to the shared predicate
    // so single-item access and collection filtering can never drift apart.
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

        if (!principal.allowsItemTags(item.tags)) {
            authzLogger.debug(
                "Tag scope denied: item {} has tags '{}' but principal requires one of {}",
                itemId,
                item.tags,
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

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.application.knowledge.search.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.ktor.server.application.ApplicationCall
import java.util.UUID

/**
 * The search [AccessScope] of this principal: its `root_ids` and `tags_include` as an engine-side filter, or
 * [AccessScope.unrestricted] for a null principal or an unrestricted scope.
 */
fun ApiPrincipal?.toAccessScope(): AccessScope =
    if (this == null) AccessScope.unrestricted() else AccessScope.principal(scope.rootIds, scope.tagsInclude)

/**
 * The ONE builder of a REST search caller's [AccessScope], shared by `GET /search` and `GET /notes/search`.
 *
 * When the caller narrows the search with [requestedAncestorId] and its principal has a `root_ids` restriction,
 * the ancestor must itself be in scope ([enforceScopeForItem]); otherwise this returns null and the route answers
 * 403. The returned scope always carries the principal's full restriction, so the search engine filters every
 * candidate by it before ranking and capping, whatever the narrowing.
 *
 * @return the caller's access scope, or null when [requestedAncestorId] is outside it.
 */
suspend fun resolveSearchAccess(
    call: ApplicationCall,
    requestedAncestorId: UUID?,
    repo: WorkItemRepository,
): AccessScope? {
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    if (requestedAncestorId != null && principal?.scope?.rootIds != null && !enforceScopeForItem(call, requestedAncestorId, repo)) {
        return null
    }
    return principal.toAccessScope()
}

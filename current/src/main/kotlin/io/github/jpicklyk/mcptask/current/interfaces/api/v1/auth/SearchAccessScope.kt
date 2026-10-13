package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiPrincipal

/**
 * The [AccessScope] of this principal: its `root_ids` and `tags_include` as an engine-side filter, or
 * [AccessScope.unrestricted] for a null principal or an unrestricted scope. The 4.0 tools receive it through
 * `CallAccess.scope` and apply it in SQL and in the search engine, before paging, ranking and capping.
 */
fun ApiPrincipal?.toAccessScope(): AccessScope =
    if (this == null) AccessScope.unrestricted() else AccessScope.principal(scope.rootIds, scope.tagsInclude)

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SearchHitDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

/**
 * Registers the FTS5 item search route under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /search` — FTS5 full-text search over item titles/summaries; scope-filtered
 *
 * Query parameters:
 * - `q` (required) — search query
 * - `ancestorId` (optional) — scope results to subtree under this item
 * - `role` (optional) — filter by item role
 * - `tag` (optional) — filter by tag (comma-separated OR match)
 *
 * **FTS5 caveat:** Returns empty results when the repository is H2-backed (test env).
 * Use real SQLite fixtures for integration tests of this endpoint.
 *
 * Scope filtering: when the principal has `root_ids`, results are filtered to descendants of
 * ALL roots (multi-root). An optional `?ancestorId=` further narrows to a single subtree;
 * if the requested ancestorId is outside the principal's scope, 403 is returned.
 */
fun Route.searchRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()

    requireCapability(ApiCapability.READ) {
        get("/search") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val rawQuery =
                call.request.queryParameters["q"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Query parameter 'q' is required"))
                    return@get
                }

            val sanitizedQuery =
                FtsQuerySanitizer.sanitize(rawQuery) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Search query produced no usable tokens"))
                    return@get
                }

            val ancestorIdRaw = call.request.queryParameters["ancestorId"]
            val requestedAncestorId = ancestorIdRaw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

            val role =
                call.request.queryParameters["role"]?.let { r ->
                    Role.entries.find { it.name.equals(r, ignoreCase = true) }
                }

            val tags =
                call.request.queryParameters["tag"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }

            val principalRoots = principal?.scope?.rootIds

            // Resolve effective scope:
            // 1. If caller supplied ?ancestorId: verify it falls within the principal's scope,
            //    then use it as a single-subtree filter (singular ancestorId path).
            // 2. Else if principal has rootIds: apply multi-root filter (closes the leak for
            //    tokens with 2+ roots that previously received unscoped results).
            // 3. Else (unscoped/admin): no subtree filter.
            val scope: SearchScope =
                when {
                    requestedAncestorId != null -> {
                        // Validate caller-requested narrowing against principal scope
                        if (principalRoots != null && !enforceScopeForItem(call, requestedAncestorId, workItemRepo)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorDto("scope_forbidden", "Requested ancestorId is outside your scope")
                            )
                            return@get
                        }
                        SearchScope(ancestorId = requestedAncestorId, role = role, tags = tags)
                    }
                    principalRoots != null -> SearchScope(ancestorIds = principalRoots, role = role, tags = tags)
                    else -> SearchScope(role = role, tags = tags)
                }

            // Dispatched via the WorkItemRepository interface so this works whether workItemRepo
            // is the concrete SQLite repo or a decorator (e.g. EventPublishingWorkItemRepository —
            // always the case when the REST API is enabled). Non-FTS dialects (H2 tests) are
            // handled inside ftsSearch, which returns an empty result.
            val result =
                workItemRepo.ftsSearch(
                    sanitizedFtsQuery = sanitizedQuery,
                    matchMode = SearchMatchMode.AUTO,
                    scope = scope,
                    limit = 50,
                    offset = 0,
                )

            val hits =
                result.hits.map { hit ->
                    SearchHitDto(
                        itemId = hit.itemId.toString(),
                        field = hit.field,
                        snippet = hit.snippet,
                        score = hit.score,
                    )
                }
            call.respond(HttpStatusCode.OK, hits)
        }
    }
}

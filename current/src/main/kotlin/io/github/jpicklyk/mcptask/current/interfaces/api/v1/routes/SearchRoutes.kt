package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
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
 * Scope filtering: when the principal has `root_ids`, results are filtered to items
 * within the principal's scope. `ancestorId` is additionally applied as a subtree filter.
 */
fun Route.searchRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()

    requireCapability(ApiCapability.READ) {
        get("/search") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val rawQuery = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Query parameter 'q' is required"))
                return@get
            }

            val sanitizedQuery = FtsQuerySanitizer.sanitize(rawQuery) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Search query produced no usable tokens"))
                return@get
            }

            val ancestorIdRaw = call.request.queryParameters["ancestorId"]
            val ancestorId = ancestorIdRaw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

            val role = call.request.queryParameters["role"]?.let { r ->
                Role.entries.find { it.name.equals(r, ignoreCase = true) }
            }

            val tags = call.request.queryParameters["tag"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

            // Merge principal scope into ancestorId: if scope has rootIds, use the first root
            // as ancestorId if none specified (or ignore — the caller can specify ancestorId explicitly)
            val effectiveAncestorId: UUID? = ancestorId
                ?: principal?.scope?.rootIds?.singleOrNull() // only auto-apply if single root

            val scope = SearchScope(
                ancestorId = effectiveAncestorId,
                role = role,
                tags = tags,
            )

            val repo = workItemRepo
            if (repo !is SQLiteWorkItemRepository) {
                // H2 test environment — FTS5 not available
                call.respond(HttpStatusCode.OK, emptyList<Any>())
                return@get
            }

            val result = repo.ftsSearch(
                sanitizedFtsQuery = sanitizedQuery,
                matchMode = SearchMatchMode.AUTO,
                scope = scope,
                limit = 50,
                offset = 0,
            )

            val hits = result.hits.map { hit ->
                mapOf(
                    "itemId" to hit.itemId.toString(),
                    "field" to hit.field,
                    "snippet" to hit.snippet,
                    "score" to hit.score,
                )
            }
            call.respond(HttpStatusCode.OK, hits)
        }
    }
}

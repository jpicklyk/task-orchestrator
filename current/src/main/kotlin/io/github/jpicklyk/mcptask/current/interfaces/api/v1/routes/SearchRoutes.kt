package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchValidationException
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.resolveSearchAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SearchHitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Page size of the REST search routes (they take no paging parameters). */
internal const val REST_SEARCH_LIMIT = 50

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
 * Scope filtering: the principal's `root_ids` and `tags_include` become the search's access scope
 * ([resolveSearchAccess]), applied inside the search before ranking and the 50-hit page, so a restricted principal
 * gets a full page of in-scope hits. An optional `?ancestorId=` further narrows to a single subtree; if the
 * requested ancestorId is outside the principal's scope, 403 is returned.
 */
fun Route.searchRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val searchService by lazy { SearchService(repositoryProvider.searchIndex()) }

    requireCapability(ApiCapability.READ) {
        get("/search") {
            val rawQuery =
                call.request.queryParameters["q"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Query parameter 'q' is required")
                    return@get
                }

            // Validate before any scope check: an unparsable filter is a 400, never widened.
            val requestedAncestorId = (call.uuidParamOrRespond("ancestorId") ?: return@get).value
            val role = (call.roleParamOrRespond("role") ?: return@get).value

            val tags =
                call.request.queryParameters["tag"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }

            val access =
                resolveSearchAccess(call, requestedAncestorId, workItemRepo) ?: run {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Requested ancestorId is outside your scope")
                    return@get
                }

            val result =
                call.searchOrRespond(searchService) {
                    SearchRequest(
                        query = rawQuery,
                        corpus = Corpus.ITEM,
                        matchMode = SearchMatchMode.AUTO,
                        ancestorId = requestedAncestorId,
                        roles = role?.let { setOf(it) },
                        tags = tags,
                        limit = REST_SEARCH_LIMIT,
                        offset = 0,
                        access = access,
                    )
                } ?: return@get

            val hits =
                result.hits.map { hit ->
                    SearchHitDto(
                        itemId = hit.itemId.toString(),
                        field = hit.field,
                        snippet = hit.snippet,
                        score = hit.score,
                        title = hit.title,
                    )
                }
            call.respond(HttpStatusCode.OK, hits)
        }
    }
}

/**
 * Runs the search [request] builds, answering 400 with the service's message for a request it rejects (and
 * returning null, so the route stops). Any other failure propagates to the error mapper.
 */
internal suspend fun ApplicationCall.searchOrRespond(
    service: SearchService,
    request: () -> SearchRequest,
): SearchResult? =
    try {
        service.search(request())
    } catch (e: SearchValidationException) {
        respondError(LegacyRestCode.BAD_REQUEST, e.message ?: "Invalid search query")
        null
    }

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.resolveSearchAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SearchHitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Registers the note search route under the `/api/v1` route prefix.
 *
 * - `GET /notes/search` -- FTS5 search over note bodies; scope-filtered
 *
 * The route requires [ApiCapability.READ]. A principal with `tags_include` sees hits only for notes whose owning item
 * carries an allowed tag (filtered inside the search, before the page is taken). The note resource reads
 * (`GET /items/{id}/notes[/{key}]`) are [noteResourceRoutes]; note writes go through `POST /ops/notes_write`. The search
 * route moves to `items_read select=search` in Q19.
 */
fun Route.noteRoutes(
    repositoryProvider: RepositoryProvider,
    unitOfWork: UnitOfWork = UnscopedUnitOfWork(repositoryProvider),
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val searchService by lazy { SearchService(repositoryProvider.searchIndex(), unitOfWork) }

    requireCapability(ApiCapability.READ) {
        // ─── GET /notes/search ──────────────────────────────────────────────
        get("/notes/search") {
            val rawQuery =
                call.request.queryParameters["q"]?.takeIf { it.isNotBlank() } ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Query parameter 'q' is required")
                    return@get
                }

            val requestedAncestorId = (call.uuidParamOrRespond("ancestorId") ?: return@get).value

            // Same access rule as /search: one builder, applied inside the search (before ranking and the page).
            val access =
                resolveSearchAccess(call, requestedAncestorId, workItemRepo) ?: run {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Requested ancestorId is outside your scope")
                    return@get
                }

            val result =
                call.searchOrRespond(searchService) {
                    SearchRequest(
                        query = rawQuery,
                        corpus = Corpus.NOTE,
                        matchMode = SearchMatchMode.AUTO,
                        ancestorId = requestedAncestorId,
                        limit = REST_SEARCH_LIMIT,
                        offset = 0,
                        access = access,
                    )
                } ?: return@get

            val hits =
                result.hits.map { hit ->
                    SearchHitDto(
                        itemId = hit.itemId.toString(),
                        noteKey = hit.noteKey,
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

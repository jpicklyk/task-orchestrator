package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.resolveSearchAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SearchHitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.DB_QUERY_FAILED
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import java.util.UUID

private val noteLogger = LoggerFactory.getLogger("NoteRoutes")

/**
 * Registers note-read routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /items/{id}/notes`        — list notes for an item, optional `?role=` or `?key=` filter
 * - `GET /items/{id}/notes/{key}`  — single note by key; 404 when absent
 * - `GET /notes/search`            — FTS5 search over note bodies; scope-filtered
 *
 * All routes require [ApiCapability.READ]. Attribution redaction is applied via
 * [AttributionRedactor] (env-driven, defaults to redact).
 *
 * A principal with `tags_include` sees `/notes/search` hits only for notes whose owning item carries an
 * allowed tag (filtered inside the search, before the page is taken); the per-item routes are already gated by
 * [enforceScopeForItem].
 */
fun Route.noteRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val redactor = AttributionRedactor.fromEnv()
    val searchService by lazy { SearchService(repositoryProvider.searchIndex()) }

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/notes ──────────────────────────────────────────
        get("/items/{id}/notes") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@get
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@get
                }

            val itemResult =
                legacyRead({
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respondError(LegacyRestCode.NOT_FOUND, "Item $id not found")
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                return@get
            }

            val role = call.request.queryParameters["role"]?.takeIf { it.isNotBlank() }
            val keyFilter = call.request.queryParameters["key"]?.takeIf { it.isNotBlank() }

            val notesResult =
                legacyRead({
                    noteLogger.warn("GET /items/{}/notes DB error: {}", id, it)
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { noteRepo.findByItemId(id, role = role) }
            val notes =
                notesResult
                    .let { list -> if (keyFilter != null) list.filter { it.key == keyFilter } else list }
                    .map { n -> redactor.redact(n.toDto(), call) }
            call.respond(HttpStatusCode.OK, notes)
        }

        // ─── GET /items/{id}/notes/{key} ────────────────────────────────────
        get("/items/{id}/notes/{key}") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@get
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@get
                }
            val key =
                call.parameters["key"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing note key")
                    return@get
                }

            val itemResult =
                legacyRead({
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respondError(LegacyRestCode.NOT_FOUND, "Item $id not found")
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                return@get
            }

            val note =
                legacyRead({
                    noteLogger.warn("GET /items/{}/notes/{} DB error: {}", id, key, it)
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { noteRepo.findByItemIdAndKey(id, key) }
            run {
                run {
                    if (note == null) {
                        call.respondError(LegacyRestCode.NOT_FOUND, "Note '$key' not found on item $id")
                    } else {
                        val dto = redactor.redact(note.toDto(), call)
                        // Emit the note's ETag as a response header so clients can supply it as
                        // If-Match on a subsequent note update (PUT). dto.etag = etagFor(modifiedAt).
                        call.response.header(HttpHeaders.ETag, dto.etag)
                        call.respond(HttpStatusCode.OK, dto)
                    }
                }
            }
        }

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

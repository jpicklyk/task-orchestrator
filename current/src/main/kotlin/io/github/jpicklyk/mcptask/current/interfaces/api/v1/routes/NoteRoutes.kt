package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.allowedItemIdsForTagScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SearchHitDto
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
 * A principal with `tags_include` sees `/notes/search` hits only for items whose tags it is
 * allowed to read; the per-item routes are already gated by [enforceScopeForItem].
 */
fun Route.noteRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val redactor = AttributionRedactor.fromEnv()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/notes ──────────────────────────────────────────
        get("/items/{id}/notes") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }

            val itemResult =
                legacyRead({
                    call.respondDbError()
                    return@get
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val role = call.request.queryParameters["role"]?.takeIf { it.isNotBlank() }
            val keyFilter = call.request.queryParameters["key"]?.takeIf { it.isNotBlank() }

            val notesResult =
                legacyRead({
                    noteLogger.warn("GET /items/{}/notes DB error: {}", id, it)
                    call.respondDbError()
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
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@get
                }
            val key =
                call.parameters["key"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing note key"))
                    return@get
                }

            val itemResult =
                legacyRead({
                    call.respondDbError()
                    return@get
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val note =
                legacyRead({
                    noteLogger.warn("GET /items/{}/notes/{} DB error: {}", id, key, it)
                    call.respondDbError()
                    return@get
                }) { noteRepo.findByItemIdAndKey(id, key) }
            run {
                run {
                    if (note == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Note '$key' not found on item $id"))
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

            val requestedAncestorId = (call.uuidParamOrRespond("ancestorId") ?: return@get).value

            val principalRoots = principal?.scope?.rootIds

            // Multi-root scope: same logic as /search — validate ?ancestorId against principal
            // scope, fall back to principalRoots for multi-root enforcement, or unrestricted.
            val scope: SearchScope =
                when {
                    requestedAncestorId != null -> {
                        if (principalRoots != null && !enforceScopeForItem(call, requestedAncestorId, workItemRepo)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorDto("scope_forbidden", "Requested ancestorId is outside your scope")
                            )
                            return@get
                        }
                        SearchScope(ancestorId = requestedAncestorId)
                    }
                    principalRoots != null -> SearchScope(ancestorIds = principalRoots)
                    else -> SearchScope()
                }

            // Dispatched via the NoteRepository interface so this works whether noteRepo is the
            // concrete SQLite repo or a decorator (e.g. EventPublishingNoteRepository — always the
            // case when the REST API is enabled).
            val result =
                noteRepo.ftsSearch(
                    sanitizedFtsQuery = sanitizedQuery,
                    matchMode = SearchMatchMode.AUTO,
                    scope = scope,
                    limit = 50,
                    offset = 0,
                )

            // tags_include has no FTS representation, so note hits are filtered by their OWNING
            // item's tags after the fact — SearchHitDto carries only itemId.
            val allowedItemIds =
                allowedItemIdsForTagScope(principal, result.hits.map { it.itemId }.toSet(), workItemRepo)

            val hits =
                result.hits
                    .filter { it.itemId in allowedItemIds }
                    .map { hit ->
                        SearchHitDto(
                            itemId = hit.itemId.toString(),
                            noteKey = hit.noteKey,
                            field = hit.field,
                            snippet = hit.snippet,
                            score = hit.score,
                        )
                    }
            call.respond(HttpStatusCode.OK, hits)
        }
    }
}

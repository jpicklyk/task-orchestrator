package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
 * **FTS5 search caveat:** the `/notes/search` endpoint delegates to
 * [SQLiteNoteRepository.ftsSearch] which returns empty results when running against H2
 * (test environment). Use real SQLite fixtures for integration tests of this endpoint.
 */
fun Route.noteRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val redactor = AttributionRedactor.fromEnv()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/notes ──────────────────────────────────────────
        get("/items/{id}/notes") {
            val rawId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                return@get
            }
            val id = runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                return@get
            }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val role = call.request.queryParameters["role"]?.takeIf { it.isNotBlank() }
            val keyFilter = call.request.queryParameters["key"]?.takeIf { it.isNotBlank() }

            val notesResult = noteRepo.findByItemId(id, role = role)
            when (notesResult) {
                is Result.Error -> {
                    noteLogger.warn("GET /items/{}/notes DB error: {}", id, notesResult.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    val notes = notesResult.data
                        .let { list -> if (keyFilter != null) list.filter { it.key == keyFilter } else list }
                        .map { n -> redactor.redact(n.toDto(), call) }
                    call.respond(HttpStatusCode.OK, notes)
                }
            }
        }

        // ─── GET /items/{id}/notes/{key} ────────────────────────────────────
        get("/items/{id}/notes/{key}") {
            val rawId = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                return@get
            }
            val id = runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                return@get
            }
            val key = call.parameters["key"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing note key"))
                return@get
            }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@get
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@get
            }

            val noteResult = noteRepo.findByItemIdAndKey(id, key)
            when (noteResult) {
                is Result.Error -> {
                    noteLogger.warn("GET /items/{}/notes/{} DB error: {}", id, key, noteResult.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    val note = noteResult.data
                    if (note == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Note '$key' not found on item $id"))
                    } else {
                        val dto = redactor.redact(note.toDto(), call)
                        call.respond(HttpStatusCode.OK, dto)
                    }
                }
            }
        }

        // ─── GET /notes/search ──────────────────────────────────────────────
        get("/notes/search") {
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

            val scope = SearchScope(ancestorId = ancestorId)

            val repo = noteRepo
            if (repo !is SQLiteNoteRepository) {
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
                    "noteKey" to hit.noteKey,
                    "field" to hit.field,
                    "snippet" to hit.snippet,
                    "score" to hit.score,
                )
            }
            call.respond(HttpStatusCode.OK, hits)
        }
    }
}

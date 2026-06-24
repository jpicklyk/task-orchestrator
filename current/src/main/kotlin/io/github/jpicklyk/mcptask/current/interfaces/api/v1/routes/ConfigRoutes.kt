package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ConfigSnapshotDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteSchemaEntryDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.SchemaDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.TraitDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.StatusGraphBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val FINGERPRINT_ETAG_PREFIX = "\"cfg-"

/**
 * Registers the read-only config/schema-discovery routes under `/api/v1/config*`.
 *
 * All endpoints require [ApiCapability.READ] and emit an ETag derived from the
 * config-file fingerprint (stable across reads; 304 when `If-None-Match` matches).
 *
 * Endpoints:
 * - `GET /config`            — [ConfigSnapshotDto] with all schemas, traits, types, status-graph
 * - `GET /config/schemas`    — list of all [SchemaDto]
 * - `GET /config/schemas/{type}` — single schema by type; 404 if unknown
 * - `GET /config/traits`     — list of all [TraitDto]
 * - `GET /config/types`      — list of registered type name strings
 * - `GET /config/status-graph` — [StatusGraphDto]
 */
fun Route.configRoutes(schemaService: WorkItemSchemaService) {
    val graphBuilder = StatusGraphBuilder(schemaService)

    requireCapability(ApiCapability.READ) {
        route("/config") {
            // GET /config — full snapshot
            get {
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)

                val allSchemas = schemaService.getAllSchemas()
                val allTraits = schemaService.getAllTraits()
                val graph = graphBuilder.getStatusGraph()

                call.respond(
                    HttpStatusCode.OK,
                    ConfigSnapshotDto(
                        schemas = allSchemas.values.map { it.toDto() },
                        traits = allTraits.entries.map { (name, entries) -> TraitDto(name, entries.map { it.toDto() }) },
                        types = allSchemas.keys.toList().sorted(),
                        statusGraph = graph,
                        defaultSchema = allSchemas["default"]?.toDto(),
                    ),
                )
            }

            // GET /config/schemas — all schemas
            get("/schemas") {
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, schemaService.getAllSchemas().values.map { it.toDto() })
            }

            // GET /config/schemas/{type} — single schema
            get("/schemas/{type}") {
                val typeName =
                    call.parameters["type"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing type parameter"))
                        return@get
                    }
                val schema = schemaService.getAllSchemas()[typeName]
                if (schema == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto("not_found", "No schema registered for type '$typeName'"),
                    )
                    return@get
                }
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, schema.toDto())
            }

            // GET /config/traits — all trait definitions
            get("/traits") {
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                val traits =
                    schemaService.getAllTraits().entries.map { (name, entries) ->
                        TraitDto(name, entries.map { it.toDto() })
                    }
                call.respond(HttpStatusCode.OK, traits)
            }

            // GET /config/types — list of type names
            get("/types") {
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(
                    HttpStatusCode.OK,
                    schemaService
                        .getAllSchemas()
                        .keys
                        .toList()
                        .sorted()
                )
            }

            // GET /config/status-graph — structural transition graph
            get("/status-graph") {
                val etag = fingerprintEtag(schemaService)
                if (respondWith304IfMatch(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.respond(HttpStatusCode.OK, graphBuilder.getStatusGraph())
            }
        }
    }
}

/** Returns an ETag string derived from the config fingerprint, or a static sentinel when no config. */
private fun fingerprintEtag(schemaService: WorkItemSchemaService): String {
    val fp = schemaService.getConfigFingerprint() ?: "no-config"
    return "$FINGERPRINT_ETAG_PREFIX$fp\""
}

/** Returns true when the client's If-None-Match matches the current ETag (304 should be sent). */
private fun respondWith304IfMatch(
    clientEtag: String?,
    currentEtag: String
): Boolean = clientEtag != null && clientEtag.trim() == currentEtag

// ─── Domain → DTO mapping helpers ───────────────────────────────────────────

private fun NoteSchemaEntry.toDto(): NoteSchemaEntryDto =
    NoteSchemaEntryDto(
        key = key,
        role = role.name.lowercase(),
        required = required,
        description = description,
        guidance = guidance,
        skill = skill,
    )

private fun WorkItemSchema.toDto(): SchemaDto =
    SchemaDto(
        type = type,
        lifecycleMode = lifecycleMode.name.lowercase(),
        hasReviewPhase = hasReviewPhase(),
        notes = notes.map { it.toDto() },
        defaultTraits = defaultTraits,
    )

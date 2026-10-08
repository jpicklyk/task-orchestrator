package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * The REST legacy fault response (F4): a store fault on a route responds the existing 3.x
 * `500 db_error` [ErrorDto] with the route's text, never an empty 500 and never a 404 (a missing
 * row stays not-found). Paired with `legacyRead`; StatusPages arrives in P16.
 */
internal suspend fun ApplicationCall.respondDbError(text: String = DB_QUERY_FAILED) {
    respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", text))
}

/** The read-fault text the 3.x REST read routes already use. */
internal const val DB_QUERY_FAILED: String = "Database query failed"

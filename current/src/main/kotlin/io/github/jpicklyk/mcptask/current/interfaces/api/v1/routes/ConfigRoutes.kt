package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.infrastructure.config.ConfigJsonSchema
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Registers `GET /api/v1/config/schema`: the JSON Schema (draft 2020-12) every config document is validated against,
 * as `application/schema+json` with a strong ETag that changes exactly when the schema does. Requires
 * [ApiCapability.READ]; the body is the schema document itself, not an envelope.
 *
 * It is the only config route left on REST. Reading and writing config, the effective view and rule text are ops of
 * the `config` tool (`POST /api/v1/ops/config`).
 */
fun Route.configSchemaRoute() {
    requireCapability(ApiCapability.READ) {
        get("/config/schema") {
            val etag = ConfigJsonSchema.etag
            call.response.header(HttpHeaders.ETag, etag)
            if (call.request.headers[HttpHeaders.IfNoneMatch]?.trim() == etag) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.respondBytes(ConfigJsonSchema.bytes, ContentType.parse(ConfigJsonSchema.MEDIA_TYPE), HttpStatusCode.OK)
        }
    }
}

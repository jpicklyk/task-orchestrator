package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.buildDependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

private val depLogger = LoggerFactory.getLogger("DependencyRoutes")

/**
 * Registers dependency-read routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /items/{id}/dependencies` — `{ blocks, blockedBy, related }` split by type/direction
 * - `GET /items/{id}/backlinks`    — items referencing this one via any dependency edge
 *
 * POST/DELETE dependency endpoints are Phase 5.
 *
 * All routes require [ApiCapability.READ]. Scope filtering is applied at the item level —
 * only items within the principal's scope are accessible.
 *
 * Note: [DependencyRepository] methods are non-suspend; they are called inside [withContext]
 * to avoid blocking the Ktor event loop.
 */
fun Route.dependencyRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val depRepo = repositoryProvider.dependencyRepository()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/dependencies ───────────────────────────────────
        get("/items/{id}/dependencies") {
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

            val deps = withContext(Dispatchers.IO) { depRepo.findByItemId(id) }
            val dto = buildDependenciesDto(id.toString(), deps)
            call.respond(HttpStatusCode.OK, dto)
        }

        // ─── GET /items/{id}/backlinks ───────────────────────────────────────
        get("/items/{id}/backlinks") {
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

            val backlinks = depRepo.backlinks(id)
            val dtos = backlinks.map { bl ->
                mapOf(
                    "fromItemId" to bl.fromItemId.toString(),
                    "type" to bl.type.name.lowercase(),
                    "fromTitle" to bl.fromTitle,
                )
            }
            call.respond(HttpStatusCode.OK, dtos)
        }
    }
}

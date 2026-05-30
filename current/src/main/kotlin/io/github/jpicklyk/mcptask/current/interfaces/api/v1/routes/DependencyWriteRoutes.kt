package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DependencyCreateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

private val depWriteLogger = LoggerFactory.getLogger("DependencyWriteRoutes")

/**
 * Registers dependency-write routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `POST   /dependencies`      — create edge ([ApiCapability.MANAGE_DEPENDENCIES])
 * - `DELETE /dependencies/{id}` — remove edge ([ApiCapability.MANAGE_DEPENDENCIES])
 *
 * Validation rules (mirror domain):
 * - `fromItemId` != `toItemId`
 * - `type` one of "blocks" | "relates_to"
 * - `unblockAt` absent or null for RELATES_TO
 * - Cycle detection via [DependencyRepository.hasCyclicDependency] → 400 `cycle_detected`
 *
 * Note: [DependencyRepository] is NON-suspend; all calls are wrapped in [withContext(IO)]
 * to avoid blocking the Ktor event loop.
 */
fun Route.dependencyWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val depRepo = repositoryProvider.dependencyRepository()

    requireCapability(ApiCapability.MANAGE_DEPENDENCIES) {
        // ─── POST /dependencies ──────────────────────────────────────────────
        post("/dependencies") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@post
                }

            ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                )
                return@post
            }

            val dto =
                try {
                    call.receive<DependencyCreateDto>()
                } catch (e: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Invalid request body"))
                    return@post
                }

            val fromId =
                runCatching { UUID.fromString(dto.fromItemId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid fromItemId UUID"))
                    return@post
                }
            val toId =
                runCatching { UUID.fromString(dto.toItemId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid toItemId UUID"))
                    return@post
                }

            if (fromId == toId) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "fromItemId and toItemId must differ"))
                return@post
            }

            val depType =
                when (dto.type.lowercase()) {
                    "blocks" -> DependencyType.BLOCKS
                    "relates_to" -> DependencyType.RELATES_TO
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorDto("validation_error", "type must be 'blocks' or 'relates_to'"),
                        )
                        return@post
                    }
                }

            if (depType == DependencyType.RELATES_TO && dto.unblockAt != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("validation_error", "unblockAt is not allowed for relates_to dependencies"),
                )
                return@post
            }

            // Verify both items exist and are in scope
            val fromResult = workItemRepo.getById(fromId)
            if (fromResult is Result.Error) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("not_found", "fromItemId $fromId not found"))
                return@post
            }
            val toResult = workItemRepo.getById(toId)
            if (toResult is Result.Error) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("not_found", "toItemId $toId not found"))
                return@post
            }

            if (!enforceScopeForItem(call, fromId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for fromItemId $fromId"))
                return@post
            }
            if (!enforceScopeForItem(call, toId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for toItemId $toId"))
                return@post
            }

            // Cycle detection and create (non-suspend: wrap in withContext(IO) + transaction)
            val dep =
                try {
                    Dependency(
                        fromItemId = fromId,
                        toItemId = toId,
                        type = depType,
                        unblockAt = dto.unblockAt,
                    )
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Validation failed"))
                    return@post
                }

            val created: Dependency? =
                withContext(Dispatchers.IO) {
                    transaction {
                        val hasCycle = depRepo.hasCyclicDependency(fromId, toId)
                        if (hasCycle) null else depRepo.create(dep)
                    }
                }

            if (created == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("cycle_detected", "Adding this dependency would create a cycle"),
                )
                return@post
            }

            call.respond(HttpStatusCode.Created, created.toDto())
        }

        // ─── DELETE /dependencies/{id} ───────────────────────────────────────
        delete("/dependencies/{id}") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@delete
                }

            ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                )
                return@delete
            }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing dependency id"))
                    return@delete
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@delete
                }

            val existing: Dependency? = withContext(Dispatchers.IO) { depRepo.findById(id) }
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Dependency $id not found"))
                return@delete
            }

            // Scope-check: both endpoints must be accessible
            if (!enforceScopeForItem(call, existing.fromItemId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied"))
                return@delete
            }

            val deleted: Boolean = withContext(Dispatchers.IO) { depRepo.delete(id) }
            if (!deleted) {
                depWriteLogger.warn("DELETE /dependencies/{} returned false (race?)", id)
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Dependency $id not found or already deleted"))
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

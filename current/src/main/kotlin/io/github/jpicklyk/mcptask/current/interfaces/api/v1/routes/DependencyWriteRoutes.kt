package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.EventActor
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.validation.DuplicateDependencyException
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
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.UUID

private val depWriteLogger = LoggerFactory.getLogger("DependencyWriteRoutes")

// Accepted Content-Types for the JSON dependency-create body (POST /dependencies). `*/*` is what
// `call.request.contentType()` reports when the header is ABSENT, which ContentNegotiation's
// wildcard match also accepted — so an absent header stays accepted and only a genuinely
// non-JSON Content-Type is rejected. Mirrors ItemWriteRoutes.JSON_WRITE_CONTENT_TYPES /
// NoteWriteRoutes.JSON_WRITE_CONTENT_TYPES (each file keeps its own copy, same convention).
private val JSON_WRITE_CONTENT_TYPES = setOf("application/json", "*/*")

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
 * - Duplicate edge (same `fromItemId`/`toItemId`/`type`) → 409 `duplicate_dependency`, caught from
 *   [io.github.jpicklyk.mcptask.current.domain.validation.DuplicateDependencyException] thrown by
 *   the repository's insert path
 *
 * Note: [DependencyRepository]'s read/write methods are suspend but still JDBC-blocking under
 * the hood; all calls are wrapped in [withContext(IO)] to keep the Ktor event loop free.
 */
fun Route.dependencyWriteRoutes(
    repositoryProvider: RepositoryProvider,
    @Suppress("UNUSED_PARAMETER") degradedModePolicy: DegradedModePolicy,
    unitOfWork: UnitOfWork,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val depRepo = repositoryProvider.dependencyRepository()

    requireCapability(ApiCapability.MANAGE_DEPENDENCIES) {
        // ─── POST /dependencies ──────────────────────────────────────────────
        post("/dependencies") {
            // Content-Type gate — explicit because the body is no longer read through
            // `receive<DependencyCreateDto>()`, which let ContentNegotiation reject a non-JSON
            // body with 415. It runs before the bounded body read, so 415 still precedes
            // anything that depends on the body. Mirrors ItemWriteRoutes/NoteWriteRoutes.
            val depContentType =
                call.request
                    .contentType()
                    .withoutParameters()
                    .toString()
            if (depContentType !in JSON_WRITE_CONTENT_TYPES) {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorDto("unsupported_media_type", "Use Content-Type: application/json"),
                )
                return@post
            }

            // Bounded (bug e941c2c7 — this route had no size limit at all before this fix; see
            // receiveBounded's KDoc). Decoded with McpJson, the same instance ContentNegotiation
            // is installed with, so this behaves exactly as `receive<DependencyCreateDto>()` did,
            // minus the unbounded buffering.
            val bodyText = call.receiveBounded(MAX_JSON_WRITE_BODY_BYTES) ?: return@post

            val dto =
                try {
                    McpJson.decodeFromString(DependencyCreateDto.serializer(), bodyText)
                } catch (e: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Invalid request body"))
                    return@post
                }

            val fromId =
                runCatchingNonCancellation { UUID.fromString(dto.fromItemId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid fromItemId UUID"))
                    return@post
                }
            val toId =
                runCatchingNonCancellation { UUID.fromString(dto.toItemId) }.getOrNull() ?: run {
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
            val fromResult =
                legacyRead({
                    call.respondDbError()
                    return@post
                }) { workItemRepo.getById(fromId) }
            if (fromResult == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("not_found", "fromItemId $fromId not found"))
                return@post
            }
            val toResult =
                legacyRead({
                    call.respondDbError()
                    return@post
                }) { workItemRepo.getById(toId) }
            if (toResult == null) {
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

            // Cycle detection and create (JDBC-blocking: wrap in withContext(IO) + suspendTransaction)
            val dep =
                try {
                    Dependency(
                        fromItemId = fromId,
                        toItemId = toId,
                        type = depType,
                        unblockAt = dto.unblockAt,
                    )
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Validation failed"))
                    return@post
                }

            val created: Dependency? =
                try {
                    val outcome =
                        withContext(Dispatchers.IO + EventActor(ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey]))) {
                            // ONE unit: the repo methods below join it, so the cycle check and the insert
                            // stay atomic against a concurrent writer.
                            unitOfWork.write("dependency.create") {
                                // RELATES_TO has no blocking edge, so it skips the cycle pre-check.
                                val edge = dep.blockingEdge()
                                val hasCycle = edge != null && depRepo.hasCyclicDependency(edge.first, edge.second)
                                Outcome.Ok(if (hasCycle) null else depRepo.create(dep))
                            }
                        }
                    when (outcome) {
                        is Outcome.Ok -> outcome.value
                        is Outcome.Err -> {
                            val error = outcome.error
                            when (error.code) {
                                ErrorCode.DUPLICATE ->
                                    call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorDto("duplicate_dependency", "A dependency of this type already exists between these items"),
                                    )
                                ErrorCode.UNAVAILABLE ->
                                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("unavailable", error.message))
                                else ->
                                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal", error.message))
                            }
                            return@post
                        }
                    }
                } catch (e: DuplicateDependencyException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorDto("duplicate_dependency", e.message ?: "A dependency of this type already exists between these items"),
                    )
                    return@post
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
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing dependency id"))
                    return@delete
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@delete
                }

            val existing: Dependency? =
                legacyRead({
                    call.respondDbError()
                    return@delete
                }) { withContext(Dispatchers.IO) { depRepo.findById(id) } }
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Dependency $id not found"))
                return@delete
            }

            // Scope-check: BOTH endpoints must be accessible. Deleting an edge mutates the
            // block/relation state of both items, so a caller must have scope over each side —
            // mirrors the POST /dependencies check. (Previously only fromItemId was checked,
            // letting a caller scoped to the 'from' subtree delete an edge reaching into a
            // subtree they have no authority over.)
            if (!enforceScopeForItem(call, existing.fromItemId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for fromItemId"))
                return@delete
            }
            if (!enforceScopeForItem(call, existing.toItemId, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for toItemId"))
                return@delete
            }

            val deleteOutcome =
                withContext(
                    Dispatchers.IO + EventActor(ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey]))
                ) { unitOfWork.write("dependency.delete") { Outcome.Ok(depRepo.delete(id)) } }
            val deleted: Boolean =
                when (deleteOutcome) {
                    is Outcome.Ok -> deleteOutcome.value
                    is Outcome.Err -> {
                        // The legacy write-fault shape (F4): 500 db_error with the route's fixed text; the SQL text
                        // goes to the log only.
                        depWriteLogger.warn("DELETE /dependencies/{} DB error: {}", id, deleteOutcome.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to delete dependency"))
                        return@delete
                    }
                }
            if (!deleted) {
                depWriteLogger.warn("DELETE /dependencies/{} returned false (race?)", id)
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Dependency $id not found or already deleted"))
            } else {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RoleTransitionDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.pageParams
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.redactActorProofIfNeeded
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.redactVerification
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val transitionLogger = LoggerFactory.getLogger("TransitionRoutes")

/**
 * Registers role-transition audit-read routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /items/{id}/transitions` — per-item transition history (append-only; paginated)
 * - `GET /transitions`            — recent transitions across items; `?since=ISO-8601` filter
 *
 * Both endpoints scope-filter: the global `/transitions` endpoint only returns transitions
 * for items the principal can access (based on `root_ids`).
 *
 * `actor` and `verification` on transitions use the same admin-only redaction as notes:
 * - Non-admin callers: `actor` is stripped to `null` and `verification` to `null`
 * - Admin callers: `actor` visible; `proof` still redacted unless `?include=proof`
 */
fun Route.transitionRoutes(
    repositoryProvider: RepositoryProvider,
    redactAttribution: Boolean = AppConfig.fromEnv().apiRedactNoteAttribution,
    redactProof: Boolean = AppConfig.fromEnv().apiRedactActorProof,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val transitionRepo = repositoryProvider.roleTransitionRepository()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/transitions ────────────────────────────────────
        get("/items/{id}/transitions") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@get
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
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

            val pp = call.pageParams()
            val result = transitionRepo.findByItemId(id, limit = pp.pageSize + 1)
            when (result) {
                is Result.Error -> {
                    transitionLogger.warn("GET /items/{}/transitions DB error: {}", id, result.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    val all = result.data
                    val page = all.take(pp.pageSize)
                    val hasMore = all.size > pp.pageSize
                    val dtos =
                        page.map { t ->
                            val dto = t.toDto()
                            applyTransitionRedaction(dto, call, redactAttribution, redactProof)
                        }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, null).copy(hasMore = hasMore))
                }
            }
        }

        // ─── GET /transitions ────────────────────────────────────────────────
        get("/transitions") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val sinceRaw = call.request.queryParameters["since"]
            val since =
                sinceRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.now().minusSeconds(86400) // default: last 24 hours

            val pp = call.pageParams()
            val result = transitionRepo.findSince(since, limit = pp.pageSize * pp.page + 1)

            when (result) {
                is Result.Error -> {
                    transitionLogger.warn("GET /transitions DB error: {}", result.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Database query failed"))
                }
                is Result.Success -> {
                    var transitions = result.data

                    // Apply scope filtering: only return transitions for items within principal scope
                    val scopeRootIds = principal?.scope?.rootIds
                    if (scopeRootIds != null) {
                        // Fetch unique item IDs from transitions
                        val itemIds = transitions.map { it.itemId }.distinct().toSet()
                        val itemsResult = workItemRepo.findByIds(itemIds)
                        if (itemsResult is Result.Success) {
                            // Filter: keep only items reachable from principal's roots
                            val accessibleItemIds = mutableSetOf<UUID>()
                            for (item in itemsResult.data) {
                                val chainResult = workItemRepo.findAncestorChains(setOf(item.id))
                                if (chainResult is Result.Success) {
                                    val ancestors = chainResult.data[item.id] ?: emptyList()
                                    val idsInChain = ancestors.map { it.id }.toSet() + item.id
                                    if (idsInChain.any { it in scopeRootIds }) {
                                        accessibleItemIds.add(item.id)
                                    }
                                }
                            }
                            transitions = transitions.filter { it.itemId in accessibleItemIds }
                        }
                    }

                    // Paginate
                    val offset = (pp.page - 1) * pp.pageSize
                    val page = transitions.drop(offset).take(pp.pageSize)
                    val hasMore = (offset + page.size) < transitions.size

                    val dtos =
                        page.map { t ->
                            val dto = t.toDto()
                            applyTransitionRedaction(dto, call, redactAttribution, redactProof)
                        }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, null).copy(hasMore = hasMore))
                }
            }
        }
    }
}

/** Applies attribution redaction to a [RoleTransitionDto]. */
private fun applyTransitionRedaction(
    dto: RoleTransitionDto,
    call: io.ktor.server.application.ApplicationCall,
    redactAttribution: Boolean,
    redactProof: Boolean,
): RoleTransitionDto {
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val isAdmin = principal?.capabilities?.contains(io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability.ADMIN) ?: false

    return if (redactAttribution && !isAdmin) {
        dto.copy(actor = null, verification = null)
    } else {
        val redactedActor = redactActorProofIfNeeded(dto.actor, call, redactProof)
        val redactedVerification = redactVerification(dto.verification, call, redactAttribution)
        dto.copy(actor = redactedActor, verification = redactedVerification)
    }
}

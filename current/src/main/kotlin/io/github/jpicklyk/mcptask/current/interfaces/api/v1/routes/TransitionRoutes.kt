package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.allowedItemIdsForScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RoleTransitionDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.DB_QUERY_FAILED
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.pageParamsOrRespond
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
 * Candidate-row cap for `GET /transitions`'s in-memory scope filtering and pagination. The
 * unfiltered fetch from `TransitionStore.findSince` must stay bounded independent of
 * how large `page` is — mirrors `TAG_SCOPE_SCAN_LIMIT` in `ItemRoutes.kt` (same shape, declared
 * separately since the two route files share no base).
 */
private const val TRANSITION_SCAN_LIMIT = 1000

/**
 * Registers role-transition audit-read routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `GET /items/{id}/transitions` — per-item transition history (append-only; paginated)
 * - `GET /transitions`            — recent transitions across items; `?since=ISO-8601` filter
 *
 * Both endpoints scope-filter: the global `/transitions` endpoint only returns transitions
 * for items the principal can access — both the `root_ids` ancestor walk and the item-level
 * `tags_include` allowlist.
 *
 * `actor` and `verification` on transitions use the same admin-only redaction as notes:
 * - Non-admin callers: `actor` is stripped to `null` and `verification` to `null`
 * - Admin callers: `actor` visible; `verification.proof` (hash + verified claims) visible
 */
fun Route.transitionRoutes(
    repositoryProvider: RepositoryProvider,
    redactAttribution: Boolean = AppConfig.fromEnv().apiRedactNoteAttribution,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val transitionRepo = repositoryProvider.roleTransitionRepository()

    requireCapability(ApiCapability.READ) {
        // ─── GET /items/{id}/transitions ────────────────────────────────────
        get("/items/{id}/transitions") {
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

            val pp = call.pageParamsOrRespond() ?: return@get
            val result =
                legacyRead({
                    transitionLogger.warn("GET /items/{}/transitions DB error: {}", id, it)
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { transitionRepo.findByItemId(id, limit = pp.pageSize + 1, offset = pp.offset) }
            run {
                run {
                    val all = result
                    val page = all.take(pp.pageSize)
                    val hasMore = all.size > pp.pageSize
                    val dtos =
                        page.map { t ->
                            val dto = t.toDto()
                            applyTransitionRedaction(dto, call, redactAttribution)
                        }
                    call.respond(HttpStatusCode.OK, buildPageDto(dtos, pp, null).copy(hasMore = hasMore))
                }
            }
        }

        // ─── GET /transitions ────────────────────────────────────────────────
        get("/transitions") {
            val principal = call.attributes.getOrNull(ApiPrincipalKey)
            val pp = call.pageParamsOrRespond() ?: return@get
            val since =
                (call.instantParamOrRespond("since") ?: return@get).value
                    ?: Instant.now().minusSeconds(86400) // default: last 24 hours
            val fetchLimit =
                minOf(pp.offset.toLong() + pp.pageSize.toLong() + 1L, TRANSITION_SCAN_LIMIT.toLong()).toInt()
            val result =
                legacyRead({
                    transitionLogger.warn("GET /transitions DB error: {}", it)
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@get
                }) { transitionRepo.findSince(since, limit = fetchLimit) }

            run {
                run {
                    var transitions = result

                    // Scope filter (root_ids ancestor walk, then tags_include) via the shared helper:
                    // one batched ancestor lookup, and a lookup error DROPS the rows (deny rather
                    // than leak) instead of returning them unfiltered. No-op, and no query, for
                    // unscoped principals.
                    val allowedIds =
                        allowedItemIdsForScope(
                            principal,
                            transitions.map { it.itemId }.toSet(),
                            workItemRepo,
                        )
                    transitions = transitions.filter { it.itemId in allowedIds }

                    // Paginate
                    val page = transitions.drop(pp.offset).take(pp.pageSize)
                    val hasMore = (pp.offset.toLong() + page.size.toLong()) < transitions.size.toLong()

                    val dtos =
                        page.map { t ->
                            val dto = t.toDto()
                            applyTransitionRedaction(dto, call, redactAttribution)
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
): RoleTransitionDto {
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val isAdmin = principal?.capabilities?.contains(io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability.ADMIN) ?: false

    return if (redactAttribution && !isAdmin) {
        dto.copy(actor = null, verification = null)
    } else {
        val redactedVerification = redactVerification(dto.verification, call, redactAttribution)
        dto.copy(verification = redactedVerification)
    }
}

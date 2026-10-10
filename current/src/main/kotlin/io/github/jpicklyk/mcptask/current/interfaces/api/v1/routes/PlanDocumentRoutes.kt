package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentStashResult
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PlanDocumentListResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PlanDocumentResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.PlanDocumentSummaryDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

private val planDocumentLogger = LoggerFactory.getLogger("PlanDocumentRoutes")

/**
 * Registers the per-root plan document read/write routes under `/api/v1/roots/{rootId}/plans`.
 *
 * Endpoints:
 * - `PUT  /roots/{rootId}/plans/{slug}` — validate + upsert the raw document body
 *   ([ApiCapability.WRITE_CONFIG] + scope); delegates the FULL validate-then-persist pipeline to
 *   [PlanDocumentService] — the SAME service the MCP `manage_plan_documents` tool's `stash`
 *   operation calls, so both surfaces converge on identical DB state (and `contentHash`) for the
 *   same payload. Body must not exceed [PlanDocumentService.MAX_BODY_BYTES] (413); a slug already
 *   ADOPTED is rejected as 409 (adoption is a one-way transition — see
 *   [io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStashOutcome.AdoptedConflict]).
 * - `GET  /roots/{rootId}/plans/{slug}` — read back the stored document, including its body
 *   ([ApiCapability.READ] + scope); 404 when no document exists at that slug.
 * - `GET  /roots/{rootId}/plans` — list metadata-only summaries (no body) for every document under
 *   the root ([ApiCapability.READ] + scope); optional `?status=pending|adopted` filter.
 *
 * **Scope:** all verbs enforce [enforceScopeForItem] against `{rootId}` itself — a per-project
 * token can only read/stash its own root's plan documents. Mirrors [projectConfigRoutes]'s
 * capability/scope/error-DTO conventions.
 *
 * **REST-only:** registered on the authenticated `/api/v1` pipeline only, like [projectConfigRoutes]
 * — never reachable on the unauthenticated `/mcp` transport.
 */
fun Route.planDocumentRoutes(
    repositoryProvider: RepositoryProvider,
    unitOfWork: UnitOfWork,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val service = PlanDocumentService(repositoryProvider, unitOfWork)

    route("/roots/{rootId}/plans") {
        // ─── GET /roots/{rootId}/plans (list, metadata only) ─────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val rootId = call.parseRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@get
                }

                val statusFilter =
                    call.request.queryParameters["status"]?.let { raw ->
                        runCatchingNonCancellation { PlanDocumentStatus.fromDbValue(raw) }.getOrElse {
                            call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid status '$raw'; expected pending or adopted")
                            return@get
                        }
                    }

                run {
                    val result =
                        legacyRead({
                            return@run run {
                                planDocumentLogger.warn("GET /roots/{}/plans DB error: {}", rootId, it)
                                call.respondError(LegacyRestCode.DB_ERROR, "Failed to list plan documents")
                            }
                        }) { service.list(rootId, statusFilter) }
                    call.respond(
                        HttpStatusCode.OK,
                        PlanDocumentListResponseDto(
                            rootId = rootId.toString(),
                            plans =
                                result.map { summary ->
                                    PlanDocumentSummaryDto(
                                        id = summary.id.toString(),
                                        rootId = summary.rootItemId.toString(),
                                        slug = summary.slug,
                                        contentHash = summary.contentHash,
                                        status = summary.status.toDbValue(),
                                        adoptedByItemId = summary.adoptedByItemId?.toString(),
                                        createdAt = summary.createdAt.toString(),
                                        updatedAt = summary.modifiedAt.toString(),
                                    )
                                },
                        ),
                    )
                }
            }
        }

        route("/{slug}") {
            // ─── GET /roots/{rootId}/plans/{slug} ─────────────────────────────
            requireCapability(ApiCapability.READ) {
                get {
                    val rootId = call.parseRootId() ?: return@get
                    val slug = call.parseSlug() ?: return@get

                    if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                        call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                        return@get
                    }

                    run {
                        val result =
                            legacyRead({
                                return@run run {
                                    planDocumentLogger.warn("GET /roots/{}/plans/{} DB error: {}", rootId, slug, it)
                                    call.respondError(LegacyRestCode.DB_ERROR, "Failed to read plan document")
                                }
                            }) { service.get(rootId, slug) }
                        val document =
                            result ?: run {
                                call.respondError(LegacyRestCode.NOT_FOUND, "No plan document found for root $rootId, slug $slug")
                                return@get
                            }
                        call.respond(HttpStatusCode.OK, document.toResponseDto(includeBody = true))
                    }
                }
            }

            // ─── PUT /roots/{rootId}/plans/{slug} ─────────────────────────────
            requireCapability(ApiCapability.WRITE_CONFIG) {
                put {
                    val rootId = call.parseRootId() ?: return@put
                    val slug = call.parseSlug() ?: return@put

                    if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                        call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                        return@put
                    }

                    // Bounded (bug e941c2c7): the cap was previously enforced only AFTER the full
                    // body had already been buffered by receiveText(). receiveBounded enforces
                    // the SAME numeric limit (PlanDocumentService.MAX_BODY_BYTES, unchanged) but
                    // before buffering — see its KDoc for the two-stage Content-Length /
                    // bounded-channel-read mechanism.
                    val body = call.receiveBounded(PlanDocumentService.MAX_BODY_BYTES) ?: return@put

                    when (val result = service.stash(rootId, slug, body)) {
                        is PlanDocumentStashResult.Success ->
                            call.respond(HttpStatusCode.OK, result.document.toResponseDto(includeBody = false))
                        is PlanDocumentStashResult.NotFound ->
                            call.respondError(LegacyRestCode.NOT_FOUND, "Root WorkItem not found: ${result.rootItemId}")
                        is PlanDocumentStashResult.NotDepthZero ->
                            call.respondError(
                                LegacyRestCode.VALIDATION_ERROR_UNPROCESSABLE,
                                "rootId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}"
                            )
                        is PlanDocumentStashResult.TooLarge ->
                            // Defensive — the pre-check above already covers this in practice.
                            call.respondError(
                                LegacyRestCode.PAYLOAD_TOO_LARGE,
                                "body is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte limit"
                            )
                        is PlanDocumentStashResult.AdoptedConflict ->
                            call.respondError(
                                LegacyRestCode.ADOPTED_CONFLICT,
                                "slug '$slug' has already been adopted" +
                                    (result.existing.adoptedByItemId?.let { " by item $it" } ?: "") +
                                    "; adoption is one-way and cannot be overwritten"
                            )
                        is PlanDocumentStashResult.RepositoryError -> {
                            planDocumentLogger.warn("PUT /roots/{}/plans/{} DB error: {}", rootId, slug, result.message)
                            call.respondError(LegacyRestCode.DB_ERROR, "Failed to store plan document")
                        }
                    }
                }
            }
        }
    }
}

private fun PlanDocument.toResponseDto(includeBody: Boolean) =
    PlanDocumentResponseDto(
        id = id.toString(),
        rootId = rootItemId.toString(),
        slug = slug,
        contentHash = contentHash,
        status = status.toDbValue(),
        adoptedByItemId = adoptedByItemId?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = modifiedAt.toString(),
        body = if (includeBody) body else null,
    )

/** Parses the `{rootId}` path parameter as a UUID; responds 400 and returns null on failure. */
private suspend fun ApplicationCall.parseRootId(): UUID? {
    val rawId =
        parameters["rootId"] ?: run {
            respondError(LegacyRestCode.BAD_REQUEST, "Missing rootId")
            return null
        }
    return runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
        respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
        null
    }
}

/** Parses the `{slug}` path parameter; responds 400 and returns null when missing or blank. */
private suspend fun ApplicationCall.parseSlug(): String? {
    val slug = parameters["slug"]?.takeIf { it.isNotBlank() }
    if (slug == null) {
        respondError(LegacyRestCode.BAD_REQUEST, "Missing slug")
        return null
    }
    return slug
}

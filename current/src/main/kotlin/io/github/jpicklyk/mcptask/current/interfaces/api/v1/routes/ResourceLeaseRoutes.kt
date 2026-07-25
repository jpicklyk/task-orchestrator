package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.hasCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ResourceLeaseListResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ResourceLeaseReleaseResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private val resourceLeaseLogger = LoggerFactory.getLogger("ResourceLeaseRoutes")

/** Lowercase-slug-like resource key pattern, matching [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition.key]. */
private val RESOURCE_KEY_PATTERN = Regex("^[a-z0-9][a-z0-9\\-_./]*$")
private const val RESOURCE_KEY_MAX_LENGTH = 128

/**
 * Registers the operator-facing resource-lease read/force-release routes under
 * `/api/v1/resources/leases`.
 *
 * Endpoints:
 * - `GET    /resources/leases`      — list every currently active (non-expired) lease across all
 *   keys and holders ([ApiCapability.READ]). `acquiredByActorId` (holder-identity) is included only
 *   for callers with [ApiCapability.ADMIN]; non-admin callers get it omitted, mirroring the
 *   [io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor] admin-gating
 *   pattern used for note attribution (leases have no note/verification shape, so that class itself
 *   isn't reused — the same admin-only rule is applied inline here instead).
 * - `DELETE /resources/leases/{key}` — administrative override: force-releases every row (any
 *   holder) for `{key}`, bypassing its TTL ([ApiCapability.ADMIN] — never granted by [ApiCapability.READ]
 *   alone). `{key}` is validated against the same resource-key pattern used at config-parse time
 *   (`^[a-z0-9][a-z0-9\-_./]*$`, max 128 chars) before touching the repository (400 on mismatch).
 *   Returns 404 when no active lease was released (`releasedCount == 0` is not itself an error at
 *   the repository layer, but this operator surface treats "nothing to release" as not-found), 200
 *   with `{resourceKey, releasedCount}` otherwise. Every successful force-release is logged at WARN
 *   naming the acting principal and the key — this bypasses normal TTL-based mutual exclusion, so it
 *   must be auditable from logs alone.
 *
 * **No scope enforcement:** resource leases are a cross-project, server-wide concurrency primitive
 * (unlike per-root items/config/plans) — there is no `rootId` to scope against, so only capability
 * checks apply here.
 *
 * **REST-only:** registered on the authenticated `/api/v1` pipeline only, like the other
 * `/api/v1/resources` routes — never reachable on the unauthenticated `/mcp` transport.
 */
fun Route.resourceLeaseRoutes(repositoryProvider: RepositoryProvider) {
    val leaseRepo = repositoryProvider.resourceLeaseRepository()

    route("/resources/leases") {
        // ─── GET /resources/leases ─────────────────────────────────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val isAdmin = hasCapability(call, ApiCapability.ADMIN)
                val leases =
                    leaseRepo.findAllActive().map { lease ->
                        val dto = lease.toDto()
                        if (isAdmin) dto else dto.copy(acquiredByActorId = null)
                    }
                call.respond(HttpStatusCode.OK, ResourceLeaseListResponseDto(leases = leases))
            }
        }

        // ─── DELETE /resources/leases/{key} ────────────────────────────────────
        requireCapability(ApiCapability.ADMIN) {
            delete("/{key}") {
                val key = call.parameters["key"]
                if (key.isNullOrBlank() || key.length > RESOURCE_KEY_MAX_LENGTH || !RESOURCE_KEY_PATTERN.matches(key)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(
                            "validation_error",
                            "resourceKey must be 1-$RESOURCE_KEY_MAX_LENGTH characters matching " +
                                "^[a-z0-9][a-z0-9\\-_./]*$",
                        ),
                    )
                    return@delete
                }

                when (val result = leaseRepo.forceReleaseByKey(key)) {
                    is LeaseReleaseResult.Success -> {
                        if (result.releasedCount == 0) {
                            call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "No active lease found for key '$key'"))
                            return@delete
                        }

                        val principal = call.attributes.getOrNull(ApiPrincipalKey)
                        resourceLeaseLogger.warn(
                            "Operator force-release: {} lease row(s) released for resourceKey='{}' by principal tokenId='{}'",
                            result.releasedCount,
                            key,
                            principal?.tokenId ?: "unknown",
                        )

                        call.respond(
                            HttpStatusCode.OK,
                            ResourceLeaseReleaseResponseDto(resourceKey = key, releasedCount = result.releasedCount),
                        )
                    }
                    is LeaseReleaseResult.DBError -> {
                        resourceLeaseLogger.warn("DELETE /resources/leases/{} DB error: {}", key, result.cause.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to force-release lease"))
                    }
                }
            }
        }
    }
}

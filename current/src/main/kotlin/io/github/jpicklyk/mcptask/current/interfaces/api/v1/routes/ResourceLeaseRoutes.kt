package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.configName
import io.github.jpicklyk.mcptask.current.application.api.grants
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

private val resourceLeaseLogger = LoggerFactory.getLogger("ResourceLeaseRoutes")

/** Lowercase-slug-like resource key pattern, matching [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition.key]. */
private val RESOURCE_KEY_PATTERN = Regex("^[a-z0-9][a-z0-9\\-_./]*$")
private const val RESOURCE_KEY_MAX_LENGTH = 128
private const val HISTORY_DEFAULT_LIMIT = 50
private const val HISTORY_MAX_LIMIT = 200

/**
 * Registers the operator-facing resource-lease read/force-release routes under `/api/v1/resources/leases`.
 * Every answer is the 4.0 envelope with the status of its error code; a missing capability is `forbidden`
 * (`scope: capability`, `required` the capability name). A 401 from the authentication plugin is the
 * `unauthenticated` envelope too.
 *
 * Endpoints:
 * - `GET    /resources/leases`: every currently active (non-expired) lease, as a list page ordered by
 *   (`resourceKey`, `holderItemId`) with `page.total == items.size` ([ApiCapability.READ]). Items are
 *   `{resourceKey, holderItemId, acquiredByActorId?, acquiredAt, expiresAt, originalAcquiredAt}`;
 *   `acquiredByActorId` (holder identity) is present only for [ApiCapability.ADMIN].
 * - `GET    /resources/leases/history?key&at&limit&cursor`: the append-only lease-interval log, newest-first
 *   `(acquiredAt DESC, id ASC)`, as a list page ([ApiCapability.READ]). `page.total` is the count of ALL matching
 *   intervals on every page; `truncated`/`next` follow the list-page rule, and `cursor` (the previous `next`, an
 *   offset only, not bound to key/at) walks every interval exactly once only while no lease starts or ends during the
 *   walk (newest-first, so activity during a walk can repeat or skip rows). Field faults (`invalid_request`) are
 *   answered before a malformed or empty `cursor` (`invalid_cursor`). `key` is validated like the DELETE key (`invalid_request` field `key`), `at` is
 *   an ISO-8601 instant (field `at`) answering "who held this at that time", `limit` is 1..200 (default 50;
 *   field `limit`). Items are `{resourceKey, holderItemId, acquiredByActorId?, acquiredAt, expiresAt,
 *   releasedAt?, releaseReason?, releasedByActorId?}`; the two actor ids are ADMIN-only.
 * - `DELETE /resources/leases/{key}`: administrative override, force-releases every holder of `{key}` bypassing
 *   its TTL ([ApiCapability.ADMIN]). `{ok: true, data: {resourceKey, releasedCount}}`; nothing released is
 *   `not_found` with kind `lease`. Every successful force-release is logged at WARN naming the acting principal and
 *   the key, and the principal's token id is recorded on the closed history interval(s).
 *
 * **No scope enforcement:** leases are a cross-project, server-wide concurrency primitive, so only capability
 * checks apply. **REST-only:** registered on the authenticated `/api/v1` pipeline, never on the `/mcp` transport.
 */
fun Route.resourceLeaseRoutes(claimService: ClaimService) {
    route("/resources/leases") {
        // GET /resources/leases
        get {
            val reqId = reqId(call)
            val principal = authorize(call, reqId, ApiCapability.READ) ?: return@get
            val isAdmin = principal.capabilities.grants(ApiCapability.ADMIN)
            val leases = claimService.activeLeases()
            // D7: the active-lease set is bounded; the page holds every lease (total = items.size).
            val page = ListPage.of(leases, total = leases.size, page = PageRequest())
            call.respondEnvelope(EnvelopeEncoder.page(page, reqId) { leaseJson(it, isAdmin) })
        }

        // DELETE /resources/leases/{key}
        delete("/{key}") {
            val reqId = reqId(call)
            val principal = authorize(call, reqId, ApiCapability.ADMIN) ?: return@delete
            val key = call.parameters["key"].orEmpty()
            if (!validKey(key)) {
                call.respondEnvelope(EnvelopeEncoder.error(invalid("key", keyReason(), key.take(MAX_RECEIVED)), reqId))
                return@delete
            }
            val released =
                when (val outcome = claimService.forceReleaseLease(key, principal.id)) {
                    is Outcome.Err -> {
                        resourceLeaseLogger.warn("DELETE /resources/leases/{} failed: {}", key, outcome.error.message)
                        call.respondEnvelope(EnvelopeEncoder.error(outcome.error, reqId))
                        return@delete
                    }
                    is Outcome.Ok -> (outcome.value as LeaseReleaseResult.Success).releasedCount
                }
            if (released == 0) {
                call.respondEnvelope(EnvelopeEncoder.error(leaseNotFound(key), reqId))
                return@delete
            }
            resourceLeaseLogger.warn(
                "Operator force-release: {} lease row(s) released for resourceKey='{}' by principal id='{}'",
                released,
                key,
                principal.id
            )
            call.respondEnvelope(
                EnvelopeEncoder.outcome(Outcome.Ok(released), reqId) {
                    buildJsonObject {
                        put("resourceKey", key)
                        put("releasedCount", it)
                    }
                }
            )
        }

        // GET /resources/leases/history
        get("/history") {
            val reqId = reqId(call)
            val principal = authorize(call, reqId, ApiCapability.READ) ?: return@get
            val isAdmin = principal.capabilities.grants(ApiCapability.ADMIN)
            val query = call.request.queryParameters

            val key = query["key"]
            val atRaw = query["at"]
            val limitRaw = query["limit"]
            val cursorRaw = query["cursor"]
            val faults = ArrayList<FieldViolation>()
            if (key != null && !validKey(key)) faults.add(FieldViolation("key", keyReason(), key.take(MAX_RECEIVED)))
            val at =
                atRaw?.let {
                    try {
                        Instant.parse(it)
                    } catch (e: DateTimeParseException) {
                        faults.add(FieldViolation("at", "must be an ISO-8601 instant", it.take(MAX_RECEIVED)))
                        null
                    }
                }
            val limit =
                if (limitRaw == null) {
                    HISTORY_DEFAULT_LIMIT
                } else {
                    limitRaw.toIntOrNull()?.takeIf { it in 1..HISTORY_MAX_LIMIT }
                        ?: HISTORY_DEFAULT_LIMIT.also {
                            faults.add(
                                FieldViolation("limit", "must be an integer from 1 to $HISTORY_MAX_LIMIT", limitRaw.take(MAX_RECEIVED))
                            )
                        }
                }
            if (faults.isNotEmpty()) {
                call.respondEnvelope(EnvelopeEncoder.error(invalid(faults.sortedBy { it.field }), reqId))
                return@get
            }

            val pageRequest =
                when (val decoded = PageRequest.from(limit, cursorRaw)) {
                    is Outcome.Err -> {
                        call.respondEnvelope(EnvelopeEncoder.error(decoded.error, reqId))
                        return@get
                    }
                    is Outcome.Ok -> decoded.value
                }
            val result = claimService.leaseHistory(key, at, pageRequest)
            val page = ListPage.of(result.items, total = result.total, page = pageRequest)
            call.respondEnvelope(EnvelopeEncoder.page(page, reqId) { intervalJson(it, isAdmin) })
        }
    }
}

private const val MAX_RECEIVED = 100

private fun reqId(call: ApplicationCall): String = call.response.headers[REQ_ID_HEADER] ?: ReqId.generate()

/**
 * The authenticated principal when it grants [required]; otherwise the envelope answer is sent here and null is
 * returned (no principal is `unauthenticated`, a missing capability is `forbidden`).
 */
private suspend fun authorize(
    call: ApplicationCall,
    reqId: String,
    required: ApiCapability
): Principal? {
    val principal = call.attributes.getOrNull(ApiPrincipalKey)
    val error =
        when {
            principal == null -> DomainError(ErrorCode.UNAUTHENTICATED, "No authenticated principal.")
            !principal.capabilities.grants(required) ->
                DomainError(
                    code = ErrorCode.FORBIDDEN,
                    message = "The credential does not grant ${required.configName()} for the capability scope.",
                    detail = ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, required.configName()),
                    fixArgs = mapOf("scope" to "capability", "required" to required.configName())
                )
            else -> null
        }
    if (error != null) {
        call.respondEnvelope(EnvelopeEncoder.error(error, reqId))
        return null
    }
    return principal
}

private fun validKey(key: String): Boolean = key.isNotBlank() && key.length <= RESOURCE_KEY_MAX_LENGTH && RESOURCE_KEY_PATTERN.matches(key)

private fun keyReason(): String = "must be 1 to $RESOURCE_KEY_MAX_LENGTH characters matching ^[a-z0-9][a-z0-9\\-_./]*$"

private fun invalid(
    field: String,
    reason: String,
    received: String? = null
): DomainError = invalid(listOf(FieldViolation(field, reason, received)))

private fun invalid(fields: List<FieldViolation>): DomainError =
    DomainError(
        code = ErrorCode.INVALID_REQUEST,
        message = "The request has ${fields.size} invalid field(s): ${fields.joinToString(", ") { it.field }}.",
        detail = ErrorDetail.InvalidRequest(fields)
    )

private fun leaseNotFound(key: String): DomainError =
    DomainError(
        code = ErrorCode.NOT_FOUND,
        message = "No active lease found for key '$key'.",
        detail = ErrorDetail.NotFound(EntityKind.LEASE, key),
        fixArgs = mapOf("kind" to "lease", "id" to key)
    )

private fun leaseJson(
    lease: ResourceLease,
    admin: Boolean
): JsonObject =
    buildJsonObject {
        put("resourceKey", lease.resourceKey)
        put("holderItemId", lease.holderItemId.toString())
        if (admin) lease.acquiredByActorId?.let { put("acquiredByActorId", it) }
        put("acquiredAt", lease.acquiredAt.toString())
        put("expiresAt", lease.expiresAt.toString())
        put("originalAcquiredAt", lease.originalAcquiredAt.toString())
    }

private fun intervalJson(
    interval: ResourceLeaseInterval,
    admin: Boolean
): JsonObject =
    buildJsonObject {
        put("resourceKey", interval.resourceKey)
        put("holderItemId", interval.holderItemId.toString())
        if (admin) interval.acquiredByActorId?.let { put("acquiredByActorId", it) }
        put("acquiredAt", interval.acquiredAt.toString())
        put("expiresAt", interval.expiresAt.toString())
        interval.releasedAt?.let { put("releasedAt", it.toString()) }
        interval.releaseReason?.let { put("releaseReason", it) }
        if (admin) interval.releasedByActorId?.let { put("releasedByActorId", it) }
    }

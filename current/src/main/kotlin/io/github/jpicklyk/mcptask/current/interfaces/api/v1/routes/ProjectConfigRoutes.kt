package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProjectConfigResponseDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

private val projectConfigLogger = LoggerFactory.getLogger("ProjectConfigRoutes")

/**
 * ETag format for a stored per-root config, derived from its content fingerprint:
 * `"cfg-<fingerprint>"` (quoted per RFC 7232). Distinct from [io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor]
 * (which is timestamp-derived, `"v1-<epochMillis>"`) — config rows use a content hash instead of a
 * timestamp because [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository.upsert]
 * already computes one (needed for hot-reload change-detection) and reusing it keeps a
 * byte-identical re-push naturally idempotent under `If-Match`.
 */
private fun configEtag(fingerprint: String): String = "\"cfg-$fingerprint\""

/**
 * Registers the per-root config read/write/delete routes under `/api/v1/roots/{rootId}/config`.
 *
 * Endpoints:
 * - `GET    /roots/{rootId}/config` — read back the stored config ([ApiCapability.READ] + scope);
 *   `If-None-Match` → 304 when unchanged; 404 when no config has been pushed for the root. Optional
 *   `?fingerprint=<sha256>` classifies that value against the root's stored fingerprint
 *   history and adds an additive `relation` field ("current"/"superseded"/"unknown") to the
 *   response body — omitted entirely when the query param is absent.
 * - `PUT    /roots/{rootId}/config` — validate + upsert raw YAML body ([ApiCapability.WRITE_CONFIG] +
 *   scope); delegates the FULL validate-then-persist pipeline to [ProjectConfigPushService] — the
 *   SAME service the MCP `manage_project_config` tool's `push` operation calls, so both surfaces
 *   converge on identical DB state for the same payload. Body must not exceed
 *   [ProjectConfigPushService.MAX_CONFIG_YAML_BYTES] (413); malformed/unsafe YAML is rejected before
 *   storing (422); a `configYaml` embedding a mismatched top-level `project.rootId` is rejected as
 *   422 `rootid_mismatch` unless the `?force=true` query param is set; a `configYaml` whose
 *   fingerprint is known-old (present in history but not current) is rejected as 409 `superseded`
 *   unless `?force=true` — distinct from the 412 `If-Match` case below, which is a concurrent-write
 *   guard rather than a known-old-content guard; optional `If-Match` against the current
 *   fingerprint-derived ETag (412 on mismatch, ignored when no row exists yet — a first push is a
 *   create). On success, top-level `configYaml` keys not honored by the per-root resolution layer
 *   (e.g. `actor_authentication`) are reported in an additive `ignoredSections` field, omitted when
 *   empty.
 * - `DELETE /roots/{rootId}/config` — remove the stored config row ([ApiCapability.WRITE_CONFIG] +
 *   scope); 404 when no row exists.
 *
 * **Scope:** all three verbs enforce [enforceScopeForItem] against `{rootId}` itself (a root has no
 * ancestors, so this reduces to "the token's `rootIds` must contain this exact root") — a per-project
 * token can only read/push/delete its own root's config.
 *
 * **REST-only:** this route is registered on the authenticated `/api/v1` pipeline only (see
 * [io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes]) and is never reachable on
 * the unauthenticated `/mcp` transport.
 */
fun Route.projectConfigRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val projectConfigRepo = repositoryProvider.projectConfigRepository()
    val service = ProjectConfigPushService(repositoryProvider)

    route("/roots/{rootId}/config") {
        // ─── GET /roots/{rootId}/config ──────────────────────────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val rootId = call.parseRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                    return@get
                }

                when (val result = projectConfigRepo.get(rootId)) {
                    is Result.Success -> {
                        val config =
                            result.data ?: run {
                                call.respond(
                                    HttpStatusCode.NotFound,
                                    ErrorDto("not_found", "No project config found for root $rootId"),
                                )
                                return@get
                            }

                        val etag = configEtag(config.fingerprint)
                        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim()
                        if (ifNoneMatch != null && ifNoneMatch == etag) {
                            call.response.header(HttpHeaders.ETag, etag)
                            call.respond(HttpStatusCode.NotModified)
                            return@get
                        }

                        val relation =
                            call.request.queryParameters["fingerprint"]?.let { queriedFingerprint ->
                                when (val relationResult = projectConfigRepo.classifyFingerprint(rootId, queriedFingerprint)) {
                                    is Result.Success -> relationResult.data.name.lowercase()
                                    is Result.Error -> null
                                }
                            }

                        call.response.header(HttpHeaders.ETag, etag)
                        call.respond(
                            HttpStatusCode.OK,
                            ProjectConfigResponseDto(
                                rootItemId = config.rootItemId.toString(),
                                fingerprint = config.fingerprint,
                                updatedAt = config.updatedAt.toString(),
                                configYaml = config.configYaml,
                                relation = relation,
                            ),
                        )
                    }
                    is Result.Error -> {
                        projectConfigLogger.warn("GET /roots/{}/config DB error: {}", rootId, result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to read project config"))
                    }
                }
            }
        }

        // ─── PUT /roots/{rootId}/config ──────────────────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            put {
                val rootId = call.parseRootId() ?: return@put

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                    return@put
                }

                val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
                val configYaml = call.receiveText()
                val sizeBytes = configYaml.toByteArray(Charsets.UTF_8).size
                if (sizeBytes > ProjectConfigPushService.MAX_CONFIG_YAML_BYTES) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ErrorDto(
                            "payload_too_large",
                            "configYaml is $sizeBytes bytes, exceeds the " +
                                "${ProjectConfigPushService.MAX_CONFIG_YAML_BYTES} byte limit",
                        ),
                    )
                    return@put
                }

                // If-Match is only enforced against an EXISTING row — mirrors NoteWriteRoutes' PUT
                // upsert semantics: a first push is a create with no prior ETag to match, so a
                // stray If-Match on a not-yet-existing root is ignored rather than 412'd.
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
                if (ifMatch != null) {
                    val existingFingerprint =
                        when (val fpResult = projectConfigRepo.getFingerprint(rootId)) {
                            is Result.Success -> fpResult.data
                            is Result.Error -> null
                        }
                    if (existingFingerprint != null) {
                        val currentEtag = configEtag(existingFingerprint)
                        if (ifMatch != currentEtag) {
                            call.response.header(HttpHeaders.ETag, currentEtag)
                            call.respond(
                                HttpStatusCode.PreconditionFailed,
                                ErrorDto("etag_mismatch", "ETag mismatch; current ETag is $currentEtag"),
                            )
                            return@put
                        }
                    }
                }

                when (val result = service.push(rootId, configYaml, force)) {
                    is ProjectConfigPushResult.Success -> {
                        val etag = configEtag(result.fingerprint)
                        call.response.header(HttpHeaders.ETag, etag)
                        call.respond(
                            HttpStatusCode.OK,
                            ProjectConfigResponseDto(
                                rootItemId = result.rootItemId.toString(),
                                fingerprint = result.fingerprint,
                                updatedAt = result.updatedAt.toString(),
                                warning = result.warning,
                                ignoredSections = result.ignoredSections.ifEmpty { null },
                            ),
                        )
                    }
                    is ProjectConfigPushResult.NotFound ->
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorDto("not_found", "Root WorkItem not found: ${result.rootItemId}"),
                        )
                    is ProjectConfigPushResult.NotDepthZero ->
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto(
                                "validation_error",
                                "rootId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}",
                            ),
                        )
                    is ProjectConfigPushResult.TooLarge ->
                        // Defensive — the pre-check above already covers this in practice.
                        call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ErrorDto(
                                "payload_too_large",
                                "configYaml is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte limit",
                            ),
                        )
                    is ProjectConfigPushResult.ParseError ->
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto("parse_error", "configYaml failed to parse: ${result.detail}"),
                        )
                    is ProjectConfigPushResult.RootIdMismatch ->
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto(
                                "rootid_mismatch",
                                "configYaml embeds project.rootId '${result.embeddedRootId}', which differs " +
                                    "from the target rootId '${result.targetRootId}'; fix project.rootId in " +
                                    "the document or retry with ?force=true",
                            ),
                        )
                    is ProjectConfigPushResult.Superseded ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ErrorDto(
                                "superseded",
                                "local config is older than the server's (updated ${result.currentUpdatedAt}); " +
                                    "pull or copy back before editing, or retry with ?force=true",
                            ),
                        )
                    is ProjectConfigPushResult.RepositoryError -> {
                        projectConfigLogger.warn("PUT /roots/{}/config DB error: {}", rootId, result.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to store project config"))
                    }
                }
            }
        }

        // ─── DELETE /roots/{rootId}/config ───────────────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            delete {
                val rootId = call.parseRootId() ?: return@delete

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                    return@delete
                }

                when (val result = projectConfigRepo.delete(rootId)) {
                    is Result.Success -> {
                        if (!result.data) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorDto("not_found", "No project config found for root $rootId"),
                            )
                            return@delete
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    is Result.Error -> {
                        projectConfigLogger.warn("DELETE /roots/{}/config DB error: {}", rootId, result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to delete project config"))
                    }
                }
            }
        }
    }
}

/** Parses the `{rootId}` path parameter as a UUID; responds 400 and returns null on failure. */
private suspend fun ApplicationCall.parseRootId(): UUID? {
    val rawId =
        parameters["rootId"] ?: run {
            respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing rootId"))
            return null
        }
    return runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
        null
    }
}

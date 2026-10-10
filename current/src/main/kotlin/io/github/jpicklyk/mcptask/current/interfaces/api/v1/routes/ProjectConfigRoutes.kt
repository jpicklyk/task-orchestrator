package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProjectConfigResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
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
 * timestamp because [io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore.upsert]
 * already computes one (needed for hot-reload change-detection) and reusing it keeps a
 * byte-identical re-push naturally idempotent under `If-Match`.
 */
private fun configEtag(fingerprint: String): String = "\"cfg-$fingerprint\""

/** The `"cfg-"` prefix of [configEtag]'s ETag format, factored out for If-Match header parsing. */
private const val CFG_ETAG_PREFIX = "\"cfg-"

/**
 * Stands in for an If-Match header value that does not parse as `"cfg-<fingerprint>"` (including
 * an empty string) -- passed through to [ProjectConfigPushService.push] as `expectedFingerprint`
 * so it can never equal a real stored fingerprint, guaranteeing a mismatch (412/PreconditionFailed)
 * on an EXISTING row while still being ignored when no row exists yet (upsertGuarded only compares
 * expectedFingerprint against a row that exists).
 */
private const val NON_MATCHING_FINGERPRINT_SENTINEL = "<malformed-if-match>"

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
 *   empty. Soft schema/trait parse warnings (e.g. an invalid note `role`, a note entry missing
 *   `key`) do NOT fail the push — the config is still stored with a 200 — but are reported in an
 *   additive `schemaWarnings` field, omitted when empty.
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
fun Route.projectConfigRoutes(
    repositoryProvider: RepositoryProvider,
    unitOfWork: UnitOfWork,
    configDocumentParser: ConfigDocumentParser = YamlConfigDocumentParser,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val projectConfigRepo = repositoryProvider.projectConfigRepository()
    val service = ProjectConfigPushService(repositoryProvider, configDocumentParser, unitOfWork)

    route("/roots/{rootId}/config") {
        // ─── GET /roots/{rootId}/config ──────────────────────────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val rootId = call.parseRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@get
                }

                run {
                    val result =
                        legacyRead({
                            return@run run {
                                projectConfigLogger.warn("GET /roots/{}/config DB error: {}", rootId, it)
                                call.respondError(LegacyRestCode.DB_ERROR, "Failed to read project config")
                            }
                        }) { projectConfigRepo.get(rootId) }
                    val config =
                        result ?: run {
                            call.respondError(LegacyRestCode.NOT_FOUND, "No project config found for root $rootId")
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
                            service.classifyRelation(rootId, queriedFingerprint)
                        }

                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(
                        HttpStatusCode.OK,
                        ProjectConfigResponseDto(
                            rootId = config.rootItemId.toString(),
                            fingerprint = config.fingerprint,
                            updatedAt = config.updatedAt.toString(),
                            configYaml = config.configYaml,
                            relation = relation,
                        ),
                    )
                }
            }
        }

        // ─── PUT /roots/{rootId}/config ──────────────────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            put {
                val rootId = call.parseRootId() ?: return@put

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@put
                }

                val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
                // Bounded (bug e941c2c7): the cap was previously enforced only AFTER the full
                // body had already been buffered by receiveText(). receiveBounded enforces the
                // SAME numeric limit (ProjectConfigPushService.MAX_CONFIG_YAML_BYTES, unchanged)
                // but before buffering — see its KDoc for the two-stage Content-Length /
                // bounded-channel-read mechanism.
                val configYaml =
                    call.receiveBounded(ProjectConfigPushService.MAX_CONFIG_YAML_BYTES) ?: return@put

                // If-Match maps "cfg-<fingerprint>" to the raw fingerprint; any other supplied
                // value (including an empty string) maps to a sentinel that can never equal a real
                // fingerprint, preserving the prior "mismatch on an EXISTING row -> 412, ignored
                // when no row exists yet" behavior (a first push is a create with nothing to
                // compare against). Evaluated INSIDE service.push's upsertGuarded transaction, per
                // api-rest.md PUT step order: size cap -> root exists -> depth-0 -> parse ->
                // rootId guard -> THEN this precondition, atomic with the fast-forward guard and
                // the write itself -- no separate pre-read here, so a fingerprint-read failure can
                // no longer silently skip If-Match (previously fail-open: a getFingerprint
                // failure mapped to null and the check was skipped; now a repository error
                // surfaces as ProjectConfigPushResult.RepositoryError -> 500 db_error, fail-closed).
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
                val expectedFingerprint =
                    when {
                        ifMatch == null -> null
                        ifMatch.startsWith(CFG_ETAG_PREFIX) && ifMatch.endsWith("\"") ->
                            ifMatch.removePrefix(CFG_ETAG_PREFIX).removeSuffix("\"")
                        else -> NON_MATCHING_FINGERPRINT_SENTINEL
                    }

                when (val result = service.push(rootId, configYaml, force, expectedFingerprint)) {
                    is ProjectConfigPushResult.Success -> {
                        val etag = configEtag(result.fingerprint)
                        call.response.header(HttpHeaders.ETag, etag)
                        call.respond(
                            HttpStatusCode.OK,
                            ProjectConfigResponseDto(
                                rootId = result.rootItemId.toString(),
                                fingerprint = result.fingerprint,
                                updatedAt = result.updatedAt.toString(),
                                warning = result.warning,
                                ignoredSections = result.ignoredSections.ifEmpty { null },
                                schemaWarnings = result.schemaWarnings.ifEmpty { null },
                            ),
                        )
                    }
                    is ProjectConfigPushResult.NotFound ->
                        call.respondError(LegacyRestCode.NOT_FOUND, "Root WorkItem not found: ${result.rootItemId}")
                    is ProjectConfigPushResult.NotDepthZero ->
                        call.respondError(
                            LegacyRestCode.VALIDATION_ERROR_UNPROCESSABLE,
                            "rootId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}"
                        )
                    is ProjectConfigPushResult.TooLarge ->
                        // Defensive — the pre-check above already covers this in practice.
                        call.respondError(
                            LegacyRestCode.PAYLOAD_TOO_LARGE,
                            "configYaml is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte limit"
                        )
                    is ProjectConfigPushResult.ParseError ->
                        call.respondError(LegacyRestCode.PARSE_ERROR, "configYaml failed to parse: ${result.detail}")
                    is ProjectConfigPushResult.RootIdMismatch ->
                        call.respondError(
                            LegacyRestCode.ROOTID_MISMATCH,
                            "configYaml embeds project.rootId '${result.embeddedRootId}', which differs " +
                                "from the target rootId '${result.targetRootId}'; fix project.rootId in " +
                                "the document or retry with ?force=true"
                        )
                    is ProjectConfigPushResult.Superseded ->
                        call.respondError(
                            LegacyRestCode.SUPERSEDED,
                            "local config is older than the server's (updated ${result.currentUpdatedAt}); " +
                                "pull or copy back before editing, or retry with ?force=true"
                        )
                    is ProjectConfigPushResult.PreconditionFailed -> {
                        val currentEtag = configEtag(result.currentFingerprint)
                        call.response.header(HttpHeaders.ETag, currentEtag)
                        call.respondError(LegacyRestCode.ETAG_MISMATCH, "ETag mismatch; current ETag is $currentEtag")
                    }
                    is ProjectConfigPushResult.RepositoryError -> {
                        projectConfigLogger.warn("PUT /roots/{}/config DB error: {}", rootId, result.message)
                        call.respondError(LegacyRestCode.DB_ERROR, "Failed to store project config")
                    }
                }
            }
        }

        // ─── DELETE /roots/{rootId}/config ───────────────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            delete {
                val rootId = call.parseRootId() ?: return@delete

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@delete
                }

                run {
                    // The service records project_config.deleted in the same unit as the delete.
                    val result =
                        when (val outcome = service.delete(rootId)) {
                            is Outcome.Ok -> outcome.value
                            is Outcome.Err ->
                                return@run run {
                                    projectConfigLogger.warn(
                                        "DELETE /roots/{}/config DB error: {}",
                                        rootId,
                                        LegacyFaults.message(outcome.error),
                                    )
                                    call.respondError(LegacyRestCode.DB_ERROR, "Failed to delete project config")
                                }
                        }
                    if (!result) {
                        call.respondError(LegacyRestCode.NOT_FOUND, "No project config found for root $rootId")
                        return@delete
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

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

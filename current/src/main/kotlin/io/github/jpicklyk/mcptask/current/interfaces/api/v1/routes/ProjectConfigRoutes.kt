package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigRollbackResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ConfigHistoryResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ConfigVersionSummaryDto
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
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

private val projectConfigLogger = LoggerFactory.getLogger("ProjectConfigRoutes")

/**
 * ETag of a stored config version: `"cfg-v<version>"` (quoted per RFC 7232). Distinct from
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor] (timestamp-derived, `"v1-<epochMillis>"`):
 * a config version number is the natural, monotonic validator of the versioned store.
 */
private fun configEtag(version: Int): String = "\"cfg-v$version\""

/** The pre-versioning ETag form `"cfg-<fingerprint>"`, still accepted in `If-Match` / `If-None-Match` (legacy shim). */
private fun legacyConfigEtag(fingerprint: String): String = "\"cfg-$fingerprint\""

/** The `"cfg-"` prefix shared by both ETag forms, factored out for header parsing. */
private const val CFG_ETAG_PREFIX = "\"cfg-"

/** The `v` that follows [CFG_ETAG_PREFIX] in a version ETag (`"cfg-v<digits>"`); never a hex fingerprint character. */
private const val CFG_VERSION_MARKER = "v"

/**
 * Stands in for an If-Match header value that parses as neither ETag form (including an empty string): passed on as
 * `expectedFingerprint` so it can never equal a real fingerprint, guaranteeing a mismatch (412) on an EXISTING head
 * while still being ignored when no head exists yet.
 */
private const val NON_MATCHING_FINGERPRINT_SENTINEL = "<malformed-if-match>"

/** The compare-and-set a parsed `If-Match` header asks for; both null = no precondition. */
private data class ConfigPrecondition(
    val expectedVersion: Int? = null,
    val expectedFingerprint: String? = null,
)

/** Parses `If-Match`: `"cfg-v<n>"` -> version, any other `"cfg-<x>"` -> legacy fingerprint, anything else -> sentinel. */
private fun parseIfMatch(ifMatch: String?): ConfigPrecondition {
    if (ifMatch == null) return ConfigPrecondition()
    if (ifMatch.startsWith(CFG_ETAG_PREFIX) && ifMatch.endsWith("\"")) {
        val inner = ifMatch.removePrefix(CFG_ETAG_PREFIX).removeSuffix("\"")
        if (inner.startsWith(CFG_VERSION_MARKER)) {
            val digits = inner.removePrefix(CFG_VERSION_MARKER)
            if (digits.isNotEmpty() && digits.all { it.isDigit() }) {
                digits.toIntOrNull()?.let { return ConfigPrecondition(expectedVersion = it) }
            }
        }
        return ConfigPrecondition(expectedFingerprint = inner)
    }
    return ConfigPrecondition(expectedFingerprint = NON_MATCHING_FINGERPRINT_SENTINEL)
}

/** Largest accepted `limit` of the history route; mirrors the store's cap. */
private const val MAX_HISTORY_LIMIT = 200

/** Cap of the tiny rollback JSON body. */
private const val MAX_ROLLBACK_BODY_BYTES = 4096

/**
 * Registers the per-root config routes under `/api/v1/roots/{rootId}/config`.
 *
 * Endpoints:
 * - `GET    /roots/{rootId}/config[?version=N][&fingerprint=F]` — read the head version ([ApiCapability.READ] +
 *   scope), or version N (a reset version reads as an empty document). 404 when the root has no config or its head
 *   is a reset. `If-None-Match` -> 304 when it equals `"cfg-v<n>"` or the legacy `"cfg-<fingerprint>"`. Optional
 *   `?fingerprint=<sha256>` adds a `relation` field ("current"/"superseded"/"unknown").
 * - `PUT    /roots/{rootId}/config` — validate + append the next version ([ApiCapability.WRITE_CONFIG] + scope);
 *   delegates the FULL pipeline to [ConfigService], the SAME service the MCP `manage_project_config` tool's `push`
 *   calls. Body must not exceed [ConfigService.MAX_CONFIG_YAML_BYTES] (413); malformed/unsafe YAML is rejected (422);
 *   a mismatched embedded `project.rootId` is 422 `rootid_mismatch` and known-old content is 409 `superseded`, both
 *   unless `?force=true`; optional `If-Match` (`"cfg-v<n>"` or the legacy `"cfg-<fingerprint>"`) is a compare-and-set
 *   that `force` never skips (412, ignored when no head exists yet). Byte-identical re-pushes append nothing.
 * - `DELETE /roots/{rootId}/config` — reset: appends an empty reset version, history is kept
 *   ([ApiCapability.WRITE_CONFIG] + scope); 404 when there is no config or it is already reset.
 * - `GET    /roots/{rootId}/config/history[?limit=&before=]` — versions newest first ([ApiCapability.READ] + scope).
 * - `POST   /roots/{rootId}/config/rollback` — `{"version": N}` appends a copy of version N as the new head
 *   ([ApiCapability.WRITE_CONFIG] + scope); optional `If-Match`.
 *
 * **Scope:** every verb enforces [enforceScopeForItem] against `{rootId}` itself.
 *
 * **REST-only:** registered on the authenticated `/api/v1` pipeline only and never reachable on the unauthenticated
 * `/mcp` transport.
 */
fun Route.projectConfigRoutes(
    repositoryProvider: RepositoryProvider,
    configService: ConfigService,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val service = configService

    route("/roots/{rootId}/config") {
        // ─── GET /roots/{rootId}/config ──────────────────────────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val rootId = call.parseRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@get
                }

                val versionParam = call.request.queryParameters["version"]
                val requestedVersion =
                    if (versionParam == null) {
                        null
                    } else {
                        versionParam.toIntOrNull()?.takeIf { it >= 1 } ?: run {
                            call.respondError(LegacyRestCode.BAD_REQUEST, "version must be an integer >= 1")
                            return@get
                        }
                    }

                run {
                    val result =
                        legacyRead({
                            return@run run {
                                projectConfigLogger.warn("GET /roots/{}/config DB error: {}", rootId, it)
                                call.respondError(LegacyRestCode.DB_ERROR, "Failed to read project config")
                            }
                        }) { service.get(rootId, requestedVersion) }
                    val config =
                        result ?: run {
                            val message =
                                if (requestedVersion == null) {
                                    "No project config found for root $rootId"
                                } else {
                                    "No config version $requestedVersion found for root $rootId"
                                }
                            call.respondError(LegacyRestCode.NOT_FOUND, message)
                            return@get
                        }

                    val etag = configEtag(config.version)
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim()
                    if (ifNoneMatch != null && (ifNoneMatch == etag || ifNoneMatch == legacyConfigEtag(config.fingerprint))) {
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
                            rootId = rootId.toString(),
                            fingerprint = config.fingerprint,
                            updatedAt = config.createdAt.toString(),
                            configYaml = config.yaml,
                            relation = relation,
                            version = config.version,
                            source = config.source.wire,
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
                // Bounded (bug e941c2c7): the cap is enforced before the body is buffered.
                val configYaml =
                    call.receiveBounded(ConfigService.MAX_CONFIG_YAML_BYTES) ?: return@put

                // The If-Match precondition is evaluated INSIDE service.push's write unit, atomic with the
                // fast-forward guard and the write itself; a store failure surfaces as RepositoryError -> 500
                // db_error (fail-closed), never as a silently skipped precondition.
                val precondition = parseIfMatch(call.request.headers[HttpHeaders.IfMatch]?.trim())

                when (
                    val result =
                        service.push(
                            rootId,
                            configYaml,
                            force,
                            expectedVersion = precondition.expectedVersion,
                            expectedFingerprint = precondition.expectedFingerprint,
                        )
                ) {
                    is ConfigPushResult.Success -> {
                        call.response.header(HttpHeaders.ETag, configEtag(result.version))
                        call.respond(
                            HttpStatusCode.OK,
                            ProjectConfigResponseDto(
                                rootId = result.rootItemId.toString(),
                                fingerprint = result.fingerprint,
                                updatedAt = result.updatedAt.toString(),
                                warning = result.warning,
                                schemaWarnings = result.schemaWarnings.ifEmpty { null },
                                version = result.version,
                                appended = result.appended,
                            ),
                        )
                    }
                    is ConfigPushResult.NotFound ->
                        call.respondError(LegacyRestCode.NOT_FOUND, "Root WorkItem not found: ${result.rootItemId}")
                    is ConfigPushResult.NotDepthZero ->
                        call.respondError(
                            LegacyRestCode.VALIDATION_ERROR_UNPROCESSABLE,
                            "rootId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}"
                        )
                    is ConfigPushResult.TooLarge ->
                        // Defensive — the bounded receive above already covers this in practice.
                        call.respondError(
                            LegacyRestCode.PAYLOAD_TOO_LARGE,
                            "configYaml is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte limit"
                        )
                    is ConfigPushResult.ParseError ->
                        call.respondError(
                            LegacyRestCode.PARSE_ERROR,
                            "configYaml failed to parse: ${result.detail}",
                            details = result.violations.takeIf { it.isNotEmpty() }?.let { violationsDetails(it, emptyList()) },
                        )
                    is ConfigPushResult.Invalid ->
                        call.respondError(
                            LegacyRestCode.PARSE_ERROR,
                            "configYaml failed validation with ${result.errors.size} error(s); nothing was stored",
                            details = violationsDetails(result.errors, result.warnings),
                        )
                    is ConfigPushResult.RootIdMismatch ->
                        call.respondError(
                            LegacyRestCode.ROOTID_MISMATCH,
                            "configYaml embeds project.rootId '${result.embeddedRootId}', which differs " +
                                "from the target rootId '${result.targetRootId}'; fix project.rootId in " +
                                "the document or retry with ?force=true"
                        )
                    is ConfigPushResult.Superseded ->
                        call.respondError(
                            LegacyRestCode.SUPERSEDED,
                            "local config is older than the server's (updated ${result.currentUpdatedAt}); " +
                                "pull or copy back before editing, or retry with ?force=true"
                        )
                    is ConfigPushResult.PreconditionFailed -> {
                        val currentEtag = configEtag(result.currentVersion)
                        call.response.header(HttpHeaders.ETag, currentEtag)
                        call.respondError(LegacyRestCode.ETAG_MISMATCH, "ETag mismatch; current ETag is $currentEtag")
                    }
                    is ConfigPushResult.RepositoryError -> {
                        projectConfigLogger.warn("PUT /roots/{}/config DB error: {}", rootId, result.message)
                        call.respondError(LegacyRestCode.DB_ERROR, "Failed to store project config")
                    }
                }
            }
        }

        // ─── DELETE /roots/{rootId}/config (reset) ───────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            delete {
                val rootId = call.parseRootId() ?: return@delete

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@delete
                }

                run {
                    // The service records config.version_created in the same unit as the reset version.
                    val result =
                        when (val outcome = service.reset(rootId)) {
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

        // ─── GET /roots/{rootId}/config/history ──────────────────────────────
        requireCapability(ApiCapability.READ) {
            get("/history") {
                val rootId = call.parseRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@get
                }

                val limitParam = call.request.queryParameters["limit"]
                val limit =
                    if (limitParam == null) {
                        ConfigService.DEFAULT_HISTORY_LIMIT
                    } else {
                        limitParam.toIntOrNull()?.takeIf { it in 1..MAX_HISTORY_LIMIT } ?: run {
                            call.respondError(LegacyRestCode.VALIDATION_ERROR, "limit must be an integer between 1 and $MAX_HISTORY_LIMIT")
                            return@get
                        }
                    }
                val beforeParam = call.request.queryParameters["before"]
                val before =
                    if (beforeParam == null) {
                        null
                    } else {
                        beforeParam.toIntOrNull() ?: run {
                            call.respondError(LegacyRestCode.BAD_REQUEST, "before must be an integer")
                            return@get
                        }
                    }

                run {
                    val versions =
                        legacyRead({
                            return@run run {
                                projectConfigLogger.warn("GET /roots/{}/config/history DB error: {}", rootId, it)
                                call.respondError(LegacyRestCode.DB_ERROR, "Failed to read project config history")
                            }
                        }) { service.history(rootId, limit, before) }
                    val headVersion =
                        legacyRead({
                            return@run run {
                                projectConfigLogger.warn("GET /roots/{}/config/history DB error: {}", rootId, it)
                                call.respondError(LegacyRestCode.DB_ERROR, "Failed to read project config history")
                            }
                        }) { service.headVersion(rootId) }
                    call.respond(
                        HttpStatusCode.OK,
                        ConfigHistoryResponseDto(
                            rootId = rootId.toString(),
                            headVersion = headVersion,
                            versions = versions.map { it.toSummaryDto() },
                        ),
                    )
                }
            }
        }

        // ─── POST /roots/{rootId}/config/rollback ────────────────────────────
        requireCapability(ApiCapability.WRITE_CONFIG) {
            post("/rollback") {
                val rootId = call.parseRootId() ?: return@post

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for root $rootId")
                    return@post
                }

                val body = call.receiveBounded(MAX_ROLLBACK_BODY_BYTES) ?: return@post
                val toVersion =
                    runCatchingNonCancellation {
                        ((Json.parseToJsonElement(body) as? JsonObject)?.get("version") as? JsonPrimitive)
                            ?.takeIf { !it.isString }
                            ?.intOrNull
                    }.getOrNull()?.takeIf { it >= 1 } ?: run {
                        call.respondError(LegacyRestCode.BAD_REQUEST, "Body must be a JSON object with an integer \"version\" >= 1")
                        return@post
                    }
                val precondition = parseIfMatch(call.request.headers[HttpHeaders.IfMatch]?.trim())

                when (
                    val result =
                        service.rollback(
                            rootId,
                            toVersion,
                            expectedVersion = precondition.expectedVersion,
                            expectedFingerprint = precondition.expectedFingerprint,
                        )
                ) {
                    is ConfigRollbackResult.Success -> {
                        call.response.header(HttpHeaders.ETag, configEtag(result.version.version))
                        call.respond(
                            HttpStatusCode.OK,
                            ProjectConfigResponseDto(
                                rootId = rootId.toString(),
                                fingerprint = result.version.fingerprint,
                                updatedAt = result.version.createdAt.toString(),
                                version = result.version.version,
                                source = result.version.source.wire,
                                appended = result.appended,
                            ),
                        )
                    }
                    is ConfigRollbackResult.NotFound ->
                        call.respondError(
                            LegacyRestCode.NOT_FOUND,
                            "No config version ${result.toVersion} found for root ${result.rootItemId}"
                        )
                    is ConfigRollbackResult.PreconditionFailed -> {
                        val currentEtag = configEtag(result.currentVersion)
                        call.response.header(HttpHeaders.ETag, currentEtag)
                        call.respondError(LegacyRestCode.ETAG_MISMATCH, "ETag mismatch; current ETag is $currentEtag")
                    }
                    is ConfigRollbackResult.RepositoryError -> {
                        projectConfigLogger.warn("POST /roots/{}/config/rollback DB error: {}", rootId, result.message)
                        call.respondError(LegacyRestCode.DB_ERROR, "Failed to roll back project config")
                    }
                }
            }
        }
    }
}

private fun ConfigVersion.toSummaryDto(): ConfigVersionSummaryDto =
    ConfigVersionSummaryDto(
        version = version,
        fingerprint = fingerprint,
        source = source.wire,
        createdAt = createdAt.toString(),
        principalId = principalId,
        warningsCount = warnings.size,
        canonical = canonicalJson != null,
    )

/** The `details` of a validation rejection: `{errors: [{path, reason}], warnings?: [...]}`, paths are RFC 6901 pointers. */
private fun violationsDetails(
    errors: List<ConfigViolation>,
    warnings: List<ConfigViolation>
): JsonObject =
    buildJsonObject {
        put("errors", JsonArray(errors.map(::violationJson)))
        if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map(::violationJson)))
    }

private fun violationJson(violation: ConfigViolation): JsonObject =
    buildJsonObject {
        put("path", JsonPrimitive(violation.path))
        put("reason", JsonPrimitive(violation.reason))
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

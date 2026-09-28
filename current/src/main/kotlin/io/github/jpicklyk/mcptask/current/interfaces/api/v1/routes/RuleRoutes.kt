package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.RuleGetResult
import io.github.jpicklyk.mcptask.current.application.service.RuleListResult
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RuleListResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RuleResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RuleSummaryDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

private val ruleRoutesLogger = LoggerFactory.getLogger("RuleRoutes")

/**
 * Registers the per-root rule-text READ routes under `/api/v1/roots/{rootId}/rules` -- the REST
 * counterpart of the MCP `query_rules` tool's direct (`rootId`+`key`) lookup. Both surfaces
 * converge on the same [RuleService], itself a read-only view over the `rule/<key>` plan documents
 * `manage_plan_documents`/`PlanDocumentService` write.
 *
 * Endpoints:
 * - `GET /roots/{rootId}/rules/{key}` -- one rule's body + `rulesVersion`
 *   ([ApiCapability.READ] + scope). 400 on a malformed `rootId` or `key`; 404 `not_found` for an
 *   unknown root; 422 `validation_error` when `rootId` is not depth-0; 404 `rule_not_found` when no
 *   `rule/<key>` document exists at that root.
 * - `GET /roots/{rootId}/rules` -- `{key, rulesVersion, updatedAt}` for every rule under the root,
 *   sorted by key, never the body ([ApiCapability.READ] + scope); same root-validation errors as
 *   above minus the per-key 404.
 *
 * **Validation/authorization order** (mirrors [EffectiveConfigRoutes.kt]'s documented order):
 * parse `rootId` (400) -> parse/validate `key` grammar, `{key}` route only (400) ->
 * [enforceScopeForItem] (403) -> root exists ([RuleGetResult.RootNotFound]/[RuleListResult.RootNotFound], 404) ->
 * root is depth-0 ([RuleGetResult.NotDepthZero]/[RuleListResult.NotDepthZero], 422) -> document
 * exists, `{key}` route only ([RuleGetResult.RuleNotFound], 404) -> 200.
 *
 * **REST-only:** registered on the authenticated `/api/v1` pipeline only, never reachable on the
 * unauthenticated `/mcp` transport -- same as [planDocumentRoutes].
 *
 * No item-mode (skill-pointer) lookup on REST: that mode resolves an item's effective config,
 * which REST routes never read (see the A3 task-scope-addendum's corrections).
 */
fun Route.ruleRoutes(repositoryProvider: RepositoryProvider) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val service = RuleService(repositoryProvider.planDocumentRepository(), workItemRepo)

    route("/roots/{rootId}/rules") {
        // ─── GET /roots/{rootId}/rules (list, metadata only) ──────────────────
        requireCapability(ApiCapability.READ) {
            get {
                val rootId = call.parseRuleRootId() ?: return@get

                if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                    return@get
                }

                when (val result = service.list(rootId)) {
                    is RuleListResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            RuleListResponseDto(
                                rootId = rootId.toString(),
                                rules =
                                    result.rules.map { rule ->
                                        RuleSummaryDto(
                                            key = rule.key,
                                            rulesVersion = rule.rulesVersion,
                                            updatedAt = rule.updatedAt.toString(),
                                        )
                                    },
                            ),
                        )
                    }
                    is RuleListResult.RootNotFound ->
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorDto("not_found", "Root WorkItem not found: ${result.rootId}"),
                        )
                    is RuleListResult.NotDepthZero ->
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto(
                                "validation_error",
                                "rootId must reference a depth-0 (root) WorkItem; '${result.rootId}' has depth ${result.depth}",
                            ),
                        )
                    is RuleListResult.RepositoryError -> {
                        ruleRoutesLogger.warn("GET /roots/{}/rules DB error: {}", rootId, result.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to list rules"))
                    }
                }
            }
        }

        route("/{key}") {
            // ─── GET /roots/{rootId}/rules/{key} ───────────────────────────────
            requireCapability(ApiCapability.READ) {
                get {
                    val rootId = call.parseRuleRootId() ?: return@get
                    val key = call.parseRuleKey() ?: return@get

                    if (!enforceScopeForItem(call, rootId, workItemRepo)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for root $rootId"))
                        return@get
                    }

                    when (val result = service.get(rootId, key)) {
                        is RuleGetResult.Success -> {
                            call.respond(
                                HttpStatusCode.OK,
                                RuleResponseDto(
                                    rootId = rootId.toString(),
                                    key = key,
                                    rulesVersion = result.document.contentHash,
                                    body = result.document.body,
                                ),
                            )
                        }
                        is RuleGetResult.RootNotFound ->
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorDto("not_found", "Root WorkItem not found: ${result.rootId}"),
                            )
                        is RuleGetResult.NotDepthZero ->
                            call.respond(
                                HttpStatusCode.UnprocessableEntity,
                                ErrorDto(
                                    "validation_error",
                                    "rootId must reference a depth-0 (root) WorkItem; '${result.rootId}' has depth ${result.depth}",
                                ),
                            )
                        is RuleGetResult.RuleNotFound ->
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorDto("rule_not_found", "No rule '${result.key}' for root ${result.rootId}"),
                            )
                        is RuleGetResult.RepositoryError -> {
                            ruleRoutesLogger.warn("GET /roots/{}/rules/{} DB error: {}", rootId, key, result.message)
                            call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to read rule"))
                        }
                    }
                }
            }
        }
    }
}

/** Parses the `{rootId}` path parameter as a UUID; responds 400 and returns null on failure. */
private suspend fun ApplicationCall.parseRuleRootId(): UUID? {
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

/**
 * Parses the `{key}` path parameter; responds 400 and returns null when missing or failing
 * [RuleService.KEY_PATTERN] (lowercase alphanumeric/./_/-, starting alphanumeric, max 100 chars).
 */
private suspend fun ApplicationCall.parseRuleKey(): String? {
    val key = parameters["key"]
    if (key == null || !RuleService.KEY_PATTERN.matches(key)) {
        respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid rule key: ${key ?: "<missing>"}"))
        return null
    }
    return key
}

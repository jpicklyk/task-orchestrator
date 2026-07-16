package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * MCP tool for pushing and reading back per-root config YAML — the transport-agnostic sync path
 * for the layered schema resolver (see [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveSchema]).
 *
 * A client pushes its repo's `.taskorchestrator/config.yaml` text keyed by a project root's
 * WorkItem UUID; [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]
 * picks up the change on the very next schema-resolving read (hot-reload is a property of that
 * service's read path — this tool does not need to invalidate anything itself).
 *
 * Supports two operations:
 * - **push**: validates the target root (must exist, must be depth 0), parse-validates the YAML
 *   BEFORE storing it, then upserts via [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository].
 * - **get**: reads back the stored config + fingerprint for a root, or a not-found error.
 */
class ManageProjectConfigTool : BaseToolDefinition() {
    override val name = "manage_project_config"

    override val description =
        """
Push or read back per-root config YAML for the layered schema resolver — a client-pushed
`.taskorchestrator/config.yaml` document keyed by a project root's WorkItem UUID, which the
resolver layers over the global config on every schema-resolving read (no separate reload step).

**push** — validates, in order: `configYaml` must not exceed 128 KiB (rejected before any parse
attempt); the root item must exist; it must be depth 0 (configs anchor to project roots only, error
otherwise); if the root's `type` is not "project", the response includes a non-fatal `warning` field
(a naming convention, not an enforced constraint) but the push still succeeds. `configYaml` is then
parse-validated BEFORE storing, using a safe YAML constructor that only builds plain maps/lists/
scalars (arbitrary `!!`-tagged Java type construction is rejected, not silently ignored) —
unparseable or unsafe YAML is rejected with the parse error and never written, so a broken or
malicious config can never silently exist server-side. A `configYaml` whose fingerprint is
known-old (present in the root's fingerprint history but not current) is rejected as a
`CONFLICT_ERROR` naming force as the override, unless `force: true` is passed — this guards against
silently reverting a later push made from elsewhere. Pushing byte-identical content is naturally
idempotent: the returned `fingerprint` is unchanged, so callers can `get` first and skip the push
when fingerprints match. A pushed document may set `note_limits`/`status_labels` to override the
global config for this root's items alongside `work_item_schemas`/`note_schemas`/`traits`/`project`;
any other top-level key (e.g. `actor_authentication`, which stays global-only) is reported back in
an additive `ignoredSections` array, present only when non-empty.

**get** — returns the stored `configYaml` + `fingerprint` + `updatedAt` for a root, or a
not-found error when no config has been pushed for it. When `fingerprint` is supplied, the response
also includes a `relation` field ("current"/"superseded"/"unknown") classifying that value against
the root's stored fingerprint history.
        """.trimIndent()

    override val category = ToolCategory.SYSTEM

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Operation: push, get"))
                            put("enum", JsonArray(listOf("push", "get").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "rootItemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Project root WorkItem UUID or hex prefix (4+ chars) — must be a depth-0 item for push"
                                )
                            )
                        }
                    )
                    put(
                        "configYaml",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Raw config.yaml text to store for this root (push only); max 128 KiB, " +
                                        "parse-validated before storing"
                                )
                            )
                        }
                    )
                    put(
                        "force",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "push only, default false: bypass push guards — skips the embedded " +
                                        "project.rootId mismatch check and the known-old (fast-forward) " +
                                        "fingerprint guard"
                                )
                            )
                        }
                    )
                    put(
                        "fingerprint",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "get only: SHA-256 hex digest to classify against the stored config's " +
                                        "current fingerprint and history; adds a relation field to the response"
                                )
                            )
                        }
                    )
                },
            required = listOf("operation", "rootItemId")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "push" -> {
                validateIdOrPrefix(params, "rootItemId", required = true)
                requireString(params, "configYaml")
            }
            "get" -> validateIdOrPrefix(params, "rootItemId", required = true)
            else -> throw ToolValidationException("Invalid operation: $operation. Must be push or get")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement =
        when (val operation = requireString(params, "operation")) {
            "push" -> executePush(params, context)
            "get" -> executeGet(params, context)
            else -> errorResponse("Invalid operation: $operation. Must be push or get", ErrorCodes.VALIDATION_ERROR)
        }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        val op = (params as? JsonObject)?.get("operation")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        if (isError) return "manage_project_config($op) failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val rootItemId = data?.get("rootItemId")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        return when (op) {
            "push" -> "Pushed project config for root $rootItemId"
            "get" -> "Retrieved project config for root $rootItemId"
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // push
    // ──────────────────────────────────────────────

    private suspend fun executePush(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootItemId, idError) = resolveItemId(params, "rootItemId", context)
        if (idError != null) return idError
        val configYaml = requireString(params, "configYaml")
        val force = optionalBoolean(params, "force")

        val service = ProjectConfigPushService(context.repositoryProvider)
        return when (val result = service.push(rootItemId!!, configYaml, force)) {
            is ProjectConfigPushResult.Success ->
                successResponse(
                    buildJsonObject {
                        put("rootItemId", JsonPrimitive(result.rootItemId.toString()))
                        put("fingerprint", JsonPrimitive(result.fingerprint))
                        put("updatedAt", JsonPrimitive(result.updatedAt.toString()))
                        result.warning?.let { put("warning", JsonPrimitive(it)) }
                        if (result.ignoredSections.isNotEmpty()) {
                            put("ignoredSections", JsonArray(result.ignoredSections.map { JsonPrimitive(it) }))
                        }
                    }
                )
            is ProjectConfigPushResult.NotFound ->
                errorResponse(
                    "Root WorkItem not found: ${result.rootItemId}",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            is ProjectConfigPushResult.NotDepthZero ->
                errorResponse(
                    "rootItemId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}",
                    ErrorCodes.VALIDATION_ERROR
                )
            is ProjectConfigPushResult.TooLarge ->
                errorResponse(
                    "configYaml is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte " +
                        "(${result.maxBytes / 1024} KiB) limit",
                    ErrorCodes.VALIDATION_ERROR
                )
            is ProjectConfigPushResult.ParseError ->
                errorResponse(
                    "configYaml failed to parse: ${result.detail}",
                    ErrorCodes.VALIDATION_ERROR,
                    details = result.detail
                )
            is ProjectConfigPushResult.RootIdMismatch ->
                errorResponse(
                    "configYaml embeds project.rootId '${result.embeddedRootId}', which differs from " +
                        "the target rootItemId '${result.targetRootId}'; fix project.rootId in the " +
                        "document or pass force: true to push anyway",
                    ErrorCodes.VALIDATION_ERROR
                )
            is ProjectConfigPushResult.Superseded ->
                errorResponse(
                    "local config is older than the server's (updated ${result.currentUpdatedAt}); " +
                        "pull or copy back before editing, or pass force: true to overwrite anyway",
                    ErrorCodes.CONFLICT_ERROR
                )
            is ProjectConfigPushResult.RepositoryError ->
                errorResponse(
                    "Failed to store project config: ${result.message}",
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    // ──────────────────────────────────────────────
    // get
    // ──────────────────────────────────────────────

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootItemId, idError) = resolveItemId(params, "rootItemId", context)
        if (idError != null) return idError
        val fingerprint = optionalString(params, "fingerprint")

        val service = ProjectConfigPushService(context.repositoryProvider)
        return when (val result = service.get(rootItemId!!)) {
            is Result.Success -> {
                val config =
                    result.data ?: return errorResponse(
                        "No project config found for root: $rootItemId",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
                val relation =
                    fingerprint?.let {
                        when (
                            val relationResult =
                                context.repositoryProvider.projectConfigRepository().classifyFingerprint(rootItemId, it)
                        ) {
                            is Result.Success -> relationResult.data.name.lowercase()
                            is Result.Error -> null
                        }
                    }
                successResponse(
                    buildJsonObject {
                        put("rootItemId", JsonPrimitive(config.rootItemId.toString()))
                        put("configYaml", JsonPrimitive(config.configYaml))
                        put("fingerprint", JsonPrimitive(config.fingerprint))
                        put("updatedAt", JsonPrimitive(config.updatedAt.toString()))
                        relation?.let { put("relation", JsonPrimitive(it)) }
                    }
                )
            }
            is Result.Error ->
                errorResponse(
                    "Failed to read project config: ${result.error.message}",
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    companion object {
        /**
         * `configYaml` size limit for `push`, in bytes — delegates to
         * [ProjectConfigPushService.MAX_CONFIG_YAML_BYTES], the single source of truth now that the
         * validate+persist pipeline lives in the service. Kept as a public alias here for backward
         * compatibility (existing callers/tests reference `ManageProjectConfigTool.MAX_CONFIG_YAML_BYTES`).
         */
        const val MAX_CONFIG_YAML_BYTES = ProjectConfigPushService.MAX_CONFIG_YAML_BYTES
    }
}

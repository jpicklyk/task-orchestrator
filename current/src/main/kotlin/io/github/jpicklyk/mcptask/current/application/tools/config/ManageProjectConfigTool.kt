package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

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
malicious config can never silently exist server-side. Pushing byte-identical content is naturally
idempotent: the returned `fingerprint` is unchanged, so callers can `get` first and skip the push
when fingerprints match.

**get** — returns the stored `configYaml` + `fingerprint` + `updatedAt` for a root, or a
not-found error when no config has been pushed for it.
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

        val sizeBytes = configYaml.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_CONFIG_YAML_BYTES) {
            return errorResponse(
                "configYaml is $sizeBytes bytes, exceeds the $MAX_CONFIG_YAML_BYTES byte " +
                    "($MAX_CONFIG_YAML_KIB KiB) limit",
                ErrorCodes.VALIDATION_ERROR
            )
        }

        val itemResult = context.workItemRepository().getById(rootItemId!!)
        val item =
            when (itemResult) {
                is Result.Success -> itemResult.data
                is Result.Error -> return errorResponse(
                    "Root WorkItem not found: $rootItemId",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            }

        if (item.depth != 0) {
            return errorResponse(
                "rootItemId must reference a depth-0 (root) WorkItem; '$rootItemId' has depth ${item.depth}",
                ErrorCodes.VALIDATION_ERROR
            )
        }

        val typeWarning =
            if (item.type != "project") {
                "Root item type is '${item.type ?: "null"}', not 'project' — config pushed anyway " +
                    "(a naming convention, not an enforced constraint)"
            } else {
                null
            }

        val parseError = validateYaml(configYaml)
        if (parseError != null) {
            return errorResponse(
                "configYaml failed to parse: $parseError",
                ErrorCodes.VALIDATION_ERROR,
                details = parseError
            )
        }

        return when (val result = context.projectConfigRepository().upsert(rootItemId, configYaml)) {
            is Result.Success -> {
                val config = result.data
                successResponse(
                    buildJsonObject {
                        put("rootItemId", JsonPrimitive(config.rootItemId.toString()))
                        put("fingerprint", JsonPrimitive(config.fingerprint))
                        put("updatedAt", JsonPrimitive(config.updatedAt.toString()))
                        typeWarning?.let { put("warning", JsonPrimitive(it)) }
                    }
                )
            }
            is Result.Error ->
                errorResponse(
                    "Failed to store project config: ${result.error.message}",
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

        return when (val result = context.projectConfigRepository().get(rootItemId!!)) {
            is Result.Success -> {
                val config =
                    result.data ?: return errorResponse(
                        "No project config found for root: $rootItemId",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
                successResponse(
                    buildJsonObject {
                        put("rootItemId", JsonPrimitive(config.rootItemId.toString()))
                        put("configYaml", JsonPrimitive(config.configYaml))
                        put("fingerprint", JsonPrimitive(config.fingerprint))
                        put("updatedAt", JsonPrimitive(config.updatedAt.toString()))
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

    // ──────────────────────────────────────────────
    // YAML parse-validation
    // ──────────────────────────────────────────────

    /**
     * Parses [configYaml] the same way [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]
     * will on every subsequent read (via the shared [YamlSchemaParser]), but BEFORE storing — a
     * stored-but-unparseable config would otherwise silently fall through to the global schema on
     * every future read, a confusing failure mode discovered only much later. Returns the parse
     * failure message, or null when [configYaml] parses cleanly.
     *
     * Uses [SafeConstructor] rather than SnakeYAML's default `Constructor` — `configYaml` is
     * attacker-reachable input (pushed over the MCP protocol by a caller, not read from a trusted
     * local file), and the default `Constructor` will happily instantiate an arbitrary Java type
     * named by a `!!`-tag (the SnakeYAML deserialization-RCE gadget class, CWE-502). `SafeConstructor`
     * only ever builds plain maps/lists/scalars, which is all a config document legitimately needs;
     * any `!!`-tagged custom type throws [org.yaml.snakeyaml.constructor.ConstructorException] before
     * anything is instantiated, and that exception is caught below like any other parse failure.
     *
     * Soft validation warnings collected by [YamlSchemaParser.parseRoot] (e.g. a note entry
     * missing `key`, an invalid `lifecycle` value) are NOT treated as rejection here — only a hard
     * YAML syntax/shape failure (invalid syntax, or a non-map document) is. Those soft warnings
     * mirror the global config loader's existing behavior: skip the offending entry, keep going.
     */
    private fun validateYaml(configYaml: String): String? =
        try {
            @Suppress("UNCHECKED_CAST")
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(configYaml)
            if (root != null) {
                YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
            }
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            e.message ?: e.javaClass.simpleName
        }

    companion object {
        /** `configYaml` size limit for `push`, in KiB — see [MAX_CONFIG_YAML_BYTES]. */
        private const val MAX_CONFIG_YAML_KIB = 128

        /**
         * Hard cap on a pushed `configYaml` document's UTF-8 byte size (CWE-770: uncontrolled
         * resource consumption). Enforced before any parse attempt, well above any legitimate
         * config document, and small enough to bound parse cost against a hostile payload.
         */
        const val MAX_CONFIG_YAML_BYTES = MAX_CONFIG_YAML_KIB * 1024
    }
}

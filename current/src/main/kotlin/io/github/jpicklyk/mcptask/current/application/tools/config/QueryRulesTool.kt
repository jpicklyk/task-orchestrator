package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.service.RuleGetResult
import io.github.jpicklyk.mcptask.current.application.service.RuleListResult
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for reading git-tracked rule text -- client-neutral operating rules (blind-authorship
 * protocol, commit discipline, forbidden test patterns, review scoping, and so on) stashed as
 * `rule/<key>` plan documents via `manage_plan_documents` and served here READ-ONLY.
 *
 * Never inlines rule text into `query_items`'s `schema` operation -- that surface stays
 * pointer-only (a note schema entry's `skill` field names a rule key; the caller resolves the
 * actual text through this tool, either directly (`rootId`+`key`) or via the skill pointer
 * (`itemId`+`noteKey`)).
 *
 * Supports two operations:
 * - **get**: reads back one rule's body and `rulesVersion` (the plan document's `contentHash`).
 *   Exactly one of two mutually exclusive parameter pairs selects the lookup: `rootId`+`key`
 *   (direct) or `itemId`+`noteKey` (skill-pointer -- resolves the item's EFFECTIVE, trait-merged
 *   schema, looks up `noteKey`'s entry, and follows its `skill` field as the rule key under the
 *   item's own root).
 * - **list**: returns `{key, rulesVersion, updatedAt}` for every valid rule key under `rootId`,
 *   sorted by key, never the body. Accepts only `rootId` -- `key`, `itemId`, and `noteKey` are
 *   rejected.
 */
class QueryRulesTool : BaseToolDefinition() {
    override val name = "query_rules"

    override val category = ToolCategory.SYSTEM

    override val description =
        """
Read git-tracked rule text stashed as `rule/<key>` plan documents.

**get** -- returns one rule's body + rulesVersion. Requires exactly one of: `rootId`+`key`
(direct lookup), or `itemId`+`noteKey` (resolves the item's effective schema's note entry and
follows its skill pointer to a rule key under the item's own root). Providing both pairs, or
neither, fails.

**list** -- returns `{key, rulesVersion, updatedAt}` for every rule under `rootId`, sorted by
key, never the body. Takes only `rootId` -- `key`/`itemId`/`noteKey` are rejected.
        """.trimIndent()

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = true,
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
                            put("description", JsonPrimitive("Operation: get, list"))
                            put("enum", JsonArray(listOf("get", "list").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "rootId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Project root WorkItem UUID or hex prefix (4+ chars) -- direct get, or list (required for list)"
                                )
                            )
                        }
                    )
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Rule key (direct get only, with rootId): lowercase alphanumeric/./_/-, " +
                                        "starting alphanumeric, max 100 chars"
                                )
                            )
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "WorkItem UUID or hex prefix (4+ chars) whose effective schema resolves the " +
                                        "skill pointer (skill-pointer get only, with noteKey)"
                                )
                            )
                        }
                    )
                    put(
                        "noteKey",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Note key to resolve via itemId's effective schema; its entry's skill field " +
                                        "names the rule key (skill-pointer get only, with itemId)"
                                )
                            )
                        }
                    )
                },
            required = listOf("operation")
        )

    override fun validateParams(params: JsonElement) {
        when (val operation = requireString(params, "operation")) {
            "get" -> validateGetParams(params)
            "list" -> validateListParams(params)
            else -> throw ToolValidationException("Invalid operation: $operation. Must be get or list")
        }
    }

    private fun validateGetParams(params: JsonElement) {
        val rootId = optionalString(params, "rootId")
        val key = optionalString(params, "key")
        val itemId = optionalString(params, "itemId")
        val noteKey = optionalString(params, "noteKey")

        val rootPairPresent = rootId != null || key != null
        val itemPairPresent = itemId != null || noteKey != null

        if (rootPairPresent && itemPairPresent) {
            throw ToolValidationException(
                "get requires exactly one of (rootId+key) or (itemId+noteKey), not both"
            )
        }
        if (!rootPairPresent && !itemPairPresent) {
            throw ToolValidationException(
                "get requires exactly one of (rootId+key) or (itemId+noteKey)"
            )
        }

        if (rootPairPresent) {
            if (rootId == null || key == null) {
                throw ToolValidationException("get with rootId also requires key (and vice versa)")
            }
            validateIdOrPrefix(params, "rootId", required = true)
            validateKeyGrammar(key)
        } else {
            if (itemId == null || noteKey == null) {
                throw ToolValidationException("get with itemId also requires noteKey (and vice versa)")
            }
            validateIdOrPrefix(params, "itemId", required = true)
        }
    }

    private fun validateListParams(params: JsonElement) {
        validateIdOrPrefix(params, "rootId", required = true)
        if (optionalString(params, "key") != null) {
            throw ToolValidationException("list does not accept 'key' -- use get for a single rule")
        }
        if (optionalString(params, "itemId") != null) {
            throw ToolValidationException("list does not accept 'itemId'")
        }
        if (optionalString(params, "noteKey") != null) {
            throw ToolValidationException("list does not accept 'noteKey'")
        }
    }

    private fun validateKeyGrammar(key: String) {
        if (!RuleService.KEY_PATTERN.matches(key)) {
            throw ToolValidationException(
                "key must be lowercase alphanumeric/./_/-, start with an alphanumeric, max 100 chars, got: $key"
            )
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement =
        when (val operation = requireString(params, "operation")) {
            "get" -> executeGet(params, context)
            "list" -> executeList(params, context)
            else -> errorResponse("Invalid operation: $operation. Must be get or list", ErrorCodes.VALIDATION_ERROR)
        }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        val op = (params as? JsonObject)?.get("operation")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        if (isError) return "query_rules($op) failed"
        return when (op) {
            "get" -> "Retrieved rule"
            "list" -> "Listed rules"
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // get
    // ──────────────────────────────────────────────

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val service = ruleService(context)
        val rootIdParam = optionalString(params, "rootId")
        return if (rootIdParam != null) {
            val (rootId, idError) = resolveItemId(params, "rootId", context)
            if (idError != null) return idError
            val key = requireString(params, "key")
            renderGetResult(service.get(rootId!!, key), rootId, key, resolvedFrom = null)
        } else {
            val (itemId, idError) = resolveItemId(params, "itemId", context)
            if (idError != null) return idError
            val noteKey = requireString(params, "noteKey")
            executeSkillPointerGet(itemId!!, noteKey, service, context)
        }
    }

    /**
     * Resolves `itemId`'s EFFECTIVE (trait-merged) schema via [ToolExecutionContext.resolveSchema]
     * -- a [io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException] from
     * that call is intentionally left to propagate to
     * [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter], which maps it to the
     * transient `config_unavailable` structured error -- then follows `noteKey`'s entry's `skill`
     * field as a rule key under the item's own root.
     */
    private suspend fun executeSkillPointerGet(
        itemId: UUID,
        noteKey: String,
        service: RuleService,
        context: ToolExecutionContext
    ): JsonElement {
        val item: WorkItem =
            when (val itemResult = context.workItemRepository().getById(itemId)) {
                is Result.Success -> itemResult.data
                is Result.Error -> return errorResponse("WorkItem not found: $itemId", ErrorCodes.RESOURCE_NOT_FOUND)
            }
        val rootId =
            item.rootId ?: return errorResponse(
                "Item $itemId has no rootId; cannot resolve a rule root",
                ErrorCodes.VALIDATION_ERROR
            )

        val schema =
            context.resolveSchema(item) ?: return errorResponse(
                "No schema resolved for item $itemId; cannot resolve noteKey '$noteKey'",
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        val entry = schema.notes.firstOrNull { it.key == noteKey }
        val skill = entry?.skill
        if (entry == null || skill == null) {
            return errorResponse(
                "Note key '$noteKey' not found in item $itemId's effective schema, or has no skill pointer",
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        }
        if (!RuleService.KEY_PATTERN.matches(skill)) {
            return errorResponse(
                "Resolved skill '$skill' is not a valid rule key",
                ErrorCodes.VALIDATION_ERROR
            )
        }

        return renderGetResult(
            service.get(rootId, skill),
            rootId,
            skill,
            resolvedFrom = ResolvedFrom(itemId, noteKey, skill)
        )
    }

    private data class ResolvedFrom(
        val itemId: UUID,
        val noteKey: String,
        val skill: String
    )

    private fun renderGetResult(
        result: RuleGetResult,
        rootId: UUID,
        key: String,
        resolvedFrom: ResolvedFrom?
    ): JsonElement =
        when (result) {
            is RuleGetResult.Success ->
                successResponse(
                    buildJsonObject {
                        put("rootId", JsonPrimitive(rootId.toString()))
                        put("key", JsonPrimitive(key))
                        put("slug", JsonPrimitive(result.document.slug))
                        put("rulesVersion", JsonPrimitive(result.document.contentHash))
                        put("body", JsonPrimitive(result.document.body))
                        if (resolvedFrom != null) {
                            put(
                                "resolvedFrom",
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(resolvedFrom.itemId.toString()))
                                    put("noteKey", JsonPrimitive(resolvedFrom.noteKey))
                                    put("skill", JsonPrimitive(resolvedFrom.skill))
                                }
                            )
                        }
                    }
                )
            is RuleGetResult.RootNotFound ->
                errorResponse("Root WorkItem not found: ${result.rootId}", ErrorCodes.RESOURCE_NOT_FOUND)
            is RuleGetResult.NotDepthZero ->
                errorResponse(
                    "rootId must reference a depth-0 (root) WorkItem; '${result.rootId}' has depth ${result.depth}",
                    ErrorCodes.VALIDATION_ERROR
                )
            is RuleGetResult.RuleNotFound ->
                errorResponse(
                    "No rule '${result.key}' for root ${result.rootId}",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            is RuleGetResult.RepositoryError ->
                errorResponse("Failed to read rule: ${result.message}", ErrorCodes.DATABASE_ERROR)
        }

    // ──────────────────────────────────────────────
    // list
    // ──────────────────────────────────────────────

    private suspend fun executeList(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootId, idError) = resolveItemId(params, "rootId", context)
        if (idError != null) return idError
        val service = ruleService(context)

        return when (val result = service.list(rootId!!)) {
            is RuleListResult.Success ->
                successResponse(
                    buildJsonObject {
                        put("rootId", JsonPrimitive(rootId.toString()))
                        put(
                            "rules",
                            JsonArray(
                                result.rules.map { rule ->
                                    buildJsonObject {
                                        put("key", JsonPrimitive(rule.key))
                                        put("rulesVersion", JsonPrimitive(rule.rulesVersion))
                                        put("updatedAt", JsonPrimitive(rule.updatedAt.toString()))
                                    }
                                }
                            )
                        )
                    }
                )
            is RuleListResult.RootNotFound ->
                errorResponse("Root WorkItem not found: ${result.rootId}", ErrorCodes.RESOURCE_NOT_FOUND)
            is RuleListResult.NotDepthZero ->
                errorResponse(
                    "rootId must reference a depth-0 (root) WorkItem; '${result.rootId}' has depth ${result.depth}",
                    ErrorCodes.VALIDATION_ERROR
                )
            is RuleListResult.RepositoryError ->
                errorResponse("Failed to list rules: ${result.message}", ErrorCodes.DATABASE_ERROR)
        }
    }

    private fun ruleService(context: ToolExecutionContext): RuleService =
        RuleService(context.repositoryProvider.planDocumentRepository(), context.repositoryProvider.workItemRepository())
}

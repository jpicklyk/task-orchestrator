package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.RuleGetResult
import io.github.jpicklyk.mcptask.current.application.service.RuleListResult
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.checkItemScope
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.resolveItemId
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** A `config` `rules` element: list a root's rules, read one, or follow a note's skill pointer to one. */
@Serializable
data class ConfigRulesRequest(
    @Doc(ConfigDocs.ROOT_ID) val rootId: IdRef? = null,
    @Doc("Rule key: lowercase letters, digits, dot, underscore or hyphen, starting alphanumeric, at most 100 characters.")
    val key: String? = null,
    @Doc("Item whose effective schema holds noteKey: a UUID or a hex prefix of 8 to 32 characters. A root item is its own root.")
    val itemId: IdRef? = null,
    @Doc("Note key looked up in itemId's effective schema; its skill field names the rule key.")
    val noteKey: String? = null
)

internal val configRulesOp =
    OpSpec<ConfigRulesRequest, JsonElement>(
        name = "rules",
        doc =
            "Read git-tracked rule text (rule/<key> plan documents). rootId alone lists {key, rulesVersion, updatedAt}; " +
                "rootId with key returns one rule's body; itemId with noteKey follows that note's skill pointer to the rule " +
                "under the item's root. Any other combination is invalid_request.",
        profiles = orchestration(),
        capability = ApiCapability.READ,
        request = serializer<ConfigRulesRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.CONFIG_INVALID),
        execute = { env, ctx, req ->
            shapeError(req)?.let { return@OpSpec Outcome.Err(it) }
            val service = RuleService(env.repositoryProvider.planDocumentRepository(), env.workItemRepository())
            ConfigSupport.guarded {
                when {
                    req.itemId != null -> pointerGet(env, ctx, service, req.itemId, requireNotNull(req.noteKey))
                    req.key != null -> directGet(env, ctx, service, requireNotNull(req.rootId), req.key)
                    else -> list(env, ctx, service, requireNotNull(req.rootId))
                }
            }
        }
    )

/** The `invalid_request` of a field combination that names no mode, or of a malformed key. */
private fun shapeError(req: ConfigRulesRequest): DomainError? {
    val faults = ArrayList<FieldViolation>()
    when {
        req.rootId != null -> {
            if (req.itemId != null) faults += FieldViolation("/itemId", "cannot be combined with rootId")
            if (req.noteKey != null) faults += FieldViolation("/noteKey", "cannot be combined with rootId")
        }
        req.itemId != null || req.noteKey != null -> {
            if (req.key != null) faults += FieldViolation("/key", "cannot be combined with itemId or noteKey")
            if (req.itemId == null) faults += FieldViolation("/itemId", "is required with noteKey")
            if (req.noteKey == null) faults += FieldViolation("/noteKey", "is required with itemId")
        }
        req.key != null -> faults += FieldViolation("/rootId", "is required with key")
        else -> faults += FieldViolation("/rootId", "is required to list; give rootId, rootId and key, or itemId and noteKey")
    }
    if (req.key != null && !RuleService.KEY_PATTERN.matches(req.key)) {
        faults +=
            FieldViolation(
                "/key",
                "must be lowercase letters, digits, . _ - and start alphanumeric, at most 100 characters",
                req.key.take(100)
            )
    }
    return if (faults.isEmpty()) null else invalidFields(faults)
}

private suspend fun list(
    env: ToolExecutionContext,
    ctx: CallContext,
    service: RuleService,
    rootRef: IdRef
): Outcome<JsonElement> {
    val rootId =
        when (val r = ConfigSupport.resolveRoot(env, ctx, rootRef)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    return when (val result = service.list(rootId)) {
        is RuleListResult.Success ->
            Outcome.Ok(
                buildJsonObject {
                    put("rootId", rootId.toString())
                    put(
                        "rules",
                        JsonArray(
                            result.rules.map { rule ->
                                buildJsonObject {
                                    put("key", rule.key)
                                    put("rulesVersion", rule.rulesVersion)
                                    put("updatedAt", rule.updatedAt.toString())
                                }
                            }
                        )
                    )
                }
            )
        is RuleListResult.RootNotFound -> Outcome.Err(ConfigSupport.notFound(EntityKind.ROOT, result.rootId.toString()))
        is RuleListResult.NotDepthZero -> Outcome.Err(notRoot(result.depth))
        is RuleListResult.RepositoryError -> Outcome.Err(result.error)
    }
}

private suspend fun directGet(
    env: ToolExecutionContext,
    ctx: CallContext,
    service: RuleService,
    rootRef: IdRef,
    key: String
): Outcome<JsonElement> {
    val rootId =
        when (val r = ConfigSupport.resolveRoot(env, ctx, rootRef)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    return render(service.get(rootId, key), rootId, key, resolvedFrom = null)
}

private suspend fun pointerGet(
    env: ToolExecutionContext,
    ctx: CallContext,
    service: RuleService,
    itemRef: IdRef,
    noteKey: String
): Outcome<JsonElement> {
    val itemId =
        when (val r = resolveItemId(env, ctx.scope, itemRef, "/itemId")) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    (checkItemScope(env, ctx, itemId) as? Outcome.Err)?.let { return it }
    val item = env.workItemRepository().getById(itemId) ?: return Outcome.Err(ConfigSupport.notFound(EntityKind.ITEM, itemId.toString()))
    val rootId: UUID =
        if (item.depth == 0) item.id else item.rootId ?: return Outcome.Err(invalidField("/itemId", "has no resolvable root"))
    val skill =
        env
            .resolveSchema(item)
            ?.notes
            ?.firstOrNull { it.key == noteKey }
            ?.skill
    if (skill == null) {
        return Outcome.Err(invalidField("/noteKey", "has no entry with a skill pointer in the item's effective schema", noteKey))
    }
    if (!RuleService.KEY_PATTERN.matches(skill)) {
        return Outcome.Err(invalidField("/noteKey", "its skill pointer '$skill' is not a valid rule key", noteKey))
    }
    val from =
        buildJsonObject {
            put("itemId", itemId.toString())
            put("noteKey", noteKey)
            put("skill", skill)
        }
    return render(service.get(rootId, skill), rootId, skill, resolvedFrom = from)
}

private fun render(
    result: RuleGetResult,
    rootId: UUID,
    key: String,
    resolvedFrom: JsonObject?
): Outcome<JsonElement> =
    when (result) {
        is RuleGetResult.Success ->
            Outcome.Ok(
                buildJsonObject {
                    put("rootId", rootId.toString())
                    put("key", key)
                    put("slug", result.document.slug)
                    put("rulesVersion", result.document.contentHash)
                    put("body", result.document.body)
                    resolvedFrom?.let { put("resolvedFrom", it) }
                }
            )
        is RuleGetResult.RootNotFound -> Outcome.Err(ConfigSupport.notFound(EntityKind.ROOT, result.rootId.toString()))
        is RuleGetResult.NotDepthZero -> Outcome.Err(notRoot(result.depth))
        is RuleGetResult.RuleNotFound -> Outcome.Err(ConfigSupport.notFound(EntityKind.DOCUMENT, "rule/${result.key}"))
        is RuleGetResult.RepositoryError -> Outcome.Err(result.error)
    }

private fun notRoot(depth: Int): DomainError = invalidField("/rootId", "must reference a depth-0 (root) item; it has depth $depth")

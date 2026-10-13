package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/** An `items_write` `create` element. `type` is required; whether it is declared is the service's rule. */
@Serializable
data class CreateItemRequest(
    @Doc(ItemDocs.TITLE) val title: String,
    @Doc(ItemDocs.TYPE) val type: String,
    @Doc(ItemDocs.PARENT_ID) val parentId: IdRef? = null,
    @Doc(ItemDocs.DESCRIPTION) val description: String? = null,
    @Doc(ItemDocs.SUMMARY) val summary: String = "",
    @Doc(ItemDocs.STATUS_LABEL) val statusLabel: String? = null,
    @Doc(ItemDocs.PRIORITY) val priority: PriorityWire = PriorityWire.MEDIUM,
    @Doc(ItemDocs.COMPLEXITY) val complexity: Int? = null,
    @Doc(ItemDocs.REQUIRES_VERIFICATION) val requiresVerification: Boolean = false,
    @Doc(ItemDocs.METADATA) val metadata: String? = null,
    @Doc(ItemDocs.TAGS) val tags: List<String> = emptyList(),
    @Doc(ItemDocs.TRAITS) val traits: List<String> = emptyList(),
    @Doc(ItemDocs.PROPERTIES) val properties: JsonObject? = null
)

/** The wire name of the $(Create create[1]) op. */
internal object CreateItemOp {
    const val NAME = "create"
}

internal fun createItemOp(): OpSpec<CreateItemRequest, ItemWriteResult> =
    OpSpec(
        name = CreateItemOp.NAME,
        doc = "Create an item in queue.",
        capability = ApiCapability.WRITE_ITEMS,
        profiles = knowledge(),
        request = serializer<CreateItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.SCHEMA_VIOLATION,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { createItem(env, ctx, req) } }
    )

private suspend fun WriteScope.createItem(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: CreateItemRequest
): Outcome<ItemWriteResult> {
    // Every scope rejection precedes any write.
    val parentId: UUID? =
        when (val ref = req.parentId) {
            null -> {
                // A root create has no parent to anchor on: the new id can never be a listed root, and under a
                // tag scope the tags it is created WITH must carry a listed tag.
                val roots = ctx.scope.rootIds
                if (roots != null) return Outcome.Err(ScopeGuard.forbidden(ForbiddenScope.ROOT, roots.map { it.toString() }))
                if (!tagsSatisfyScope(req.tags, ctx.scope)) {
                    return Outcome.Err(ScopeGuard.forbidden(ForbiddenScope.TAG, ctx.scope.tagsInclude))
                }
                null
            }
            else -> {
                val resolved =
                    when (val r = resolveItemId(env, ctx.scope, ref, "parentId")) {
                        is Outcome.Err -> return r
                        is Outcome.Ok -> r.value
                    }
                (checkItemScope(env, ctx, resolved) as? Outcome.Err)?.let { return it }
                resolved
            }
        }

    val command =
        ItemCreateCommand(
            id = UUID.randomUUID(),
            parentId = parentId,
            title = req.title,
            description = req.description,
            summary = req.summary,
            statusLabel = req.statusLabel,
            priority = req.priority.priority,
            complexity = req.complexity,
            requiresVerification = req.requiresVerification,
            metadata = req.metadata,
            tags = joinTags(req.tags),
            type = req.type,
            properties = req.properties?.toString(),
            traits = req.traits
        )
    return when (val created = env.itemCommandService.create(command)) {
        is Outcome.Err -> created
        is Outcome.Ok -> Outcome.Ok(ItemWriteResult(id = created.value.id, version = created.value.version))
    }
}

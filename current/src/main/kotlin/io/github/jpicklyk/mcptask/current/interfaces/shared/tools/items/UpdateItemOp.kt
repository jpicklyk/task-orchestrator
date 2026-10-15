package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.of
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.ItemPatchCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.UUID

/**
 * An `items_write` `update` element. An absent or null field keeps its value (the decoder cannot tell the two
 * apart); [clear] names the optional fields to null, and `tags: []` / `traits: []` clear those lists.
 */
@Serializable
data class UpdateItemRequest(
    @Doc(ItemDocs.ID) val id: IdRef,
    @Doc(ItemDocs.EXPECTED_VERSION) val expectedVersion: Long? = null,
    @Doc(ItemDocs.TITLE) val title: String? = null,
    @Doc(ItemDocs.TYPE) val type: String? = null,
    @Doc(ItemDocs.PARENT_ID) val parentId: IdRef? = null,
    @Doc(ItemDocs.DESCRIPTION) val description: String? = null,
    @Doc(ItemDocs.SUMMARY) val summary: String? = null,
    @Doc(ItemDocs.STATUS_LABEL) val statusLabel: String? = null,
    @Doc(ItemDocs.PRIORITY) val priority: PriorityWire? = null,
    @Doc(ItemDocs.COMPLEXITY) val complexity: Int? = null,
    @Doc(ItemDocs.REQUIRES_VERIFICATION) val requiresVerification: Boolean? = null,
    @Doc(ItemDocs.METADATA) val metadata: String? = null,
    @Doc(ItemDocs.TAGS) val tags: List<String>? = null,
    @Doc(ItemDocs.TRAITS) val traits: List<String>? = null,
    @Doc(ItemDocs.PROPERTIES) val properties: JsonObject? = null,
    @Doc(ItemDocs.CLEAR) val clear: List<ClearField> = emptyList()
)

/** The wire name of the $(Update update[1]) op. */
internal object UpdateItemOp {
    const val NAME = "update"
}

internal fun updateItemOp(): OpSpec<UpdateItemRequest, ItemWriteResult> =
    OpSpec(
        name = UpdateItemOp.NAME,
        doc = "Update an item; absent or null keeps a value, clear nulls optional fields.",
        capability = ApiCapability.WRITE_ITEMS,
        profiles = knowledge(),
        request = serializer<UpdateItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.CYCLE_DETECTED,
                ErrorCode.SCHEMA_VIOLATION,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { updateItem(env, ctx, req) } }
    )

/** Rejects a `clear` that repeats a field or contradicts a value sent in the same element. */
private fun clearConflicts(req: UpdateItemRequest): List<FieldViolation> {
    val out = ArrayList<FieldViolation>()
    val seen = HashSet<ClearField>()
    for (field in req.clear) {
        if (!seen.add(field)) out.add(FieldViolation("clear", "lists ${wireName(field)} more than once"))
    }

    fun conflict(
        field: ClearField,
        sent: Boolean
    ) {
        if (sent && field in seen) out.add(FieldViolation("clear", "cannot clear ${wireName(field)} and set it in the same element"))
    }
    conflict(ClearField.DESCRIPTION, req.description != null)
    conflict(ClearField.STATUS_LABEL, req.statusLabel != null)
    conflict(ClearField.METADATA, req.metadata != null)
    conflict(ClearField.COMPLEXITY, req.complexity != null)
    conflict(ClearField.PROPERTIES, req.properties != null)
    conflict(ClearField.PARENT_ID, req.parentId != null)
    return out
}

private fun wireName(field: ClearField): String =
    when (field) {
        ClearField.DESCRIPTION -> "description"
        ClearField.STATUS_LABEL -> "statusLabel"
        ClearField.METADATA -> "metadata"
        ClearField.COMPLEXITY -> "complexity"
        ClearField.PROPERTIES -> "properties"
        ClearField.PARENT_ID -> "parentId"
    }

private suspend fun WriteScope.updateItem(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: UpdateItemRequest
): Outcome<ItemWriteResult> {
    clearConflicts(req).takeIf { it.isNotEmpty() }?.let { return Outcome.Err(invalidFields(it)) }

    // Scope first (a missing item under a restricted scope is forbidden), then existence.
    val id =
        when (val r = resolveItemId(env, ctx.scope, req.id, "id")) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }
    val newParent: UUID? =
        req.parentId?.let { ref ->
            when (val r = resolveItemId(env, ctx.scope, ref, "parentId")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        }

    val repo = env.workItemRepository()
    val existing = repo.getById(id) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(id))

    val clearParent = ClearField.PARENT_ID in req.clear
    val parent =
        when {
            newParent != null -> ParentChange.MoveUnder(newParent)
            clearParent -> ParentChange.MoveToRoot
            else -> ParentChange.Keep
        }
    // Reparent targets are authorized like a create-time parent; a move to root makes the item a new project root, which
    // only an unrestricted scope or ADMIN may do (clearing the parent of an item that already is a root is a no-op).
    if (newParent != null && newParent != existing.parentId) {
        (checkItemScope(env, ctx, newParent) as? Outcome.Err)?.let { return it }
    } else if (newParent == null && clearParent && existing.parentId != null && !ctx.rootWritesAllowed) {
        return Outcome.Err(rootWriteForbidden(ctx))
    }

    val command =
        ItemPatchCommand(
            itemId = id,
            expectedVersion = req.expectedVersion ?: existing.version,
            parent = parent,
            title = req.title ?: existing.title,
            description = if (ClearField.DESCRIPTION in req.clear) null else req.description ?: existing.description,
            summary = req.summary ?: existing.summary,
            statusLabel = if (ClearField.STATUS_LABEL in req.clear) null else req.statusLabel ?: existing.statusLabel,
            priority = req.priority?.priority ?: existing.priority,
            complexity = if (ClearField.COMPLEXITY in req.clear) null else req.complexity ?: existing.complexity,
            requiresVerification = req.requiresVerification ?: existing.requiresVerification,
            metadata = if (ClearField.METADATA in req.clear) null else req.metadata ?: existing.metadata,
            tags = if (req.tags == null) existing.tags else joinTags(req.tags),
            type = req.type ?: existing.type,
            properties = if (ClearField.PROPERTIES in req.clear) null else req.properties?.toString() ?: existing.properties,
            traits = req.traits ?: existing.traits
        )
    val caller = ClaimHolder.resolve(ctx.principal.claim, ctx.seat?.assignmentId)
    return when (val patched = env.itemCommandService.patch(command, caller, SeatCaller.of(ctx))) {
        is Outcome.Err -> patched
        is Outcome.Ok ->
            Outcome.Ok(
                ItemWriteResult(
                    id = patched.value.item.id,
                    version = patched.value.item.version,
                    cascade = cascadeSummaries(patched.value.cascadeEvents, ctx, repo)
                )
            )
    }
}

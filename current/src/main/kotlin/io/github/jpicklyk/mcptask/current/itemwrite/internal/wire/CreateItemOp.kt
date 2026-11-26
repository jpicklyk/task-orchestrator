package io.github.jpicklyk.mcptask.current.itemwrite.internal.wire

import io.github.jpicklyk.mcptask.current.item.PriorityWire
import io.github.jpicklyk.mcptask.current.item.checkItemScope
import io.github.jpicklyk.mcptask.current.item.resolveItemId
import io.github.jpicklyk.mcptask.current.itemwrite.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.itemwrite.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.itemwrite.ItemWriteResult
import io.github.jpicklyk.mcptask.current.itemwrite.ItemWriteStoreProvider
import io.github.jpicklyk.mcptask.current.itemwrite.ItemWriteToolServices
import io.github.jpicklyk.mcptask.current.knowledge.LinkService
import io.github.jpicklyk.mcptask.current.knowledge.ResolvedItemDerivedFrom
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.invalidFields
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
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
    @Doc(ItemDocs.PROPERTIES) val properties: JsonObject? = null,
    @Doc(ItemLinkDocs.LINKS) val links: List<ItemLinkSpec> = emptyList()
)

/** The wire name of the $(Create create[1]) op. */
internal object CreateItemOp {
    const val NAME = "create"
}

internal fun createItemOp(): OpSpec<ItemWriteToolServices, CreateItemRequest, ItemWriteResult, ItemWriteStoreProvider> =
    OpSpec(
        name = CreateItemOp.NAME,
        doc = "Create an item. It starts in queue, or lifecycle-free (no role) when its type's schema has lifecycle: none.",
        capability = ApiCapability.WRITE_ITEMS,
        profiles = knowledge(),
        services = ItemWriteToolServices::class,
        stores = ItemWriteStoreProvider::class,
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

private suspend fun WriteScope<ItemWriteStoreProvider>.createItem(
    env: ItemWriteToolServices,
    ctx: CallContext,
    req: CreateItemRequest
): Outcome<ItemWriteResult> {
    // Every scope rejection precedes any write.
    val parentId: UUID? =
        when (val ref = req.parentId) {
            null -> {
                // A depth-0 create founds a new project: no restricted scope owns it (it can never be a listed root),
                // so it needs an unrestricted scope or ADMIN.
                if (!ctx.rootWritesAllowed) return Outcome.Err(rootWriteForbidden(ctx))
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
    // The new row's tags must stay inside the tag scope (and carry a listed tag, or its creator could not read it).
    (ScopeGuard.checkTagWrite(ctx.scope, null, joinTags(req.tags)) as? Outcome.Err)?.let { return it }

    // Declared sources: parsed, their note items scope-checked, then resolved in the new item's root, before any write.
    val id = UUID.randomUUID()
    var linkRootId = id
    var resolvedLinks = emptyList<ResolvedItemDerivedFrom>()
    if (req.links.isNotEmpty()) {
        val (refs, violations) = parseItemLinks(req.links, "/links")
        if (violations.isNotEmpty()) return Outcome.Err(invalidFields(violations))
        val targets =
            when (val r = linkTargetsInScope(env, ctx, refs, "/links")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        if (parentId != null) {
            linkRootId =
                env.stores
                    .hierarchyStore()
                    .resolveChildPlacement(parentId)
                    ?.rootId
                    ?: return Outcome.Err(ItemCommandErrors.parentNotFound(parentId))
        }
        resolvedLinks =
            when (val r = LinkService(env.stores, env.unitOfWork).resolveItemDerivedFrom(linkRootId, targets, "/links")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
    }

    val command =
        ItemCreateCommand(
            id = id,
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
        is Outcome.Ok -> {
            LinkService(env.stores, env.unitOfWork).recordItemDerivedFrom(created.value.id, linkRootId, resolvedLinks, now)
            Outcome.Ok(ItemWriteResult(id = created.value.id, version = created.value.version))
        }
    }
}

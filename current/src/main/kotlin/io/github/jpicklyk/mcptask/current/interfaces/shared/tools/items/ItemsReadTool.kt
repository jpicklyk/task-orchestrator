package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.event.EventTools
import io.github.jpicklyk.mcptask.current.knowledge.KnowledgeTools
import io.github.jpicklyk.mcptask.current.lifecycle.LifecycleTools
import io.github.jpicklyk.mcptask.current.model.claim.ClaimStatus
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.ItemSortField
import io.github.jpicklyk.mcptask.current.model.item.Priority
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.SortDirection
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.platform.tool.pointer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The 4.0 `items_read` tool (plan section 4.1, Q19, Q22): one select per file (`ItemsRead<Select>.kt`), assembled here,
 * so later items add a select by adding a file. Every select needs READ; `get`, `list`, `schema`, `search` and
 * `overview` are KNOWLEDGE-profile, `context`, `next`, `blocked` and `history` ORCHESTRATION-only (plan section 3.1).
 *
 * Shared request fields (a name used by two selects) take their documentation from [ItemsReadDocs], so every select
 * generates the identical schema for them (the registry rejects a mismatch). The shared strings name only knowledge
 * selects: what an orchestration select does with a shared field (a default limit, the `fields` it takes) is in its own
 * select `doc`, which the knowledge profile never renders.
 */
object ItemsReadTool {
    const val NAME = "items_read"

    /** The knowledge selects, the opening every profile's text shares (select enumeration only: doc single-source policy). */
    private const val KNOWLEDGE_SELECTS =
        "Read work items. Selects: get (one item), list (a filtered page), schema (the note schema of a type or an " +
            "item), search (full text over item, note and document content), overview (a hierarchy summary), related " +
            "(items and documents related to an item by dependencies, links, shared tags and similar text)"

    /** The shared (full) description, served to orchestration and REST: every select. */
    const val DESCRIPTION =
        KNOWLEDGE_SELECTS +
            ", context (an item's gate state, or a health view without itemId), next (ranked items ready to pick up), " +
            "blocked (blocked items with their blockers), history (the transition audit trail)."

    /** The knowledge profile's description: it serves no context, next, blocked or history. */
    const val KNOWLEDGE_DESCRIPTION = "$KNOWLEDGE_SELECTS."

    /** The director profile's description: it serves get, list, overview and blocked only. */
    const val DIRECTOR_DESCRIPTION =
        "Read work items. Selects: get (one item), list (a filtered page), overview (a hierarchy summary), " +
            "blocked (blocked items with their blockers)."

    val spec: SelectToolSpec =
        SelectToolSpec(
            name = NAME,
            description = DESCRIPTION,
            profileDescriptions = mapOf(ToolProfile.KNOWLEDGE to KNOWLEDGE_DESCRIPTION, ToolProfile.DIRECTOR to DIRECTOR_DESCRIPTION),
            selects =
                listOf(
                    ItemsReadGet.select,
                    ItemsReadList.select,
                    LifecycleTools.itemsReadSchema,
                    KnowledgeTools.itemsReadSearch,
                    ItemsReadOverview.select,
                    KnowledgeTools.itemsReadRelated,
                    LifecycleTools.itemsReadContext,
                    LifecycleTools.itemsReadNext,
                    LifecycleTools.itemsReadBlocked,
                    EventTools.itemsReadHistory,
                )
        )
}

/** The single documentation text of every request field more than one `items_read` select declares. */
object ItemsReadDocs {
    const val ITEM_ID =
        "Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. " +
            "related: the item to find relatives of."
    const val LIMIT =
        "Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50."
    const val EXPLAIN = "Add ranking detail to each hit (search: per-analyzer ranks; related: per-signal ranks); default false."
    const val CURSOR = "The page.next of the previous page; absent for the first page."
    const val FIELDS =
        "Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance."
    const val ROLE = "Only items (search: content of items) in this role."
    const val TAGS = "Only items with at least one of these tags (case-insensitive)."
    const val TRAITS = "Only items with at least one of these traits (exact)."
    const val ANCESTOR_ID = "Only this item's subtree, itself included (UUID or 8-32 hex)."
    const val TYPE = "list: only items of this type. schema: the type whose schema to return."
    const val PARENT_ID = "Only direct children of this item (UUID or 8-32 hex)."
    const val PRIORITY = "Only items with this priority."
    const val CREATED_AFTER = "Created at or after (ISO 8601)."
    const val CREATED_BEFORE = "Created at or before (ISO 8601)."
    const val MODIFIED_AFTER = "Modified at or after (ISO 8601)."
    const val MODIFIED_BEFORE = "Modified at or before (ISO 8601)."
    const val ROLE_CHANGED_AFTER = "Role changed at or after (ISO 8601)."
    const val ROLE_CHANGED_BEFORE = "Role changed at or before (ISO 8601)."
}

/** A role on the wire. */
@Serializable
enum class RoleWire(
    val role: Role
) {
    @SerialName("queue")
    QUEUE(Role.QUEUE),

    @SerialName("work")
    WORK(Role.WORK),

    @SerialName("review")
    REVIEW(Role.REVIEW),

    @SerialName("blocked")
    BLOCKED(Role.BLOCKED),

    @SerialName("terminal")
    TERMINAL(Role.TERMINAL),
}

/** A priority on the wire. */
@Serializable
enum class PriorityWire(
    val priority: Priority
) {
    @SerialName("high")
    HIGH(Priority.HIGH),

    @SerialName("medium")
    MEDIUM(Priority.MEDIUM),

    @SerialName("low")
    LOW(Priority.LOW),
}

/** A claim-status filter on the wire. */
@Serializable
enum class ClaimStatusWire(
    val status: ClaimStatus
) {
    @SerialName("claimed")
    CLAIMED(ClaimStatus.CLAIMED),

    @SerialName("unclaimed")
    UNCLAIMED(ClaimStatus.UNCLAIMED),

    @SerialName("expired")
    EXPIRED(ClaimStatus.EXPIRED),
}

/** A list sort field on the wire (no 3.x aliases). */
@Serializable
enum class SortWire(
    val field: ItemSortField
) {
    @SerialName("title")
    TITLE(ItemSortField.TITLE),

    @SerialName("priority")
    PRIORITY(ItemSortField.PRIORITY),

    @SerialName("complexity")
    COMPLEXITY(ItemSortField.COMPLEXITY),

    @SerialName("createdAt")
    CREATED_AT(ItemSortField.CREATED_AT),

    @SerialName("modifiedAt")
    MODIFIED_AT(ItemSortField.MODIFIED_AT),
}

/** A sort direction on the wire. */
@Serializable
enum class OrderWire(
    val direction: SortDirection
) {
    @SerialName("asc")
    ASC(SortDirection.ASC),

    @SerialName("desc")
    DESC(SortDirection.DESC),
}

// ---- shared select helpers ------------------------------------------------------------------------------

/** [ScopedIds.resolve] of [ref] (request field [field]) under the caller's scope; the resolved id is not scope-checked. */
internal suspend fun resolveId(
    env: ItemLookup,
    ctx: CallContext,
    ref: IdRef,
    field: String
): Outcome<UUID> = ScopedIds.resolve(env.workItemRepository(), ctx.scope, ref, pointer(field))

/** [ScopedIds.resolveTarget]: a target id the caller may not see is `forbidden`. */
internal suspend fun resolveTarget(
    env: ItemLookup,
    ctx: CallContext,
    ref: IdRef,
    field: String
): Outcome<UUID> = ScopedIds.resolveTarget(env.workItemRepository(), ctx.scope, ref, pointer(field))

/** [resolveId] of an optional [ref]; null stays null. */
internal suspend fun resolveOptionalId(
    env: ItemLookup,
    ctx: CallContext,
    ref: IdRef?,
    field: String
): Outcome<UUID?> = if (ref == null) Outcome.Ok(null) else resolveId(env, ctx, ref, field)

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.CursorCodec
import io.github.jpicklyk.mcptask.current.application.port.ItemSortField
import io.github.jpicklyk.mcptask.current.application.port.ListPage
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SortDirection
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallFields
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** Select enumeration only; every field documents itself (doc single-source policy). */
    const val DESCRIPTION =
        "Read work items. Selects: get (one item), list (a filtered page), schema (the note schema of a type or an " +
            "item), search (full text over item, note and document content), overview (a hierarchy summary), related " +
            "(items and documents related to an item by dependencies, links, shared tags and similar text)."

    val spec: SelectToolSpec =
        SelectToolSpec(
            NAME,
            DESCRIPTION,
            listOf(
                ItemsReadGet.select,
                ItemsReadList.select,
                ItemsReadSchema.select,
                ItemsReadSearch.select,
                ItemsReadOverview.select,
                ItemsReadRelated.select,
                ItemsReadContext.select,
                ItemsReadNext.select,
                ItemsReadBlocked.select,
                ItemsReadHistory.select,
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

/** A search match mode on the wire. */
@Serializable
enum class MatchModeWire(
    val mode: SearchMatchMode
) {
    @SerialName("auto")
    AUTO(SearchMatchMode.AUTO),

    @SerialName("substring")
    SUBSTRING(SearchMatchMode.SUBSTRING),

    @SerialName("text")
    TEXT(SearchMatchMode.TEXT),
}

/** A searchable kind of content on the wire. */
@Serializable
enum class KindWire(
    val corpus: Corpus
) {
    @SerialName("item")
    ITEM(Corpus.ITEM),

    @SerialName("note")
    NOTE(Corpus.NOTE),

    @SerialName("document")
    DOCUMENT(Corpus.DOCUMENT),
}

// ---- shared select helpers ------------------------------------------------------------------------------

/** The JSON pointer of the top-level request field [name]. */
internal fun pointer(name: String): String = "/$name"

/** [ScopedIds.resolve] of [ref] (request field [field]) under the caller's scope; the resolved id is not scope-checked. */
internal suspend fun resolveId(
    env: ToolExecutionContext,
    ctx: CallContext,
    ref: IdRef,
    field: String
): Outcome<UUID> = ScopedIds.resolve(env.workItemRepository(), ctx.scope, ref, pointer(field))

/** [ScopedIds.resolveTarget]: a target id the caller may not see is `forbidden`. */
internal suspend fun resolveTarget(
    env: ToolExecutionContext,
    ctx: CallContext,
    ref: IdRef,
    field: String
): Outcome<UUID> = ScopedIds.resolveTarget(env.workItemRepository(), ctx.scope, ref, pointer(field))

/** [resolveId] of an optional [ref]; null stays null. */
internal suspend fun resolveOptionalId(
    env: ToolExecutionContext,
    ctx: CallContext,
    ref: IdRef?,
    field: String
): Outcome<UUID?> = if (ref == null) Outcome.Ok(null) else resolveId(env, ctx, ref, field)

/** [ScopedIds.denied]. */
internal fun scopeDenied(scope: AccessScope): DomainError = ScopedIds.denied(scope)

/**
 * The page a select reads: [limit] (default [default], `invalid_request` on `/limit` outside `1..max`) and [cursor]
 * (`invalid_cursor` when it does not decode).
 */
internal fun pageRequest(
    limit: Int?,
    cursor: String?,
    default: Int,
    max: Int
): Outcome<PageRequest> {
    val effective = limit ?: default
    if (effective !in 1..max) return Outcome.Err(invalidField(pointer("limit"), "must be between 1 and $max", effective.toString()))
    val offset =
        if (cursor == null) {
            0
        } else {
            when (val decoded = CursorCodec.decode(cursor)) {
                is Outcome.Err -> return decoded
                is Outcome.Ok -> decoded.value
            }
        }
    return Outcome.Ok(PageRequest(effective, offset))
}

/**
 * [unknownFields], and, when the names are all accepted, notes them on the call's telemetry (`request_shape.fields`).
 * A select calls this once, in place of [unknownFields].
 */
internal suspend fun acceptFields(
    fields: List<String>?,
    allowed: List<String>
): DomainError? {
    val error = unknownFields(fields, allowed)
    if (error == null && !fields.isNullOrEmpty()) recordCallFields(fields)
    return error
}

/** `invalid_request` on `/fields` for any name of [fields] outside [allowed]; null when all are allowed. */
internal fun unknownFields(
    fields: List<String>?,
    allowed: List<String>
): DomainError? {
    val bad = fields?.firstOrNull { it !in allowed } ?: return null
    return invalidField(pointer("fields"), "must contain only: ${allowed.joinToString(", ")}", bad)
}

/**
 * A [ListPage] of [items] read at [page] out of [total], built by [ListPage.of] (envelope amendment U22: `truncated` and
 * `next` mean "items exist after this page", so the last page carries no cursor). Page and count share one read unit, so
 * `total >= items.size`; the max only guards the [ListPage] invariant.
 */
internal fun <T> pageOf(
    items: List<T>,
    total: Int,
    page: PageRequest,
    capped: Boolean = false
): ListPage<T> = ListPage.of(items, total = maxOf(total, items.size), page = page, capped = capped)

/** `{items, page}` members of [page] (the encoder's list data shape), for a select whose data carries more. */
internal fun <T> JsonObjectBuilder.putPage(
    page: ListPage<T>,
    item: (T) -> JsonElement
) {
    put("items", JsonArray(page.items.map(item)))
    put(
        "page",
        buildJsonObject {
            put("total", page.total)
            put("truncated", page.truncated)
            if (page.capped) put("capped", true)
            page.next?.let { put("next", it) }
        }
    )
}

/** Role counts as `{role: n}`, zero roles omitted, in role order. */
internal fun countsJson(counts: Map<Role, Int>): JsonObject =
    buildJsonObject {
        for (role in Role.entries) {
            val n = counts[role] ?: 0
            if (n > 0) put(role.name.lowercase(), n)
        }
    }

/** `not_found` for an item id that does not exist (visible only to an unrestricted caller, see [resolveTarget]). */
internal fun itemNotFound(id: UUID): DomainError =
    DomainError(
        code = ErrorCode.NOT_FOUND,
        message = "No item matches $id.",
        detail = ErrorDetail.NotFound(io.github.jpicklyk.mcptask.current.domain.error.EntityKind.ITEM, id.toString()),
        fixArgs = mapOf("kind" to "item", "id" to id.toString())
    )

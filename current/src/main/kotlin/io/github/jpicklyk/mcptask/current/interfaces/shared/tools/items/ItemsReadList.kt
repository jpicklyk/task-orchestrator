package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.application.port.ItemSort
import io.github.jpicklyk.mcptask.current.application.port.ItemSortField
import io.github.jpicklyk.mcptask.current.application.port.SortDirection
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.time.Instant
import java.util.UUID

/**
 * `items_read` select `list`: one page of the items the filters select and the caller may see, as compact views.
 *
 * The page and its exact `total` are read in ONE read unit (plan section 3.3); the caller's scope is applied by the
 * store before paging. A filter id (`parentId`, `ancestorId`) is resolved under D2 but not scope-checked: an
 * out-of-scope or unmatched subtree simply selects nothing. `fields` adds `summary`, timestamps or `ancestors`.
 */
object ItemsReadList {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("summary", "createdAt", "modifiedAt", "roleChangedAt", "ancestors")

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.PARENT_ID) val parentId: IdRef? = null,
        @Doc(ItemsReadDocs.ANCESTOR_ID) val ancestorId: IdRef? = null,
        @Doc("Only items at this depth (0 = roots).") val depth: Int? = null,
        @Doc(ItemsReadDocs.ROLE) val role: RoleWire? = null,
        @Doc(ItemsReadDocs.PRIORITY) val priority: PriorityWire? = null,
        @Doc(ItemsReadDocs.TAGS) val tags: List<String>? = null,
        @Doc(ItemsReadDocs.TRAITS) val traits: List<String>? = null,
        @Doc(ItemsReadDocs.TYPE) val type: String? = null,
        @Doc("Only items in this claim state (expired: TTL elapsed).")
        val claimStatus: ClaimStatusWire? = null,
        @Doc(ItemsReadDocs.CREATED_AFTER) @Contextual val createdAfter: Instant? = null,
        @Doc(ItemsReadDocs.CREATED_BEFORE) @Contextual val createdBefore: Instant? = null,
        @Doc(ItemsReadDocs.MODIFIED_AFTER) @Contextual val modifiedAfter: Instant? = null,
        @Doc(ItemsReadDocs.MODIFIED_BEFORE) @Contextual val modifiedBefore: Instant? = null,
        @Doc(ItemsReadDocs.ROLE_CHANGED_AFTER)
        @Contextual val roleChangedAfter: Instant? = null,
        @Doc(ItemsReadDocs.ROLE_CHANGED_BEFORE)
        @Contextual val roleChangedBefore: Instant? = null,
        @Doc("Sort field; default createdAt.") val sort: SortWire? = null,
        @Doc("Sort direction; default desc.") val order: OrderWire? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
    )

    val select =
        SelectSpec.Paged<Request, JsonObject>(
            name = "list",
            doc = "A filtered, sorted page of the items the caller may see, as compact views with an exact total.",
            profile = ToolProfile.KNOWLEDGE,
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                unknownFields(req.fields, FIELDS)?.let { return@handler Outcome.Err(it) }
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, PageRequest.DEFAULT_LIMIT, PageRequest.MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val parentId =
                    when (val r = resolveOptionalId(env, ctx, req.parentId, "parentId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val ancestorId =
                    when (val r = resolveOptionalId(env, ctx, req.ancestorId, "ancestorId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val filter =
                    ItemFilter(
                        parentId = parentId,
                        ancestorId = ancestorId,
                        depth = req.depth,
                        role = req.role?.role,
                        priority = req.priority?.priority,
                        tagsAny = req.tags,
                        traitsAny = req.traits,
                        type = req.type,
                        createdAfter = req.createdAfter,
                        createdBefore = req.createdBefore,
                        modifiedAfter = req.modifiedAfter,
                        modifiedBefore = req.modifiedBefore,
                        roleChangedAfter = req.roleChangedAfter,
                        roleChangedBefore = req.roleChangedBefore,
                        claimStatus = req.claimStatus?.status,
                    )
                val inverted = filter.validate()
                if (inverted.isNotEmpty()) {
                    return@handler Outcome.Err(invalidFields(inverted.map { FieldViolation(pointer(it.field), it.reason, it.received) }))
                }
                val sort =
                    ItemSort(
                        req.sort?.field ?: ItemSortField.CREATED_AT,
                        req.order?.direction ?: SortDirection.DESC
                    )
                val wanted = req.fields.orEmpty().toSet()
                val scope = ctx.scope
                val read =
                    env.unitOfWork.read {
                        val store = stores.itemStore()
                        val items = store.find(filter, scope, ItemPage(page.limit, page.offset), sort)
                        val total = store.count(filter, scope)
                        val chains =
                            if ("ancestors" in wanted && items.isNotEmpty()) {
                                stores.hierarchyStore().findAncestorChains(items.map { it.id }.toSet())
                            } else {
                                emptyMap()
                            }
                        Read(items, total, chains)
                    }
                recordCallResultCounts(read.total, null)
                val timestamps = "createdAt" in wanted || "modifiedAt" in wanted || "roleChangedAt" in wanted
                val views =
                    read.items.map { item ->
                        var view = ItemView.compact(item, summary = "summary" in wanted)
                        if (timestamps) {
                            val all = ItemView.compact(item, timestamps = true)
                            for (name in listOf("createdAt", "modifiedAt", "roleChangedAt")) {
                                if (name in wanted) view = ItemView.with(view, name, all.getValue(name))
                            }
                        }
                        if ("ancestors" in wanted) {
                            val visible = ItemView.visibleAncestors(read.chains[item.id] ?: emptyList(), scope)
                            view = ItemView.with(view, "ancestors", JsonArray(visible.map { ItemView.compact(it) }))
                        }
                        view
                    }
                Outcome.Ok(pageOf(views, read.total, page))
            },
            item = { it }
        )

    private class Read(
        val items: List<WorkItem>,
        val total: Int,
        val chains: Map<UUID, List<WorkItem>>,
    )
}

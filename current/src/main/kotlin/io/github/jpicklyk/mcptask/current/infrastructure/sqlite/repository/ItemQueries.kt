package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ItemSortFields
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import java.time.Instant
import java.util.UUID

/**
 * Every work-item selector the stores support, as one value. The item store (filters, scoped queries, counts) and
 * the claim store (next-item, claimable, selector counts) both build their WHERE clause from this via
 * [ItemQueries.conditions], so a filter is defined once.
 */
internal data class ItemQuerySpec(
    val parentId: UUID? = null,
    val depth: Int? = null,
    val role: Role? = null,
    val priority: Priority? = null,
    val tags: List<String>? = null,
    val type: String? = null,
    val complexityMax: Int? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val modifiedAfter: Instant? = null,
    val modifiedBefore: Instant? = null,
    val roleChangedAfter: Instant? = null,
    val roleChangedBefore: Instant? = null,
    val claimStatus: ClaimStatus? = null
)

internal object ItemQueries {
    /**
     * The AND-ed conditions for [spec] under an optional [scope] (the scope condition comes first so SQLite can
     * push it through `idx_work_items_root_id` or the primary key). Returns null when the scope is empty (nothing
     * can match). [at] is the instant claim-status conditions are evaluated at; required when
     * `spec.claimStatus` is set.
     */
    fun conditions(
        spec: ItemQuerySpec,
        scope: ResolvedScope?,
        at: Instant?
    ): MutableList<Op<Boolean>>? {
        if (scope == ResolvedScope.Empty) return null
        val conditions = mutableListOf<Op<Boolean>>()
        scope?.let { conditions.add(it.toCondition()) }
        spec.parentId?.let { conditions.add(WorkItemsTable.parentId eq it) }
        spec.depth?.let { conditions.add(WorkItemsTable.depth eq it) }
        spec.role?.let { conditions.add(WorkItemsTable.role eq it.name.lowercase()) }
        spec.priority?.let { conditions.add(WorkItemsTable.priority eq it.name.lowercase()) }
        spec.tags?.takeIf { it.isNotEmpty() }?.let { conditions.add(tagFilter(it)) }
        spec.type?.let { conditions.add(WorkItemsTable.type eq it) }
        spec.complexityMax?.let { conditions.add(WorkItemsTable.complexity lessEq it) }
        spec.createdAfter?.let { conditions.add(WorkItemsTable.createdAt greaterEq it) }
        spec.createdBefore?.let { conditions.add(WorkItemsTable.createdAt lessEq it) }
        spec.modifiedAfter?.let { conditions.add(WorkItemsTable.modifiedAt greaterEq it) }
        spec.modifiedBefore?.let { conditions.add(WorkItemsTable.modifiedAt lessEq it) }
        spec.roleChangedAfter?.let { conditions.add(WorkItemsTable.roleChangedAt greaterEq it) }
        spec.roleChangedBefore?.let { conditions.add(WorkItemsTable.roleChangedAt lessEq it) }
        spec.claimStatus?.let { conditions.add(ClaimPredicates.forStatus(it, requireNotNull(at) { "claim status needs an instant" })) }
        return conditions
    }

    /**
     * Matches tags at word boundaries within a comma-separated string: alone ("bug"), at the start
     * ("bug,feature"), in the middle ("alpha,bug,beta") or at the end ("alpha,bug"). OR across the list.
     */
    fun tagFilter(tags: List<String>): Op<Boolean> =
        tags
            .map { tag ->
                val t = tag.trim().lowercase()
                (WorkItemsTable.tags eq t) or
                    (WorkItemsTable.tags like "$t,%") or
                    (WorkItemsTable.tags like "%,$t,%") or
                    (WorkItemsTable.tags like "%,$t")
            }.reduce { acc, op -> acc or op }

    /** Priority rank HIGH=0, MEDIUM=1, LOW=2 (anything else 99), the single definition behind every priority sort. */
    fun priorityRank(): Expression<Int> =
        Case()
            .When(WorkItemsTable.priority eq Priority.HIGH.name.lowercase(), LiteralOp(IntegerColumnType(), 0))
            .When(WorkItemsTable.priority eq Priority.MEDIUM.name.lowercase(), LiteralOp(IntegerColumnType(), 1))
            .When(WorkItemsTable.priority eq Priority.LOW.name.lowercase(), LiteralOp(IntegerColumnType(), 2))
            .Else(LiteralOp(IntegerColumnType(), 99))

    /**
     * Applies the shared sortBy/sortOrder mapping: every value [ItemSortFields] advertises (`title`, `priority`,
     * `complexity`, `createdAt`, `modifiedAt`) sorts by its own column, plus the legacy `created`/`modified`
     * aliases. `priority` sorts by rank (high/medium/low), not the raw varchar. `complexity` sorts with NULLs last
     * regardless of direction. Unresolved/null [sortBy] falls back to `createdAt`. A secondary `id ASC` tiebreak
     * makes pagination stable across ties on the primary key.
     */
    fun applySort(
        query: Query,
        sortBy: String?,
        sortOrder: String?
    ): Query {
        val order =
            when (sortOrder?.lowercase()) {
                "asc" -> SortOrder.ASC
                else -> SortOrder.DESC
            }
        val canonical = sortBy?.let { ItemSortFields.canonicalField(it) } ?: ItemSortFields.CREATED_AT

        return when (canonical) {
            ItemSortFields.TITLE -> query.orderBy(WorkItemsTable.title, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
            ItemSortFields.PRIORITY -> {
                // "desc" means high-first, i.e. rank ASCENDING; "asc" means low-first, i.e. rank DESCENDING.
                val rankOrder = if (order == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                query.orderBy(priorityRank(), rankOrder).orderBy(WorkItemsTable.id, SortOrder.ASC)
            }
            ItemSortFields.COMPLEXITY -> {
                val complexityOrder = if (order == SortOrder.ASC) SortOrder.ASC_NULLS_LAST else SortOrder.DESC_NULLS_LAST
                query.orderBy(WorkItemsTable.complexity, complexityOrder).orderBy(WorkItemsTable.id, SortOrder.ASC)
            }
            ItemSortFields.MODIFIED_AT -> query.orderBy(WorkItemsTable.modifiedAt, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
            else -> query.orderBy(WorkItemsTable.createdAt, order).orderBy(WorkItemsTable.id, SortOrder.ASC)
        }
    }
}

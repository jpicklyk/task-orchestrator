package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.time.Instant
import java.util.UUID

/**
 * What [ItemStore.find] and [ItemStore.count] select. Every non-null field is a condition and the
 * conditions are AND-ed; [tagsAny] is OR-ed within its list.
 *
 * @property parentId Direct children of this item.
 * @property ancestorId The subtree rooted at this item, the item itself included. AND-ed with the
 *   caller's [AccessScope] root restriction, never a replacement for it.
 * @property tagsAny The item carries at least one of these tags: each one trimmed and lower-cased,
 *   matched against the comma-separated `tags` column at element boundaries. Null or empty means no
 *   tag condition.
 * @property createdAfter Inclusive lower bound; the other After/Before pairs work the same way.
 * @property claimStatus Evaluated at the instant the store reads its clock for the call.
 */
data class ItemFilter(
    val parentId: UUID? = null,
    val ancestorId: UUID? = null,
    val depth: Int? = null,
    val role: Role? = null,
    val priority: Priority? = null,
    val tagsAny: List<String>? = null,
    val type: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val modifiedAfter: Instant? = null,
    val modifiedBefore: Instant? = null,
    val roleChangedAfter: Instant? = null,
    val roleChangedBefore: Instant? = null,
    val claimStatus: ClaimStatus? = null,
) {
    /**
     * The inverted time ranges of this filter, in the order created, modified, roleChanged. A range
     * is inverted when its After bound is strictly later than its Before bound; equal bounds are
     * valid. Each violation names the After field, with the reason `must be before <Before field>`.
     */
    fun validate(): List<FieldViolation> =
        listOfNotNull(
            inverted("createdAfter", createdAfter, "createdBefore", createdBefore),
            inverted("modifiedAfter", modifiedAfter, "modifiedBefore", modifiedBefore),
            inverted("roleChangedAfter", roleChangedAfter, "roleChangedBefore", roleChangedBefore),
        )

    private fun inverted(
        afterField: String,
        after: Instant?,
        beforeField: String,
        before: Instant?,
    ): FieldViolation? =
        if (after != null && before != null && after > before) {
            FieldViolation(afterField, "must be before $beforeField", after.toString())
        } else {
            null
        }

    companion object {
        /** No condition: every item. */
        val ALL = ItemFilter()
    }
}

/**
 * The column an item list is ordered by. [wire] is the name callers send.
 *
 * - [PRIORITY] orders by rank (high, medium, low), not by the stored text: descending means
 *   high first.
 * - [COMPLEXITY] puts items without a complexity last in both directions.
 */
enum class ItemSortField(
    val wire: String
) {
    TITLE("title"),
    PRIORITY("priority"),
    COMPLEXITY("complexity"),
    CREATED_AT("createdAt"),
    MODIFIED_AT("modifiedAt"),
    ;

    companion object {
        /** The advertised field names, in declaration order. */
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        // Legacy aliases kept for callers that predate the AR-46 fix.
        private val ALIASES: Map<String, ItemSortField> = mapOf("created" to CREATED_AT, "modified" to MODIFIED_AT)

        /**
         * The field [raw] names, ignoring case, with the `created`/`modified` aliases; null when it
         * names none. Null means invalid, never "use the default".
         */
        fun parse(raw: String): ItemSortField? {
            val lower = raw.lowercase()
            return entries.firstOrNull { it.wire.lowercase() == lower } ?: ALIASES[lower]
        }
    }
}

/** Sort direction. [wire] is the name callers send. */
enum class SortDirection(
    val wire: String
) {
    ASC("asc"),
    DESC("desc"),
    ;

    companion object {
        /** The accepted direction names. */
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        /** The direction [raw] names, ignoring case; null when it names none. */
        fun parse(raw: String): SortDirection? {
            val lower = raw.lowercase()
            return entries.firstOrNull { it.wire == lower }
        }
    }
}

/**
 * The order of an item list. Every sort ends with an `id` ascending tiebreak, so offset pages are
 * disjoint even when rows tie on [field].
 */
data class ItemSort(
    val field: ItemSortField,
    val direction: SortDirection,
) {
    companion object {
        /** Newest first. */
        val DEFAULT = ItemSort(ItemSortField.CREATED_AT, SortDirection.DESC)

        /**
         * The sort a caller asked for: [sortBy] null means `createdAt`, [sortOrder] null means
         * `desc`. Returns null when either value is supplied but unknown; there is no silent
         * fallback.
         */
        fun parse(
            sortBy: String?,
            sortOrder: String?,
        ): ItemSort? {
            val field = if (sortBy == null) ItemSortField.CREATED_AT else ItemSortField.parse(sortBy) ?: return null
            val direction = if (sortOrder == null) SortDirection.DESC else SortDirection.parse(sortOrder) ?: return null
            return ItemSort(field, direction)
        }
    }
}

/**
 * One window of an item list. The store applies no upper bound on [limit]; API caps belong to the
 * caller.
 */
data class ItemPage(
    val limit: Int,
    val offset: Int = 0,
) {
    init {
        require(limit >= 1) { "limit must be at least 1, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
    }
}

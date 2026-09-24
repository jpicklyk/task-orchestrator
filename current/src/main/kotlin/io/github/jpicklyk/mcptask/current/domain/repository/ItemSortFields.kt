package io.github.jpicklyk.mcptask.current.domain.repository

// ---------------------------------------------------------------------------
// Shared sortBy/sortOrder vocabulary for WorkItemRepository.findByFilters /
// findInScope and the MCP/REST callers that validate against it
// (QueryItemsTool.validateParams, ItemRoutes). Defined once here so the tool
// boundary and the route boundary cannot drift from the repository's actual
// sort-column mapping (AR-46).
// ---------------------------------------------------------------------------

/**
 * Canonical sortBy field names and legacy aliases, and the valid sortOrder values,
 * for work-item list/search queries.
 */
object ItemSortFields {
    const val TITLE = "title"
    const val PRIORITY = "priority"
    const val COMPLEXITY = "complexity"
    const val CREATED_AT = "createdAt"
    const val MODIFIED_AT = "modifiedAt"

    /** Advertised sortBy values (parameterSchema / docs). */
    val FIELDS: List<String> = listOf(TITLE, PRIORITY, COMPLEXITY, CREATED_AT, MODIFIED_AT)

    /** Valid sortOrder values. */
    val ORDERS: List<String> = listOf("asc", "desc")

    // Legacy aliases kept for backward compatibility with existing repository tests
    // and REST callers that predate the AR-46 fix.
    private val aliases: Map<String, String> =
        mapOf(
            "created" to CREATED_AT,
            "modified" to MODIFIED_AT,
        )

    /**
     * Resolves [raw] (case-insensitive) to one of [FIELDS], honoring the legacy
     * `created`/`modified` aliases. Returns null when [raw] does not match any
     * advertised field or alias — callers treat null as "unknown/invalid", not
     * as "use the default".
     */
    fun canonicalField(raw: String): String? {
        val lower = raw.lowercase()
        FIELDS.forEach { field -> if (field.lowercase() == lower) return field }
        return aliases[lower]
    }
}

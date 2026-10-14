package io.github.jpicklyk.mcptask.current.application.port

import java.util.UUID

/**
 * What a caller may see, applied by the store or search engine as a filter BEFORE paging, ranking and capping, so a
 * restricted caller gets full pages of in-scope rows and exact totals.
 *
 * Two forms:
 * - [unrestricted] — everything. The MCP tools use it explicitly (one call site each), so introducing a principal
 *   scope on MCP changes exactly those call sites.
 * - [principal] — a REST principal's scope: [rootIds] (an item is visible when its stamped `root_id` is one of
 *   them; roots only, every listed id must be a depth-0 project root, so there is no ancestor walk) and [tagsInclude] (the item, or the note's owning
 *   item, carries one of these tags: the comma-separated `tags` column is split, each element trimmed, and an
 *   element must equal a listed tag exactly — case-sensitive, empty elements never match). The interfaces layer
 *   builds it from the authenticated principal.
 *
 * @property rootIds Root allowlist (depth-0 project roots only), or null for no root restriction. An empty set admits nothing.
 * @property tagsInclude Tag allowlist; empty means no tag restriction.
 */
class AccessScope private constructor(
    val rootIds: Set<UUID>?,
    val tagsInclude: Set<String>,
) {
    /** True when neither a root nor a tag restriction applies. */
    val isUnrestricted: Boolean get() = rootIds == null && tagsInclude.isEmpty()

    override fun equals(other: Any?): Boolean = other is AccessScope && other.rootIds == rootIds && other.tagsInclude == tagsInclude

    override fun hashCode(): Int = 31 * (rootIds?.hashCode() ?: 0) + tagsInclude.hashCode()

    override fun toString(): String =
        if (isUnrestricted) "AccessScope(unrestricted)" else "AccessScope(rootIds=$rootIds, tagsInclude=$tagsInclude)"

    companion object {
        private val UNRESTRICTED = AccessScope(null, emptySet())

        /** No restriction at all. */
        fun unrestricted(): AccessScope = UNRESTRICTED

        /** A principal's scope: [rootIds] null for no root restriction, [tagsInclude] empty for no tag restriction. */
        fun principal(
            rootIds: Set<UUID>?,
            tagsInclude: Set<String>,
        ): AccessScope = if (rootIds == null && tagsInclude.isEmpty()) UNRESTRICTED else AccessScope(rootIds?.toSet(), tagsInclude.toSet())
    }
}

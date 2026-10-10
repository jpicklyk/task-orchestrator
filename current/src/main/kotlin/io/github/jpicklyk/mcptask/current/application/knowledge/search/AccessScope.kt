package io.github.jpicklyk.mcptask.current.application.knowledge.search

import java.util.UUID

/**
 * What a caller may see, applied by the search engine as a filter BEFORE ranking and capping, so a restricted
 * caller gets full pages of in-scope hits.
 *
 * Two forms:
 * - [unrestricted] — everything. The MCP tools use it explicitly (one call site each), so introducing a principal
 *   scope on MCP changes exactly those call sites.
 * - [principal] — a REST principal's scope: [rootIds] (an item is visible when its ancestor chain, itself
 *   included, contains one of them; ids need not be depth-0) and [tagsInclude] (the item, or the note's owning
 *   item, carries one of these tags, compared exactly). The interfaces layer builds it from the authenticated
 *   principal.
 *
 * @property rootIds Root allowlist, or null for no root restriction. An empty set admits nothing.
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

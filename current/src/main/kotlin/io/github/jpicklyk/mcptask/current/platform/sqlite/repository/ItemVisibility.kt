package io.github.jpicklyk.mcptask.current.platform.sqlite.repository

import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Op

/**
 * The root restriction of an [AccessScope] after resolution: [resolved] is the WHERE form of its `rootIds`, or null
 * when the scope has no root restriction. See [resolveRoots].
 */
internal class ResolvedRoots(
    val resolved: ResolvedScope?,
)

/**
 * Resolves the root restriction of [scope] (inside a transaction): null when nothing is visible (an empty
 * root set), else the [ResolvedRoots]: `root_id IN (...)`, no probe and no traversal.
 */
internal fun resolveRoots(scope: AccessScope): ResolvedRoots? {
    val rootIds = scope.rootIds ?: return ResolvedRoots(null)
    val resolved = ScopeResolver.resolve(rootIds)
    return if (resolved == ResolvedScope.Empty) null else ResolvedRoots(resolved)
}

/**
 * The WHERE conditions over the outer `work_items` row that admit only items visible under [scope], with the same
 * predicates as `ItemStore.find`: the resolved root restriction and the [TagAllowlistOp] tag allowlist. Empty for an
 * unrestricted scope.
 */
internal fun visibilityConditions(
    roots: ResolvedRoots,
    scope: AccessScope,
): List<Op<Boolean>> {
    val out = ArrayList<Op<Boolean>>()
    roots.resolved?.let { out.add(it.toCondition()) }
    if (scope.tagsInclude.isNotEmpty()) out.add(TagAllowlistOp(WorkItemsTable.tags, scope.tagsInclude))
    return out
}

/** [visibilityConditions] after resolving [scope]'s roots; null when nothing is visible. Must run inside a transaction. */
internal fun visibilityConditions(scope: AccessScope): List<Op<Boolean>>? = resolveRoots(scope)?.let { visibilityConditions(it, scope) }

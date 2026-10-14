package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The ONE scope predicate (Q32 deleted the REST twin): decides whether a call's [AccessScope] admits
 * an item. Tool handlers call it for every item an op touches (the dispatcher cannot, because only a
 * handler knows which items an op reads or writes), and the SSE stream filters tag-scoped events with it.
 *
 * Rules: an unrestricted scope is admitted with no database read. With a root restriction the item's stamped
 * `root_id` must be a listed root (no ancestor walk; an item with a NULL `root_id` is not admitted); with a tag
 * restriction the item must carry one of the listed tags (the CSV `tags` column split, each element trimmed, exact
 * case-sensitive match; no ancestor walk). A missing item, or a lookup fault, under a restricted scope is
 * `forbidden` (fail closed, so scope is no existence oracle).
 */
object ScopeGuard {
    private val logger = LoggerFactory.getLogger(ScopeGuard::class.java)

    suspend fun check(
        scope: AccessScope,
        itemId: UUID,
        repo: WorkItemRepository
    ): Outcome<Unit> {
        if (scope.isUnrestricted) return Outcome.Ok(Unit)

        val item =
            try {
                repo.getById(itemId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.warn("Scope check failed to read item {}: {}", itemId, e.message)
                return Outcome.Err(forbiddenFor(scope))
            } ?: return Outcome.Err(forbiddenFor(scope))
        val rootIds = scope.rootIds
        if (rootIds != null && !inRoots(item, rootIds)) return Outcome.Err(forbidden(ForbiddenScope.ROOT, rootIds.map { it.toString() }))
        if (scope.tagsInclude.isNotEmpty() &&
            !hasListedTag(item, scope)
        ) {
            return Outcome.Err(forbidden(ForbiddenScope.TAG, scope.tagsInclude))
        }
        return Outcome.Ok(Unit)
    }

    /** The error for a missing item or a lookup fault: the root restriction when there is one, else the tag one. */
    private fun forbiddenFor(scope: AccessScope): DomainError =
        scope.rootIds?.let { forbidden(ForbiddenScope.ROOT, it.map { id -> id.toString() }) }
            ?: forbidden(ForbiddenScope.TAG, scope.tagsInclude)

    private fun inRoots(
        item: WorkItem,
        rootIds: Set<UUID>
    ): Boolean = item.rootId?.let { it in rootIds } == true

    private fun hasListedTag(
        item: WorkItem,
        scope: AccessScope
    ): Boolean = splitTags(item.tags).any { it in scope.tagsInclude }

    /**
     * Batch twin of [check]: the subset of [ids] the [scope] admits, under the same rules as [check]. An
     * unrestricted scope returns [ids] with no database read. A restricted scope costs one `findByIds`, with the
     * root and tag halves applied to the same rows. A missing item is not admitted, and a lookup fault admits
     * nothing (fail closed).
     */
    suspend fun admitted(
        scope: AccessScope,
        ids: Set<UUID>,
        repo: WorkItemRepository
    ): Set<UUID> {
        if (scope.isUnrestricted || ids.isEmpty()) return ids
        try {
            val rootIds = scope.rootIds
            return repo
                .findByIds(ids)
                .filter { item ->
                    (rootIds == null || inRoots(item, rootIds)) && (scope.tagsInclude.isEmpty() || hasListedTag(item, scope))
                }.mapTo(LinkedHashSet()) { it.id }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Scope check failed to read {} items: {}", ids.size, e.message)
            return emptySet()
        }
    }

    /**
     * The subset of [ids] that name an EXISTING item which is not a stamped depth-0 root (`parent_id` set, or
     * `root_id` not its own id). A missing id is not returned: a scope anchor may be created after the credential
     * (the project root is created through the running server once a token is provisioned) and admits nothing until
     * it exists. Used to validate `scope.root_ids` anchors; one `findByIds` read.
     */
    suspend fun nonRoots(
        ids: Set<UUID>,
        repo: WorkItemRepository
    ): Set<UUID> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            repo
                .findByIds(ids)
                .filter { it.parentId != null || it.rootId != it.id }
                .mapTo(LinkedHashSet()) { it.id }
        }

    /** An item's CSV `tags` column as a set: elements trimmed, empty ones dropped; matching is exact and case-sensitive. */
    private fun splitTags(tags: String?): Set<String> =
        tags
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /** The `forbidden` error for [scope]; `required` is the sorted listed values joined by `,`. */
    fun forbidden(
        scope: ForbiddenScope,
        listed: Collection<String>
    ): DomainError = forbidden(scope, listed.sorted().joinToString(","))

    /** The `forbidden` error for [scope] naming [required] verbatim. */
    fun forbidden(
        scope: ForbiddenScope,
        required: String
    ): DomainError =
        DomainError(
            code = ErrorCode.FORBIDDEN,
            message = "The credential does not grant $required for the ${scope.name.lowercase()} scope.",
            detail = ErrorDetail.Forbidden(scope, required),
            fixArgs = mapOf("scope" to scope.name.lowercase(), "required" to required)
        )
}

package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Transport-neutral twin of the REST `enforceScopeForItem`: decides whether a call's [AccessScope] admits
 * one item. Tool handlers call it for every item an op touches; the dispatcher cannot, because only a
 * handler knows which items an op reads or writes.
 *
 * Rules: an unrestricted scope is admitted with no database read. With a root restriction the item or any
 * ancestor must be a listed root; with a tag restriction the item must carry one of the listed tags (the
 * CSV `tags` column split, each element trimmed, exact case-sensitive match; no ancestor walk). A missing
 * item, or a lookup fault, under a restricted scope is `forbidden` (fail closed, so scope is no existence
 * oracle).
 */
object ScopeGuard {
    private val logger = LoggerFactory.getLogger(ScopeGuard::class.java)

    suspend fun check(
        scope: AccessScope,
        itemId: UUID,
        repo: WorkItemRepository
    ): Outcome<Unit> {
        if (scope.isUnrestricted) return Outcome.Ok(Unit)

        val rootIds = scope.rootIds
        if (rootIds != null) {
            val chain =
                try {
                    repo.findAncestorChains(setOf(itemId))[itemId] ?: emptyList()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logger.warn("Scope check failed to read the ancestors of {}: {}", itemId, e.message)
                    return Outcome.Err(forbidden(ForbiddenScope.ROOT, rootIds.map { it.toString() }))
                }
            val inChain = chain.map { it.id }.toSet() + itemId
            if (inChain.none { it in rootIds }) return Outcome.Err(forbidden(ForbiddenScope.ROOT, rootIds.map { it.toString() }))
        }

        if (scope.tagsInclude.isNotEmpty()) {
            val item =
                try {
                    repo.getById(itemId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logger.warn("Scope check failed to read item {}: {}", itemId, e.message)
                    return Outcome.Err(forbidden(ForbiddenScope.TAG, scope.tagsInclude))
                } ?: return Outcome.Err(forbidden(ForbiddenScope.TAG, scope.tagsInclude))
            val tags =
                item.tags
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
            if (tags.none { it in scope.tagsInclude }) return Outcome.Err(forbidden(ForbiddenScope.TAG, scope.tagsInclude))
        }
        return Outcome.Ok(Unit)
    }

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

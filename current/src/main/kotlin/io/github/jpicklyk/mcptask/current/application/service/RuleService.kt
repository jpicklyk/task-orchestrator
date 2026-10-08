package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import java.time.Instant
import java.util.UUID

/**
 * Transport-agnostic read pipeline for git-tracked rule text, stored as `rule/<key>` plan
 * documents (see [PlanDocumentStore]) -- the same table `manage_plan_documents`/`PlanDocumentService`
 * already write to, so `query_rules` and the `rules` REST routes are pure READ surfaces over that
 * store with no new table and no migration.
 *
 * Depends only on domain repositories ([PlanDocumentStore], [WorkItemRepository]) -- no
 * infrastructure import -- so both the MCP `query_rules` tool and the REST `rules` routes can share
 * one instance without violating the `domain -> application -> infrastructure -> interfaces`
 * layering rule ([io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext] hands
 * out `RepositoryProvider`-backed domain-repository instances, never the provider or the
 * infrastructure types themselves).
 *
 * Pipeline for both [get] and [list]: root exists -> root is depth-0 -> (for [get]) document
 * exists at `rule/<key>`. Callers resolving a skill-pointer (item mode) resolve the item's
 * effective schema themselves (via `ToolExecutionContext.resolveSchema`, which this class does not
 * depend on) and pass the resolved rule key straight to [get].
 */
class RuleService(
    private val planDocumentRepository: PlanDocumentStore,
    private val workItemRepository: WorkItemRepository,
) {
    /**
     * Reads the rule document at `rule/<key>` under [rootId]. See [RuleGetResult] for the possible
     * outcomes. [key] is assumed to already have been validated against [KEY_PATTERN] by the caller
     * (both `QueryRulesTool.validateParams` and the REST route check this before calling in, so the
     * check stays 400/VALIDATION_ERROR at the transport boundary rather than a generic service error).
     */
    suspend fun get(
        rootId: UUID,
        key: String,
    ): RuleGetResult {
        // Only a genuine not-found (null) is a 404; a store fault is a db_error (500).
        val item =
            legacyRead({ return RuleGetResult.RepositoryError(it) }) { workItemRepository.getById(rootId) }
                ?: return RuleGetResult.RootNotFound(rootId)
        if (item.depth != 0) {
            return RuleGetResult.NotDepthZero(rootId, item.depth)
        }

        val slug = RULE_SLUG_PREFIX + key
        return run {
            val result = legacyRead({ return@run RuleGetResult.RepositoryError(it) }) { planDocumentRepository.get(rootId, slug) }
            val document = result ?: return RuleGetResult.RuleNotFound(rootId, key)
            RuleGetResult.Success(document)
        }
    }

    /**
     * Lists every `rule/<key>` document under [rootId] whose key matches [KEY_PATTERN] -- slugs
     * that fail the grammar (stashable via `manage_plan_documents`, per P5, but never served) are
     * silently excluded, as are non-`rule/` slugs; `status` is ignored (both PENDING and ADOPTED
     * rule documents are listed and served -- adoption freezes a rule's content, it does not hide
     * it). Sorted by key ascending. See [RuleListResult] for the possible outcomes.
     */
    suspend fun list(rootId: UUID): RuleListResult {
        // Only a genuine not-found (null) is a 404; a store fault is a db_error (500).
        val item =
            legacyRead({ return RuleListResult.RepositoryError(it) }) { workItemRepository.getById(rootId) }
                ?: return RuleListResult.RootNotFound(rootId)
        if (item.depth != 0) {
            return RuleListResult.NotDepthZero(rootId, item.depth)
        }

        return run {
            val result =
                legacyRead({ return@run RuleListResult.RepositoryError(it) }) { planDocumentRepository.list(rootId, status = null) }
            val rules =
                result
                    .filter { it.slug.startsWith(RULE_SLUG_PREFIX) }
                    .mapNotNull { summary ->
                        val key = summary.slug.removePrefix(RULE_SLUG_PREFIX)
                        if (!KEY_PATTERN.matches(key)) return@mapNotNull null
                        RuleSummary(key, summary.contentHash, summary.modifiedAt)
                    }.sortedBy { it.key }
            RuleListResult.Success(rules)
        }
    }

    companion object {
        /** Plan-document slug prefix a rule document is stored under: `rule/<key>`. */
        const val RULE_SLUG_PREFIX = "rule/"

        /**
         * Hard cap on a rule document body's UTF-8 byte size, enforced by
         * [PlanDocumentService.stash] for any slug starting with [RULE_SLUG_PREFIX] (tighter than
         * that service's general 64 KiB cap for non-rule plan documents).
         */
        const val MAX_RULE_BODY_BYTES = 16384

        /**
         * Rule-key grammar: lowercase alphanumeric, `.`, `_`, `-`; must start with an alphanumeric;
         * max 100 characters. No `/`, uppercase, or `:` -- a key is a single path segment usable
         * verbatim in a REST URL and as a plan-document slug suffix.
         */
        val KEY_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
    }
}

/** One row of [RuleService.list] -- metadata only, never the body. */
data class RuleSummary(
    val key: String,
    val rulesVersion: String,
    val updatedAt: Instant,
)

/** Outcome of [RuleService.get]. */
sealed class RuleGetResult {
    /** The rule document was found. */
    data class Success(
        val document: PlanDocument,
    ) : RuleGetResult()

    /** No WorkItem exists for [rootId]. */
    data class RootNotFound(
        val rootId: UUID,
    ) : RuleGetResult()

    /** [rootId] resolved to a WorkItem, but it is not depth-0 (rules anchor to project roots only). */
    data class NotDepthZero(
        val rootId: UUID,
        val depth: Int,
    ) : RuleGetResult()

    /** [rootId] is a valid depth-0 root, but no `rule/<key>` document exists there. */
    data class RuleNotFound(
        val rootId: UUID,
        val key: String,
    ) : RuleGetResult()

    /** The repository call itself failed. */
    data class RepositoryError(
        val message: String,
    ) : RuleGetResult()
}

/** Outcome of [RuleService.list]. */
sealed class RuleListResult {
    /** The (possibly empty) list of rule summaries under the root. */
    data class Success(
        val rules: List<RuleSummary>,
    ) : RuleListResult()

    /** No WorkItem exists for [rootId]. */
    data class RootNotFound(
        val rootId: UUID,
    ) : RuleListResult()

    /** [rootId] resolved to a WorkItem, but it is not depth-0 (rules anchor to project roots only). */
    data class NotDepthZero(
        val rootId: UUID,
        val depth: Int,
    ) : RuleListResult()

    /** The repository call itself failed. */
    data class RepositoryError(
        val message: String,
    ) : RuleListResult()
}

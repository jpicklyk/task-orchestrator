package io.github.jpicklyk.mcptask.current.application.knowledge.document

import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind

/** A document address within one root: its kind and its slug. */
data class KindAndSlug(
    val kind: DocumentKind,
    val slug: String
)

/**
 * The ONE mapping between a 3.x plan-document slug and a 4.0 document address.
 *
 * A slug starting with exactly [RULE_PREFIX] (`rule/`, case-sensitive) is the RULE-kind document named by the rest of
 * the slug; every other slug is the PLAN-kind document with the same slug. The migration (`V23`) applies the same
 * split in SQL with `substr(slug, 1, 5) = 'rule/'`.
 */
object PlanSlugs {
    /** The 3.x slug prefix of a rule document (`rule/`). */
    const val RULE_PREFIX = RuleService.RULE_SLUG_PREFIX

    /** Maps a 3.x slug to its document address. */
    fun toKindAndSlug(slug: String): KindAndSlug =
        if (slug.startsWith(RULE_PREFIX)) {
            KindAndSlug(DocumentKind.RULE, slug.substring(RULE_PREFIX.length))
        } else {
            KindAndSlug(DocumentKind.PLAN, slug)
        }

    /**
     * Maps a document address back to its 3.x slug: `rule/<slug>` for [DocumentKind.RULE], the slug unchanged for any
     * other kind.
     */
    fun fromKindAndSlug(
        kind: DocumentKind,
        slug: String
    ): String = if (kind == DocumentKind.RULE) RULE_PREFIX + slug else slug
}

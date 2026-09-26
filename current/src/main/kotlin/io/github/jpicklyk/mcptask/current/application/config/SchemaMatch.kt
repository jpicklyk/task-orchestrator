package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * A resolved [WorkItemSchema] together with which layer ([source]) supplied its BASE schema (the
 * type/tag lookup step) and that layer's config [fingerprint] (null when the supplying layer has no
 * fingerprint available, e.g. no global config loaded). Trait notes merged into [schema] may
 * originate from a different layer than [source]: [source] and [fingerprint] describe the base
 * schema's provenance only.
 */
data class SchemaMatch(
    val schema: WorkItemSchema,
    val source: ConfigSource,
    val fingerprint: String?,
)

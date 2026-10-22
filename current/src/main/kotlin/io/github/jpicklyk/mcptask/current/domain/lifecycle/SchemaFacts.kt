package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.util.UUID

/**
 * The schema facts the transition table depends on. [lifecycle] is never [LifecycleMode.NONE]: whether an item is
 * lifecycle-free is carried by its null role, so a role-bearing item that resolves to a `lifecycle: none` schema is
 * read as AUTO (the 3.x reading of an unknown value). Build from a schema with [of].
 */
data class SchemaFacts(
    val hasReviewPhase: Boolean,
    val lifecycle: LifecycleMode
) {
    companion object {
        /** An item with no resolved schema: no review phase, AUTO lifecycle. */
        val SCHEMA_FREE: SchemaFacts = SchemaFacts(hasReviewPhase = false, lifecycle = LifecycleMode.AUTO)

        /** The facts of [schema] (null: [SCHEMA_FREE]), its lifecycle folded through [roleBearing]. */
        fun of(schema: WorkItemSchema?): SchemaFacts =
            if (schema == null) SCHEMA_FREE else SchemaFacts(schema.hasReviewPhase(), roleBearing(schema.lifecycleMode))

        /** [mode] as a role-bearing item reads it: [LifecycleMode.NONE] maps to [LifecycleMode.AUTO], any other mode is kept. */
        fun roleBearing(mode: LifecycleMode): LifecycleMode = if (mode == LifecycleMode.NONE) LifecycleMode.AUTO else mode
    }
}

/** The facts about a (new or current) parent that structural rules depend on. [role] is null for a lifecycle-free parent. */
data class ParentFacts(
    val id: UUID,
    val role: Role?,
    val lifecycle: LifecycleMode
)

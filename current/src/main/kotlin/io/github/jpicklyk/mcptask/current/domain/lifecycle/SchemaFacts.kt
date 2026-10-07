package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** The schema facts the transition table depends on. */
data class SchemaFacts(
    val hasReviewPhase: Boolean,
    val lifecycle: LifecycleMode
) {
    companion object {
        /** An item with no resolved schema: no review phase, AUTO lifecycle. */
        val SCHEMA_FREE: SchemaFacts = SchemaFacts(hasReviewPhase = false, lifecycle = LifecycleMode.AUTO)
    }
}

/** The facts about a (new or current) parent that structural rules depend on. */
data class ParentFacts(
    val id: UUID,
    val role: Role,
    val lifecycle: LifecycleMode
)

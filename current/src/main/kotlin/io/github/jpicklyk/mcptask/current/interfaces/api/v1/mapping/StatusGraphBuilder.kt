package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.domain.lifecycle.SchemaFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphTypeDto

/**
 * Derives the static status-transition graph from registered work-item schemas.
 *
 * The graph is derived from the transition table ([TransitionTable.cell]) for every (role, user
 * trigger) pair, per registered schema type (its review phase and lifecycle mode are the
 * [SchemaFacts]). A `To` cell emits the target role; the `ToPrevious` cell of (blocked, resume)
 * emits the sentinel `"<previousRole>"` so dashboards know they must read the live item to resolve
 * the target; an invalid cell is omitted.
 *
 * Stateless over the RESOLVED schemas it is handed: it reads no config and caches nothing. A caller passes the
 * schema map of its own scope ([buildStatusGraph]) or the one resolved schema of one item ([rowFor]); a null schema
 * is a schema-free item ([SchemaFacts.SCHEMA_FREE]).
 *
 * Lives in the interfaces layer because it returns `interfaces/api/v1/dto` `*Dto` types — the
 * application layer must not depend on interface DTOs.
 */
class StatusGraphBuilder {
    companion object {
        /** The ordered list of roles that appear as rows in the graph. */
        val GRAPH_ROLES: List<Role> =
            listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED, Role.TERMINAL)

        /** The sentinel emitted for the (blocked, resume) cell. */
        const val PREVIOUS_ROLE_SENTINEL = "<previousRole>"
    }

    /**
     * Builds the status-transition graph for [schemas]: the schema map the caller resolved for its scope (global or
     * per-root layered). Graph `types` follow [schemas]' iteration order.
     */
    fun buildStatusGraph(schemas: Map<String, WorkItemSchema>): StatusGraphDto {
        val triggers = Trigger.User.entries.sortedBy { it.wire }

        val types =
            schemas.entries.map { (typeName, schema) ->
                val hasReview = schema.hasReviewPhase()
                val transitions = buildTransitionsForType(SchemaFacts(hasReview, schema.lifecycleMode), triggers)
                StatusGraphTypeDto(
                    type = typeName,
                    lifecycleMode = schema.lifecycleMode.name.lowercase(),
                    hasReviewPhase = hasReview,
                    transitions = transitions,
                )
            }

        return StatusGraphDto(
            roles = GRAPH_ROLES.map { it.name.lowercase() },
            triggers = triggers.map { it.wire },
            types = types,
        )
    }

    /**
     * The row of [role] in the status graph of the ONE resolved [schema] (null = schema-free): each user trigger the
     * transition table allows from [role], by wire name, to its target role name, or [PREVIOUS_ROLE_SENTINEL] for the
     * return-to-previous cell. Triggers the table rejects from [role] are absent.
     */
    fun rowFor(
        schema: WorkItemSchema?,
        role: Role,
    ): Map<String, String> {
        val facts = schema?.let { SchemaFacts(it.hasReviewPhase(), it.lifecycleMode) } ?: SchemaFacts.SCHEMA_FREE
        val row = LinkedHashMap<String, String>()
        for (trigger in Trigger.User.entries.sortedBy { it.wire }) {
            resolveCell(role, trigger, facts)?.let { row[trigger.wire] = it }
        }
        return row
    }

    private fun buildTransitionsForType(
        facts: SchemaFacts,
        triggers: List<Trigger.User>,
    ): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()

        for (role in GRAPH_ROLES) {
            val rowKey = role.name.lowercase()
            for (trigger in triggers) {
                val targetCell = resolveCell(role, trigger, facts) ?: continue
                result.getOrPut(rowKey) { mutableMapOf() }[trigger.wire] = targetCell
            }
        }

        return result
    }

    /**
     * The table cell for (role, trigger) as a target string: the target role, [PREVIOUS_ROLE_SENTINEL]
     * for a return-to-previous cell, or `null` (omitted) for an invalid cell.
     */
    private fun resolveCell(
        role: Role,
        trigger: Trigger.User,
        facts: SchemaFacts,
    ): String? =
        when (val cell = TransitionTable.cell(role, trigger, facts)) {
            is TransitionTable.Cell.To -> cell.role.name.lowercase()
            TransitionTable.Cell.ToPrevious -> PREVIOUS_ROLE_SENTINEL
            TransitionTable.Cell.Invalid, TransitionTable.Cell.NotApplicable -> null
        }
}

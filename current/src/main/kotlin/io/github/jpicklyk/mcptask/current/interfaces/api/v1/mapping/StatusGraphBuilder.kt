package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
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
 * The result is cached in memory keyed by config fingerprint. When the fingerprint changes
 * (config reload), the cache is invalidated and the graph is rebuilt on the next request.
 *
 * Lives in the interfaces layer because it returns `interfaces/api/v1/dto` `*Dto` types — the
 * application layer must not depend on interface DTOs.
 */
class StatusGraphBuilder(
    private val schemaService: WorkItemSchemaService,
) {
    companion object {
        /** The ordered list of roles that appear as rows in the graph. */
        val GRAPH_ROLES: List<Role> =
            listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED, Role.TERMINAL)

        /** The sentinel emitted for the (blocked, resume) cell. */
        const val PREVIOUS_ROLE_SENTINEL = "<previousRole>"
    }

    @Volatile
    private var cache: Pair<String?, StatusGraphDto>? = null

    /**
     * Returns the current status graph, rebuilding it when the config fingerprint has changed.
     */
    fun getStatusGraph(): StatusGraphDto {
        val fingerprint = schemaService.getConfigFingerprint()
        val cached = cache
        if (cached != null && cached.first == fingerprint) {
            return cached.second
        }
        val graph = buildGraph()
        cache = Pair(fingerprint, graph)
        return graph
    }

    private fun buildGraph(): StatusGraphDto = buildStatusGraph(schemaService.getAllSchemas())

    /**
     * Builds the status-transition graph for [schemas] directly, bypassing the fingerprint cache —
     * used by callers (e.g. the per-root effective config view) that already hold a resolved,
     * possibly per-root-layered schema map and need a fresh, uncached graph for it. Graph `types`
     * follow [schemas]' iteration order. [getStatusGraph] (global-only, fingerprint-cached) is
     * unchanged.
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

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.StatusGraphTypeDto

/**
 * Derives the static status-transition graph from registered work-item schemas.
 *
 * The graph is built by calling the pure [RoleTransitionHandler.resolveTransition] overload
 * (role + trigger, no WorkItem) for every (role, trigger) combination across all registered
 * schema types. Two overrides are applied on top of the pure result:
 *
 * 1. **(work, start)**: the pure overload assumes `hasReviewPhase=true` and returns REVIEW.
 *    When the schema's `hasReviewPhase()==false` this cell is rewritten to `"terminal"`.
 *
 * 2. **(blocked, resume)**: the pure overload returns `success=false` because it cannot
 *    determine `previousRole`. We always emit the sentinel `"<previousRole>"` for this cell
 *    so dashboards know they must read the live item to resolve the target.
 *
 * The result is cached in memory keyed by config fingerprint. When the fingerprint changes
 * (config reload), the cache is invalidated and the graph is rebuilt on the next request.
 *
 * Lives in the interfaces layer because it returns `interfaces/api/v1/dto` `*Dto` types — the
 * application layer must not depend on interface DTOs.
 */
class StatusGraphBuilder(
    private val schemaService: WorkItemSchemaService,
    private val handler: RoleTransitionHandler = RoleTransitionHandler(),
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

    private fun buildGraph(): StatusGraphDto {
        val triggers = RoleTransitionHandler.USER_TRIGGERS.toList().sorted()
        val schemas = schemaService.getAllSchemas()

        val types =
            schemas.entries.map { (typeName, schema) ->
                val hasReview = schema.hasReviewPhase()
                val transitions = buildTransitionsForType(hasReview, triggers)
                StatusGraphTypeDto(
                    type = typeName,
                    lifecycleMode = schema.lifecycleMode.name.lowercase(),
                    hasReviewPhase = hasReview,
                    transitions = transitions,
                )
            }

        return StatusGraphDto(
            roles = GRAPH_ROLES.map { it.name.lowercase() },
            triggers = triggers,
            types = types,
        )
    }

    private fun buildTransitionsForType(
        hasReviewPhase: Boolean,
        triggers: List<String>,
    ): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()

        for (role in GRAPH_ROLES) {
            val rowKey = role.name.lowercase()
            for (trigger in triggers) {
                val targetCell = resolveCell(role, trigger, hasReviewPhase) ?: continue
                result.getOrPut(rowKey) { mutableMapOf() }[trigger] = targetCell
            }
        }

        return result
    }

    /**
     * Resolves a single (role, trigger) cell to a target role string,
     * or returns `null` if the transition is invalid (cell should be omitted).
     *
     * Special cases:
     * - **(blocked, resume)** always emits [PREVIOUS_ROLE_SENTINEL].
     * - **(work, start)** result is overridden based on [hasReviewPhase].
     */
    private fun resolveCell(
        role: Role,
        trigger: String,
        hasReviewPhase: Boolean,
    ): String? {
        // Special case: (blocked, resume) — pure overload can't resolve without WorkItem
        if (role == Role.BLOCKED && trigger == "resume") {
            return PREVIOUS_ROLE_SENTINEL
        }

        val resolution = handler.resolveTransition(role, trigger)
        if (!resolution.success || resolution.targetRole == null) return null

        // Override (work, start): pure result is REVIEW (assumes hasReviewPhase=true),
        // but we need to respect the schema's actual hasReviewPhase flag.
        return if (role == Role.WORK && trigger == "start" && !hasReviewPhase) {
            Role.TERMINAL.name.lowercase()
        } else {
            resolution.targetRole.name.lowercase()
        }
    }
}

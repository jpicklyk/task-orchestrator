package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Records a role transition event — when an entity moves from one semantic role to another.
 *
 * Role transitions are recorded at request_transition time, only when the entity's role
 * actually changes (e.g., queue→work, work→review). Same-role status changes within a
 * workflow (e.g., pending→backlog, both "queue") do NOT create a transition record.
 *
 * Use cases:
 * - Workflow analytics: time-in-role metrics, bottleneck detection
 * - Audit trail: when did entity enter/exit each phase
 * - Reporting: role-based velocity and throughput
 *
 * @property id Unique identifier for this transition record
 * @property entityId The task, feature, or project that transitioned
 * @property entityType "task", "feature", or "project"
 * @property fromRole Previous role (queue, work, review, blocked, terminal)
 * @property toRole New role after transition
 * @property fromStatus The specific status before transition (e.g., "in-progress")
 * @property toStatus The specific status after transition (e.g., "testing")
 * @property transitionedAt When the transition occurred
 * @property trigger The trigger that caused the transition (e.g., "start", "complete")
 * @property summary Optional note about why the transition happened
 */
data class RoleTransition(
    val id: UUID = UUID.randomUUID(),
    val entityId: UUID,
    val entityType: String,
    val fromRole: String,
    val toRole: String,
    val fromStatus: String,
    val toStatus: String,
    val transitionedAt: Instant = Instant.now(),
    val trigger: String? = null,
    val summary: String? = null
) {
    init {
        require(entityType in listOf("task", "feature", "project")) {
            "entityType must be 'task', 'feature', or 'project', got: $entityType"
        }
        require(fromRole.isNotBlank()) { "fromRole must not be blank" }
        require(toRole.isNotBlank()) { "toRole must not be blank" }
        require(fromStatus.isNotBlank()) { "fromStatus must not be blank" }
        require(toStatus.isNotBlank()) { "toStatus must not be blank" }
        require(fromRole != toRole) { "fromRole and toRole must be different (both are '$fromRole')" }
    }
}

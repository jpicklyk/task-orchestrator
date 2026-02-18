package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.RoleTransitionRepository
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of RoleTransitionRepository for unit tests.
 */
class MockRoleTransitionRepository : RoleTransitionRepository {

    private val transitions = ConcurrentHashMap<UUID, RoleTransition>()

    // Custom behaviors for testing
    var nextCreateResult: ((RoleTransition) -> Result<RoleTransition>)? = null

    override suspend fun create(transition: RoleTransition): Result<RoleTransition> {
        val customResult = nextCreateResult?.invoke(transition)
        if (customResult != null) {
            return customResult
        }

        transitions[transition.id] = transition
        return Result.Success(transition)
    }

    override suspend fun findByEntityId(
        entityId: UUID,
        entityType: String?
    ): Result<List<RoleTransition>> {
        val filtered = transitions.values.filter { it.entityId == entityId }
        val result = if (entityType != null) {
            filtered.filter { it.entityType == entityType }
        } else {
            filtered
        }
        return Result.Success(result.sortedBy { it.transitionedAt })
    }

    override suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        entityType: String?,
        role: String?
    ): Result<List<RoleTransition>> {
        var filtered = transitions.values.filter {
            it.transitionedAt >= startTime && it.transitionedAt <= endTime
        }

        if (entityType != null) {
            filtered = filtered.filter { it.entityType == entityType }
        }

        if (role != null) {
            filtered = filtered.filter { it.toRole == role }
        }

        return Result.Success(filtered.sortedBy { it.transitionedAt })
    }

    override suspend fun deleteByEntityId(entityId: UUID): Result<Int> {
        val toDelete = transitions.values.filter { it.entityId == entityId }
        toDelete.forEach { transitions.remove(it.id) }
        return Result.Success(toDelete.size)
    }

    /**
     * Test helper method to add a transition directly to the repository
     */
    fun addTransition(transition: RoleTransition) {
        transitions[transition.id] = transition
    }

    /**
     * Test helper method to clear all transitions from the repository
     */
    fun clearTransitions() {
        transitions.clear()
        nextCreateResult = null
    }

    /**
     * Get all transitions for testing purposes
     */
    fun getAllTransitions(): List<RoleTransition> {
        return transitions.values.toList()
    }
}

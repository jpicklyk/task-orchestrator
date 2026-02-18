package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import java.time.Instant
import java.util.UUID

/**
 * Repository interface for persisting and querying role transition records.
 */
interface RoleTransitionRepository {
    /**
     * Record a new role transition.
     */
    suspend fun create(transition: RoleTransition): Result<RoleTransition>

    /**
     * Find all role transitions for a specific entity.
     * @param entityId The entity to query
     * @param entityType Optional filter by entity type
     * @return Transitions ordered by transitionedAt ascending
     */
    suspend fun findByEntityId(
        entityId: UUID,
        entityType: String? = null
    ): Result<List<RoleTransition>>

    /**
     * Find role transitions within a time range.
     * @param startTime Start of time range (inclusive)
     * @param endTime End of time range (inclusive)
     * @param entityType Optional filter by entity type
     * @param role Optional filter by toRole (find entries into a specific role)
     * @return Transitions ordered by transitionedAt ascending
     */
    suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        entityType: String? = null,
        role: String? = null
    ): Result<List<RoleTransition>>

    /**
     * Delete all transition records for a specific entity.
     * Used when an entity is deleted.
     */
    suspend fun deleteByEntityId(entityId: UUID): Result<Int>
}

package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import java.time.Instant
import java.util.UUID

interface RoleTransitionRepository {
    suspend fun create(transition: RoleTransition): RoleTransition

    suspend fun findByItemId(
        itemId: UUID,
        limit: Int = 50,
        offset: Int = 0
    ): List<RoleTransition>

    suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        role: String? = null,
        limit: Int = 50
    ): List<RoleTransition>

    suspend fun findSince(
        since: Instant,
        limit: Int = 50
    ): List<RoleTransition>

    suspend fun deleteByItemId(itemId: UUID): Int
}

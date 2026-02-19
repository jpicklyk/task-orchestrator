package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import java.time.Instant
import java.util.UUID

interface RoleTransitionRepository {
    suspend fun create(transition: RoleTransition): Result<RoleTransition>
    suspend fun findByItemId(itemId: UUID, limit: Int = 50): Result<List<RoleTransition>>
    suspend fun findByTimeRange(startTime: Instant, endTime: Instant, role: String? = null, limit: Int = 50): Result<List<RoleTransition>>
    suspend fun findSince(since: Instant, limit: Int = 50): Result<List<RoleTransition>>
    suspend fun deleteByItemId(itemId: UUID): Result<Int>
}

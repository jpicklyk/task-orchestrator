package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.WorkSessionRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.WorkSessionsTable
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.v1.core.transactions.transaction
import org.jetbrains.exposed.v1.sql.*
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.lessEq
import java.time.Instant
import java.util.*

/**
 * SQLite implementation of WorkSessionRepository using Exposed ORM.
 */
class SQLiteWorkSessionRepository : WorkSessionRepository {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun create(session: WorkSession): Result<WorkSession> = try {
        transaction {
            WorkSessionsTable.insert { row ->
                row[id] = session.sessionId
                row[clientId] = session.instanceInfo.clientId
                row[clientVersion] = session.instanceInfo.version
                row[hostname] = session.instanceInfo.hostname
                row[userContext] = session.instanceInfo.userContext
                row[startedAt] = session.startedAt
                row[lastActivity] = session.lastActivity
                row[capabilities] = json.encodeToString(session.capabilities.toList())
                row[gitWorktreeInfo] = session.gitWorktree?.let { json.encodeToString(it) }
                row[activeAssignments] = encodeActiveAssignments(session)
                row[metadata] = "{}" // Empty JSON object for now
                row[createdAt] = session.startedAt
            }
            Result.Success(session)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to create session: ${e.message}", e)
    }
    
    override suspend fun getById(sessionId: String): Result<WorkSession?> = try {
        transaction {
            val row = WorkSessionsTable.select { WorkSessionsTable.id eq sessionId }.singleOrNull()
            Result.Success(row?.let { mapRowToWorkSession(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get session by ID: ${e.message}", e)
    }
    
    override suspend fun getActiveSessions(): Result<List<WorkSession>> = try {
        transaction {
            val thresholdTime = Instant.now().minusSeconds(7200) // 2 hours ago
            val rows = WorkSessionsTable.select {
                WorkSessionsTable.lastActivity greater thresholdTime
            }.toList()
            
            Result.Success(rows.map { mapRowToWorkSession(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get active sessions: ${e.message}", e)
    }
    
    override suspend fun getAllSessions(): Result<List<WorkSession>> = try {
        transaction {
            val rows = WorkSessionsTable.selectAll().toList()
            Result.Success(rows.map { mapRowToWorkSession(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get all sessions: ${e.message}", e)
    }
    
    override suspend fun update(session: WorkSession): Result<WorkSession> = try {
        transaction {
            val updatedRows = WorkSessionsTable.update({ WorkSessionsTable.id eq session.sessionId }) { row ->
                row[clientId] = session.instanceInfo.clientId
                row[clientVersion] = session.instanceInfo.version
                row[hostname] = session.instanceInfo.hostname
                row[userContext] = session.instanceInfo.userContext
                row[startedAt] = session.startedAt
                row[lastActivity] = session.lastActivity
                row[capabilities] = json.encodeToString(session.capabilities.toList())
                row[gitWorktreeInfo] = session.gitWorktree?.let { json.encodeToString(it) }
                row[activeAssignments] = encodeActiveAssignments(session)
            }
            
            if (updatedRows > 0) {
                Result.Success(session)
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to update session: ${e.message}", e)
    }
    
    override suspend fun updateActivity(sessionId: String, activityTime: Instant): Result<WorkSession> = try {
        transaction {
            val updatedRows = WorkSessionsTable.update({ WorkSessionsTable.id eq sessionId }) { row ->
                row[lastActivity] = activityTime
            }
            
            if (updatedRows > 0) {
                val updatedRow = WorkSessionsTable.select { WorkSessionsTable.id eq sessionId }.single()
                Result.Success(mapRowToWorkSession(updatedRow))
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to update session activity: ${e.message}", e)
    }
    
    override suspend fun delete(sessionId: String): Result<Unit> = try {
        transaction {
            val deletedRows = WorkSessionsTable.deleteWhere { WorkSessionsTable.id eq sessionId }
            if (deletedRows > 0) {
                Result.Success(Unit)
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to delete session: ${e.message}", e)
    }
    
    override suspend fun deleteInactiveSessions(inactiveThresholdMinutes: Long): Result<Int> = try {
        transaction {
            val thresholdTime = Instant.now().minusSeconds(inactiveThresholdMinutes * 60)
            val deletedRows = WorkSessionsTable.deleteWhere {
                WorkSessionsTable.lastActivity lessEq thresholdTime
            }
            Result.Success(deletedRows)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to delete inactive sessions: ${e.message}", e)
    }
    
    override suspend fun getSessionsWorkingOnEntity(entityType: String, entityId: UUID): Result<List<WorkSession>> = try {
        transaction {
            val sessions = getAllSessions().getOrThrow()
            val matchingSessions = sessions.filter { session ->
                when (entityType.uppercase()) {
                    "PROJECT" -> session.activeProjects.contains(entityId)
                    "FEATURE" -> session.activeFeatures.contains(entityId)
                    "TASK" -> session.activeTasks.contains(entityId)
                    else -> false
                }
            }
            Result.Success(matchingSessions)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get sessions working on entity: ${e.message}", e)
    }
    
    override suspend fun getSessionsWithWorktree(scope: LockScope, entityId: UUID): Result<List<WorkSession>> = try {
        transaction {
            val sessions = getAllSessions().getOrThrow()
            val matchingSessions = sessions.filter { session ->
                session.gitWorktree?.let { worktree ->
                    worktree.assignedScope == scope && worktree.assignedEntityId == entityId
                } ?: false
            }
            Result.Success(matchingSessions)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get sessions with worktree: ${e.message}", e)
    }
    
    override suspend fun isSessionActive(sessionId: String): Result<Boolean> = try {
        transaction {
            val thresholdTime = Instant.now().minusSeconds(7200) // 2 hours
            val session = WorkSessionsTable.select {
                (WorkSessionsTable.id eq sessionId) and
                (WorkSessionsTable.lastActivity greater thresholdTime)
            }.singleOrNull()
            
            Result.Success(session != null)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to check if session is active: ${e.message}", e)
    }
    
    override suspend fun updateCapabilities(sessionId: String, capabilities: Set<String>): Result<WorkSession> = try {
        transaction {
            val updatedRows = WorkSessionsTable.update({ WorkSessionsTable.id eq sessionId }) { row ->
                row[WorkSessionsTable.capabilities] = json.encodeToString(capabilities.toList())
            }
            
            if (updatedRows > 0) {
                val updatedRow = WorkSessionsTable.select { WorkSessionsTable.id eq sessionId }.single()
                Result.Success(mapRowToWorkSession(updatedRow))
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to update capabilities: ${e.message}", e)
    }
    
    override suspend fun setGitWorktree(sessionId: String, worktreeInfo: GitWorktreeInfo): Result<WorkSession> = try {
        transaction {
            val updatedRows = WorkSessionsTable.update({ WorkSessionsTable.id eq sessionId }) { row ->
                row[gitWorktreeInfo] = json.encodeToString(worktreeInfo)
            }
            
            if (updatedRows > 0) {
                val updatedRow = WorkSessionsTable.select { WorkSessionsTable.id eq sessionId }.single()
                Result.Success(mapRowToWorkSession(updatedRow))
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to set git worktree: ${e.message}", e)
    }
    
    override suspend fun clearGitWorktree(sessionId: String): Result<WorkSession> = try {
        transaction {
            val updatedRows = WorkSessionsTable.update({ WorkSessionsTable.id eq sessionId }) { row ->
                row[gitWorktreeInfo] = null
            }
            
            if (updatedRows > 0) {
                val updatedRow = WorkSessionsTable.select { WorkSessionsTable.id eq sessionId }.single()
                Result.Success(mapRowToWorkSession(updatedRow))
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Session not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to clear git worktree: ${e.message}", e)
    }
    
    override suspend fun getSessionStatistics(): Result<SessionStatistics> = try {
        transaction {
            val allSessions = getAllSessions().getOrThrow()
            val now = Instant.now()
            val twoHoursAgo = now.minusSeconds(7200)
            
            val activeSessions = allSessions.filter { it.lastActivity.isAfter(twoHoursAgo) }
            val idleSessions = allSessions.filter { 
                it.lastActivity.isBefore(twoHoursAgo) && it.lastActivity.isAfter(now.minusSeconds(14400))
            }
            val expiredSessions = allSessions.filter { it.lastActivity.isBefore(now.minusSeconds(14400)) }
            
            val sessionsByClient = allSessions.groupBy { it.instanceInfo.clientId }
                .mapValues { it.value.size }
            
            val sessionsWithWorktrees = allSessions.count { it.gitWorktree != null }
            
            val averageDuration = if (allSessions.isNotEmpty()) {
                allSessions.map { session ->
                    java.time.Duration.between(session.startedAt, session.lastActivity).toMinutes().toDouble()
                }.average()
            } else 0.0
            
            val mostActiveSessions = allSessions.map { session ->
                val totalEntities = session.activeTasks.size + session.activeFeatures.size + session.activeProjects.size
                session.sessionId to totalEntities
            }.sortedByDescending { it.second }.take(5)
            
            val allCapabilities = allSessions.flatMap { it.capabilities }
            val capabilityDistribution = allCapabilities.groupBy { it }.mapValues { it.value.size }
            
            Result.Success(
                SessionStatistics(
                    totalSessions = allSessions.size,
                    activeSessions = activeSessions.size,
                    idleSessions = idleSessions.size,
                    expiredSessions = expiredSessions.size,
                    sessionsByClient = sessionsByClient,
                    averageSessionDurationMinutes = averageDuration,
                    sessionsWithWorktrees = sessionsWithWorktrees,
                    mostActiveSessions = mostActiveSessions,
                    capabilityDistribution = capabilityDistribution
                )
            )
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get session statistics: ${e.message}", e)
    }
    
    private fun mapRowToWorkSession(row: ResultRow): WorkSession {
        val capabilitiesJson = row[WorkSessionsTable.capabilities]
        val capabilities = if (capabilitiesJson.isNotEmpty()) {
            json.decodeFromString<List<String>>(capabilitiesJson).toSet()
        } else emptySet()
        
        val gitWorktree = row[WorkSessionsTable.gitWorktreeInfo]?.let { json.decodeFromString<GitWorktreeInfo>(it) }
        
        val (activeTasks, activeFeatures, activeProjects) = decodeActiveAssignments(row[WorkSessionsTable.activeAssignments])
        
        return WorkSession(
            sessionId = row[WorkSessionsTable.id],
            instanceInfo = InstanceInfo(
                clientId = row[WorkSessionsTable.clientId],
                version = row[WorkSessionsTable.clientVersion],
                hostname = row[WorkSessionsTable.hostname],
                userContext = row[WorkSessionsTable.userContext]
            ),
            startedAt = row[WorkSessionsTable.startedAt],
            lastActivity = row[WorkSessionsTable.lastActivity],
            activeTasks = activeTasks,
            activeFeatures = activeFeatures,
            activeProjects = activeProjects,
            gitWorktree = gitWorktree,
            capabilities = capabilities
        )
    }
    
    private fun encodeActiveAssignments(session: WorkSession): String {
        val assignments = mapOf(
            "tasks" to session.activeTasks.map { it.toString() },
            "features" to session.activeFeatures.map { it.toString() },
            "projects" to session.activeProjects.map { it.toString() }
        )
        return json.encodeToString(assignments)
    }
    
    private fun decodeActiveAssignments(assignmentsJson: String?): Triple<Set<UUID>, Set<UUID>, Set<UUID>> {
        if (assignmentsJson.isNullOrEmpty()) {
            return Triple(emptySet(), emptySet(), emptySet())
        }
        
        return try {
            val assignments: Map<String, List<String>> = json.decodeFromString(assignmentsJson)
            val tasks = assignments["tasks"]?.map { UUID.fromString(it) }?.toSet() ?: emptySet()
            val features = assignments["features"]?.map { UUID.fromString(it) }?.toSet() ?: emptySet()
            val projects = assignments["projects"]?.map { UUID.fromString(it) }?.toSet() ?: emptySet()
            Triple(tasks, features, projects)
        } catch (e: Exception) {
            Triple(emptySet(), emptySet(), emptySet())
        }
    }
}
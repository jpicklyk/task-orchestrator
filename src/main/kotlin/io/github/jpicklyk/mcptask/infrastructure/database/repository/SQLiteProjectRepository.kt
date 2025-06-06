package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.ProjectsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/**
 * SQLite implementation of the ProjectRepository interface.
 * Extends the unified base class to inherit common functionality.
 */
class SQLiteProjectRepository(
    databaseManager: DatabaseManager
) : SQLiteBusinessEntityRepository<Project, ProjectStatus, Nothing>(databaseManager),
    ProjectRepository {

    //======================================
    // Base Class Implementation
    //======================================

    override val entityType: EntityType = EntityType.PROJECT
    override val table: Table = ProjectsTable
    override val idColumn: Column<EntityID<UUID>> = ProjectsTable.id
    override val searchVectorColumn: Column<String?> = ProjectsTable.searchVector

    override fun mapRowToEntity(row: ResultRow): Project {
        return Project(
            id = row[ProjectsTable.id].value,
            name = row[ProjectsTable.name],
            summary = row[ProjectsTable.summary],
            status = row[ProjectsTable.status],
            createdAt = row[ProjectsTable.createdAt],
            modifiedAt = row[ProjectsTable.modifiedAt],
            tags = emptyList() // Tags are loaded separately
        )
    }

    override fun addTagsToEntity(entity: Project, tags: List<String>): Project {
        return entity.copy(tags = tags)
    }

    override fun getSearchableText(entity: Project): String {
        return buildString {
            append(entity.name)
            append(" ").append(entity.summary)
            entity.tags.forEach { tag -> append(" ").append(tag) }
        }
    }

    override fun validateEntity(entity: Project) {
        entity.validate()
    }

    override fun insertEntity(entity: Project) {
        ProjectsTable.insert {
            it[id] = entity.id
            it[name] = entity.name
            it[summary] = entity.summary
            it[status] = entity.status
            it[createdAt] = entity.createdAt
            it[modifiedAt] = entity.modifiedAt
        }
    }

    override fun updateEntityInternal(entity: Project): Int {
        return ProjectsTable.update({ ProjectsTable.id eq entity.id }) {
            it[name] = entity.name
            it[summary] = entity.summary
            it[status] = entity.status
            it[modifiedAt] = entity.modifiedAt
        }
    }

    override fun getEntityId(entity: Project): UUID = entity.id
    override fun getModifiedAtColumn(): Column<Instant> = ProjectsTable.modifiedAt
    override fun getEntityTags(entity: Project): List<String> = entity.tags
    override fun getStatusColumn(): Column<ProjectStatus>? = ProjectsTable.status
    override fun getPriorityColumn(): Column<Nothing>? = null // Projects don't have priority
    override fun getEntityStatus(entity: Project): ProjectStatus? = entity.status
    override fun getEntityPriority(entity: Project): Nothing? = null // Projects don't have priority

    //======================================
    // ProjectRepository Interface Implementation
    //======================================

    override suspend fun findByName(
        name: String,
        limit: Int,
    ): Result<List<Project>> = withContext(Dispatchers.IO) {
        try {
            val projects = transaction {
                val searchPattern = "%${name.lowercase()}%"
                val projects = ProjectsTable.selectAll()
                    .where { ProjectsTable.searchVector like searchPattern }
                    .orderBy(ProjectsTable.modifiedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { row -> mapRowToEntity(row) }
                    .toList()

                // Load projects with tags
                loadEntitiesWithTags(projects)
            }

            Result.Success(projects)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to find projects by name: ${e.message}", e))
        }
    }

    override suspend fun getFeatureCount(projectId: UUID): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                // Check if the project exists first
                val projectExists = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.count() > 0
                if (!projectExists) {
                    return@transaction null
                }

                FeaturesTable.selectAll().where { FeaturesTable.projectId eq projectId }.count().toInt()
            }

            if (count != null) {
                Result.Success(count)
            } else {
                Result.Error(
                    RepositoryError.NotFound(
                        projectId,
                        EntityType.PROJECT,
                        "Project not found with ID: $projectId"
                    )
                )
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get feature count: ${e.message}", e))
        }
    }

    override suspend fun getTaskCount(projectId: UUID): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = transaction {
                // Check if the project exists first
                val projectExists = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.count() > 0
                if (!projectExists) {
                    return@transaction null
                }

                TaskTable.selectAll().where { TaskTable.projectId eq projectId }.count().toInt()
            }

            if (count == null) {
                Result.Error(
                    RepositoryError.NotFound(
                        projectId,
                        EntityType.PROJECT,
                        "Project not found with ID: $projectId"
                    )
                )
            } else {
                Result.Success(count)
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get task count: ${e.message}", e))
        }
    }
}
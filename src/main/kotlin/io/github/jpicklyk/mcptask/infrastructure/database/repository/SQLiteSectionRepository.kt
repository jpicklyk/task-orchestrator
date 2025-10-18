package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.SectionsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/**
 * SQLite implementation of the SectionRepository interface.
 */
class SQLiteSectionRepository(
    private val databaseManager: io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
) : SectionRepository {

    override suspend fun getSectionsForEntity(entityType: EntityType, entityId: UUID): Result<List<Section>> =
        withContext(Dispatchers.IO) {
            try {
                val sections = transaction {
                    SectionsTable.selectAll().where {
                        (SectionsTable.entityType eq entityType.name) and
                                (SectionsTable.entityId eq entityId)
                    }.orderBy(SectionsTable.ordinal)
                        .map { mapRowToSection(it) }
                }

                Result.Success(sections)
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to get sections for entity: ${e.message}", e))
            }
        }

    override suspend fun getSection(id: UUID): Result<Section> =
        withContext(Dispatchers.IO) {
            try {
                val section = transaction {
                    SectionsTable.selectAll().where { SectionsTable.id eq id }
                        .map { mapRowToSection(it) }
                        .singleOrNull()
                }

                if (section != null) {
                    Result.Success(section)
                } else {
                    Result.Error(RepositoryError.NotFound(id, EntityType.TASK, "Section not found"))
                }
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to get section: ${e.message}", e))
            }
        }

    override suspend fun addSection(entityType: EntityType, entityId: UUID, section: Section): Result<Section> =
        withContext(Dispatchers.IO) {
            try {
                // Ensure the section is valid
                section.validate()

                // Create a copy with the provided entityType and entityId
                val updatedSection = section.copy(
                    entityType = entityType,
                    entityId = entityId
                )

                val createdId = transaction {
                    // Check for duplicate ordinal before inserting
                    val existingWithOrdinal = SectionsTable.selectAll().where {
                        (SectionsTable.entityType eq entityType.name) and
                                (SectionsTable.entityId eq entityId) and
                                (SectionsTable.ordinal eq updatedSection.ordinal)
                    }.count()

                    if (existingWithOrdinal > 0) {
                        throw IllegalArgumentException(
                            "A section with ordinal ${updatedSection.ordinal} already exists for this ${entityType.name.lowercase()}. " +
                                    "Please use a different ordinal value or use the ReorderSections tool to reorganize existing sections."
                        )
                    }

                    SectionsTable.insert {
                        it[SectionsTable.id] = updatedSection.id
                        it[SectionsTable.entityType] = updatedSection.entityType.name
                        it[SectionsTable.entityId] = updatedSection.entityId
                        it[SectionsTable.title] = updatedSection.title
                        it[SectionsTable.usageDescription] = updatedSection.usageDescription
                        it[SectionsTable.content] = updatedSection.content
                        it[SectionsTable.contentFormat] = updatedSection.contentFormat.name
                        it[SectionsTable.ordinal] = updatedSection.ordinal
                        it[SectionsTable.tags] = updatedSection.tags.joinToString(",")
                        it[SectionsTable.createdAt] = updatedSection.createdAt
                        it[SectionsTable.modifiedAt] = updatedSection.modifiedAt
                        it[SectionsTable.version] = updatedSection.version
                    } get SectionsTable.id
                }

                Result.Success(updatedSection)
            } catch (e: IllegalArgumentException) {
                Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to add section: ${e.message}", e))
            }
        }

    override suspend fun updateSection(section: Section): Result<Section> =
        withContext(Dispatchers.IO) {
            try {
                // Ensure the section is valid
                section.validate()

                // Update modification time
                val updatedSection = section.withUpdatedModificationTime()

                val result = transaction {
                    // Check if section exists first to distinguish NotFound from OptimisticLockError
                    val existingSection = SectionsTable.selectAll().where { SectionsTable.id eq section.id }.singleOrNull()
                    val exists = existingSection != null

                    if (exists) {
                        // Check for duplicate ordinal (excluding the section being updated)
                        val duplicateOrdinal = SectionsTable.selectAll().where {
                            (SectionsTable.entityType eq section.entityType.name) and
                                    (SectionsTable.entityId eq section.entityId) and
                                    (SectionsTable.ordinal eq updatedSection.ordinal) and
                                    (SectionsTable.id neq section.id)
                        }.count()

                        if (duplicateOrdinal > 0) {
                            throw IllegalArgumentException(
                                "A section with ordinal ${updatedSection.ordinal} already exists for this ${section.entityType.name.lowercase()}. " +
                                        "Please use a different ordinal value or use the ReorderSections tool to reorganize existing sections."
                            )
                        }
                    }

                    // Optimistic locking: only update if version matches
                    val rowsUpdated = SectionsTable.update({
                        (SectionsTable.id eq section.id) and (SectionsTable.version eq section.version)
                    }) {
                        it[title] = updatedSection.title
                        it[usageDescription] = updatedSection.usageDescription
                        it[content] = updatedSection.content
                        it[contentFormat] = updatedSection.contentFormat.name
                        it[ordinal] = updatedSection.ordinal
                        it[tags] = updatedSection.tags.joinToString(",")
                        it[modifiedAt] = updatedSection.modifiedAt
                        it[version] = section.version + 1
                    }

                    if (rowsUpdated > 0) {
                        // Return section with incremented version since update succeeded
                        Result.Success(updatedSection.copy(version = section.version + 1))
                    } else {
                        // 0 rows updated: either entity doesn't exist OR version conflict
                        if (!exists) {
                            Result.Error(RepositoryError.NotFound(section.id, EntityType.SECTION, "Section not found"))
                        } else {
                            Result.Error(
                                RepositoryError.ConflictError(
                                    "Optimistic lock conflict: Section with id ${section.id} has been modified by another process"
                                )
                            )
                        }
                    }
                }

                result
            } catch (e: IllegalArgumentException) {
                Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to update section: ${e.message}", e))
            }
        }

    override suspend fun deleteSection(id: UUID): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val rowsDeleted = transaction {
                    SectionsTable.deleteWhere { SectionsTable.id eq id }
                }

                if (rowsDeleted > 0) {
                    Result.Success(true)
                } else {
                    Result.Error(RepositoryError.NotFound(id, EntityType.TASK, "Section not found"))
                }
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to delete section: ${e.message}", e))
            }
        }

    override suspend fun reorderSections(
        entityType: EntityType,
        entityId: UUID,
        sectionIds: List<UUID>
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                transaction {
                    // Two-phase update to avoid unique constraint violations:
                    // First, set all ordinals to negative values (temporary)
                    sectionIds.forEachIndexed { index, sectionId ->
                        SectionsTable.update({
                            (SectionsTable.id eq sectionId) and
                                    (SectionsTable.entityType eq entityType.name) and
                                    (SectionsTable.entityId eq entityId)
                        }) {
                            // Set to a negative value that won't conflict with existing values
                            // Use a different negative value for each section to avoid conflicts
                            it[ordinal] = -1000 - index
                            it[modifiedAt] = Instant.now()
                        }
                    }

                    // Second, update with the actual ordinal values
                    sectionIds.forEachIndexed { index, sectionId ->
                        SectionsTable.update({
                            (SectionsTable.id eq sectionId) and
                                    (SectionsTable.entityType eq entityType.name) and
                                    (SectionsTable.entityId eq entityId)
                        }) {
                            it[ordinal] = index
                            it[modifiedAt] = Instant.now()
                        }
                    }
                }

                Result.Success(true)
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to reorder sections: ${e.message}", e))
            }
        }

    override suspend fun getMaxOrdinal(entityType: EntityType, entityId: UUID): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val maxOrdinal = transaction {
                    SectionsTable
                        .selectAll()
                        .where {
                            (SectionsTable.entityType eq entityType.name) and
                                    (SectionsTable.entityId eq entityId)
                        }
                        .maxByOrNull { it[SectionsTable.ordinal] }
                        ?.get(SectionsTable.ordinal)
                        ?: -1 // Return -1 if no sections exist
                }

                Result.Success(maxOrdinal)
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to get max ordinal: ${e.message}", e))
            }
        }

    /**
     * Maps a database row to a Section entity.
     */
    private fun mapRowToSection(row: ResultRow): Section {
        return Section(
            id = row[SectionsTable.id],
            entityType = EntityType.valueOf(row[SectionsTable.entityType]),
            entityId = row[SectionsTable.entityId],
            title = row[SectionsTable.title],
            usageDescription = row[SectionsTable.usageDescription],
            content = row[SectionsTable.content],
            contentFormat = ContentFormat.valueOf(row[SectionsTable.contentFormat]),
            ordinal = row[SectionsTable.ordinal],
            tags = if (row[SectionsTable.tags].isNotEmpty()) {
                row[SectionsTable.tags].split(",")
            } else {
                emptyList()
            },
            createdAt = row[SectionsTable.createdAt],
            modifiedAt = row[SectionsTable.modifiedAt],
            version = row[SectionsTable.version]
        )
    }
}

package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of SectionRepository for unit tests.
 */
class MockSectionRepository : SectionRepository {
    // In-memory storage for sections
    private val sections = ConcurrentHashMap<UUID, Section>()

    // Collections to store what was passed to various methods
    val addedSections = mutableListOf<Section>()
    val updatedSections = mutableListOf<Section>()
    val deletedSectionIds = mutableListOf<UUID>()

    // Custom behaviors for testing
    var nextGetSectionResult: ((UUID) -> Result<Section>)? = null
    var nextAddResult: ((Section) -> Result<Section>)? = null
    var nextUpdateResult: ((Section) -> Result<Section>)? = null
    var nextDeleteResult: ((UUID) -> Result<Boolean>)? = null

    override suspend fun getSectionsForEntity(entityType: EntityType, entityId: UUID): Result<List<Section>> {
        return Result.Success(
            sections.values
                .filter { it.entityType == entityType && it.entityId == entityId }
                .sortedBy { it.ordinal }
                .toList()
        )
    }

    override suspend fun getSection(id: UUID): Result<Section> {
        val customResult = nextGetSectionResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        val section = sections[id]
        return if (section != null) {
            Result.Success(section)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
        }
    }

    override suspend fun addSection(entityType: EntityType, entityId: UUID, section: Section): Result<Section> {
        val customResult = nextAddResult?.invoke(section)
        if (customResult != null) {
            addedSections.add(section)
            return customResult
        }
        
        try {
            // Ensure the section is valid
            section.validate()

            // Make sure entityType and entityId match what was provided
            val adjustedSection = section.copy(entityType = entityType, entityId = entityId)

            // Store the section
            sections[adjustedSection.id] = adjustedSection
            addedSections.add(adjustedSection)
            return Result.Success(adjustedSection)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to add section: ${e.message}", e))
        }
    }

    // Simplified version for tests
    fun addSection(section: Section): Result<Section> {
        return try {
            section.validate()
            sections[section.id] = section
            Result.Success(section)
        } catch (e: Exception) {
            Result.Error(RepositoryError.ValidationError(e.message ?: "Invalid section"))
        }
    }

    override suspend fun updateSection(section: Section): Result<Section> {
        val customResult = nextUpdateResult?.invoke(section)
        if (customResult != null) {
            updatedSections.add(section)
            return customResult
        }
        
        try {
            // Ensure the section exists
            if (!sections.containsKey(section.id)) {
                return Result.Error(RepositoryError.NotFound(section.id, EntityType.SECTION, "Section not found"))
            }

            // Ensure the section is valid
            section.validate()

            // Update the section with new modification time
            val updatedSection = section.withUpdatedModificationTime()
            sections[updatedSection.id] = updatedSection
            updatedSections.add(updatedSection)
            return Result.Success(updatedSection)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to update section: ${e.message}", e))
        }
    }

    override suspend fun deleteSection(id: UUID): Result<Boolean> {
        val customResult = nextDeleteResult?.invoke(id)
        if (customResult != null) {
            deletedSectionIds.add(id)
            return customResult
        }

        // Default behavior
        return if (sections.containsKey(id)) {
            sections.remove(id)
            deletedSectionIds.add(id)
            Result.Success(true)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
        }
    }

    override suspend fun reorderSections(
        entityType: EntityType,
        entityId: UUID,
        sectionIds: List<UUID>
    ): Result<Boolean> {
        try {
            // Get all sections for this entity
            val entitySections = sections.values
                .filter { it.entityType == entityType && it.entityId == entityId }

            // Verify all section IDs exist
            val missingIds = sectionIds.filter { id -> entitySections.none { it.id == id } }
            if (missingIds.isNotEmpty()) {
                return Result.Error(
                    RepositoryError.NotFound(missingIds.first(), EntityType.SECTION, "Section not found")
                )
            }

            // Update section ordinals based on the provided order
            sectionIds.forEachIndexed { index, sectionId ->
                val section = sections[sectionId]
                if (section != null) {
                    sections[sectionId] = section.copy(ordinal = index)
                }
            }

            return Result.Success(true)
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to reorder sections: ${e.message}", e))
        }
    }

    override suspend fun getMaxOrdinal(
        entityType: EntityType,
        entityId: UUID
    ): Result<Int> {
        return try {
            val maxOrdinal = sections.values
                .filter { it.entityType == entityType && it.entityId == entityId }
                .maxByOrNull { it.ordinal }
                ?.ordinal
                ?: -1 // Return -1 if no sections exist

            Result.Success(maxOrdinal)
        } catch (e: Exception) {
            Result.Error(RepositoryError.UnknownError("Failed to get max ordinal: ${e.message}", e))
        }
    }

    /**
     * Clears all sections from the repository.
     * This method is useful for test setup and teardown.
     */
    fun clear() {
        sections.clear()
        addedSections.clear()
        updatedSections.clear()
        deletedSectionIds.clear()
        nextGetSectionResult = null
        nextAddResult = null
        nextUpdateResult = null
        nextDeleteResult = null
    }

    /**
     * Get an existing section directly from the repository.
     * This method is useful for test assertions.
     */
    fun getExistingSection(id: UUID): Section? {
        return sections[id]
    }
}
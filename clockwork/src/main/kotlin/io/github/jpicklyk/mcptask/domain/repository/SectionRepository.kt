package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import java.util.*

/**
 * Repository interface for managing Section entities.
 */
interface SectionRepository {
    /**
     * Gets all sections for an entity, ordered by ordinal.
     */
    suspend fun getSectionsForEntity(entityType: EntityType, entityId: UUID): Result<List<Section>>

    /**
     * Gets a specific section by ID.
     */
    suspend fun getSection(id: UUID): Result<Section>

    /**
     * Adds a new section to an entity.
     */
    suspend fun addSection(entityType: EntityType, entityId: UUID, section: Section): Result<Section>

    /**
     * Updates an existing section.
     */
    suspend fun updateSection(section: Section): Result<Section>

    /**
     * Deletes a section by ID.
     */
    suspend fun deleteSection(id: UUID): Result<Boolean>

    /**
     * Reorders sections for an entity by changing their ordinal values.
     * The sectionIds list should contain all section IDs in the desired order.
     */
    suspend fun reorderSections(
        entityType: EntityType,
        entityId: UUID,
        sectionIds: List<UUID>
    ): Result<Boolean>

    /**
     * Gets the maximum ordinal value for sections of a specific entity.
     * Returns -1 if no sections exist for the entity.
     */
    suspend fun getMaxOrdinal(entityType: EntityType, entityId: UUID): Result<Int>
}

package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Repository interface for Template entities, providing methods to manage templates
 * and their associated sections.
 */
interface TemplateRepository {
    /**
     * Retrieves all templates, optionally filtered by attributes.
     *
     * @param targetEntityType Optional filter by entity type (TASK or FEATURE)
     * @param isBuiltIn Optional filter for built-in templates
     * @param isEnabled Optional filter for enabled templates
     * @param tags Optional filter by tags (matches any tag in the list)
     * @return Result containing a list of templates or an error
     */
    suspend fun getAllTemplates(
        targetEntityType: EntityType? = null,
        isBuiltIn: Boolean? = null,
        isEnabled: Boolean? = null,
        tags: List<String>? = null
    ): Result<List<Template>>

    /**
     * Retrieves a specific template by ID.
     *
     * @param id The template ID
     * @return Result containing the template or an error
     */
    suspend fun getTemplate(id: UUID): Result<Template>

    /**
     * Creates a new template.
     *
     * @param template The template to create
     * @return Result containing the created template or an error
     */
    suspend fun createTemplate(template: Template): Result<Template>

    /**
     * Updates an existing template.
     *
     * @param template The template to update
     * @return Result containing the updated template or an error
     */
    suspend fun updateTemplate(template: Template): Result<Template>

    /**
     * Deletes a template and its sections.
     *
     * @param id The template ID
     * @param force If true, allows deletion of protected templates
     * @return Result containing success status or an error
     */
    suspend fun deleteTemplate(id: UUID, force: Boolean = false): Result<Boolean>

    /**
     * Enables a template, making it available for use.
     *
     * @param id The template ID
     * @return Result containing the updated template or an error
     */
    suspend fun enableTemplate(id: UUID): Result<Template>

    /**
     * Disables a template, preventing it from being used.
     *
     * @param id The template ID
     * @return Result containing the updated template or an error
     */
    suspend fun disableTemplate(id: UUID): Result<Template>

    /**
     * Retrieves all template sections for a specific template.
     *
     * @param templateId The template ID
     * @return Result containing a list of template sections or an error
     */
    suspend fun getTemplateSections(templateId: UUID): Result<List<TemplateSection>>

    /**
     * Adds a section to a template.
     *
     * @param templateId The template ID
     * @param section The template section to add
     * @return Result containing the created template section or an error
     */
    suspend fun addTemplateSection(templateId: UUID, section: TemplateSection): Result<TemplateSection>

    /**
     * Updates an existing template section.
     *
     * @param section The template section to update
     * @return Result containing the updated template section or an error
     */
    suspend fun updateTemplateSection(section: TemplateSection): Result<TemplateSection>

    /**
     * Deletes a template section.
     *
     * @param id The template section ID
     * @return Result containing success status or an error
     */
    suspend fun deleteTemplateSection(id: UUID): Result<Boolean>

    /**
     * Applies a template to an entity by creating sections from the template.
     *
     * @param templateId The template ID
     * @param entityType The entity type (must match template's targetEntityType)
     * @param entityId The entity ID
     * @return Result containing the list of created sections or an error
     */
    suspend fun applyTemplate(
        templateId: UUID,
        entityType: EntityType,
        entityId: UUID
    ): Result<List<TemplateSection>>

    /**
     * Applies multiple templates to an entity in a single operation.
     *
     * @param templateIds List of template IDs to apply
     * @param entityType The entity type (must match templates' targetEntityType)
     * @param entityId The entity ID 
     * @return Result containing map of template IDs to their created sections
     */
    suspend fun applyMultipleTemplates(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID
    ): Result<Map<UUID, List<TemplateSection>>>

    /**
     * Searches for templates by name or description.
     *
     * @param query The search query
     * @param targetEntityType Optional filter by entity type
     * @return Result containing a list of matching templates or an error
     */
    suspend fun searchTemplates(
        query: String,
        targetEntityType: EntityType? = null
    ): Result<List<Template>>
}
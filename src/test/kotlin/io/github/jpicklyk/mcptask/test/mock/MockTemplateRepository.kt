package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import java.util.*

/**
 * Mock implementation of TemplateRepository for unit tests.
 */
class MockTemplateRepository : TemplateRepository {
    // In-memory storage for templates
    private val templates = mutableMapOf<UUID, Template>()

    // In-memory storage for template sections
    private val templateSections = mutableMapOf<UUID, List<TemplateSection>>()

    override suspend fun getAllTemplates(
        targetEntityType: EntityType?,
        isBuiltIn: Boolean?,
        isEnabled: Boolean?,
        tags: List<String>?
    ): Result<List<Template>> {
        // Apply filters if provided
        var filteredTemplates = templates.values.toList()

        targetEntityType?.let { entityType ->
            filteredTemplates = filteredTemplates.filter { it.targetEntityType == entityType }
        }

        isBuiltIn?.let { builtIn ->
            filteredTemplates = filteredTemplates.filter { it.isBuiltIn == builtIn }
        }

        isEnabled?.let { enabled ->
            filteredTemplates = filteredTemplates.filter { it.isEnabled == enabled }
        }

        tags?.let { tagList ->
            if (tagList.isNotEmpty()) {
                filteredTemplates = filteredTemplates.filter { template ->
                    template.tags.any { it in tagList }
                }
            }
        }

        return Result.Success(filteredTemplates)
    }

    override suspend fun getTemplate(id: UUID): Result<Template> {
        val template = templates[id]
        return if (template != null) {
            Result.Success(template)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
        }
    }

    override suspend fun createTemplate(template: Template): Result<Template> {
        // Ensure the template has a valid ID
        val templateWithId = template.copy(id = template.id)
        templates[templateWithId.id] = templateWithId
        return Result.Success(templateWithId)
    }

    override suspend fun updateTemplate(template: Template): Result<Template> {
        return if (templates.containsKey(template.id)) {
            templates[template.id] = template
            Result.Success(template)
        } else {
            val id = template.id
            Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found for update"))
        }
    }

    override suspend fun deleteTemplate(id: UUID, force: Boolean): Result<Boolean> {
        val template = templates[id]
        return if (template != null) {
            if (template.isBuiltIn && !force) {
                Result.Error(RepositoryError.ValidationError("Cannot delete built-in template without force flag"))
            } else {
                templates.remove(id)
                templateSections.remove(id)
                Result.Success(true)
            }
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found for deletion"))
        }
    }
    
    override suspend fun enableTemplate(id: UUID): Result<Template> {
        val template = templates[id]
        return if (template != null) {
            // If already enabled, just return the template
            if (template.isEnabled) {
                Result.Success(template)
            } else {
                // Create updated template with isEnabled = true
                val enabledTemplate = template.copy(isEnabled = true)
                templates[id] = enabledTemplate
                Result.Success(enabledTemplate)
            }
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
        }
    }
    
    override suspend fun disableTemplate(id: UUID): Result<Template> {
        val template = templates[id]
        return if (template != null) {
            // If already disabled, just return the template
            if (!template.isEnabled) {
                Result.Success(template)
            } else {
                // Create updated template with isEnabled = false
                val disabledTemplate = template.copy(isEnabled = false)
                templates[id] = disabledTemplate
                Result.Success(disabledTemplate)
            }
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
        }
    }

    override suspend fun getTemplateSections(templateId: UUID): Result<List<TemplateSection>> {
        return if (templates.containsKey(templateId)) {
            Result.Success(templateSections[templateId] ?: emptyList())
        } else {
            Result.Error(RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found"))
        }
    }

    override suspend fun addTemplateSection(templateId: UUID, section: TemplateSection): Result<TemplateSection> {
        return if (templates.containsKey(templateId)) {
            val sectionWithId = section.copy(id = UUID.randomUUID(), templateId = templateId)
            val currentSections = templateSections[templateId] ?: emptyList()
            templateSections[templateId] = currentSections + sectionWithId
            Result.Success(sectionWithId)
        } else {
            Result.Error(RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found"))
        }
    }

    override suspend fun updateTemplateSection(section: TemplateSection): Result<TemplateSection> {
        val templateId = section.templateId
        val sectionId = section.id

        if (!templates.containsKey(templateId)) {
            return Result.Error(
                RepositoryError.NotFound(
                    templateId,
                    EntityType.TEMPLATE,
                    "Template not found"
                )
            )
        }

        val currentSections = templateSections[templateId] ?: emptyList()
        val sectionIndex = currentSections.indexOfFirst { it.id == sectionId }

        return if (sectionIndex >= 0) {
            val updatedSections = currentSections.toMutableList()
            updatedSections[sectionIndex] = section
            templateSections[templateId] = updatedSections
            Result.Success(section)
        } else {
            Result.Error(
                RepositoryError.NotFound(
                    sectionId,
                    EntityType.SECTION,
                    "Template section not found"
                )
            )
        }
    }

    override suspend fun deleteTemplateSection(id: UUID): Result<Boolean> {
        for ((templateId, sections) in templateSections) {
            val sectionIndex = sections.indexOfFirst { it.id == id }
            if (sectionIndex >= 0) {
                val updatedSections = sections.toMutableList()
                updatedSections.removeAt(sectionIndex)
                templateSections[templateId] = updatedSections
                return Result.Success(true)
            }
        }
        return Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Template section not found"))
    }

    override suspend fun applyTemplate(
        templateId: UUID,
        entityType: EntityType,
        entityId: UUID
    ): Result<List<TemplateSection>> {
        val template = templates[templateId]

        return if (template != null) {
            if (template.targetEntityType != entityType) {
                Result.Error(
                    RepositoryError.ValidationError(
                        "Template target entity type ${template.targetEntityType} doesn't match requested entity type $entityType"
                    )
                )
            } else if (!template.isEnabled) {
                Result.Error(
                    RepositoryError.ValidationError(
                        "Template '${template.name}' (ID: ${template.id}) is disabled and cannot be applied. " +
                        "Use enableTemplate() to enable it first."
                    )
                )
            } else {
                val sections = templateSections[templateId] ?: emptyList()
                Result.Success(sections)
            }
        } else {
            Result.Error(RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found"))
        }
    }
    
    override suspend fun applyMultipleTemplates(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID
    ): Result<Map<UUID, List<TemplateSection>>> {
        if (templateIds.isEmpty()) {
            return Result.Success(emptyMap())
        }
        
        val result = mutableMapOf<UUID, List<TemplateSection>>()
        val errors = mutableListOf<Pair<UUID, String>>()
        
        for (templateId in templateIds) {
            when (val templateResult = applyTemplate(templateId, entityType, entityId)) {
                is Result.Success -> {
                    if (templateResult.data.isNotEmpty()) {
                        result[templateId] = templateResult.data
                    }
                }
                is Result.Error -> {
                    when (templateResult.error) {
                        is RepositoryError.NotFound -> {
                            errors.add(Pair(templateId, "Template not found"))
                        }
                        is RepositoryError.ValidationError -> {
                            errors.add(Pair(templateId, templateResult.error.message))
                        }
                        else -> {
                            errors.add(Pair(templateId, "Error applying template: ${templateResult.error.message}"))
                        }
                    }
                }
            }
        }
        
        // If all templates failed, return the first error
        if (result.isEmpty() && errors.isNotEmpty()) {
            val (id, message) = errors.first()
            return Result.Error(
                RepositoryError.ValidationError("Failed to apply template $id: $message")
            )
        }
        
        return Result.Success(result)
    }

    override suspend fun searchTemplates(
        query: String,
        targetEntityType: EntityType?
    ): Result<List<Template>> {
        val queryLower = query.lowercase()
        var filteredTemplates: List<Template> = templates.values.filter { template ->
            template.name.lowercase().contains(queryLower) ||
                    template.description.lowercase().contains(queryLower)
        }

        targetEntityType?.let { entityType ->
            filteredTemplates = filteredTemplates.filter { it.targetEntityType == entityType }
        }

        return Result.Success(filteredTemplates)
    }
}
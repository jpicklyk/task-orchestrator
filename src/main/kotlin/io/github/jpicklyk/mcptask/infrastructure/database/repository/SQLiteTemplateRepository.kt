package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TemplateSectionsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TemplatesTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.like
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

/**
 * SQLite implementation of the TemplateRepository interface.
 */
class SQLiteTemplateRepository(private val sectionRepository: SectionRepository) : TemplateRepository {

    override suspend fun getAllTemplates(
        targetEntityType: EntityType?,
        isBuiltIn: Boolean?,
        isEnabled: Boolean?,
        tags: List<String>?
    ): Result<List<Template>> = withContext(Dispatchers.IO) {
        try {
            val templates = transaction {
                var query = TemplatesTable.selectAll()

                targetEntityType?.let {
                    query = query.andWhere { TemplatesTable.targetEntityType eq it.name }
                }
                isBuiltIn?.let {
                    query = query.andWhere { TemplatesTable.isBuiltIn eq it }
                }
                isEnabled?.let {
                    query = query.andWhere { TemplatesTable.isEnabled eq it }
                }

                tags?.let { tagList ->
                    if (tagList.isNotEmpty()) {
                        val conditions = tagList.map { tag ->
                            TemplatesTable.tags like "%$tag%"
                        }
                        query = query.andWhere {
                            conditions.fold(Op.FALSE as Op<Boolean>) { acc, op -> acc or op }
                        }
                    }
                }

                query.orderBy(TemplatesTable.name)
                    .map { mapRowToTemplate(it) }
            }

            Result.Success(templates)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get templates: ${e.message}", e))
        }
    }

    override suspend fun getTemplate(id: UUID): Result<Template> = withContext(Dispatchers.IO) {
        try {
            val template = transaction {
                TemplatesTable.selectAll().where { TemplatesTable.id eq id }
                    .map { mapRowToTemplate(it) }
                    .singleOrNull()
            }

            if (template != null) {
                Result.Success(template)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to get template: ${e.message}", e))
        }
    }

    override suspend fun createTemplate(template: Template): Result<Template> = withContext(Dispatchers.IO) {
        try {
            template.validate()

            val existingTemplate = transaction {
                TemplatesTable.selectAll().where { TemplatesTable.name eq template.name }
                    .map { mapRowToTemplate(it) }
                    .singleOrNull()
            }

            if (existingTemplate != null) {
                return@withContext Result.Error(
                    RepositoryError.ConflictError("Template with name '${template.name}' already exists")
                )
            }

            transaction {
                TemplatesTable.insert {
                    it[id] = template.id
                    it[name] = template.name
                    it[description] = template.description
                    it[targetEntityType] = template.targetEntityType.name
                    it[isBuiltIn] = template.isBuiltIn
                    it[isProtected] = template.isProtected
                    it[isEnabled] = template.isEnabled
                    it[createdBy] = template.createdBy
                    it[tags] = template.tags.joinToString(",")
                    it[createdAt] = template.createdAt
                    it[modifiedAt] = template.modifiedAt
                }
            }

            Result.Success(template)
        } catch (e: IllegalArgumentException) {
            Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to create template: ${e.message}", e))
        }
    }

    override suspend fun updateTemplate(template: Template): Result<Template> = withContext(Dispatchers.IO) {
        try {
            template.validate()

            val existingTemplate = when (val result = getTemplate(template.id)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext result
            }

            if (existingTemplate.isProtected && !template.isProtected) {
                return@withContext Result.Error(
                    RepositoryError.ValidationError("Cannot remove protection from a protected template")
                )
            }

            if (template.name != existingTemplate.name) {
                val nameConflict = transaction {
                    TemplatesTable.selectAll().where {
                        (TemplatesTable.name eq template.name) and (TemplatesTable.id neq template.id)
                    }.count() > 0
                }

                if (nameConflict) {
                    return@withContext Result.Error(
                        RepositoryError.ConflictError("Template with name '${template.name}' already exists")
                    )
                }
            }

            val updatedTemplate = template.withUpdatedModificationTime()
            val rowsUpdated = transaction {
                TemplatesTable.update({ TemplatesTable.id eq updatedTemplate.id }) {
                    it[name] = updatedTemplate.name
                    it[description] = updatedTemplate.description
                    it[targetEntityType] = updatedTemplate.targetEntityType.name
                    it[isBuiltIn] = updatedTemplate.isBuiltIn
                    it[isProtected] = updatedTemplate.isProtected
                    it[isEnabled] = updatedTemplate.isEnabled
                    it[createdBy] = updatedTemplate.createdBy
                    it[tags] = updatedTemplate.tags.joinToString(",")
                    it[modifiedAt] = updatedTemplate.modifiedAt
                }
            }

            if (rowsUpdated > 0) {
                Result.Success(updatedTemplate)
            } else {
                Result.Error(RepositoryError.NotFound(template.id, EntityType.TEMPLATE, "Template not found"))
            }
        } catch (e: IllegalArgumentException) {
            Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to update template: ${e.message}", e))
        }
    }

    override suspend fun deleteTemplate(id: UUID, force: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val existingTemplate = when (val result = getTemplate(id)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext result
            }

            if (existingTemplate.isProtected && !force) {
                return@withContext Result.Error(
                    RepositoryError.ValidationError("Cannot delete a protected template without force=true")
                )
            }

            transaction {
                TemplateSectionsTable.deleteWhere { TemplateSectionsTable.templateId eq id }
                TemplatesTable.deleteWhere { TemplatesTable.id eq id }
            }

            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to delete template: ${e.message}", e))
        }
    }

    override suspend fun enableTemplate(id: UUID): Result<Template> = withContext(Dispatchers.IO) {
        try {
            val existingTemplate = when (val result = getTemplate(id)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext result
            }

            // If the template is already enabled, just return it
            if (existingTemplate.isEnabled) {
                return@withContext Result.Success(existingTemplate)
            }

            // Create an updated version with isEnabled = true and updated modification time
            val updatedTemplate = existingTemplate.copy(
                isEnabled = true,
                modifiedAt = java.time.Instant.now()
            )

            // Update in the database
            val rowsUpdated = transaction {
                TemplatesTable.update({ TemplatesTable.id eq id }) {
                    it[isEnabled] = true
                    it[modifiedAt] = updatedTemplate.modifiedAt
                }
            }

            if (rowsUpdated > 0) {
                Result.Success(updatedTemplate)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to enable template: ${e.message}", e))
        }
    }

    override suspend fun disableTemplate(id: UUID): Result<Template> = withContext(Dispatchers.IO) {
        try {
            val existingTemplate = when (val result = getTemplate(id)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext result
            }

            // If the template is already disabled, just return it
            if (!existingTemplate.isEnabled) {
                return@withContext Result.Success(existingTemplate)
            }

            // Create an updated version with isEnabled = false and updated modification time
            val updatedTemplate = existingTemplate.copy(
                isEnabled = false,
                modifiedAt = java.time.Instant.now()
            )

            // Update in the database
            val rowsUpdated = transaction {
                TemplatesTable.update({ TemplatesTable.id eq id }) {
                    it[isEnabled] = false
                    it[modifiedAt] = updatedTemplate.modifiedAt
                }
            }

            if (rowsUpdated > 0) {
                Result.Success(updatedTemplate)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to disable template: ${e.message}", e))
        }
    }

    override suspend fun getTemplateSections(templateId: UUID): Result<List<TemplateSection>> =
        withContext(Dispatchers.IO) {
            try {
                // First verify that the template exists
                val templateExists = transaction {
                    TemplatesTable.selectAll().where { TemplatesTable.id eq templateId }.count() > 0
                }

                if (!templateExists) {
                    return@withContext Result.Error(
                        RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found")
                    )
                }

                val sections = transaction {
                    TemplateSectionsTable.selectAll().where { TemplateSectionsTable.templateId eq templateId }
                        .orderBy(TemplateSectionsTable.ordinal)
                        .map { mapRowToTemplateSection(it) }
                }

                Result.Success(sections)
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to get template sections: ${e.message}", e))
            }
        }

    override suspend fun addTemplateSection(templateId: UUID, section: TemplateSection): Result<TemplateSection> =
        withContext(Dispatchers.IO) {
            try {
                // Validate the section
                section.validate()

                // Verify that the template exists
                val templateExists = transaction {
                    TemplatesTable.selectAll().where { TemplatesTable.id eq templateId }.count() > 0
                }

                if (!templateExists) {
                    return@withContext Result.Error(
                        RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found")
                    )
                }

                // Create a copy with the provided templateId
                val updatedSection = section.copy(templateId = templateId)

                // Insert the section
                transaction {
                    TemplateSectionsTable.insert {
                        it[id] = updatedSection.id
                        it[TemplateSectionsTable.templateId] = updatedSection.templateId
                        it[title] = updatedSection.title
                        it[usageDescription] = updatedSection.usageDescription
                        it[contentSample] = updatedSection.contentSample
                        it[contentFormat] = updatedSection.contentFormat.name
                        it[ordinal] = updatedSection.ordinal
                        it[isRequired] = updatedSection.isRequired
                        it[tags] = updatedSection.tags.joinToString(",")
                    }
                }

                Result.Success(updatedSection)
            } catch (e: IllegalArgumentException) {
                Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to add template section: ${e.message}", e))
            }
        }

    override suspend fun updateTemplateSection(section: TemplateSection): Result<TemplateSection> =
        withContext(Dispatchers.IO) {
            try {
                // Validate the section
                section.validate()

                // Check if the section exists
                val sectionExists = transaction {
                    TemplateSectionsTable.selectAll().where { TemplateSectionsTable.id eq section.id }.count() > 0
                }

                if (!sectionExists) {
                    return@withContext Result.Error(
                        RepositoryError.NotFound(section.id, EntityType.TEMPLATE, "Template section not found")
                    )
                }

                // Update the section
                val rowsUpdated = transaction {
                    TemplateSectionsTable.update({ TemplateSectionsTable.id eq section.id }) {
                        it[title] = section.title
                        it[usageDescription] = section.usageDescription
                        it[contentSample] = section.contentSample
                        it[contentFormat] = section.contentFormat.name
                        it[ordinal] = section.ordinal
                        it[isRequired] = section.isRequired
                        it[tags] = section.tags.joinToString(",")
                    }
                }

                if (rowsUpdated > 0) {
                    Result.Success(section)
                } else {
                    Result.Error(
                        RepositoryError.NotFound(
                            section.id,
                            EntityType.TEMPLATE,
                            "Template section not found"
                        )
                    )
                }
            } catch (e: IllegalArgumentException) {
                Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
            } catch (e: Exception) {
                Result.Error(RepositoryError.DatabaseError("Failed to update template section: ${e.message}", e))
            }
        }

    override suspend fun deleteTemplateSection(id: UUID): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val rowsDeleted = transaction {
                TemplateSectionsTable.deleteWhere { TemplateSectionsTable.id eq id }
            }

            if (rowsDeleted > 0) {
                Result.Success(true)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template section not found"))
            }
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to delete template section: ${e.message}", e))
        }
    }

    override suspend fun applyTemplate(
        templateId: UUID,
        entityType: EntityType,
        entityId: UUID
    ): Result<List<TemplateSection>> = withContext(Dispatchers.IO) {
        try {
            // Get the template
            val template = when (val result = getTemplate(templateId)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext Result.Error(
                    RepositoryError.NotFound(templateId, EntityType.TEMPLATE, "Template not found")
                )
            }

            // Verify that the template's target entity type matches the provided entity type
            if (template.targetEntityType != entityType) {
                return@withContext Result.Error(
                    RepositoryError.ValidationError(
                        "Template target entity type (${template.targetEntityType}) does not match provided entity type ($entityType)"
                    )
                )
            }

            // Verify that the template is enabled
            if (!template.isEnabled) {
                return@withContext Result.Error(
                    RepositoryError.ValidationError(
                        "Template '${template.name}' (ID: ${template.id}) is disabled and cannot be applied. " +
                                "Use enableTemplate() to enable it first."
                    )
                )
            }

            // Get the template sections
            val sections = when (val result = getTemplateSections(templateId)) {
                is Result.Success -> result.data
                is Result.Error -> return@withContext result
            }

            if (sections.isEmpty()) {
                return@withContext Result.Success(emptyList())
            }

            // Get existing sections for the entity to check for duplicates
            val existingSectionsResult = sectionRepository.getSectionsForEntity(entityType, entityId)
            val existingSections = when (existingSectionsResult) {
                is Result.Success -> existingSectionsResult.data
                is Result.Error -> return@withContext Result.Error(existingSectionsResult.error)
            }

            // Create a set of existing section titles for quick duplicate detection
            val existingSectionTitles = existingSections.map { it.title }.toSet()

            // Filter out template sections that would create duplicates
            val duplicateTitles = sections.filter { templateSection ->
                existingSectionTitles.contains(templateSection.title)
            }.map { it.title }

            // If there are duplicates, return an error indicating the issue
            if (duplicateTitles.isNotEmpty()) {
                return@withContext Result.Error(
                    RepositoryError.ValidationError(
                        "Template '${template.name}' cannot be applied: sections with titles [${duplicateTitles.joinToString(", ")}] already exist. " +
                                "Remove existing sections with these titles or use a different template to avoid duplicates."
                    )
                )
            }

            // Get the current maximum ordinal for the entity to avoid conflicts
            val maxOrdinalResult = sectionRepository.getMaxOrdinal(entityType, entityId)
            val currentMaxOrdinal = when (maxOrdinalResult) {
                is Result.Success -> maxOrdinalResult.data
                is Result.Error -> return@withContext Result.Error(maxOrdinalResult.error)
            }

            // Calculate ordinal offset: start after the highest existing ordinal
            val ordinalOffset = currentMaxOrdinal + 1

            // Apply each template section to create a new section for the entity
            val createdSections = mutableListOf<TemplateSection>()

            for (templateSection in sections) {
                // Convert template section to a regular section with ordinal offset
                val section = templateSection.toSection(entityType, entityId, ordinalOffset)

                // Add the section
                when (val result = sectionRepository.addSection(entityType, entityId, section)) {
                    is Result.Success -> {
                        // Create a TemplateSection with the actual ordinal that was assigned
                        val adjustedTemplateSection = templateSection.copy(ordinal = section.ordinal)
                        createdSections.add(adjustedTemplateSection)
                    }
                    is Result.Error -> {
                        // Return error on first failure to maintain consistency
                        return@withContext Result.Error(result.error)
                    }
                }
            }

            Result.Success(createdSections)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to apply template: ${e.message}", e))
        }
    }

    override suspend fun applyMultipleTemplates(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID
    ): Result<Map<UUID, List<TemplateSection>>> = withContext(Dispatchers.IO) {
        try {
            if (templateIds.isEmpty()) {
                return@withContext Result.Success(emptyMap())
            }

            // Get the current maximum ordinal for the entity to avoid conflicts
            val maxOrdinalResult = sectionRepository.getMaxOrdinal(entityType, entityId)
            var currentMaxOrdinal = when (maxOrdinalResult) {
                is Result.Success -> maxOrdinalResult.data
                is Result.Error -> return@withContext Result.Error(maxOrdinalResult.error)
            }

            // Get existing sections for the entity to check for duplicates across all templates
            val existingSectionsResult = sectionRepository.getSectionsForEntity(entityType, entityId)
            val existingSections = when (existingSectionsResult) {
                is Result.Success -> existingSectionsResult.data
                is Result.Error -> return@withContext Result.Error(existingSectionsResult.error)
            }

            // Track titles across all templates being applied to detect duplicates within the request
            val allTemplateSectionTitles = mutableSetOf<String>()
            val existingSectionTitles = existingSections.map { it.title }.toSet()

            // Process each template sequentially to maintain ordinal consistency
            val result = mutableMapOf<UUID, List<TemplateSection>>()
            val errors = mutableListOf<Pair<UUID, String>>()

            for (templateId in templateIds) {
                // Get the template to check its sections count for ordinal calculation
                val template = when (val templateResult = getTemplate(templateId)) {
                    is Result.Success -> templateResult.data
                    is Result.Error -> {
                        errors.add(Pair(templateId, templateResult.error.message))
                        continue
                    }
                }

                // Verify that the template's target entity type matches the provided entity type
                if (template.targetEntityType != entityType) {
                    errors.add(
                        Pair(
                            templateId,
                            "Template target entity type (${template.targetEntityType}) does not match provided entity type ($entityType)"
                        )
                    )
                    continue
                }

                // Verify that the template is enabled
                if (!template.isEnabled) {
                    errors.add(
                        Pair(
                            templateId,
                            "Template '${template.name}' (ID: ${template.id}) is disabled and cannot be applied"
                        )
                    )
                    continue
                }

                // Get the template sections
                val sections = when (val sectionsResult = getTemplateSections(templateId)) {
                    is Result.Success -> sectionsResult.data
                    is Result.Error -> {
                        errors.add(Pair(templateId, sectionsResult.error.message))
                        continue
                    }
                }

                if (sections.isEmpty()) {
                    result[templateId] = emptyList()
                    continue
                }

                // Check for duplicate section titles with existing sections and within this batch
                val duplicateWithExisting = sections.filter { templateSection ->
                    existingSectionTitles.contains(templateSection.title)
                }.map { it.title }
                
                val duplicateWithinBatch = sections.filter { templateSection ->
                    allTemplateSectionTitles.contains(templateSection.title)
                }.map { it.title }

                // Combine all duplicates
                val allDuplicates = (duplicateWithExisting + duplicateWithinBatch).distinct()

                if (allDuplicates.isNotEmpty()) {
                    errors.add(
                        Pair(
                            templateId,
                            "Template '${template.name}' has sections with duplicate titles [${allDuplicates.joinToString(", ")}]"
                        )
                    )
                    continue
                }

                // Add this template's section titles to the batch tracker
                allTemplateSectionTitles.addAll(sections.map { it.title })

                // Calculate ordinal offset: start after the current highest ordinal
                val ordinalOffset = currentMaxOrdinal + 1

                // Apply each template section to create a new section for the entity
                val createdSections = mutableListOf<TemplateSection>()
                var templateSectionCount = 0

                for (templateSection in sections) {
                    // Convert template section to a regular section with ordinal offset
                    val section = templateSection.toSection(entityType, entityId, ordinalOffset)

                    // Add the section
                    when (val addResult = sectionRepository.addSection(entityType, entityId, section)) {
                        is Result.Success -> {
                            // Create a TemplateSection with the actual ordinal that was assigned
                            val adjustedTemplateSection = templateSection.copy(ordinal = section.ordinal)
                            createdSections.add(adjustedTemplateSection)
                            templateSectionCount++
                        }

                        is Result.Error -> {
                            errors.add(
                                Pair(
                                    templateId,
                                    "Failed to add section '${templateSection.title}': ${addResult.error.message}"
                                )
                            )
                            break // Stop applying this template on first section failure
                        }
                    }
                }

                if (createdSections.isNotEmpty()) {
                    result[templateId] = createdSections
                    // Update current max ordinal for the next template
                    currentMaxOrdinal += templateSectionCount
                }
            }

            // If all templates failed, return the first error
            if (result.isEmpty() && errors.isNotEmpty()) {
                val (id, message) = errors.first()
                return@withContext Result.Error(
                    RepositoryError.ValidationError("Failed to apply template $id: $message")
                )
            }

            Result.Success(result)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to apply multiple templates: ${e.message}", e))
        }
    }

    override suspend fun searchTemplates(
        query: String,
        targetEntityType: EntityType?
    ): Result<List<Template>> = withContext(Dispatchers.IO) {
        try {
            val searchTerms = query.split(Regex("\\s+")).filter { it.length > 2 }

            if (searchTerms.isEmpty()) {
                return@withContext Result.Success(emptyList())
            }

            val templates = transaction {
                var queryBuilder = TemplatesTable.selectAll()

                // Apply search conditions
                val searchConditions = searchTerms.map { term ->
                    (TemplatesTable.name like "%$term%") or
                            (TemplatesTable.description like "%$term%") or
                            (TemplatesTable.tags like "%$term%")
                }

                queryBuilder = queryBuilder.andWhere {
                    searchConditions.fold(Op.FALSE as Op<Boolean>) { acc, op -> acc or op }
                }

                // Apply entity type filter if provided
                targetEntityType?.let {
                    queryBuilder = queryBuilder.andWhere { TemplatesTable.targetEntityType eq it.name }
                }

                queryBuilder.orderBy(TemplatesTable.name)
                    .map { mapRowToTemplate(it) }
            }

            Result.Success(templates)
        } catch (e: Exception) {
            Result.Error(RepositoryError.DatabaseError("Failed to search templates: ${e.message}", e))
        }
    }

    /**
     * Maps a database row to a Template entity.
     */
    private fun mapRowToTemplate(row: ResultRow): Template {
        return Template(
            id = row[TemplatesTable.id],
            name = row[TemplatesTable.name],
            description = row[TemplatesTable.description],
            targetEntityType = EntityType.valueOf(row[TemplatesTable.targetEntityType]),
            isBuiltIn = row[TemplatesTable.isBuiltIn],
            isProtected = row[TemplatesTable.isProtected],
            isEnabled = row[TemplatesTable.isEnabled],
            createdBy = row[TemplatesTable.createdBy],
            tags = if (row[TemplatesTable.tags].isNotEmpty()) {
                row[TemplatesTable.tags].split(",")
            } else {
                emptyList()
            },
            createdAt = row[TemplatesTable.createdAt],
            modifiedAt = row[TemplatesTable.modifiedAt]
        )
    }

    /**
     * Maps a database row to a TemplateSection entity.
     */
    private fun mapRowToTemplateSection(row: ResultRow): TemplateSection {
        return TemplateSection(
            id = row[TemplateSectionsTable.id],
            templateId = row[TemplateSectionsTable.templateId],
            title = row[TemplateSectionsTable.title],
            usageDescription = row[TemplateSectionsTable.usageDescription],
            contentSample = row[TemplateSectionsTable.contentSample],
            contentFormat = ContentFormat.valueOf(row[TemplateSectionsTable.contentFormat]),
            ordinal = row[TemplateSectionsTable.ordinal],
            isRequired = row[TemplateSectionsTable.isRequired],
            tags = if (row[TemplateSectionsTable.tags].isNotEmpty()) {
                row[TemplateSectionsTable.tags].split(",")
            } else {
                emptyList()
            }
        )
    }
}
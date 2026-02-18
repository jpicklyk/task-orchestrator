package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests for CachedTemplateRepository to verify caching behavior.
 */
class CachedTemplateRepositoryTest {

    private lateinit var mockDelegate: MockTemplateRepository
    private lateinit var cachedRepository: CachedTemplateRepository

    @BeforeEach
    fun setup() {
        mockDelegate = MockTemplateRepository()
        cachedRepository = CachedTemplateRepository(mockDelegate)
    }

    @Test
    fun `should cache template on first get and return from cache on subsequent gets`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        // First call - cache miss
        val result1 = cachedRepository.getTemplate(template.id)
        assertTrue(result1 is Result.Success)
        assertEquals(1, mockDelegate.getTemplateCallCount)

        // Second call - cache hit
        val result2 = cachedRepository.getTemplate(template.id)
        assertTrue(result2 is Result.Success)
        assertEquals(1, mockDelegate.getTemplateCallCount) // Should not increment

        // Verify same template
        assertEquals((result1 as Result.Success).data.id, (result2 as Result.Success).data.id)
    }

    @Test
    fun `should cache template list and return from cache on subsequent calls with same filters`() = runBlocking {
        val template1 = Template(
            name = "Template 1",
            description = "Description 1",
            targetEntityType = EntityType.TASK
        )
        val template2 = Template(
            name = "Template 2",
            description = "Description 2",
            targetEntityType = EntityType.FEATURE
        )
        mockDelegate.mockAddTemplate(template1)
        mockDelegate.mockAddTemplate(template2)

        // First call - cache miss
        val result1 = cachedRepository.getAllTemplates(targetEntityType = EntityType.TASK)
        assertTrue(result1 is Result.Success)
        assertEquals(1, mockDelegate.getAllTemplatesCallCount)

        // Second call with same filters - cache hit
        val result2 = cachedRepository.getAllTemplates(targetEntityType = EntityType.TASK)
        assertTrue(result2 is Result.Success)
        assertEquals(1, mockDelegate.getAllTemplatesCallCount) // Should not increment

        // Call with different filters - cache miss
        val result3 = cachedRepository.getAllTemplates(targetEntityType = EntityType.FEATURE)
        assertTrue(result3 is Result.Success)
        assertEquals(2, mockDelegate.getAllTemplatesCallCount) // Should increment
    }

    @Test
    fun `should cache template sections and return from cache on subsequent calls`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        val section = TemplateSection(
            templateId = template.id,
            title = "Section 1",
            usageDescription = "Usage description",
            contentSample = "Sample content",
            ordinal = 0
        )
        mockDelegate.mockAddTemplateSection(template.id, section)

        // First call - cache miss
        val result1 = cachedRepository.getTemplateSections(template.id)
        assertTrue(result1 is Result.Success)
        assertEquals(1, mockDelegate.getTemplateSectionsCallCount)

        // Second call - cache hit
        val result2 = cachedRepository.getTemplateSections(template.id)
        assertTrue(result2 is Result.Success)
        assertEquals(1, mockDelegate.getTemplateSectionsCallCount) // Should not increment
    }

    @Test
    fun `should update template cache when template is updated`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        // Get template to cache it
        val result1 = cachedRepository.getTemplate(template.id)
        assertTrue(result1 is Result.Success)
        assertEquals("Test description", (result1 as Result.Success).data.description)
        assertEquals(1, mockDelegate.getTemplateCallCount)

        // Update template
        val updatedTemplate = template.copy(description = "Updated description")
        cachedRepository.updateTemplate(updatedTemplate)

        // Get template again - should return updated template from cache (not from delegate)
        val result2 = cachedRepository.getTemplate(template.id)
        assertTrue(result2 is Result.Success)
        assertEquals("Updated description", (result2 as Result.Success).data.description)
        assertEquals(1, mockDelegate.getTemplateCallCount) // Still 1 - using cache
    }

    @Test
    fun `should invalidate list caches when template is created`() = runBlocking {
        // Get all templates to cache the list
        cachedRepository.getAllTemplates()
        assertEquals(1, mockDelegate.getAllTemplatesCallCount)

        // Create new template
        val newTemplate = Template(
            name = "New Template",
            description = "New description",
            targetEntityType = EntityType.TASK
        )
        cachedRepository.createTemplate(newTemplate)

        // Get all templates again - should fetch from delegate (cache invalidated)
        cachedRepository.getAllTemplates()
        assertEquals(2, mockDelegate.getAllTemplatesCallCount)
    }

    @Test
    fun `should invalidate list caches when template is deleted`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        // Get all templates to cache the list
        cachedRepository.getAllTemplates()
        assertEquals(1, mockDelegate.getAllTemplatesCallCount)

        // Delete template
        cachedRepository.deleteTemplate(template.id)

        // Get all templates again - should fetch from delegate (cache invalidated)
        cachedRepository.getAllTemplates()
        assertEquals(2, mockDelegate.getAllTemplatesCallCount)
    }

    @Test
    fun `should invalidate list caches when template is enabled`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK,
            isEnabled = false
        )
        mockDelegate.mockAddTemplate(template)

        // Get all templates to cache the list
        cachedRepository.getAllTemplates(isEnabled = false)
        assertEquals(1, mockDelegate.getAllTemplatesCallCount)

        // Enable template
        cachedRepository.enableTemplate(template.id)

        // Get all templates again - should fetch from delegate (cache invalidated)
        cachedRepository.getAllTemplates(isEnabled = false)
        assertEquals(2, mockDelegate.getAllTemplatesCallCount)
    }

    @Test
    fun `should invalidate sections cache when section is added`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        // Get sections to cache them
        cachedRepository.getTemplateSections(template.id)
        assertEquals(1, mockDelegate.getTemplateSectionsCallCount)

        // Add new section
        val newSection = TemplateSection(
            templateId = template.id,
            title = "New Section",
            usageDescription = "Usage description",
            contentSample = "Sample content",
            ordinal = 0
        )
        cachedRepository.addTemplateSection(template.id, newSection)

        // Get sections again - should fetch from delegate (cache invalidated)
        cachedRepository.getTemplateSections(template.id)
        assertEquals(2, mockDelegate.getTemplateSectionsCallCount)
    }

    @Test
    fun `should provide cache statistics`() = runBlocking {
        val template1 = Template(
            name = "Template 1",
            description = "Description 1",
            targetEntityType = EntityType.TASK
        )
        val template2 = Template(
            name = "Template 2",
            description = "Description 2",
            targetEntityType = EntityType.FEATURE
        )
        mockDelegate.mockAddTemplate(template1)
        mockDelegate.mockAddTemplate(template2)

        // Cache some templates
        cachedRepository.getTemplate(template1.id)
        cachedRepository.getTemplate(template2.id)

        // Cache a list
        cachedRepository.getAllTemplates()

        // Cache sections
        cachedRepository.getTemplateSections(template1.id)

        val stats = cachedRepository.getCacheStats()
        assertTrue(stats.templateCacheSize >= 2) // At least 2 templates cached
        assertTrue(stats.listCacheSize >= 1) // At least 1 list cached
        assertTrue(stats.sectionsCacheSize >= 1) // At least 1 sections cached
    }

    @Test
    fun `should clear all caches when clearCache is called`() = runBlocking {
        val template = Template(
            name = "Test Template",
            description = "Test description",
            targetEntityType = EntityType.TASK
        )
        mockDelegate.mockAddTemplate(template)

        // Cache template, list, and sections
        cachedRepository.getTemplate(template.id)
        cachedRepository.getAllTemplates()
        cachedRepository.getTemplateSections(template.id)

        // Verify caches are populated
        assertTrue(cachedRepository.getCacheStats().templateCacheSize > 0)
        assertTrue(cachedRepository.getCacheStats().listCacheSize > 0)

        // Clear all caches
        cachedRepository.clearCache()

        // Verify caches are empty
        assertEquals(0, cachedRepository.getCacheStats().templateCacheSize)
        assertEquals(0, cachedRepository.getCacheStats().listCacheSize)
        assertEquals(0, cachedRepository.getCacheStats().sectionsCacheSize)
    }

    /**
     * Mock implementation of TemplateRepository for testing.
     */
    private class MockTemplateRepository : TemplateRepository {
        private val templates = mutableMapOf<UUID, Template>()
        private val templateSections = mutableMapOf<UUID, MutableList<TemplateSection>>()

        var getTemplateCallCount = 0
        var getAllTemplatesCallCount = 0
        var getTemplateSectionsCallCount = 0

        fun mockAddTemplate(template: Template) {
            templates[template.id] = template
        }

        fun mockAddTemplateSection(templateId: UUID, section: TemplateSection) {
            templateSections.getOrPut(templateId) { mutableListOf() }.add(section)
        }

        override suspend fun getTemplate(id: UUID): Result<Template> {
            getTemplateCallCount++
            return templates[id]?.let { Result.Success(it) }
                ?: Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
        }

        override suspend fun getAllTemplates(
            targetEntityType: EntityType?,
            isBuiltIn: Boolean?,
            isEnabled: Boolean?,
            tags: List<String>?
        ): Result<List<Template>> {
            getAllTemplatesCallCount++
            var filtered = templates.values.toList()

            targetEntityType?.let { type ->
                filtered = filtered.filter { it.targetEntityType == type }
            }
            isBuiltIn?.let { builtIn ->
                filtered = filtered.filter { it.isBuiltIn == builtIn }
            }
            isEnabled?.let { enabled ->
                filtered = filtered.filter { it.isEnabled == enabled }
            }

            return Result.Success(filtered)
        }

        override suspend fun getTemplateSections(templateId: UUID): Result<List<TemplateSection>> {
            getTemplateSectionsCallCount++
            return Result.Success(templateSections[templateId] ?: emptyList())
        }

        override suspend fun createTemplate(template: Template): Result<Template> {
            templates[template.id] = template
            return Result.Success(template)
        }

        override suspend fun updateTemplate(template: Template): Result<Template> {
            if (!templates.containsKey(template.id)) {
                return Result.Error(RepositoryError.NotFound(template.id, EntityType.TEMPLATE, "Template not found"))
            }
            templates[template.id] = template
            return Result.Success(template)
        }

        override suspend fun deleteTemplate(id: UUID, force: Boolean): Result<Boolean> {
            return if (templates.remove(id) != null) {
                templateSections.remove(id)
                Result.Success(true)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            }
        }

        override suspend fun enableTemplate(id: UUID): Result<Template> {
            val template = templates[id]
                ?: return Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            val updatedTemplate = template.copy(isEnabled = true)
            templates[id] = updatedTemplate
            return Result.Success(updatedTemplate)
        }

        override suspend fun disableTemplate(id: UUID): Result<Template> {
            val template = templates[id]
                ?: return Result.Error(RepositoryError.NotFound(id, EntityType.TEMPLATE, "Template not found"))
            val updatedTemplate = template.copy(isEnabled = false)
            templates[id] = updatedTemplate
            return Result.Success(updatedTemplate)
        }

        override suspend fun addTemplateSection(templateId: UUID, section: TemplateSection): Result<TemplateSection> {
            templateSections.getOrPut(templateId) { mutableListOf() }.add(section)
            return Result.Success(section)
        }

        override suspend fun updateTemplateSection(section: TemplateSection): Result<TemplateSection> {
            return Result.Success(section)
        }

        override suspend fun deleteTemplateSection(id: UUID): Result<Boolean> {
            return Result.Success(true)
        }

        override suspend fun applyTemplate(
            templateId: UUID,
            entityType: EntityType,
            entityId: UUID
        ): Result<List<TemplateSection>> {
            return Result.Success(emptyList())
        }

        override suspend fun applyMultipleTemplates(
            templateIds: List<UUID>,
            entityType: EntityType,
            entityId: UUID
        ): Result<Map<UUID, List<TemplateSection>>> {
            return Result.Success(emptyMap())
        }

        override suspend fun searchTemplates(
            query: String,
            targetEntityType: EntityType?
        ): Result<List<Template>> {
            return Result.Success(emptyList())
        }
    }
}

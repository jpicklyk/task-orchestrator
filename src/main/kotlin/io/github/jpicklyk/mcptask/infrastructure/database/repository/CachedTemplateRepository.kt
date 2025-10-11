package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached decorator for TemplateRepository that provides in-memory caching for template operations.
 *
 * This implementation wraps the underlying template repository and caches:
 * - Individual templates by ID
 * - Template lists by filter parameters
 * - Template sections by template ID
 *
 * Cache is automatically invalidated on modifications (create, update, delete, enable/disable).
 *
 * ## Performance Benefits
 * - Reduces database queries for frequently accessed templates
 * - Particularly beneficial for built-in templates that rarely change
 * - `list_templates` calls avoid repeated database queries
 * - Template application operations retrieve templates from cache
 *
 * ## Thread Safety
 * All cache operations use ConcurrentHashMap for thread-safe concurrent access.
 */
class CachedTemplateRepository(
    private val delegate: TemplateRepository
) : TemplateRepository {

    private val logger = LoggerFactory.getLogger(CachedTemplateRepository::class.java)

    // Cache for individual templates by ID
    private val templateCache = ConcurrentHashMap<UUID, Template>()

    // Cache for template sections by template ID
    private val templateSectionsCache = ConcurrentHashMap<UUID, List<TemplateSection>>()

    // Cache for template lists by filter key
    private val templateListCache = ConcurrentHashMap<String, List<Template>>()

    /**
     * Clears all caches. Useful for testing or manual cache invalidation.
     */
    fun clearCache() {
        templateCache.clear()
        templateSectionsCache.clear()
        templateListCache.clear()
        logger.debug("Template cache cleared")
    }

    /**
     * Invalidates cache for a specific template.
     */
    private fun invalidateTemplate(templateId: UUID) {
        templateCache.remove(templateId)
        templateSectionsCache.remove(templateId)
        logger.debug("Invalidated cache for template: $templateId")
    }

    /**
     * Invalidates all list caches (called when templates are modified).
     */
    private fun invalidateListCaches() {
        templateListCache.clear()
        logger.debug("Invalidated all template list caches")
    }

    /**
     * Generates cache key for template list queries.
     */
    private fun generateListCacheKey(
        targetEntityType: EntityType?,
        isBuiltIn: Boolean?,
        isEnabled: Boolean?,
        tags: List<String>?
    ): String {
        return "list:${targetEntityType?.name}:${isBuiltIn}:${isEnabled}:${tags?.sorted()?.joinToString(",")}"
    }

    override suspend fun getAllTemplates(
        targetEntityType: EntityType?,
        isBuiltIn: Boolean?,
        isEnabled: Boolean?,
        tags: List<String>?
    ): Result<List<Template>> {
        val cacheKey = generateListCacheKey(targetEntityType, isBuiltIn, isEnabled, tags)

        // Check cache first
        templateListCache[cacheKey]?.let { cachedList ->
            logger.debug("Template list cache hit for key: $cacheKey")
            return Result.Success(cachedList)
        }

        // Cache miss - fetch from delegate
        logger.debug("Template list cache miss for key: $cacheKey")
        return when (val result = delegate.getAllTemplates(targetEntityType, isBuiltIn, isEnabled, tags)) {
            is Result.Success -> {
                // Cache the result
                templateListCache[cacheKey] = result.data
                // Also cache individual templates
                result.data.forEach { template ->
                    templateCache[template.id] = template
                }
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun getTemplate(id: UUID): Result<Template> {
        // Check cache first
        templateCache[id]?.let { cachedTemplate ->
            logger.debug("Template cache hit for ID: $id")
            return Result.Success(cachedTemplate)
        }

        // Cache miss - fetch from delegate
        logger.debug("Template cache miss for ID: $id")
        return when (val result = delegate.getTemplate(id)) {
            is Result.Success -> {
                // Cache the result
                templateCache[id] = result.data
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun createTemplate(template: Template): Result<Template> {
        return when (val result = delegate.createTemplate(template)) {
            is Result.Success -> {
                // Cache the new template
                templateCache[result.data.id] = result.data
                // Invalidate list caches
                invalidateListCaches()
                logger.debug("Created template and invalidated list caches: ${result.data.id}")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun updateTemplate(template: Template): Result<Template> {
        return when (val result = delegate.updateTemplate(template)) {
            is Result.Success -> {
                // Update cache
                templateCache[result.data.id] = result.data
                // Invalidate list caches (template properties may have changed)
                invalidateListCaches()
                logger.debug("Updated template and invalidated list caches: ${result.data.id}")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun deleteTemplate(id: UUID, force: Boolean): Result<Boolean> {
        return when (val result = delegate.deleteTemplate(id, force)) {
            is Result.Success -> {
                // Invalidate template and its sections
                invalidateTemplate(id)
                // Invalidate list caches
                invalidateListCaches()
                logger.debug("Deleted template and invalidated caches: $id")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun enableTemplate(id: UUID): Result<Template> {
        return when (val result = delegate.enableTemplate(id)) {
            is Result.Success -> {
                // Update cache
                templateCache[result.data.id] = result.data
                // Invalidate list caches (enabled status changed)
                invalidateListCaches()
                logger.debug("Enabled template and invalidated list caches: ${result.data.id}")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun disableTemplate(id: UUID): Result<Template> {
        return when (val result = delegate.disableTemplate(id)) {
            is Result.Success -> {
                // Update cache
                templateCache[result.data.id] = result.data
                // Invalidate list caches (enabled status changed)
                invalidateListCaches()
                logger.debug("Disabled template and invalidated list caches: ${result.data.id}")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun getTemplateSections(templateId: UUID): Result<List<TemplateSection>> {
        // Check cache first
        templateSectionsCache[templateId]?.let { cachedSections ->
            logger.debug("Template sections cache hit for template: $templateId")
            return Result.Success(cachedSections)
        }

        // Cache miss - fetch from delegate
        logger.debug("Template sections cache miss for template: $templateId")
        return when (val result = delegate.getTemplateSections(templateId)) {
            is Result.Success -> {
                // Cache the result
                templateSectionsCache[templateId] = result.data
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun addTemplateSection(templateId: UUID, section: TemplateSection): Result<TemplateSection> {
        return when (val result = delegate.addTemplateSection(templateId, section)) {
            is Result.Success -> {
                // Invalidate sections cache for this template
                templateSectionsCache.remove(templateId)
                logger.debug("Added template section and invalidated sections cache: $templateId")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun updateTemplateSection(section: TemplateSection): Result<TemplateSection> {
        return when (val result = delegate.updateTemplateSection(section)) {
            is Result.Success -> {
                // Invalidate sections cache for the template
                templateSectionsCache.remove(section.templateId)
                logger.debug("Updated template section and invalidated sections cache: ${section.templateId}")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun deleteTemplateSection(id: UUID): Result<Boolean> {
        // Note: We need to fetch the section first to know which template to invalidate
        // This is a limitation of the current design - consider adding templateId parameter
        return when (val result = delegate.deleteTemplateSection(id)) {
            is Result.Success -> {
                // Clear all sections caches since we don't know which template this belongs to
                templateSectionsCache.clear()
                logger.debug("Deleted template section and cleared sections cache")
                result
            }
            is Result.Error -> result
        }
    }

    override suspend fun applyTemplate(
        templateId: UUID,
        entityType: EntityType,
        entityId: UUID
    ): Result<List<TemplateSection>> {
        // No caching for apply operations - these create new data
        return delegate.applyTemplate(templateId, entityType, entityId)
    }

    override suspend fun applyMultipleTemplates(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID
    ): Result<Map<UUID, List<TemplateSection>>> {
        // No caching for apply operations - these create new data
        return delegate.applyMultipleTemplates(templateIds, entityType, entityId)
    }

    override suspend fun searchTemplates(
        query: String,
        targetEntityType: EntityType?
    ): Result<List<Template>> {
        // Search operations are not cached to ensure freshness
        // Could be added later with TTL-based caching if needed
        return delegate.searchTemplates(query, targetEntityType)
    }

    /**
     * Get cache statistics for monitoring and debugging.
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            templateCacheSize = templateCache.size,
            sectionsCacheSize = templateSectionsCache.size,
            listCacheSize = templateListCache.size
        )
    }

    data class CacheStats(
        val templateCacheSize: Int,
        val sectionsCacheSize: Int,
        val listCacheSize: Int
    )
}

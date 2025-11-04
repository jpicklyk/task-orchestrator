package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.TaskCounts
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of FeatureRepository for unit tests.
 */
class MockFeatureRepository : FeatureRepository {

    private val features = ConcurrentHashMap<UUID, Feature>()
    private val featureTags = ConcurrentHashMap<UUID, List<String>>()
    private val taskCounts = ConcurrentHashMap<UUID, Int>()

    // Custom behaviors for testing
    var nextGetFeatureResult: ((UUID) -> Result<Feature>)? = null
    var nextCreateResult: ((Feature) -> Result<Feature>)? = null
    var nextUpdateResult: ((Feature) -> Result<Feature>)? = null
    var nextDeleteResult: ((UUID) -> Result<Boolean>)? = null

    // BaseRepository methods
    override suspend fun getById(id: UUID): Result<Feature> {
        val customResult = nextGetFeatureResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        val feature = features[id]
        return if (feature != null) {
            Result.Success(feature)
        } else {
            Result.Error(RepositoryError.NotFound(id, EntityType.FEATURE, "Feature not found"))
        }
    }

    override suspend fun create(entity: Feature): Result<Feature> {
        val customResult = nextCreateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }
        
        try {
            entity.validate()
            features[entity.id] = entity
            featureTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to create feature: ${e.message}", e))
        }
    }

    override suspend fun update(entity: Feature): Result<Feature> {
        val customResult = nextUpdateResult?.invoke(entity)
        if (customResult != null) {
            return customResult
        }
        
        if (!features.containsKey(entity.id)) {
            return Result.Error(RepositoryError.NotFound(entity.id, EntityType.FEATURE, "Feature not found"))
        }

        try {
            entity.validate()
            features[entity.id] = entity
            featureTags[entity.id] = entity.tags
            return Result.Success(entity)
        } catch (e: IllegalArgumentException) {
            return Result.Error(RepositoryError.ValidationError(e.message ?: "Validation failed"))
        } catch (e: Exception) {
            return Result.Error(RepositoryError.UnknownError("Failed to update feature: ${e.message}", e))
        }
    }

    override suspend fun delete(id: UUID): Result<Boolean> {
        val customResult = nextDeleteResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        if (!features.containsKey(id)) {
            return Result.Error(RepositoryError.NotFound(id, EntityType.FEATURE, "Feature not found"))
        }

        features.remove(id)
        featureTags.remove(id)
        taskCounts.remove(id)
        return Result.Success(true)
    }

    // QueryableRepository methods
    override suspend fun findAll(limit: Int): Result<List<Feature>> {
        val allFeatures = features.values.toList()
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(allFeatures)
    }

    override suspend fun count(): Result<Long> {
        return Result.Success(features.size.toLong())
    }

    // SearchableRepository methods
    override suspend fun search(query: String, limit: Int): Result<List<Feature>> {
        val lowerQuery = query.lowercase()
        val searchResults = features.values.toList()
            .filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(searchResults)
    }

    override suspend fun countSearch(query: String): Result<Long> {
        val lowerQuery = query.lowercase()
        val count = features.values.count {
            it.name.lowercase().contains(lowerQuery) ||
                    it.summary.lowercase().contains(lowerQuery)
        }
        return Result.Success(count.toLong())
    }

    // TaggableRepository methods
    override suspend fun findByTag(tag: String, limit: Int): Result<List<Feature>> {
        val taggedFeatures = features.values.toList()
            .filter { feature -> featureTags[feature.id]?.contains(tag) == true }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedFeatures)
    }

    override suspend fun findByTags(
        tags: List<String>,
        matchAll: Boolean,
        limit: Int,
    ): Result<List<Feature>> {
        val taggedFeatures = features.values.toList()
            .filter { feature ->
                val featureTagList = featureTags[feature.id] ?: emptyList()
                if (matchAll) {
                    tags.all { tag -> featureTagList.contains(tag) }
                } else {
                    tags.any { tag -> featureTagList.contains(tag) }
                }
            }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(taggedFeatures)
    }

    override suspend fun getAllTags(): Result<List<String>> {
        val allTags = featureTags.values.flatten().distinct().sorted()
        return Result.Success(allTags)
    }

    override suspend fun countByTag(tag: String): Result<Long> {
        val count = features.values.count { feature -> featureTags[feature.id]?.contains(tag) == true }
        return Result.Success(count.toLong())
    }

    // FilterableRepository methods
    override suspend fun findByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<FeatureStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Feature>> {
        var filteredFeatures = features.values.toList()

        // Apply filters if provided
        if (projectId != null) {
            filteredFeatures = filteredFeatures.filter { it.projectId == projectId }
        }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredFeatures = filteredFeatures.filter { feature ->
                val featureTagList = featureTags[feature.id] ?: emptyList()
                tags.any { tag -> featureTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredFeatures = filteredFeatures.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        // Sort by modifiedAt (newest first) to match SQLite implementation
        filteredFeatures = filteredFeatures.sortedByDescending { it.modifiedAt }

        // Apply pagination
        val paginatedFeatures = filteredFeatures
            .take(limit)

        return Result.Success(paginatedFeatures)
    }

    override suspend fun countByFilters(
        projectId: UUID?,
        statusFilter: StatusFilter<FeatureStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?
    ): Result<Long> {
        var filteredFeatures = features.values.toList()

        if (projectId != null) {
            filteredFeatures = filteredFeatures.filter { it.projectId == projectId }
        }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredFeatures = filteredFeatures.filter { feature ->
                val featureTagList = featureTags[feature.id] ?: emptyList()
                tags.any { tag -> featureTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredFeatures = filteredFeatures.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        return Result.Success(filteredFeatures.size.toLong())
    }

    override suspend fun findByStatus(status: FeatureStatus, limit: Int): Result<List<Feature>> {
        val statusFeatures = features.values.toList()
            .filter { it.status == status }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(statusFeatures)
    }

    override suspend fun findByPriority(priority: Priority, limit: Int): Result<List<Feature>> {
        val priorityFeatures = features.values.toList()
            .filter { it.priority == priority }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(priorityFeatures)
    }

    // ProjectScopedRepository methods
    override suspend fun findByProject(projectId: UUID, limit: Int): Result<List<Feature>> {
        val projectFeatures = features.values.toList()
            .filter { it.projectId == projectId }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(projectFeatures)
    }

    override suspend fun findByProjectAndFilters(
        projectId: UUID,
        statusFilter: StatusFilter<FeatureStatus>?,
        priorityFilter: StatusFilter<Priority>?,
        tags: List<String>?,
        textQuery: String?,
        limit: Int,
    ): Result<List<Feature>> {
        var filteredFeatures = features.values.toList()
            .filter { it.projectId == projectId }

        if (statusFilter != null && !statusFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { statusFilter.matches(it.status) }
        }

        if (priorityFilter != null && !priorityFilter.isEmpty()) {
            filteredFeatures = filteredFeatures.filter { priorityFilter.matches(it.priority) }
        }

        if (!tags.isNullOrEmpty()) {
            filteredFeatures = filteredFeatures.filter { feature ->
                val featureTagList = featureTags[feature.id] ?: emptyList()
                tags.any { tag -> featureTagList.contains(tag) }
            }
        }

        if (!textQuery.isNullOrBlank()) {
            val lowerQuery = textQuery.lowercase()
            filteredFeatures = filteredFeatures.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.summary.lowercase().contains(lowerQuery)
            }
        }

        filteredFeatures = filteredFeatures.sortedByDescending { it.modifiedAt }

        val paginatedFeatures = filteredFeatures
            .take(limit)

        return Result.Success(paginatedFeatures)
    }

    override suspend fun countByProject(projectId: UUID): Result<Long> {
        val count = features.values.count { it.projectId == projectId }
        return Result.Success(count.toLong())
    }

    // FeatureRepository-specific methods
    override suspend fun findByName(name: String, limit: Int): Result<List<Feature>> {
        val lowerName = name.lowercase()
        val nameFeatures = features.values.toList()
            .filter { it.name.lowercase().contains(lowerName) }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
        return Result.Success(nameFeatures)
    }

    override suspend fun getTaskCount(featureId: UUID): Result<Int> {
        return Result.Success(taskCounts[featureId] ?: 0)
    }

    // Workflow cascade detection methods
    override fun getTaskCountsByFeatureId(featureId: UUID): TaskCounts {
        // For testing, return a basic TaskCounts with just the total
        // Real tests should set this up with more detailed counts
        val total = taskCounts[featureId] ?: 0
        return TaskCounts(
            total = total,
            pending = 0,
            inProgress = 0,
            completed = total, // Assume all are completed for simple case
            cancelled = 0,
            testing = 0,
            blocked = 0
        )
    }

    /**
     * Test helper method to add a feature directly to the repository
     */
    fun addFeature(feature: Feature) {
        features[feature.id] = feature
        featureTags[feature.id] = feature.tags
    }

    /**
     * Add multiple features directly to the repository
     */
    fun addFeatures(featuresList: List<Feature>) {
        featuresList.forEach { feature ->
            features[feature.id] = feature
            featureTags[feature.id] = feature.tags
        }
    }

    /**
     * Set task count for a feature (for testing)
     */
    fun setTaskCount(featureId: UUID, count: Int) {
        taskCounts[featureId] = count
    }

    /**
     * Test helper method to clear all features from the repository
     */
    fun clearFeatures() {
        features.clear()
        featureTags.clear()
        taskCounts.clear()
        nextGetFeatureResult = null
        nextCreateResult = null
        nextUpdateResult = null
        nextDeleteResult = null
    }
}
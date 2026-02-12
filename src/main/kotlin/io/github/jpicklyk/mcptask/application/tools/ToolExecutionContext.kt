package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider

/**
 * Context for tool execution, containing references to repositories and other services.
 * This is passed to tools during execution to provide access to common resources.
 *
 * @property repositoryProvider Provider of repository instances
 * @param statusProgressionService Optional status progression service for role-aware features
 */
class ToolExecutionContext(
    val repositoryProvider: RepositoryProvider,
    private val statusProgressionService: StatusProgressionService? = null
) {
    /**
     * Gets the StatusProgressionService instance, if available.
     *
     * @return The StatusProgressionService or null if not configured
     */
    fun statusProgressionService(): StatusProgressionService? = statusProgressionService

    /**
     * Gets the TaskRepository instance.
     *
     * @return The TaskRepository
     */
    fun taskRepository(): TaskRepository = repositoryProvider.taskRepository()

    /**
     * Gets the FeatureRepository instance.
     *
     * @return The FeatureRepository
     */
    fun featureRepository(): FeatureRepository = repositoryProvider.featureRepository()

    /**
     * Gets the SectionRepository instance.
     *
     * @return The SectionRepository
     */
    fun sectionRepository(): SectionRepository = repositoryProvider.sectionRepository()

    /**
     * Gets the TemplateRepository instance.
     *
     * @return The TemplateRepository
     */
    fun templateRepository(): TemplateRepository = repositoryProvider.templateRepository()

    /**
     * Gets the ProjectRepository instance.
     *
     * @return The ProjectRepository
     */
    fun projectRepository(): ProjectRepository = repositoryProvider.projectRepository()

    /**
     * Gets the DependencyRepository instance.
     *
     * @return The DependencyRepository
     */
    fun dependencyRepository(): DependencyRepository = repositoryProvider.dependencyRepository()
}

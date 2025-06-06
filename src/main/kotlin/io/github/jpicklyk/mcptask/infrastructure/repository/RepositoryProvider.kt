package io.github.jpicklyk.mcptask.infrastructure.repository

import io.github.jpicklyk.mcptask.domain.repository.*

/**
 * Interface for providing access to repositories.
 * This allows decoupling the repository implementation from the tools.
 */
interface RepositoryProvider {
    /**
     * Provides access to the ProjectRepository.
     *
     * @return The ProjectRepository implementation
     */
    fun projectRepository(): ProjectRepository

    /**
     * Provides access to the TaskRepository.
     *
     * @return The TaskRepository implementation
     */
    fun taskRepository(): TaskRepository

    /**
     * Provides access to the FeatureRepository.
     *
     * @return The FeatureRepository implementation
     */
    fun featureRepository(): FeatureRepository

    /**
     * Provides access to the SectionRepository.
     *
     * @return The SectionRepository implementation
     */
    fun sectionRepository(): SectionRepository

    /**
     * Provides access to the TemplateRepository.
     *
     * @return The TemplateRepository implementation
     */
    fun templateRepository(): TemplateRepository

    /**
     * Provides access to the DependencyRepository.
     *
     * @return The DependencyRepository implementation
     */
    fun dependencyRepository(): DependencyRepository
}
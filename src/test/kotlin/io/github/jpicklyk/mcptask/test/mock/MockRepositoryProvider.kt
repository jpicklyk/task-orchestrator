package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider

/**
 * Mock implementation of RepositoryProvider for unit tests.
 */
class MockRepositoryProvider(
    private val projectRepository: ProjectRepository = MockProjectRepository(),
    private val taskRepository: TaskRepository = MockTaskRepository(),
    private val featureRepository: FeatureRepository = MockFeatureRepository(),
    private val sectionRepository: SectionRepository = MockSectionRepository(),
    private val templateRepository: TemplateRepository = MockTemplateRepository(),
    private val dependencyRepository: DependencyRepository = MockDependencyRepository()
) : RepositoryProvider {

    override fun projectRepository(): ProjectRepository = projectRepository

    override fun taskRepository(): TaskRepository = taskRepository

    override fun featureRepository(): FeatureRepository = featureRepository

    override fun sectionRepository(): SectionRepository = sectionRepository

    override fun templateRepository(): TemplateRepository = templateRepository

    override fun dependencyRepository(): DependencyRepository = dependencyRepository
}
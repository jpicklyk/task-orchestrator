package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of DependencyRepository for unit tests.
 */
class MockDependencyRepository : DependencyRepository {

    private val dependencies = ConcurrentHashMap<UUID, Dependency>()

    // Custom behaviors for testing
    var nextCreateResult: ((Dependency) -> Dependency)? = null
    var nextFindByIdResult: ((UUID) -> Dependency?)? = null
    var nextDeleteResult: ((UUID) -> Boolean)? = null
    var shouldThrowOnCycleCheck: Boolean = false

    override fun create(dependency: Dependency): Dependency {
        val customResult = nextCreateResult?.invoke(dependency)
        if (customResult != null) {
            return customResult
        }
        
        dependencies[dependency.id] = dependency
        return dependency
    }

    override fun findById(id: UUID): Dependency? {
        val customResult = nextFindByIdResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        return dependencies[id]
    }

    override fun findByTaskId(taskId: UUID): List<Dependency> {
        return dependencies.values.filter { 
            it.fromTaskId == taskId || it.toTaskId == taskId 
        }
    }

    override fun findByFromTaskId(fromTaskId: UUID): List<Dependency> {
        return dependencies.values.filter { it.fromTaskId == fromTaskId }
    }

    override fun findByToTaskId(toTaskId: UUID): List<Dependency> {
        return dependencies.values.filter { it.toTaskId == toTaskId }
    }

    override fun delete(id: UUID): Boolean {
        val customResult = nextDeleteResult?.invoke(id)
        if (customResult != null) {
            return customResult
        }
        
        return dependencies.remove(id) != null
    }

    override fun deleteByTaskId(taskId: UUID): Int {
        val toDelete = dependencies.values.filter { 
            it.fromTaskId == taskId || it.toTaskId == taskId 
        }
        
        toDelete.forEach { dependency ->
            dependencies.remove(dependency.id)
        }
        
        return toDelete.size
    }

    override fun hasCyclicDependency(fromTaskId: UUID, toTaskId: UUID): Boolean {
        if (shouldThrowOnCycleCheck) {
            throw RuntimeException("Test exception during cycle check")
        }
        
        // Simple cycle detection for testing: check if reverse dependency exists
        return dependencies.values.any { 
            it.fromTaskId == toTaskId && it.toTaskId == fromTaskId 
        }
    }

    /**
     * Test helper method to add a dependency directly to the repository
     */
    fun addDependency(dependency: Dependency) {
        dependencies[dependency.id] = dependency
    }

    /**
     * Add multiple dependencies directly to the repository
     */
    fun addDependencies(dependenciesList: List<Dependency>) {
        dependenciesList.forEach { dependency ->
            dependencies[dependency.id] = dependency
        }
    }

    /**
     * Test helper method to clear all dependencies from the repository
     */
    fun clearDependencies() {
        dependencies.clear()
        nextCreateResult = null
        nextFindByIdResult = null
        nextDeleteResult = null
        shouldThrowOnCycleCheck = false
    }

    /**
     * Get all dependencies for testing purposes
     */
    fun getAllDependencies(): List<Dependency> {
        return dependencies.values.toList()
    }
}
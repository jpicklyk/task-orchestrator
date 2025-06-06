package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
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
        
        // Check for cyclic dependencies before creating
        if (hasCyclicDependency(dependency.fromTaskId, dependency.toTaskId)) {
            throw ValidationException("Creating this dependency would result in a circular dependency")
        }

        // Check for duplicate dependencies
        val existingDependency = dependencies.values.find { existing ->
            existing.fromTaskId == dependency.fromTaskId &&
                    existing.toTaskId == dependency.toTaskId &&
                    existing.type == dependency.type
        }

        if (existingDependency != null) {
            throw ValidationException("A dependency of this type already exists between these tasks")
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
        
        // If they're the same task, it's definitely a cycle
        if (fromTaskId == toTaskId) {
            return true
        }

        // Use depth-first search to check for cycles
        val visited = mutableSetOf<UUID>()
        val visiting = mutableSetOf<UUID>()

        fun hasCycle(currentTaskId: UUID): Boolean {
            // If we've already fully explored this node, it doesn't lead to a cycle
            if (currentTaskId in visited) {
                return false
            }

            // If we're currently visiting this node, we found a cycle
            if (currentTaskId in visiting) {
                return true
            }

            // Mark as currently visiting
            visiting.add(currentTaskId)

            // Check all the tasks that this task depends on (outgoing edges)
            val outgoingDeps = findByFromTaskId(currentTaskId)
            for (dependency in outgoingDeps) {
                // If the dependency type is BLOCKS or RELATES_TO, follow it
                if (dependency.type != DependencyType.IS_BLOCKED_BY) {
                    // If we reach the original fromTaskId, we found a cycle
                    if (dependency.toTaskId == fromTaskId) {
                        return true
                    }

                    // Recursively check dependencies
                    if (hasCycle(dependency.toTaskId)) {
                        return true
                    }
                }
            }

            // Also check for IS_BLOCKED_BY in the reverse direction
            val incomingDeps = findByToTaskId(currentTaskId)
            for (dependency in incomingDeps) {
                if (dependency.type == DependencyType.IS_BLOCKED_BY) {
                    // If we reach the original fromTaskId, we found a cycle
                    if (dependency.fromTaskId == fromTaskId) {
                        return true
                    }

                    // Recursively check dependencies
                    if (hasCycle(dependency.fromTaskId)) {
                        return true
                    }
                }
            }

            // Done visiting this node
            visiting.remove(currentTaskId)
            visited.add(currentTaskId)

            return false
        }

        // Start DFS from the toTaskId
        return hasCycle(toTaskId)
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
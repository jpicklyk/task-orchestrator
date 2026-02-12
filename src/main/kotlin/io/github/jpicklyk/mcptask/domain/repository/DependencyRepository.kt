package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.Dependency
import java.util.*

/**
 * Repository interface for Dependency entities.
 */
interface DependencyRepository {
    /**
     * Creates a new dependency.
     * @param dependency The dependency to create
     * @return The created dependency
     */
    fun create(dependency: Dependency): Dependency
    
    /**
     * Finds a dependency by its ID.
     * @param id The dependency ID
     * @return The dependency if found, null otherwise
     */
    fun findById(id: UUID): Dependency?
    
    /**
     * Finds all dependencies for a task (both incoming and outgoing).
     * @param taskId The task ID
     * @return List of dependencies involving the task
     */
    fun findByTaskId(taskId: UUID): List<Dependency>
    
    /**
     * Finds all dependencies where the task is the source (outgoing dependencies).
     * @param fromTaskId The source task ID
     * @return List of outgoing dependencies
     */
    fun findByFromTaskId(fromTaskId: UUID): List<Dependency>
    
    /**
     * Finds all dependencies where the task is the target (incoming dependencies).
     * @param toTaskId The target task ID
     * @return List of incoming dependencies
     */
    fun findByToTaskId(toTaskId: UUID): List<Dependency>
    
    /**
     * Deletes a dependency by its ID.
     * @param id The dependency ID
     * @return True if the dependency was deleted, false if not found
     */
    fun delete(id: UUID): Boolean
    
    /**
     * Deletes all dependencies for a task.
     * @param taskId The task ID
     * @return Number of dependencies deleted
     */
    fun deleteByTaskId(taskId: UUID): Int
    
    /**
     * Creates multiple dependencies atomically in a single transaction.
     * Validates all dependencies (existence, duplicates, cycles) before inserting any.
     * Cycle detection considers the entire batch as a graph.
     *
     * @param dependencies The list of dependencies to create
     * @return The list of created dependencies
     * @throws ValidationException if any dependency fails validation (entire batch is rolled back)
     */
    fun createBatch(dependencies: List<Dependency>): List<Dependency>

    /**
     * Checks if adding a dependency would create a cycle.
     * @param fromTaskId The source task ID
     * @param toTaskId The target task ID
     * @return True if creating the dependency would cause a cycle
     */
    fun hasCyclicDependency(fromTaskId: UUID, toTaskId: UUID): Boolean
}
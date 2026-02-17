package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import java.util.UUID

/**
 * Repository for managing WorkItem dependencies.
 * Uses non-suspend functions following the v2 pattern (needed for synchronous cascade detection).
 */
interface DependencyRepository {
    fun create(dependency: Dependency): Dependency
    fun findById(id: UUID): Dependency?
    fun findByItemId(itemId: UUID): List<Dependency>
    fun findByFromItemId(fromItemId: UUID): List<Dependency>
    fun findByToItemId(toItemId: UUID): List<Dependency>
    fun delete(id: UUID): Boolean
    fun deleteByItemId(itemId: UUID): Int
    fun createBatch(dependencies: List<Dependency>): List<Dependency>
    fun hasCyclicDependency(fromItemId: UUID, toItemId: UUID): Boolean
}

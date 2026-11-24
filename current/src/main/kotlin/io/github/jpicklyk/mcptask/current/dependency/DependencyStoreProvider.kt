package io.github.jpicklyk.mcptask.current.dependency

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository

/** The stores the dependency slice reads and writes. `RepositoryProvider` implements it. */
interface DependencyStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun dependencyRepository(): DependencyStore
}

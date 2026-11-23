package io.github.jpicklyk.mcptask.current.application.port

/** The stores the lifecycle slice reads outside a unit. `RepositoryProvider` implements it. */
interface LifecycleStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun dependencyRepository(): DependencyStore
}

package io.github.jpicklyk.mcptask.current.application.port

/** The stores the lifecycle slice reads: work items, dependencies and resource leases. [RepositoryProvider] implements it. */
interface LifecycleStoreProvider : DependencyStoreProvider {
    fun resourceLeaseRepository(): LeaseStore
}

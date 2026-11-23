package io.github.jpicklyk.mcptask.current.application.port

/** The stores the config slice reads and writes. [RepositoryProvider] implements it. */
interface ConfigStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun configStore(): ConfigStore

    fun planDocumentRepository(): PlanDocumentStore
}

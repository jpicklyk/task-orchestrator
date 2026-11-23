package io.github.jpicklyk.mcptask.current.application.port

/** The stores the knowledge slice (documents, links, plan documents) reads and writes. [RepositoryProvider] implements it. */
interface KnowledgeStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun itemStore(): ItemStore

    fun hierarchyStore(): HierarchyStore

    fun planDocumentRepository(): PlanDocumentStore

    fun documentStore(): DocumentStore

    fun linkStore(): LinkStore
}

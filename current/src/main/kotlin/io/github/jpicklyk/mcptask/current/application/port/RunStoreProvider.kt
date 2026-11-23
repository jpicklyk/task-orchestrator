package io.github.jpicklyk.mcptask.current.application.port

/** The stores the run slice (runs, seats, decisions) reads and writes. [RepositoryProvider] implements it. */
interface RunStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun itemStore(): ItemStore

    fun runStore(): RunStore

    fun runSeatStore(): RunSeatStore

    fun decisionRequestStore(): DecisionRequestStore
}

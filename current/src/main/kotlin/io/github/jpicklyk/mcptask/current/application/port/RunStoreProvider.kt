package io.github.jpicklyk.mcptask.current.application.port

/**
 * The stores the run slice (runs, seats, decisions) reads and writes. It extends [LifecycleStoreProvider] because the
 * frontier reads dependencies and a raise reads the hierarchy. [RepositoryProvider] implements it.
 */
interface RunStoreProvider : LifecycleStoreProvider {
    fun runStore(): RunStore

    fun runSeatStore(): RunSeatStore

    fun decisionRequestStore(): DecisionRequestStore
}

package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.dependency.DependencyStoreProvider
import io.github.jpicklyk.mcptask.current.item.ItemStoreProvider

/**
 * The stores the run slice (runs, seats, decisions) reads and writes. It extends [ItemStoreProvider] and
 * [DependencyStoreProvider] because a raise reads the hierarchy and the frontier reads dependencies; it does not reach the
 * lifecycle slice's accessors. [RepositoryProvider] implements it.
 */
interface RunStoreProvider :
    ItemStoreProvider,
    DependencyStoreProvider {
    fun runStore(): RunStore

    fun runSeatStore(): RunSeatStore

    fun decisionRequestStore(): DecisionRequestStore
}

package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.item.ItemStore
import io.github.jpicklyk.mcptask.current.item.WorkItemRepository
import io.github.jpicklyk.mcptask.current.knowledge.PlanDocumentStore

/** The stores the config slice reads and writes. `RepositoryProvider` implements it. */
interface ConfigStoreProvider {
    fun workItemRepository(): WorkItemRepository

    fun itemStore(): ItemStore

    fun configStore(): ConfigStore

    fun planDocumentRepository(): PlanDocumentStore
}

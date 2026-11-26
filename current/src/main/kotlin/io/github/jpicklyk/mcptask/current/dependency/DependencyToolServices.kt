package io.github.jpicklyk.mcptask.current.dependency

import io.github.jpicklyk.mcptask.current.item.ItemLookup
import io.github.jpicklyk.mcptask.current.item.WorkItemRepository
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/** The services `deps_read` and `deps_write` take as `env`. Building the holder touches no store. */
class DependencyToolServices(
    val stores: DependencyStoreProvider,
    val unitOfWork: UnitOfWork<DependencyStoreProvider>,
    private val dependencyCommand: Lazy<DependencyCommandService>
) : ItemLookup {
    val dependencyCommandService: DependencyCommandService by dependencyCommand

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()
}

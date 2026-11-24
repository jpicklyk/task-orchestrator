package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.port.WorkflowStoreProvider
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService
import io.github.jpicklyk.mcptask.current.application.service.SchemaPinService
import io.github.jpicklyk.mcptask.current.dependency.DependencyCommandService
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services the structural ops of `items_write` (`create`, `update`, `delete`, `repin`, `create_tree`,
 * `complete_tree`) take as `env`. A lazy member stays unbuilt until a handler reads it, and building the holder touches
 * no store.
 */
class WorkflowToolServices(
    val stores: WorkflowStoreProvider,
    val unitOfWork: UnitOfWork<WorkflowStoreProvider>,
    val configResolver: EffectiveConfigResolver,
    private val itemCommand: Lazy<ItemCommandService>,
    private val dependencyCommand: Lazy<DependencyCommandService>,
    private val noteCommand: Lazy<NoteCommandService>,
    private val planDocument: Lazy<PlanDocumentService>,
    private val schemaPin: Lazy<SchemaPinService>,
    private val advanceFactory: Lazy<AdvanceServiceFactory>
) : ItemLookup {
    val itemCommandService: ItemCommandService by itemCommand
    val dependencyCommandService: DependencyCommandService by dependencyCommand
    val noteCommandService: NoteCommandService by noteCommand
    val planDocumentService: PlanDocumentService by planDocument
    val schemaPinService: SchemaPinService by schemaPin

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()

    fun advanceServiceFactory(): AdvanceServiceFactory = advanceFactory.value
}

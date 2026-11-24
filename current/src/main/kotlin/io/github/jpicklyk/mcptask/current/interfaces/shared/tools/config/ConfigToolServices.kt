package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.ConfigStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.platform.rest.dto.StatusGraphDto

/** The status graph of a set of type schemas, which the `effective` op reports. The server wires the lifecycle's builder. */
fun interface StatusGraphSource {
    fun buildStatusGraph(schemas: Map<String, WorkItemSchema>): StatusGraphDto
}

/**
 * The services the `config` ops take as `env`. [configService] is the shared one production wires; null in tests,
 * where the ops build their own over [stores] per call. [statusGraphs] builds the `effective` op's status graph.
 * Building the holder touches no store.
 */
class ConfigToolServices(
    val stores: ConfigStoreProvider,
    val unitOfWork: UnitOfWork<ConfigStoreProvider>,
    val configResolver: EffectiveConfigResolver,
    val configService: ConfigService?,
    val statusGraphs: StatusGraphSource
) : ItemLookup {
    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()
}

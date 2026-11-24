package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.ConfigStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services the `config` ops take as `env`. [configService] is the shared one production wires; null in tests,
 * where the ops build their own over [stores] per call. Building the holder touches no store.
 */
class ConfigToolServices(
    val stores: ConfigStoreProvider,
    val unitOfWork: UnitOfWork<ConfigStoreProvider>,
    val configResolver: EffectiveConfigResolver,
    val configService: ConfigService?
) : ItemLookup {
    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()
}

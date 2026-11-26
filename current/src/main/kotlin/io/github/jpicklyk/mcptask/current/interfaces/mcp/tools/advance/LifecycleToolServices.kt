package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.LifecycleStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.read.WorkViewQueries
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.GateStatusQuery
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.claim.ClaimService
import io.github.jpicklyk.mcptask.current.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services the `advance` ops and the lifecycle reads of `items_read` (`next`, `blocked`, `context`, `schema`) take
 * as `env`. A lazy member stays unbuilt until a handler reads it, and building the holder touches no store.
 */
class LifecycleToolServices(
    val stores: LifecycleStoreProvider,
    val unitOfWork: UnitOfWork<LifecycleStoreProvider>,
    val clock: Clock,
    val configResolver: EffectiveConfigResolver,
    val workViewQueries: WorkViewQueries,
    val nextItemRecommender: NextItemRecommender,
    private val claim: Lazy<ClaimService>,
    private val advanceFactory: Lazy<AdvanceServiceFactory>
) : ItemLookup {
    val claimService: ClaimService by claim

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()

    fun advanceServiceFactory(): AdvanceServiceFactory = advanceFactory.value

    /** The read-only gate status `context` inspects: the same snapshot loader and policy every advance uses. */
    fun gateStatusQuery(): GateStatusQuery = advanceServiceFactory().gateStatusQuery()
}

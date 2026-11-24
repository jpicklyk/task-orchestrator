package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.decision.DecisionService
import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.RunStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.run.RunLimits
import io.github.jpicklyk.mcptask.current.application.run.RunService
import io.github.jpicklyk.mcptask.current.application.run.SeatService
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services the `runs_read` selects and `runs_write` ops take as `env`. A lazy member stays unbuilt until a handler
 * reads it (a provider that serves no runs, seats or decisions throws only then), and building the holder touches no
 * store.
 */
class RunToolServices(
    val stores: RunStoreProvider,
    val unitOfWork: UnitOfWork<RunStoreProvider>,
    val clock: Clock,
    val configResolver: EffectiveConfigResolver,
    private val run: Lazy<RunService>,
    private val seat: Lazy<SeatService>,
    private val decision: Lazy<DecisionService>,
    private val limits: Lazy<RunLimits>,
    private val advanceFactory: Lazy<AdvanceServiceFactory>
) : ItemLookup {
    val runService: RunService by run
    val seatService: SeatService by seat

    /** The single owner of decision-request writes and decision events (R24). */
    val decisionService: DecisionService by decision

    /** The run limits (R27): the assign with its attempt and seat caps, raising its server decisions through [decisionService]. */
    val runLimits: RunLimits by limits

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()

    fun advanceServiceFactory(): AdvanceServiceFactory = advanceFactory.value
}

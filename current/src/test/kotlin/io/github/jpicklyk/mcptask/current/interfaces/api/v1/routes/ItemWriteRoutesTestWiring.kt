package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.ktor.server.routing.Route

/**
 * Test wiring for route suites that mount [itemWriteRoutes] without a full `ToolExecutionContext` at hand: builds the
 * required [ItemCommandService] over the SAME [repositoryProvider], [unitOfWork] and [advanceServiceFactory] (and so
 * the same config resolver) the advance route uses, exactly as `ToolExecutionContext.itemCommandService` does in
 * production. Production wiring (`CurrentMcpServer.installRestApiRoutes`) passes the context's instance explicitly.
 */
internal fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotency: IdempotencyService,
    advanceServiceFactory: AdvanceServiceFactory,
    unitOfWork: UnitOfWork,
    clock: Clock = Clock.SYSTEM,
) = itemWriteRoutes(
    repositoryProvider,
    degradedModePolicy,
    idempotency,
    advanceServiceFactory,
    unitOfWork,
    ItemCommandService(
        repositoryProvider,
        advanceServiceFactory.configResolver,
        unitOfWork,
        advanceServiceFactory,
        ClaimService(repositoryProvider, unitOfWork),
    ),
    clock = clock,
)

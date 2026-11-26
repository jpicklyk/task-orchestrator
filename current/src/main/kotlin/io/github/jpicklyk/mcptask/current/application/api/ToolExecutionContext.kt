package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.DecisionEffects
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.RunDecisionPort
import io.github.jpicklyk.mcptask.current.application.run.RunDecisionEffects
import io.github.jpicklyk.mcptask.current.application.run.RunRollupSnapshot
import io.github.jpicklyk.mcptask.current.application.run.RunService
import io.github.jpicklyk.mcptask.current.application.run.SeatService
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.claim.ClaimService
import io.github.jpicklyk.mcptask.current.config.ConfigService
import io.github.jpicklyk.mcptask.current.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.config.SchemaPinService
import io.github.jpicklyk.mcptask.current.dependency.DependencyCommandService
import io.github.jpicklyk.mcptask.current.item.WorkItemRepository
import io.github.jpicklyk.mcptask.current.knowledge.DocumentService
import io.github.jpicklyk.mcptask.current.knowledge.LinkService
import io.github.jpicklyk.mcptask.current.knowledge.PlanDocumentService
import io.github.jpicklyk.mcptask.current.knowledge.RelatedService
import io.github.jpicklyk.mcptask.current.knowledge.SearchService
import io.github.jpicklyk.mcptask.current.lifecycle.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.lifecycle.NextItemRecommender
import io.github.jpicklyk.mcptask.current.lifecycle.WorkViewQueries
import io.github.jpicklyk.mcptask.current.note.NoteCommandService
import io.github.jpicklyk.mcptask.current.platform.eventrecording.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.platform.idempotency.IdempotencyService
import io.github.jpicklyk.mcptask.current.platform.port.BodySource
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import java.util.UUID

/**
 * Execution context provided to every tool operation (MCP and REST) during invocation.
 *
 * Provides typed access to all repository interfaces via the [RepositoryProvider] and the shared
 * application services, so operations interact with the persistence layer without direct knowledge
 * of the repository implementations.
 */
class ToolExecutionContext(
    val repositoryProvider: RepositoryProvider,
    val nextItemRecommender: NextItemRecommender =
        NextItemRecommender(repositoryProvider.workItemRepository()),
    perRootConfigService: PerRootConfigSource? = null,
    /**
     * The single config resolution entry point (schemas, traits, dispatch profiles, status labels). Defaults to a
     * resolver with NO server config (an empty server layer) over `perRootConfigService`; production
     * and any test that needs server schemas pass a resolver built over a real server [LayerBackedGlobalLookup].
     */
    val configResolver: EffectiveConfigResolver =
        EffectiveConfigResolver(
            LayerBackedGlobalLookup(null),
            perRootConfigService
        ),
    /** Time source for units of work; production wires the system clock. */
    val clock: Clock = Clock.SYSTEM,
    /**
     * The transaction boundary. Production wires the SQLite implementation; the default runs blocks
     * without a transaction so tests with mocked stores keep constructing the context unchanged.
     */
    val unitOfWork: UnitOfWork<RepositoryProvider> = UnscopedUnitOfWork(repositoryProvider, clock),
    /**
     * The read model (the `next`, `blocked`, `overview` and `context` views), one read unit per view. Production
     * builds ONE instance in `ServerComposition`; the default builds one over [unitOfWork].
     */
    val workViewQueries: WorkViewQueries = WorkViewQueries(unitOfWork),
    /** The shared config service; null in tests, where tools build their own over [repositoryProvider]. */
    val configService: ConfigService? = null,
    /**
     * Reads the file a `bodyFromFile` field names. Production wires a `FileBodySource` over the agent config root,
     * built once in `ServerComposition`; the default rejects every path (fail closed), so a context nobody wired a
     * root into can never read a file.
     */
    val bodySource: BodySource = BodySource.DISABLED,
    /** The lease kill switch (`AppConfig.resourceLeasesEnforced`, read once at startup). */
    private val resourceLeasesEnforced: Boolean = true,
    /** What an answer to a server-raised decision class does (R27); null (the default) wires [RunDecisionEffects]. */
    decisionEffects: DecisionEffects? = null,
    /**
     * The run rollup snapshot a close stores in `run.ended` (R28). Production wires the report-backed one in
     * `ServerComposition`; the default stores none.
     */
    private val rollupSnapshot: RunRollupSnapshot = RunRollupSnapshot.NONE,
) {
    private val decisionEffectsOverride: DecisionEffects? = decisionEffects

    /**
     * What an answer to a server-raised decision class does inside the answer's unit (R27): the constructor's override,
     * else [RunDecisionEffects] over [runService]. Lazy for the same reason as [runService].
     */
    val decisionEffects: DecisionEffects by lazy { decisionEffectsOverride ?: RunDecisionEffects(runService) }

    /**
     * Durable per-element idempotency over [unitOfWork]. Lazy so contexts that never serve a keyed call
     * (most tests) do not build it.
     */
    val idempotency: IdempotencyService<RepositoryProvider> by lazy { IdempotencyService(unitOfWork) }

    /**
     * Lazy so 17+ test files that strict-mock [RepositoryProvider] are unaffected by an eager
     * accessor call at construction time; built once per [ToolExecutionContext] instance.
     */
    private val advanceServiceFactoryLazy by lazy {
        AdvanceServiceFactory(
            workItemRepository(),
            repositoryProvider.dependencyRepository(),
            repositoryProvider.noteRepository(),
            repositoryProvider.resourceLeaseRepository(),
            configResolver,
            unitOfWork,
            resourceLeasesEnforced = { resourceLeasesEnforced },
            clock = clock,
            claimService = claimService,
            schemaPinService = schemaPinService,
            openRunResolver = ::openRunOf
        )
    }

    /**
     * The not-closed run (open or paused) [itemId] is a member of, for seat enforcement (R23); read inside the caller's
     * unit. A provider that serves no runs (its `runStore()` throws [UnsupportedOperationException], as the interface
     * default does) has none, so it never enforces seats.
     */
    private suspend fun openRunOf(itemId: UUID): UUID? {
        val runStore =
            try {
                repositoryProvider.runStore()
            } catch (e: UnsupportedOperationException) {
                return null
            }
        return runStore.openRunByItem(setOf(itemId))[itemId]
    }

    /**
     * THE pin writer (plan section 3.8), shared by the item command service and every built advance service, and the
     * pin view the item read surfaces render. Pins are on only when [configResolver] reads stored versions by id.
     * Lazy for the same reason as [advanceServiceFactoryLazy]; the config store is touched only when pins are on.
     */
    val schemaPinService: SchemaPinService by lazy {
        SchemaPinService(configResolver, if (configResolver.pinsEnabled) repositoryProvider.configStore() else null)
    }

    /** The single owner of claim and resource-lease writes and their events. Lazy for the same reason as [advanceServiceFactoryLazy]. */
    val claimService: ClaimService by lazy { ClaimService(repositoryProvider, unitOfWork) }

    /**
     * The single owner of run writes and run events, with [seatService] as its seat port. Lazy for the same reason as
     * [advanceServiceFactoryLazy], and because a provider that serves no runs throws from `runStore()`.
     */
    val runService: RunService by lazy {
        RunService(
            repositoryProvider,
            unitOfWork,
            claimService,
            runsConfig = { rootId -> configResolver.resolveRunsConfig(rootId) },
            seats = seatService,
            decisions = RunDecisionPort.over(repositoryProvider),
            rollupSnapshot = rollupSnapshot
        )
    }

    /**
     * The single owner of seat-assignment writes, seat tokens and seat events (R22). Lazy for the same reason as
     * [runService]; its store is reached only when a seat is touched (a provider that serves no seats throws then).
     */
    val seatService: SeatService by lazy { SeatService(repositoryProvider, unitOfWork, claimService, configResolver) }

    /**
     * The single owner of the note write policy, shared by `notes_write` and `items_write` `create_tree`. Lazy for the same reason as [advanceServiceFactoryLazy].
     */
    val noteCommandService: NoteCommandService by lazy {
        NoteCommandService(repositoryProvider, configResolver, unitOfWork, openRunResolver = ::openRunOf)
    }

    /**
     * The single owner of the dependency write policy (normalization, duplicate and cycle rules), shared by
     * `deps_write` and `items_write` `create_tree`. Lazy for the same reason as
     * [advanceServiceFactoryLazy].
     */
    val dependencyCommandService: DependencyCommandService by lazy { DependencyCommandService(repositoryProvider, unitOfWork) }

    /**
     * The single owner of item creates, patches and deletes (placement, structural rules, item events), shared by
     * `items_write` (`create`, `update`, `delete`, `create_tree`) and the structural edits that cascade. Lazy for the same reason as
     * [advanceServiceFactoryLazy].
     */
    val itemCommandService: ItemCommandService by lazy {
        ItemCommandService(
            repositoryProvider,
            configResolver,
            unitOfWork,
            advanceServiceFactory(),
            claimService,
            schemaPinService = schemaPinService
        )
    }

    /** The plan-document write owner (stash, and the adoption `items_write` `create_tree` runs in its unit). */
    val planDocumentService: PlanDocumentService by lazy { PlanDocumentService(repositoryProvider, unitOfWork) }

    /** The document write owner, shared by `docs_write`. Lazy for the same reason as [advanceServiceFactoryLazy]. */
    val documentService: DocumentService by lazy { DocumentService(repositoryProvider, unitOfWork) }

    /** The link write owner (attach and detach), shared by `docs_write`. Lazy for the same reason as [advanceServiceFactoryLazy]. */
    val linkService: LinkService by lazy { LinkService(repositoryProvider, unitOfWork) }

    /**
     * Full-text search over items and notes, built from [RepositoryProvider.searchIndex] on first use (a provider
     * without a search index fails only when a search actually runs).
     */
    val searchService: SearchService by lazy { SearchService(repositoryProvider.searchIndex(), unitOfWork) }

    /**
     * The `related` read (weighted fusion of dependency, link, tag and text signals), built from
     * [RepositoryProvider.searchIndex] on first use, like [searchService].
     */
    val relatedService: RelatedService by lazy { RelatedService(repositoryProvider.searchIndex(), unitOfWork) }

    /** Access to WorkItem CRUD and query operations. */
    fun workItemRepository(): WorkItemRepository = repositoryProvider.workItemRepository()

    /**
     * The shared [AdvanceServiceFactory] for this context, backing every `advance` `transition` /
     * `items_write` `complete_tree` construction of [io.github.jpicklyk.mcptask.current.lifecycle.AdvanceService].
     */
    fun advanceServiceFactory(): AdvanceServiceFactory = advanceServiceFactoryLazy
}

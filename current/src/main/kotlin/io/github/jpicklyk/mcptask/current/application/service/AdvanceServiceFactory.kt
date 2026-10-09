package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem

/**
 * Builds a per-item [AdvanceService] (status labels bound to that item's `rootId` through
 * [EffectiveConfigResolver.labelFor]) and the shared [TransitionPreview], from ONE shared
 * [configResolver].
 *
 * The single construction site for [AdvanceService] in production: the MCP `advance_item` tool,
 * `complete_tree`, and the REST advance route all call [forItem]; `get_context`, `get_next_status` and
 * `GET /items/{id}/gate` evaluate through [preview], so previews and advances share one loader and one
 * policy.
 */
class AdvanceServiceFactory(
    private val workItemRepository: WorkItemRepository,
    private val roleTransitionRepository: TransitionStore,
    private val dependencyRepository: DependencyStore,
    private val noteRepository: NoteStore,
    private val resourceLeaseRepository: LeaseStore?,
    val configResolver: EffectiveConfigResolver,
    /** The transaction boundary every built [AdvanceService] runs its unit in. */
    private val unitOfWork: UnitOfWork,
    private val resourceLeasesEnforced: () -> Boolean = { AdvanceService.resourceLeasesEnforcedFromEnv() },
    /** The one time source every built [AdvanceService] reads (the ambient unit instant wins inside a unit). */
    private val clock: Clock = Clock.SYSTEM,
    /** Where every built [AdvanceService] routes its claim and lease writes (and their events). */
    private val claimService: ClaimService? = null,
) {
    private val previewLazy by lazy {
        TransitionPreview(
            unitOfWork,
            TransitionSnapshotLoader(
                workItemRepository,
                dependencyRepository,
                noteRepository,
                resourceLeaseRepository,
                schemaResolver = { configResolver.resolveSchema(it) },
                resourceRequirementsResolver = { configResolver.resolveResourceRequirements(it) },
                independencePolicyResolver = { configResolver.resolveIndependencePolicy(it.rootId) }
            ),
            resourceLeasesEnforced
        )
    }

    /**
     * Builds the [AdvanceService] for advances of [item] (and the cascades they trigger), with status
     * labels resolved under [item]'s `rootId`. Nothing is read here: the schema, independence policy,
     * resource requirements, registry and labels of the item and of every cascade target are read inside
     * the advance's write unit, where the per-root last-known-good fallback is disabled. A per-root read
     * fault on the advanced item therefore surfaces from [AdvanceService.advance] as a
     * `PerRootConfigUnavailableException` (`config_unavailable`), keyed or unkeyed alike; one on a cascade
     * target skips that cascade and the primary still commits.
     *
     * [resourceLeasesEnforced] is read fresh on EVERY call.
     */
    fun forItem(item: WorkItem): AdvanceService {
        val rootId = item.rootId
        return AdvanceService(
            workItemRepository = workItemRepository,
            roleTransitionRepository = roleTransitionRepository,
            dependencyRepository = dependencyRepository,
            noteRepository = noteRepository,
            labelFor = { trigger, target -> configResolver.labelFor(rootId, trigger, target) },
            schemaResolver = { configResolver.resolveSchema(it) },
            unitOfWork = unitOfWork,
            resourceLeaseRepository = resourceLeaseRepository,
            resourceRequirementsResolver = { configResolver.resolveResourceRequirements(it) },
            resourceRegistryResolver = { configResolver.resolveResourceRegistry(it) },
            resourceLeasesEnforced = resourceLeasesEnforced(),
            independencePolicyResolver = { workItem: WorkItem -> configResolver.resolveIndependencePolicy(workItem.rootId) },
            clock = clock,
            claimService = claimService
        )
    }

    /** The shared read-only [TransitionPreview] (lease kill switch read per evaluation). */
    fun preview(): TransitionPreview = previewLazy
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Builds a per-item [AdvanceService] (status labels bound to that item's `rootId` through
 * [EffectiveConfigResolver.labelFor]) and the shared [TransitionPreview], from ONE shared
 * [configResolver].
 *
 * The single construction site for [AdvanceService] in production: the `advance` `transition` op,
 * `items_write` `complete_tree` and the structural cascades of `items_write` all call [forItem]; `items_read`
 * `select=context` evaluates through [preview], so previews and advances share one loader and one
 * policy.
 */
class AdvanceServiceFactory(
    private val workItemRepository: WorkItemRepository,
    private val dependencyRepository: DependencyStore,
    private val noteRepository: NoteStore,
    private val resourceLeaseRepository: LeaseStore?,
    val configResolver: EffectiveConfigResolver,
    /** The transaction boundary every built [AdvanceService] runs its unit in. */
    private val unitOfWork: UnitOfWork,
    private val resourceLeasesEnforced: () -> Boolean = { true },
    /** The one time source every built [AdvanceService] reads (the ambient unit instant wins inside a unit). */
    private val clock: Clock = Clock.SYSTEM,
    /** Where every built [AdvanceService] routes its claim and lease writes (and their events). */
    private val claimService: ClaimService,
    /** The pin writer every built [AdvanceService] re-pins with when a transition leaves TERMINAL. */
    private val schemaPinService: SchemaPinService = SchemaPinService(configResolver),
    /**
     * The not-closed run an item is a member of, for the SEAT gate (R23) of every built [AdvanceService]; the default
     * resolves none (seats never enforced). Previews never read it: they evaluate without a caller.
     */
    private val openRunResolver: suspend (UUID) -> UUID? = { null }
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
     * [resourceLeasesEnforced] is invoked on EVERY call (production binds it to the value read once at startup).
     */
    fun forItem(item: WorkItem): AdvanceService {
        val rootId = item.rootId
        return AdvanceService(
            workItemRepository = workItemRepository,
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
            claimService = claimService,
            schemaPinService = schemaPinService,
            openRunResolver = openRunResolver
        )
    }

    /** The shared read-only [TransitionPreview] (lease kill switch read per evaluation). */
    fun preview(): TransitionPreview = previewLazy
}

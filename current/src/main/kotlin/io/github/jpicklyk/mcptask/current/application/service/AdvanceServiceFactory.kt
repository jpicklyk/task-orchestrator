package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository

/**
 * Builds a per-item [AdvanceService], bound to that item's `rootId` (per-root status-label
 * layering — see [EffectiveConfigResolver.rootBoundStatusLabels]) and config-resolved
 * schema/resource wiring, from ONE shared [configResolver].
 *
 * The single construction site for [AdvanceService] in production: the MCP `advance_item` tool,
 * `complete_tree`, and the REST advance route all call [forItem] instead of hand-wiring an
 * `AdvanceService(...)` themselves, so the three previously-separate construction sites (and their
 * risk of drifting apart, as in bug 80e48e55/3e455253) collapse into one.
 */
class AdvanceServiceFactory(
    private val workItemRepository: WorkItemRepository,
    private val roleTransitionRepository: RoleTransitionRepository,
    private val dependencyRepository: DependencyRepository,
    private val noteRepository: NoteRepository,
    private val resourceLeaseRepository: ResourceLeaseRepository?,
    val configResolver: EffectiveConfigResolver,
    private val resourceLeasesEnforced: () -> Boolean = { AdvanceService.resourceLeasesEnforcedFromEnv() },
) {
    /**
     * Builds the [AdvanceService] for a single advance of [item] via [trigger]. Bound to [item]'s
     * `rootId` for per-root status-label layering — mirrors the pre-refactor per-site inline
     * construction byte-for-byte. May propagate `PerRootConfigUnavailableException` from the
     * status-label resolution.
     *
     * [resourceLeasesEnforced] is read fresh on EVERY call, matching the prior per-call
     * `AdvanceService.resourceLeasesEnforcedFromEnv()` reads at each construction site.
     */
    suspend fun forItem(
        item: WorkItem,
        trigger: String
    ): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepository,
            roleTransitionRepository = roleTransitionRepository,
            dependencyRepository = dependencyRepository,
            noteRepository = noteRepository,
            statusLabelService = configResolver.rootBoundStatusLabels(item.rootId, trigger),
            schemaResolver = { configResolver.resolveSchema(it) },
            resourceLeaseRepository = resourceLeaseRepository,
            resourceRequirementsResolver = { configResolver.resolveResourceRequirements(it) },
            resourceRegistryResolver = { configResolver.resolveResourceRegistry(it) },
            resourceLeasesEnforced = resourceLeasesEnforced()
        )
}

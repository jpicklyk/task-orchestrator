package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.LayeredConfig
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.SchemaMatch
import io.github.jpicklyk.mcptask.current.application.config.ServiceBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import java.util.UUID

/**
 * Execution context provided to all MCP tools during invocation.
 *
 * Provides typed access to all repository interfaces via the [RepositoryProvider].
 * Tools receive this context in their [ToolDefinition.execute] method,
 * enabling them to interact with the persistence layer without direct
 * knowledge of the repository implementations.
 */
class ToolExecutionContext(
    val repositoryProvider: RepositoryProvider,
    private val noteSchemaService: NoteSchemaService = NoOpNoteSchemaService,
    private val statusLabelService: StatusLabelService = NoOpStatusLabelService,
    private val actorVerifier: ActorVerifier = NoOpActorVerifier,
    val degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
    val idempotencyCache: IdempotencyCache = IdempotencyCache(),
    val nextItemRecommender: NextItemRecommender =
        NextItemRecommender(
            repositoryProvider.workItemRepository(),
            repositoryProvider.dependencyRepository()
        ),
    perRootConfigService: PerRootConfigSource? = null,
    /**
     * The single resolution entry point every resolver method below delegates to. Defaults to a
     * resolver over the legacy global services and `perRootConfigService`, so existing
     * constructions compile and behave unchanged.
     */
    val configResolver: EffectiveConfigResolver =
        EffectiveConfigResolver(
            ServiceBackedGlobalLookup(noteSchemaService, statusLabelService),
            perRootConfigService
        ),
) {
    /** Access to WorkItem CRUD and query operations. */
    fun workItemRepository(): WorkItemRepository = repositoryProvider.workItemRepository()

    /** Access to Note upsert, query, and delete operations. */
    fun noteRepository(): NoteRepository = repositoryProvider.noteRepository()

    /** Access to Dependency graph operations (synchronous, non-suspend). */
    fun dependencyRepository(): DependencyRepository = repositoryProvider.dependencyRepository()

    /** Access to RoleTransition audit trail operations. */
    fun roleTransitionRepository(): RoleTransitionRepository = repositoryProvider.roleTransitionRepository()

    /** Access to Note schema configuration service. */
    fun noteSchemaService(): NoteSchemaService = noteSchemaService

    /** Access to the status label configuration service. */
    fun statusLabelService(): StatusLabelService = statusLabelService

    /** Access to the actor claim verification service. */
    fun actorVerifier(): ActorVerifier = actorVerifier

    /** Access to the atomic work-tree creation executor. */
    fun workTreeExecutor(): WorkTreeExecutor = repositoryProvider.workTreeExecutor()

    /** Access to per-root config (raw YAML document) CRUD operations. */
    fun projectConfigRepository(): ProjectConfigRepository = repositoryProvider.projectConfigRepository()

    /**
     * Resolves the effective [WorkItemSchema] for a [WorkItem], including trait note merging.
     * Delegates to [EffectiveConfigResolver.resolveSchema]; see [LayeredConfig] for the LEGACY
     * per-root-over-global precedence table (type step, tag step, trait notes).
     */
    suspend fun resolveSchema(item: WorkItem): WorkItemSchema? = configResolver.resolveSchema(item)

    /**
     * Same resolution as [resolveSchema], but also reports which config layer supplied the BASE
     * schema and that layer's config fingerprint, which is what `query_items`'s `schema` operation
     * needs for its `configSource`/`configFingerprint` fields. Delegates to
     * [EffectiveConfigResolver.resolveSchemaWithSource].
     */
    suspend fun resolveSchemaWithSource(item: WorkItem): ResolvedSchema? = configResolver.resolveSchemaWithSource(item)

    /**
     * Type-only counterpart to [resolveSchemaWithSource] for callers that have a type name but no
     * [WorkItem]: no tag fallback and no trait merging. Returns null when neither layer defines [type].
     */
    suspend fun resolveTypeSchema(
        type: String,
        rootId: UUID?
    ): ResolvedSchema? = configResolver.resolveTypeSchema(type, rootId)

    /**
     * Returns true if the resolved (trait-merged) schema for [item] has a REVIEW phase; false when
     * no schema matches (schema-free mode, skip REVIEW).
     */
    suspend fun resolveHasReviewPhase(item: WorkItem): Boolean = configResolver.resolveHasReviewPhase(item)

    /**
     * Resolves the effective [ResourceRequirement] list for [item]'s traits (config layer only;
     * nothing here acquires or enforces a lease). Delegates to
     * [EffectiveConfigResolver.resolveResourceRequirements].
     */
    suspend fun resolveResourceRequirements(item: WorkItem): List<ResourceRequirement> = configResolver.resolveResourceRequirements(item)

    /**
     * Type-only counterpart to [resolveResourceRequirements]: [defaultTraits] is the complete trait
     * set. Delegates to [EffectiveConfigResolver.resolveResourceRequirementsForType].
     */
    suspend fun resolveResourceRequirementsForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): List<ResourceRequirement> = configResolver.resolveResourceRequirementsForType(defaultTraits, rootId)

    /**
     * Resolves the dispatch routing profile for [item] at [role]. Convenience overload for callers
     * with no already-resolved schema in hand: resolves it via [resolveSchema] first, then delegates
     * to the 3-arg overload. Callers that already have a resolved schema MUST use the 3-arg overload
     * instead: this one costs a second, redundant schema resolution (and its own per-root read).
     */
    suspend fun resolveDispatchProfile(
        item: WorkItem,
        role: Role
    ): DispatchProfile? {
        val resolvedSchema = resolveSchema(item)
        return resolveDispatchProfile(item, role, resolvedSchema)
    }

    /**
     * Resolves the dispatch routing profile for [item] at [role], using [resolvedSchema]'s
     * `defaultTraits` instead of re-resolving the schema. A trait-less item costs zero per-root
     * reads (`AdvanceItemToolTest.kt:2119` pins the rooted, trait-less advance's read count). Trait
     * order is item traits first, then defaultTraits (the reverse of note merging). Delegates to
     * [EffectiveConfigResolver.resolveDispatchProfile].
     */
    suspend fun resolveDispatchProfile(
        item: WorkItem,
        role: Role,
        resolvedSchema: WorkItemSchema?
    ): DispatchProfile? = configResolver.resolveDispatchProfile(item, role, resolvedSchema)

    /**
     * Per-phase counterpart to the 3-arg [resolveDispatchProfile]: a profile for EVERY phase
     * [item]'s traits declare. Delegates to [EffectiveConfigResolver.resolveDispatchProfiles].
     */
    suspend fun resolveDispatchProfiles(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): Map<Role, DispatchProfile> = configResolver.resolveDispatchProfiles(item, resolvedSchema)

    /**
     * Type-only counterpart to [resolveDispatchProfiles]: [defaultTraits] is the complete trait set.
     * Delegates to [EffectiveConfigResolver.resolveDispatchProfilesForType].
     */
    suspend fun resolveDispatchProfilesForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): Map<Role, DispatchProfile> = configResolver.resolveDispatchProfilesForType(defaultTraits, rootId)

    /**
     * Resolves the effective resource registry (top-level `resources:`) visible to [rootId]. GLOBAL
     * WINS on a key collision (logged). Delegates to [EffectiveConfigResolver.resolveResourceRegistry].
     */
    suspend fun resolveResourceRegistry(rootId: UUID?): Map<String, ResourceDefinition> = configResolver.resolveResourceRegistry(rootId)

    /**
     * Layered `note_limits.mode` resolution: [rootId]'s per-root explicit value wins, else the
     * global mode. Delegates to [EffectiveConfigResolver.resolveNoteLimitsMode].
     */
    suspend fun resolveNoteLimitsMode(rootId: UUID?): String = configResolver.resolveNoteLimitsMode(rootId)

    /**
     * Layered status-label resolution for a single [trigger]: an explicit per-root key for
     * [trigger] wins (including an explicit `null` value); otherwise the global label.
     */
    suspend fun resolveStatusLabel(
        trigger: String,
        rootId: UUID?
    ): String? = resolveStatusLabels(listOf(trigger), rootId)[trigger]

    /**
     * Batched counterpart to [resolveStatusLabel]: every trigger in [triggers] from a SINGLE
     * per-root read. Delegates to [EffectiveConfigResolver.resolveStatusLabels].
     */
    suspend fun resolveStatusLabels(
        triggers: Collection<String>,
        rootId: UUID?
    ): Map<String, String?> = configResolver.resolveStatusLabels(triggers, rootId)

    /**
     * Builds a synchronous [StatusLabelService] bound to [rootId] and pre-resolved for [trigger],
     * `"complete"` and `"cascade"`, for handing to
     * [io.github.jpicklyk.mcptask.current.application.service.AdvanceService]. Shared by the MCP
     * advance tool and the REST advance route (bug 80e48e55). Delegates to
     * [EffectiveConfigResolver.rootBoundStatusLabels].
     */
    suspend fun rootAwareStatusLabelService(
        rootId: UUID?,
        trigger: String
    ): StatusLabelService = configResolver.rootBoundStatusLabels(rootId, trigger)

    /**
     * Returns the union of trait names available for the given [rootIds], per-root traits first,
     * followed by the global trait list, distinct. Delegates to
     * [EffectiveConfigResolver.availableTraits].
     */
    suspend fun availableTraits(rootIds: Collection<UUID>): List<String> = configResolver.availableTraits(rootIds)
}

/** Which config layer supplied a resolved schema; see [ToolExecutionContext.resolveSchemaWithSource]. */
typealias SchemaSource = ConfigSource

/**
 * A resolved [WorkItemSchema] together with which layer supplied its base schema and that layer's
 * config fingerprint. Returned by [ToolExecutionContext.resolveSchemaWithSource] and
 * [ToolExecutionContext.resolveTypeSchema].
 */
typealias ResolvedSchema = SchemaMatch

/**
 * Runs [block] and, on [PerRootConfigUnavailableException], logs one WARN naming [what] and [id]
 * and returns null instead of propagating. Shared by the several post-commit "decoration" call
 * sites (dispatch hints, `schemaMatch`/`expectedNotes`, `availableTraits`, the `itemContext` entry)
 * where the underlying write already succeeded — per D7, a per-root config read failure resolving a
 * response-only hint must never be reported as a failure of an already-committed operation. Callers
 * combine this with `?: continue` where the omission means skipping to the next loop item.
 */
internal inline fun <T> omitOnConfigUnavailable(
    logger: org.slf4j.Logger,
    what: String,
    id: Any?,
    block: () -> T
): T? =
    try {
        block()
    } catch (e: PerRootConfigUnavailableException) {
        logger.warn(
            "Per-root config unavailable resolving {} for {}; omitting from an already-committed operation: {}",
            what,
            id,
            e.message
        )
        null
    }

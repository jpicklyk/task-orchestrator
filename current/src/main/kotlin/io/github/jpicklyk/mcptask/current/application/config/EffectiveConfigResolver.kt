package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.statusLabelKey
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The single entry point for effective (layered) config resolution: schema, traits, resources,
 * dispatch, resource registry, note-limits mode, status labels and trait-name hints, each resolved
 * against [rootId]'s per-root layer ([perRoot]) over the [global] fallback per the root's effective
 * `schema_resolution` mode (see [LayeredConfig]'s facet table and [LayeredConfig.effectiveMode]).
 *
 * A null `rootId`, or no [perRoot] source wired, skips the per-root layer entirely: zero per-root
 * reads. Otherwise every public method reads the per-root layer independently (one read per call)
 * at the point it needs it; a method whose trait list is empty returns before any per-root read
 * where documented.
 *
 * `PerRootConfigUnavailableException` from [perRoot] propagates unchanged from every method: a
 * per-root config read failure is never treated as "no per-root config, use the global layer".
 * Callers at an operation boundary catch it and report the transient `config_unavailable` outcome.
 */
class EffectiveConfigResolver(
    val global: GlobalConfigLookup,
    private val perRoot: PerRootConfigSource?,
) {
    /** Fetches [rootId]'s per-root layer (see class kdoc for the null/no-source cases) and pairs it with [global]. */
    suspend fun layered(rootId: UUID?): LayeredConfig = LayeredConfig(rootId, layerFor(rootId), global)

    /** Effective (trait-merged) schema for [item], or null when no schema matches (schema-free). */
    suspend fun resolveSchema(item: WorkItem): WorkItemSchema? {
        val layered = layered(item.rootId)
        val baseSchema = layered.baseSchema(item.type, item.tagList())?.first ?: return null
        return layered.mergeTraits(item, baseSchema)
    }

    /**
     * Same as [resolveSchema], plus which layer supplied the BASE schema and that layer's
     * fingerprint (fetched after trait merging, and from the global side only when the base came
     * from the global layer).
     */
    suspend fun resolveSchemaWithSource(item: WorkItem): SchemaMatch? {
        val layered = layered(item.rootId)
        val (baseSchema, source) = layered.baseSchema(item.type, item.tagList()) ?: return null
        val merged = layered.mergeTraits(item, baseSchema)
        return SchemaMatch(merged, source, layered.fingerprintFor(source))
    }

    /** Type-only schema lookup (no tag step, no trait merging); null when neither layer defines [type]. */
    suspend fun resolveTypeSchema(
        type: String,
        rootId: UUID?
    ): SchemaMatch? = layered(rootId).resolveTypeSchema(type)

    /** True when [item]'s resolved schema has a REVIEW phase; false when no schema matches. */
    suspend fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false

    /**
     * Resource requirements for [item]'s traits: `(base schema's defaultTraits, if any) + item
     * traits`, distinct; a null base schema is tolerated (item traits are still honored).
     */
    suspend fun resolveResourceRequirements(item: WorkItem): List<ResourceRequirement> {
        val layered = layered(item.rootId)
        val baseSchema = layered.baseSchema(item.type, item.tagList())?.first
        val defaultTraits = baseSchema?.defaultTraits ?: emptyList()
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val allTraits = (defaultTraits + itemTraits).distinct()
        return layered.mergeResourceRequirements(allTraits)
    }

    /** Resource requirements for a type's [defaultTraits] alone (no item). */
    suspend fun resolveResourceRequirementsForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): List<ResourceRequirement> {
        val layered = layered(rootId)
        return layered.mergeResourceRequirements(defaultTraits.distinct())
    }

    /**
     * Dispatch profile for [item] at [role], using [resolvedSchema]'s defaultTraits. Trait order is
     * item traits FIRST, then defaultTraits (the reverse of note merging); the first trait with a
     * profile for [role] wins outright.
     */
    suspend fun resolveDispatchProfile(
        item: WorkItem,
        role: Role,
        resolvedSchema: WorkItemSchema?
    ): DispatchProfile? = resolveDispatchProfilesForTraits(dispatchTraitsFor(item, resolvedSchema), item.rootId)[role]

    /** Per-phase counterpart to [resolveDispatchProfile]: every role [item]'s traits declare. */
    suspend fun resolveDispatchProfiles(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): Map<Role, DispatchProfile> = resolveDispatchProfilesForTraits(dispatchTraitsFor(item, resolvedSchema), item.rootId)

    /** Type-only counterpart to [resolveDispatchProfiles]: [defaultTraits] is the complete trait set. */
    suspend fun resolveDispatchProfilesForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): Map<Role, DispatchProfile> = resolveDispatchProfilesForTraits(defaultTraits.distinct(), rootId)

    /**
     * Per-seat dispatch profiles for [item], keyed by phase then seat name, filtered to
     * [resolvedSchema]'s (already trait-merged) seats. Same trait order as [resolveDispatchProfile]
     * (item traits first, then the resolved schema's defaultTraits). Returns before any per-root
     * read when either the trait list or [resolvedSchema]'s seats are empty.
     */
    suspend fun resolveDispatchBySeat(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): Map<Role, Map<String, DispatchProfile>> {
        val traits = dispatchTraitsFor(item, resolvedSchema)
        val seats = resolvedSchema?.seats ?: emptyList()
        if (traits.isEmpty() || seats.isEmpty()) return emptyMap()
        return layered(item.rootId).mergeDispatchBySeat(traits, seats)
    }

    /** Type-only counterpart to [resolveDispatchBySeat]: [defaultTraits] is the complete trait set. */
    suspend fun resolveDispatchBySeatForType(
        defaultTraits: List<String>,
        seats: List<SeatDefinition>,
        rootId: UUID?
    ): Map<Role, Map<String, DispatchProfile>> {
        val traits = defaultTraits.distinct()
        if (traits.isEmpty() || seats.isEmpty()) return emptyMap()
        return layered(rootId).mergeDispatchBySeat(traits, seats)
    }

    /** Resource registry visible to [rootId]; global wins on a key collision (see [LayeredConfig.resourceRegistry]). */
    suspend fun resolveResourceRegistry(rootId: UUID?): Map<String, ResourceDefinition> = layered(rootId).resourceRegistry()

    /** `note_limits.mode` for [rootId]: per-root explicit value, else global. */
    suspend fun resolveNoteLimitsMode(rootId: UUID?): String = layered(rootId).noteLimitsMode()

    /** `independence:` policy (A2) for [rootId]: per-root block wins WHOLESALE, else global. */
    suspend fun resolveIndependencePolicy(rootId: UUID?): IndependencePolicy = layered(rootId).independencePolicy()

    /** Status labels for every trigger in [triggers] from ONE per-root read (see [LayeredConfig.statusLabel]). */
    suspend fun resolveStatusLabels(
        triggers: Collection<String>,
        rootId: UUID?
    ): Map<String, String?> {
        val layered = layered(rootId)
        return triggers.associateWith { trigger -> layered.statusLabel(trigger) }
    }

    /**
     * The ONE label policy for an applied transition: the status label for [trigger] entering [target]
     * under [rootId] (per-root `status_labels` key, else global, else the built-in defaults). The key is
     * [statusLabelKey]: `"cascade"` for any cascade, `"complete"` for a `start` into TERMINAL, else the
     * trigger's wire name. One per-root read per call.
     */
    suspend fun labelFor(
        rootId: UUID?,
        trigger: Trigger,
        target: Role
    ): String? = layered(rootId).statusLabel(statusLabelKey(trigger, target))

    /**
     * Union of trait names for [rootIds]: per-root trait keys first (one per-root read per ELEMENT
     * of [rootIds], in iteration order), then the global trait names, distinct. No per-root source
     * wired means the global list only.
     */
    suspend fun availableTraits(rootIds: Collection<UUID>): List<String> {
        val perRootTraits =
            if (perRoot != null) {
                rootIds.flatMap { rootId ->
                    layerFor(rootId)
                        ?.document
                        ?.traits
                        ?.keys
                        .orEmpty()
                }
            } else {
                emptyList()
            }
        return (perRootTraits + global.traitNames()).distinct()
    }

    /** Trait list for dispatch resolution: item traits first, then [resolvedSchema]'s defaultTraits. */
    private fun dispatchTraitsFor(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): List<String> {
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val defaultTraits = resolvedSchema?.defaultTraits ?: emptyList()
        return (itemTraits + defaultTraits).distinct()
    }

    /** Returns BEFORE any per-root read when [traits] is empty (the common, trait-less case). */
    private suspend fun resolveDispatchProfilesForTraits(
        traits: List<String>,
        rootId: UUID?
    ): Map<Role, DispatchProfile> {
        if (traits.isEmpty()) return emptyMap()
        return layered(rootId).mergeDispatch(traits)
    }

    /**
     * One per-root read for [rootId], or null (no read) when [rootId] is null or no source is
     * wired. When a [ConfigSession] is ambient in the coroutine context (installed by
     * [withConfigSession] at the call boundary), the read is memoised per `(perRoot, rootId)` for
     * the lifetime of that session — see [ConfigSession] for the memo contract, including the
     * `runBlocking` caveat. With no ambient session, every call reads fresh (O2 requires this: T0
     * and existing direct-[EffectiveConfigResolver] callers depend on unmemoised reads).
     */
    private suspend fun layerFor(rootId: UUID?): ConfigLayer? {
        if (rootId == null || perRoot == null) return null
        val session = coroutineContext[ConfigSession.Key]
        return if (session != null) {
            session.memoized(perRoot, rootId) { perRoot.layer(rootId) }
        } else {
            perRoot.layer(rootId)
        }
    }
}

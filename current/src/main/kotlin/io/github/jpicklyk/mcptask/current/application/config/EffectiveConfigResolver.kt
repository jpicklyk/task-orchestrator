package io.github.jpicklyk.mcptask.current.application.config

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
 * against [rootId]'s per-root layer ([perRoot]) over the [global] fallback by the one precedence rule in
 * [LayeredConfig]'s table. Every cross-trait merge goes through [TraitResolver], in its one trait order
 * (item traits, then the schema's `default_traits`).
 *
 * A null `rootId`, or no [perRoot] source wired, skips the per-root layer entirely: zero per-root
 * reads. Otherwise every public method reads the per-root layer independently (one read per call)
 * at the point it needs it; a method whose trait list is empty returns before any per-root read
 * where documented.
 *
 * ## Pins (plan section 3.8)
 *
 * The item-facet methods (schema, review phase, resources, dispatch by role and by seat) resolve an item through
 * [forItem]: a pinned item against the config VERSIONS and base schema it is pinned to, an unpinned item at the heads.
 * The root-wide methods (labels, note limits, independence, registry, type and trait lists, every `*ForType`) always
 * read the heads. Pins need a [ConfigVersionSource]: a resolver without one ([pinsEnabled] false) resolves every item
 * at the heads and its callers write no pins.
 *
 * `PerRootConfigUnavailableException` from [perRoot] (and from a pinned version read) propagates unchanged from every
 * method: a config read failure is never treated as "no per-root config, use the global layer".
 * Callers at an operation boundary catch it and report the transient `config_unavailable` outcome.
 */
class EffectiveConfigResolver private constructor(
    private val server: ServerConfigSource,
    private val perRoot: PerRootConfigSource?,
    private val fixedGlobal: GlobalConfigLookup?,
    private val versions: ConfigVersionSource?,
) {
    /**
     * The production form: the server layer is the DB head, read per call (memoised per [ConfigSession]). [versions]
     * defaults to whichever source also serves stored versions by id (production: the one `PerRootConfigService`).
     */
    constructor(
        server: ServerConfigSource,
        perRoot: PerRootConfigSource?,
        versions: ConfigVersionSource? = (server as? ConfigVersionSource) ?: (perRoot as? ConfigVersionSource),
    ) : this(server, perRoot, null, versions)

    /**
     * A fixed server lookup (tests, and any caller that holds one document): never reads a server source. Pins are
     * off unless [versions] is passed (a fixed server document has no versions; the server side of a pinned item is
     * then always this lookup).
     */
    constructor(
        global: GlobalConfigLookup,
        perRoot: PerRootConfigSource?,
        versions: ConfigVersionSource? = null,
    ) : this({ null }, perRoot, global, versions)

    /** Session-memo key of pinned version reads (distinct from the per-root and server sources). */
    private val versionMemoKey = Any()

    /**
     * True when this resolver can read stored versions by id, so items can be pinned and resolved against their pins.
     * False: every item resolves at the heads and callers write no pins.
     */
    val pinsEnabled: Boolean get() = versions != null

    /**
     * The fixed server lookup of a resolver built from a [GlobalConfigLookup]. A resolver built from a
     * [ServerConfigSource] has no fixed lookup (its layer is read per call): use [layered] and
     * [LayeredConfig.global] instead.
     */
    val global: GlobalConfigLookup
        get() =
            fixedGlobal
                ?: throw IllegalStateException("This resolver reads the server layer from the database; use layered(rootId).global")

    /**
     * Fetches [rootId]'s per-root layer (see class kdoc for the null/no-source cases) and pairs it with the server
     * layer (read here: the DB head, or the fixed lookup).
     */
    suspend fun layered(rootId: UUID?): LayeredConfig = LayeredConfig(rootId, layerFor(rootId), serverLookup())

    /**
     * The config [item] resolves against. Unpinned (or pins off): [layered] at the heads of its root, exactly as before
     * pins existed. Pinned: the per-root layer is the pinned root version (none when the pin names none), the server
     * layer the pinned server version (empty when the pin names none; a fixed server lookup always), and the base
     * schema is forced to the pinned key and layer ([LayeredConfig.pinnedBase]). Pinned versions are memoised per
     * [ConfigSession] by version id; a read fault propagates (fail closed).
     */
    suspend fun forItem(item: WorkItem): LayeredConfig {
        val pin = item.pin
        val source = versions
        if (pin == null || source == null) return layered(item.rootId)
        val perRootLayer = pin.rootVersionId?.let { pinnedLayer(source, it) }
        val global = fixedGlobal ?: LayerBackedGlobalLookup(pin.serverVersionId?.let { pinnedLayer(source, it) })
        return LayeredConfig(item.rootId, perRootLayer, global, PinnedBase(pin.schemaKey, pin.schemaLayer))
    }

    /** One read of a stored version by id, memoised per ambient [ConfigSession]; fresh with no session. */
    private suspend fun pinnedLayer(
        source: ConfigVersionSource,
        versionId: UUID
    ): ConfigLayer? {
        val session = coroutineContext[ConfigSession.Key]
        return if (session != null) {
            session.memoized(versionMemoKey, versionId) { source.layerAt(versionId) }
        } else {
            source.layerAt(versionId)
        }
    }

    /**
     * The stored version [versionId] as a layer (see [ConfigVersionSource.layerAt]), or null when pins are off or no
     * such version exists.
     */
    suspend fun versionLayer(versionId: UUID): ConfigLayer? = versions?.let { pinnedLayer(it, versionId) }

    /** The config the item facets of [item] resolve against (see [forItem]). */
    private suspend fun itemConfig(item: WorkItem): LayeredConfig = forItem(item)

    /** The server side of a resolution: the fixed lookup, else a lookup over the freshly (or session-memoised) read layer. */
    private suspend fun serverLookup(): GlobalConfigLookup = fixedGlobal ?: LayerBackedGlobalLookup(serverLayer())

    /**
     * One read of the server layer. Memoised per ambient [ConfigSession] like the per-root read (keyed on the server
     * source and the nil UUID); fresh with no session.
     */
    private suspend fun serverLayer(): ConfigLayer? {
        val session = coroutineContext[ConfigSession.Key]
        return if (session != null) {
            session.memoized(server, SERVER_KEY) { server.layer() }
        } else {
            server.layer()
        }
    }

    /** Effective (trait-merged) schema for [item], or null when no schema matches (schema-free). */
    suspend fun resolveSchema(item: WorkItem): WorkItemSchema? = mergedSchema(item, itemConfig(item))

    /**
     * The effective schema [item] would resolve to at the config HEADS, ignoring its pin (the Q9 rule over the current
     * root and server documents, trait-merged). Used to detect a note key the head requires but the pin lacks.
     */
    suspend fun resolveSchemaAtHead(item: WorkItem): WorkItemSchema? = mergedSchema(item, layered(item.rootId))

    private fun mergedSchema(
        item: WorkItem,
        layered: LayeredConfig
    ): WorkItemSchema? {
        val baseSchema = layered.baseSchema(item.type)?.first ?: return null
        return TraitResolver(layered).mergeSchema(baseSchema, itemTraits(item), item.id)
    }

    /**
     * Same as [resolveSchema], plus which layer supplied the BASE schema and that layer's
     * fingerprint (fetched after trait merging, and from the global side only when the base came
     * from the global layer). For a pinned item the fingerprint is the pinned version's.
     */
    suspend fun resolveSchemaWithSource(item: WorkItem): SchemaMatch? {
        val layered = itemConfig(item)
        val (baseSchema, source) = layered.baseSchema(item.type) ?: return null
        val merged = TraitResolver(layered).mergeSchema(baseSchema, itemTraits(item), item.id)
        return SchemaMatch(merged, source, layered.fingerprintFor(source))
    }

    /** Type-only schema lookup (no trait merging), `"default"` steps included; null when no layer matches. */
    suspend fun resolveTypeSchema(
        type: String,
        rootId: UUID?
    ): SchemaMatch? = layered(rootId).resolveTypeSchema(type)

    /**
     * The types an item under [rootId] may be created or retyped with: the union of both layers' declared schema
     * keys, sorted (see [LayeredConfig.typeKeys]). Empty when no schema is configured.
     */
    suspend fun availableTypes(rootId: UUID?): List<String> = layered(rootId).typeKeys()

    /** True when [item]'s resolved schema has a REVIEW phase; false when no schema matches. */
    suspend fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false

    /**
     * Resource requirements for [item]'s traits in [TraitResolver.order] (item traits, then the base
     * schema's defaultTraits); a null base schema is tolerated (item traits are still honored).
     */
    suspend fun resolveResourceRequirements(item: WorkItem): List<ResourceRequirement> {
        val layered = itemConfig(item)
        val defaultTraits = layered.baseSchema(item.type)?.first?.defaultTraits ?: emptyList()
        return TraitResolver(layered).resources(TraitResolver.order(itemTraits(item), defaultTraits))
    }

    /** Resource requirements for a type's [defaultTraits] alone (no item). */
    suspend fun resolveResourceRequirementsForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): List<ResourceRequirement> = TraitResolver(layered(rootId)).resources(defaultTraits.distinct())

    /**
     * Dispatch profile for [item] at [role], using [resolvedSchema]'s defaultTraits. Trait order is
     * [TraitResolver.order] (item traits, then defaultTraits); the first trait with a profile for
     * [role] wins outright.
     */
    suspend fun resolveDispatchProfile(
        item: WorkItem,
        role: Role,
        resolvedSchema: WorkItemSchema?
    ): DispatchProfile? = resolveDispatchProfiles(item, resolvedSchema)[role]

    /** Per-phase counterpart to [resolveDispatchProfile]: every role [item]'s traits declare. */
    suspend fun resolveDispatchProfiles(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): Map<Role, DispatchProfile> {
        val traits = dispatchTraitsFor(item, resolvedSchema)
        // Returns BEFORE any config read when the trait list is empty (the common, trait-less case).
        if (traits.isEmpty()) return emptyMap()
        return TraitResolver(itemConfig(item)).dispatch(traits)
    }

    /** Type-only counterpart to [resolveDispatchProfiles]: [defaultTraits] is the complete trait set. */
    suspend fun resolveDispatchProfilesForType(
        defaultTraits: List<String>,
        rootId: UUID?
    ): Map<Role, DispatchProfile> {
        val traits = defaultTraits.distinct()
        if (traits.isEmpty()) return emptyMap()
        return TraitResolver(layered(rootId)).dispatch(traits)
    }

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
        return TraitResolver(itemConfig(item)).dispatchBySeat(traits, seats)
    }

    /** Type-only counterpart to [resolveDispatchBySeat]: [defaultTraits] is the complete trait set. */
    suspend fun resolveDispatchBySeatForType(
        defaultTraits: List<String>,
        seats: List<SeatDefinition>,
        rootId: UUID?
    ): Map<Role, Map<String, DispatchProfile>> {
        val traits = defaultTraits.distinct()
        if (traits.isEmpty() || seats.isEmpty()) return emptyMap()
        return TraitResolver(layered(rootId)).dispatchBySeat(traits, seats)
    }

    /** Resource registry visible to [rootId]: the server registry only (see [LayeredConfig.resourceRegistry]). */
    suspend fun resolveResourceRegistry(rootId: UUID?): Map<String, ResourceDefinition> = layered(rootId).resourceRegistry()

    /** `note_limits.mode` for [rootId]: per-root explicit value, else global. */
    suspend fun resolveNoteLimitsMode(rootId: UUID?): String = layered(rootId).noteLimitsMode()

    /** `independence:` policy (A2) for [rootId]: per-root block wins WHOLESALE, else global. */
    suspend fun resolveIndependencePolicy(rootId: UUID?): IndependencePolicy = layered(rootId).independencePolicy()

    /** `runs:` settings for [rootId], resolved per field (see [LayeredConfig.runsConfig]). */
    suspend fun resolveRunsConfig(rootId: UUID?): RunsConfig = layered(rootId).runsConfig()

    /** The declared `decision_classes:` entries for [rootId], merged per key (see [LayeredConfig.decisionClasses]). */
    suspend fun resolveDecisionClasses(rootId: UUID?): Map<String, DecisionClassConfig> = layered(rootId).decisionClasses()

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
     * [statusLabelKey]: `"cascade"` for the terminal cascade, `"start"`/`"reopen"` for the start and reopen
     * cascades, `"complete"` for a `start` into TERMINAL, else the trigger's wire name. One per-root read per call.
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
        return (perRootTraits + serverLookup().traitNames()).distinct()
    }

    /** Trait list for dispatch resolution: [TraitResolver.order] of [item]'s traits and [resolvedSchema]'s defaultTraits. */
    private fun dispatchTraitsFor(
        item: WorkItem,
        resolvedSchema: WorkItemSchema?
    ): List<String> = TraitResolver.order(itemTraits(item), resolvedSchema?.defaultTraits ?: emptyList())

    /** [item]'s own typed traits. */
    private fun itemTraits(item: WorkItem): List<String> = item.traits

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

/** Session-memo key of the server layer (no real root has the nil UUID). */
private val SERVER_KEY = UUID(0L, 0L)

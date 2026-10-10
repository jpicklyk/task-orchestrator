package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.util.UUID

/**
 * Both config layers visible to one root, and the per-layer LOOKUPS every facet reads. Pure and
 * synchronous: the per-root layer was already fetched (by [EffectiveConfigResolver.layered]) before
 * this object exists, and the server side is a [GlobalConfigLookup] consulted lazily, only at the
 * point a facet needs it. Cross-trait merging is not done here: it belongs to [TraitResolver].
 *
 * [perRoot] is null when [rootId] is null, no per-root source is wired, or the root has no usable
 * per-root config (no row, or an unparseable row).
 *
 * ## ONE PRECEDENCE RULE
 *
 * A specific declaration beats a generic one, and per-root beats the server at the same level:
 *
 * | Facet | Rule |
 * |---|---|
 * | Schema for a type | per-root exact type -> server exact type -> per-root `"default"` -> server `"default"` -> none. A null type uses only the two `"default"` steps. Tags are never consulted. |
 * | Trait (every dimension) | when the per-root `traits:` map contains the name, notes, seats, resources and dispatch (by role and by seat) ALL come from the per-root entry, and a dimension it does not declare is empty; otherwise all come from the server entry |
 * | Resource registry | server only (a per-root `resources:` section is ignored) |
 * | note_limits.mode | per-root explicit value, else server |
 * | independence (A2) | per-root block wins WHOLESALE (both `mode` and `require_verified` together), else server |
 * | Status labels | per-root map wins per trigger by key presence (an explicit null included), else server |
 * | Trait names | per-root keys first, then server, distinct |
 * | Type keys | union of both layers' schema keys, sorted |
 */
class LayeredConfig(
    val rootId: UUID?,
    val perRoot: ConfigLayer?,
    val global: GlobalConfigLookup,
) {
    private val perRootDocument: ConfigDocument? get() = perRoot?.document

    /**
     * The base schema (no trait merging) for an item of [type], with the supplying layer and that
     * layer's fingerprint; see the precedence table. Null when no layer has a match.
     */
    fun resolveBaseSchema(type: String?): SchemaMatch? {
        val (schema, source) = baseSchema(type) ?: return null
        return SchemaMatch(schema, source, fingerprintFor(source))
    }

    /** [resolveBaseSchema] for a known type name. */
    fun resolveTypeSchema(type: String): SchemaMatch? = resolveBaseSchema(type)

    /**
     * Every schema key an item of this root may be typed with: the per-root document's `work_item_schemas` keys plus
     * the server keys, sorted and distinct. `"default"` is a key like any other. Empty when nothing is declared.
     */
    fun typeKeys(): List<String> {
        val perRootKeys = perRootDocument?.workItemSchemas?.keys.orEmpty()
        return (perRootKeys + global.schemaKeys()).distinct().sorted()
    }

    /** True when the per-root document declares trait [name] at all: its `traits:` map contains the name. */
    fun perRootDeclaresTrait(name: String): Boolean = perRootDocument?.traits?.containsKey(name) == true

    /** Trait notes for [name] from the declaring layer (see the table); null when the trait is unknown to both. */
    fun traitNotes(name: String): List<NoteSchemaEntry>? =
        if (perRootDeclaresTrait(name)) perRootDocument?.traits?.get(name) else global.traitNotes(name)

    /** Trait resource requirements for [name] from the declaring layer; empty when that layer declares none. */
    fun traitResources(name: String): List<ResourceRequirement> =
        if (perRootDeclaresTrait(name)) perRootDocument?.traitResources?.get(name).orEmpty() else global.traitResources(name)

    /** Trait dispatch role map for [name] from the declaring layer; empty when that layer declares none. */
    fun traitDispatch(name: String): Map<Role, DispatchProfile> =
        if (perRootDeclaresTrait(name)) perRootDocument?.traitDispatch?.get(name).orEmpty() else global.traitDispatch(name)

    /** Trait by-seat dispatch overrides for [name] from the declaring layer; empty when that layer declares none. */
    fun traitDispatchBySeat(name: String): Map<Role, Map<String, SeatDispatchOverride>> =
        if (perRootDeclaresTrait(name)) {
            perRootDocument?.traitDispatchBySeat?.get(name).orEmpty()
        } else {
            global.traitDispatchBySeat(name)
        }

    /** Trait seats for [name] from the declaring layer; empty when that layer declares none. */
    fun traitSeats(name: String): List<SeatDefinition> =
        if (perRootDeclaresTrait(name)) perRootDocument?.traitSeats?.get(name).orEmpty() else global.traitSeats(name)

    /**
     * The resource registry visible to [rootId]: the server's `resources:` registry only. A resource key is a
     * server-global lock/lease namespace, so a per-root `resources:` section is never honored.
     */
    fun resourceRegistry(): Map<String, ResourceDefinition> = global.resourceRegistry()

    /** `note_limits.mode`: the per-root explicit value, else the server mode. */
    fun noteLimitsMode(): String = perRootDocument?.noteLimitsMode ?: global.noteLimitsMode()

    /**
     * The effective `independence:` policy (A2): the per-root block wins WHOLESALE (both `mode`
     * and `require_verified` together), else the server policy.
     */
    fun independencePolicy(): IndependencePolicy = perRootDocument?.independence ?: global.independencePolicy()

    /**
     * Status label for [trigger]: the per-root `status_labels` map wins only when it contains
     * [trigger] as a key (its value may be an explicit null); otherwise the server label.
     */
    fun statusLabel(trigger: String): String? {
        val perRootLabels = perRootDocument?.statusLabels
        return if (perRootLabels != null && perRootLabels.containsKey(trigger)) {
            perRootLabels[trigger]
        } else {
            global.statusLabel(trigger)
        }
    }

    /** Trait names visible to [rootId]: per-root keys first, then the server names, distinct. */
    fun traitNames(): List<String> {
        val perRootTraits = perRootDocument?.traits?.keys.orEmpty()
        return (perRootTraits + global.traitNames()).distinct()
    }

    /**
     * Base schema and supplying layer, without fetching any fingerprint (the server fingerprint is
     * fetched lazily, only when a caller needs provenance).
     */
    internal fun baseSchema(type: String?): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument
        if (type != null) {
            snapshot?.workItemSchemas?.get(type)?.let { return it to ConfigSource.PER_ROOT }
            global.exactSchema(type)?.let { return it to ConfigSource.GLOBAL }
        }
        snapshot?.workItemSchemas?.get(DEFAULT_TYPE)?.let { return it to ConfigSource.PER_ROOT }
        return global.exactSchema(DEFAULT_TYPE)?.let { it to ConfigSource.GLOBAL }
    }

    /** Fingerprint of the layer that supplied a base schema; the server one is fetched only for GLOBAL. */
    internal fun fingerprintFor(source: ConfigSource): String? =
        when (source) {
            ConfigSource.PER_ROOT -> perRoot?.fingerprint
            ConfigSource.GLOBAL -> global.fingerprint()
        }

    private companion object {
        /** The `"default"` schema key, the generic last resort in each layer. */
        const val DEFAULT_TYPE = "default"
    }
}

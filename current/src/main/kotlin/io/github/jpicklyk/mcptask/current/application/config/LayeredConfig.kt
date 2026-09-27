package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Both config layers visible to one root, plus every facet's LEGACY merge rule, declared once.
 * Pure and synchronous: the per-root layer was already fetched (by [EffectiveConfigResolver.layered])
 * before this object exists, and the global side is a [GlobalConfigLookup] consulted lazily, only at
 * the point a facet needs it.
 *
 * [perRoot] is null when [rootId] is null, no per-root source is wired, or the root has no usable
 * per-root config (no row, or an unparseable row).
 *
 * ## FACET TABLE
 *
 * Every facet below except type/tag schema lookup is identical across all three
 * [SchemaResolutionMode]s ([effectiveMode]). Type/tag lookup depends on the mode:
 *
 * | Mode | Type lookup | Tag lookup |
 * |---|---|---|
 * | LEGACY (default when [effectiveMode] is absent everywhere) | per-root exact type -> per-root `"default"` -> global exact type -> global `"default"` (the last step is the global lookup's own fold) | (only when a per-root layer exists) per-root first-matching-tag -> per-root `"default"`; then global: notes from the first tag with a global EXACT match (D2), else `"default"`'s notes |
 * | LAYERED | per-root exact type -> global exact type -> per-root `"default"` -> global `"default"` | per-root first-EXACT-matching-tag -> global first-EXACT-matching-tag -> per-root `"default"` -> global `"default"` |
 * | ISOLATED | per-root exact type -> per-root `"default"` (global NEVER consulted) | per-root first-EXACT-matching-tag -> per-root `"default"` (global NEVER consulted) |
 *
 * | Facet | Rule |
 * |---|---|
 * | Trait notes | per-root trait entry wins wholesale per trait name (an empty list shadows) |
 * | Trait resources | per-root trait entry wins wholesale per trait name |
 * | Trait dispatch | per-root trait role map wins wholesale per trait name (see [traitDispatchEntry]) |
 * | Resource registry | start from per-root, then GLOBAL overwrites each colliding key (WARN on collision) |
 * | note_limits.mode | per-root explicit value, else global |
 * | Status labels | per-root map wins per trigger by key presence (an explicit null included), else global |
 * | Trait names | per-root keys first, then global, distinct |
 *
 * Once a root has pushed its own config, a per-root `"default"` schema wins over a global EXACT
 * type match: that config is the root's complete self-description for gate purposes, not a patch
 * over the global floor. This shadowing is LEGACY-only — LAYERED and ISOLATED always try a global
 * (LAYERED) or per-root (ISOLATED) EXACT type match before ever consulting either layer's
 * `"default"`.
 */
class LayeredConfig(
    val rootId: UUID?,
    val perRoot: ConfigLayer?,
    val global: GlobalConfigLookup,
) {
    private val perRootDocument: ConfigDocument? get() = perRoot?.document

    /**
     * The schema-resolution mode in effect for [rootId]: the per-root document's own
     * `schema_resolution` wins when set; otherwise the global file's key (an `ISOLATED` global
     * value has no per-root layer above it to isolate from, so [GlobalConfigLookup.schemaResolution]
     * treats it as [SchemaResolutionMode.LAYERED] already — see `GlobalConfigFile`); absent
     * everywhere is [SchemaResolutionMode.LEGACY]. Computed from data already in hand — no I/O, no
     * extra per-root read.
     */
    val effectiveMode: SchemaResolutionMode =
        perRootDocument?.schemaResolution
            ?: global.schemaResolution()?.let { if (it == SchemaResolutionMode.ISOLATED) SchemaResolutionMode.LAYERED else it }
            ?: SchemaResolutionMode.LEGACY

    /**
     * Resolves the base schema (no trait merging) for an item of [type] carrying [tags], with the
     * supplying layer and that layer's fingerprint. Type-first lookup with tag fallback; see the
     * facet table.
     */
    fun resolveBaseSchema(
        type: String?,
        tags: List<String>
    ): SchemaMatch? {
        val (schema, source) = baseSchema(type, tags) ?: return null
        return SchemaMatch(schema, source, fingerprintFor(source))
    }

    /**
     * Type-only lookup (no tag step), per [effectiveMode]: LEGACY is per-root exact type -> per-root
     * `"default"` -> global type lookup (unchanged); LAYERED is per-root exact -> global exact ->
     * per-root `"default"` -> global `"default"`; ISOLATED is per-root exact -> per-root `"default"`
     * only (global never consulted). Returns null when no layer defines [type] under that mode.
     */
    fun resolveTypeSchema(type: String): SchemaMatch? {
        val (schema, source) =
            when (effectiveMode) {
                SchemaResolutionMode.LEGACY -> resolveTypeAgainstLayers(type)
                SchemaResolutionMode.LAYERED -> layeredResolveType(type)
                SchemaResolutionMode.ISOLATED -> isolatedResolveType(type)
            } ?: return null
        return SchemaMatch(schema, source, fingerprintFor(source))
    }

    /** Trait notes for [name]: the per-root entry wins wholesale, else the global one; null when unknown to both. */
    fun traitNotes(name: String): List<NoteSchemaEntry>? {
        val perRootNotes = perRootDocument?.traits?.get(name)
        return perRootNotes ?: global.traitNotes(name)
    }

    /** Trait resource requirements for [name]: the per-root entry wins wholesale, else the global one. */
    fun traitResources(name: String): List<ResourceRequirement> {
        val perRootRequirements = perRootDocument?.traitResources?.get(name)
        return perRootRequirements ?: global.traitResources(name)
    }

    /** Trait dispatch role map for [name]: the per-root map wins wholesale, else the global one. */
    fun traitDispatch(name: String): Map<Role, DispatchProfile> = traitDispatchEntry(name)

    /**
     * The resource registry visible to [rootId]. GLOBAL WINS on collision (the inverse of trait
     * layering): a resource key is a server-global lock/lease namespace, so a per-root redefinition
     * of a globally-known key is logged and the global definition is used.
     */
    fun resourceRegistry(): Map<String, ResourceDefinition> {
        val perRootRegistry = perRootDocument?.resourceRegistry ?: emptyMap()
        val globalRegistry = global.resourceRegistry()

        val merged = LinkedHashMap<String, ResourceDefinition>(perRootRegistry)
        for ((key, definition) in globalRegistry) {
            if (merged.containsKey(key)) {
                logger.warn(
                    "Resource registry key '{}' is defined in both per-root and global config for root '{}'; " +
                        "global definition wins",
                    key,
                    rootId
                )
            }
            merged[key] = definition
        }
        return merged
    }

    /** `note_limits.mode`: the per-root explicit value, else the global mode. */
    fun noteLimitsMode(): String {
        val perRootMode = perRootDocument?.noteLimitsMode
        return perRootMode ?: global.noteLimitsMode()
    }

    /**
     * Status label for [trigger]: the per-root `status_labels` map wins only when it contains
     * [trigger] as a key (its value may be an explicit null); otherwise the global label.
     */
    fun statusLabel(trigger: String): String? {
        val perRootLabels = perRootDocument?.statusLabels
        return if (perRootLabels != null && perRootLabels.containsKey(trigger)) {
            perRootLabels[trigger]
        } else {
            global.statusLabel(trigger)
        }
    }

    /** Trait names visible to [rootId]: per-root keys first, then the global names, distinct. */
    fun traitNames(): List<String> {
        val perRootTraits = perRootDocument?.traits?.keys.orEmpty()
        return (perRootTraits + global.traitNames()).distinct()
    }

    // ---------------------------------------------------------------------------------------------
    // Internal steps used by EffectiveConfigResolver (kept separate so the global fingerprint is
    // fetched lazily, only when a caller needs provenance, and after trait merging as before).
    // ---------------------------------------------------------------------------------------------

    /** Base schema and supplying layer, without fetching any fingerprint. */
    internal fun baseSchema(
        type: String?,
        tags: List<String>
    ): Pair<WorkItemSchema, ConfigSource>? =
        when (effectiveMode) {
            SchemaResolutionMode.LEGACY -> legacyBaseSchema(type, tags)
            SchemaResolutionMode.LAYERED -> layeredBaseSchema(type, tags)
            SchemaResolutionMode.ISOLATED -> isolatedBaseSchema(type, tags)
        }

    private fun legacyBaseSchema(
        type: String?,
        tags: List<String>
    ): Pair<WorkItemSchema, ConfigSource>? {
        // Type-first lookup: whole-algorithm-first per layer. Run the ENTIRE per-root layer
        // (exact type match, then per-root "default") before ever consulting the global layer.
        type?.let { t ->
            resolveTypeAgainstLayers(t)?.let { return it }
        }

        // Tag fallback: run the per-root schema map through the SAME first-tag-match/"default"
        // algorithm first; only fall through to the global tag algorithm when the per-root layer
        // has no config row for this root, or no tag (nor "default") matches within it.
        val snapshot = perRootDocument
        if (snapshot != null) {
            resolvePerRootTagMatch(tags, snapshot)?.let { return it to ConfigSource.PER_ROOT }
        }

        // Tag fallback: find the matched tag, then look up the full WorkItemSchema
        // to preserve lifecycleMode and defaultTraits from config
        val tagNotes = global.notesForTags(tags) ?: return null
        val matchedType =
            if (tags.isEmpty()) {
                "default"
            } else {
                tags.firstOrNull { tag -> global.hasExactTagSchema(tag) } ?: "default"
            }
        // Retrieve the full WorkItemSchema (with lifecycle/defaultTraits) if available.
        // Re-use tagNotes from above to avoid a redundant notesForTags call in the fallback.
        val resolved = global.schemaForType(matchedType) ?: WorkItemSchema(type = matchedType, notes = tagNotes)
        return resolved to ConfigSource.GLOBAL
    }

    /**
     * LAYERED: EXACT-only at every step, per-root layer entirely before global, type before tags,
     * `"default"` (per-root then global) only as the very last resort.
     */
    private fun layeredBaseSchema(
        type: String?,
        tags: List<String>
    ): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument

        type?.let { t ->
            snapshot?.workItemSchemas?.get(t)?.let { return it to ConfigSource.PER_ROOT }
            global.exactSchema(t)?.let { return it to ConfigSource.GLOBAL }
        }

        if (snapshot != null) {
            for (tag in tags) {
                snapshot.workItemSchemas[tag]?.let { return it to ConfigSource.PER_ROOT }
            }
        }
        for (tag in tags) {
            global.exactSchema(tag)?.let { return it to ConfigSource.GLOBAL }
        }

        snapshot?.workItemSchemas?.get(DEFAULT_TYPE)?.let { return it to ConfigSource.PER_ROOT }
        global.exactSchema(DEFAULT_TYPE)?.let { return it to ConfigSource.GLOBAL }
        return null
    }

    /** ISOLATED: per-root layer only, EXACT-only; the global layer is never consulted. */
    private fun isolatedBaseSchema(
        type: String?,
        tags: List<String>
    ): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument ?: return null

        type?.let { t ->
            snapshot.workItemSchemas[t]?.let { return it to ConfigSource.PER_ROOT }
        }
        for (tag in tags) {
            snapshot.workItemSchemas[tag]?.let { return it to ConfigSource.PER_ROOT }
        }
        snapshot.workItemSchemas[DEFAULT_TYPE]?.let { return it to ConfigSource.PER_ROOT }
        return null
    }

    /** Fingerprint of the layer that supplied a base schema; the global one is fetched only for GLOBAL. */
    internal fun fingerprintFor(source: ConfigSource): String? =
        when (source) {
            ConfigSource.PER_ROOT -> perRoot?.fingerprint
            ConfigSource.GLOBAL -> global.fingerprint()
        }

    /**
     * Merges trait notes into [baseSchema]: default traits from the schema + per-item traits from
     * [item]'s properties, distinct; each trait's notes via [traitNotes] (unknown traits WARN and are
     * skipped); base note keys win, and the first trait in order wins for duplicate trait keys.
     */
    internal fun mergeTraits(
        item: WorkItem,
        baseSchema: WorkItemSchema
    ): WorkItemSchema {
        val defaultTraits = baseSchema.defaultTraits
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val allTraits = (defaultTraits + itemTraits).distinct()

        if (allTraits.isEmpty()) return baseSchema

        val traitNotes = mutableListOf<NoteSchemaEntry>()
        for (traitName in allTraits) {
            val notes = traitNotes(traitName)
            if (notes == null) {
                logger.warn("Unknown trait '{}' on item '{}'; skipping", traitName, item.id)
                continue
            }
            traitNotes.addAll(notes)
        }

        if (traitNotes.isEmpty()) return baseSchema

        // Base note keys win; first-trait-in-order wins for duplicate trait keys
        val existingKeys = baseSchema.notes.map { it.key }.toMutableSet()
        val mergedNotes = baseSchema.notes.toMutableList()
        for (note in traitNotes) {
            if (note.key !in existingKeys) {
                mergedNotes.add(note)
                existingKeys.add(note.key)
            }
        }

        return baseSchema.copy(notes = mergedNotes)
    }

    /**
     * Cross-trait resource-requirement merge over [traits] (in order): a UNION of keys; on a
     * duplicate key EXCLUSIVE wins over ADVISORY regardless of declaring trait, and `ttlSeconds`
     * keeps the FIRST-seen value.
     */
    internal fun mergeResourceRequirements(traits: List<String>): List<ResourceRequirement> {
        if (traits.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, ResourceRequirement>()
        for (traitName in traits) {
            val requirements = traitResources(traitName)
            for (requirement in requirements) {
                val existing = merged[requirement.key]
                if (existing == null) {
                    merged[requirement.key] = requirement
                } else if (existing.mode != ResourceMode.EXCLUSIVE && requirement.mode == ResourceMode.EXCLUSIVE) {
                    merged[requirement.key] = existing.copy(mode = ResourceMode.EXCLUSIVE)
                }
                // else: keep the first-seen entry as-is: first-seen wins for ttlSeconds, and the
                // mode is already EXCLUSIVE (nothing beats it) or unchanged ADVISORY-vs-ADVISORY.
            }
        }
        return merged.values.toList()
    }

    /**
     * Per-trait dispatch over [traits] (in order): for each trait, [traitDispatchEntry]; the first
     * trait to define a profile for a given [Role] claims it, later traits cannot override.
     */
    internal fun mergeDispatch(traits: List<String>): Map<Role, DispatchProfile> {
        val result = LinkedHashMap<Role, DispatchProfile>()
        for (traitName in traits) {
            val dispatch = traitDispatchEntry(traitName)
            for ((role, profile) in dispatch) {
                if (role !in result) {
                    result[role] = profile
                }
            }
        }
        return result
    }

    /**
     * The ONE place every dispatch read goes through (A1 seat seam): the per-root trait's role map
     * wins wholesale over the global one. A per-root entry with no profile for a role does NOT fall
     * through to the global entry for that role.
     */
    private fun traitDispatchEntry(name: String): Map<Role, DispatchProfile> {
        val perRootDispatch = perRootDocument?.traitDispatch?.get(name)
        return perRootDispatch ?: global.traitDispatch(name)
    }

    private fun resolveTypeAgainstLayers(type: String): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument
        if (snapshot != null) {
            val perRootMatch = snapshot.workItemSchemas[type] ?: snapshot.workItemSchemas[DEFAULT_TYPE]
            if (perRootMatch != null) return perRootMatch to ConfigSource.PER_ROOT
        }
        return global.schemaForType(type)?.let { it to ConfigSource.GLOBAL }
    }

    private fun layeredResolveType(type: String): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument
        snapshot?.workItemSchemas?.get(type)?.let { return it to ConfigSource.PER_ROOT }
        global.exactSchema(type)?.let { return it to ConfigSource.GLOBAL }
        snapshot?.workItemSchemas?.get(DEFAULT_TYPE)?.let { return it to ConfigSource.PER_ROOT }
        return global.exactSchema(DEFAULT_TYPE)?.let { it to ConfigSource.GLOBAL }
    }

    private fun isolatedResolveType(type: String): Pair<WorkItemSchema, ConfigSource>? {
        val snapshot = perRootDocument ?: return null
        snapshot.workItemSchemas[type]?.let { return it to ConfigSource.PER_ROOT }
        return snapshot.workItemSchemas[DEFAULT_TYPE]?.let { it to ConfigSource.PER_ROOT }
    }

    private fun resolvePerRootTagMatch(
        tags: List<String>,
        snapshot: ConfigDocument
    ): WorkItemSchema? {
        for (tag in tags) {
            snapshot.workItemSchemas[tag]?.let { return it }
        }
        return snapshot.workItemSchemas["default"]
    }

    private companion object {
        /**
         * Logger category kept as `ToolExecutionContext` (where this logic lived before extraction)
         * so existing log filters and WARN consumers see byte-identical output.
         */
        val logger: Logger =
            LoggerFactory.getLogger("io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext")

        /** The `"default"` schema key, tried last in every mode's type/tag lookup. */
        const val DEFAULT_TYPE = "default"
    }
}

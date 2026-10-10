package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigLookup
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * A server config a test fills in AFTER building its context (e.g. a context built once in `@BeforeEach`, with each
 * test declaring the schemas it needs). Every lookup reads the CURRENT [document] through a fresh
 * [LayerBackedGlobalLookup], so resolution follows the production rules over a real [ConfigDocument].
 */
class MutableServerConfig {
    val schemas: MutableMap<String, WorkItemSchema> = linkedMapOf()
    val traits: MutableMap<String, List<NoteSchemaEntry>> = linkedMapOf()
    val traitResources: MutableMap<String, List<ResourceRequirement>> = linkedMapOf()
    val traitDispatch: MutableMap<String, Map<Role, DispatchProfile>> = linkedMapOf()
    val resourceRegistry: MutableMap<String, ResourceDefinition> = linkedMapOf()
    var fingerprint: String? = null
    var noteLimitsMode: String? = null
    var statusLabels: Map<String, String?>? = null

    /** Declares [type] with [notes] (and optional [defaultTraits]). */
    fun schema(
        type: String,
        notes: List<NoteSchemaEntry>,
        defaultTraits: List<String> = emptyList(),
    ) {
        schemas[type] = WorkItemSchema(type = type, notes = notes, defaultTraits = defaultTraits)
    }

    /** The current server document. */
    fun document(): ConfigDocument =
        ConfigDocument(
            workItemSchemas = LinkedHashMap(schemas),
            traits = LinkedHashMap(traits),
            traitResources = LinkedHashMap(traitResources),
            traitDispatch = LinkedHashMap(traitDispatch),
            resourceRegistry = LinkedHashMap(resourceRegistry),
            noteLimitsMode = noteLimitsMode,
            statusLabels = statusLabels,
        )

    /** A [GlobalConfigLookup] that reads the current [document] on every call. */
    val lookup: GlobalConfigLookup =
        object : GlobalConfigLookup {
            private fun now() = LayerBackedGlobalLookup(TestConfigResolver.layer(document(), fingerprint))

            override fun exactSchema(key: String): WorkItemSchema? = now().exactSchema(key)

            override fun schemaKeys(): Set<String> = now().schemaKeys()

            override fun traitNotes(name: String): List<NoteSchemaEntry>? = now().traitNotes(name)

            override fun traitResources(name: String): List<ResourceRequirement> = now().traitResources(name)

            override fun traitDispatch(name: String): Map<Role, DispatchProfile> = now().traitDispatch(name)

            override fun traitSeats(name: String): List<SeatDefinition> = now().traitSeats(name)

            override fun traitDispatchBySeat(name: String): Map<Role, Map<String, SeatDispatchOverride>> = now().traitDispatchBySeat(name)

            override fun resourceRegistry(): Map<String, ResourceDefinition> = now().resourceRegistry()

            override fun noteLimitsMode(): String = now().noteLimitsMode()

            override fun independencePolicy(): IndependencePolicy = now().independencePolicy()

            override fun fingerprint(): String? = now().fingerprint()

            override fun traitNames(): List<String> = now().traitNames()

            override fun statusLabel(trigger: String): String? = now().statusLabel(trigger)
        }

    /** A resolver over this server config and [perRoot]. */
    fun resolver(perRoot: PerRootConfigSource? = null): EffectiveConfigResolver = EffectiveConfigResolver(lookup, perRoot)
}

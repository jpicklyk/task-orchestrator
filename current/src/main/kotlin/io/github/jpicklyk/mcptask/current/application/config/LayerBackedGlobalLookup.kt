package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * [GlobalConfigLookup] answered from one already-parsed global [ConfigLayer], with the same
 * semantics as the YAML-backed global services but derived from the document instead of separate
 * loaders:
 *
 * - type and tag lookups are exact document lookups with the `"default"` schema folded in as the
 *   last step (mirroring `YamlWorkItemSchemaService.getSchemaForType`/`getSchemaForTags`);
 * - `note_limits.mode` falls back to `"warn"` when the document does not opine;
 * - status labels: no `status_labels` section means [NoOpStatusLabelService] defaults; a present
 *   section maps a trigger to its (possibly explicitly null) value, and an absent trigger to null.
 *
 * A null [layer] (no global config file) behaves as an empty document with a null fingerprint.
 */
class LayerBackedGlobalLookup(
    private val layer: ConfigLayer?,
) : GlobalConfigLookup {
    private val document: ConfigDocument = layer?.document ?: ConfigDocument.EMPTY

    override fun schemaForType(type: String): WorkItemSchema? = document.workItemSchemas[type] ?: document.workItemSchemas[DEFAULT_KEY]

    override fun notesForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = document.workItemSchemas[tag]
            if (schema != null) return schema.notes
        }
        return document.workItemSchemas[DEFAULT_KEY]?.notes
    }

    override fun traitNotes(name: String): List<NoteSchemaEntry>? = document.traits[name]

    override fun traitResources(name: String): List<ResourceRequirement> = document.traitResources[name] ?: emptyList()

    override fun traitDispatch(name: String): Map<Role, DispatchProfile> = document.traitDispatch[name] ?: emptyMap()

    override fun resourceRegistry(): Map<String, ResourceDefinition> = document.resourceRegistry

    override fun noteLimitsMode(): String = document.noteLimitsMode ?: DEFAULT_NOTE_LIMITS_MODE

    override fun fingerprint(): String? = layer?.fingerprint

    override fun traitNames(): List<String> = document.traits.keys.toList()

    override fun statusLabel(trigger: String): String? {
        val labels = document.statusLabels ?: return NoOpStatusLabelService.resolveLabel(trigger)
        return if (labels.containsKey(trigger)) labels[trigger] else null
    }

    override fun exactSchema(key: String): WorkItemSchema? = document.workItemSchemas[key]

    override fun hasExactTagSchema(tag: String): Boolean = document.workItemSchemas.containsKey(tag)

    override fun schemaResolution(): SchemaResolutionMode? = document.schemaResolution

    private companion object {
        const val DEFAULT_KEY = "default"
        const val DEFAULT_NOTE_LIMITS_MODE = "warn"
    }
}

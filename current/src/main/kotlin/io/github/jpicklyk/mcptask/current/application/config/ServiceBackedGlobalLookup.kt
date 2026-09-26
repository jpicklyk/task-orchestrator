package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * [GlobalConfigLookup] backed by the legacy global services. Pure 1:1 delegation with no caching
 * and no extra calls: each lookup invokes exactly the one [WorkItemSchemaService] or
 * [StatusLabelService] method the pre-extraction `ToolExecutionContext` invoked at the same point,
 * so tests that strict-mock only those methods keep working.
 *
 * As of AR-39 (C4), [noteSchemaService]'s own `getSchemaForType`/`getSchemaForTags` are EXACT (no
 * `"default"` fold) — the fold now lives here, in [schemaForType]/[notesForTags], via one extra
 * call to `getSchemaForType("default")` after a miss. A tags-only exact fake with no "default" key
 * returns null from that extra call, exactly matching today's result for such a fake. LAYERED and
 * ISOLATED tag probes use [exactSchema] instead, so a strict mock that stubs only
 * `getSchemaForType(any())`/`getSchemaForTags(any())` (LEGACY's calls) is never reached by them.
 */
class ServiceBackedGlobalLookup(
    private val noteSchemaService: WorkItemSchemaService,
    private val statusLabelService: StatusLabelService,
    private val schemaResolution: SchemaResolutionMode? = null,
) : GlobalConfigLookup {
    override fun schemaForType(type: String): WorkItemSchema? =
        noteSchemaService.getSchemaForType(type) ?: noteSchemaService.getSchemaForType(DEFAULT_KEY)

    override fun notesForTags(tags: List<String>): List<NoteSchemaEntry>? =
        noteSchemaService.getSchemaForTags(tags) ?: noteSchemaService.getSchemaForType(DEFAULT_KEY)?.notes

    override fun traitNotes(name: String): List<NoteSchemaEntry>? = noteSchemaService.getTraitNotes(name)

    override fun traitResources(name: String): List<ResourceRequirement> = noteSchemaService.getTraitResources(name)

    override fun traitDispatch(name: String): Map<Role, DispatchProfile> = noteSchemaService.getTraitDispatch(name)

    override fun resourceRegistry(): Map<String, ResourceDefinition> = noteSchemaService.getResourceRegistry()

    override fun noteLimitsMode(): String = noteSchemaService.getNoteLimitsMode()

    override fun fingerprint(): String? = noteSchemaService.getConfigFingerprint()

    override fun traitNames(): List<String> = noteSchemaService.getAvailableTraits()

    override fun statusLabel(trigger: String): String? = statusLabelService.resolveLabel(trigger)

    override fun exactSchema(key: String): WorkItemSchema? = noteSchemaService.getSchemaForType(key)

    override fun hasExactTagSchema(tag: String): Boolean = noteSchemaService.getSchemaForTags(listOf(tag)) != null

    override fun schemaResolution(): SchemaResolutionMode? = schemaResolution

    private companion object {
        const val DEFAULT_KEY = "default"
    }
}

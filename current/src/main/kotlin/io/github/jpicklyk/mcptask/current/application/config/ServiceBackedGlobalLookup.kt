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
 */
class ServiceBackedGlobalLookup(
    private val noteSchemaService: WorkItemSchemaService,
    private val statusLabelService: StatusLabelService,
) : GlobalConfigLookup {
    override fun schemaForType(type: String): WorkItemSchema? = noteSchemaService.getSchemaForType(type)

    override fun notesForTags(tags: List<String>): List<NoteSchemaEntry>? = noteSchemaService.getSchemaForTags(tags)

    override fun traitNotes(name: String): List<NoteSchemaEntry>? = noteSchemaService.getTraitNotes(name)

    override fun traitResources(name: String): List<ResourceRequirement> = noteSchemaService.getTraitResources(name)

    override fun traitDispatch(name: String): Map<Role, DispatchProfile> = noteSchemaService.getTraitDispatch(name)

    override fun resourceRegistry(): Map<String, ResourceDefinition> = noteSchemaService.getResourceRegistry()

    override fun noteLimitsMode(): String = noteSchemaService.getNoteLimitsMode()

    override fun fingerprint(): String? = noteSchemaService.getConfigFingerprint()

    override fun traitNames(): List<String> = noteSchemaService.getAvailableTraits()

    override fun statusLabel(trigger: String): String? = statusLabelService.resolveLabel(trigger)
}

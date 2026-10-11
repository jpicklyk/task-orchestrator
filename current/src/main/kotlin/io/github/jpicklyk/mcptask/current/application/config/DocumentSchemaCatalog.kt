package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * A synchronous [WorkItemSchemaService] view over ONE already-read server [layer] (null = no server config: every
 * catalogue is empty). The REST config routes read [ServerConfigSource.layer] once per request and view it through
 * this class instead of holding a long-lived service.
 */
class DocumentSchemaCatalog(
    private val layer: ConfigLayer?,
) : WorkItemSchemaService {
    private val document: ConfigDocument = layer?.document ?: ConfigDocument.EMPTY

    override fun getLoadWarnings(): List<String> = document.warnings

    override fun getAllSchemas(): Map<String, WorkItemSchema> = document.workItemSchemas

    override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = document.traits

    override fun getConfigFingerprint(): String? = layer?.fingerprint

    override fun getTraitResources(traitName: String): List<ResourceRequirement> = document.traitResources[traitName] ?: emptyList()

    override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = document.traitDispatch[traitName] ?: emptyMap()
}

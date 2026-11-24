package io.github.jpicklyk.mcptask.current.config.internal.app

import io.github.jpicklyk.mcptask.current.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.lease.ResourceRequirement
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.schema.DispatchProfile
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema

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

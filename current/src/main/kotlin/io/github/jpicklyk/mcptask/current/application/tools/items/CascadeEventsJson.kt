package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.application.tools.LegacyMcpCode
import io.github.jpicklyk.mcptask.current.application.tools.LegacyMcpErrorMapper
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The `cascadeEvents` array of a `manage_items` update or delete element: the old parent's re-evaluation after a
 * reparent or delete, in exactly the element shape `advance_item` emits. Null when there is nothing to report (the
 * key is then omitted).
 */
internal fun cascadeEventsJson(events: List<AdvanceCascadeEvent>): JsonArray? {
    if (events.isEmpty()) return null
    return JsonArray(
        events.map { event ->
            buildJsonObject {
                put("itemId", JsonPrimitive(event.itemId.toString()))
                put("title", JsonPrimitive(event.title))
                put("previousRole", JsonPrimitive(event.previousRole.toJsonString()))
                put("targetRole", JsonPrimitive(event.targetRole.toJsonString()))
                put("applied", JsonPrimitive(event.applied))
                if (event.gateBlocked) {
                    put("gateBlocked", JsonPrimitive(true))
                    put("missingNotes", NoteSchemaJsonHelpers.buildMissingNotesArray(event.gateMissingNotes))
                }
                if (event.resourceBlocked) {
                    put("resourceBlocked", JsonPrimitive(true))
                    put("contendedResources", JsonArray(event.contendedResources.map { JsonPrimitive(it) }))
                }
                if (event.roleBlocked) put("roleBlocked", JsonPrimitive(true))
                if (event.dependencyBlocked) {
                    put("dependencyBlocked", JsonPrimitive(true))
                    put("blockers", NoteSchemaJsonHelpers.buildBlockersArray(event.blockers))
                }
                event.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                event.error?.let { put("error", JsonPrimitive(it)) }
                NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(event.violations)?.let { put("violations", it) }
            }
        }
    )
}

/**
 * The transient per-element failure for a per-root config fault inside an item write: the element is not written,
 * the rest of the batch proceeds (the same shape `manage_notes` emits). [idKey]/[idValue] name the element.
 */
internal fun configUnavailableFailure(
    idKey: String,
    idValue: JsonPrimitive,
    e: PerRootConfigUnavailableException
): JsonObject =
    buildJsonObject {
        put(idKey, idValue)
        put("error", JsonPrimitive(e.message))
        put("errorKind", JsonPrimitive(LegacyMcpErrorMapper.kindOf(LegacyMcpCode.CONFIG_UNAVAILABLE).toJsonString()))
        put("errorCode", JsonPrimitive(LegacyMcpCode.CONFIG_UNAVAILABLE.wire))
    }

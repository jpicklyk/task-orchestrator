package io.github.jpicklyk.mcptask.current.application.service.rest

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Projects a [WorkItem] into a [JsonObject] containing only the fields that RFC 7396
 * JSON Merge Patch is allowed to touch (the field allowlist from §5.4.1).
 *
 * Server-owned identity/audit/role fields are intentionally excluded from the
 * projection — they are not present in the base object, so a patch cannot delete them.
 * A patch that explicitly names a server-owned field is rejected by the field-allowlist
 * check before the projection is consulted.
 *
 * The output is used as the "base" in [MergePatchApplier.apply].
 *
 * Allowed patchable fields: title, description, summary, statusLabel, priority, complexity,
 * tags, type, properties, metadata, requiresVerification, parentId.
 */
object WorkItemPatchProjection {
    private val lenientJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun toJsonObject(item: WorkItem): JsonObject =
        buildJsonObject {
            put("title", JsonPrimitive(item.title))
            if (item.description != null) {
                put("description", JsonPrimitive(item.description))
            } else {
                put("description", JsonNull)
            }
            put("summary", JsonPrimitive(item.summary))
            if (item.statusLabel != null) {
                put("statusLabel", JsonPrimitive(item.statusLabel))
            } else {
                put("statusLabel", JsonNull)
            }
            put("priority", JsonPrimitive(item.priority.name.lowercase()))
            if (item.complexity != null) {
                put("complexity", JsonPrimitive(item.complexity))
            } else {
                put("complexity", JsonNull)
            }
            put("requiresVerification", JsonPrimitive(item.requiresVerification))
            if (item.tags != null) {
                put("tags", JsonPrimitive(item.tags))
            } else {
                put("tags", JsonNull)
            }
            if (item.type != null) {
                put("type", JsonPrimitive(item.type))
            } else {
                put("type", JsonNull)
            }
            if (item.metadata != null) {
                put("metadata", JsonPrimitive(item.metadata))
            } else {
                put("metadata", JsonNull)
            }
            if (item.parentId != null) {
                put("parentId", JsonPrimitive(item.parentId.toString()))
            } else {
                put("parentId", JsonNull)
            }
            // properties: stored as JSON string; project it as a JsonObject for merge
            // (or JsonNull if absent). The patch handler applies MergePatchApplier on the
            // sub-object when the patch for "properties" is a JsonObject.
            if (item.properties != null) {
                val parsed =
                    runCatching {
                        lenientJson.parseToJsonElement(item.properties).jsonObject
                    }.getOrNull()
                if (parsed != null) {
                    put("properties", parsed)
                } else {
                    put("properties", JsonPrimitive(item.properties)) // non-object string kept
                }
            } else {
                put("properties", JsonNull)
            }
        }
}

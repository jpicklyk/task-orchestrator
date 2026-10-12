package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ItemSchemaView
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The schema view of the removed 3.x `query_items(schema)` (Q19), for the golden tests that pinned its bytes: the SAME
 * [ItemSchemaView] builder `items_read` (`select: schema`) serves, wrapped in the 3.x success envelope
 * (`ResponseUtil.createSuccessResponse`). Reads `type` (+ optional `rootId`) or `itemId` (a full UUID); answers
 * `{success: false}` when no schema matches or the item does not exist.
 */
class ItemSchemaProbe {
    suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val obj = params as JsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        val data =
            if (type != null) {
                val rootId = obj["rootId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) }
                ItemSchemaView.buildTypeSchemaJson(type, rootId, context.configResolver)
            } else {
                val id = UUID.fromString(obj["itemId"]!!.jsonPrimitive.content)
                context.workItemRepository().getById(id)?.let { ItemSchemaView.buildItemSchemaJson(it, context.configResolver) }
            }
        return if (data ==
            null
        ) {
            buildJsonObject { put("success", JsonPrimitive(false)) }
        } else {
            ResponseUtil.createSuccessResponse(data = data)
        }
    }
}

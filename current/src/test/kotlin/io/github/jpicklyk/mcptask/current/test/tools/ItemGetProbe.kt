package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.IdResolution
import io.github.jpicklyk.mcptask.current.application.api.IdResolver
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.toFullJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A read-back probe for 3.x write-tool tests that used to verify their writes through `query_items(get)`, removed in
 * Q19 (the 4.0 read is `items_read`). It reads the stored item straight from the repository and answers the 3.x
 * shape those tests assert on: `{success: true, data: <WorkItem.toFullJson()>}`, or `{success: false}` when the id
 * names no item. Only `itemId` (a UUID or a 3.x hex prefix) and `includeTimestamps` are read; `operation` is ignored.
 */
class ItemGetProbe {
    suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val obj = params as JsonObject
        val raw = obj["itemId"]!!.jsonPrimitive.content
        val includeTimestamps = (obj["includeTimestamps"] as? JsonPrimitive)?.booleanOrNull ?: false
        val repo = context.workItemRepository()
        val item =
            when (val resolved = IdResolver(repo).resolveLegacy(raw)) {
                is IdResolution.Resolved -> repo.getById(resolved.id)
                else -> null
            } ?: return buildJsonObject { put("success", JsonPrimitive(false)) }
        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("data", item.toFullJson(includeTimestamps = includeTimestamps))
        }
    }
}

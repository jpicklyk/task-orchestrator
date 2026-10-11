package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The ONE WorkItem -> JSON renderer of the 4.0 tools (plan section 3.3, "lean defaults"). Later items extend it
 * (pins, schema version, nullable role); nothing else renders an item for a 4.0 tool.
 *
 * Lean: a null, an empty string, an empty array and `requiresVerification: false` are omitted; `version` is always
 * present. Tags are an array (the stored comma-separated list split, each element trimmed, empty elements dropped).
 * Claim identity is never rendered.
 *
 * - [full] (get, the scoped overview's `item`): `id` and `parentId`/`rootId` as full UUIDs, every content member.
 * - [compact] (list entries, overview entries, ancestors): `id` and `parentId` as 8-hex short ids, `type`, `title`,
 *   `role`, `statusLabel`, `priority`, `depth`, `tags`, `traits`, `version`.
 */
object ItemView {
    /** Hex characters of a short id: the first 8 of the dash-free UUID. */
    const val SHORT_ID_LENGTH = 8

    /** The 8-hex short form of [id]. */
    fun shortId(id: UUID): String = id.toString().substring(0, SHORT_ID_LENGTH)

    /** The stored comma-separated [raw] tags as a list: split, trimmed, empty elements dropped. */
    fun tags(raw: String?): List<String> =
        raw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** The full view of [item], with `createdAt`/`modifiedAt`/`roleChangedAt` appended when [timestamps]. */
    fun full(
        item: WorkItem,
        timestamps: Boolean = false
    ): JsonObject =
        buildJsonObject {
            put("id", item.id.toString())
            text("parentId", item.parentId?.toString())
            text("rootId", item.rootId?.toString())
            text("type", item.type)
            text("title", item.title)
            text("summary", item.summary)
            text("description", item.description)
            put("role", item.role.name.lowercase())
            text("statusLabel", item.statusLabel)
            text("previousRole", item.previousRole?.name?.lowercase())
            put("priority", item.priority.name.lowercase())
            item.complexity?.let { put("complexity", it) }
            put("depth", item.depth)
            array("tags", tags(item.tags))
            array("traits", item.traits)
            text("metadata", item.metadata)
            text("properties", item.properties)
            if (item.requiresVerification) put("requiresVerification", true)
            put("version", item.version)
            array("diagnostics", item.diagnostics ?: emptyList())
            if (timestamps) timestampsOf(item)
        }

    /** The compact view of [item]; [summary] adds its summary, [timestamps] its three timestamps. */
    fun compact(
        item: WorkItem,
        summary: Boolean = false,
        timestamps: Boolean = false
    ): JsonObject =
        buildJsonObject {
            put("id", shortId(item.id))
            text("parentId", item.parentId?.let { shortId(it) })
            text("type", item.type)
            text("title", item.title)
            if (summary) text("summary", item.summary)
            put("role", item.role.name.lowercase())
            text("statusLabel", item.statusLabel)
            put("priority", item.priority.name.lowercase())
            put("depth", item.depth)
            array("tags", tags(item.tags))
            array("traits", item.traits)
            put("version", item.version)
            if (timestamps) timestampsOf(item)
        }

    /** [base] with [key] = [value] appended (keys keep their order). */
    fun with(
        base: JsonObject,
        key: String,
        value: kotlinx.serialization.json.JsonElement
    ): JsonObject = JsonObject(LinkedHashMap(base).apply { put(key, value) })

    /**
     * The ancestors of an item a caller under [scope] may see, root-first (plan decision D3). [chain] is the full
     * ancestor chain, root-first, the item itself excluded. A root restriction drops every ancestor above the first
     * listed root in the chain (none survive when the item is itself the listed root); a tag restriction drops every
     * ancestor that carries no listed tag.
     */
    fun visibleAncestors(
        chain: List<WorkItem>,
        scope: AccessScope
    ): List<WorkItem> {
        var out = chain
        scope.rootIds?.let { roots ->
            val first = out.indexOfFirst { it.id in roots }
            out = if (first >= 0) out.drop(first) else emptyList()
        }
        if (scope.tagsInclude.isNotEmpty()) {
            out = out.filter { ancestor -> tags(ancestor.tags).any { it in scope.tagsInclude } }
        }
        return out
    }

    private fun JsonObjectBuilder.timestampsOf(item: WorkItem) {
        put("createdAt", item.createdAt.toString())
        put("modifiedAt", item.modifiedAt.toString())
        put("roleChangedAt", item.roleChangedAt.toString())
    }

    private fun JsonObjectBuilder.text(
        key: String,
        value: String?
    ) {
        if (!value.isNullOrEmpty()) put(key, value)
    }

    private fun JsonObjectBuilder.array(
        key: String,
        values: List<String>
    ) {
        if (values.isNotEmpty()) put(key, JsonArray(values.map { JsonPrimitive(it) }))
    }
}

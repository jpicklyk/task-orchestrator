package io.github.jpicklyk.mcptask.current.itemwrite

import io.github.jpicklyk.mcptask.current.platform.idempotency.IdempotencyCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * One cascade the old parent of a moved or deleted item ran: [from] and [to] are role names, [reason] the blocking code.
 * [redacted] marks a parent outside the call's scope: only [itemId] reaches the wire ([from] and [to] are empty and
 * [applied] false, carrying nothing).
 */
data class CascadeSummary(
    val itemId: UUID,
    val from: String,
    val to: String,
    val applied: Boolean,
    val reason: String? = null,
    val redacted: Boolean = false
) {
    companion object {
        /** The `{itemId}`-only entry of a cascade into a parent outside the call's scope. */
        fun redacted(itemId: UUID): CascadeSummary = CascadeSummary(itemId, from = "", to = "", applied = false, redacted = true)
    }
}

/**
 * The result of one `items_write` element. [id] is the item the element wrote (the tree root for `create_tree`, the
 * `rootId` of a `complete_tree`; omitted when there is none). [version] is set by create, update, `create_tree` and
 * `repin`, [deleted] (the item plus its descendants) by delete; [cascade] lists the old parent's cascades and is
 * omitted from the wire when empty. [payload] holds an op's own members, spliced into `results[i]` after them
 * (`repin`: `repinned` and, for a pinned item, `schemaVersion`).
 */
data class ItemWriteResult(
    val id: UUID? = null,
    val version: Long? = null,
    val deleted: Int? = null,
    val cascade: List<CascadeSummary> = emptyList(),
    val payload: JsonObject = JsonObject(emptyMap())
)

/** The `results[i]` fields of a successful element. */
internal fun itemWriteElement(result: ItemWriteResult): JsonObject =
    buildJsonObject {
        result.id?.let { put("id", it.toString()) }
        result.version?.let { put("version", it) }
        result.deleted?.let { put("deleted", it) }
        if (result.cascade.isNotEmpty()) {
            put(
                "cascade",
                JsonArray(
                    result.cascade.map { c ->
                        buildJsonObject {
                            put("itemId", c.itemId.toString())
                            if (!c.redacted) {
                                put("from", c.from)
                                put("to", c.to)
                                put("applied", c.applied)
                                c.reason?.let { put("reason", it) }
                            }
                        }
                    }
                )
            )
        }
        for ((key, value) in result.payload) put(key, value)
    }

/** Stores an element's result in an idempotency record: exactly the fields [itemWriteElement] emits. */
internal object ItemWriteCodec : IdempotencyCodec<ItemWriteResult> {
    /** The members [itemWriteElement] writes from the typed fields; every other member is [ItemWriteResult.payload]. */
    private val RESULT_MEMBERS = setOf("id", "version", "deleted", "cascade")

    override fun encode(value: ItemWriteResult): JsonElement = itemWriteElement(value)

    override fun decode(json: JsonElement): ItemWriteResult {
        val obj = json.jsonObject
        return ItemWriteResult(
            id = obj["id"]?.jsonPrimitive?.contentOrNull?.let(UUID::fromString),
            version = obj["version"]?.jsonPrimitive?.longOrNull,
            deleted = obj["deleted"]?.jsonPrimitive?.intOrNull,
            cascade =
                (obj["cascade"] as? JsonArray).orEmpty().map { element ->
                    val c = element.jsonObject
                    val itemId = UUID.fromString(c.getValue("itemId").jsonPrimitive.content)
                    if ("from" !in c) {
                        CascadeSummary.redacted(itemId)
                    } else {
                        CascadeSummary(
                            itemId = itemId,
                            from = c.getValue("from").jsonPrimitive.content,
                            to = c.getValue("to").jsonPrimitive.content,
                            applied = c.getValue("applied").jsonPrimitive.booleanOrNull ?: false,
                            reason = c["reason"]?.jsonPrimitive?.contentOrNull
                        )
                    }
                },
            payload = JsonObject(obj.filterKeys { it !in RESULT_MEMBERS })
        )
    }
}

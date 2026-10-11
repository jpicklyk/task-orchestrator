package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Sweep fixtures for tests that drive item writes through the production `items_write` tool over the real
 * dispatcher (the removed `manage_items` was their previous entry point). Pure plumbing: no assertion logic lives
 * here, and the oracle of every test stays in the test itself.
 */
internal suspend fun ToolExecutionContext.itemsWrite(
    vararg ops: JsonObject,
    atomic: Boolean = false,
    actor: JsonObject? = null,
    reqId: String = "k7f3q9ab",
    profile: ToolProfile? = null
): JsonObject {
    val spec = checkNotNull(ToolRegistry(buildToolSpecs())["items_write"]) { "items_write is not registered" }
    // Default: knowledge, unless an op is orchestration-only (create_tree and complete_tree, amendment U23).
    val effectiveProfile =
        profile ?: if (ops.any { it.opName() in ORCHESTRATION_ONLY_OPS }) ToolProfile.ORCHESTRATION else ToolProfile.KNOWLEDGE
    val arguments =
        buildJsonObject {
            actor?.let { put("actor", it) }
            if (atomic) put("atomic", true)
            put("ops", JsonArray(ops.toList()))
        }
    return buildToolDispatcher(this).dispatch(spec, effectiveProfile, arguments, reqId, "mcp").envelope.body
}

/** The `items_write` ops the knowledge profile hides. */
private val ORCHESTRATION_ONLY_OPS = setOf("create_tree", "complete_tree")

private fun JsonObject.opName(): String? = (this["op"] as? JsonPrimitive)?.content

/** [ToolExecutionContext.itemsWrite] over the rig's production composition. */
internal suspend fun EventLogRig.itemsWrite(
    vararg ops: JsonObject,
    atomic: Boolean = false,
    actor: JsonObject? = null,
    reqId: String = "k7f3q9ab",
    profile: ToolProfile? = null
): JsonObject = ctx.itemsWrite(*ops, atomic = atomic, actor = actor, reqId = reqId, profile = profile)

/** A `create` element for [title]; [extra] adds or overrides fields. */
internal fun createOp(
    title: String,
    parentId: UUID? = null,
    type: String? = TEST_ITEM_TYPE,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "create")
        put("title", title)
        if (type != null) put("type", type)
        if (parentId != null) put("parentId", parentId.toString())
        extra.forEach { (k, v) -> put(k, v) }
    }

/** An `update` element for [id] with [fields]. */
internal fun updateOp(
    id: UUID,
    vararg fields: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "update")
        put("id", id.toString())
        fields.forEach { (k, v) -> put(k, v) }
    }

/** A `delete` element for [id]. */
internal fun deleteOp(
    id: UUID,
    recursive: Boolean = false,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "delete")
        put("id", id.toString())
        if (recursive) put("recursive", true)
        extra.forEach { (k, v) -> put(k, v) }
    }

/** A `create_tree` element with [fields] (`root`, `children`, `deps`, `notes`, ...). */
internal fun createTreeOp(vararg fields: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        put("op", "create_tree")
        fields.forEach { (k, v) -> put(k, v) }
    }

/**
 * A `complete_tree` element with [fields] (`rootId` or `itemIds`, `trigger`, `includeRoot`). The op is in the
 * orchestration profile: dispatch it with `profile = ToolProfile.ORCHESTRATION`.
 */
internal fun completeTreeOp(vararg fields: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        put("op", "complete_tree")
        fields.forEach { (k, v) -> put(k, v) }
    }

/** The `items[i].id` of a successful tree element ([okResult]). */
internal fun JsonObject.treeItemId(i: Int): UUID =
    UUID.fromString(
        this["items"]!!
            .jsonArray[i]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    )

/** A string-array JSON value. */
internal fun strings(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/** The `data.results` array of a batch envelope. */
internal fun JsonObject.results(): List<JsonObject> = this["data"]!!.jsonObject["results"]!!.jsonArray.map { it.jsonObject }

/** The `i`-th result, which must be `ok:true`. */
internal fun JsonObject.okResult(i: Int = 0): JsonObject {
    val r = results()[i]
    check(r["ok"]!!.jsonPrimitive.boolean) { "result $i is not ok: $this" }
    return r
}

/** The id of the `i`-th result, which must be `ok:true`. */
internal fun JsonObject.okId(i: Int = 0): UUID = UUID.fromString(okResult(i)["id"]!!.jsonPrimitive.content)

/** The `i`-th failing result's error object (the batch envelope's per-element error). */
internal fun JsonObject.errorAt(i: Int = 0): JsonObject {
    val r = results()[i]
    check(!r["ok"]!!.jsonPrimitive.boolean) { "result $i is not an error: $this" }
    return r["error"]!!.jsonObject
}

/** JSON null, for `clear`-free no-op fields in tests. */
internal val JSON_NULL: JsonElement = JsonNull

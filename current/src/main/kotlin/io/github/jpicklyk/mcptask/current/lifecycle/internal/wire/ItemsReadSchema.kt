package io.github.jpicklyk.mcptask.current.lifecycle.internal.wire

import io.github.jpicklyk.mcptask.current.item.ItemView
import io.github.jpicklyk.mcptask.current.item.ItemsReadDocs
import io.github.jpicklyk.mcptask.current.item.resolveTarget
import io.github.jpicklyk.mcptask.current.lifecycle.LifecycleToolServices
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.ItemSchemaView
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.acceptFields
import io.github.jpicklyk.mcptask.current.platform.tool.invalidFields
import io.github.jpicklyk.mcptask.current.platform.tool.itemNotFound
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
import io.github.jpicklyk.mcptask.current.platform.tool.pointer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `schema`: the resolved note schema of a type ([Request.type], optionally under [Request.rootId])
 * or of an item ([Request.itemId]), built by [ItemSchemaView]; with neither, the declared types and their lifecycle
 * (S63). At most one of `type` and `itemId`; `rootId` not with `itemId`. Ids are resolved under D2 and scope-checked. No matching schema is not an error: `{schemaFree: true}`
 * (D6). Per-note `guidance` is stripped unless `fields: ["guidance"]`.
 */
object ItemsReadSchema {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("guidance")

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.TYPE) val type: String? = null,
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef? = null,
        @Doc("With type, or with neither type nor itemId: the project root whose config resolves it (UUID or 8-32 hex).")
        val rootId: IdRef? = null,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
    )

    val select =
        SelectSpec.Single<LifecycleToolServices, Request, JsonObject>(
            name = "schema",
            doc = "The note schema of a type or of an item, or with neither the declared types and their lifecycle; schemaFree when no schema matches; guidance only with fields.",
            profiles = knowledge(),
            services = LifecycleToolServices::class,
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
            handler = handler@{ env, ctx, req ->
                acceptFields(req.fields, FIELDS)?.let { return@handler Outcome.Err(it) }
                val faults = ArrayList<FieldViolation>()
                if (req.type != null && req.itemId != null) {
                    faults.add(FieldViolation(pointer("type"), "give at most one of type and itemId"))
                }
                if (req.rootId != null && req.itemId != null) {
                    faults.add(FieldViolation(pointer("rootId"), "is not allowed with itemId"))
                }
                if (faults.isNotEmpty()) return@handler Outcome.Err(invalidFields(faults))

                if (req.itemId == null) {
                    val rootId =
                        if (req.rootId == null) {
                            null
                        } else {
                            when (val r = resolveTarget(env, ctx, req.rootId, "rootId")) {
                                is Outcome.Err -> return@handler r
                                is Outcome.Ok -> r.value
                            }
                        }
                    if (req.type == null) return@handler Outcome.Ok(ItemSchemaView.buildDeclaredTypesJson(rootId, env.configResolver))
                    val typed = ItemSchemaView.buildTypeSchemaJson(req.type, rootId, env.configResolver)
                    return@handler Outcome.Ok(schemaOrFree(typed, req.fields))
                }
                val id =
                    when (val r = resolveTarget(env, ctx, req.itemId, "itemId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val item = env.workItemRepository().getById(id) ?: return@handler Outcome.Err(itemNotFound(id))
                Outcome.Ok(schemaOrFree(ItemSchemaView.buildItemSchemaJson(item, env.configResolver), req.fields))
            },
            data = { it }
        )

    /** [schema], guidance stripped unless asked for, or `{schemaFree: true}` when no schema matches. */
    private fun schemaOrFree(
        schema: JsonObject?,
        fields: List<String>?
    ): JsonObject =
        when {
            schema == null -> buildJsonObject { put("schemaFree", true) }
            "guidance" in fields.orEmpty() -> schema
            else -> withoutGuidance(schema)
        }

    /** [schema] with the `guidance` member removed from every entry of `notes`. */
    private fun withoutGuidance(schema: JsonObject): JsonObject {
        val notes = schema["notes"] as? JsonArray ?: return schema
        val stripped = JsonArray(notes.map { note -> if (note is JsonObject) JsonObject(note.filterKeys { it != "guidance" }) else note })
        return ItemView.with(schema, "notes", stripped)
    }
}

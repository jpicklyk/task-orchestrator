package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders a [ToolSpec] for one [ToolProfile]: its input schema, its description and its MCP annotations.
 *
 * - Select tool properties: `select` (an enum of the visible selects in registration order), the union of
 *   the visible selects' fields (first appearance order), then `describe`.
 * - Ops tool properties: `ops` (an array whose items hold `op`, an enum of the visible ops, the union of
 *   the visible ops' fields, and `idempotencyKey`; items require `op`), `atomic`, then `describe`.
 * - There is no top-level `required`, so a `{describe:true}` call is schema-valid. Per-select/op
 *   requiredness is published by `describe` and enforced by the decoder.
 * - A union field not declared by every visible select/op gets ` [select: a, b]` / ` [ops: a, b]`
 *   appended to its description (when it has one), naming the visible selects/ops that take it.
 * - A select/op hidden by the profile contributes nothing, so a field only it declares disappears.
 */
object ToolSchemaRenderer {
    const val SELECT_DOC = "Which read to run. A field marked [select: ...] applies only to the selects named."
    const val OPS_DOC = "Write operations, one flat object per op, run in order. A field marked [ops: ...] applies only to the ops named."
    const val OP_DOC = "The operation this element runs."
    const val ATOMIC_DOC = "true: the first failing op rolls back the whole batch. Default false: each op commits on its own."
    const val IDEMPOTENCY_KEY_DOC =
        "Optional client key (1-128 chars, needs actor). Resending the same op with the same key returns the stored result."
    const val DESCRIBE_DOC = "true: return the operations, their parameters and errors instead of running the tool."
    const val DESCRIBE_HINT = "Call with describe:true for each operation's parameters and errors."

    /** The input schema `{type:object, properties}` of [spec] in [profile], or null when nothing is visible. */
    fun inputSchema(
        spec: ToolSpec,
        profile: ToolProfile
    ): JsonObject? {
        val properties =
            when (spec) {
                is SelectToolSpec -> selectProperties(spec, profile)
                is OpsToolSpec<*> -> opsProperties(spec, profile)
            } ?: return null
        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
        }
    }

    /** The rendered tool description: the spec's description plus [DESCRIBE_HINT]. */
    fun description(spec: ToolSpec): String = spec.description + " " + DESCRIBE_HINT

    /**
     * MCP annotations. Select tool: read-only, not destructive, idempotent, closed world. Ops tool: not
     * read-only, destructive exactly when a visible op is destructive, not idempotent, closed world.
     */
    fun annotations(
        spec: ToolSpec,
        profile: ToolProfile
    ): ToolAnnotations =
        when (spec) {
            is SelectToolSpec ->
                ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
            is OpsToolSpec<*> ->
                ToolAnnotations(
                    readOnlyHint = false,
                    destructiveHint = spec.visibleOps(profile).any { it.destructive },
                    idempotentHint = false,
                    openWorldHint = false
                )
        }

    private fun selectProperties(
        spec: SelectToolSpec,
        profile: ToolProfile
    ): JsonObject? {
        val visible = spec.visibleSelects(profile)
        if (visible.isEmpty()) return null
        val names = visible.map { it.name }
        return buildJsonObject {
            put("select", enumProperty(SELECT_DOC, names))
            for ((field, schema) in union(visible.map { it.name to it.request.descriptor }, "select")) put(field, schema)
            put("describe", booleanProperty(DESCRIBE_DOC))
        }
    }

    private fun opsProperties(
        spec: OpsToolSpec<*>,
        profile: ToolProfile
    ): JsonObject? {
        val visible = spec.visibleOps(profile)
        if (visible.isEmpty()) return null
        val names = visible.map { it.name }
        val items =
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("op", enumProperty(OP_DOC, names))
                        for ((field, schema) in union(visible.map { it.name to it.request.descriptor }, "ops")) put(field, schema)
                        put(
                            "idempotencyKey",
                            buildJsonObject {
                                put("type", "string")
                                put("description", IDEMPOTENCY_KEY_DOC)
                            }
                        )
                    }
                )
                put("required", JsonArray(listOf(JsonPrimitive("op"))))
            }
        return buildJsonObject {
            put(
                "ops",
                buildJsonObject {
                    put("type", "array")
                    put("description", OPS_DOC)
                    put("items", items)
                }
            )
            put("atomic", booleanProperty(ATOMIC_DOC))
            put("describe", booleanProperty(DESCRIBE_DOC))
        }
    }

    /**
     * The union of the members' top-level fields in first-appearance order. The registry guarantees that a
     * field has one schema across members; a field missing from some member gets the `[label: ...]` suffix.
     */
    private fun union(
        members: List<Pair<String, SerialDescriptor>>,
        label: String
    ): LinkedHashMap<String, JsonObject> {
        val schemas = LinkedHashMap<String, JsonObject>()
        val declaredBy = LinkedHashMap<String, MutableList<String>>()
        for ((name, descriptor) in members) {
            for ((field, schema) in JsonSchemaGenerator.properties(descriptor)) {
                schemas.putIfAbsent(field, schema)
                declaredBy.getOrPut(field) { ArrayList() }.add(name)
            }
        }
        val out = LinkedHashMap<String, JsonObject>()
        for ((field, schema) in schemas) {
            val owners = declaredBy.getValue(field)
            val description = (schema["description"] as? JsonPrimitive)?.content
            out[field] =
                if (owners.size == members.size || description == null) {
                    schema
                } else {
                    JsonObject(schema + ("description" to JsonPrimitive("$description [$label: ${owners.joinToString(", ")}]")))
                }
        }
        return out
    }

    private fun enumProperty(
        doc: String,
        values: List<String>
    ): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", doc)
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        }

    private fun booleanProperty(doc: String): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            put("description", doc)
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advanceTool
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes.notesReadTool
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes.notesWriteTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.configTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps.depsReadTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps.depsWriteTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemsReadTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.itemsWriteTool
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject

/**
 * The 4.0 tools, validated once at construction.
 *
 * `init` throws [IllegalArgumentException], naming the tool, select/op and field, for:
 * - a duplicate tool name, or a duplicate select/op name within one tool;
 * - a tool or select with no select/op;
 * - a tool name not matching `^[a-z][a-z0-9_]{0,63}$` (the Messages API tool-name charset, F15), or a
 *   select/op name not matching `^[a-z][a-z_]{0,63}$` (the `call_log` operation pattern);
 * - a request class that is not a class or object, or that declares a reserved field: `actor`,
 *   `describe`, `select` for a select; `actor`, `op`, `idempotencyKey` for an op element;
 * - a polymorphic or sealed member anywhere in a request (and any other type the schema generator
 *   rejects, such as a recursive class);
 * - the same field name in two selects/ops of one tool with a different generated schema (the tool's
 *   input schema shows one union of fields, so they must agree).
 */
class ToolRegistry(
    specs: List<ToolSpec>
) {
    val tools: List<ToolSpec> = specs.toList()

    private val byName: Map<String, ToolSpec>

    init {
        val names = LinkedHashMap<String, ToolSpec>()
        for (spec in tools) {
            require(TOOL_NAME.matches(spec.name)) { "Tool '${spec.name}': name must match ${TOOL_NAME.pattern}" }
            require(names.put(spec.name, spec) == null) { "Tool '${spec.name}': duplicate tool name" }
            when (spec) {
                is SelectToolSpec ->
                    validate(
                        spec.name,
                        "select",
                        spec.selects.map { Member(it.name, it.request.descriptor) },
                        SELECT_FORBIDDEN_FIELDS
                    )
                is OpsToolSpec<*> ->
                    validate(
                        spec.name,
                        "op",
                        spec.ops.map { Member(it.name, it.request.descriptor) },
                        OpSpec.OP_FORBIDDEN_FIELDS
                    )
            }
        }
        byName = names
    }

    operator fun get(name: String): ToolSpec? = byName[name]

    /** The tools with at least one select/op visible in [profile], in registration order. */
    fun visible(profile: ToolProfile): List<ToolSpec> = tools.filter { visibleNames(it, profile).isNotEmpty() }

    private class Member(
        val name: String,
        val descriptor: SerialDescriptor
    )

    private fun validate(
        tool: String,
        noun: String,
        members: List<Member>,
        forbidden: Set<String>
    ) {
        require(members.isNotEmpty()) { "Tool '$tool': declares no $noun" }
        val seen = HashSet<String>()
        // field name -> (first declaring member, its generated schema)
        val fields = LinkedHashMap<String, Pair<String, JsonObject>>()
        for (member in members) {
            val where = "Tool '$tool' $noun '${member.name}'"
            require(OP_NAME.matches(member.name)) { "$where: name must match ${OP_NAME.pattern}" }
            require(seen.add(member.name)) { "$where: duplicate $noun name" }
            val kind = member.descriptor.kind
            require(kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
                "$where: request must be a class or object, was $kind"
            }
            val properties =
                try {
                    JsonSchemaGenerator.schema(member.descriptor)
                    JsonSchemaGenerator.properties(member.descriptor)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("$where: ${e.message}", e)
                }
            for ((field, schema) in properties) {
                require(field !in forbidden) { "$where: field '$field' is reserved" }
                val first = fields[field]
                if (first == null) {
                    fields[field] = member.name to schema
                } else {
                    require(first.second == schema) {
                        "$where: field '$field' has a different schema than in $noun '${first.first}'"
                    }
                }
            }
        }
    }

    companion object {
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
        val OP_NAME = Regex("^[a-z][a-z_]{0,63}$")

        /** Field names a select request class must not declare. */
        val SELECT_FORBIDDEN_FIELDS: Set<String> = SelectSpec.SELECT_RESERVED
    }
}

/**
 * The production 4.0 tools. Each tool item appends its spec here (and deletes the 3.x tools it replaces from
 * `buildMcpTools()`). Q11 registered `notes_read` and `notes_write`, the first two; Q12 added
 * `deps_read` and `deps_write`; Q13 added `advance`; Q18 added `items_write`; Q19 added `items_read`; Q23 added `config`.
 */
fun buildToolSpecs(): List<ToolSpec> =
    listOf(
        itemsWriteTool(),
        notesReadTool,
        notesWriteTool,
        depsReadTool(),
        depsWriteTool(),
        advanceTool(),
        ItemsReadTool.spec,
        configTool(),
    )

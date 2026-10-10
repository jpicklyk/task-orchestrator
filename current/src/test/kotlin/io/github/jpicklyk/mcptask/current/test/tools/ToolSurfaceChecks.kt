package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Registry-level surface checks (task-scope D11 budgets and D12 documentation rule) written independently as pure
 * functions over a [ToolRegistry], so the boundary behaviour (8,000 ok, 8,001 flagged) is provable on fixtures and the same
 * functions run over the production `buildToolSpecs()` registry.
 *
 * Sizes are character counts of the compact `tools/list` entry ([ToolGoldens.compact] of [McpOperationAdapter.toolFor]).
 */
object ToolSurfaceChecks {
    const val PER_TOOL_CEILING = 8_000
    const val KNOWLEDGE_TOTAL_CEILING = 22_000
    const val ORCHESTRATION_TOTAL_CEILING = 38_000

    /** Compact char count per visible tool of [profile], in registry order. */
    fun sizes(
        registry: ToolRegistry,
        profile: ToolProfile
    ): Map<String, Int> =
        registry.tools
            .mapNotNull { spec -> McpOperationAdapter.toolFor(spec, profile)?.let { spec.name to ToolGoldens.compact(it).length } }
            .toMap(LinkedHashMap())

    fun totalCeiling(profile: ToolProfile): Int =
        when (profile) {
            ToolProfile.KNOWLEDGE -> KNOWLEDGE_TOTAL_CEILING
            ToolProfile.ORCHESTRATION -> ORCHESTRATION_TOTAL_CEILING
        }

    /** One message per breached ceiling: a tool over 8,000 in a profile, or a profile total over its ceiling. */
    fun budgetViolations(registry: ToolRegistry): List<String> {
        val out = mutableListOf<String>()
        for (profile in ToolProfile.entries) {
            val sizes = sizes(registry, profile)
            for ((tool, size) in sizes) {
                if (size > PER_TOOL_CEILING) out += "$tool/${profile.wire}: $size chars exceeds $PER_TOOL_CEILING"
            }
            val total = sizes.values.sum()
            if (total > totalCeiling(profile)) out += "total/${profile.wire}: $total chars exceeds ${totalCeiling(profile)}"
        }
        return out
    }

    /**
     * One message per documentation fault: a rendered tool description without the describe hint, or an input-schema property
     * at any depth (including `ops.items` and nested classes) whose description is absent or blank.
     */
    fun docViolations(registry: ToolRegistry): List<String> {
        val out = mutableListOf<String>()
        for (profile in ToolProfile.entries) {
            for (spec in registry.tools) {
                val schema = ToolSchemaRenderer.inputSchema(spec, profile) ?: continue
                val label = "${spec.name}/${profile.wire}"
                if (ToolSchemaRenderer.DESCRIBE_HINT !in
                    ToolSchemaRenderer.description(spec)
                ) {
                    out += "$label: description lacks the describe hint"
                }
                walk(schema, "", label, out)
            }
        }
        return out
    }

    private fun walk(
        schema: JsonElement,
        path: String,
        label: String,
        out: MutableList<String>
    ) {
        val obj = schema as? JsonObject ?: return
        (obj["properties"] as? JsonObject)?.forEach { (name, child) ->
            val childPath = "$path/$name"
            val description = ((child as? JsonObject)?.get("description") as? JsonPrimitive)?.content
            if (description.isNullOrBlank()) out += "$label$childPath: no description"
            walk(child, childPath, label, out)
        }
        obj["items"]?.let { walk(it, "$path[]", label, out) }
        (obj["additionalProperties"] as? JsonObject)?.let { walk(it, "$path{}", label, out) }
    }

    /** Every property name at every depth of [schema] (used to prove a walk reaches nested properties). */
    fun propertyPaths(schema: JsonObject): List<String> {
        val out = mutableListOf<String>()

        fun go(
            node: JsonElement,
            path: String
        ) {
            val obj = node as? JsonObject ?: return
            (obj["properties"] as? JsonObject)?.forEach { (name, child) ->
                out += "$path/$name"
                go(child, "$path/$name")
            }
            obj["items"]?.let { go(it, "$path[]") }
        }
        go(schema, "")
        return out
    }
}

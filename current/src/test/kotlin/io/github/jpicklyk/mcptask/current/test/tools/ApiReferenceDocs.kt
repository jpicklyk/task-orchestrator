package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ErrorDetailDocs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Renders the generated blocks of `docs/api-reference.md` (Q33 / 8c5cb2a3): one block per registry tool and one error
 * catalog block. The source of truth is the registry ([ToolDescriber] over the orchestration profile, which shows every
 * select and op, with each op's own profile read from its spec), so the tables can never drift from the request classes.
 *
 * Markers: `<!-- generated:tool NAME -->` ... `<!-- /generated:tool NAME -->` and
 * `<!-- generated:error-catalog -->` ... `<!-- /generated:error-catalog -->`. Prose around the blocks stays hand-written.
 * Output is deterministic: LF, no trailing spaces, `|` escaped, newlines in descriptions collapsed.
 *
 * Paths are relative to the Gradle test working directory (the `current/` project directory).
 */
object ApiReferenceDocs {
    val FILE: Path = Paths.get("docs", "api-reference.md")
    const val REGENERATE_TASK = ":current:regenerateApiReference"
    const val CATALOG = "error-catalog"

    private val START = Regex("<!-- generated:(error-catalog|tool [a-z][a-z0-9_]*) -->")

    fun startMarker(name: String): String = if (name == CATALOG) "<!-- generated:$CATALOG -->" else "<!-- generated:tool $name -->"

    fun endMarker(name: String): String = if (name == CATALOG) "<!-- /generated:$CATALOG -->" else "<!-- /generated:tool $name -->"

    /** The full block (markers included) of [name], a tool name or [CATALOG]. */
    fun block(
        name: String,
        registry: ToolRegistry
    ): String {
        val body = if (name == CATALOG) catalogBody() else toolBody(requireNotNull(registry[name]) { "unknown tool $name" })
        return startMarker(name) + "\n" + body + "\n" + endMarker(name)
    }

    /** Every expected block name: the tools in registration order, then the catalog. */
    fun names(registry: ToolRegistry): List<String> = registry.tools.map { it.name } + CATALOG

    /** The block names found in [text] (start markers), in order. */
    fun foundNames(text: String): List<String> = START.findAll(text).map { it.groupValues[1].removePrefix("tool ") }.toList()

    /** Text of the block of [name] currently in [text] (markers included), or null when absent. */
    fun extract(
        text: String,
        name: String
    ): String? {
        val start = text.indexOf(startMarker(name))
        if (start < 0) return null
        val endMark = endMarker(name)
        val end = text.indexOf(endMark, start)
        if (end < 0) return null
        return text.substring(start, end + endMark.length)
    }

    /** [text] with the block of [name] replaced by its fresh rendering; fails when a marker pair is absent. */
    fun replace(
        text: String,
        name: String,
        registry: ToolRegistry
    ): String {
        val current = extract(text, name) ?: error("api-reference.md has no marker pair for '$name' (${startMarker(name)})")
        return text.replace(current, block(name, registry))
    }

    fun normalize(text: String): String = text.replace("\r\n", "\n")

    fun read(path: Path = FILE): String = normalize(Files.readString(path, Charsets.UTF_8))

    // ------------------------------------------------------------------ rendering

    private fun toolBody(spec: ToolSpec): String {
        val described = ToolDescriber.describe(spec, ToolProfile.ORCHESTRATION, null)
        val ops = described.getValue("operations").jsonArray
        val noun = if (spec is SelectToolSpec) "select" else "op"
        val out = StringBuilder()
        out.append(cell(spec.description)).append("\n")
        for (op in ops) {
            val o = op.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            out.append("\n")
            out.append(
                "**`${spec.name}` $noun `$name`** (${meta(spec, name)}): ${cell(o.getValue("description").jsonPrimitive.content)}\n\n"
            )
            out.append(table(o.getValue("parameters").jsonObject))
            val codes =
                o.getValue("errors").jsonArray.map {
                    it.jsonObject
                        .getValue("code")
                        .jsonPrimitive.content
                }
            out.append("\nErrors: ").append(codes.joinToString(", ") { "`$it`" }).append("\n")
        }
        return out.toString().trimEnd('\n')
    }

    private fun meta(
        spec: ToolSpec,
        op: String
    ): String =
        when (spec) {
            is SelectToolSpec -> {
                val s = spec.selects.single { it.name == op }
                "${profileLabel(s.profile)}; capability `${wire(s.capability)}`"
            }
            is OpsToolSpec<*> -> {
                val o = spec.ops.single { it.name == op }
                val destructive = if (o.destructive) "; destructive" else ""
                "${profileLabel(o.profile)}; capability `${wire(o.capability)}`$destructive"
            }
        }

    /** The kebab-case form capabilities use in config and prose (`write-items`). */
    private fun wire(c: ApiCapability): String = c.name.lowercase().replace('_', '-')

    private fun profileLabel(p: ToolProfile): String =
        if (p == ToolProfile.KNOWLEDGE) "knowledge and orchestration profiles" else "orchestration profile only"

    private class Row(
        val field: String,
        val type: String,
        val required: Boolean,
        val description: String
    )

    private fun table(schema: JsonObject): String {
        val rows = ArrayList<Row>()
        flatten(schema, "", rows)
        if (rows.isEmpty()) return "No fields.\n"
        val out = StringBuilder("| Field | Type | Required | Description |\n| --- | --- | --- | --- |\n")
        for (r in rows) {
            out.append("| `${r.field}` | ${cell(r.type)} | ${if (r.required) "yes" else "no"} | ${cell(r.description)} |\n")
        }
        return out.toString()
    }

    private fun flatten(
        schema: JsonObject,
        prefix: String,
        rows: MutableList<Row>
    ) {
        val properties = schema["properties"]?.jsonObject ?: return
        val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        for ((name, value) in properties) {
            val p = value.jsonObject
            val path = prefix + name
            rows += Row(path, typeOf(p), name in required, p["description"]?.jsonPrimitive?.content.orEmpty())
            if (p["properties"] != null) {
                flatten(p, "$path.", rows)
            } else if (p["type"]?.jsonPrimitive?.content == "array") {
                val items = p["items"]?.jsonObject
                if (items?.get("properties") != null) flatten(items, "$path[].", rows)
            }
        }
    }

    private fun typeOf(p: JsonObject): String {
        val type = p["type"]?.jsonPrimitive?.content ?: return "any"
        return when {
            p["enum"] != null -> "string: " + p.getValue("enum").jsonArray.joinToString(" | ") { it.jsonPrimitive.content }
            type == "array" -> "array of " + (p["items"]?.jsonObject?.let { typeOf(it) } ?: "any")
            type == "object" && p["additionalProperties"] != null -> "map of " + typeOf(p.getValue("additionalProperties").jsonObject)
            p["format"] != null -> "$type (${p.getValue("format").jsonPrimitive.content})"
            else -> type
        }
    }

    private fun catalogBody(): String {
        val out = StringBuilder("| Code | Kind | HTTP status | Detail |\n| --- | --- | --- | --- |\n")
        for (code in ErrorCode.entries) {
            val detail =
                ErrorDetailDocs.of(code)?.entries?.joinToString("; ") { "`${it.key}`: ${it.value.jsonPrimitive.content}" } ?: "none"
            out.append("| `${code.wire}` | ${code.kind.toJsonString()} | ${code.httpStatus} | ${cell(detail)} |\n")
        }
        return out.toString().trimEnd('\n')
    }

    /** One table cell or paragraph line: newlines collapsed, `|` escaped, no trailing spaces. */
    private fun cell(text: String): String =
        text
            .replace(Regex("\\s*\\r?\\n\\s*"), " ")
            .trim()
            .replace("|", "\\|")
}

package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Path and render helpers for the per-tool 4.0 schema goldens (task-scope D10): one pretty JSON file per
 * (tool, visible profile) at [DIR]/`<tool>.<profile>.json`, whose content is the `tools/list` entry
 * [McpOperationAdapter.toolFor] registers. No assertions live here; the golden tests compare against
 * [expected] and write actuals to [ACTUAL_DIR] on a mismatch, naming [REGENERATE_TASK].
 *
 * Paths are relative to the Gradle test working directory (the `current/` project directory).
 */
object ToolGoldens {
    val DIR: Path = Paths.get("src", "test", "resources", "golden", "tools")
    val ACTUAL_DIR: Path = Paths.get("build", "tool-goldens.actual")
    const val REGENERATE_TASK = ":current:regenerateToolGoldens"
    const val EXTENSION = ".json"

    /** `tools/list` encoding: the MCP JSON config (`explicitNulls = false`), compact. */
    private val compactJson: Json = McpJson

    private val prettyJson: Json =
        Json(McpJson) {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun fileName(
        tool: String,
        profile: ToolProfile
    ): String = "$tool.${profile.wire}$EXTENSION"

    /** The compact `tools/list` JSON of [tool] (what the budget guards measure). */
    fun compact(tool: Tool): String = compactJson.encodeToString(Tool.serializer(), tool)

    /** The golden file content of [spec] in [profile] (pretty, LF, trailing newline), or null when hidden. */
    fun render(
        spec: ToolSpec,
        profile: ToolProfile
    ): String? {
        val tool = McpOperationAdapter.toolFor(spec, profile) ?: return null
        return prettyJson.encodeToString(Tool.serializer(), tool).replace("\r\n", "\n") + "\n"
    }

    /** Every golden file of [registry]: file name to content, over each tool and each profile it is visible in. */
    fun expected(registry: ToolRegistry): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for (spec in registry.tools) {
            for (profile in ToolProfile.entries) {
                render(spec, profile)?.let { out[fileName(spec.name, profile)] = it }
            }
        }
        return out
    }

    /** The golden files present in [dir]: file name to content (empty when [dir] does not exist). */
    fun read(dir: Path = DIR): Map<String, String> {
        if (!Files.isDirectory(dir)) return emptyMap()
        Files.list(dir).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(EXTENSION) }
                .toList()
                .associate { it.fileName.toString() to Files.readString(it) }
                .toSortedMap()
        }
    }

    /** What [write] changed. */
    data class WriteResult(
        val written: List<String>,
        val deleted: List<String>
    )

    /**
     * Writes every [expected] file of [registry] into [dir] (UTF-8, LF) and deletes any other `*.json` there,
     * so a removed tool or a profile that no longer shows a tool leaves no stale golden.
     */
    fun write(
        registry: ToolRegistry,
        dir: Path = DIR
    ): WriteResult {
        val files = expected(registry)
        Files.createDirectories(dir)
        val stale = read(dir).keys - files.keys
        stale.forEach { Files.delete(dir.resolve(it)) }
        files.forEach { (name, content) -> Files.writeString(dir.resolve(name), content) }
        return WriteResult(files.keys.toList(), stale.sorted())
    }
}

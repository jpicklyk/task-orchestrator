package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.fail

/**
 * Guards against tool parameters that are declared in a tool's `parameterSchema` but never read
 * (proposal 6ce8e087, issue #290). A declared-but-unread parameter is accepted by MCP validation,
 * silently dropped, and the call reports success — worse than an unknown parameter when the same
 * name DOES work elsewhere (the `manage_items(update)` top-level `traits` incident fixed in #289).
 *
 * Same family as [ToolDocumentationConsistencyTest] and [ToolTokenBudgetTest]: the tool list is
 * derived from [buildMcpTools], so every tool the production server registers is covered.
 *
 * Contract — every declared top-level parameter must EITHER:
 *   1. be read somewhere in the tool's own source file, OR
 *   2. carry an explicit `Ignored` qualifier (capital I, whole word) in its own schema field
 *      `description`, following the `requiresVerification` precedent on `manage_items`
 *      ("Ignored at the top level for create — set requiresVerification on each item ...").
 *
 * ## Read-detection heuristic (static source scan)
 *
 * The tool's source file is located from its class's package + simple name under
 * `current/src/main/kotlin`. Before scanning, the file is stripped of:
 *   - block comments / KDoc and whole-line or trailing ` // ` comments,
 *   - triple-quoted raw strings (the prose `description`),
 *   - the `override val parameterSchema = ToolSchema(...)` initializer (paren-matched), since
 *     the declaration itself names every parameter.
 *
 * A parameter counts as read when its exact string literal (`"name"`) still appears in what
 * remains, EXCEPT as the first argument of `put(` / `putJsonObject(` / `putJsonArray(` (response
 * building, not a read). A small table of shared helpers whose parameter name is a default
 * argument (`validateRequestIdParam(params)` reads `requestId`) supplies implicit reads.
 *
 * Why only the tool's own file: top-level params are read in the tool file (directly, or by
 * passing the literal name to a [BaseToolDefinition] helper such as `optionalString(params,
 * "x")`). Handlers in the same package receive already-extracted values and read PER-ITEM fields
 * — scanning them would let a per-item `"type"` read mask an unread top-level `type`, which is
 * exactly the residual this guard was written to catch.
 *
 * ## Known limits
 *   - Presence, not reachability: a literal on a dead branch, or used for a different purpose
 *     (e.g. a nested-object field with the same name read in the tool file, a `mapOf("x" to ...)`
 *     response key), counts as a read. The guard catches the "never wired at all" failure mode,
 *     not a param wired on one operation but not another.
 *   - A tool that reads top-level params by a computed name, or hands the whole params object to
 *     a helper in another file that reads a literal of its own, would false-fail; teach
 *     [implicitReads] about that helper rather than qualifying the param as ignored.
 *   - The `Ignored` qualifier is trusted as written; it is not cross-checked against the code.
 */
class ToolParameterReadCoverageTest {
    // Derived from buildMcpTools() (interfaces/mcp/CurrentMcpServer.kt) rather than a hard-coded
    // list, so this test automatically covers every tool the production server registers.
    private val allTools: List<ToolDefinition> = buildMcpTools()

    /** Helper-call pattern -> parameter it reads implicitly (via a default argument). */
    private val implicitReads: Map<Regex, String> =
        mapOf(
            Regex("""\bvalidateRequestIdParam\(\s*\w+\s*\)""") to "requestId",
        )

    private val ignoredQualifier = Regex("""\bIgnored\b""")

    @Test
    fun `every declared parameter is read or explicitly qualified as ignored`() {
        val failures = mutableListOf<String>()

        for (tool in allTools) {
            val schemaProps = tool.parameterSchema.properties ?: continue
            val source = stripNonCode(readToolSource(tool))

            for (paramName in schemaProps.keys) {
                val desc =
                    (schemaProps[paramName] as? JsonObject)
                        ?.get("description")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                if (ignoredQualifier.containsMatchIn(desc)) continue
                if (isRead(paramName, source)) continue
                failures.add(
                    "${tool.name}: parameter '$paramName' is declared in parameterSchema but never read in " +
                        "${tool::class.simpleName}.kt"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Declared-but-unread tool parameters (accepted, silently dropped, reported as success). " +
                    "Wire each one, or — if it is intentionally unused at the top level — say so with an " +
                    "'Ignored ...' qualifier in its own parameterSchema description (see the " +
                    "requiresVerification precedent on manage_items):\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    private fun isRead(
        paramName: String,
        source: String
    ): Boolean {
        if (implicitReads.any { (pattern, param) -> param == paramName && pattern.containsMatchIn(source) }) {
            return true
        }
        val literal = Regex("\"" + Regex.escape(paramName) + "\"")
        val responseKey = Regex("""\bput(?:JsonObject|JsonArray)?\(\s*$""")
        return literal.findAll(source).any { match ->
            !responseKey.containsMatchIn(source.substring(maxOf(0, match.range.first - 40), match.range.first))
        }
    }

    // ---- source location & stripping ------------------------------------------------------------

    private fun readToolSource(tool: ToolDefinition): String {
        val cls = tool::class.java
        val relative = cls.packageName.replace('.', '/') + "/" + cls.simpleName + ".kt"
        val path = repoRoot().resolve("current/src/main/kotlin").resolve(relative)
        if (!Files.isRegularFile(path)) fail("source for ${tool.name} not found at $path")
        return Files.readString(path)
    }

    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        fail("could not locate repo root (looking for a dir containing both 'claude-plugins/' and 'current/')")
    }

    private fun stripNonCode(text: String): String {
        var s = text.replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
        s = s.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        s = s.replace(Regex("""(?m)^\s*//.*$"""), "")
        s = s.replace(Regex("""\s//\s.*"""), "")
        return removeParameterSchemaInitializer(s)
    }

    private fun removeParameterSchemaInitializer(s: String): String {
        val decl = s.indexOf("override val parameterSchema")
        if (decl < 0) return s
        val open = s.indexOf('(', decl)
        if (open < 0) return s
        var depth = 0
        var i = open
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') {
                    i++
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return s.substring(0, decl) + s.substring(i + 1)
                    }
                }
            }
            i++
        }
        fail("unbalanced parentheses in parameterSchema initializer")
    }
}

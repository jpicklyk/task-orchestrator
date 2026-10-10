package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source guard for item `0c07190d` (error catalog adoption), scenario S13 of the frozen `test-plan`: error bodies are
 * built in exactly two places, one per surface.
 *
 * - REST: `ErrorDto(` is constructed only by the REST mapper (`LegacyRestErrorMapper.kt`) and declared in `Dtos.kt`.
 *   The OAuth `error_description` bodies (AuthenticationPlugin, AuthorizationPlugin, EventRoutes) are a separate
 *   RFC 6750 contract that does not use `ErrorDto`, so they need no exemption and none is granted.
 * - MCP: a `ToolError` is constructed (`ToolError(...)` or the `permanent` / `transient` / `shedding` factories) only by
 *   the MCP mapper (`LegacyMcpErrorMapper.kt`) and declared in `ToolError.kt`.
 * - The String-code overloads of `ResponseUtil.createErrorResponse` and `BaseToolDefinition.errorResponse` are gone.
 *
 * NEW-SURFACE (the mapper files are introduced by the item) with a substitute red-proof named in the plan: in a
 * scratch copy, add a stray `ErrorDto("x", "y")` to a route and a stray `ToolError.permanent("c", "m")` to a tool; both
 * tests then fail naming the file and line. The matcher itself is also pinned below on synthetic text.
 *
 * Oracle: [T] task-scope Build step 8 and AC3/AC5 (MCP and REST each serialize through one mapper; the guard scans
 * `current/src/main`, comments and string literals excluded).
 */
class LegacyErrorMapperGuardTest {
    companion object {
        private const val REST_MAPPER = "interfaces/api/v1/error/LegacyRestErrorMapper.kt"
        private const val DTOS = "interfaces/api/v1/dto/Dtos.kt"
        private const val MCP_MAPPER = "application/tools/LegacyMcpErrorMapper.kt"
        private const val TOOL_ERROR = "domain/model/ToolError.kt"

        val ERROR_DTO_CONSTRUCTION = Regex("""\bErrorDto\s*\(""")
        val TOOL_ERROR_CONSTRUCTION = Regex("""\bToolError\s*(\(|\.(permanent|transient|shedding)\s*\()""")
        val STRING_CREATE_ERROR_RESPONSE = Regex("""fun\s+createErrorResponse\s*\(\s*message\s*:\s*String""")
        val STRING_ERROR_RESPONSE = Regex("""fun\s+errorResponse\s*\([^)]*\bcode\s*:\s*String""")

        /** `path:line` of every code line of [source] (comments and string-literal contents excluded) matching [pattern]. */
        internal fun hits(
            source: GuardSupport.Source,
            pattern: Regex
        ): List<String> =
            source.text
                .lines()
                .withIndex()
                .filter { (_, line) -> GuardSupport.isCodeLine(line) && pattern.containsMatchIn(GuardSupport.stripStrings(line)) }
                .map { (i, _) -> "${source.path}:${i + 1}" }
    }

    private val sources by lazy { GuardSupport.productionSources() }

    private fun source(path: String): GuardSupport.Source =
        sources.firstOrNull { it.path == path } ?: error("vacuity control: $path is not in the production scan (${sources.size} files)")

    @Test
    fun `S13 ErrorDto is constructed only by the REST mapper and declared in Dtos`() {
        val offenders =
            sources
                .filter { it.path != REST_MAPPER && it.path != DTOS }
                .flatMap { hits(it, ERROR_DTO_CONSTRUCTION) }

        assertEquals(emptyList(), offenders, "ErrorDto( must be built through LegacyRestErrorMapper only")
    }

    @Test
    fun `S13 the REST mapper really constructs ErrorDto, so the scan above is not vacuous`() {
        assertTrue(hits(source(REST_MAPPER), ERROR_DTO_CONSTRUCTION).isNotEmpty(), "LegacyRestErrorMapper must build the ErrorDto bodies")
        assertTrue(hits(source(DTOS), ERROR_DTO_CONSTRUCTION).isNotEmpty(), "Dtos.kt declares ErrorDto, which matches the same pattern")
    }

    @Test
    fun `S13 ToolError is constructed only by the MCP mapper and declared in ToolError`() {
        val offenders =
            sources
                .filter { it.path != MCP_MAPPER && it.path != TOOL_ERROR }
                .flatMap { hits(it, TOOL_ERROR_CONSTRUCTION) }

        assertEquals(emptyList(), offenders, "ToolError must be built through LegacyMcpErrorMapper only")
    }

    @Test
    fun `S13 the MCP mapper really constructs ToolError, so the scan above is not vacuous`() {
        assertTrue(hits(source(MCP_MAPPER), TOOL_ERROR_CONSTRUCTION).isNotEmpty(), "LegacyMcpErrorMapper must build the ToolError values")
    }

    @Test
    fun `S13 the String-code error response overloads are gone`() {
        val offenders =
            sources.flatMap { src ->
                val flat = src.text
                buildList {
                    if (STRING_CREATE_ERROR_RESPONSE.containsMatchIn(flat)) add("${src.path}: createErrorResponse(message: String, ...)")
                    if (STRING_ERROR_RESPONSE.containsMatchIn(flat)) add("${src.path}: errorResponse(..., code: String, ...)")
                }
            }

        assertEquals(emptyList(), offenders, "only the ToolError / LegacyMcpCode overloads may remain")
    }

    // ----------------------------------------------
    // the matchers themselves, on synthetic text
    // ----------------------------------------------

    private fun synthetic(text: String) = GuardSupport.Source("synthetic/Sample.kt", text)

    @Test
    fun `S13 matcher flags a stray ErrorDto construction`() {
        assertEquals(1, hits(synthetic("""respond(ErrorDto("x", "y"))"""), ERROR_DTO_CONSTRUCTION).size)
        assertEquals(1, hits(synthetic("""val d = ErrorDto ( error = "x", message = "y" )"""), ERROR_DTO_CONSTRUCTION).size)
    }

    @Test
    fun `S13 matcher ignores comments, string literals, longer identifiers and non-constructions`() {
        val text =
            listOf(
                "// ErrorDto(\"x\", \"y\") in a comment",
                "/** builds an ErrorDto(x) */",
                " * ErrorDto(x) in a KDoc body",
                "val s = \"ErrorDto(\"",
                "fun respondErrorDto(code: String) = Unit",
                "val t: ErrorDto? = null"
            ).joinToString("\n")

        assertEquals(emptyList(), hits(synthetic(text), ERROR_DTO_CONSTRUCTION))
    }

    @Test
    fun `S13 matcher flags every ToolError construction form`() {
        assertEquals(1, hits(synthetic("""return ToolError(kind, "c", "m")"""), TOOL_ERROR_CONSTRUCTION).size)
        assertEquals(1, hits(synthetic("""return ToolError.permanent("c", "m")"""), TOOL_ERROR_CONSTRUCTION).size)
        assertEquals(1, hits(synthetic("""return ToolError.transient("c", "m")"""), TOOL_ERROR_CONSTRUCTION).size)
        assertEquals(1, hits(synthetic("""return ToolError.shedding("c", "m", 5L)"""), TOOL_ERROR_CONSTRUCTION).size)
    }

    @Test
    fun `S13 matcher ignores a ToolError type reference`() {
        val text = "fun envelope(toolError: ToolError, extra: Int): JsonObject = x\nval e: ToolError? = null\nimport a.b.ToolError"

        assertEquals(emptyList(), hits(synthetic(text), TOOL_ERROR_CONSTRUCTION))
    }

    @Test
    fun `S13 matcher flags a String-code overload declaration, including a multi-line one`() {
        assertTrue(
            STRING_CREATE_ERROR_RESPONSE.containsMatchIn("fun createErrorResponse(\n        message: String,\n        code: String\n    )")
        )
        assertTrue(
            STRING_ERROR_RESPONSE.containsMatchIn(
                "protected fun errorResponse(\n    message: String,\n    code: String = ErrorCodes.VALIDATION_ERROR,\n)"
            )
        )
        assertTrue(
            !STRING_ERROR_RESPONSE.containsMatchIn(
                "protected fun errorResponse(\n    message: String,\n    code: LegacyMcpCode = LegacyMcpCode.VALIDATION_ERROR,\n)"
            )
        )
        assertTrue(!STRING_CREATE_ERROR_RESPONSE.containsMatchIn("fun createErrorResponse(\n    toolError: ToolError,\n)"))
    }
}

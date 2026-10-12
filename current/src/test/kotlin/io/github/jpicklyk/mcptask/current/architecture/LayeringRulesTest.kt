package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-tests of [LayeringRules] on synthetic files (task-scope F, scenarios S1-S5): each rule fires on a forbidden edge and
 * stays silent on an allowed one, so a green [LayeringTest] means "no violation", not "a rule that never fires".
 */
class LayeringRulesTest {
    private val root = LayeringRules.CURRENT_ROOT

    private fun imports(
        path: String,
        vararg names: String
    ) = LayeringRules.violations(path, names.toList(), "").map { it.importedFqName }

    private fun code(
        path: String,
        text: String
    ) = LayeringRules.violations(path, emptyList(), text).map { it.importedFqName }

    // ------------------------------------------------------------------ S1 R4 by import

    @Test
    fun `S1 R4 flags an application or domain import of the MCP SDK or Ktor`() {
        assertEquals(
            listOf("io.modelcontextprotocol.kotlin.sdk.types.Tool"),
            imports("application/api/X.kt", "io.modelcontextprotocol.kotlin.sdk.types.Tool")
        )
        assertEquals(
            listOf("io.ktor.server.application.ApplicationCall"),
            imports("application/service/X.kt", "io.ktor.server.application.ApplicationCall")
        )
        assertEquals(listOf("io.ktor.http.HttpStatusCode"), imports("domain/model/X.kt", "io.ktor.http.HttpStatusCode"))
    }

    @Test
    fun `S1 R4 does not flag infrastructure or interfaces importing Ktor or the MCP SDK`() {
        assertEquals(emptyList(), imports("infrastructure/config/JwksKeySetProvider.kt", "io.ktor.client.HttpClient"))
        assertEquals(emptyList(), imports("interfaces/mcp/McpOperationAdapter.kt", "io.modelcontextprotocol.kotlin.sdk.server.Server"))
        assertEquals(emptyList(), imports("application/api/X.kt", "io.ktorx.Thing", "kotlinx.serialization.json.JsonObject"))
    }

    // ------------------------------------------------------------------ S2 R4 by FQN

    @Test
    fun `S2 R4 flags a fully-qualified Ktor reference on a code line but not on a KDoc or comment line`() {
        val text =
            """
            /**
             * See [io.ktor.server.application.ApplicationCall] for the call shape.
             */
            // io.ktor.http.HttpStatusCode is the status type
            val status = io.ktor.http.HttpStatusCode.OK
            """.trimIndent()
        assertEquals(listOf("io.ktor.http.HttpStatusCode.OK"), code("application/service/X.kt", text))
    }

    // ------------------------------------------------------------------ S3 R5

    @Test
    fun `S3 R5 flags every layer importing bootstrap but not the root package or bootstrap itself`() {
        val composition = "$root.bootstrap.ServerComposition"
        for (layer in listOf("domain/model", "application/api", "infrastructure/config", "interfaces/api/v1/routes")) {
            assertEquals(listOf(composition), imports("$layer/X.kt", composition), layer)
        }
        assertEquals(emptyList(), imports("CurrentMain.kt", composition))
        assertEquals(emptyList(), imports("bootstrap/CurrentMcpServer.kt", composition))
    }

    // ------------------------------------------------------------------ S4 R1-R3

    @Test
    fun `S4 R1 to R3 flag inward-to-outward edges and allow the outward-to-inward ones`() {
        assertEquals(listOf("$root.application.api.X"), imports("domain/model/A.kt", "$root.application.api.X"))
        assertEquals(listOf("$root.infrastructure.sqlite.X"), imports("domain/model/A.kt", "$root.infrastructure.sqlite.X"))
        assertEquals(listOf("$root.interfaces.mcp.X"), imports("domain/model/A.kt", "$root.interfaces.mcp.X"))
        assertEquals(listOf("$root.infrastructure.sqlite.X"), imports("application/service/A.kt", "$root.infrastructure.sqlite.X"))
        assertEquals(listOf("$root.interfaces.api.v1.X"), imports("application/service/A.kt", "$root.interfaces.api.v1.X"))
        assertEquals(listOf("$root.interfaces.api.v1.X"), imports("infrastructure/config/A.kt", "$root.interfaces.api.v1.X"))

        assertEquals(
            emptyList(),
            imports("infrastructure/config/A.kt", "$root.application.port.X"),
            "infrastructure -> application is allowed"
        )
        assertEquals(emptyList(), imports("application/service/A.kt", "$root.domain.model.X"))
        assertEquals(
            emptyList(),
            imports("interfaces/api/v1/A.kt", "$root.infrastructure.config.X", "$root.application.api.X", "$root.domain.model.X")
        )
        assertEquals(emptyList(), imports("domain/model/A.kt", "$root.domain.error.X"))
        assertEquals(emptyList(), imports("domain/model/A.kt", "$root.applicationx.X"), "a prefix match needs a package boundary")
    }

    // ------------------------------------------------------------------ S5 baseline diff

    @Test
    fun `S5 the baseline diff reports unbaselined and stale lines and ignores comments and blanks`() {
        val baseline = LayeringRules.parseBaseline(listOf("# header", "", "  a.kt -> x.Y  ", "b.kt -> x.Z"))
        assertEquals(setOf("a.kt -> x.Y", "b.kt -> x.Z"), baseline)

        val diff = LayeringRules.diff(setOf("a.kt -> x.Y", "c.kt -> x.W"), baseline)
        assertEquals(setOf("c.kt -> x.W"), diff.unbaselined)
        assertEquals(setOf("b.kt -> x.Z"), diff.stale)

        assertTrue(LayeringRules.diff(baseline, baseline).clean)
        assertEquals(
            "infrastructure/config/A.kt -> x.Y",
            LayeringRules.Violation("infrastructure\\config\\A.kt", "x.Y").key(),
            "the key normalizes Windows separators"
        )
    }
}

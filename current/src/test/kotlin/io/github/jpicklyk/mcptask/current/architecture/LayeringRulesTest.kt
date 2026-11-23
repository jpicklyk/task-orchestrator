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
        assertEquals(listOf("io.ktor.http.HttpStatusCode"), imports("model/item/X.kt", "io.ktor.http.HttpStatusCode"))
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
        for (layer in listOf("model/item", "application/api", "infrastructure/config", "interfaces/api/v1/routes")) {
            assertEquals(listOf(composition), imports("$layer/X.kt", composition), layer)
        }
        assertEquals(emptyList(), imports("CurrentMain.kt", composition))
        assertEquals(emptyList(), imports("bootstrap/CurrentMcpServer.kt", composition))
    }

    // ------------------------------------------------------------------ S4 R1-R3

    @Test
    fun `S4 R1 to R3 flag inward-to-outward edges and allow the outward-to-inward ones`() {
        assertEquals(listOf("$root.application.api.X"), imports("model/item/A.kt", "$root.application.api.X"))
        assertEquals(listOf("$root.infrastructure.sqlite.X"), imports("model/item/A.kt", "$root.infrastructure.sqlite.X"))
        assertEquals(listOf("$root.interfaces.mcp.X"), imports("model/item/A.kt", "$root.interfaces.mcp.X"))
        assertEquals(listOf("$root.application.api.X"), imports("domain/lifecycle/A.kt", "$root.application.api.X"))
        assertEquals(listOf("$root.infrastructure.sqlite.X"), imports("domain/lifecycle/A.kt", "$root.infrastructure.sqlite.X"))
        assertEquals(listOf("$root.infrastructure.sqlite.X"), imports("application/service/A.kt", "$root.infrastructure.sqlite.X"))
        assertEquals(listOf("$root.interfaces.api.v1.X"), imports("application/service/A.kt", "$root.interfaces.api.v1.X"))
        assertEquals(listOf("$root.interfaces.api.v1.X"), imports("infrastructure/config/A.kt", "$root.interfaces.api.v1.X"))

        assertEquals(
            emptyList(),
            imports("infrastructure/config/A.kt", "$root.application.port.X"),
            "infrastructure -> application is allowed"
        )
        assertEquals(emptyList(), imports("application/service/A.kt", "$root.model.item.X"))
        assertEquals(
            emptyList(),
            imports("interfaces/api/v1/A.kt", "$root.infrastructure.config.X", "$root.application.api.X", "$root.model.item.X")
        )
        assertEquals(emptyList(), imports("model/item/A.kt", "$root.model.error.X"))
        assertEquals(emptyList(), imports("model/item/A.kt", "$root.applicationx.X"), "a prefix match needs a package boundary")
    }

    // ------------------------------------------------------------------ S4b interim platform folders

    @Test
    fun `S4b a platform folder counts as the layer its table row names`() {
        assertEquals(
            listOf("$root.interfaces.mcp.X"),
            imports("platform/sqlite/schema/A.kt", "$root.interfaces.mcp.X"),
            "a platform folder that counts as infrastructure may not reach interfaces"
        )
        assertEquals(listOf("$root.bootstrap.X"), imports("platform/sqlite/A.kt", "$root.bootstrap.X"))
        assertEquals(
            emptyList(),
            imports("platform/sqlite/A.kt", "$root.application.port.X", "$root.model.item.X"),
            "infrastructure -> application and model are allowed"
        )
    }

    @Test
    fun `S4b domain and model forbid all of platform`() {
        assertEquals(listOf("$root.platform.sqlite.X"), imports("model/item/A.kt", "$root.platform.sqlite.X"))
        assertEquals(listOf("$root.platform.other.X"), imports("domain/lifecycle/A.kt", "$root.platform.other.X"))
        assertEquals(emptyList(), imports("model/item/A.kt", "$root.platformx.X"), "a prefix match needs a package boundary")
    }

    @Test
    fun `S4b application forbids a platform folder that counts as infrastructure`() {
        assertEquals(listOf("$root.platform.sqlite.X"), imports("application/service/A.kt", "$root.platform.sqlite.X"))
        assertEquals(
            emptyList(),
            imports("application/service/A.kt", "$root.platform.sqlitex.X"),
            "a prefix match needs a package boundary"
        )
    }

    @Test
    fun `S4b infrastructure forbids a platform folder that counts as interfaces but not one that counts as infrastructure`() {
        assertEquals(emptyList(), imports("infrastructure/config/A.kt", "$root.platform.sqlite.X"))
        assertTrue(
            LayeringRules.forbiddenPrefixesFor("infrastructure/config/A.kt").none { it.startsWith("$root.platform") },
            "the shipped table has no interfaces row, so infrastructure forbids no platform folder"
        )
        val table = mapOf("sqlite" to "infrastructure", "web" to "interfaces")
        val forbidden = LayeringRules.forbiddenPrefixesFor("infrastructure/config/A.kt", table)
        assertTrue("$root.platform.web" in forbidden, "a folder that counts as interfaces is forbidden to infrastructure")
        assertTrue("$root.platform.sqlite" !in forbidden, "a folder that counts as infrastructure is allowed to infrastructure")
        assertTrue(
            "$root.platform.web" in LayeringRules.forbiddenPrefixesFor("application/service/A.kt", table),
            "application forbids it too"
        )
    }

    @Test
    fun `S4b a file under platform whose folder has no table row is itself a violation`() {
        assertEquals(listOf("$root.platform.unknown has no layer row"), imports("platform/unknown/A.kt"))
        assertEquals(listOf("$root.platform.<root> has no layer row"), imports("platform/A.kt"))
        assertEquals(emptyList(), imports("platform/sqlite/A.kt"), "a mapped folder is not itself a violation")
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

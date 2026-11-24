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

    /** The slices of the synthetic tree; a look-alike such as `applicationx` is deliberately not one. */
    private val slices = setOf("telemetry")

    private fun imports(
        path: String,
        vararg names: String
    ) = LayeringRules.violations(path, names.toList(), "", slices).map { it.importedFqName }

    private fun code(
        path: String,
        text: String
    ) = LayeringRules.violations(path, emptyList(), text, slices).map { it.importedFqName }

    // ------------------------------------------------------------------ package line

    @Test
    fun `a slice file's own package line is not read as a reference`() {
        assertEquals(
            emptyList(),
            code("telemetry/internal/domain/Rule.kt", "package $root.telemetry.internal.domain\n\nclass Rule"),
            "a domain file's package line names its own package, not slice-top",
        )
    }

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
        val shipped = LayeringRules.forbiddenPrefixesFor("infrastructure/config/A.kt")
        assertEquals(
            setOf("$root.platform.tool", "$root.platform.envelope", "$root.platform.rest"),
            shipped.filter { it.startsWith("$root.platform") }.toSet(),
            "the shipped table's interfaces rows are exactly the platform folders infrastructure forbids"
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
    fun `S4b platform request counts as application`() {
        assertEquals(emptyList(), imports("platform/request/A.kt", "$root.model.error.X", "$root.platform.port.X"))
        assertEquals(
            listOf("$root.infrastructure.sqlite.X", "$root.interfaces.mcp.X", "$root.bootstrap.X", "io.ktor.server.X"),
            imports(
                "platform/request/A.kt",
                "$root.infrastructure.sqlite.X",
                "$root.interfaces.mcp.X",
                "$root.bootstrap.X",
                "io.ktor.server.X"
            ),
            "application rules R1, R2, R4, R5 apply to platform/request"
        )
        assertEquals(emptyList(), imports("application/api/notes/A.kt", "$root.platform.request.Doc"))
        assertEquals(emptyList(), imports("infrastructure/config/A.kt", "$root.platform.request.Doc"))
        assertEquals(listOf("$root.platform.request.Doc"), imports("model/item/A.kt", "$root.platform.request.Doc"))
    }

    @Test
    fun `S4b platform tool envelope and rest count as interfaces`() {
        listOf("tool", "envelope", "rest").forEach { folder ->
            val path = "platform/$folder/A.kt"
            assertEquals(emptyList(), imports(path, "$root.application.api.X", "$root.infrastructure.config.X", "io.ktor.server.X"), path)
            assertEquals(listOf("$root.bootstrap.X"), imports(path, "$root.bootstrap.X"), path)
            val target = "$root.platform.$folder.X"
            assertEquals(listOf(target), imports("application/service/A.kt", target), "application -> platform/$folder")
            assertEquals(listOf(target), imports("infrastructure/config/A.kt", target), "infrastructure -> platform/$folder")
            assertEquals(listOf(target), imports("model/item/A.kt", target), "model -> platform/$folder")
            assertEquals(emptyList(), imports("interfaces/mcp/A.kt", target), "interfaces -> platform/$folder")
        }
    }

    @Test
    fun `S4b a file under platform whose folder has no table row is itself a violation`() {
        assertEquals(listOf("$root.platform.unknown has no layer row"), imports("platform/unknown/A.kt"))
        assertEquals(listOf("$root.platform.<root> has no layer row"), imports("platform/A.kt"))
        assertEquals(emptyList(), imports("platform/sqlite/A.kt"), "a mapped folder is not itself a violation")
    }

    // ------------------------------------------------------------------ S4c slices

    @Test
    fun `S4c a slice file takes slice-top or the layer of its internal folder`() {
        assertEquals(LayeringRules.SLICE_TOP, LayeringRules.layerOf("telemetry/A.kt"))
        assertEquals("domain", LayeringRules.layerOf("telemetry/internal/domain/A.kt"))
        assertEquals("application", LayeringRules.layerOf("telemetry/internal/app/A.kt"))
        assertEquals("application", LayeringRules.layerOf("telemetry/internal/app/sub/A.kt"), "an old sub-package stays a sub-folder")
        assertEquals("infrastructure", LayeringRules.layerOf("telemetry/internal/sqlite/A.kt"))
        assertEquals("interfaces", LayeringRules.layerOf("telemetry/internal/wire/A.kt"))
    }

    @Test
    fun `S4c a path under a slice that is neither top nor an internal layer folder is a shape violation`() {
        val shape = "has no valid slice shape (<s>/F.kt or <s>/internal/{domain,app,sqlite,wire}/..)"
        listOf(
            "telemetry/internal/A.kt",
            "telemetry/internal/other/A.kt",
            "telemetry/internal/sqlitex/A.kt",
            "telemetry/sub/A.kt",
            "telemetry/app/A.kt",
            "telemetry/sqlite/A.kt"
        ).forEach { path ->
            assertEquals(listOf("$root.telemetry $shape"), imports(path), path)
            assertEquals(null, LayeringRules.layerOf(path), path)
        }
        assertEquals(emptyList(), imports("telemetry/A.kt"))
        assertEquals(emptyList(), imports("telemetry/internal/wire/A.kt"))
    }

    @Test
    fun `S4c server and the legacy layers are not slices`() {
        assertEquals(null, GuardSupport.sliceOf("server/A.kt"))
        assertEquals(null, GuardSupport.sliceOf("CurrentMain.kt"))
        assertEquals("server", LayeringRules.layerOf("server/A.kt"))
        assertEquals(emptyList(), imports("server/A.kt", "$root.telemetry.internal.sqlite.X", "io.ktor.server.X"))
        listOf("domain", "application", "infrastructure", "interfaces", "bootstrap", "model", "platform", "server").forEach {
            assertEquals(null, GuardSupport.sliceOf("$it/x/A.kt"), it)
        }
        assertEquals("telemetry", GuardSupport.sliceOf("telemetry/A.kt"))
    }

    @Test
    fun `S4c model and domain forbid slice-top and every slice layer but not a slice domain`() {
        val top = "$root.telemetry.TelemetryStoreProvider"
        val app = "$root.telemetry.internal.app.CallLogStore"
        val sqlite = "$root.telemetry.internal.sqlite.SqliteCallLogStore"
        val wire = "$root.telemetry.internal.wire.CallLogWriter"
        val domain = "$root.telemetry.internal.domain.Rule"
        listOf("model/item/A.kt", "domain/lifecycle/A.kt").forEach { path ->
            assertEquals(listOf(top, app, sqlite, wire), imports(path, top, app, sqlite, wire, domain), path)
        }
    }

    @Test
    fun `S4c application forbids a slice infrastructure and interfaces but not slice-top or application`() {
        val top = "$root.telemetry.TelemetryStoreProvider"
        val app = "$root.telemetry.internal.app.CallLogStore"
        val sqlite = "$root.telemetry.internal.sqlite.SqliteCallLogStore"
        val wire = "$root.telemetry.internal.wire.CallLogWriter"
        assertEquals(listOf(sqlite, wire), imports("application/port/A.kt", top, app, sqlite, wire))
        assertEquals(
            listOf(sqlite, wire),
            imports("platform/calltelemetry/A.kt", top, app, sqlite, wire),
            "a platform folder counting as application"
        )
        assertEquals(listOf(sqlite, wire), imports("telemetry/internal/app/A.kt", top, app, sqlite, wire), "a slice application folder")
    }

    @Test
    fun `S4c infrastructure forbids a slice interfaces only`() {
        val top = "$root.telemetry.TelemetryStoreProvider"
        val app = "$root.telemetry.internal.app.CallLogStore"
        val sqlite = "$root.telemetry.internal.sqlite.SqliteCallLogStore"
        val wire = "$root.telemetry.internal.wire.CallLogWriter"
        assertEquals(listOf(wire), imports("infrastructure/sqlite/repository/A.kt", top, app, sqlite, wire))
        assertEquals(listOf(wire), imports("telemetry/internal/sqlite/A.kt", top, app, sqlite, wire), "a slice infrastructure folder")
        assertEquals(emptyList(), imports("interfaces/mcp/A.kt", top, app, sqlite, wire), "interfaces reach every slice layer")
        assertEquals(emptyList(), imports("telemetry/internal/wire/A.kt", top, app, sqlite, wire))
    }

    @Test
    fun `S4c slice-top reaches its own internal sqlite and forbids only bootstrap and the transports`() {
        assertEquals(
            emptyList(),
            imports("telemetry/A.kt", "$root.telemetry.internal.sqlite.SqliteCallLogStore"),
            "top -> own internal.sqlite"
        )
        assertEquals(
            emptyList(),
            imports(
                "telemetry/A.kt",
                "$root.application.port.X",
                "$root.infrastructure.config.X",
                "$root.platform.port.X",
                "$root.model.item.X"
            )
        )
        assertEquals(
            listOf("$root.bootstrap.X", "io.ktor.server.X", "io.modelcontextprotocol.kotlin.sdk.server.Server"),
            imports("telemetry/A.kt", "$root.bootstrap.X", "io.ktor.server.X", "io.modelcontextprotocol.kotlin.sdk.server.Server")
        )
    }

    @Test
    fun `S4c a slice internal layer follows the rules of the layer it counts as`() {
        assertEquals(listOf("io.ktor.server.X"), imports("telemetry/internal/app/A.kt", "io.ktor.server.X"), "application: no transports")
        assertEquals(listOf("io.ktor.server.X"), imports("telemetry/internal/domain/A.kt", "io.ktor.server.X"))
        assertEquals(
            listOf("$root.platform.port.X"),
            imports("telemetry/internal/domain/A.kt", "$root.platform.port.X"),
            "domain forbids platform"
        )
        assertEquals(emptyList(), imports("telemetry/internal/app/A.kt", "$root.platform.port.X", "$root.model.item.X"))
        assertEquals(emptyList(), imports("telemetry/internal/sqlite/A.kt", "io.ktor.client.HttpClient", "$root.platform.sqlite.X"))
        assertEquals(emptyList(), imports("telemetry/internal/wire/A.kt", "io.ktor.server.X", "$root.application.port.X"))
        assertEquals(listOf("$root.bootstrap.X"), imports("telemetry/internal/wire/A.kt", "$root.bootstrap.X"))
        assertEquals(listOf("$root.bootstrap.X"), imports("telemetry/internal/sqlite/A.kt", "$root.bootstrap.X"))
    }

    @Test
    fun `S4c a fully-qualified slice reference on a code line counts and a KDoc line does not`() {
        val text =
            """
            /** See [$root.telemetry.internal.sqlite.SqliteCallLogStore]. */
            val store = $root.telemetry.internal.sqlite.SqliteCallLogStore(db)
            val top = $root.telemetry.TelemetryStoreProvider::class
            """.trimIndent()
        assertEquals(listOf("$root.telemetry.internal.sqlite.SqliteCallLogStore"), code("application/service/X.kt", text))
        assertEquals(emptyList(), code("infrastructure/config/X.kt", text))
    }

    @Test
    fun `S4c a slice domain file forbids its own slice top and application but reaches its own domain and the model`() {
        val top = "$root.telemetry.TelemetryStoreProvider"
        val app = "$root.telemetry.internal.app.CallLogStore"
        val own = "$root.telemetry.internal.domain.Rule"
        assertEquals(listOf(top, app), imports("telemetry/internal/domain/A.kt", top, app, own, "$root.model.item.X"))
    }

    @Test
    fun `S4c a fully-qualified slice reference in a slice file counts on a code line`() {
        val text =
            """
            val a = $root.telemetry.internal.sqlite.SqliteCallLogStore(db)
            val b = $root.telemetry.internal.wire.CallLogWriter(u)
            val c = $root.telemetry.TelemetryStoreProvider::class
            val d = $root.telemetry.internal.app.CallLogStore::class
            """.trimIndent()
        assertEquals(
            listOf("$root.telemetry.internal.sqlite.SqliteCallLogStore", "$root.telemetry.internal.wire.CallLogWriter"),
            code("telemetry/internal/app/A.kt", text)
        )
        assertEquals(listOf("$root.telemetry.internal.wire.CallLogWriter"), code("telemetry/internal/sqlite/A.kt", text))
        assertEquals(emptyList(), code("telemetry/A.kt", text), "slice-top forbids only bootstrap and the transports")
    }

    @Test
    fun `S4c a wildcard import of a slice package is classified like the package`() {
        assertEquals(
            listOf("$root.telemetry.internal.sqlite.*", "$root.telemetry.internal.wire.*"),
            imports(
                "application/port/A.kt",
                "$root.telemetry.internal.sqlite.*",
                "$root.telemetry.internal.wire.*",
                "$root.telemetry.internal.app.*"
            )
        )
        assertEquals(listOf("$root.telemetry.*"), imports("model/item/A.kt", "$root.telemetry.*"))
        assertEquals(emptyList(), imports("interfaces/mcp/A.kt", "$root.telemetry.internal.sqlite.*"))
    }

    @Test
    fun `S4c a reference that names no slice is not one`() {
        assertEquals(
            emptyList(),
            imports("application/service/A.kt", "$root.CurrentMain", "$root.telemetryx.internal.sqlite.X", "$root.Telemetry.X")
        )
        assertEquals(emptyList(), imports("model/item/A.kt", "$root.CurrentMain", "$root.telemetryx.X", "$root.telemetry"))
        assertEquals(
            listOf("$root.telemetry.X"),
            LayeringRules.violations("model/item/A.kt", listOf("$root.telemetry.X"), "", slices).map { it.importedFqName }
        )
        assertEquals(
            emptyList(),
            LayeringRules.violations("model/item/A.kt", listOf("$root.telemetry.X"), "", emptySet()).map { it.importedFqName },
            "with no slice given, no reference names one"
        )
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

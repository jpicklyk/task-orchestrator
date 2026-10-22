package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.CurrentMcpServer
import io.github.jpicklyk.mcptask.current.bootstrap.ServerComposition
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item b1addc2b -- "Fail closed on unparseable global config".
 * Covers test-plan scenarios S1, S6 and the DEGRADED_MODE_POLICY variable case of S13 -- the
 * composition-root and startup-propagation half of the fix. Loader-level parsing coverage (S2, S5,
 * S7, S8, S10-S12, S15, and the config-parsing probes) is in
 * `io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFailClosedTest`.
 *
 * Oracle source: the item's frozen `diagnosis` note (decisions D1, D3, D5) and `test-plan` note
 * (queue phase) -- never this file's own reading of the fixed source. All scenarios here are
 * EXISTING-SURFACE per the test-plan: [ServerComposition]'s constructor (including its `logger`
 * parameter) and `build()`, [CurrentMcpServer]'s constructor and `run()`
 * were all public before this fix. Only the *behavior* on a broken or absent global config, and
 * the new startup WARN for a real jwks verifier under `accept-cached`, are new -- both reachable
 * via a plain revert of the fix, which therefore yields behavioral red directly.
 */
class GlobalConfigStartupFailClosedTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    /** Builds a SQLite-backed DatabaseManager with schema created (no live env reads). Mirrors
     * `ServerCompositionTest.buildDatabaseManager`. */
    private fun writeGlobalConfig(
        dir: Path,
        content: String
    ): Path {
        val configDir = dir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.writeString(file, content)
        return file
    }

    /** An [AppConfig] snapshot pointing AGENT_CONFIG_DIR at [dir], with the REST API left
     * disabled (default) and any [extraEnv] entries layered in (e.g. DEGRADED_MODE_POLICY). */
    private fun agentConfigDirAppConfig(
        dir: Path,
        extraEnv: Map<String, String> = emptyMap()
    ): AppConfig {
        val env = mapOf("AGENT_CONFIG_DIR" to dir.toString()) + extraEnv
        return AppConfig.fromEnv { key -> env[key] }
    }

    // -------------------------------------------------------------------------
    // S1 -- an empty AGENT_CONFIG_DIR (no .taskorchestrator/config.yaml at all) is a legitimate
    // "nothing configured" state: build() succeeds with coded defaults. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S1 build() over an empty AGENT_CONFIG_DIR succeeds with a null note-schema fingerprint`(
        @TempDir tempDir: Path
    ) {
        val composition =
            ServerComposition(
                appConfig = agentConfigDirAppConfig(tempDir),
                databaseManager = db.databaseManager,
                shutdownCoordinator = ShutdownCoordinator(),
            ).build()

        assertNull(runBlocking { composition.serverConfig.layer() }?.fingerprint)
    }

    // -------------------------------------------------------------------------
    // S6 -- a broken global config makes CurrentMcpServer.run() THROW an IAE instead of returning
    // Failed(UNKNOWN_TRANSPORT); no readiness marker is ever written. D1/D3
    // -------------------------------------------------------------------------

    @Test
    fun `S6 a broken global config makes run() throw IAE instead of Failed(UNKNOWN_TRANSPORT), no readiness marker written`(
        @TempDir tempDir: Path
    ) {
        val configDir = tempDir.resolve("agent-config")
        writeGlobalConfig(
            configDir,
            """
            traits: [
            """.trimIndent()
        )

        val dbPath = tempDir.resolve("s6-${System.nanoTime()}.db").toString()
        val readinessFile = tempDir.resolve("ready")
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                // Deliberately also invalid, to prove composition failure is reached and thrown
                // BEFORE transport dispatch would otherwise turn this into Failed(UNKNOWN_TRANSPORT).
                "MCP_TRANSPORT" to "sse",
                "AGENT_CONFIG_DIR" to configDir.toString(),
                "READINESS_FILE" to readinessFile.toString()
            )
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = AppConfig.fromEnv { key -> env[key] }
            )

        val ex = assertFailsWith<IllegalArgumentException> { server.run() }
        assertTrue(ex.message?.contains("config.yaml") == true, "message must name the config file: ${ex.message}")
        assertFalse(
            Files.exists(readinessFile),
            "readiness marker must not exist when composition fails before transport dispatch"
        )
    }

    // -------------------------------------------------------------------------
    // S13 -- a set DEGRADED_MODE_POLICY variable fails build closed, whatever its value. D5
    // -------------------------------------------------------------------------

    @Test
    fun `S13 a set DEGRADED_MODE_POLICY variable fails build closed whatever its value`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(tempDir, "# no keys")
        for (value in listOf("reject", "accept-cached", "accept-self-reported")) {
            val appConfig = agentConfigDirAppConfig(tempDir, extraEnv = mapOf("DEGRADED_MODE_POLICY" to value))
            val ex =
                assertFailsWith<IllegalArgumentException>("value $value") {
                    ServerComposition(appConfig, db.databaseManager, ShutdownCoordinator()).build()
                }
            assertTrue(ex.message?.contains("DEGRADED_MODE_POLICY") == true, "Expected the variable in: ${ex.message}")
        }
    }
}

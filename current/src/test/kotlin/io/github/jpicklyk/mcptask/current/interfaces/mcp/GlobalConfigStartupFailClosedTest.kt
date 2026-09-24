package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item b1addc2b -- "Fail closed on unparseable global config and
 * invalid actor_authentication values". Covers test-plan scenarios S1, S3, S6, S9, S13 -- the
 * composition-root and startup-propagation half of the fix. Loader-level parsing coverage (S2, S5,
 * S7, S8, S10-S12, S15, and the config-parsing probes) is in
 * `io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFailClosedTest`.
 *
 * Oracle source: the item's frozen `diagnosis` note (decisions D1, D3, D5) and `test-plan` note
 * (queue phase) -- never this file's own reading of the fixed source. All scenarios here are
 * EXISTING-SURFACE per the test-plan: [ServerComposition]'s constructor (including its `logger`
 * parameter) and `build()`, [CurrentMcpServer]'s constructor and `run()`, and [DegradedModePolicy]
 * were all public before this fix. Only the *behavior* on a broken or absent global config, and
 * the new startup WARN for a real jwks verifier under `accept-cached`, are new -- both reachable
 * via a plain revert of the fix, which therefore yields behavioral red directly.
 */
class GlobalConfigStartupFailClosedTest {
    /** Builds an H2-backed DatabaseManager with schema created (no live env reads). Mirrors
     * `ServerCompositionTest.buildDatabaseManager`. */
    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "global_config_startup_test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

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

    private fun jwksConfig(policy: String): String =
        """
        actor_authentication:
          degraded_mode_policy: $policy
          verifier:
            type: jwks
            jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
            algorithms:
              - RS256
        """.trimIndent()

    // -------------------------------------------------------------------------
    // S1 -- an empty AGENT_CONFIG_DIR (no .taskorchestrator/config.yaml at all) is a legitimate
    // "nothing configured" state: build() succeeds with coded defaults. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S1 build() over an empty AGENT_CONFIG_DIR succeeds with ACCEPT_CACHED, a null note-schema fingerprint, and actor auth disabled`(
        @TempDir tempDir: Path
    ) {
        val composition =
            ServerComposition(
                appConfig = agentConfigDirAppConfig(tempDir),
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
            ).build()

        assertEquals(DegradedModePolicy.ACCEPT_CACHED, composition.degradedModePolicy)
        assertNull(composition.noteSchemaService.getConfigFingerprint())
        assertFalse(composition.actorAuthEnabled)
    }

    // -------------------------------------------------------------------------
    // S3 -- a real jwks verifier under (effective) accept-cached logs exactly one startup WARN,
    // for both static-JWKS and DID-trust configurations. D5
    // -------------------------------------------------------------------------

    @Test
    fun `S3 build() logs exactly one accept-cached WARN for a real jwks verifier (static JWKS)`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(
            tempDir,
            """
            actor_authentication:
              verifier:
                type: jwks
                jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                algorithms:
                  - RS256
            """.trimIndent()
        )
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null, logger).build()

        verify(exactly = 1) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }

    @Test
    fun `S3 build() logs exactly one accept-cached WARN for a real jwks verifier (DID-trust)`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(
            tempDir,
            """
            actor_authentication:
              verifier:
                type: jwks
                did_allowlist:
                  - "did:web:example.com"
                algorithms:
                  - EdDSA
            """.trimIndent()
        )
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null, logger).build()

        verify(exactly = 1) { logger.warn(match<String> { it.contains("accept-cached") }) }
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
            actor_authentication:
              degraded_mode_policy: reject
              verifier:
                type: jwks
                jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                algorithms:
                  - RS256
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
                shutdownCoordinator = null,
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
    // S9 -- build() over an invalid degraded_mode_policy value throws IAE. D2/D3
    // -------------------------------------------------------------------------

    @Test
    fun `S9 build() over an invalid degraded_mode_policy value throws IAE`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(
            tempDir,
            """
            actor_authentication:
              degraded_mode_policy: banana
              verifier:
                type: noop
            """.trimIndent()
        )

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null).build()
            }
        assertTrue(ex.message?.contains("banana") == true, "Expected 'banana' in: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S13 -- the startup WARN fires only when the EFFECTIVE (post-env-override) policy is
    // accept-cached AND the verifier is a real jwks. D5
    // -------------------------------------------------------------------------

    @Test
    fun `S13 jwks with YAML reject logs zero accept-cached WARNs`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(tempDir, jwksConfig(policy = "reject"))
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null, logger).build()

        verify(exactly = 0) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }

    @Test
    fun `S13 jwks with YAML accept-self-reported logs zero accept-cached WARNs`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(tempDir, jwksConfig(policy = "accept-self-reported"))
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null, logger).build()

        verify(exactly = 0) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }

    @Test
    fun `S13 noop verifier under accept-cached logs zero accept-cached WARNs`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(
            tempDir,
            """
            actor_authentication:
              verifier:
                type: noop
            """.trimIndent()
        )
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(agentConfigDirAppConfig(tempDir), buildDatabaseManager(), null, logger).build()

        verify(exactly = 0) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }

    @Test
    fun `S13 jwks YAML accept-cached overridden by env reject logs zero accept-cached WARNs`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(tempDir, jwksConfig(policy = "accept-cached"))
        val appConfig = agentConfigDirAppConfig(tempDir, extraEnv = mapOf("DEGRADED_MODE_POLICY" to "reject"))
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(appConfig, buildDatabaseManager(), null, logger).build()

        verify(exactly = 0) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }

    @Test
    fun `S13 jwks YAML reject overridden by env accept-cached logs exactly one accept-cached WARN`(
        @TempDir tempDir: Path
    ) {
        writeGlobalConfig(tempDir, jwksConfig(policy = "reject"))
        val appConfig = agentConfigDirAppConfig(tempDir, extraEnv = mapOf("DEGRADED_MODE_POLICY" to "accept-cached"))
        val logger = mockk<Logger>(relaxed = true)

        ServerComposition(appConfig, buildDatabaseManager(), null, logger).build()

        verify(exactly = 1) { logger.warn(match<String> { it.contains("accept-cached") }) }
    }
}

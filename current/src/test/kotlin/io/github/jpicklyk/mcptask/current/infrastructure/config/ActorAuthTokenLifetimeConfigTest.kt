package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Independent test-author coverage for item 3dcfcbab, Part 1 & 2 (config parsing, actor side):
 * `actor_authentication.verifier.max_token_lifetime_seconds` and `.jti_replay_protection`, parsed
 * by [YamlActorAuthenticationConfigService].
 *
 * Oracle (task-scope, frozen at queue phase): "Parse in YamlActorAuthenticationConfigService
 * .parseVerifier: Int/Long only; String/Double/Boolean -> wrongVerifierFieldType (startup
 * failure); <= 0 -> IllegalArgumentException naming the key. No disable value." Absent ->
 * defaults to 86400L (`VerifierConfig.Jwks` constructor default). `jti_replay_protection` is a
 * strict boolean (optBoolean per task-scope), default false.
 *
 * The exact exception message text for the wrong-*type* branch (String/Double/Boolean) is not
 * independently declared beyond "startup failure" / IllegalArgumentException, so those cases
 * assert only the declared exception type and fail-fast behavior, not message content - recorded
 * in the manifest's arbitration notes.
 */
class ActorAuthTokenLifetimeConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createConfigFile(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    // -------------------------------------------------------------------------
    // Default
    // -------------------------------------------------------------------------

    @Test
    fun `max_token_lifetime_seconds absent defaults to 86400 and jti_replay_protection defaults to false`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals(86400L, verifier.maxTokenLifetimeSeconds)
        assertEquals(false, verifier.jtiReplayProtection)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // Valid values
    // -------------------------------------------------------------------------

    @Test
    fun `max_token_lifetime_seconds as an integer 3600 is accepted`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: 3600
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(3600L, verifier.maxTokenLifetimeSeconds)
    }

    @Test
    fun `jti_replay_protection true is accepted`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    jti_replay_protection: true
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(true, verifier.jtiReplayProtection)
    }

    // -------------------------------------------------------------------------
    // S16 - invalid values fail startup
    // -------------------------------------------------------------------------

    @Test
    fun `max_token_lifetime_seconds as a quoted string throws at startup`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: "3600"
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
    }

    @Test
    fun `max_token_lifetime_seconds as a decimal 1_5 throws at startup`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: 1.5
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
    }

    @Test
    fun `max_token_lifetime_seconds of 0 throws naming the key`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: 0
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("max_token_lifetime_seconds") == true,
            "Expected the error to name the offending key, got: ${ex.message}"
        )
    }

    @Test
    fun `max_token_lifetime_seconds of negative 1 throws naming the key`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: -1
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("max_token_lifetime_seconds") == true,
            "Expected the error to name the offending key, got: ${ex.message}"
        )
    }

    @Test
    fun `jti_replay_protection as a non-boolean string throws at startup`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    jti_replay_protection: "yes"
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
    }

    // -------------------------------------------------------------------------
    // Overflow ceiling (ADDENDUM, fix cycle 4fe7bd3d): MAX_TOKEN_LIFETIME_SECONDS_CEILING caps
    // max_token_lifetime_seconds from above. Oracle: the ADDENDUM declares
    // MAX_TOKEN_LIFETIME_SECONDS_CEILING = 3_153_600_000L (100 years, in seconds) as the upper
    // bound for this config input, and task-scope states a wrong/invalid value fails startup.
    // -------------------------------------------------------------------------

    @Test
    fun `max_token_lifetime_seconds one above the ceiling throws naming the key`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: ${MAX_TOKEN_LIFETIME_SECONDS_CEILING + 1}
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("max_token_lifetime_seconds") == true,
            "Expected the error to name the offending key, got: ${ex.message}"
        )
    }

    @Test
    fun `max_token_lifetime_seconds exactly at the ceiling is accepted`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - EdDSA
                    max_token_lifetime_seconds: $MAX_TOKEN_LIFETIME_SECONDS_CEILING
                """.trimIndent()
            )
        val service = YamlActorAuthenticationConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(MAX_TOKEN_LIFETIME_SECONDS_CEILING, verifier.maxTokenLifetimeSeconds)
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.AuditingConfig
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class YamlAuditingConfigServiceTest {
    @TempDir
    lateinit var tempDir: Path

    // -------------------------------------------------------------------------
    // Missing / empty config
    // -------------------------------------------------------------------------

    @Test
    fun `missing config file returns AuditingConfig defaults`() {
        val nonExistentPath = tempDir.resolve("does-not-exist/config.yaml")
        val service = YamlAuditingConfigService(nonExistentPath)

        val config = service.getConfig()
        assertEquals(AuditingConfig(), config)
        assertTrue(config.enabled)
        assertEquals(VerifierConfig.Noop, config.verifier)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `config with auditing enabled but no verifier section returns Noop`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val config = service.getConfig()
        assertTrue(config.enabled)
        assertEquals(VerifierConfig.Noop, config.verifier)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `config with no auditing section returns defaults`() {
        val configFile =
            createConfigFile(
                """
                note_schemas:
                  default:
                    - key: test
                      role: queue
                      required: false
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val config = service.getConfig()
        assertEquals(AuditingConfig(), config)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // Noop verifier
    // -------------------------------------------------------------------------

    @Test
    fun `verifier type noop returns Noop`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // JWKS verifier — fully populated
    // -------------------------------------------------------------------------

    @Test
    fun `verifier type jwks with all fields returns fully populated Jwks`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  verifier:
                    type: jwks
                    oidc_discovery: "https://accounts.example.com/.well-known/openid-configuration"
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    jwks_path: "/etc/keys/jwks.json"
                    issuer: "https://accounts.example.com"
                    audience: "mcp-task-orchestrator"
                    algorithms:
                      - RS256
                      - ES256
                    cache_ttl_seconds: 600
                    require_sub_match: false
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals("https://accounts.example.com/.well-known/openid-configuration", verifier.oidcDiscovery)
        assertEquals("https://accounts.example.com/.well-known/jwks.json", verifier.jwksUri)
        assertEquals("/etc/keys/jwks.json", verifier.jwksPath)
        assertEquals("https://accounts.example.com", verifier.issuer)
        assertEquals("mcp-task-orchestrator", verifier.audience)
        assertEquals(listOf("RS256", "ES256"), verifier.algorithms)
        assertEquals(600L, verifier.cacheTtlSeconds)
        assertFalse(verifier.requireSubMatch)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // JWKS verifier — minimal valid configs
    // -------------------------------------------------------------------------

    @Test
    fun `verifier type jwks with only jwks_path is valid`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals("/etc/keys/jwks.json", verifier.jwksPath)
        assertNull(verifier.oidcDiscovery)
        assertNull(verifier.jwksUri)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `verifier type jwks with only oidc_discovery is valid`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    oidc_discovery: "https://accounts.example.com/.well-known/openid-configuration"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals("https://accounts.example.com/.well-known/openid-configuration", verifier.oidcDiscovery)
        assertNull(verifier.jwksUri)
        assertNull(verifier.jwksPath)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `verifier type jwks with only jwks_uri is valid`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals("https://accounts.example.com/.well-known/jwks.json", verifier.jwksUri)
        assertNull(verifier.oidcDiscovery)
        assertNull(verifier.jwksPath)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // JWKS verifier — error / fallback cases
    // -------------------------------------------------------------------------

    @Test
    fun `verifier type jwks with no source URI, path, or discovery produces warning and falls back to Noop`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    issuer: "https://accounts.example.com"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertTrue(service.getWarnings().isNotEmpty())
        assertTrue(service.getWarnings().any { it.contains("jwks") && it.contains("oidc_discovery") })
    }

    @Test
    fun `unknown verifier type produces warning and falls back to Noop`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: magic-unicorn
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertTrue(service.getWarnings().isNotEmpty())
        assertTrue(service.getWarnings().any { it.contains("magic-unicorn") })
    }

    // -------------------------------------------------------------------------
    // Field-level defaults and parsing
    // -------------------------------------------------------------------------

    @Test
    fun `algorithms parsed as list of strings`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                      - ES384
                      - PS256
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(listOf("RS256", "ES384", "PS256"), verifier.algorithms)
    }

    @Test
    fun `require_sub_match defaults to true when absent`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertTrue(verifier.requireSubMatch)
    }

    @Test
    fun `cache_ttl_seconds defaults to 300 when absent`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(300L, verifier.cacheTtlSeconds)
    }

    @Test
    fun `algorithms defaults to empty list when absent`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(emptyList<String>(), verifier.algorithms)
    }

    @Test
    fun `stale_on_error defaults to true when absent`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertTrue(verifier.staleOnError)
    }

    @Test
    fun `stale_on_error false is respected`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    stale_on_error: false
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertFalse(verifier.staleOnError)
    }

    @Test
    fun `stale_on_error true is respected`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    stale_on_error: true
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertTrue(verifier.staleOnError)
    }

    @Test
    fun `auditing enabled flag false is respected`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: false
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)

        assertFalse(service.getConfig().enabled)
    }

    // -------------------------------------------------------------------------
    // degraded_mode_policy parsing
    // -------------------------------------------------------------------------

    @Test
    fun `degraded_mode_policy defaults to ACCEPT_CACHED when absent`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `degraded_mode_policy accept-cached is parsed correctly`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: accept-cached
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `degraded_mode_policy accept-self-reported is parsed correctly`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: accept-self-reported
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.ACCEPT_SELF_REPORTED, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `degraded_mode_policy reject is parsed correctly`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: reject
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `degraded_mode_policy unknown value produces warning and defaults to ACCEPT_CACHED`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: banana
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isNotEmpty())
        assertTrue(service.getWarnings().any { it.contains("banana") })
    }

    @Test
    fun `AuditingConfig default has degraded_mode_policy ACCEPT_CACHED`() {
        val configFile =
            createConfigFile(
                """
                note_schemas:
                  default:
                    - key: test
                      role: queue
                      required: false
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(configFile)
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
    }

    // -------------------------------------------------------------------------
    // DEGRADED_MODE_POLICY environment variable override
    // -------------------------------------------------------------------------

    @Test
    fun `DEGRADED_MODE_POLICY env unset falls through to YAML default`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: reject
                  verifier:
                    type: noop
                """.trimIndent()
            )
        // No env var set — YAML value should be used
        val service = YamlAuditingConfigService(configFile, envResolver = { null })
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var overrides YAML value`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: reject
                  verifier:
                    type: noop
                """.trimIndent()
            )
        // Env var set to accept-self-reported; YAML says reject
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "accept-self-reported" else null }
        )
        assertEquals(DegradedModePolicy.ACCEPT_SELF_REPORTED, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var accept-cached overrides YAML reject`() {
        val configFile =
            createConfigFile(
                """
                auditing:
                  enabled: true
                  degraded_mode_policy: reject
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "accept-cached" else null }
        )
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var is case-insensitive — REJECT uppercase`() {
        val configFile = createConfigFile("auditing:\n  enabled: true")
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "REJECT" else null }
        )
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var is case-insensitive — mixed case Reject`() {
        val configFile = createConfigFile("auditing:\n  enabled: true")
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "Reject" else null }
        )
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var is case-insensitive — lowercase reject`() {
        val configFile = createConfigFile("auditing:\n  enabled: true")
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "reject" else null }
        )
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
    }

    @Test
    fun `DEGRADED_MODE_POLICY env var invalid value throws IllegalArgumentException`() {
        val configFile = createConfigFile("auditing:\n  enabled: true")
        val service = YamlAuditingConfigService(
            configFile,
            envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "banana" else null }
        )
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message!!.contains("accept-cached, accept-self-reported, reject"))
        assertTrue(ex.message!!.contains("banana"))
    }

    @Test
    fun `DEGRADED_MODE_POLICY env unset with no YAML section defaults to ACCEPT_CACHED`() {
        val configFile = createConfigFile("note_schemas:\n  default: []")
        val service = YamlAuditingConfigService(configFile, envResolver = { null })
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
    }

    // -------------------------------------------------------------------------
    // Malformed YAML
    // -------------------------------------------------------------------------

    @Test
    fun `malformed YAML falls back to defaults gracefully`() {
        val configFile =
            createConfigFile("{{{{invalid yaml!!!")
        val service = YamlAuditingConfigService(configFile)

        val config = service.getConfig()
        assertEquals(AuditingConfig(), config)
        assertTrue(service.getWarnings().isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun createConfigFile(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }
}

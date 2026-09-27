package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `test-plan` note on item `df7d579a` (scenarios
 * S1-S4, S9-S10). [GlobalConfigFile] is C1's single lazy, cached read of the global
 * `.taskorchestrator/config.yaml`; these tests check its own `layer()` contract (S1, S9) and
 * that every service built on top of it (`YamlWorkItemSchemaService`, [YamlStatusLabelService],
 * [YamlActorAuthenticationConfigService]) behaves identically whether constructed from a
 * [GlobalConfigFile] or from the legacy `configPath` constructor (S2-S4, S10) — the parity the
 * "single global parse" restructuring must preserve exactly, per #331's fail-closed semantics.
 */
class GlobalConfigFileTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    private fun absentConfigPath(): Path = tempDir.resolve(".taskorchestrator/config.yaml")

    // ──────────────────────────────────────────────
    // S1 — layer() over absent / empty / comment-only files
    // ──────────────────────────────────────────────

    @Test
    fun `S1 layer returns null when the config file is absent`() {
        val gcf = GlobalConfigFile(absentConfigPath())
        assertNull(gcf.layer())
    }

    @Test
    fun `S1 layer over an empty file yields EMPTY document, GLOBAL source, and a fingerprint of the bytes`() {
        val configPath = writeConfig("")
        val gcf = GlobalConfigFile(configPath)

        val layer = gcf.layer()
        assertNotNull(layer)
        assertEquals(ConfigSource.GLOBAL, layer.source)
        assertEquals(ConfigDocument.EMPTY, layer.document)
        assertEquals(configFingerprint(""), layer.fingerprint)
    }

    @Test
    fun `S1 layer over a comment-only file yields EMPTY document, GLOBAL source, and a fingerprint of the bytes`() {
        val content = "# just a comment\n"
        val configPath = writeConfig(content)
        val gcf = GlobalConfigFile(configPath)

        val layer = gcf.layer()
        assertNotNull(layer)
        assertEquals(ConfigSource.GLOBAL, layer.source)
        assertEquals(ConfigDocument.EMPTY, layer.document)
        assertEquals(configFingerprint(content), layer.fingerprint)
    }

    // ──────────────────────────────────────────────
    // S9 — fail-closed: malformed / non-map global config
    // ──────────────────────────────────────────────

    @Test
    fun `S9 layer throws IAE naming the config path for malformed YAML`() {
        val configPath = writeConfig("{{{{invalid yaml!!!")
        val gcf = GlobalConfigFile(configPath)

        val ex = assertFailsWith<IllegalArgumentException> { gcf.layer() }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "Expected the config path in: ${ex.message}")
    }

    @Test
    fun `S9 layer throws IAE naming the config path when the yaml root is a list`() {
        val configPath = writeConfig("- a\n- b\n")
        val gcf = GlobalConfigFile(configPath)

        val ex = assertFailsWith<IllegalArgumentException> { gcf.layer() }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "Expected the config path in: ${ex.message}")
    }

    @Test
    fun `S9 layer throws IAE naming the config path when the yaml root is a scalar`() {
        val configPath = writeConfig("just-a-string")
        val gcf = GlobalConfigFile(configPath)

        val ex = assertFailsWith<IllegalArgumentException> { gcf.layer() }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "Expected the config path in: ${ex.message}")
    }

    @Test
    fun `S9 layer throws IAE naming the config path for a disallowed yaml tag`() {
        val configPath = writeConfig("value: !!javax.script.ScriptEngineManager {}")
        val gcf = GlobalConfigFile(configPath)

        val ex = assertFailsWith<IllegalArgumentException> { gcf.layer() }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "Expected the config path in: ${ex.message}")
    }

    @Test
    fun `S9 every service built on the same GlobalConfigFile also throws IAE naming the config path`() {
        val configPath = writeConfig("{{{{invalid yaml!!!")
        val gcf = GlobalConfigFile(configPath)

        val schemaEx = assertFailsWith<IllegalArgumentException> { YamlWorkItemSchemaService(gcf).getAllSchemas() }
        assertTrue(schemaEx.message?.contains(configPath.toString()) == true)

        val statusEx = assertFailsWith<IllegalArgumentException> { YamlStatusLabelService(gcf).resolveLabel("start") }
        assertTrue(statusEx.message?.contains(configPath.toString()) == true)

        val actorEx = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(gcf).getConfig() }
        assertTrue(actorEx.message?.contains(configPath.toString()) == true)
    }

    // ──────────────────────────────────────────────
    // S2 — YamlWorkItemSchemaService parity: GlobalConfigFile ctor vs legacy path ctor
    // ──────────────────────────────────────────────

    @Test
    fun `S2 YamlWorkItemSchemaService via GlobalConfigFile matches the legacy path constructor on every getter`() {
        val content =
            """
            work_item_schemas:
              feature-task:
                lifecycle: manual
                default_traits:
                  - needs-security-review
                notes:
                  - key: specification
                    role: queue
                    required: true
                    description: "Spec"
                    guidance: "Do it well"
                    skill: "spec-quality"
                    maxLength: 500
            traits:
              needs-security-review:
                notes:
                  - key: security-assessment
                    role: review
                    required: true
                    description: "Security"
            note_limits:
              mode: reject
            """.trimIndent()
        val configPath = writeConfig(content)

        val viaGcf = YamlWorkItemSchemaService(GlobalConfigFile(configPath))
        val viaPath = YamlWorkItemSchemaService(configPath)

        assertEquals(viaPath.getAllSchemas(), viaGcf.getAllSchemas())
        assertEquals(viaPath.getSchemaForType("feature-task"), viaGcf.getSchemaForType("feature-task"))
        assertEquals(viaPath.getSchemaForTags(listOf("feature-task")), viaGcf.getSchemaForTags(listOf("feature-task")))
        assertEquals(viaPath.getTraitNotes("needs-security-review"), viaGcf.getTraitNotes("needs-security-review"))
        assertEquals(viaPath.getAvailableTraits(), viaGcf.getAvailableTraits())
        assertEquals(viaPath.getDefaultTraits("feature-task"), viaGcf.getDefaultTraits("feature-task"))
        assertEquals(viaPath.getAllTraits(), viaGcf.getAllTraits())
        assertEquals(viaPath.getLoadWarnings(), viaGcf.getLoadWarnings())
        assertEquals(viaPath.getResourceRegistry(), viaGcf.getResourceRegistry())
        assertEquals(viaPath.getTraitResources("needs-security-review"), viaGcf.getTraitResources("needs-security-review"))
        assertEquals(viaPath.getTraitDispatch("needs-security-review"), viaGcf.getTraitDispatch("needs-security-review"))
        assertEquals("reject", viaGcf.getNoteLimitsMode())
        assertEquals(viaPath.getNoteLimitsMode(), viaGcf.getNoteLimitsMode())
        assertEquals(viaPath.getConfigFingerprint(), viaGcf.getConfigFingerprint())
    }

    @Test
    fun `S2 getNoteLimitsMode is warn via GlobalConfigFile when note_limits is absent, matching the legacy constructor`() {
        val configPath = writeConfig("work_item_schemas:\n  default:\n    notes: []\n")

        val viaGcf = YamlWorkItemSchemaService(GlobalConfigFile(configPath))
        val viaPath = YamlWorkItemSchemaService(configPath)

        assertEquals("warn", viaGcf.getNoteLimitsMode())
        assertEquals(viaPath.getNoteLimitsMode(), viaGcf.getNoteLimitsMode())
    }

    // ──────────────────────────────────────────────
    // S3 — YamlStatusLabelService parity: GlobalConfigFile ctor vs legacy path ctor
    // ──────────────────────────────────────────────

    private val triggers = listOf("start", "complete", "block", "cancel", "cascade", "resume", "reopen", "zzz")

    private fun assertStatusLabelParity(
        configPath: Path,
        message: String,
    ) {
        val viaGcf = YamlStatusLabelService(GlobalConfigFile(configPath))
        val viaPath = YamlStatusLabelService(configPath)
        for (trigger in triggers) {
            assertEquals(viaPath.resolveLabel(trigger), viaGcf.resolveLabel(trigger), "$message (trigger=$trigger)")
        }
    }

    @Test
    fun `S3 status labels parity — absent config file falls back to NoOp defaults on both constructors`() {
        val configPath = absentConfigPath()
        assertStatusLabelParity(configPath, "absent config file")

        assertEquals("in-progress", YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
        assertNull(YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("zzz"))
    }

    @Test
    fun `S3 status labels parity — empty file falls back to NoOp defaults on both constructors`() {
        val configPath = writeConfig("")
        assertStatusLabelParity(configPath, "empty file")

        assertEquals("in-progress", YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
    }

    @Test
    fun `S3 status labels parity — no status_labels section falls back to NoOp defaults on both constructors`() {
        val configPath = writeConfig("work_item_schemas:\n  default:\n    notes: []\n")
        assertStatusLabelParity(configPath, "no status_labels section")

        assertEquals("in-progress", YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
    }

    @Test
    fun `S3 status labels parity — status_labels as an empty map returns null for every trigger on both constructors`() {
        val configPath = writeConfig("status_labels: {}\n")
        assertStatusLabelParity(configPath, "status_labels: {}")

        assertNull(YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
    }

    @Test
    fun `S3 status labels parity — an explicit null trigger value is a present key mapped to null on both constructors`() {
        val configPath = writeConfig("status_labels:\n  start: null\n")
        assertStatusLabelParity(configPath, "status_labels.start: null")

        val viaGcf = YamlStatusLabelService(GlobalConfigFile(configPath))
        assertNull(viaGcf.resolveLabel("start"))
        assertNull(viaGcf.resolveLabel("complete"), "a trigger absent from the map must also resolve to null")
    }

    @Test
    fun `S3 status labels parity — a partial override leaves unmapped triggers null on both constructors`() {
        val configPath = writeConfig("status_labels:\n  start: \"root-started\"\n")
        assertStatusLabelParity(configPath, "partial override")

        val viaGcf = YamlStatusLabelService(GlobalConfigFile(configPath))
        assertEquals("root-started", viaGcf.resolveLabel("start"))
        assertNull(viaGcf.resolveLabel("complete"))
    }

    @Test
    fun `S3 status labels parity — status_labels as a scalar falls back to NoOp defaults on both constructors`() {
        val configPath = writeConfig("status_labels: \"oops\"\n")
        assertStatusLabelParity(configPath, "status_labels as scalar")

        assertEquals("in-progress", YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
    }

    @Test
    fun `S3 status labels parity — an explicit null for the whole key falls back to NoOp defaults on both constructors`() {
        val configPath = writeConfig("status_labels: ~\n")
        assertStatusLabelParity(configPath, "status_labels: ~")

        assertEquals("in-progress", YamlStatusLabelService(GlobalConfigFile(configPath)).resolveLabel("start"))
    }

    // ──────────────────────────────────────────────
    // S4 — YamlActorAuthenticationConfigService parity: GlobalConfigFile ctor vs legacy path ctor
    // ──────────────────────────────────────────────

    @Test
    fun `S4 actor-auth parity — valid jwks verifier`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val viaGcf = YamlActorAuthenticationConfigService(GlobalConfigFile(configPath))
        val viaPath = YamlActorAuthenticationConfigService(configPath)

        assertEquals(viaPath.getConfig(), viaGcf.getConfig())
        assertEquals(viaPath.getWarnings(), viaGcf.getWarnings())
    }

    @Test
    fun `S4 actor-auth parity — noop verifier`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  verifier:
                    type: noop
                """.trimIndent(),
            )
        val viaGcf = YamlActorAuthenticationConfigService(GlobalConfigFile(configPath))
        val viaPath = YamlActorAuthenticationConfigService(configPath)

        assertEquals(viaPath.getConfig(), viaGcf.getConfig())
        assertEquals(viaPath.getWarnings(), viaGcf.getWarnings())
    }

    @Test
    fun `S4 actor-auth parity — absent actor_authentication section`() {
        val configPath = writeConfig("work_item_schemas:\n  default:\n    notes: []\n")
        val viaGcf = YamlActorAuthenticationConfigService(GlobalConfigFile(configPath))
        val viaPath = YamlActorAuthenticationConfigService(configPath)

        assertEquals(viaPath.getConfig(), viaGcf.getConfig())
        assertEquals(viaPath.getWarnings(), viaGcf.getWarnings())
    }

    @Test
    fun `S4 actor-auth parity — explicit null actor_authentication section`() {
        val configPath = writeConfig("actor_authentication: ~\n")
        val viaGcf = YamlActorAuthenticationConfigService(GlobalConfigFile(configPath))
        val viaPath = YamlActorAuthenticationConfigService(configPath)

        assertEquals(viaPath.getConfig(), viaGcf.getConfig())
        assertEquals(viaPath.getWarnings(), viaGcf.getWarnings())
    }

    @Test
    fun `S4 actor-auth parity — DEGRADED_MODE_POLICY env override`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  degraded_mode_policy: reject
                  verifier:
                    type: noop
                """.trimIndent(),
            )
        val envResolver: (String) -> String? = { name -> if (name == "DEGRADED_MODE_POLICY") "accept-self-reported" else null }
        val viaGcf = YamlActorAuthenticationConfigService(GlobalConfigFile(configPath), envResolver)
        val viaPath = YamlActorAuthenticationConfigService(configPath, envResolver)

        assertEquals(viaPath.getConfig(), viaGcf.getConfig())
        assertEquals(DegradedModePolicy.ACCEPT_SELF_REPORTED, viaGcf.getConfig().degradedModePolicy)
    }

    // ──────────────────────────────────────────────
    // S10 — actor-auth fail-closed parity: GlobalConfigFile ctor vs legacy path ctor
    // ──────────────────────────────────────────────

    @Test
    fun `S10 actor-auth fail-closed parity — invalid degraded_mode_policy`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  degraded_mode_policy: banana
                  verifier:
                    type: noop
                """.trimIndent(),
            )
        val exGcf =
            assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(GlobalConfigFile(configPath)).getConfig() }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath).getConfig() }

        assertEquals(exPath.message, exGcf.message)
        assertTrue(exGcf.message?.contains("banana") == true)
    }

    @Test
    fun `S10 actor-auth fail-closed parity — actor_authentication as a scalar value`() {
        val configPath = writeConfig("actor_authentication: reject\n")

        val exGcf =
            assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(GlobalConfigFile(configPath)).getConfig() }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath).getConfig() }

        assertEquals(exPath.message, exGcf.message)
    }

    @Test
    fun `S10 actor-auth fail-closed parity — jwks verifier with no key source`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    issuer: "https://accounts.example.com"
                """.trimIndent(),
            )
        val exGcf =
            assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(GlobalConfigFile(configPath)).getConfig() }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath).getConfig() }

        assertEquals(exPath.message, exGcf.message)
        assertTrue(exGcf.message?.contains("oidc_discovery") == true && exGcf.message?.contains("jwks_uri") == true)
    }

    @Test
    fun `S10 actor-auth fail-closed parity — unknown verifier type`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  verifier:
                    type: magic-unicorn
                """.trimIndent(),
            )
        val exGcf =
            assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(GlobalConfigFile(configPath)).getConfig() }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath).getConfig() }

        assertEquals(exPath.message, exGcf.message)
        assertTrue(exGcf.message?.contains("magic-unicorn") == true)
    }

    @Test
    fun `S10 actor-auth fail-closed parity — legacy top-level auditing key`() {
        val configPath = writeConfig("auditing:\n  enabled: true\n")

        val exGcf =
            assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(GlobalConfigFile(configPath)).getConfig() }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath).getConfig() }

        assertEquals(exPath.message, exGcf.message)
        assertTrue(exGcf.message?.contains("Unknown top-level config key 'auditing:'") == true)
    }

    @Test
    fun `S10 actor-auth fail-closed parity — bad DEGRADED_MODE_POLICY env value`() {
        val configPath =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  verifier:
                    type: noop
                """.trimIndent(),
            )
        val badEnv: (String) -> String? = { name -> if (name == "DEGRADED_MODE_POLICY") "banana" else null }

        val exGcf =
            assertFailsWith<IllegalArgumentException> {
                YamlActorAuthenticationConfigService(
                    GlobalConfigFile(configPath),
                    badEnv
                ).getConfig()
            }
        val exPath = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(configPath, badEnv).getConfig() }

        assertEquals(exPath.message, exGcf.message)
        assertTrue(exGcf.message?.contains("banana") == true)
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenario S12 (O11). Oracle: `task-scope`'s O11 build step — "in `YamlStatusLabelService.loadLabelsFromPath`
 * and `YamlActorAuthenticationConfigService.loadYamlConfigFromPath`, replace `Yaml()` with
 * `Yaml(SafeConstructor(LoaderOptions()))`. That is the only change in each. Lenient versus
 * fail-closed semantics are unchanged: a `!!` tag becomes an ordinary parse error handled by the
 * existing catch" — cited by `test-plan` against [GlobalConfigFileTest]'s S9 tag-policy pattern
 * (a disallowed yaml tag fails to parse under `SafeConstructor`, well before any top-level-key
 * validation runs) and each class's own pre-existing fail-closed/fallback contract:
 * [YamlStatusLabelServiceTest] ("malformed YAML uses defaults gracefully" -> [NoOpStatusLabelService]
 * fallback) for the status-label legacy path constructor, and [GlobalConfigFileTest]'s "IAE naming
 * the config path" pattern for the actor-auth legacy path constructor's existing catch.
 *
 * EXISTING-SURFACE per the test-plan label ([YamlStatusLabelService] and
 * [YamlActorAuthenticationConfigService] both predate C3). Revert per the frozen plan: "Yaml()" —
 * reverting O11 restores the unsafe constructor, under which a `!!java.lang.StringBuilder` tag
 * constructs a real (non-String) object rather than failing to parse, so the whole-file parse
 * failure this test observes would not occur.
 */
class LegacyLoaderSafeConstructorTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    @Test
    fun `S12 - YamlStatusLabelService legacy path constructor falls back to NoOp defaults for a disallowed yaml tag`() {
        val path = writeConfig("status_labels:\n  start: !!java.lang.StringBuilder \"x\"\n")
        val service = YamlStatusLabelService(path)

        assertEquals(
            NoOpStatusLabelService.resolveLabel("start"),
            service.resolveLabel("start"),
            "a disallowed yaml tag must be treated as an ordinary parse error, falling back to NoOp defaults",
        )
    }

    @Test
    fun `S12 - YamlActorAuthenticationConfigService legacy path constructor throws IAE naming the config path for a disallowed yaml tag`() {
        val path = writeConfig("x: !!java.lang.StringBuilder \"y\"\n")
        val service = YamlActorAuthenticationConfigService(path)

        val ex = assertFailsWith<IllegalArgumentException> { service.getConfig() }
        assertTrue(ex.message?.contains(path.toString()) == true, "expected the config path in: ${ex.message}")
    }

    // ──────────────────────────────────────────────
    // Strengthening: a disallowed tag elsewhere in the document must reject the WHOLE document,
    // even when it also carries an otherwise-valid, distinctive setting the service would
    // otherwise honor. These tests pin whole-document rejection. Note: with SnakeYAML 2.x a plain
    // Yaml() ALSO rejects `!!` global tags by default ("Global tag is not allowed"), so these tests
    // guard the fail-closed outcome rather than distinguishing SafeConstructor from plain Yaml()
    // (orchestrator red-proof, f2c50e6d: no behavioural red possible for that revert).
    // ──────────────────────────────────────────────

    @Test
    fun `S12 - YamlStatusLabelService legacy path constructor rejects the whole document, not just the disallowed key`() {
        val path =
            writeConfig(
                "status_labels:\n  start: !!java.lang.StringBuilder \"x\"\n  complete: \"custom-done\"\n",
            )
        val service = YamlStatusLabelService(path)

        assertEquals(
            NoOpStatusLabelService.resolveLabel("complete"),
            service.resolveLabel("complete"),
            "a disallowed tag anywhere in the document must reject the whole document; the valid " +
                "'complete: custom-done' entry must not silently take effect",
        )
    }

    @Test
    fun `S12 - YamlActorAuthenticationConfigService legacy path constructor rejects the doc even when actor_authentication is valid`() {
        // work_item_schemas is a top-level key the legacy actor-auth loader tolerates when
        // actor_authentication is absent (see GlobalConfigFileTest "absent actor_authentication
        // section", which uses the same key with the same legacy path constructor and asserts no
        // exception is thrown), so the disallowed tag placed under it — not under
        // actor_authentication — is what must reject the whole document.
        val path =
            writeConfig(
                """
                actor_authentication:
                  enabled: true
                  verifier:
                    type: noop
                work_item_schemas:
                  default: !!java.lang.StringBuilder "z"
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(path)

        val ex = assertFailsWith<IllegalArgumentException> { service.getConfig() }
        assertTrue(
            ex.message?.contains(path.toString()) == true,
            "expected the config path in: ${ex.message}; a disallowed tag anywhere in the document " +
                "must reject the whole document, not just leave the valid actor_authentication section to load",
        )
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a0a47509, R18) for the removed degraded-mode policy: scenarios S13, S14 and S15 of
 * the frozen test-plan (all EXISTING-SURFACE: the YAML service and its constructors predate the change, so red-proof is a plain
 * revert of the key and variable checks).
 *
 * Oracles (written from the task-scope section A and the plan, never from a run): the key `actor_authentication.degraded_mode_policy`
 * present at all, with any value (a former policy word, an unknown word, an empty string, or YAML null), is an
 * IllegalArgumentException naming the key and the config path; the environment variable `DEGRADED_MODE_POLICY` set to any value
 * (resolver answers non-null, including the empty string) is an IllegalArgumentException naming the variable, whether or not a
 * config file exists; with both absent the config loads as before (Noop verifier, no warnings). A removed key fails closed:
 * a removed policy is never silently ignored, because an operator who still believes `reject` is in force must find out at boot.
 *
 * The throw may happen at construction or on the first read; every negative assertion therefore wraps construction plus
 * both reads in one block, which a revert of either check leaves non-throwing.
 *
 * Negative-assertion conditions: each throw is paired with the identical config minus the key (or a resolver that answers null)
 * that loads, so a service that always threw fails the pair; a comment mentioning the key and an unrelated variable name do not
 * throw, so a text search in place of a key check fails.
 *
 * Domain invariants satisfied by construction: configs are minimal valid `actor_authentication` blocks (a `verifier: {type: noop}`
 * section), written under `.taskorchestrator/config.yaml` of a temp directory; the environment is injected through the
 * constructor's resolver, never the process environment.
 */
class DegradedPolicyKeyRemovedTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(content: String): Path {
        val dir = File(tempDir.toFile(), ".taskorchestrator")
        dir.mkdirs()
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file.toPath()
    }

    private fun load(
        path: Path,
        env: (String) -> String? = { null },
    ): ActorAuthenticationConfig {
        val service = YamlActorAuthenticationConfigService(path, env)
        val config = service.getConfig()
        service.getWarnings()
        return config
    }

    private fun configWithPolicyLine(policyLine: String): String = "actor_authentication:\n  $policyLine\n  verifier:\n    type: noop\n"

    private val configWithoutKey = "actor_authentication:\n  verifier:\n    type: noop\n"

    private fun assertKeyFailsClosed(
        policyLine: String,
        label: String,
    ) {
        val path = writeConfig(configWithPolicyLine(policyLine))
        val ex = assertThrows<IllegalArgumentException>("$label: a present degraded_mode_policy key must fail startup") { load(path) }
        val message = ex.message.orEmpty()
        assertTrue("degraded_mode_policy" in message, "$label: the message names the key: $message")
        assertTrue(path.toString() in message, "$label: the message names the config path ($path): $message")
        // control: the identical config without the key loads
        assertEquals(VerifierConfig.Noop, load(writeConfig(configWithoutKey)).verifier, "$label: control")
    }

    // ------------------------------------------------------------------ S13 the YAML key

    @Test
    fun `S13 a former policy word fails startup naming the key and the config path`() {
        assertKeyFailsClosed("degraded_mode_policy: accept-cached", "accept-cached")
        assertKeyFailsClosed("degraded_mode_policy: accept-self-reported", "accept-self-reported")
        assertKeyFailsClosed("degraded_mode_policy: reject", "reject")
    }

    @Test
    fun `S13 an explicit YAML null or a bare key fails startup`() {
        assertKeyFailsClosed("degraded_mode_policy: null", "null")
        assertKeyFailsClosed("degraded_mode_policy: ~", "tilde")
        assertKeyFailsClosed("degraded_mode_policy:", "bare key")
    }

    @Test
    fun `S13 an unknown or empty value fails the same way as a former policy word`() {
        assertKeyFailsClosed("degraded_mode_policy: banana", "banana")
        assertKeyFailsClosed("degraded_mode_policy: \"\"", "empty string")
        assertKeyFailsClosed("degraded_mode_policy: true", "boolean")
    }

    @Test
    fun `S13 probe an uppercase or underscored spelling of a former value still fails startup`() {
        assertKeyFailsClosed("degraded_mode_policy: REJECT", "uppercase")
        assertKeyFailsClosed("degraded_mode_policy: accept_cached", "underscore")
    }

    @Test
    fun `S13 the key fails startup beside a jwks verifier too`() {
        val path =
            writeConfig(
                """
                actor_authentication:
                  degraded_mode_policy: reject
                  verifier:
                    type: jwks
                    audience: "test-audience"
                    issuer: "https://issuer.example.com"
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent() + "\n",
            )
        val ex = assertThrows<IllegalArgumentException> { load(path) }
        assertTrue("degraded_mode_policy" in ex.message.orEmpty(), "message: ${ex.message}")
    }

    // ------------------------------------------------------------------ S14 the environment variable

    private fun envOf(
        name: String,
        value: String?,
    ): (String) -> String? = { asked -> if (asked == name) value else null }

    @Test
    fun `S14 DEGRADED_MODE_POLICY set to reject fails startup naming the variable with no config file`() {
        val missing = tempDir.resolve("no-such-dir/config.yaml")
        val ex =
            assertThrows<IllegalArgumentException> { load(missing, envOf("DEGRADED_MODE_POLICY", "reject")) }
        assertTrue("DEGRADED_MODE_POLICY" in ex.message.orEmpty(), "the message names the variable: ${ex.message}")
        // control: the same missing file with the variable unset loads the defaults
        assertEquals(ActorAuthenticationConfig(), load(missing, envOf("DEGRADED_MODE_POLICY", null)))
    }

    @Test
    fun `S14 any value of the variable fails startup, including the other policy words, an unknown word and the empty string`() {
        val missing = tempDir.resolve("still-no-dir/config.yaml")
        for (value in listOf("accept-cached", "accept-self-reported", "banana", "")) {
            val ex = assertThrows<IllegalArgumentException>("value '$value'") { load(missing, envOf("DEGRADED_MODE_POLICY", value)) }
            assertTrue("DEGRADED_MODE_POLICY" in ex.message.orEmpty(), "value '$value': ${ex.message}")
        }
        assertEquals(ActorAuthenticationConfig(), load(missing), "control: no variable")
    }

    @Test
    fun `S14 the variable fails startup beside a valid config file that has no key`() {
        val path = writeConfig(configWithoutKey)
        val ex = assertThrows<IllegalArgumentException> { load(path, envOf("DEGRADED_MODE_POLICY", "reject")) }
        assertTrue("DEGRADED_MODE_POLICY" in ex.message.orEmpty(), "message: ${ex.message}")
        assertEquals(VerifierConfig.Noop, load(path).verifier, "control: the same file with no variable loads")
    }

    // ------------------------------------------------------------------ S15 neither present

    @Test
    fun `S15 neither the key nor the variable loads the Noop verifier with no warnings`() {
        val path = writeConfig(configWithoutKey)
        val service = YamlActorAuthenticationConfigService(path) { null }
        assertEquals(ActorAuthenticationConfig(), service.getConfig())
        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertEquals(emptyList(), service.getWarnings())
    }

    @Test
    fun `S15 a missing config file with no variable loads the defaults with no warnings`() {
        val service = YamlActorAuthenticationConfigService(tempDir.resolve("absent/config.yaml")) { null }
        assertEquals(ActorAuthenticationConfig(), service.getConfig())
        assertEquals(emptyList(), service.getWarnings())
    }

    @Test
    fun `S15 probe a comment that mentions the removed key does not trip the check`() {
        val path =
            writeConfig(
                "actor_authentication:\n" +
                    "  # degraded_mode_policy: reject   (removed in 4.0)\n" +
                    "  verifier:\n" +
                    "    type: noop\n",
            )
        val service = YamlActorAuthenticationConfigService(path) { null }
        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertEquals(emptyList(), service.getWarnings())
    }

    @Test
    fun `S15 probe a config with enabled true and no verifier section loads as before`() {
        val path = writeConfig("actor_authentication:\n  enabled: true\n")
        val service = YamlActorAuthenticationConfigService(path) { null }
        assertEquals(VerifierConfig.Noop, service.getConfig().verifier)
        assertEquals(emptyList(), service.getWarnings())
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for item 983615e7 (diagnosis Fix step 8): [AppConfig.deprecatedEnvWarnings]
 * must surface a startup-time WARN-worthy notice naming `API_REDACT_ACTOR_PROOF` whenever that env
 * var is set to any value, since actor proofs are no longer persisted and the flag it used to gate
 * has nothing left to redact.
 *
 * Test-plan scenario S12 (NEW-SURFACE: `deprecatedEnvWarnings()` is introduced by this fix — no
 * revert can yield behavioral red for a symbol that does not exist pre-fix; the narrowest-revert
 * recipe is "remove `deprecatedEnvWarnings()` and its call site in `CurrentMcpServer.run()`", which
 * is compile-red, not behavioral-red — substitute verification: the method's declared contract
 * (`envResolver("API_REDACT_ACTOR_PROOF") != null` → one entry naming the var, else empty) is
 * exercised directly and independently of the WARN-logging call site).
 *
 * Follows [AppConfigTest]'s injected-resolver convention: a fake `(String) -> String?` resolver
 * over a fixed map, so no JVM environment mutation is required.
 */
class AppConfigDeprecatedEnvTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test
    fun `S12 API_REDACT_ACTOR_PROOF unset yields no deprecation warnings`() {
        val config = AppConfig.fromEnv(env())
        assertEquals(emptyList(), config.deprecatedEnvWarnings())
    }

    @Test
    fun `S12 API_REDACT_ACTOR_PROOF=false yields exactly one warning naming the var`() {
        val config = AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to "false"))
        val warnings = config.deprecatedEnvWarnings()
        assertEquals(1, warnings.size, "expected exactly one deprecation warning; got $warnings")
        assertTrue(
            warnings.single().contains("API_REDACT_ACTOR_PROOF"),
            "warning must name API_REDACT_ACTOR_PROOF: ${warnings.single()}"
        )
    }

    @Test
    fun `S12 API_REDACT_ACTOR_PROOF=true yields exactly one warning naming the var`() {
        val config = AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to "true"))
        val warnings = config.deprecatedEnvWarnings()
        assertEquals(1, warnings.size, "expected exactly one deprecation warning; got $warnings")
        assertTrue(
            warnings.single().contains("API_REDACT_ACTOR_PROOF"),
            "warning must name API_REDACT_ACTOR_PROOF: ${warnings.single()}"
        )
    }

    @Test
    fun `S12 API_REDACT_ACTOR_PROOF set to an arbitrary non-boolean value still yields one warning`() {
        // The declared contract fires on "any value", independent of whether it parses as a
        // boolean — the var itself is deprecated, not just a specific setting of it.
        val config = AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to "banana"))
        val warnings = config.deprecatedEnvWarnings()
        assertEquals(1, warnings.size, "expected exactly one deprecation warning for any set value; got $warnings")
    }

    @Test
    fun `S12 an empty string value still counts as set and yields one warning`() {
        // envResolver("API_REDACT_ACTOR_PROOF") != null is satisfied by "" (present-but-empty),
        // distinct from the var being entirely absent from the environment.
        val config = AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to ""))
        val warnings = config.deprecatedEnvWarnings()
        assertEquals(1, warnings.size, "an empty-but-present value must still count as set; got $warnings")
    }

    @Test
    fun `S12 other env vars being set does not trigger the API_REDACT_ACTOR_PROOF warning`() {
        val config = AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "false", "LOG_LEVEL" to "DEBUG"))
        assertEquals(
            emptyList(),
            config.deprecatedEnvWarnings(),
            "unrelated env vars being set must not trigger the API_REDACT_ACTOR_PROOF deprecation warning"
        )
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `59989657` (test-plan S4, S5, S9, S10, S11, S12).
 *
 * Targets `YamlActorAuthenticationConfigService`'s NEW https-or-loopback validation of
 * `jwks_uri`/`oidc_discovery` (`validateKeySourceUrl`, D-c) and the new `allow_insecure_url`
 * verifier key (`VerifierConfig.Jwks.allowInsecureUrl`).
 *
 * A sibling `YamlActorAuthenticationConfigServiceTest.kt` already exists in this worktree and
 * covers this service's other parsing behavior (DID-trust, multiple-source mutual exclusion,
 * `degraded_mode_policy`, etc.) -- none of ITS scenarios touch `allow_insecure_url` or a
 * non-https `jwks_uri`/`oidc_discovery`, so this file adds the https/loopback coverage rather
 * than duplicating it. Per file-ownership scope, that sibling file is read-only here.
 *
 * Oracles (supplied verbatim in the dispatch prompt's declarations block):
 * `YamlActorAuthenticationConfigService.kt`'s `validateKeySourceUrl` KDoc oracle (D-c) --
 * https always accepted; http accepted only with `allow_insecure_url: true` AND a literal
 * loopback host (no DNS resolution); every other outcome throws `IllegalArgumentException`
 * naming `actor_authentication.verifier.<keyName>`; `allow_insecure_url` itself must be a YAML
 * boolean or parsing throws naming `actor_authentication.verifier.allow_insecure_url`.
 * `current/docs/fleet-deployment.md` :119-120 (the human-facing statement of the same rule).
 *
 * Every fixture below carries a non-empty `algorithms:` list -- confirmed against the sibling
 * test file's own `algorithms missing from type jwks throws IllegalArgumentException` case --
 * because that pre-existing gate runs BEFORE `validateKeySourceUrl` and would otherwise mask
 * the scheme-validation outcome under test.
 */
class ActorAuthKeySourceHttpsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createConfigFile(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    // -------------------------------------------------------------------------------------------
    // S4 -- https (and HTTPS://) jwks_uri/oidc_discovery load with no opt-in; jwks_path and
    // did_allowlist (no URL field at all) are unaffected by the new validation. [KDoc] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S4 - https jwks_uri with no opt-in loads successfully`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        verifier as VerifierConfig.Jwks
        assertEquals("https://accounts.example.com/.well-known/jwks.json", verifier.jwksUri)
        assertFalse(verifier.allowInsecureUrl, "S4: allow_insecure_url must default to false when absent")
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S4 - uppercase HTTPS scheme is accepted case-insensitively`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "HTTPS://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("HTTPS://accounts.example.com/.well-known/jwks.json", verifier.jwksUri)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S4 - https oidc_discovery with no opt-in loads successfully`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    oidc_discovery: "https://accounts.example.com/.well-known/openid-configuration"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("https://accounts.example.com/.well-known/openid-configuration", verifier.oidcDiscovery)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S4 - jwks_path has no URL field and is unaffected by the https validation`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_path: "/etc/keys/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("/etc/keys/jwks.json", verifier.jwksPath)
        assertFalse(verifier.allowInsecureUrl)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S4 - did_allowlist has no URL field and is unaffected by the https validation`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    did_allowlist:
                      - "did:web:example.com"
                    algorithms:
                      - EdDSA
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals(listOf("did:web:example.com"), verifier.didAllowlist)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------------------------
    // S5 -- loopback http jwks_uri loads ONLY with allow_insecure_url: true; the field round-trips
    // onto VerifierConfig.Jwks.allowInsecureUrl. [fleet:119-120] NEW-SURFACE
    //
    // Narrowest-revert recipe (for the orchestrator's red-proof; not run by the test author):
    // keep the `allowInsecureUrl` field on VerifierConfig.Jwks, but revert ONLY the parseVerifier
    // call site so it always constructs with `allowInsecureUrl = false` regardless of the YAML
    // key, while leaving validateKeySourceUrl's enforcement intact. Under that narrow revert,
    // each of the three loopback http URLs below flips from "loads" to "throws
    // IllegalArgumentException" (since validateKeySourceUrl would see allowInsecureUrl=false),
    // and `verifier.allowInsecureUrl` can never be observed true from YAML -- both assertions
    // below turn red.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S5 - http localhost jwks_uri with allow_insecure_url true loads and round-trips the flag`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://localhost:8080/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("http://localhost:8080/j", verifier.jwksUri)
        assertTrue(verifier.allowInsecureUrl, "S5: allow_insecure_url: true must round-trip onto the domain field")
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S5 - http 127-0-0-1 jwks_uri with allow_insecure_url true loads`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://127.0.0.1:9000/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("http://127.0.0.1:9000/j", verifier.jwksUri)
        assertTrue(verifier.allowInsecureUrl)
    }

    @Test
    fun `S5 - http bracketed IPv6 loopback jwks_uri with allow_insecure_url true loads`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://[::1]:9000/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertEquals("http://[::1]:9000/j", verifier.jwksUri)
        assertTrue(verifier.allowInsecureUrl)
    }

    @Test
    fun `S5 - allow_insecure_url absent defaults to false on an https config`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val verifier = service.getConfig().verifier as VerifierConfig.Jwks
        assertFalse(verifier.allowInsecureUrl, "S5: absent allow_insecure_url must default to false")
    }

    // -------------------------------------------------------------------------------------------
    // S9 -- http jwks_uri / http oidc_discovery WITHOUT the opt-in each throw naming that key.
    //
    // Ambiguity arbitration (recorded per test-author skill §8): the test-plan's S9 literal body
    // ("http://idp.example/j as jwks_uri, then oidc_discovery") is ambiguous between one YAML
    // setting BOTH keys (which would trip the pre-existing "exactly one source" mutual-exclusion
    // check first, per the frozen diagnosis note's Blast radius/D-c ordering -- never reaching
    // validateKeySourceUrl) and two separate single-key configs. The dispatch's own supplied
    // "DECLARATION GAPS" analysis (item 3) flags this exact ambiguity and names the two-
    // separate-sub-cases reading as the one that actually exercises validateKeySourceUrl.
    // Resolved here as two separate sub-cases per that supplied analysis -- not by consulting
    // the implementation. [fleet:119] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S9 - http jwks_uri without opt-in throws naming jwks_uri`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://idp.example/j"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.jwks_uri") == true,
            "S9: expected the key-naming pattern for jwks_uri. Got: ${ex.message}",
        )
    }

    @Test
    fun `S9 - http oidc_discovery without opt-in throws naming oidc_discovery`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    oidc_discovery: "http://idp.example/.well-known/openid-configuration"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.oidc_discovery") == true,
            "S9: expected the key-naming pattern for oidc_discovery. Got: ${ex.message}",
        )
    }

    // -------------------------------------------------------------------------------------------
    // S10 -- non-http(s) schemes and a malformed URL always throw, even with opt-in.
    // [fleet:119] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S10 - file scheme jwks_uri throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "file:///j.json"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.jwks_uri") == true,
            "S10: a file:// scheme must never be accepted, opt-in or not. Got: ${ex.message}",
        )
    }

    @Test
    fun `S10 - ftp scheme jwks_uri throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "ftp://idp/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.jwks_uri") == true,
            "S10: an ftp:// scheme must never be accepted, opt-in or not. Got: ${ex.message}",
        )
    }

    @Test
    fun `S10 - malformed URL throws naming jwks_uri`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "not a url"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.jwks_uri") == true,
            "S10: a malformed URL must throw naming jwks_uri, never silently fall back. Got: ${ex.message}",
        )
    }

    // -------------------------------------------------------------------------------------------
    // S11 -- loopback-spoofing hosts and out-of-range octets are rejected even WITH the opt-in
    // (isLiteralLoopbackHost does no DNS resolution and no substring/suffix matching).
    // [fleet:119] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S11 - ordinary non-loopback http host throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://auth.example.com/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message?.contains("actor_authentication.verifier.jwks_uri") == true, "Got: ${ex.message}")
    }

    @Test
    fun `S11 - 127-0-0-1-evil-com spoofed loopback suffix throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://127.0.0.1.evil.com/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message?.contains("actor_authentication.verifier.jwks_uri") == true, "Got: ${ex.message}")
    }

    @Test
    fun `S11 - localhost-evil-com spoofed loopback suffix throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://localhost.evil.com/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message?.contains("actor_authentication.verifier.jwks_uri") == true, "Got: ${ex.message}")
    }

    @Test
    fun `S11 - 127-0-0-256 out-of-range octet throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://127.0.0.256/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message?.contains("actor_authentication.verifier.jwks_uri") == true, "Got: ${ex.message}")
    }

    @Test
    fun `S11 - percent-encoded 127-0-0-1 pct2e evil-com throws even with opt-in`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "http://127.0.0.1%2eevil.com/j"
                    allow_insecure_url: true
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(ex.message?.contains("actor_authentication.verifier.jwks_uri") == true, "Got: ${ex.message}")
    }

    // -------------------------------------------------------------------------------------------
    // S12 -- allow_insecure_url must itself be a YAML boolean, checked regardless of scheme.
    // [fleet:120] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S12 - allow_insecure_url as a quoted string throws naming allow_insecure_url, even against https`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    allow_insecure_url: "true"
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.allow_insecure_url") == true,
            "S12: a quoted-string allow_insecure_url must throw naming that key even though the URL is https. Got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("must be a boolean") == true,
            "S12: expected the documented 'must be a boolean' wording. Got: ${ex.message}",
        )
    }

    @Test
    fun `S12 - allow_insecure_url as an integer throws naming allow_insecure_url, even against https`() {
        val configFile =
            createConfigFile(
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    allow_insecure_url: 1
                    algorithms:
                      - RS256
                """.trimIndent(),
            )
        val service = YamlActorAuthenticationConfigService(configFile)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.getConfig() }
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.allow_insecure_url") == true,
            "S12: an integer allow_insecure_url must throw naming that key even though the URL is https. Got: ${ex.message}",
        )
    }
}

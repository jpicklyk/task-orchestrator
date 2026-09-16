package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Independent test authorship for item 231dd7f3 (API_JWKS_URL must be https; http is permitted
 * only for a literal loopback host with API_JWKS_ALLOW_INSECURE_URL=true; every rejection is an
 * IllegalArgumentException from ApiAuthConfigLoader.load()).
 *
 * Oracles: frozen `test-plan` / `diagnosis` notes on item 231dd7f3 (documented API_JWKS_URL
 * contract, the loader's fail-fast IllegalArgumentException path, EnvBoolean.require's
 * unrecognised-value contract, RFC 3986 §3.2.2 literal-host comparison). No assertion in this
 * file was derived by reading infrastructure/config/ApiAuthConfigLoader.kt.
 *
 * All scenarios are EXISTING-SURFACE: every test drives only the pre-existing public surface
 * `ApiAuthConfigLoader(envResolver).load()` / `ApiAuthConfig.Jwks`; the new scheme-validation
 * members introduced by the fix are private and are not referenced directly.
 */
class ApiAuthConfigLoaderJwksSchemeTest {
    @TempDir
    lateinit var tempDir: Path

    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeTokenFile(content: String): String {
        val file = tempDir.resolve("tokens.yaml").toFile()
        file.writeText(content)
        return file.absolutePath
    }

    private fun validBearerTokenYaml(): String {
        val hash = sha256Hex("my-secret-token")
        return """
            version: 1
            tokens:
              - id: dashboard
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
    }

    private fun jwksLoader(vararg extra: Pair<String, String?>): ApiAuthConfigLoader {
        val base =
            arrayOf(
                "API_ENABLED" to "true",
                "API_AUTH_MODE" to "jwks",
                "API_JWKS_ISSUER" to "https://idp.example.com",
                "API_JWKS_AUDIENCE" to "task-orchestrator-api",
                "API_JWKS_ALGORITHMS" to "RS256",
            )
        return ApiAuthConfigLoader(envResolver = env(*base, *extra))
    }

    // -------------------------------------------------------------------------
    // S1 — happy: https, no flag. Regression guard, no red.
    // -------------------------------------------------------------------------

    @Test
    fun `S1 https URL with no flag returns Jwks config with url verbatim`() {
        val loader = jwksLoader("API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json")
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        assertEquals("https://idp.example.com/.well-known/jwks.json", (config as ApiAuthConfig.Jwks).url)
    }

    // -------------------------------------------------------------------------
    // S2 — failure (red): http, flag unset -> throws naming var/scheme/escape hatch.
    // -------------------------------------------------------------------------

    @Test
    fun `S2 http URL without the insecure flag throws naming API_JWKS_URL http https and the flag`() {
        val loader = jwksLoader("API_JWKS_URL" to "http://auth.example.com/.well-known/jwks.json")
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("API_JWKS_URL"), "Error: $msg")
        assertTrue(msg.contains("http://auth.example.com/.well-known/jwks.json"), "Error: $msg")
        assertTrue(msg.contains("scheme 'http'"), "Error: $msg")
        assertTrue(msg.contains("https"), "Error: $msg")
        assertTrue(msg.contains("API_JWKS_ALLOW_INSECURE_URL"), "Error: $msg")
        assertTrue(msg.contains("true"), "Error: $msg")
    }

    // -------------------------------------------------------------------------
    // S3 — happy (no red): http + localhost + flag true -> accepted.
    // -------------------------------------------------------------------------

    @Test
    fun `S3 http localhost URL with the insecure flag true is accepted`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://localhost:8080/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        assertEquals("http://localhost:8080/jwks.json", (config as ApiAuthConfig.Jwks).url)
    }

    // -------------------------------------------------------------------------
    // S4 — failure (red): http + flag true but non-loopback host -> still throws, names host.
    // -------------------------------------------------------------------------

    @Test
    fun `S4 http non-loopback URL with the insecure flag true still throws naming the host`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://auth.example.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("API_JWKS_URL"), "Error: $msg")
        assertTrue(msg.contains("API_JWKS_ALLOW_INSECURE_URL=true"), "Error: $msg")
        assertTrue(msg.contains("host 'auth.example.com' is not a loopback address"), "Error: $msg")
    }

    // -------------------------------------------------------------------------
    // S5 — edge (red): literal loopback forms (127.0.0.1, [::1]) + flag -> accepted.
    // -------------------------------------------------------------------------

    @Test
    fun `S5 http with literal loopback host 127_0_0_1 and flag true is accepted`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://127.0.0.1:9000/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
    }

    @Test
    fun `S5 http with bracketed IPv6 loopback host and flag true is accepted`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://[::1]:9000/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
    }

    // -------------------------------------------------------------------------
    // S6 — edge (red): loopback-LOOKING suffixed hosts + flag -> rejected (O5, literal compare).
    // -------------------------------------------------------------------------

    @Test
    fun `S6 http on a 127_0_0_1-prefixed but not loopback host with flag true is rejected`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://127.0.0.1.evil.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("is not a loopback address"), "Error: $msg")
        assertTrue(msg.contains("127.0.0.1.evil.com"), "Error: $msg")
    }

    @Test
    fun `S6 http on a localhost-prefixed but not loopback host with flag true is rejected`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://localhost.evil.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("is not a loopback address"), "Error: $msg")
        assertTrue(msg.contains("localhost.evil.com"), "Error: $msg")
    }

    // -------------------------------------------------------------------------
    // S7 — edge (red): scheme comparison is case-insensitive.
    // -------------------------------------------------------------------------

    @Test
    fun `S7 uppercase HTTPS scheme with no flag is accepted`() {
        val loader = jwksLoader("API_JWKS_URL" to "HTTPS://idp.example.com/x")
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
    }

    @Test
    fun `S7 uppercase HTTP scheme with localhost and flag true is accepted`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "HTTP://localhost/x",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
    }

    // -------------------------------------------------------------------------
    // S8 — failure (red): malformed flag value -> throws naming variable + raw value (O4).
    // Evaluated before the scheme check, so it fires even for an https URL.
    // -------------------------------------------------------------------------

    @Test
    fun `S8 malformed API_JWKS_ALLOW_INSECURE_URL value throws naming the variable and the raw value`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "maybe",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("API_JWKS_ALLOW_INSECURE_URL"), "Error: $msg")
        assertTrue(msg.contains("invalid value"), "Error: $msg")
        assertTrue(msg.contains("maybe"), "Error: $msg")
        assertTrue(msg.contains("true or false"), "Error: $msg")
    }

    // -------------------------------------------------------------------------
    // S9 — edge (red): non-http(s) scheme rejected even with the flag set true.
    // -------------------------------------------------------------------------

    @Test
    fun `S9 file scheme is rejected even with the insecure flag set true`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "file:///tmp/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("API_JWKS_URL"), "Error: $msg")
        assertTrue(msg.contains("scheme 'file'"), "Error: $msg")
        assertTrue(msg.contains("https"), "Error: $msg")
    }

    // -------------------------------------------------------------------------
    // S10 — edge (no red): flag true + https unchanged; flag is jwks-mode-scoped only.
    // -------------------------------------------------------------------------

    @Test
    fun `S10 flag true with an https URL leaves the Jwks config unchanged`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        assertEquals("https://idp.example.com/.well-known/jwks.json", (config as ApiAuthConfig.Jwks).url)
    }

    @Test
    fun `S10 API_JWKS_ALLOW_INSECURE_URL under bearer mode is ignored — normal Bearer config`() {
        val tokensPath = writeTokenFile(validBearerTokenYaml())
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "bearer",
                        "API_TOKENS_PATH" to tokensPath,
                        "API_JWKS_ALLOW_INSECURE_URL" to "true",
                    ),
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Bearer::class.java, config)
    }

    // -------------------------------------------------------------------------
    // Probes (§6) — recorded in test-manifest whether or not they found anything.
    // -------------------------------------------------------------------------

    // Boundary/suffix probe: "127." prefix with a segment out of 0..255 range is not loopback.
    @Test
    fun `probe http on 127_0_0_256 (out-of-range IPv4 segment) with flag true is rejected`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://127.0.0.256/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("is not a loopback address"), "Error: ${ex.message}")
    }

    // Mixed-case probe: loopback host name compared case-insensitively.
    @Test
    fun `probe mixed-case LocalHost with http and flag true is accepted`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://LocalHost:8080/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
    }

    // Empty vs. absent vs. null probe on API_JWKS_ALLOW_INSECURE_URL.
    @Test
    fun `probe flag absent from env defaults to false and rejects http`() {
        val loader = jwksLoader("API_JWKS_URL" to "http://auth.example.com/.well-known/jwks.json")
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_ALLOW_INSECURE_URL"), "Error: ${ex.message}")
    }

    @Test
    fun `probe flag set to empty string is an unrecognised value and throws`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        val msg = ex.message!!
        assertTrue(msg.contains("API_JWKS_ALLOW_INSECURE_URL"), "Error: $msg")
        assertTrue(msg.contains("invalid value"), "Error: $msg")
    }

    @Test
    fun `probe flag literal false rejects http`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://auth.example.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "false",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_ALLOW_INSECURE_URL"), "Error: ${ex.message}")
    }

    // Encoded-host probe: percent-encoded suffix on a loopback-looking host is not a literal
    // loopback match (RFC 3986 §3.2.2 — literal comparison only, no decoding, no DNS).
    @Test
    fun `probe percent-encoded evil suffix on a loopback-looking host is rejected`() {
        val loader =
            jwksLoader(
                "API_JWKS_URL" to "http://127.0.0.1%2eevil.com/.well-known/jwks.json",
                "API_JWKS_ALLOW_INSECURE_URL" to "true",
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("is not a loopback address"), "Error: ${ex.message}")
    }

    // Alternate separators / duplicates / replay-idempotency: N/A — ApiAuthConfigLoader.load()
    // is a pure function over env vars with no path parsing and no persisted state to replay.
}

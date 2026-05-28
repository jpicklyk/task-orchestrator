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

class ApiAuthConfigLoaderTest {
    @TempDir
    lateinit var tempDir: Path

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

    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    // -------------------------------------------------------------------------
    // API disabled
    // -------------------------------------------------------------------------

    @Test
    fun `API_ENABLED=false returns Disabled`() {
        val loader = ApiAuthConfigLoader(envResolver = env("API_ENABLED" to "false"))
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Disabled::class.java, config)
    }

    @Test
    fun `API_ENABLED not set defaults to enabled — requires AUTH_MODE`() {
        val loader = ApiAuthConfigLoader(envResolver = env())
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_AUTH_MODE"), "Error: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // Bearer mode — valid
    // -------------------------------------------------------------------------

    @Test
    fun `API_ENABLED=true and API_AUTH_MODE=bearer with valid tokens file loads Bearer config`() {
        val tokensPath = writeTokenFile(validBearerTokenYaml())
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "bearer",
                        "API_TOKENS_PATH" to tokensPath,
                    ),
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Bearer::class.java, config)
        val bearer = config as ApiAuthConfig.Bearer
        assertEquals(1, bearer.tokens.size)
    }

    @Test
    fun `API_AUTH_MODE=BEARER uppercase is accepted`() {
        val tokensPath = writeTokenFile(validBearerTokenYaml())
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "BEARER",
                        "API_TOKENS_PATH" to tokensPath,
                    ),
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Bearer::class.java, config)
    }

    // -------------------------------------------------------------------------
    // Bearer mode — fail-fast
    // -------------------------------------------------------------------------

    @Test
    fun `bearer mode with missing tokens file throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "bearer",
                        "API_TOKENS_PATH" to "/nonexistent/tokens.yaml",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("not found") || ex.message!!.contains("missing"), "Error: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // JWKS mode — valid
    // -------------------------------------------------------------------------

    @Test
    fun `valid JWKS mode returns Jwks config`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                        "API_JWKS_ISSUER" to "https://idp.example.com",
                        "API_JWKS_AUDIENCE" to "task-orchestrator-api",
                        "API_JWKS_ALGORITHMS" to "RS256,EdDSA",
                    ),
            )
        val config = loader.load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        val jwks = config as ApiAuthConfig.Jwks
        assertEquals("https://idp.example.com/.well-known/jwks.json", jwks.url)
        assertEquals("https://idp.example.com", jwks.issuer)
        assertEquals("task-orchestrator-api", jwks.audience)
        assertEquals(listOf("RS256", "EdDSA"), jwks.algorithms)
        assertEquals(300L, jwks.cacheTtlSeconds) // default
    }

    @Test
    fun `JWKS mode with custom cache TTL`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                        "API_JWKS_ISSUER" to "https://idp.example.com",
                        "API_JWKS_AUDIENCE" to "api",
                        "API_JWKS_ALGORITHMS" to "RS256",
                        "API_JWKS_CACHE_TTL_SECONDS" to "600",
                    ),
            )
        val config = loader.load() as ApiAuthConfig.Jwks
        assertEquals(600L, config.cacheTtlSeconds)
    }

    // -------------------------------------------------------------------------
    // JWKS mode — fail-fast
    // -------------------------------------------------------------------------

    @Test
    fun `JWKS mode missing API_JWKS_URL throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_ISSUER" to "https://idp.example.com",
                        "API_JWKS_AUDIENCE" to "api",
                        "API_JWKS_ALGORITHMS" to "RS256",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_URL"), "Error: ${ex.message}")
    }

    @Test
    fun `JWKS mode missing API_JWKS_ISSUER throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                        "API_JWKS_AUDIENCE" to "api",
                        "API_JWKS_ALGORITHMS" to "RS256",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_ISSUER"), "Error: ${ex.message}")
    }

    @Test
    fun `JWKS mode missing API_JWKS_AUDIENCE throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                        "API_JWKS_ISSUER" to "https://idp.example.com",
                        "API_JWKS_ALGORITHMS" to "RS256",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_AUDIENCE"), "Error: ${ex.message}")
    }

    @Test
    fun `JWKS mode missing API_JWKS_ALGORITHMS throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "jwks",
                        "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                        "API_JWKS_ISSUER" to "https://idp.example.com",
                        "API_JWKS_AUDIENCE" to "api",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_JWKS_ALGORITHMS"), "Error: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // Invalid mode values
    // -------------------------------------------------------------------------

    @Test
    fun `API_AUTH_MODE=none throws with explicit message`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "none",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("none") || ex.message!!.contains("not allowed"), "Error: ${ex.message}")
    }

    @Test
    fun `API_AUTH_MODE=invalid throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                        "API_AUTH_MODE" to "invalid-mode",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message != null, "Should have an error message")
    }

    @Test
    fun `API_AUTH_MODE unset when enabled throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        "API_ENABLED" to "true",
                    ),
            )
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(ex.message!!.contains("API_AUTH_MODE"), "Error: ${ex.message}")
    }
}

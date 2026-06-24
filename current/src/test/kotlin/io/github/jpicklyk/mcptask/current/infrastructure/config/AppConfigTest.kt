package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AppConfig.fromEnv] — verifies every env var parses with the correct type and
 * default, env-over-default precedence, the AGENT_CONFIG_DIR → user.dir fallback, and that invalid
 * numeric inputs degrade to defaults exactly as the original inline call sites did.
 *
 * Tests inject a fake resolver (a map lookup) so no JVM environment mutation is required.
 */
class AppConfigTest {
    /** Builds a resolver over a fixed map; unset keys return null. */
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    // ------------------------------------------------------------------
    // Defaults (everything unset)
    // ------------------------------------------------------------------

    @Test
    fun `unset environment yields documented defaults`() {
        val c = AppConfig.fromEnv { null }

        assertEquals("mcp-task-orchestrator-current", c.mcpServerName)
        assertEquals("stdio", c.mcpTransport)
        assertEquals("0.0.0.0", c.mcpHttpHost)
        assertEquals(3001, c.mcpHttpPort)

        assertEquals("data/current-tasks.db", c.databasePath)
        assertTrue(c.useFlyway)
        assertEquals("INFO", c.logLevel)
        assertNull(c.agentConfigDir)
        assertEquals(10, c.databaseMaxConnections)
        assertFalse(c.databaseShowSql)
        assertEquals(5000L, c.databaseBusyTimeoutMs)
        assertNull(c.databaseBusyTimeoutRaw)

        assertFalse(c.flywayRepair)

        assertFalse(c.apiAllowQueryTokenForSse)
        assertEquals(30, c.apiSseAuthCheckIntervalSeconds)
        assertEquals(1000, c.apiSseBufferSize)

        assertEquals("/run/secrets/api-tokens.yaml", c.apiTokensPath)

        // Redaction defaults to true (redact).
        assertTrue(c.apiRedactNoteAttribution)
        assertTrue(c.apiRedactActorProof)

        // Warn defaults to true.
        assertTrue(c.apiWarnOnClaimedAdvance)

        assertEquals(emptyList(), c.corsAllowedOrigins)
        assertEquals(listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"), c.corsAllowedMethods)
        assertEquals(listOf("Authorization", "Content-Type", "If-Match"), c.corsAllowedHeaders)
        assertEquals(listOf("ETag", "Last-Event-ID"), c.corsExposeHeaders)
        assertEquals(3600L, c.corsMaxAgeSeconds)
    }

    // ------------------------------------------------------------------
    // Precedence: env value overrides the default
    // ------------------------------------------------------------------

    @Test
    fun `set values override defaults with correct types`() {
        val c =
            AppConfig.fromEnv(
                env(
                    "MCP_SERVER_NAME" to "custom-name",
                    "MCP_TRANSPORT" to "HTTP",
                    "MCP_HTTP_HOST" to "127.0.0.1",
                    "MCP_HTTP_PORT" to "8080",
                    "DATABASE_PATH" to "/tmp/x.db",
                    "USE_FLYWAY" to "false",
                    "LOG_LEVEL" to "DEBUG",
                    "DATABASE_MAX_CONNECTIONS" to "25",
                    "DATABASE_SHOW_SQL" to "true",
                    "DATABASE_BUSY_TIMEOUT_MS" to "12000",
                    "FLYWAY_REPAIR" to "true",
                    "API_ALLOW_QUERY_TOKEN_FOR_SSE" to "true",
                    "API_SSE_AUTH_CHECK_INTERVAL_SECONDS" to "45",
                    "API_SSE_BUFFER_SIZE" to "2048",
                    "API_TOKENS_PATH" to "/secrets/custom.yaml",
                    "API_WARN_ON_CLAIMED_ADVANCE" to "false",
                    "CORS_MAX_AGE_SECONDS" to "7200",
                ),
            )

        assertEquals("custom-name", c.mcpServerName)
        // MCP_TRANSPORT is lowercased.
        assertEquals("http", c.mcpTransport)
        assertEquals("127.0.0.1", c.mcpHttpHost)
        assertEquals(8080, c.mcpHttpPort)
        assertEquals("/tmp/x.db", c.databasePath)
        assertFalse(c.useFlyway)
        assertEquals("DEBUG", c.logLevel)
        assertEquals(25, c.databaseMaxConnections)
        assertTrue(c.databaseShowSql)
        assertEquals(12000L, c.databaseBusyTimeoutMs)
        assertEquals("12000", c.databaseBusyTimeoutRaw)
        assertTrue(c.flywayRepair)
        assertTrue(c.apiAllowQueryTokenForSse)
        assertEquals(45, c.apiSseAuthCheckIntervalSeconds)
        assertEquals(2048, c.apiSseBufferSize)
        assertEquals("/secrets/custom.yaml", c.apiTokensPath)
        assertFalse(c.apiWarnOnClaimedAdvance)
        assertEquals(7200L, c.corsMaxAgeSeconds)
    }

    // ------------------------------------------------------------------
    // Numeric parsing: invalid input falls back to the default
    // ------------------------------------------------------------------

    @Test
    fun `invalid numeric port falls back to default`() {
        val c = AppConfig.fromEnv(env("MCP_HTTP_PORT" to "not-a-number"))
        assertEquals(3001, c.mcpHttpPort)
    }

    @Test
    fun `invalid max connections falls back to default`() {
        val c = AppConfig.fromEnv(env("DATABASE_MAX_CONNECTIONS" to "abc"))
        assertEquals(10, c.databaseMaxConnections)
    }

    @Test
    fun `invalid sse buffer size falls back to default`() {
        val c = AppConfig.fromEnv(env("API_SSE_BUFFER_SIZE" to "x"))
        assertEquals(1000, c.apiSseBufferSize)
    }

    @Test
    fun `invalid cors max age falls back to default`() {
        val c = AppConfig.fromEnv(env("CORS_MAX_AGE_SECONDS" to "abc"))
        assertEquals(3600L, c.corsMaxAgeSeconds)
    }

    @Test
    fun `busy timeout below floor is clamped to 100`() {
        val c = AppConfig.fromEnv(env("DATABASE_BUSY_TIMEOUT_MS" to "50"))
        assertEquals(100L, c.databaseBusyTimeoutMs)
        // Raw value is preserved unparsed for logging.
        assertEquals("50", c.databaseBusyTimeoutRaw)
    }

    @Test
    fun `busy timeout unparseable falls back to default`() {
        val c = AppConfig.fromEnv(env("DATABASE_BUSY_TIMEOUT_MS" to "nope"))
        assertEquals(5000L, c.databaseBusyTimeoutMs)
    }

    // ------------------------------------------------------------------
    // Redaction flag semantics: only literal "false" disables; default true
    // ------------------------------------------------------------------

    @Test
    fun `redaction flags only disabled by literal false`() {
        assertFalse(AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "false")).apiRedactNoteAttribution)
        assertFalse(AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "FALSE")).apiRedactNoteAttribution)
        assertFalse(AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "  false ")).apiRedactNoteAttribution)
        // Any non-"false" value keeps redaction on.
        assertTrue(AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "true")).apiRedactNoteAttribution)
        assertTrue(AppConfig.fromEnv(env("API_REDACT_NOTE_ATTRIBUTION" to "0")).apiRedactNoteAttribution)
        assertFalse(AppConfig.fromEnv(env("API_REDACT_ACTOR_PROOF" to "false")).apiRedactActorProof)
    }

    @Test
    fun `warn on claimed advance only disabled by literal false`() {
        assertFalse(AppConfig.fromEnv(env("API_WARN_ON_CLAIMED_ADVANCE" to "false")).apiWarnOnClaimedAdvance)
        assertFalse(AppConfig.fromEnv(env("API_WARN_ON_CLAIMED_ADVANCE" to "FALSE")).apiWarnOnClaimedAdvance)
        assertTrue(AppConfig.fromEnv(env("API_WARN_ON_CLAIMED_ADVANCE" to "true")).apiWarnOnClaimedAdvance)
    }

    // ------------------------------------------------------------------
    // allowQueryToken: only literal "true" enables (matches original ==)
    // ------------------------------------------------------------------

    @Test
    fun `allow query token only enabled by literal true`() {
        assertTrue(AppConfig.fromEnv(env("API_ALLOW_QUERY_TOKEN_FOR_SSE" to "true")).apiAllowQueryTokenForSse)
        assertTrue(AppConfig.fromEnv(env("API_ALLOW_QUERY_TOKEN_FOR_SSE" to "TRUE")).apiAllowQueryTokenForSse)
        assertFalse(AppConfig.fromEnv(env("API_ALLOW_QUERY_TOKEN_FOR_SSE" to "1")).apiAllowQueryTokenForSse)
        assertFalse(AppConfig.fromEnv(env("API_ALLOW_QUERY_TOKEN_FOR_SSE" to "yes")).apiAllowQueryTokenForSse)
    }

    // ------------------------------------------------------------------
    // CORS list parsing: trim + drop blanks
    // ------------------------------------------------------------------

    @Test
    fun `cors origins parse trims and drops blanks`() {
        val c =
            AppConfig.fromEnv(
                env("CORS_ALLOWED_ORIGINS" to " https://a.com , ,https://b.com  "),
            )
        assertEquals(listOf("https://a.com", "https://b.com"), c.corsAllowedOrigins)
    }

    @Test
    fun `cors methods override default list`() {
        val c = AppConfig.fromEnv(env("CORS_ALLOWED_METHODS" to "GET,POST"))
        assertEquals(listOf("GET", "POST"), c.corsAllowedMethods)
    }

    @Test
    fun `all-blank cors list parses to empty list preserving original semantics`() {
        // Preserves the ORIGINAL CorsConfig behavior: the default list applies only when the env var
        // is UNSET (null). An all-blank-but-present value parses to a non-null empty list, so the
        // default does NOT kick in — methods become empty.
        val c = AppConfig.fromEnv(env("CORS_ALLOWED_METHODS" to " , , "))
        assertEquals(emptyList(), c.corsAllowedMethods)

        // Whereas a fully UNSET value yields the default list.
        assertEquals(
            listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"),
            AppConfig.fromEnv { null }.corsAllowedMethods,
        )
    }

    // ------------------------------------------------------------------
    // API tokens path: blank/whitespace falls back to the default
    // ------------------------------------------------------------------

    @Test
    fun `blank api tokens path falls back to default`() {
        assertEquals(
            "/run/secrets/api-tokens.yaml",
            AppConfig.fromEnv(env("API_TOKENS_PATH" to "   ")).apiTokensPath,
        )
    }

    // ------------------------------------------------------------------
    // AGENT_CONFIG_DIR → user.dir fallback
    // ------------------------------------------------------------------

    @Test
    fun `agent config dir is captured raw and resolves with user-dir fallback`() {
        val set = AppConfig.fromEnv(env("AGENT_CONFIG_DIR" to "/project"))
        assertEquals("/project", set.agentConfigDir)
        assertEquals("/project", AppConfig.resolveConfigBaseDir(set.agentConfigDir))

        val unset = AppConfig.fromEnv { null }
        assertNull(unset.agentConfigDir)
        // Fallback equals the JVM user.dir property.
        assertEquals(System.getProperty("user.dir"), AppConfig.resolveConfigBaseDir(unset.agentConfigDir))
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

/**
 * Immutable, typed snapshot of every environment variable the application reads.
 *
 * Historically each call site read its own environment variable inline via [System.getenv],
 * scattering defaults and parsing rules across the codebase (routes, services, config loaders,
 * the database layer). [AppConfig] consolidates those reads into a single startup snapshot so:
 *
 *  - the environment is read **once** at startup ([fromEnv]) and never mutated afterward,
 *  - defaults and precedence live in **one** place,
 *  - call sites receive typed values through constructors/parameters instead of reaching for
 *    [System.getenv], which makes them trivially testable without mutating the JVM environment.
 *
 * This is a **structural** refactor: every field below preserves the exact env-var name, default,
 * and parsing rule that previously lived at the call site. See the per-field docs for the original
 * source location.
 *
 * Validation that throws on misconfiguration (the REST API auth config and the actor-authentication
 * config) intentionally stays in its existing loader classes ([ApiAuthConfigLoader],
 * [YamlActorAuthenticationConfigService]); those already accept an injectable `envResolver`. The
 * composition root passes [envResolver] into them so they read from the same source as this
 * snapshot, preserving their fail-fast behavior unchanged.
 *
 * @property envResolver the raw resolver used to build this snapshot, retained so loaders that do
 *   their own validated parsing can be constructed against the same environment source.
 */
data class AppConfig(
    // ---- MCP server / transport (CurrentMcpServer) ----
    val mcpServerName: String,
    val mcpTransport: String,
    val mcpHttpHost: String,
    val mcpHttpPort: Int,
    // ---- Database (DatabaseConfig / DatabaseManager) ----
    val databasePath: String,
    val useFlyway: Boolean,
    val logLevel: String,
    val agentConfigDir: String?,
    val databaseMaxConnections: Int,
    val databaseShowSql: Boolean,
    val databaseBusyTimeoutMs: Long,
    /** Raw, unparsed value of DATABASE_BUSY_TIMEOUT_MS — retained so DatabaseManager can reproduce
     *  its existing per-case logging (unparseable / below-floor / set / unset) without re-reading env. */
    val databaseBusyTimeoutRaw: String?,
    // ---- Flyway (FlywayDatabaseSchemaManager) ----
    val flywayRepair: Boolean,
    // ---- REST API: SSE / events ----
    val apiAllowQueryTokenForSse: Boolean,
    val apiSseAuthCheckIntervalSeconds: Int,
    val apiSseBufferSize: Int,
    // ---- REST API: bearer tokens ----
    val apiTokensPath: String,
    // ---- REST API: redaction (AttributionRedactor / TransitionRoutes) ----
    val apiRedactNoteAttribution: Boolean,
    val apiRedactActorProof: Boolean,
    // ---- REST API: advance warning (ItemWriteRoutes) ----
    val apiWarnOnClaimedAdvance: Boolean,
    // ---- CORS (CorsConfig) ----
    val corsAllowedOrigins: List<String>,
    val corsAllowedMethods: List<String>,
    val corsAllowedHeaders: List<String>,
    val corsExposeHeaders: List<String>,
    val corsMaxAgeSeconds: Long,
    // ---- Raw env resolver (for validated loaders that parse env themselves) ----
    val envResolver: (String) -> String?,
) {
    companion object {
        // Bearer token store default — mirrors CurrentMcpServer.resolveApiWiring.
        internal const val DEFAULT_API_TOKENS_PATH = "/run/secrets/api-tokens.yaml"

        // Default lists for CORS — mirror CorsConfig.configureCors.
        internal val DEFAULT_CORS_METHODS = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
        internal val DEFAULT_CORS_HEADERS = listOf("Authorization", "Content-Type", "If-Match")
        internal val DEFAULT_CORS_EXPOSE_HEADERS = listOf("ETag", "Last-Event-ID")

        /**
         * Reads the environment once and returns a typed snapshot.
         *
         * @param env the environment-variable resolver. Defaults to [System.getenv]; tests inject a
         *   fake (e.g. a map lookup) to avoid mutating the JVM environment.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): AppConfig =
            AppConfig(
                // MCP server / transport — preserves CurrentMcpServer defaults.
                mcpServerName = env("MCP_SERVER_NAME") ?: "mcp-task-orchestrator-current",
                mcpTransport = env("MCP_TRANSPORT")?.lowercase() ?: "stdio",
                mcpHttpHost = env("MCP_HTTP_HOST") ?: "0.0.0.0",
                mcpHttpPort = env("MCP_HTTP_PORT")?.toIntOrNull() ?: 3001,
                // Database — preserves DatabaseConfig defaults exactly.
                databasePath = env("DATABASE_PATH") ?: "data/current-tasks.db",
                useFlyway = env("USE_FLYWAY")?.toBoolean() ?: true,
                logLevel = env("LOG_LEVEL") ?: "INFO",
                agentConfigDir = env("AGENT_CONFIG_DIR"),
                databaseMaxConnections = env("DATABASE_MAX_CONNECTIONS")?.toIntOrNull() ?: 10,
                databaseShowSql = env("DATABASE_SHOW_SQL")?.toBoolean() ?: false,
                databaseBusyTimeoutMs = resolveBusyTimeoutMs(env("DATABASE_BUSY_TIMEOUT_MS")),
                databaseBusyTimeoutRaw = env("DATABASE_BUSY_TIMEOUT_MS"),
                // Flyway.
                flywayRepair = env("FLYWAY_REPAIR")?.toBoolean() ?: false,
                // REST API SSE / events.
                apiAllowQueryTokenForSse = env("API_ALLOW_QUERY_TOKEN_FOR_SSE")?.lowercase() == "true",
                apiSseAuthCheckIntervalSeconds = env("API_SSE_AUTH_CHECK_INTERVAL_SECONDS")?.toIntOrNull() ?: 30,
                apiSseBufferSize = env("API_SSE_BUFFER_SIZE")?.toIntOrNull() ?: 1000,
                // REST API bearer tokens.
                apiTokensPath =
                    env("API_TOKENS_PATH")?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_API_TOKENS_PATH,
                // REST API redaction — default true (redact); only the literal "false" disables.
                apiRedactNoteAttribution = parseRedactFlag(env("API_REDACT_NOTE_ATTRIBUTION")),
                apiRedactActorProof = parseRedactFlag(env("API_REDACT_ACTOR_PROOF")),
                // REST API advance warning — default true; only the literal "false" disables.
                apiWarnOnClaimedAdvance = env("API_WARN_ON_CLAIMED_ADVANCE")?.lowercase() != "false",
                // CORS — comma-separated lists, blank entries dropped; defaults mirror CorsConfig.
                corsAllowedOrigins = parseCsv(env("CORS_ALLOWED_ORIGINS")) ?: emptyList(),
                corsAllowedMethods = parseCsv(env("CORS_ALLOWED_METHODS")) ?: DEFAULT_CORS_METHODS,
                corsAllowedHeaders = parseCsv(env("CORS_ALLOWED_HEADERS")) ?: DEFAULT_CORS_HEADERS,
                corsExposeHeaders = parseCsv(env("CORS_EXPOSE_HEADERS")) ?: DEFAULT_CORS_EXPOSE_HEADERS,
                corsMaxAgeSeconds = env("CORS_MAX_AGE_SECONDS")?.trim()?.toLongOrNull() ?: 3600L,
                envResolver = env,
            )

        /**
         * Resolves the directory that contains the `.taskorchestrator/` config folder, applying the
         * canonical `AGENT_CONFIG_DIR → user.dir` fallback. Preserves the behavior previously
         * duplicated in [YamlWorkItemSchemaService.resolveDefaultConfigPath] and
         * [DefaultJwksKeySetProvider].
         */
        fun resolveConfigBaseDir(agentConfigDir: String?): String = agentConfigDir ?: System.getProperty("user.dir")

        // ---- Pure parsing helpers (preserve original call-site semantics exactly) ----

        /**
         * Redaction flags ([AttributionRedactor], [TransitionRoutes]) default to `true` and are only
         * turned off by the literal string `"false"` (case-insensitive, trimmed).
         */
        internal fun parseRedactFlag(raw: String?): Boolean = raw?.trim()?.lowercase()?.let { it != "false" } ?: true

        /**
         * Comma-separated env list → trimmed, blank-filtered list, or `null` when unset so callers
         * can apply their own default. Mirrors the CorsConfig parsing.
         */
        internal fun parseCsv(raw: String?): List<String>? = raw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        /**
         * Resolves DATABASE_BUSY_TIMEOUT_MS: unset → default 5000 ms, unparseable → default,
         * below the 100 ms floor → 100 ms. Identical logic to the former
         * `DatabaseConfig.resolveBusyTimeoutMs`.
         */
        internal fun resolveBusyTimeoutMs(rawValue: String?): Long {
            rawValue ?: return DEFAULT_BUSY_TIMEOUT_MS
            val parsed = rawValue.toLongOrNull() ?: return DEFAULT_BUSY_TIMEOUT_MS
            return if (parsed < MIN_BUSY_TIMEOUT_MS) MIN_BUSY_TIMEOUT_MS else parsed
        }

        internal const val DEFAULT_BUSY_TIMEOUT_MS = 5000L
        internal const val MIN_BUSY_TIMEOUT_MS = 100L
    }
}

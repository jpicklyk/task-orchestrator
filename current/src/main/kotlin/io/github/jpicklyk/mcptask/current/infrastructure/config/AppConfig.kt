package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.slf4j.LoggerFactory

/**
 * `FTS_INTEGRITY`: whether startup runs the per-table FTS5 `integrity-check` (cost grows with the indexed bytes:
 * about 2.6 s at 100k notes). [OFF] skips only that check; the FTS inventory check always runs.
 */
enum class FtsIntegrityMode {
    STARTUP,
    OFF,
    ;

    companion object {
        private val logger = LoggerFactory.getLogger(FtsIntegrityMode::class.java)

        /**
         * Parses `FTS_INTEGRITY` (`startup` | `off`, case-insensitive, trimmed). Unset returns [STARTUP] silently; any
         * other value logs a WARN naming the variable and the raw value, then returns [STARTUP] (the [EnvBoolean.parse]
         * convention: a bad value never fails startup).
         */
        fun parse(raw: String?): FtsIntegrityMode {
            if (raw == null) return STARTUP
            return when (raw.trim().lowercase()) {
                "startup" -> STARTUP
                "off" -> OFF
                else -> {
                    logger.warn("Environment variable 'FTS_INTEGRITY' has unrecognized value '{}'; using default startup", raw)
                    STARTUP
                }
            }
        }
    }
}

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
 * Validation that throws on misconfiguration (the REST API auth config) intentionally stays in its
 * existing loader class ([io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfigLoader]);
 * it already accepts an injectable `envResolver`. The
 * composition root passes [envResolver] into it so it reads from the same source as this
 * snapshot, preserving its fail-fast behavior unchanged.
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
    /** `MCP_ALLOWED_HOSTS` — raw, unvalidated CSV of extra hosts for the Host-header allowlist;
     *  default empty (loopback-only). Validation and matching semantics live in
     *  `installHostAllowlist` (interfaces.mcp). */
    val mcpAllowedHosts: List<String>,
    /** `MCP_ALLOW_UNAUTHENTICATED` -- the V9 opt-in: with `MCP_TRANSPORT=http` and the REST API disabled, `/mcp` serves as
     *  the local principal only when this is true; otherwise startup fails. Default false; parsed with
     *  [EnvBoolean.require], so an unrecognized value fails startup. Ignored by stdio and by a credential API mode. */
    val mcpAllowUnauthenticated: Boolean = false,
    // ---- Database (DatabaseManager) ----
    val databasePath: String,
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
    /** `DB_COMPACT_ON_UPGRADE` -- when true (default), [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager.updateSchema]
     *  runs the one-time post-V17 startup compaction ([io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupCompaction])
     *  after a successful Flyway-mode schema update. Set to `false` to opt out (e.g. to run the
     *  offline compaction runbook manually instead). */
    val dbCompactOnUpgrade: Boolean = true,
    /** UPGRADE_BACKUP -- when true (default), a 3.x database file (schema version <= 17) is copied to <db>.pre-4.0.<UTC stamp>.bak and verified before the first 4.0 migration; a failed copy fails startup (Reason.UPGRADE_BACKUP). alse skips with one WARN. */
    val upgradeBackup: Boolean = true,
    /** `USE_FLYWAY` is ignored (Flyway is the only schema path); true when it is set so startup can warn. Not a parsed boolean. */
    val ignoredUseFlywaySet: Boolean = false,
    /** Raw `SCHEMA_MODE` (`migrate` | `validate`); parsed by `SchemaMode.parse` where the schema manager is built, so a bad value still fails startup there. */
    val schemaModeRaw: String? = null,
    /** Raw `MCP_TOOL_PROFILE` (`knowledge` | `orchestration`); parsed by `ToolProfile.parse` at startup (infrastructure must not import the interfaces layer). Absent/blank means orchestration. */
    val mcpToolProfileRaw: String? = null,
    /** `FTS_INTEGRITY` (`startup` default | `off`) -- whether startup runs the FTS5 integrity check ([FtsIntegrityMode]). */
    val ftsIntegrity: FtsIntegrityMode = FtsIntegrityMode.STARTUP,
    /** `RESOURCE_LEASES_ENFORCED` -- the lease kill switch, read ONCE at startup (restart to change). Default true; false skips the lease gate and acquisition on every advance. */
    val resourceLeasesEnforced: Boolean = true,
    /** `RETENTION_CALL_LOG_DAYS` / `RETENTION_EVENTS_DAYS` / `RETENTION_AUDIT_EVENTS_DAYS` / `RETENTION_LEASE_HISTORY_DAYS` ([RetentionSettings.parse]); an invalid value fails startup. */
    val retention: RetentionSettings = RetentionSettings.DEFAULT,
    // ---- REST API: SSE / events ----
    val apiAllowQueryTokenForSse: Boolean,
    val apiSseAuthCheckIntervalSeconds: Int,
    val apiSseBufferSize: Int,
    // ---- REST API: bearer tokens ----
    val apiTokensPath: String,
    // ---- REST API: note attribution redaction ----
    val apiRedactNoteAttribution: Boolean,
    // ---- CORS (CorsConfig) ----
    val corsAllowedOrigins: List<String>,
    val corsAllowedMethods: List<String>,
    val corsAllowedHeaders: List<String>,
    val corsExposeHeaders: List<String>,
    val corsMaxAgeSeconds: Long,
    // ---- Readiness (ReadinessMarker / Docker HEALTHCHECK) ----
    val readinessFile: String,
    // ---- Raw env resolver (for validated loaders that parse env themselves) ----
    val envResolver: (String) -> String?,
) {
    companion object {
        // Bearer token store default — mirrors CurrentMcpServer.resolveApiWiring.
        internal const val DEFAULT_API_TOKENS_PATH = "/run/secrets/api-tokens.yaml"

        // Readiness marker default — mirrors the Dockerfile's READINESS_FILE default.
        internal const val DEFAULT_READINESS_FILE = "/tmp/mcp-task-orchestrator.ready"

        // Default lists for CORS — mirror CorsConfig.configureCors.
        internal val DEFAULT_CORS_METHODS = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
        internal val DEFAULT_CORS_HEADERS = listOf("Authorization", "Content-Type", "If-Match", "X-Client-Host")
        internal val DEFAULT_CORS_EXPOSE_HEADERS = listOf("ETag", "Last-Event-ID", "Retry-After", "X-Req-Id")

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
                mcpAllowedHosts = parseCsv(env("MCP_ALLOWED_HOSTS")) ?: emptyList(),
                // V9 opt-in -- fail fast on an unrecognized value (EnvBoolean.require), like API_ENABLED.
                mcpAllowUnauthenticated =
                    EnvBoolean.require("MCP_ALLOW_UNAUTHENTICATED", env("MCP_ALLOW_UNAUTHENTICATED"), false),
                // Database.
                databasePath = env("DATABASE_PATH") ?: "data/current-tasks.db",
                logLevel = env("LOG_LEVEL") ?: "INFO",
                agentConfigDir = env("AGENT_CONFIG_DIR"),
                databaseMaxConnections = env("DATABASE_MAX_CONNECTIONS")?.toIntOrNull() ?: 10,
                databaseShowSql = EnvBoolean.parse("DATABASE_SHOW_SQL", env("DATABASE_SHOW_SQL"), false),
                databaseBusyTimeoutMs = resolveBusyTimeoutMs(env("DATABASE_BUSY_TIMEOUT_MS")),
                databaseBusyTimeoutRaw = env("DATABASE_BUSY_TIMEOUT_MS"),
                // Flyway.
                flywayRepair = EnvBoolean.parse("FLYWAY_REPAIR", env("FLYWAY_REPAIR"), false),
                dbCompactOnUpgrade = EnvBoolean.parse("DB_COMPACT_ON_UPGRADE", env("DB_COMPACT_ON_UPGRADE"), true),
                upgradeBackup = EnvBoolean.parse("UPGRADE_BACKUP", env("UPGRADE_BACKUP"), true),
                ignoredUseFlywaySet = env("USE_FLYWAY") != null,
                schemaModeRaw = env("SCHEMA_MODE"),
                mcpToolProfileRaw = env("MCP_TOOL_PROFILE"),
                ftsIntegrity = FtsIntegrityMode.parse(env("FTS_INTEGRITY")),
                resourceLeasesEnforced = EnvBoolean.parse("RESOURCE_LEASES_ENFORCED", env("RESOURCE_LEASES_ENFORCED"), true),
                // Retention windows -- an invalid value throws IllegalArgumentException and fails startup.
                retention = RetentionSettings.parse(env),
                // REST API SSE / events.
                apiAllowQueryTokenForSse =
                    EnvBoolean.parse("API_ALLOW_QUERY_TOKEN_FOR_SSE", env("API_ALLOW_QUERY_TOKEN_FOR_SSE"), false),
                apiSseAuthCheckIntervalSeconds = env("API_SSE_AUTH_CHECK_INTERVAL_SECONDS")?.toIntOrNull() ?: 30,
                apiSseBufferSize = env("API_SSE_BUFFER_SIZE")?.toIntOrNull() ?: 1000,
                // REST API bearer tokens.
                apiTokensPath =
                    env("API_TOKENS_PATH")?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_API_TOKENS_PATH,
                // REST API redaction — default true (redact); unified EnvBoolean vocabulary
                // (true/1/yes vs false/0/no) now disables via "0"/"no" too, not only "false".
                apiRedactNoteAttribution =
                    EnvBoolean.parse("API_REDACT_NOTE_ATTRIBUTION", env("API_REDACT_NOTE_ATTRIBUTION"), true),
                // CORS — comma-separated lists, blank entries dropped; defaults mirror CorsConfig.
                corsAllowedOrigins = parseCsv(env("CORS_ALLOWED_ORIGINS")) ?: emptyList(),
                corsAllowedMethods = parseCsv(env("CORS_ALLOWED_METHODS")) ?: DEFAULT_CORS_METHODS,
                corsAllowedHeaders = parseCsv(env("CORS_ALLOWED_HEADERS")) ?: DEFAULT_CORS_HEADERS,
                corsExposeHeaders = parseCsv(env("CORS_EXPOSE_HEADERS")) ?: DEFAULT_CORS_EXPOSE_HEADERS,
                corsMaxAgeSeconds = env("CORS_MAX_AGE_SECONDS")?.trim()?.toLongOrNull() ?: 3600L,
                // Readiness marker — plain string, no boolean parsing involved.
                readinessFile = env("READINESS_FILE") ?: DEFAULT_READINESS_FILE,
                envResolver = env,
            )

        /**
         * Resolves the directory that contains the `.taskorchestrator/` config folder, applying the
         * canonical `AGENT_CONFIG_DIR → user.dir` fallback. The composition root builds the base
         * directory ONCE from this and hands it to every consumer.
         */
        fun resolveConfigBaseDir(agentConfigDir: String?): String = agentConfigDir ?: System.getProperty("user.dir")

        // ---- Pure parsing helpers (preserve original call-site semantics exactly) ----

        /**
         * Comma-separated env list → trimmed, blank-filtered list, or `null` when unset so callers
         * can apply their own default. Mirrors the CorsConfig parsing.
         */
        internal fun parseCsv(raw: String?): List<String>? = raw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        /**
         * Resolves DATABASE_BUSY_TIMEOUT_MS: unset → default 5000 ms, unparseable → default,
         * below the 100 ms floor → 100 ms.
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

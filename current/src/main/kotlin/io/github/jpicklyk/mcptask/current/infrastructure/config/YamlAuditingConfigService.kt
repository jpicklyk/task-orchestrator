package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.AuditingConfig
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Path

/**
 * YAML-backed loader for the `auditing:` section of `.taskorchestrator/config.yaml`.
 *
 * The config path is resolved using the same `AGENT_CONFIG_DIR` environment-variable
 * pattern as [YamlNoteSchemaService].
 *
 * Expected YAML structure:
 * ```yaml
 * auditing:
 *   enabled: true
 *   degraded_mode_policy: accept-cached   # accept-cached (default) | accept-self-reported | reject
 *   verifier:
 *     type: jwks          # "jwks" or "noop" (default: noop)
 *     oidc_discovery: "https://accounts.example.com/.well-known/openid-configuration"
 *     jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
 *     jwks_path: "/etc/keys/jwks.json"
 *     issuer: "https://accounts.example.com"
 *     audience: "mcp-task-orchestrator"
 *     algorithms:
 *       - RS256
 *       - ES256
 *     cache_ttl_seconds: 300
 *     require_sub_match: true
 * ```
 *
 * For the `jwks` type, exactly one of `oidc_discovery`, `jwks_uri`, or `jwks_path` should be
 * provided. If none is present, a warning is added and the verifier falls back to [VerifierConfig.Noop].
 *
 * If the config file is missing, the `auditing:` section is absent, or any exception occurs
 * during parsing, [AuditingConfig] defaults are returned (`enabled=true`, [VerifierConfig.Noop]).
 */
class YamlAuditingConfigService(
    private val configPath: Path = YamlNoteSchemaService.resolveDefaultConfigPath()
) {
    private val logger = LoggerFactory.getLogger(YamlAuditingConfigService::class.java)

    private data class LoadResult(
        val config: AuditingConfig,
        val warnings: List<String>
    )

    /** Lazily loaded result containing the parsed config and any parse warnings. */
    private val loadResult: LoadResult by lazy { loadConfig() }

    /** Returns the parsed [AuditingConfig]; defaults are returned on any error. */
    fun getConfig(): AuditingConfig = loadResult.config

    /** Returns warnings collected during config parsing (empty list if none). */
    fun getWarnings(): List<String> = loadResult.warnings

    @Suppress("UNCHECKED_CAST")
    private fun loadConfig(): LoadResult {
        val warnings = mutableListOf<String>()

        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; using default auditing config", configPath)
            return LoadResult(AuditingConfig(), warnings)
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root =
                    yaml.load<Map<String, Any>>(reader)
                        ?: return@use LoadResult(AuditingConfig(), warnings)

                val auditingSection =
                    root["auditing"] as? Map<String, Any>
                        ?: run {
                            logger.debug("No 'auditing' section in config; using defaults")
                            return@use LoadResult(AuditingConfig(), warnings)
                        }

                val enabled = (auditingSection["enabled"] as? Boolean) ?: true

                val verifierSection = auditingSection["verifier"] as? Map<String, Any>
                val verifier =
                    if (verifierSection != null) {
                        parseVerifier(verifierSection, warnings)
                    } else {
                        VerifierConfig.Noop
                    }

                val degradedModePolicy = parseDegradedModePolicy(auditingSection, warnings)

                LoadResult(
                    AuditingConfig(
                        enabled = enabled,
                        verifier = verifier,
                        degradedModePolicy = degradedModePolicy
                    ),
                    warnings
                )
            }
        } catch (e: Exception) {
            val msg = "Failed to load auditing config from '$configPath': ${e.message}"
            warnings.add(msg)
            logger.warn(msg)
            LoadResult(AuditingConfig(), warnings)
        }
    }

    private fun parseDegradedModePolicy(
        auditingSection: Map<String, Any>,
        warnings: MutableList<String>
    ): DegradedModePolicy {
        val raw = auditingSection["degraded_mode_policy"] as? String ?: return DegradedModePolicy.ACCEPT_CACHED
        val parsed = DegradedModePolicy.fromConfigString(raw)
        if (parsed == null) {
            val msg =
                "Unknown auditing.degraded_mode_policy '$raw'; " +
                    "valid values: accept-cached, accept-self-reported, reject. Defaulting to accept-cached."
            warnings.add(msg)
            logger.warn(msg)
            return DegradedModePolicy.ACCEPT_CACHED
        }
        return parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVerifier(
        verifierMap: Map<String, Any>,
        warnings: MutableList<String>
    ): VerifierConfig {
        val type = (verifierMap["type"] as? String)?.lowercase()

        return when (type) {
            null, "noop" -> VerifierConfig.Noop

            "jwks" -> {
                val oidcDiscovery = verifierMap["oidc_discovery"] as? String
                val jwksUri = verifierMap["jwks_uri"] as? String
                val jwksPath = verifierMap["jwks_path"] as? String

                if (oidcDiscovery == null && jwksUri == null && jwksPath == null) {
                    val msg =
                        "auditing.verifier type 'jwks' requires one of: " +
                            "oidc_discovery, jwks_uri, or jwks_path; falling back to Noop"
                    warnings.add(msg)
                    logger.warn(msg)
                    return VerifierConfig.Noop
                }

                val issuer = verifierMap["issuer"] as? String
                val audience = verifierMap["audience"] as? String

                val rawAlgorithms = verifierMap["algorithms"]
                val algorithms: List<String> =
                    when (rawAlgorithms) {
                        is List<*> -> rawAlgorithms.filterIsInstance<String>()
                        else -> emptyList()
                    }

                val cacheTtlSeconds =
                    when (val raw = verifierMap["cache_ttl_seconds"]) {
                        is Int -> raw.toLong()
                        is Long -> raw
                        is Number -> raw.toLong()
                        else -> 300L
                    }

                val requireSubMatch = (verifierMap["require_sub_match"] as? Boolean) ?: true

                val staleOnError = (verifierMap["stale_on_error"] as? Boolean) ?: true

                VerifierConfig.Jwks(
                    oidcDiscovery = oidcDiscovery,
                    jwksUri = jwksUri,
                    jwksPath = jwksPath,
                    issuer = issuer,
                    audience = audience,
                    algorithms = algorithms,
                    cacheTtlSeconds = cacheTtlSeconds,
                    requireSubMatch = requireSubMatch,
                    staleOnError = staleOnError
                )
            }

            else -> {
                val msg = "Unknown auditing.verifier type '$type'; falling back to Noop"
                warnings.add(msg)
                logger.warn(msg)
                VerifierConfig.Noop
            }
        }
    }
}

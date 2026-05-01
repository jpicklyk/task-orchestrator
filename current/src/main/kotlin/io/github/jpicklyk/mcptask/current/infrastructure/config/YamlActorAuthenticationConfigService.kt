package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Path

/**
 * YAML-backed loader for the `actor_authentication:` section of `.taskorchestrator/config.yaml`.
 *
 * The config path is resolved using the same `AGENT_CONFIG_DIR` environment-variable
 * pattern as [YamlNoteSchemaService].
 *
 * Expected YAML structure:
 * ```yaml
 * actor_authentication:
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
 * If the config file is missing, the `actor_authentication:` section is absent, or any exception occurs
 * during parsing, [ActorAuthenticationConfig] defaults are returned (`enabled=true`, [VerifierConfig.Noop]).
 *
 * ## `DEGRADED_MODE_POLICY` environment variable
 *
 * When the `DEGRADED_MODE_POLICY` environment variable is set it overrides the YAML value:
 * - Valid values (case-insensitive): `accept-cached`, `accept-self-reported`, `reject`
 * - If set to an invalid value a [IllegalArgumentException] is thrown at construction time so the
 *   misconfiguration is surfaced immediately rather than silently falling back to a default.
 * - If unset, the YAML value is used (then the coded default [DegradedModePolicy.ACCEPT_CACHED]).
 *
 * @param configPath Path to the `.taskorchestrator/config.yaml` file.
 * @param envResolver Injectable resolver for environment variables; defaults to [System::getenv].
 *   Tests inject a fake to avoid mutating the JVM environment.
 */
class YamlActorAuthenticationConfigService(
    private val configPath: Path = YamlNoteSchemaService.resolveDefaultConfigPath(),
    private val envResolver: (String) -> String? = System::getenv
) {
    private val logger = LoggerFactory.getLogger(YamlActorAuthenticationConfigService::class.java)

    private data class LoadResult(
        val config: ActorAuthenticationConfig,
        val warnings: List<String>
    )

    /** Lazily loaded result containing the parsed config and any parse warnings. */
    private val loadResult: LoadResult by lazy { loadConfig() }

    /** Returns the parsed [ActorAuthenticationConfig]; defaults are returned on any error. */
    fun getConfig(): ActorAuthenticationConfig = loadResult.config

    /** Returns warnings collected during config parsing (empty list if none). */
    fun getWarnings(): List<String> = loadResult.warnings

    private fun loadConfig(): LoadResult {
        // --- Env-var override (evaluated eagerly so invalid values fail at startup) ---
        val envPolicy = resolveEnvDegradedModePolicy()

        val yamlResult = loadYamlConfig()

        val finalConfig =
            if (envPolicy != null) {
                logger.info("DEGRADED_MODE_POLICY env var overrides YAML: {}", envPolicy.toConfigString())
                yamlResult.config.copy(degradedModePolicy = envPolicy)
            } else {
                yamlResult.config
            }

        return LoadResult(finalConfig, yamlResult.warnings)
    }

    /**
     * Reads and validates the `DEGRADED_MODE_POLICY` environment variable.
     *
     * @return the parsed [DegradedModePolicy] if the env var is set, or `null` if unset.
     * @throws IllegalArgumentException if the env var is set but not a recognised value.
     */
    private fun resolveEnvDegradedModePolicy(): DegradedModePolicy? {
        val raw = envResolver("DEGRADED_MODE_POLICY") ?: return null
        return DegradedModePolicy.fromConfigString(raw)
            ?: throw IllegalArgumentException(
                "DEGRADED_MODE_POLICY env var must be one of: " +
                    "accept-cached, accept-self-reported, reject. Got: '$raw'"
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadYamlConfig(): LoadResult {
        val warnings = mutableListOf<String>()
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; using default actor_authentication config", configPath)
            return LoadResult(ActorAuthenticationConfig(), warnings)
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root =
                    yaml.load<Map<String, Any>>(reader)
                        ?: return@use LoadResult(ActorAuthenticationConfig(), warnings)

                // Hard cut: legacy auditing: key produces a clear migration error
                if (root.containsKey("auditing")) {
                    throw IllegalArgumentException(
                        "Unknown top-level config key 'auditing:'. " +
                            "Did you mean 'actor_authentication:'? See CHANGELOG for migration."
                    )
                }

                val actorAuthSection =
                    root["actor_authentication"] as? Map<String, Any>
                        ?: run {
                            logger.debug("No 'actor_authentication' section in config; using defaults")
                            return@use LoadResult(ActorAuthenticationConfig(), warnings)
                        }

                val enabled = (actorAuthSection["enabled"] as? Boolean) ?: true

                val verifierSection = actorAuthSection["verifier"] as? Map<String, Any>
                val verifier =
                    if (verifierSection != null) {
                        parseVerifier(verifierSection, warnings)
                    } else {
                        VerifierConfig.Noop
                    }

                val degradedModePolicy = parseDegradedModePolicy(actorAuthSection, warnings)

                LoadResult(
                    ActorAuthenticationConfig(
                        enabled = enabled,
                        verifier = verifier,
                        degradedModePolicy = degradedModePolicy
                    ),
                    warnings
                )
            }
        } catch (e: IllegalArgumentException) {
            // Config constraint violations (e.g. mutual-exclusion, legacy key) are surfaced immediately
            throw e
        } catch (e: Exception) {
            val msg = "Failed to load actor_authentication config from '$configPath': ${e.message}"
            warnings.add(msg)
            logger.warn(msg)
            LoadResult(ActorAuthenticationConfig(), warnings)
        }
    }

    private fun parseDegradedModePolicy(
        actorAuthSection: Map<String, Any>,
        warnings: MutableList<String>
    ): DegradedModePolicy {
        val raw = actorAuthSection["degraded_mode_policy"] as? String ?: return DegradedModePolicy.ACCEPT_CACHED
        val parsed = DegradedModePolicy.fromConfigString(raw)
        if (parsed == null) {
            val msg =
                "Unknown actor_authentication.degraded_mode_policy '$raw'; " +
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

                val rawDidAllowlist = verifierMap["did_allowlist"]
                val didAllowlist: List<String> =
                    when (rawDidAllowlist) {
                        is List<*> -> rawDidAllowlist.filterIsInstance<String>()
                        else -> emptyList()
                    }
                val didPattern = verifierMap["did_pattern"] as? String
                val didStrictRelationship = (verifierMap["did_strict_relationship"] as? Boolean) ?: true
                val didLooseKidMatch = (verifierMap["did_loose_kid_match"] as? Boolean) ?: true

                val isDidTrust = didAllowlist.isNotEmpty() || didPattern != null
                val isStaticJwks = oidcDiscovery != null || jwksUri != null || jwksPath != null

                // Mutual exclusion: DID-trust and static-JWKS fields cannot coexist
                if (isDidTrust && isStaticJwks) {
                    val conflicting =
                        buildList {
                            if (oidcDiscovery != null) add("oidc_discovery")
                            if (jwksUri != null) add("jwks_uri")
                            if (jwksPath != null) add("jwks_path")
                        }.joinToString(", ")
                    throw IllegalArgumentException(
                        "actor_authentication.verifier: DID-trust mode (did_allowlist/did_pattern) is mutually exclusive " +
                            "with static JWKS fields; conflicting fields: $conflicting"
                    )
                }

                // Neither DID trust nor static JWKS configured — no key source available
                if (!isDidTrust && !isStaticJwks) {
                    val msg =
                        "actor_authentication.verifier type 'jwks' requires one of: " +
                            "oidc_discovery, jwks_uri, jwks_path (static JWKS mode), " +
                            "or did_allowlist/did_pattern (DID-trust mode); falling back to Noop"
                    warnings.add(msg)
                    logger.warn(msg)
                    return VerifierConfig.Noop
                }

                // When static JWKS mode, enforce "exactly one source" hard error
                if (isStaticJwks) {
                    val sourcesSet = listOfNotNull(oidcDiscovery, jwksUri, jwksPath)
                    if (sourcesSet.size > 1) {
                        val provided =
                            buildList {
                                if (oidcDiscovery != null) add("oidc_discovery")
                                if (jwksUri != null) add("jwks_uri")
                                if (jwksPath != null) add("jwks_path")
                            }.joinToString(", ")
                        throw IllegalArgumentException(
                            "actor_authentication.verifier type 'jwks' requires exactly one of oidc_discovery, " +
                                "jwks_uri, or jwks_path; multiple were provided: $provided"
                        )
                    }
                }

                val issuer = verifierMap["issuer"] as? String
                val audience = verifierMap["audience"] as? String

                val rawAlgorithms = verifierMap["algorithms"]
                val algorithms: List<String> =
                    when (rawAlgorithms) {
                        is List<*> -> rawAlgorithms.filterIsInstance<String>()
                        else -> emptyList()
                    }

                // algorithms is required under type: jwks — no implicit default list
                if (algorithms.isEmpty()) {
                    throw IllegalArgumentException(
                        "actor_authentication.verifier type 'jwks' requires a non-empty 'algorithms' allowlist; " +
                            "supported values include EdDSA, ES256, ES384, ES512, RS256, RS384, RS512"
                    )
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
                    staleOnError = staleOnError,
                    didAllowlist = didAllowlist,
                    didPattern = didPattern,
                    didStrictRelationship = didStrictRelationship,
                    didLooseKidMatch = didLooseKidMatch
                )
            }

            else -> {
                val msg = "Unknown actor_authentication.verifier type '$type'; falling back to Noop"
                warnings.add(msg)
                logger.warn(msg)
                VerifierConfig.Noop
            }
        }
    }
}

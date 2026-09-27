package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.FileReader
import java.nio.file.Path

/**
 * YAML-backed loader for the `actor_authentication:` section of `.taskorchestrator/config.yaml`.
 *
 * Two constructors:
 *  - `YamlActorAuthenticationConfigService(configPath, envResolver)` (or its default): reads and
 *    parses the file independently via its own SafeConstructor-based `Yaml`/`FileReader`. Kept unchanged for backward
 *    compatibility.
 *  - `YamlActorAuthenticationConfigService(globalConfig: GlobalConfigFile, envResolver)`: reads
 *    the `actor_authentication` section from the ONE shared, already-parsed [GlobalConfigFile]
 *    document ([io.github.jpicklyk.mcptask.current.application.config.ConfigDocument.actorAuthenticationSection])
 *    instead of re-parsing the file. Section validation and error messages are shared with the
 *    path-based constructor (`configPath` in every message is [globalConfig]'s
 *    [GlobalConfigFile.configPath] in this mode). A parse failure at the [GlobalConfigFile] level
 *    (YAML syntax error, non-mapping root) propagates as the same [IllegalArgumentException]
 *    [GlobalConfigFile] itself throws — this class adds no separate parse step in document mode.
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
 * For the `jwks` type, exactly one of `oidc_discovery`, `jwks_uri`, `jwks_path` (static-JWKS mode)
 * or `did_allowlist`/`did_pattern` (DID-trust mode) must be provided; see below for what happens
 * when none is.
 *
 * If the config file is missing, empty, or comment-only, or the `actor_authentication:` section is
 * absent (or explicitly `null`), [ActorAuthenticationConfig] defaults are returned
 * (`enabled=true`, [VerifierConfig.Noop]).
 *
 * **FAILS CLOSED**: if the file exists but cannot be read or parsed (YAML syntax error, non-mapping
 * root document), or a present, non-null field fails to parse (an unrecognized
 * `degraded_mode_policy`, an unrecognized `verifier.type`, a non-map `verifier` or
 * `actor_authentication` value, or `type: jwks` with no configured key source),
 * [IllegalArgumentException] is thrown naming the config path rather than silently substituting a
 * default. A malformed field being silently ignored could otherwise turn a security-relevant
 * misconfiguration into unverified access without any startup signal.
 *
 * ## `DEGRADED_MODE_POLICY` environment variable
 *
 * When the `DEGRADED_MODE_POLICY` environment variable is set it overrides the YAML value:
 * - Valid values (case-insensitive): `accept-cached`, `accept-self-reported`, `reject`
 * - If set to an invalid value a [IllegalArgumentException] is thrown at construction time so the
 *   misconfiguration is surfaced immediately rather than silently falling back to a default.
 * - If unset, the YAML value is used (then the coded default [DegradedModePolicy.ACCEPT_CACHED]).
 *
 * @param configPath Path to the `.taskorchestrator/config.yaml` file (path-based constructor only).
 * @param globalConfig Shared, already-parsed global config document (document-based constructor).
 * @param envResolver Injectable resolver for environment variables; defaults to [System::getenv].
 *   Tests inject a fake to avoid mutating the JVM environment.
 */
class YamlActorAuthenticationConfigService private constructor(
    private val configPathParam: Path?,
    private val globalConfig: GlobalConfigFile?,
    private val envResolver: (String) -> String? = System::getenv
) {
    constructor(
        configPath: Path = YamlNoteSchemaService.resolveDefaultConfigPath(),
        envResolver: (String) -> String? = System::getenv
    ) : this(configPath, null, envResolver)

    constructor(
        globalConfig: GlobalConfigFile,
        envResolver: (String) -> String? = System::getenv
    ) : this(null, globalConfig, envResolver)

    /** The config path every error message names, regardless of which constructor was used. */
    private val configPath: Path get() = configPathParam ?: globalConfig!!.configPath

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

    /** Dispatches to the path-based or document-based load, depending on which was constructed. */
    private fun loadYamlConfig(): LoadResult {
        val sharedGlobalConfig = globalConfig
        return if (sharedGlobalConfig != null) {
            loadYamlConfigFromDocument(sharedGlobalConfig)
        } else {
            loadYamlConfigFromPath()
        }
    }

    /**
     * Reads `actor_authentication` from [sharedGlobalConfig]'s already-parsed document. A `null`
     * layer (no global config file) means the coded defaults, exactly like an absent file in the
     * path-based flow. The legacy `auditing:` hard-cut is detected via the document's
     * `presentSections` instead of re-inspecting a raw root map. A parse failure at the
     * [GlobalConfigFile] level (accessed via [GlobalConfigFile.layer]) propagates unchanged — this
     * method adds no separate try/catch around it.
     */
    private fun loadYamlConfigFromDocument(sharedGlobalConfig: GlobalConfigFile): LoadResult {
        val warnings = mutableListOf<String>()
        val document = sharedGlobalConfig.layer()?.document

        if (document?.presentSections?.contains("auditing") == true) {
            throwLegacyAuditingKeyError()
        }

        val config = buildConfigFromActorAuthSection(document?.actorAuthenticationSection)
        return LoadResult(config, warnings)
    }

    /**
     * Loads the `actor_authentication:` section, failing closed on any parse problem.
     *
     * An absent file, an empty/comment-only file (parses to `null`), an absent
     * `actor_authentication:` key, or an explicit `actor_authentication: null` all keep the coded
     * defaults (`enabled=true`, [VerifierConfig.Noop]) — these are legitimate "nothing configured"
     * states, not errors. Everything else that prevents a well-formed [ActorAuthenticationConfig]
     * from being produced — a YAML syntax error, a root document that is not a mapping, or a
     * present-and-non-null field that fails to parse — throws [IllegalArgumentException] naming
     * [configPath] rather than silently substituting a default (see the class kdoc for why).
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadYamlConfigFromPath(): LoadResult {
        val warnings = mutableListOf<String>()
        val path = configPathParam!!
        if (!path.toFile().exists()) {
            logger.debug("No config file found at {}; using default actor_authentication config", path)
            return LoadResult(ActorAuthenticationConfig(), warnings)
        }

        val loaded =
            try {
                val yaml = Yaml(SafeConstructor(LoaderOptions()))
                FileReader(path.toFile()).use { reader -> yaml.load<Any?>(reader) }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to parse actor_authentication config from '$path': ${e.message}",
                    e
                )
            }

        // Empty or comment-only file parses to null — a legitimate "nothing configured" state.
        if (loaded == null) return LoadResult(ActorAuthenticationConfig(), warnings)

        val root =
            loaded as? Map<String, Any>
                ?: throw IllegalArgumentException(
                    "Config file '$path' root must be a mapping; got '$loaded'"
                )

        // Hard cut: legacy auditing: key produces a clear migration error
        if (root.containsKey("auditing")) {
            throwLegacyAuditingKeyError()
        }

        val config = buildConfigFromActorAuthSection(root["actor_authentication"])
        return LoadResult(config, warnings)
    }

    private fun throwLegacyAuditingKeyError(): Nothing =
        throw IllegalArgumentException(
            "Unknown top-level config key 'auditing:'. " +
                "Did you mean 'actor_authentication:'? See CHANGELOG for migration."
        )

    /**
     * Builds an [ActorAuthenticationConfig] from the raw `actor_authentication` value — shared by
     * both the path-based and document-based load flows so section validation and error messages
     * (naming [configPath]) are identical regardless of which constructor was used.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildConfigFromActorAuthSection(actorAuthRaw: Any?): ActorAuthenticationConfig {
        val actorAuthSection: Map<String, Any> =
            when (actorAuthRaw) {
                null -> {
                    logger.debug("No 'actor_authentication' section in config; using defaults")
                    return ActorAuthenticationConfig()
                }
                is Map<*, *> -> actorAuthRaw as Map<String, Any>
                else ->
                    throw IllegalArgumentException(
                        "actor_authentication in '$configPath' must be a mapping; got '$actorAuthRaw'"
                    )
            }

        val enabled = (actorAuthSection["enabled"] as? Boolean) ?: true

        val verifierRaw = actorAuthSection["verifier"]
        val verifier: VerifierConfig =
            when (verifierRaw) {
                null -> VerifierConfig.Noop
                is Map<*, *> -> parseVerifier(verifierRaw as Map<String, Any>)
                else ->
                    throw IllegalArgumentException(
                        "actor_authentication.verifier in '$configPath' must be a mapping; got '$verifierRaw'"
                    )
            }

        val degradedModePolicy = parseDegradedModePolicy(actorAuthSection)

        return ActorAuthenticationConfig(
            enabled = enabled,
            verifier = verifier,
            degradedModePolicy = degradedModePolicy
        )
    }

    private fun parseDegradedModePolicy(actorAuthSection: Map<String, Any>): DegradedModePolicy {
        val raw = actorAuthSection["degraded_mode_policy"] ?: return DegradedModePolicy.ACCEPT_CACHED
        val rawStr =
            raw as? String
                ?: throw IllegalArgumentException(
                    "actor_authentication.degraded_mode_policy in '$configPath' must be a string; got '$raw'"
                )
        return DegradedModePolicy.fromConfigString(rawStr)
            ?: throw IllegalArgumentException(
                "Unknown actor_authentication.degraded_mode_policy '$rawStr' in '$configPath'; " +
                    "valid values: accept-cached, accept-self-reported, reject"
            )
    }

    private fun wrongVerifierFieldType(
        key: String,
        expected: String,
        value: Any,
    ) = IllegalArgumentException("actor_authentication.verifier.$key in '$configPath' must be $expected; got '$value'")

    private fun Map<String, Any>.optString(key: String): String? =
        when (val v = this[key]) {
            null -> null
            is String -> v
            else -> throw wrongVerifierFieldType(key, "a string", v)
        }

    private fun Map<String, Any>.optBoolean(
        key: String,
        default: Boolean,
    ): Boolean =
        when (val v = this[key]) {
            null -> default
            is Boolean -> v
            else -> throw wrongVerifierFieldType(key, "a boolean", v)
        }

    private fun Map<String, Any>.optStringList(key: String): List<String> =
        when (val v = this[key]) {
            null -> emptyList()
            is List<*> -> v.map { it as? String ?: throw wrongVerifierFieldType(key, "a list of strings", v) }
            else -> throw wrongVerifierFieldType(key, "a list of strings", v)
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseVerifier(verifierMap: Map<String, Any>): VerifierConfig {
        val typeRaw = verifierMap["type"]
        val type: String? =
            when (typeRaw) {
                null -> null
                is String -> typeRaw.lowercase()
                else ->
                    throw IllegalArgumentException(
                        "actor_authentication.verifier.type in '$configPath' must be a string; got '$typeRaw'"
                    )
            }

        return when (type) {
            null, "noop" -> VerifierConfig.Noop

            "jwks" -> {
                // Every field below is typed strictly: a present, non-null value of the wrong type is
                // fatal (e.g. a list-valued `audience` would otherwise silently disable the aud check).
                val oidcDiscovery = verifierMap.optString("oidc_discovery")
                val jwksUri = verifierMap.optString("jwks_uri")
                val jwksPath = verifierMap.optString("jwks_path")

                val didAllowlist = verifierMap.optStringList("did_allowlist")
                val didPattern = verifierMap.optString("did_pattern")
                val didStrictRelationship = verifierMap.optBoolean("did_strict_relationship", default = true)
                val didLooseKidMatch = verifierMap.optBoolean("did_loose_kid_match", default = true)

                // Type-checked whenever type=jwks, regardless of source or scheme — a malformed
                // value (e.g. a string) must fail loudly rather than silently default to false.
                val rawAllowInsecureUrl = verifierMap["allow_insecure_url"]
                val allowInsecureUrl: Boolean =
                    when (rawAllowInsecureUrl) {
                        null -> false
                        is Boolean -> rawAllowInsecureUrl
                        else -> throw IllegalArgumentException(
                            "actor_authentication.verifier.allow_insecure_url must be a boolean; got '$rawAllowInsecureUrl'"
                        )
                    }

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

                // Neither DID trust nor static JWKS configured — no key source available. This goes
                // beyond a plain "unparseable field" fallback: a `type: jwks` verifier with no way to
                // fetch keys is a security-relevant misconfiguration in the same class as the
                // `algorithms` check below, so it is fatal rather than silently downgrading to Noop.
                if (!isDidTrust && !isStaticJwks) {
                    throw IllegalArgumentException(
                        "actor_authentication.verifier type 'jwks' in '$configPath' requires one of: " +
                            "oidc_discovery, jwks_uri, jwks_path (static JWKS mode), " +
                            "or did_allowlist/did_pattern (DID-trust mode); none configured"
                    )
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

                    // https-or-loopback rule for the single configured key source. jwks_path (a
                    // local file) is exempt — this only governs sources fetched over HTTP(S).
                    if (oidcDiscovery != null) {
                        validateKeySourceUrl(oidcDiscovery, "oidc_discovery", allowInsecureUrl)
                    }
                    if (jwksUri != null) {
                        validateKeySourceUrl(jwksUri, "jwks_uri", allowInsecureUrl)
                    }
                }

                val issuer = verifierMap.optString("issuer")
                val audience = verifierMap.optString("audience")

                val algorithms = verifierMap.optStringList("algorithms")

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
                        null -> 300L
                        else -> throw wrongVerifierFieldType("cache_ttl_seconds", "a number", raw)
                    }

                val requireSubMatch = verifierMap.optBoolean("require_sub_match", default = true)

                val staleOnError = verifierMap.optBoolean("stale_on_error", default = true)

                val maxTokenLifetimeSeconds =
                    when (val raw = verifierMap["max_token_lifetime_seconds"]) {
                        is Int -> raw.toLong()
                        is Long -> raw
                        null -> 86400L
                        else -> throw wrongVerifierFieldType("max_token_lifetime_seconds", "a number", raw)
                    }
                if (maxTokenLifetimeSeconds <= 0) {
                    throw IllegalArgumentException(
                        "actor_authentication.verifier.max_token_lifetime_seconds in '$configPath' " +
                            "must be a positive number; got '$maxTokenLifetimeSeconds'"
                    )
                }
                if (maxTokenLifetimeSeconds > MAX_TOKEN_LIFETIME_SECONDS_CEILING) {
                    throw IllegalArgumentException(
                        "actor_authentication.verifier.max_token_lifetime_seconds in '$configPath' " +
                            "must not exceed $MAX_TOKEN_LIFETIME_SECONDS_CEILING (~100 years); " +
                            "got '$maxTokenLifetimeSeconds'"
                    )
                }

                val jtiReplayProtection = verifierMap.optBoolean("jti_replay_protection", default = false)

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
                    didLooseKidMatch = didLooseKidMatch,
                    allowInsecureUrl = allowInsecureUrl,
                    maxTokenLifetimeSeconds = maxTokenLifetimeSeconds,
                    jtiReplayProtection = jtiReplayProtection
                )
            }

            else ->
                throw IllegalArgumentException(
                    "Unknown actor_authentication.verifier.type '$type' in '$configPath'; " +
                        "valid values: noop, jwks"
                )
        }
    }

    /**
     * Enforces the https-or-loopback rule (mirroring the REST API's `API_JWKS_URL` /
     * `API_JWKS_ALLOW_INSECURE_URL` contract, see [ApiAuthConfigLoader]) on a single configured
     * actor-authentication key-source value ([raw] — either `oidc_discovery` or `jwks_uri`,
     * named by [keyName] for the error message).
     *
     * `https` is always accepted. `http` is accepted only when [allowInsecureUrl] is true AND the
     * URL's host is a literal loopback address ([isLiteralLoopbackHost] — no DNS resolution, so a
     * host merely resolving to loopback is still rejected). Every other outcome — any other
     * scheme, a malformed URL, or `http` without both conditions — throws
     * [IllegalArgumentException] naming `actor_authentication.verifier.<keyName>`, which the
     * caller's `catch (e: IllegalArgumentException)` re-throws rather than swallowing into a
     * silent Noop fallback (unlike the generic `catch (e: Exception)` below it).
     */
    private fun validateKeySourceUrl(
        raw: String,
        keyName: String,
        allowInsecureUrl: Boolean
    ) = requireHttpsOrLoopbackKeySource(raw, "actor_authentication.verifier.$keyName", allowInsecureUrl, logger)
}

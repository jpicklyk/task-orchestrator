package io.github.jpicklyk.mcptask.current.infrastructure.config

private const val REMOVED_POLICY_ENV_VAR = "DEGRADED_MODE_POLICY"

/**
 * Startup guard for server configuration that no longer exists. Silently ignoring a setting an operator believes is
 * in force would be worse than refusing to start, so each leftover fails startup with an upgrade message
 * ([IllegalArgumentException]) before the readiness marker is written. Checked in order:
 *
 * 1. a set `DEGRADED_MODE_POLICY` environment variable (any value, empty included): verification fails closed and
 *    there is no degraded-mode policy;
 * 2. a top-level `auditing:` key (the 3.x name for a block that no longer exists);
 * 3. a top-level `actor_authentication:` key, whatever its value (map, scalar, `{}`, bare or explicit null): since
 *    4.0 every caller is identified by its transport credential, so the block authenticated nothing.
 *
 * No config file, or an empty or comment-only one, passes. A parse failure in [globalConfig] propagates unchanged.
 *
 * @param globalConfig the shared, already-parsed global config file.
 * @param envResolver resolver behind `AppConfig`; this function never reads the process environment itself.
 */
internal fun checkRemovedServerConfig(
    globalConfig: GlobalConfigFile,
    envResolver: (String) -> String?,
) {
    if (envResolver(REMOVED_POLICY_ENV_VAR) != null) {
        throw IllegalArgumentException(
            "$REMOVED_POLICY_ENV_VAR is no longer supported: verification fails closed and there is no " +
                "degraded-mode policy. Unset the environment variable.",
        )
    }
    val sections = globalConfig.layer()?.document?.presentSections ?: return
    if ("auditing" in sections) {
        throw IllegalArgumentException(
            "Unknown top-level config key 'auditing:' in '${globalConfig.configPath}': it was removed. See CHANGELOG for migration.",
        )
    }
    if ("actor_authentication" in sections) {
        throw IllegalArgumentException(
            "actor_authentication in '${globalConfig.configPath}' is no longer supported: since 4.0 every caller is " +
                "identified by its transport credential (API_AUTH_MODE / API_JWKS_*, the /mcp auth mode), so the " +
                "block authenticated nothing. Remove the block (for the plugin hook's attribution requirement use " +
                "actor_attribution.required).",
        )
    }
}

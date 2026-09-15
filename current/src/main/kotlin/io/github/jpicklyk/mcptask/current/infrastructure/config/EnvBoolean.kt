package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.slf4j.LoggerFactory

/**
 * Single shared boolean parser for every environment-variable boolean read in the codebase.
 *
 * Before this object existed, boolean env vars were parsed with four different, mutually
 * incompatible conventions scattered across [AppConfig], [ApiAuthConfigLoader],
 * `AdvanceService`, and `FlywayDatabaseSchemaManager`:
 *  - Kotlin's `.toBoolean()` (only the exact literal `"true"`, case-insensitively, is true —
 *    `"1"` silently parses to `false`, with no diagnostic)
 *  - `.lowercase() == "true"` (same trap, spelled differently)
 *  - `.lowercase() != "false"` ("default true unless literal false" — silently treats `"1"` /
 *    `"0"` / `"no"` as "true", the opposite of what an operator typing `RESOURCE_LEASES_ENFORCED=0`
 *    would expect)
 *  - an explicit `true/1/yes` vs `false/0/no` set that throws on anything else
 *
 * This produced the `USE_FLYWAY=1` bug: the operator's intent (`"1"` meaning enabled) was
 * silently discarded because `.toBoolean()` only recognizes the literal string `"true"`.
 *
 * [EnvBoolean] recognizes `true/1/yes` and `false/0/no` (case-insensitive, trimmed) as the one
 * true/false vocabulary for every boolean env var, and gives call sites two failure modes:
 *  - [parse] — unrecognized value logs a WARN naming the variable, the raw value, and the
 *    default, then falls back to the default (used by env vars that must never crash startup).
 *  - [require] — unrecognized value throws [IllegalArgumentException] naming the variable and
 *    the raw value (used by the REST API's fail-fast startup validation).
 *
 * An unset variable (`raw == null`) always resolves to the caller's default silently, in both
 * [parse] and [require] — this is "not configured", not "misconfigured".
 */
object EnvBoolean {
    private val logger = LoggerFactory.getLogger(EnvBoolean::class.java)

    /**
     * Parses [raw] against the shared boolean vocabulary, trimmed and lowercased first.
     *
     * @return `true` for `true`/`1`/`yes`, `false` for `false`/`0`/`no`, or `null` when [raw] is
     *   `null`, blank, or any other value.
     */
    fun parseOrNull(raw: String?): Boolean? {
        val normalized = raw?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    /**
     * Resolves a boolean env var that must never fail startup.
     *
     * `raw == null` (unset) returns [default] silently. A non-null value that [parseOrNull] does
     * not recognize (including an empty string) logs a WARN naming [name], the raw value, and
     * [default], then also returns [default].
     *
     * @param name the environment variable name, used only in the WARN log message.
     */
    fun parse(
        name: String,
        raw: String?,
        default: Boolean,
    ): Boolean {
        if (raw == null) return default
        val parsed = parseOrNull(raw)
        if (parsed != null) return parsed
        logger.warn(
            "Environment variable '{}' has unrecognized boolean value '{}'; using default {}",
            name,
            raw,
            default,
        )
        return default
    }

    /**
     * Resolves a boolean env var whose misconfiguration must fail startup fast.
     *
     * `raw == null` (unset) returns [default] silently. A non-null value that [parseOrNull] does
     * not recognize throws [IllegalArgumentException] naming [name] and the raw value.
     *
     * @param name the environment variable name, used in the exception message.
     */
    fun require(
        name: String,
        raw: String?,
        default: Boolean,
    ): Boolean {
        if (raw == null) return default
        return parseOrNull(raw)
            ?: throw IllegalArgumentException(
                "$name has invalid value '$raw'. Expected: true or false.",
            )
    }
}

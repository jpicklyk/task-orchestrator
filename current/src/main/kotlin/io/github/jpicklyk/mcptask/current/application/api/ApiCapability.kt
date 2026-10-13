package io.github.jpicklyk.mcptask.current.application.api

/**
 * Operations that may be granted to a [Principal].
 *
 * Capabilities are **additive**: a principal with [READ] and [WRITE_NOTES] cannot create items. [ADMIN] implies
 * every other capability (see [grants]).
 */
enum class ApiCapability {
    /** All read selects and GET endpoints. */
    READ,

    /** Note writes. */
    WRITE_NOTES,

    /** Item writes. */
    WRITE_ITEMS,

    /** Role transitions (advance). */
    ADVANCE,

    /** Dependency writes. */
    MANAGE_DEPENDENCIES,

    /** Per-root project config writes (the `config` tool's write ops). Grants no `docs_write` op. */
    WRITE_CONFIG,

    /** Every `docs_write` op (plans, rules, pages). */
    WRITE_DOCS,

    /** Run tools. Parseable and grantable; no consumer enforces it yet. */
    RUNS,

    /** Decision answering. Parseable and grantable; no consumer enforces it yet. */
    DECIDE,

    /** Operator-only endpoints; also implies every capability above. */
    ADMIN,
    ;

    companion object {
        /**
         * Parses a capability from its kebab-case form as used in the token file and the JWT `to_capabilities` claim
         * (trimmed, case-insensitive): `read`, `write-notes`, `write-items`, `advance`, `manage-dependencies`,
         * `write-config`, `write-docs`, `runs`, `decide`, `admin`.
         *
         * @throws IllegalArgumentException for an unknown value.
         */
        fun fromConfigString(value: String): ApiCapability {
            val text = value.lowercase().trim()
            return entries.firstOrNull { it.configName() == text }
                ?: throw IllegalArgumentException("Unknown capability '$value'. Valid values: ${validValues()}")
        }

        /** Every valid kebab-case spelling, comma-separated, for error messages. */
        fun validValues(): String = entries.joinToString(", ") { it.configName() }
    }
}

/**
 * The ONE capability predicate, shared by the REST route wrapper and the tool dispatcher: [ApiCapability.ADMIN]
 * implies every capability, anything else must be listed.
 */
fun Set<ApiCapability>.grants(required: ApiCapability): Boolean = ApiCapability.ADMIN in this || required in this

/** The kebab-case spelling [ApiCapability.fromConfigString] parses (`write-items`, `read`, ...). */
fun ApiCapability.configName(): String = name.lowercase().replace('_', '-')

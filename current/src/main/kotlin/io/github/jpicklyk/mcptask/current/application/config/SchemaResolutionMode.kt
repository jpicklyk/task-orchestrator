package io.github.jpicklyk.mcptask.current.application.config

/**
 * AR-39 schema-resolution mode a config document may opt into via the top-level
 * `schema_resolution:` key. Parsed and stored on [ConfigDocument]; honored by
 * [LayeredConfig] per its effective mode (see [LayeredConfig.effectiveMode] and its facet table).
 * Opt-in only: absent everywhere still resolves as [LEGACY] (D1 — no default flip).
 */
enum class SchemaResolutionMode {
    LEGACY,
    LAYERED,
    ISOLATED,
    ;

    companion object {
        /**
         * Parses [raw] into a [SchemaResolutionMode]. Only an exact, lowercase match of
         * `"legacy"`, `"layered"`, or `"isolated"` is recognized; anything else (including a
         * differently-cased value) returns `null` — the caller treats `null` as "absent", not as
         * an error, and adds no warning of its own here.
         */
        fun fromConfigString(raw: String): SchemaResolutionMode? =
            when (raw) {
                "legacy" -> LEGACY
                "layered" -> LAYERED
                "isolated" -> ISOLATED
                else -> null
            }
    }
}

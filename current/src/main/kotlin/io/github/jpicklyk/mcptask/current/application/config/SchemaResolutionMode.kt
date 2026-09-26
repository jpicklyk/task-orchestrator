package io.github.jpicklyk.mcptask.current.application.config

/**
 * AR-39 schema-resolution mode a config document may opt into via the top-level
 * `schema_resolution:` key. Parsed and stored on [ConfigDocument] by C1 but NOT read by anything
 * yet — the resolver that honors it (LAYERED precedence, ISOLATED fencing) lands in a later item.
 * Until then every effective resolution behaves as [LEGACY] regardless of what a document sets.
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

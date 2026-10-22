package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

/**
 * How [FlywayDatabaseSchemaManager] treats the schema at startup (`SCHEMA_MODE`).
 *
 * - [MIGRATE] (default): apply pending migrations.
 * - [VALIDATE]: run no migrate and no baseline; a pending or future version fails startup.
 */
enum class SchemaMode {
    MIGRATE,
    VALIDATE;

    companion object {
        /**
         * Parses a raw `SCHEMA_MODE` value: null or blank is [MIGRATE]; `migrate` / `validate` are
         * trimmed and case-insensitive.
         *
         * @throws IllegalArgumentException for any other value.
         */
        fun parse(raw: String?): SchemaMode {
            val v = raw?.trim()?.lowercase().orEmpty()
            return when (v) {
                "", "migrate" -> MIGRATE
                "validate" -> VALIDATE
                else -> throw IllegalArgumentException("Unknown SCHEMA_MODE '$raw' (expected 'migrate' or 'validate')")
            }
        }
    }
}

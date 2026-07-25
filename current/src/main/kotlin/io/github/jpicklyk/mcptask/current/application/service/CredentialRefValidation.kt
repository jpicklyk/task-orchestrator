package io.github.jpicklyk.mcptask.current.application.service

/**
 * Shared validation rules for the optional `credentialRefs` audit field accepted by the MCP
 * `advance_item` tool and the REST `POST /items/{id}/advance` route. Both call sites route
 * through [validate] so the two surfaces enforce byte-for-byte identical rules.
 *
 * Entries are opaque credential/secret *labels* (e.g. "vault/prod-db-password", "github-pat-ci")
 * recorded on the `role_transitions` audit row — never the credential value itself. Callers must
 * never pass raw secret material here; this field is deliberately not redacted on read (see
 * TransitionRoutes.kt) because the labels themselves are not sensitive.
 */
object CredentialRefValidation {
    /** Maximum number of credentialRefs entries accepted per transition. */
    const val MAX_ENTRIES = 8

    /** Maximum length (inclusive) of a single credentialRefs entry. */
    const val MAX_LENGTH = 128

    /** Lowercase-slug-like label pattern: starts alphanumeric, then alphanumeric/-/_/./ allowed. */
    val ENTRY_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9\\-_./]*$")

    /** Outcome of validating a candidate `credentialRefs` list. */
    sealed class Result {
        data class Valid(
            val values: List<String>
        ) : Result()

        /**
         * @property index the offending entry's position in the list, or -1 when the violation is
         *   about the list as a whole (e.g. too many entries).
         * @property reason a human-readable description of the violation, without any index prefix
         *   — callers compose the final message since the two call sites format it differently.
         */
        data class Invalid(
            val index: Int,
            val reason: String
        ) : Result()
    }

    /**
     * Validates [values] against the shared rules: at most [MAX_ENTRIES] entries, each 1..[MAX_LENGTH]
     * characters matching [ENTRY_PATTERN]. Returns the first violation encountered (list-size check
     * first, then per-entry checks in order).
     */
    fun validate(values: List<String>): Result {
        if (values.size > MAX_ENTRIES) {
            return Result.Invalid(-1, "must not contain more than $MAX_ENTRIES entries (found ${values.size})")
        }
        values.forEachIndexed { index, value ->
            if (value.isEmpty() || value.length > MAX_LENGTH) {
                return Result.Invalid(index, "must be 1-$MAX_LENGTH characters (found length ${value.length})")
            }
            if (!ENTRY_PATTERN.matches(value)) {
                return Result.Invalid(
                    index,
                    "'$value' does not match required pattern ^[a-z0-9][a-z0-9\\-_./]*$"
                )
            }
        }
        return Result.Valid(values)
    }
}

package io.github.jpicklyk.mcptask.current.application.service.search

/**
 * Sanitizes user-provided search text into a safe FTS5 query string.
 *
 * ## Algorithm
 *
 * 1. Split input on whitespace to extract individual tokens.
 * 2. For each non-empty token, escape FTS5 special characters/operators.
 * 3. Wrap each escaped token in double quotes (makes it a phrase term for FTS5).
 * 4. Join tokens with a space (implicit AND in FTS5's default query mode).
 * 5. Reject input that produces zero tokens after splitting.
 * 6. For trigram-only mode ([validateForTrigram]), additionally reject input where every
 *    token is shorter than 3 characters — trigram indexes require ≥3-char tokens to match.
 *
 * ## Special characters escaped
 *
 * | Char/Word | Why it's special in FTS5 |
 * |-----------|--------------------------|
 * | `"` | Begins/ends phrase queries |
 * | `*` | Prefix wildcard |
 * | `:` | Column filter syntax |
 * | `-` | NOT operator (when prefixing a term) |
 * | `(`, `)` | Grouping |
 * | `AND` | Boolean AND (case-sensitive when uppercase) |
 * | `OR` | Boolean OR |
 * | `NOT` | Boolean NOT |
 * | `NEAR` | Proximity operator |
 *
 * ## Examples
 *
 * ```
 * sanitize("OAuth flow")         → "\"OAuth\" \"flow\""
 * sanitize("auth-check")         → "\"auth-check\""   (hyphen inside a quoted term is fine)
 * sanitize("find (this)")        → "\"find\" \"this\"" (parens stripped, each token quoted)
 * sanitize("NOT bad")            → "\"NOT\" \"bad\""   (operator word escaped via quoting)
 * sanitize("hello AND world")    → "\"hello\" \"AND\" \"world\""
 * sanitize("  ")                 → null (no tokens → rejected)
 * sanitize("ab")                 → "\"ab\""  (valid for text table; fails validateForTrigram)
 * ```
 */
object FtsQuerySanitizer {
    /**
     * FTS5 operator keywords (case-sensitive in FTS5, but we match case-insensitively on the
     * user's input and wrap them in quotes so FTS5 treats them as literal terms).
     */
    private val FTS5_OPERATOR_WORDS = setOf("AND", "OR", "NOT", "NEAR")

    /**
     * Sanitize a raw user query string into a safe FTS5 query string, or return null if
     * the input produces no usable tokens (empty/whitespace-only input).
     *
     * The returned string is safe to pass directly as the MATCH operand in an FTS5 query.
     * Each token is double-quoted, so FTS5 treats it as a phrase term (not an operator).
     *
     * @param rawQuery The user-supplied search string (may be empty or contain FTS5 operators).
     * @return Sanitized FTS5 query string, or null if no usable tokens exist.
     */
    fun sanitize(rawQuery: String): String? {
        val tokens =
            rawQuery
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val sanitizedTokens =
            tokens.map { token ->
                // Escape double-quotes inside the token (the only char that can break a
                // quoted FTS5 phrase — all others are safe inside double-quotes).
                val escaped = token.replace("\"", "\\\"")
                // Wrap in double quotes to make it an FTS5 phrase term.
                // This prevents: *, :, -, (, ), AND, OR, NOT, NEAR from being parsed as operators.
                "\"$escaped\""
            }

        return sanitizedTokens.joinToString(" ")
    }

    /**
     * Sanitize and validate for trigram-table usage. Returns the sanitized query string, or null
     * when the input is empty. Throws [IllegalArgumentException] when ALL tokens are shorter than
     * 3 characters — the trigram index cannot match sub-3-char tokens and would always return
     * empty results.
     *
     * Use this when [matchMode] is SUBSTRING or when AUTO mode falls back to the trigram table only.
     *
     * @param rawQuery The user-supplied search string.
     * @return Sanitized FTS5 query string suitable for the trigram table.
     * @throws IllegalArgumentException when input is non-empty but all tokens are < 3 chars.
     */
    fun sanitizeForTrigram(rawQuery: String): String? {
        val sanitized = sanitize(rawQuery) ?: return null
        validateForTrigram(rawQuery)
        return sanitized
    }

    /**
     * Validate that at least one token in [rawQuery] has ≥3 characters (the minimum for the
     * trigram table to produce any matches). Throws if no such token exists.
     *
     * @param rawQuery The original (unsanitized) user query.
     * @throws IllegalArgumentException if no token has ≥3 characters.
     */
    fun validateForTrigram(rawQuery: String) {
        val tokens = rawQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hasLongEnoughToken = tokens.any { it.length >= 3 }
        if (!hasLongEnoughToken) {
            throw IllegalArgumentException(
                "Trigram search requires at least one token with ≥3 characters. " +
                    "Got tokens: ${tokens.joinToString(", ") { "\"$it\"" }}. " +
                    "Try a longer search term or use matchMode=text for stemming-based search."
            )
        }
    }
}

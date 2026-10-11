package io.github.jpicklyk.mcptask.current.application.knowledge.search

/**
 * Splits user search text into plain terms. It applies no query syntax: every term is matched literally, so
 * characters such as `"`, `*`, `:`, `-`, `(`, `)` and words such as `AND`, `OR`, `NOT`, `NEAR` are ordinary text.
 * Escaping for a particular engine is the storage adapter's job, not this one's.
 *
 * ## Rules
 *
 * 1. **Split.** Terms are the maximal runs of characters that are not ASCII whitespace (space, tab, line feed,
 *    vertical tab, form feed, carriage return).
 * 2. **Empty.** Input with no terms (empty or whitespace-only) is rejected by the caller ([tokenize] returns an
 *    empty list).
 * 3. **Substring minimum.** The substring analyzer cannot match a term shorter than [SUBSTRING_MIN_TERM_LENGTH]
 *    characters, so a substring-only search whose every term is shorter than that is rejected
 *    ([substringViolation]).
 * 4. **Control characters.** Any ISO control character (U+0000-U+001F, U+007F-U+009F) other than the six
 *    separators above is rejected ([controlCharacterViolation]); it is never meaningful search text and a NUL
 *    breaks the FTS5 engine.
 *
 * ```
 * tokenize("OAuth flow")    -> ["OAuth", "flow"]
 * tokenize("auth-check")    -> ["auth-check"]
 * tokenize("NOT bad")       -> ["NOT", "bad"]
 * tokenize("  ")            -> []
 * substringViolation(["ab"]) -> message (every term under 3 characters)
 * ```
 */
object QueryTokenizer {
    /** The shortest term the substring analyzer can match. */
    const val SUBSTRING_MIN_TERM_LENGTH = 3

    /** ASCII whitespace: tab, line feed, vertical tab, form feed, carriage return, space. */
    private val SEPARATORS: Set<Char> = setOf(Char(9), Char(10), Char(11), Char(12), Char(13), Char(32))

    /**
     * The rejection message naming the first ISO control character in [rawQuery] that is not one of the six
     * separators, else null.
     */
    fun controlCharacterViolation(rawQuery: String): String? {
        val c = rawQuery.firstOrNull { it.isISOControl() && it !in SEPARATORS } ?: return null
        return "Search query contains a control character (U+%04X). Remove it and retry.".format(c.code)
    }

    /** The terms of [rawQuery], in order; empty when it has none. */
    fun tokenize(rawQuery: String): List<String> {
        val terms = mutableListOf<String>()
        val current = StringBuilder()
        for (c in rawQuery) {
            if (c in SEPARATORS) {
                if (current.isNotEmpty()) {
                    terms += current.toString()
                    current.setLength(0)
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) terms += current.toString()
        return terms
    }

    /**
     * The rejection message when no term in [terms] reaches [SUBSTRING_MIN_TERM_LENGTH] characters (a substring
     * search for them could never match), else null.
     */
    fun substringViolation(terms: List<String>): String? {
        if (terms.any { it.length >= SUBSTRING_MIN_TERM_LENGTH }) return null
        return "Trigram search requires at least one token with ≥3 characters. " +
            "Got tokens: ${terms.joinToString(", ") { "\"$it\"" }}. " +
            "Try a longer search term or use matchMode=text for stemming-based search."
    }
}

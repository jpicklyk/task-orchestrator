package io.github.jpicklyk.mcptask.current.application.knowledge.related

import java.util.Locale

/**
 * The fixed term-choice algorithm of the `related` text signal: which words of an entity's own text stand for it.
 *
 * Steps, in order:
 * 1. split every text on any character that is not a letter or digit;
 * 2. lowercase each token (root locale);
 * 3. keep tokens of at least [MIN_LENGTH] characters;
 * 4. drop tokens made only of digits, and every word in [STOPWORDS];
 * 5. keep distinct tokens;
 * 6. sort by length descending, then lexicographically ascending (longer words tend to be rarer);
 * 7. take the first `max`.
 *
 * Pure and deterministic: the same texts always give the same terms.
 */
object SimilarTerms {
    /** Shortest token kept. */
    const val MIN_LENGTH = 3

    /** Most terms the related service asks for. */
    const val DEFAULT_MAX = 8

    /** Common words that never stand for an entity (the minimum list the plan names). */
    val STOPWORDS: Set<String> =
        setOf(
            "the",
            "and",
            "for",
            "with",
            "that",
            "this",
            "from",
            "are",
            "was",
            "were",
            "will",
            "have",
            "has",
            "not",
            "but",
            "into",
            "than",
            "then",
            "they",
            "them",
            "their",
            "there",
            "these",
            "those",
            "when",
            "what",
            "which",
            "while",
            "where",
            "who",
            "how",
            "all",
            "any",
            "can",
            "its",
            "our",
            "you",
            "your",
        )

    /** At most [max] terms of [texts] (see the class description); an empty list when [max] is below 1. */
    fun extract(
        texts: List<String>,
        max: Int,
    ): List<String> {
        if (max < 1) return emptyList()
        val distinct = LinkedHashSet<String>()
        for (text in texts) {
            val token = StringBuilder()

            fun flush() {
                if (token.isNotEmpty()) {
                    val word = token.toString().lowercase(Locale.ROOT)
                    token.setLength(0)
                    if (keep(word)) distinct += word
                }
            }
            for (c in text) {
                if (c.isLetterOrDigit()) token.append(c) else flush()
            }
            flush()
        }
        return distinct
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .take(max)
    }

    private fun keep(word: String): Boolean = word.length >= MIN_LENGTH && !word.all { it.isDigit() } && word !in STOPWORDS
}

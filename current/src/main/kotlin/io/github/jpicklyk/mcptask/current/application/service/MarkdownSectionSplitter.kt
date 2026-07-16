package io.github.jpicklyk.mcptask.current.application.service

/**
 * Splits a stashed plan document (already CRLF-normalized to LF by
 * [io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService]) into addressable
 * sections keyed by a deterministic heading slug ("anchor"). Backs `create_work_tree`'s
 * `docRef`/`noteAnchors` materialization path (see
 * [io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool]), which slices
 * a note body out of a larger stashed document by anchor rather than requiring the caller to inline
 * every note's full text.
 *
 * A **section** runs from a heading line (any level, `#` through `######`) up to — but not
 * including — the next heading of the SAME level or a HIGHER level (fewer or equal `#`
 * characters), or to the end of the document if no such heading follows. A heading's own
 * sub-headings (deeper levels) are therefore included in its section.
 *
 * **Slugs** are deterministic kebab-case: the heading text is lowercased, runs of non-alphanumeric
 * characters collapse to a single `-`, and leading/trailing `-` are trimmed (an all-punctuation or
 * empty heading falls back to the literal slug `"section"`). Duplicate slugs within one document are
 * disambiguated with a `-2`, `-3`, ... suffix in document order — the first occurrence keeps the bare
 * slug.
 */
object MarkdownSectionSplitter {
    private val HEADING_LINE = Regex("^(#{1,6})\\s+(\\S.*?)\\s*$")

    private data class Heading(
        val level: Int,
        val slug: String,
        val lineIndex: Int
    )

    /** Returns every heading's deduped slug, in document order. Empty when [body] has no headings. */
    fun anchors(body: String): List<String> = parseHeadings(body).map { it.slug }

    /**
     * Returns the section text for [anchor] — the matching heading's line through the line
     * immediately before the next heading of the same or higher level (or through the end of the
     * document if none follows) — or null when no heading in [body] slugs to [anchor].
     */
    fun slice(
        body: String,
        anchor: String
    ): String? {
        val lines = body.split("\n")
        val headings = parseHeadings(body)
        val target = headings.firstOrNull { it.slug == anchor } ?: return null
        val endLine =
            headings
                .firstOrNull { it.lineIndex > target.lineIndex && it.level <= target.level }
                ?.lineIndex
                ?: lines.size
        return lines.subList(target.lineIndex, endLine).joinToString("\n")
    }

    /**
     * Parses every markdown heading line in [body], in document order, assigning each a
     * dedup-disambiguated slug. Tolerates a stray trailing `\r` per line defensively even though
     * callers are expected to have already CRLF-normalized [body].
     */
    private fun parseHeadings(body: String): List<Heading> {
        val lines = body.split("\n")
        val seenSlugCounts = mutableMapOf<String, Int>()
        val headings = mutableListOf<Heading>()
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.removeSuffix("\r")
            val match = HEADING_LINE.matchEntire(line) ?: continue
            val level = match.groupValues[1].length
            val baseSlug = slugify(match.groupValues[2])
            val seenCount = seenSlugCounts.getOrDefault(baseSlug, 0)
            seenSlugCounts[baseSlug] = seenCount + 1
            val slug = if (seenCount == 0) baseSlug else "$baseSlug-${seenCount + 1}"
            headings.add(Heading(level, slug, index))
        }
        return headings
    }

    private fun slugify(headingText: String): String {
        val slug =
            headingText
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
        return slug.ifEmpty { "section" }
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.TextQuery

/**
 * Renders a [TextQuery] as an FTS5 `MATCH` operand: the only place FTS5 query syntax is produced.
 *
 * Each term becomes a double-quoted phrase (an embedded `"` is doubled, the FTS5 phrase escape), and phrases are
 * joined by a space, which FTS5 reads as AND. Quoting neutralizes every FTS5 operator, so a term is always matched
 * literally:
 *
 * | Char/Word | Why it is special in FTS5 |
 * |-----------|---------------------------|
 * | `"` | Begins/ends phrase queries |
 * | `*` | Prefix wildcard |
 * | `:` | Column filter syntax |
 * | `-` | NOT operator (when prefixing a term) |
 * | `(`, `)` | Grouping |
 * | `^` | Initial-token anchor |
 * | `AND`, `OR`, `NOT`, `NEAR` | Boolean / proximity operators |
 *
 * ```
 * render(["OAuth", "flow"])  -> "OAuth" "flow"
 * render(["say", "\"hi\""])  -> "say" """hi"""
 * render(["NOT", "bad"])     -> "NOT" "bad"
 * ```
 */
internal object Fts5QueryRenderer {
    private const val QUOTE = "\""

    fun render(query: TextQuery): String = query.terms.joinToString(" ") { QUOTE + it.replace(QUOTE, QUOTE + QUOTE) + QUOTE }
}

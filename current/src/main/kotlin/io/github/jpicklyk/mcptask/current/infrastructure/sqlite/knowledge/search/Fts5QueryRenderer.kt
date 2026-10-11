package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.TextOp
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

    /** The phrases joined by a space (AND) for [TextOp.ALL], by `OR` ([renderAny]) for [TextOp.ANY]. */
    fun render(query: TextQuery): String =
        when (query.op) {
            TextOp.ALL -> query.terms.joinToString(" ") { phrase(it) }
            TextOp.ANY -> renderAny(query)
        }

    /** The same phrases joined with `OR`: a row matches when it contains ANY term (column filters, [TextOp.ANY]). */
    fun renderAny(query: TextQuery): String = query.terms.joinToString(" OR ") { phrase(it) }

    private fun phrase(term: String): String = QUOTE + term.replace(QUOTE, QUOTE + QUOTE) + QUOTE
}

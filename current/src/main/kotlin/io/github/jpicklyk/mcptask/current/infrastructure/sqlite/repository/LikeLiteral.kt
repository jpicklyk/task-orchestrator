package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import org.jetbrains.exposed.v1.core.LikePattern

/**
 * Embeds caller-supplied text in a SQL `LIKE` pattern as a LITERAL: `%`, `_` and the escape character itself are
 * escaped with [ESCAPE_CHAR], so a tag or search term containing a pattern character matches only itself. Every
 * pattern built from caller input goes through [escape] and carries the matching escape clause ([pattern] for
 * Exposed, [ESCAPE_CLAUSE] for hand-written SQL).
 */
internal object LikeLiteral {
    const val ESCAPE_CHAR: Char = '\\'

    /** The clause to append after `LIKE ?` in hand-written SQL whose argument came from [escape]. */
    const val ESCAPE_CLAUSE: String = "ESCAPE '\\'"

    /** [text] with every LIKE metacharacter and the escape character escaped. */
    fun escape(text: String): String =
        buildString(text.length) {
            for (c in text) {
                if (c == '%' || c == '_' || c == ESCAPE_CHAR) append(ESCAPE_CHAR)
                append(c)
            }
        }

    /** An Exposed pattern over [raw], whose caller-supplied parts were escaped via [escape]. */
    fun pattern(raw: String): LikePattern = LikePattern(raw, ESCAPE_CHAR)
}

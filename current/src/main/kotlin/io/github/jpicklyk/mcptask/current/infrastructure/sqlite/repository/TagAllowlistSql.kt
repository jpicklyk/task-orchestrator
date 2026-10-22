package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.VarCharColumnType

/**
 * The ONE SQL form of the `tags_include` access rule, shared by the item store ([TagAllowlistOp]) and the search
 * engine, so the two cannot drift from each other or from the scope predicate (`ScopeGuard`).
 *
 * The rule: the item's comma-separated `tags` is split on `,`, every element is trimmed of the characters Kotlin's
 * `Char.isWhitespace` accepts ([TRIM_CHARS]), and the item is admitted when a non-empty element EQUALS a listed tag:
 * case-sensitive, no pattern matching. A null `tags` admits nothing.
 */
internal object TagAllowlistSql {
    /** Every BMP character Kotlin's `trim()` strips, as the character set for SQLite's `trim(X, Y)`. */
    val TRIM_CHARS: String =
        buildString {
            for (code in 0..0xFFFF) {
                val c = Char(code)
                if (!c.isSurrogate() && c.isWhitespace()) append(c)
            }
        }

    /**
     * The predicate over [tagsColumn] (an SQL expression naming the item's `tags` column) for an allowlist of
     * [allowedCount] tags. Its positional arguments, in placeholder order, are [args].
     */
    fun render(
        tagsColumn: String,
        allowedCount: Int,
    ): String {
        require(allowedCount >= 1) { "allowedCount must be at least 1" }
        return """
            EXISTS (
                WITH RECURSIVE tag_split(rest, tag) AS (
                    SELECT $tagsColumn || ',', NULL
                    UNION ALL
                    SELECT substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1), ?)
                    FROM tag_split WHERE rest <> ''
                )
                SELECT 1 FROM tag_split WHERE tag <> '' AND tag IN (${List(allowedCount) { "?" }.joinToString(", ")})
            )
            """.trimIndent()
    }

    /** The positional arguments of [render] for [allowed]: the trim set, then each allowed tag. */
    fun args(allowed: Collection<String>): List<String> = listOf(TRIM_CHARS) + allowed
}

/**
 * [TagAllowlistSql] as an Exposed condition over [tagsColumn]. [allowed] must not be empty (an empty allowlist means
 * "no tag restriction" and must not reach the query).
 */
internal class TagAllowlistOp(
    private val tagsColumn: Expression<*>,
    allowed: Collection<String>,
) : Op<Boolean>() {
    private val allowed: List<String> = allowed.toList()

    init {
        require(this.allowed.isNotEmpty()) { "allowed must not be empty" }
    }

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val sql = TagAllowlistSql.render(COLUMN_MARK, allowed.size)
        val args = TagAllowlistSql.args(allowed).iterator()
        val (beforeColumn, afterColumn) = sql.split(COLUMN_MARK).also { check(it.size == 2) }.let { it[0] to it[1] }
        appendWithArgs(queryBuilder, beforeColumn, args)
        queryBuilder.append(tagsColumn)
        appendWithArgs(queryBuilder, afterColumn, args)
        check(!args.hasNext()) { "unbound tag allowlist arguments" }
    }

    private fun appendWithArgs(
        queryBuilder: QueryBuilder,
        sql: String,
        args: Iterator<String>,
    ) {
        val pieces = sql.split('?')
        pieces.forEachIndexed { index, piece ->
            if (index > 0) queryBuilder.registerArgument(TEXT_TYPE, args.next())
            queryBuilder.append(piece)
        }
    }

    private companion object {
        const val COLUMN_MARK = "\u0001tags\u0001"
        val TEXT_TYPE = VarCharColumnType(4000)
    }
}

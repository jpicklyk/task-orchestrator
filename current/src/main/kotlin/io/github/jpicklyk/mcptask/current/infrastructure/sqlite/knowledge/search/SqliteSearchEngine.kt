package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.rawQuery
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.uuidOf
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/**
 * SQLite [SearchIndex]: one engine for every corpus, driven by its [CorpusDescriptor], over the V7 FTS5 tables
 * (trigram tokenizer for [Analyzer.SUBSTRING], porter+unicode61 for [Analyzer.STEMMED]).
 *
 * Each [candidates] call is ONE statement: the FTS5 match, every [ScopeFilter] predicate (pushed into SQL so it
 * applies before the window), the rank order and the window. Nothing is filtered after the fact.
 *
 * **Access-scope pushdown.** The SQL predicates are written to be equivalent to the REST authorization rules:
 * - `rootIds`: an owning item is visible when its ancestor chain (itself included) contains a listed id. A listed
 *   id that is a stamped depth-0 root (`parent_id IS NULL AND root_id = id`, the same test as the item store's
 *   `ScopeResolver` fast path) admits every item whose denormalized `root_id` is that id (indexed); any other
 *   listed id (below root, or an unstamped root) admits its bounded subtree (recursive walk, cycle-bounded).
 *   Orphans (`root_id` NULL, not under a walked id) are never admitted.
 * - `principalTagsAny`: the owning item's comma-separated `tags` is split, every element trimmed of whitespace (the
 *   characters Kotlin's `Char.isWhitespace` accepts), and an element must EQUAL a listed tag: case-sensitive, no
 *   pattern matching, empty elements never match.
 */
class SqliteSearchEngine(
    private val databaseManager: DatabaseManager,
) : SearchIndex {
    override suspend fun candidates(
        corpus: Corpus,
        query: TextQuery,
        analyzer: Analyzer,
        filter: ScopeFilter,
        window: Int,
    ): List<Candidate> {
        if (window < 1) return emptyList()
        if (filter.rootIds != null && filter.rootIds.isEmpty()) return emptyList()
        val descriptor = CorpusDescriptor.of(corpus)
        val statement = buildStatement(descriptor, Fts5QueryRenderer.render(query), analyzer, filter, window)
        return databaseManager.readTx {
            rawQuery(statement.sql, statement.args) { rs ->
                val out = mutableListOf<Candidate>()
                while (rs.next()) {
                    // Select order: 1=rank, 2=doc_id, 3=owner_id, 4=note_key, 5.. = one snippet per field column.
                    val rank = (rs.getObject(1) as Number).toDouble()
                    val id = uuidOf(rs.getObject(2)!!)
                    val owner = uuidOf(rs.getObject(3)!!)
                    val noteKey = if (descriptor.noteKeyColumn != null) rs.getString(4) ?: "" else null
                    val snippets = descriptor.fields.indices.map { rs.getString(5 + it) ?: "" }
                    val (field, snippet) = matchedField(descriptor, snippets)
                    out += Candidate(id = id, ownerItemId = owner, noteKey = noteKey, rank = rank, field = field, snippet = snippet)
                }
                out
            }
        }
    }

    override suspend fun titles(itemIds: Set<UUID>): Map<UUID, String> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val entityIds = itemIds.map { EntityID(it, WorkItemsTable) }
            WorkItemsTable
                .select(WorkItemsTable.id, WorkItemsTable.title)
                .where { WorkItemsTable.id inList entityIds }
                .associate { it[WorkItemsTable.id].value to it[WorkItemsTable.title] }
        }
    }

    /** One rendered statement and its positional arguments, in placeholder order. */
    internal data class Statement(
        val sql: String,
        val args: List<Pair<ColumnType<*>, Any?>>,
    )

    internal companion object {
        /** Snippet highlight delimiters: private-use code points, so a match is detectable even in text containing `<mark>`. */
        private val OPEN = Char(0xE000)
        private val CLOSE = Char(0xE001)
        private const val MARK_OPEN = "<mark>"
        private const val MARK_CLOSE = "</mark>"
        private const val ELLIPSIS = "…"
        private const val SNIPPET_TOKENS = 32

        /** Every BMP character Kotlin's `trim()` strips, as the character set for SQLite's `trim(X, Y)`. */
        val TRIM_CHARS: String =
            buildString {
                for (code in 0..0xFFFF) {
                    val c = Char(code)
                    if (!c.isSurrogate() && c.isWhitespace()) append(c)
                }
            }

        private val uuidType = UUIDColumnType()
        private val textType = VarCharColumnType(4000)
        private val intType = IntegerColumnType()

        /**
         * The field whose snippet carries a highlight (first in [CorpusDescriptor.fields] order when several do) and
         * that snippet with the delimiters rendered as `<mark>`/`</mark>`. FTS5's `snippet()` returns the leading
         * text of a column with no highlight when the column holds no match, so the highlight is the only signal of
         * which column matched. With no highlight anywhere, the first field with any text is reported.
         */
        fun matchedField(
            descriptor: CorpusDescriptor,
            snippets: List<String>,
        ): Pair<String, String> {
            val index =
                snippets.indexOfFirst { OPEN in it }.takeIf { it >= 0 }
                    ?: snippets.indexOfFirst { it.isNotEmpty() }.takeIf { it >= 0 }
                    ?: 0
            val text = snippets.getOrElse(index) { "" }.replace(OPEN.toString(), MARK_OPEN).replace(CLOSE.toString(), MARK_CLOSE)
            return descriptor.fields[index].field to text
        }

        /** Renders the candidate statement for one corpus and analyzer. */
        fun buildStatement(
            descriptor: CorpusDescriptor,
            matchExpression: String,
            analyzer: Analyzer,
            filter: ScopeFilter,
            window: Int,
        ): Statement {
            val table = descriptor.table(analyzer)
            val owner = descriptor.ownerColumn
            val ctes = mutableListOf<String>()
            val cteArgs = mutableListOf<Pair<ColumnType<*>, Any?>>()
            val where = mutableListOf("$table MATCH ?")
            val whereArgs = mutableListOf<Pair<ColumnType<*>, Any?>>(textType to matchExpression)

            fun subtreeCte(
                name: String,
                seedPredicate: String,
            ): String =
                """
                $name(id, lvl) AS (
                    SELECT id, 1 FROM work_items WHERE $seedPredicate
                    UNION ALL
                    SELECT c.id, s.lvl + 1 FROM work_items c JOIN $name s ON c.parent_id = s.id
                    WHERE s.lvl < $MAX_TRAVERSAL_DEPTH
                )
                """.trimIndent()

            filter.itemId?.let {
                where += "$owner = ?"
                whereArgs += uuidType to it
            }
            filter.ancestorId?.let {
                // Bounded so cyclic parent_id data cannot spin the walk; scope is bound-and-continue.
                ctes += subtreeCte("anc_subtree", "id = ?")
                cteArgs += uuidType to it
                where += "$owner IN (SELECT id FROM anc_subtree)"
            }
            var needsItemRow = false
            filter.rootIds?.let { roots ->
                val placeholders = roots.joinToString(", ") { "?" }
                // A stamped depth-0 root (parent_id NULL, root_id = its own id) admits its tree through the indexed
                // root_id column; every other listed id (below root, unstamped, or missing) is walked.
                ctes +=
                    subtreeCte(
                        "scope_subtree",
                        "id IN ($placeholders) AND (parent_id IS NOT NULL OR root_id IS NULL OR root_id <> id)",
                    )
                roots.forEach { cteArgs += uuidType to it }
                where +=
                    "(wi.root_id IN (SELECT id FROM work_items" +
                    " WHERE id IN ($placeholders) AND parent_id IS NULL AND root_id = id)" +
                    " OR $owner IN (SELECT id FROM scope_subtree))"
                roots.forEach { whereArgs += uuidType to it }
                needsItemRow = true
            }
            filter.roles?.takeIf { it.isNotEmpty() }?.let { roles ->
                where += "wi.role IN (${roles.joinToString(", ") { "?" }})"
                roles.forEach { whereArgs += textType to it.name.lowercase() }
                needsItemRow = true
            }
            val requestTags =
                filter.tagsAny
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            if (requestTags.isNotEmpty()) {
                // 3.x semantics, unchanged: case-insensitive membership of the comma-separated list.
                where +=
                    requestTags.joinToString(" OR ", prefix = "(", postfix = ")") {
                        "(LOWER(wi.tags) = ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ?)"
                    }
                for (t in requestTags) {
                    whereArgs += textType to t
                    whereArgs += textType to "$t,%"
                    whereArgs += textType to "%,$t,%"
                    whereArgs += textType to "%,$t"
                }
                needsItemRow = true
            }
            filter.principalTagsAny?.takeIf { it.isNotEmpty() }?.let { allowed ->
                where +=
                    """
                    EXISTS (
                        WITH RECURSIVE tag_split(rest, tag) AS (
                            SELECT wi.tags || ',', NULL
                            UNION ALL
                            SELECT substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1), ?)
                            FROM tag_split WHERE rest <> ''
                        )
                        SELECT 1 FROM tag_split WHERE tag <> '' AND tag IN (${allowed.joinToString(", ") { "?" }})
                    )
                    """.trimIndent()
                whereArgs += textType to TRIM_CHARS
                allowed.forEach { whereArgs += textType to it }
                needsItemRow = true
            }

            val itemJoin = if (needsItemRow) descriptor.ownerItemJoin.orEmpty() else ""
            val noteKey = descriptor.noteKeyColumn ?: "NULL"
            val snippets =
                descriptor.fields.joinToString(",\n") {
                    "snippet($table, ${it.index}, '$OPEN', '$CLOSE', '$ELLIPSIS', $SNIPPET_TOKENS)"
                }
            val with = if (ctes.isEmpty()) "" else "WITH RECURSIVE " + ctes.joinToString(",\n")
            val sql =
                """
                $with
                SELECT ft.rank, ${descriptor.idColumn} AS doc_id, $owner AS owner_id, $noteKey AS note_key,
                $snippets
                FROM $table ft
                ${descriptor.baseJoin}
                $itemJoin
                WHERE ${where.joinToString("\n AND ")}
                ORDER BY ft.rank
                LIMIT ?
                """.trimIndent()
            return Statement(sql, cteArgs + whereArgs + listOf(intType to window))
        }
    }
}

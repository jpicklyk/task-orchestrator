package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Corpus

/** One indexed column of a corpus: its position in the FTS5 table and the field name a hit reports for it. */
internal data class FieldColumn(
    val index: Int,
    val field: String,
)

/**
 * Everything the search engine needs to know about one corpus. The engine is generic; each corpus is data.
 *
 * Every SQL fragment here is a fixed string (no caller input). Aliases: `ft` is the FTS5 table, `wi` the owning
 * `work_items` row.
 *
 * @property corpus The corpus described.
 * @property tables The FTS5 table that serves each analyzer.
 * @property baseJoin Joins `ft` to the content row the FTS5 table indexes.
 * @property idColumn The stable document id (selected as the candidate id).
 * @property ownerColumn The owning work-item id, readable without [ownerItemJoin].
 * @property noteKeyColumn The note key column, or null when the corpus has none.
 * @property fields The indexed columns in precedence order: when several match, the first is reported.
 * @property ownerItemJoin Joins the owning `work_items` row as `wi` when a filter needs its columns; null when
 *   [baseJoin] already provides `wi`.
 * @property fixedPredicates Predicates always applied, whatever the caller's filter (for example, archived rows are
 *   never searched).
 * @property extraColumns Columns selected after the snippets, read into the corpus's own hit facts in the same
 *   statement.
 * @property contentTable The content table the FTS5 tables index; its `id` column holds [idColumn]'s value.
 * @property contentRowidColumn The [contentTable] column the FTS5 rowid mirrors.
 */
internal data class CorpusDescriptor(
    val corpus: Corpus,
    val tables: Map<Analyzer, String>,
    val baseJoin: String,
    val idColumn: String,
    val ownerColumn: String,
    val noteKeyColumn: String?,
    val fields: List<FieldColumn>,
    val ownerItemJoin: String?,
    val fixedPredicates: List<String> = emptyList(),
    val extraColumns: List<String> = emptyList(),
    val contentTable: String,
    val contentRowidColumn: String,
) {
    fun table(analyzer: Analyzer): String = tables.getValue(analyzer)

    companion object {
        /** Work-item title, summary and description (V26 `work_items_fts_*`, columns title=0, summary=1, description=2). */
        val ITEM =
            CorpusDescriptor(
                corpus = Corpus.ITEM,
                tables = mapOf(Analyzer.SUBSTRING to "work_items_fts_trigram", Analyzer.STEMMED to "work_items_fts_text"),
                baseJoin = "JOIN work_items wi ON wi.rowid = ft.rowid",
                idColumn = "wi.id",
                ownerColumn = "wi.id",
                noteKeyColumn = null,
                fields = listOf(FieldColumn(0, "title"), FieldColumn(1, "summary"), FieldColumn(2, "description")),
                ownerItemJoin = null,
                contentTable = "work_items",
                contentRowidColumn = "rowid",
            )

        /** Note bodies (V7 `notes_fts_*`, column body=0), owned by the note's work item. */
        val NOTE =
            CorpusDescriptor(
                corpus = Corpus.NOTE,
                tables = mapOf(Analyzer.SUBSTRING to "notes_fts_trigram", Analyzer.STEMMED to "notes_fts_text"),
                baseJoin = "JOIN notes n ON n.rowid = ft.rowid",
                idColumn = "n.id",
                ownerColumn = "n.work_item_id",
                noteKeyColumn = "n.key",
                fields = listOf(FieldColumn(0, "body")),
                ownerItemJoin = "JOIN work_items wi ON wi.id = n.work_item_id",
                contentTable = "notes",
                contentRowidColumn = "rowid",
            )

        /**
         * Document title and body (V24 `documents_fts_*`, columns title=0, body=1, content rowid `doc_rowid`), owned by
         * the document's root item. Archived documents are never searched; kind, slug, title and version come back
         * with the match.
         */
        val DOCUMENT =
            CorpusDescriptor(
                corpus = Corpus.DOCUMENT,
                tables = mapOf(Analyzer.SUBSTRING to "documents_fts_trigram", Analyzer.STEMMED to "documents_fts_text"),
                baseJoin = "JOIN documents d ON d.doc_rowid = ft.rowid",
                idColumn = "d.id",
                ownerColumn = "d.root_item_id",
                noteKeyColumn = null,
                fields = listOf(FieldColumn(0, "title"), FieldColumn(1, "body")),
                ownerItemJoin = "JOIN work_items wi ON wi.id = d.root_item_id",
                fixedPredicates = listOf("d.archived_at IS NULL"),
                extraColumns = listOf("d.kind", "d.slug", "d.title", "d.version"),
                contentTable = "documents",
                contentRowidColumn = "doc_rowid",
            )

        fun of(corpus: Corpus): CorpusDescriptor =
            when (corpus) {
                Corpus.ITEM -> ITEM
                Corpus.NOTE -> NOTE
                Corpus.DOCUMENT -> DOCUMENT
            }
    }
}

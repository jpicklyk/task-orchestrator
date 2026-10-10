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
) {
    fun table(analyzer: Analyzer): String = tables.getValue(analyzer)

    companion object {
        /** Work-item title and summary (V7 `work_items_fts_*`, columns title=0, summary=1). */
        val ITEM =
            CorpusDescriptor(
                corpus = Corpus.ITEM,
                tables = mapOf(Analyzer.SUBSTRING to "work_items_fts_trigram", Analyzer.STEMMED to "work_items_fts_text"),
                baseJoin = "JOIN work_items wi ON wi.rowid = ft.rowid",
                idColumn = "wi.id",
                ownerColumn = "wi.id",
                noteKeyColumn = null,
                fields = listOf(FieldColumn(0, "title"), FieldColumn(1, "summary")),
                ownerItemJoin = null,
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
            )

        fun of(corpus: Corpus): CorpusDescriptor =
            when (corpus) {
                Corpus.ITEM -> ITEM
                Corpus.NOTE -> NOTE
            }
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.application.port.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.application.port.SearchHit
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.application.port.SearchScope
import io.github.jpicklyk.mcptask.current.application.service.search.RrfFusion
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import java.util.UUID

/** SQLite [SearchIndex]: ranked full-text search over the V7 FTS5 virtual tables (trigram + text fused with RRF). */
class SqliteSearchIndex(
    private val databaseManager: DatabaseManager
) : SearchIndex {
    /**
     * Full-text search on work items using the V7 FTS5 virtual tables.
     *
     * **Pagination:** see [SearchResult] for the contract. A fixed [FTS_CANDIDATE_ROWS] rows are
     * fetched per FTS table regardless of [offset], fused into a total order (score descending,
     * ties broken ascending by work-item id), capped at [MAX_FTS_RESULTS], and only then sliced
     * by [offset]/[limit] — so every page is a slice of the same ordered list.
     *
     * @param sanitizedFtsQuery FTS5 query string. Callers (T4 — QueryItemsTool / FtsQuerySanitizer)
     *   are responsible for sanitizing user input before calling this method. Passing raw user input
     *   may cause FTS5 syntax errors.
     * @param matchMode Which FTS table(s) to query.
     * @param scope     Optional structural scope filters (subtree, tags, role).
     * @param limit     Maximum hits to return (enforced at [MAX_FTS_RESULTS]; default 20).
     * @param offset    Zero-based page offset, applied after fusion and capping.
     */
    override suspend fun ftsSearch(
        sanitizedFtsQuery: String,
        matchMode: SearchMatchMode,
        scope: SearchScope?,
        limit: Int,
        offset: Int,
    ): SearchResult {
        val effectiveLimit = limit.coerceIn(1, MAX_FTS_RESULTS)

        return run {
            databaseManager.readTx {
                val uuidType = UUIDColumnType()

                // RRF scoring delegated to RrfFusion utility (application.service.search.RrfFusion).
                // k=60 is the standard Reciprocal Rank Fusion constant.

                // Build optional subtree CTE clause.
                // Singular path (scope.ancestorId): unchanged — single-root recursive CTE.
                // Plural path (scope.ancestorIds, non-null, non-empty): multi-root CTE with one
                //   seed row per root (OR semantics). An empty ancestorIds set → no-match stub.
                // Precedence: singular ancestorId wins if both are set (backward compat).
                val subtreeCteClause =
                    when {
                        scope?.ancestorId != null -> {
                            // SINGULAR path — behavior-identical to original; MCP query_items depends on this.
                            // The `lvl` column bounds the recursive member so cyclic parent_id data
                            // cannot spin the CTE forever. Search scope is bound-and-continue: missing
                            // nodes past the bound degrade the result, they do not fail the search.
                            """
                            WITH RECURSIVE subtree(id, lvl) AS (
                                SELECT id, 1 FROM work_items WHERE id = ?
                                UNION ALL
                                SELECT wi.id, s.lvl + 1 FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
                                WHERE s.lvl < $MAX_TRAVERSAL_DEPTH
                            )
                            """.trimIndent()
                        }
                        scope?.ancestorIds != null && scope.ancestorIds.isNotEmpty() -> {
                            // PLURAL path — seed CTE with one ? per root, then walk descendants.
                            val placeholders = scope.ancestorIds.joinToString(", ") { "?" }
                            """
                            WITH RECURSIVE subtree(id, lvl) AS (
                                SELECT id, 1 FROM work_items WHERE id IN ($placeholders)
                                UNION ALL
                                SELECT wi.id, s.lvl + 1 FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
                                WHERE s.lvl < $MAX_TRAVERSAL_DEPTH
                            )
                            """.trimIndent()
                        }
                        else -> ""
                    }

                // Build the WHERE clause additions for work_items column filters.
                // These are appended as literal fragments (values bound via positional params).
                val extraWhereParts = mutableListOf<String>()
                if (scope?.itemId != null) extraWhereParts.add("wi.id = ?")
                when {
                    scope?.ancestorId != null -> extraWhereParts.add("wi.id IN (SELECT id FROM subtree)")
                    scope?.ancestorIds != null && scope.ancestorIds.isNotEmpty() -> extraWhereParts.add("wi.id IN (SELECT id FROM subtree)")
                    scope?.ancestorIds != null && scope.ancestorIds.isEmpty() -> extraWhereParts.add("1 = 0") // empty scope → no hits
                }
                if (scope?.role != null) extraWhereParts.add("wi.role = ?")
                if (!scope?.tags.isNullOrEmpty()) {
                    // Tags are stored as a comma-separated string; OR-match each tag.
                    val tagConditions =
                        scope!!.tags!!.map { t ->
                            val escaped = t.trim().lowercase()
                            // SQLite LIKE on a TEXT column — safe because value comes from validated input, not FTS query.
                            "(LOWER(wi.tags) = ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ? OR LOWER(wi.tags) LIKE ?)"
                        }
                    extraWhereParts.add("(${tagConditions.joinToString(" OR ")})")
                }
                val extraWhere = if (extraWhereParts.isEmpty()) "" else " AND " + extraWhereParts.joinToString(" AND ")

                // Accumulate positional args shared across trigram / text queries.
                // Order: subtree anchors (if any), then fts query, then scope filters.
                fun buildArgs(): List<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>> {
                    val args = mutableListOf<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>>()
                    val varcharType = VarCharColumnType(4000)
                    when {
                        scope?.ancestorId != null -> args.add(uuidType to scope.ancestorId) // SINGULAR — unchanged
                        scope?.ancestorIds != null -> scope.ancestorIds.forEach { args.add(uuidType to it) } // PLURAL — one per root
                    }
                    args.add(varcharType to sanitizedFtsQuery) // FTS MATCH param
                    if (scope?.itemId != null) args.add(uuidType to scope.itemId)
                    if (scope?.role != null) args.add(varcharType to scope.role.name.lowercase())
                    if (!scope?.tags.isNullOrEmpty()) {
                        for (tag in scope!!.tags!!) {
                            val t = tag.trim().lowercase()
                            args.add(varcharType to t)
                            args.add(varcharType to "$t,%")
                            args.add(varcharType to "%,$t,%")
                            args.add(varcharType to "%,$t")
                        }
                    }
                    return args
                }

                // Collect trigram hits: { rowid, title, summary, rank, snippet_title, snippet_summary }
                data class FtsHit(
                    val rowid: Long,
                    val rank: Double,
                    val snippetTitle: String,
                    val snippetSummary: String,
                    val matchedTable: String,
                )

                val trigramHits = mutableMapOf<Long, FtsHit>()
                val textHits = mutableMapOf<Long, FtsHit>()

                // Helper to run one FTS query and populate a hit map.
                fun runFtsQuery(
                    ftsTable: String,
                    hitMap: MutableMap<Long, FtsHit>,
                    tableName: String,
                ) {
                    val sql =
                        """
                        ${subtreeCteClause.ifEmpty { "" }}
                        SELECT
                            ft.rowid,
                            ft.rank,
                            snippet($ftsTable, 0, '<mark>', '</mark>', '…', 32) AS snip_title,
                            snippet($ftsTable, 1, '<mark>', '</mark>', '…', 32) AS snip_summary,
                            wi.id AS wi_id
                        FROM $ftsTable ft
                        JOIN work_items wi ON wi.rowid = ft.rowid
                        WHERE $ftsTable MATCH ?$extraWhere
                        ORDER BY ft.rank
                        LIMIT $FTS_CANDIDATE_ROWS
                        """.trimIndent()

                    rawQuery(sql, buildArgs()) { rs ->
                        while (rs.next()) {
                            // Exposed's JdbcResult exposes getObject(Int|String) and getString(Int), but NOT
                            // getLong/getDouble. Use getObject for numerics and cast to Number. Select order:
                            // 1=ft.rowid, 2=ft.rank, 3=snip_title, 4=snip_summary, 5=wi_id.
                            val rowid = (rs.getObject(1) as Number).toLong()
                            val rank = (rs.getObject(2) as Number).toDouble()
                            val snippetTitle = rs.getString(3) ?: ""
                            val snippetSummary = rs.getString(4) ?: ""
                            hitMap[rowid] = FtsHit(rowid, rank, snippetTitle, snippetSummary, tableName)
                        }
                    }
                }

                when (matchMode) {
                    SearchMatchMode.SUBSTRING -> runFtsQuery("work_items_fts_trigram", trigramHits, "trigram")
                    SearchMatchMode.TEXT -> runFtsQuery("work_items_fts_text", textHits, "text")
                    SearchMatchMode.AUTO -> {
                        runFtsQuery("work_items_fts_trigram", trigramHits, "trigram")
                        runFtsQuery("work_items_fts_text", textHits, "text")
                    }
                }

                // Collect all rowids (union of both maps).
                val allRowIds = (trigramHits.keys + textHits.keys).toSet()
                if (allRowIds.isEmpty()) {
                    return@readTx SearchResult(
                        hits = emptyList(),
                        totalHits = 0,
                        nextOffset = null,
                    )
                }

                // Fetch work item UUIDs + metadata for matching rowids.
                val rowidToItem = mutableMapOf<Long, Pair<UUID, String>>() // rowid -> (uuid, tags)
                val rowidInClause = allRowIds.joinToString(",") { "?" }
                val rowidArgs =
                    allRowIds.map {
                        @Suppress("UNCHECKED_CAST")
                        (
                            org.jetbrains.exposed.v1.core
                                .LongColumnType() as org.jetbrains.exposed.v1.core.ColumnType<Any?>
                        ) to (it as Any?)
                    }
                exec("SELECT rowid, id FROM work_items WHERE rowid IN ($rowidInClause)", args = rowidArgs) { rs ->
                    while (rs.next()) {
                        val rowid = rs.getLong("rowid")
                        val rawId = rs.getObject("id")

                        @Suppress("UNCHECKED_CAST")
                        val uuid = uuidType.valueFromDB(rawId!!) as UUID
                        rowidToItem[rowid] = Pair(uuid, "")
                    }
                }

                // RRF fusion: assign row_number rank (1-based, ascending by FTS rank = most relevant first).
                data class FusedDoc(
                    val rowid: Long,
                    val itemId: UUID,
                    val trigramRank: Double?,
                    val textRank: Double?,
                    var trigramRowNum: Int = Int.MAX_VALUE,
                    var textRowNum: Int = Int.MAX_VALUE,
                )

                val docs = mutableMapOf<Long, FusedDoc>()
                for (rowid in allRowIds) {
                    val itemId = rowidToItem[rowid]?.first ?: continue
                    docs[rowid] =
                        FusedDoc(
                            rowid = rowid,
                            itemId = itemId,
                            trigramRank = trigramHits[rowid]?.rank,
                            textRank = textHits[rowid]?.rank,
                        )
                }

                // Assign row numbers based on rank (lower rank = more relevant = lower row number).
                trigramHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.trigramRowNum = idx + 1 }
                textHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.textRowNum = idx + 1 }

                // Compute the fused RRF score and impose a TOTAL order: score descending, then
                // ascending work-item id. Score alone is a partial order — RRF ties are common
                // (two docs matched by one table at the same row number score identically), and a
                // stable sort would fall back to map insertion order, which is the unspecified
                // order SQLite returned equally-ranked FTS rows in. A page boundary landing inside
                // such a tie group then duplicates one hit and skips another.
                val fused =
                    docs.values
                        .map { doc ->
                            val score =
                                (if (doc.trigramRowNum < Int.MAX_VALUE) RrfFusion.score(doc.trigramRowNum) else 0.0) +
                                    (if (doc.textRowNum < Int.MAX_VALUE) RrfFusion.score(doc.textRowNum) else 0.0)
                            doc to score
                        }.sortedWith(
                            compareByDescending<Pair<FusedDoc, Double>> { it.second }
                                .thenBy { it.first.itemId },
                        )

                // Cap BEFORE slicing so totalHits (and the nextOffset derived from it) describe the
                // whole query, not the requested page — identical at every offset.
                val capExceeded = fused.size > MAX_FTS_RESULTS
                val ranked = if (capExceeded) fused.take(MAX_FTS_RESULTS) else fused

                val totalHits = ranked.size
                val pageSlice = ranked.drop(offset).take(effectiveLimit)

                val hits =
                    pageSlice.map { (doc, score) ->
                        // Pick the best snippet: prefer the table with better rank (lower absolute value).
                        val trigramHit = trigramHits[doc.rowid]
                        val textHit = textHits[doc.rowid]
                        val primaryHit =
                            when {
                                trigramHit != null && textHit != null ->
                                    if ((trigramHit.rank) <= (textHit.rank)) trigramHit else textHit
                                trigramHit != null -> trigramHit
                                else -> textHit!!
                            }

                        // Determine which field the best snippet comes from.
                        val (field, snippet) =
                            if (primaryHit.snippetTitle.isNotEmpty()) {
                                "title" to primaryHit.snippetTitle
                            } else {
                                "summary" to primaryHit.snippetSummary
                            }

                        val matchedIn = mutableListOf<String>()
                        if (trigramHit != null) matchedIn.add("trigram")
                        if (textHit != null) matchedIn.add("text")

                        SearchHit(
                            kind = "item",
                            itemId = doc.itemId,
                            noteKey = null,
                            field = field,
                            snippet = snippet.ifEmpty { primaryHit.snippetSummary },
                            score = score,
                            matchedIn = matchedIn,
                            trigramRank = doc.trigramRank,
                            textRank = doc.textRank,
                        )
                    }

                val nextOffset =
                    if (offset + effectiveLimit < totalHits) offset + effectiveLimit else null

                SearchResult(
                    hits = hits,
                    totalHits = totalHits,
                    nextOffset = nextOffset,
                    truncated = capExceeded,
                )
            }
        }
    }
}

package io.github.jpicklyk.mcptask.current.application.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import java.util.UUID

/**
 * One fused document.
 *
 * @property corpus The corpus the document came from; its [Corpus.kind] is the hit kind.
 * @property primary The candidate whose field and snippet represent the document: when both analyzers matched it,
 *   the one with the better (lower) raw rank, the substring analyzer on a tie.
 * @property score The RRF fused score.
 * @property trigramRank Raw rank from the substring analyzer, null when it did not match.
 * @property textRank Raw rank from the stemmed analyzer, null when it did not match.
 * @property matchedIn Wire labels of the analyzers that matched, substring (`trigram`) first.
 */
data class RankedHit(
    val corpus: Corpus,
    val primary: Candidate,
    val score: Double,
    val trigramRank: Double?,
    val textRank: Double?,
    val matchedIn: List<String>,
)

/**
 * The one ranking step of search: Reciprocal Rank Fusion over every (corpus, analyzer) candidate list, then a total
 * order. Every caller fuses with the same constant [K].
 */
object Ranker {
    /** The RRF smoothing constant, the single source for fusion and for `explain.rrfK`. */
    val K: Int = RrfFusion.K.toInt()

    private const val TRIGRAM_LABEL = "trigram"
    private const val TEXT_LABEL = "text"

    /** The wire label of [analyzer] in `matchedIn`. */
    fun label(analyzer: Analyzer): String =
        when (analyzer) {
            Analyzer.SUBSTRING -> TRIGRAM_LABEL
            Analyzer.STEMMED -> TEXT_LABEL
        }

    private data class DocKey(
        val corpus: Corpus,
        val id: UUID,
    )

    /**
     * Fuses [lists] — per corpus, per analyzer, candidates in rank order (best first) — into one list ordered by
     * fused score descending, then [Corpus.kind], then document id ascending. A candidate's 1-based position in its
     * own list is its rank in the fusion; a document absent from a list contributes nothing from it.
     */
    fun rank(lists: Map<Corpus, Map<Analyzer, List<Candidate>>>): List<RankedHit> {
        val sources = mutableListOf<Map<DocKey, Int>>()
        val byAnalyzer = mutableMapOf<DocKey, MutableMap<Analyzer, Candidate>>()
        for ((corpus, perAnalyzer) in lists) {
            for ((analyzer, candidates) in perAnalyzer) {
                val positions = LinkedHashMap<DocKey, Int>()
                candidates.forEachIndexed { index, candidate ->
                    val key = DocKey(corpus, candidate.id)
                    if (key !in positions) {
                        positions[key] = index + 1
                        byAnalyzer.getOrPut(key) { mutableMapOf() }[analyzer] = candidate
                    }
                }
                sources += positions
            }
        }
        val scores = RrfFusion.fuse(sources, K.toDouble())
        return byAnalyzer
            .map { (key, matched) ->
                val trigram = matched[Analyzer.SUBSTRING]
                val text = matched[Analyzer.STEMMED]
                val primary =
                    when {
                        trigram != null && text != null -> if (trigram.rank <= text.rank) trigram else text
                        trigram != null -> trigram
                        else -> text!!
                    }
                RankedHit(
                    corpus = key.corpus,
                    primary = primary,
                    score = scores.getValue(key),
                    trigramRank = trigram?.rank,
                    textRank = text?.rank,
                    matchedIn = listOfNotNull(trigram?.let { TRIGRAM_LABEL }, text?.let { TEXT_LABEL }),
                )
            }.sortedWith(
                compareByDescending<RankedHit> { it.score }
                    .thenBy { it.corpus.kind }
                    .thenBy { it.primary.id },
            )
    }
}

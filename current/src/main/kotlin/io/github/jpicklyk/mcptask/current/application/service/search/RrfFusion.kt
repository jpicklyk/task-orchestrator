package io.github.jpicklyk.mcptask.current.application.service.search

/**
 * Reciprocal Rank Fusion (RRF) utility.
 *
 * ## Formula
 *
 * For each document that appears in one or more ranked source lists:
 * ```
 * score(doc) = Σ_sources  1.0 / (k + rank_in_source(doc))
 * ```
 * where `k = 60` is the standard RRF constant. Documents that do not appear in a source are
 * simply omitted from that source's contribution (they contribute 0 to the sum).
 *
 * A rank of 1 means the document is the best result in that source list.
 *
 * ## Why k=60
 *
 * k=60 was the value used in the original Cormack & Clarke (2009) RRF paper and remains the
 * de-facto default in production search systems (OpenSearch, Elasticsearch RRF). It balances
 * sensitivity to top-ranked results without letting a single strong match dominate the fusion.
 *
 * ## Usage
 *
 * ```kotlin
 * // Two source lists (trigramRanks and textRanks), keyed by a stable document identifier.
 * val trigramRanks: Map<Long, Int> = mapOf(10L to 1, 20L to 2, 30L to 3)
 * val textRanks:    Map<Long, Int> = mapOf(20L to 1, 10L to 2, 40L to 3)
 *
 * val fused: Map<Long, Double> = RrfFusion.fuse(trigramRanks, textRanks)
 * // fused[20L] ≈ 1/62 + 1/61 ≈ 0.0161 + 0.0164 = 0.0325  (highest — appeared in both)
 * // fused[10L] ≈ 1/61 + 1/62 ≈ 0.0164 + 0.0161 = 0.0325  (second — appeared in both)
 * // fused[30L] ≈ 1/63                             = 0.0159  (trigram only)
 * // fused[40L] ≈ 1/63                             = 0.0159  (text only)
 * ```
 */
object RrfFusion {
    /**
     * Standard Reciprocal Rank Fusion constant.
     * Documents at rank 1 receive score 1/(60+1) ≈ 0.0164.
     */
    const val K = 60.0

    /**
     * Compute the RRF score for a single document at the given rank in one source list.
     *
     * @param rank 1-based rank of the document in its source list (must be ≥ 1).
     * @param k    The RRF smoothing constant (default [K] = 60.0).
     * @return RRF contribution from this source (always > 0).
     */
    fun score(
        rank: Int,
        k: Double = K
    ): Double = 1.0 / (k + rank)

    /**
     * Fuse multiple ranked source lists into a single score map.
     *
     * Each source list is a `Map<DocId, Int>` where the value is the 1-based rank of that
     * document in the source (1 = best). Documents absent from a source contribute nothing
     * from that source.
     *
     * The returned map contains every document that appeared in at least one source, keyed
     * by its document ID with the total RRF fused score as the value. Sort the returned map
     * descending by value to get the final ranked order.
     *
     * @param sources Vararg of (docId → rank) maps, one per source list.
     * @return Map of docId → fused RRF score.
     */
    fun <DocId> fuse(vararg sources: Map<DocId, Int>): Map<DocId, Double> {
        val allDocIds = sources.flatMap { it.keys }.toSet()
        return allDocIds.associateWith { docId ->
            sources.sumOf { source ->
                val rank = source[docId]
                if (rank != null) score(rank) else 0.0
            }
        }
    }

    /**
     * Convenience overload for the common two-source case (trigram + text tables).
     *
     * @param source1 First ranked source (e.g. trigram table ranks).
     * @param source2 Second ranked source (e.g. text table ranks).
     * @return Map of docId → fused RRF score.
     */
    fun <DocId> fuse(
        source1: Map<DocId, Int>,
        source2: Map<DocId, Int>
    ): Map<DocId, Double> = fuse(*arrayOf(source1, source2))
}

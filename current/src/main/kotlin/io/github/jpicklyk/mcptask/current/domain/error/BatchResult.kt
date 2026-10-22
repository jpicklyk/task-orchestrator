package io.github.jpicklyk.mcptask.current.domain.error

/** Counts of succeeded and failed elements. */
data class BatchSummary(
    val ok: Int,
    val failed: Int
)

/** The top-level failure of a batch; [index] is the failing element for atomic batches, else null. */
data class BatchFailure(
    val error: DomainError,
    val index: Int?
)

/**
 * Per-element results of a batch write, in request order (positions are never compacted).
 * When [atomic], any element failure fails the whole batch.
 */
data class BatchResult<out T>(
    val results: List<Outcome<T>>,
    val atomic: Boolean = false
) {
    init {
        require(results.isNotEmpty()) { "results must not be empty" }
    }

    val summary: BatchSummary
        get() {
            val okCount = results.count { it is Outcome.Ok }
            return BatchSummary(ok = okCount, failed = results.size - okCount)
        }

    val ok: Boolean get() = results.all { it is Outcome.Ok }

    /**
     * Null when [ok]. Atomic: the lowest-index failing element error, unchanged, with its index.
     * Non-atomic: a `partial_failure` error (no detail, no fix) with a null index.
     */
    fun failure(): BatchFailure? {
        if (ok) return null
        if (atomic) {
            val index = results.indexOfFirst { it is Outcome.Err }
            return BatchFailure((results[index] as Outcome.Err).error, index)
        }
        val s = summary
        return BatchFailure(
            DomainError(
                code = ErrorCode.PARTIAL_FAILURE,
                message = "${s.failed} of ${results.size} elements failed."
            ),
            index = null
        )
    }
}

package io.github.jpicklyk.mcptask.current.application.port

/**
 * One page of a list read (envelope `data.items` + `data.page`).
 *
 * Envelope amendment U22: [total] is the full count before the cap on EVERY page (never counted from
 * the page's own offset); [truncated] is true exactly when items exist after this page
 * (`offset + items.size < total`); [next] is an opaque cursor, present exactly when [truncated]. When
 * [capped] is true the engine capped the result, so [total] is a lower bound rather than an exact count;
 * [truncated] and [next] keep their meaning.
 *
 * The constructor checks only what it can see without the offset; every list read builds its page with
 * [of], which derives [truncated] and [next] from the [PageRequest].
 */
data class ListPage<out T>(
    val items: List<T>,
    val total: Int,
    val truncated: Boolean,
    val capped: Boolean = false,
    val next: String? = null
) {
    init {
        require(total >= 0) { "total must not be negative" }
        require(items.size <= total) { "items.size (${items.size}) must not exceed total ($total)" }
        require((next != null) == truncated) { "next must be present exactly when truncated" }
    }

    companion object {
        /**
         * The page of [items] read at [page] from a list of [total] rows (U22): `truncated` is
         * `page.offset + items.size < total` and `next` is the cursor at `page.offset + items.size`.
         */
        fun <T> of(
            items: List<T>,
            total: Int,
            page: PageRequest,
            capped: Boolean = false
        ): ListPage<T> {
            val end = page.offset + items.size
            val truncated = end < total
            return ListPage(
                items = items,
                total = total,
                truncated = truncated,
                capped = capped,
                next = if (truncated) CursorCodec.encode(end) else null
            )
        }
    }
}

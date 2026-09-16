package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.PageParams
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `c471607b-003d-41a6-9cb7-24fc0591278e`
 * ("GET /transitions page parameter is uncapped, can overflow Int offset"), scenarios S13-S14
 * of the frozen `test-plan` note (queue phase).
 *
 * Oracles: `test-plan` S13-S14, citing `PageParams.kt`'s own KDoc ("Zero-based offset" — an
 * offset is non-negative by definition) and `api-rest.md` §7 Pagination's `hasMore` semantics
 * ("more rows remain after this page"). Both scenarios are EXISTING-SURFACE per `test-plan`: a
 * plain revert of the `offset` getter body (dropping the `Long`-widened, saturating computation
 * back to the 32-bit `Int` multiply) reproduces a negative offset for `PageParams(Int.MAX_VALUE,
 * 200)` and flips [S14]'s `hasMore` to `true`, giving behavioural red without any new symbol.
 *
 * BLINDNESS: authored from the item's `diagnosis` and `test-plan` notes (both queue-phase,
 * frozen before implementation, explicitly permitted by the `test-author` skill's blindness
 * rule) and the verbatim `PageParams`/`buildPageDto` declarations supplied by the orchestrator
 * (bugwave4-2026-09 dispatch contract, "DECLARATIONS for c471607b"). No `src/main` file was
 * opened to author this suite.
 */
class PageParamsOverflowTest {
    // ─── S13: PageParams.offset stays non-negative regardless of page magnitude ──────

    @Test
    fun `S13 offset never goes negative for a large page and pageSize`() {
        val overflowProne = PageParams(page = Int.MAX_VALUE, pageSize = 200)
        assertTrue(
            overflowProne.offset >= 0,
            "PageParams(Int.MAX_VALUE, 200).offset must stay non-negative (zero-based offset), got ${overflowProne.offset}",
        )

        val stillLarge = PageParams(page = 500_000, pageSize = 200)
        assertTrue(
            stillLarge.offset >= 0,
            "PageParams(500_000, 200).offset must stay non-negative, got ${stillLarge.offset}",
        )
    }

    // ─── S14: buildPageDto.hasMore is not corrupted by a saturated offset ────────────

    @Test
    fun `S14 buildPageDto hasMore is false when offset saturates and totalItems is small`() {
        val pp = PageParams(page = Int.MAX_VALUE, pageSize = 200)
        val dto = buildPageDto(listOf("a"), pp, totalItems = 10L)
        assertFalse(
            dto.hasMore,
            "With only 10 total items, a saturated (very large) offset must never leave rows " +
                "remaining after this page — pre-fix a wrapped-negative offset makes this true",
        )
    }
}

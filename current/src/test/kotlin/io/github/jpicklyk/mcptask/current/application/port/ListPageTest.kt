package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for [ListPage] and [PageRequest.from], scenario S11.
 *
 * Oracles: envelope section 2 "List data" as amended by U22 (`truncated` means items exist after this page; `next`
 * present only when truncated),
 * section 8 U15 (when `capped`, `total` is a lower bound and the two rules keep their meaning), plan section 3.3
 * ("default 50, max 200"; limit outside 1..200 is `invalid_request` on field `limit`; a bad cursor is `invalid_cursor`).
 * All scenarios here are NEW-SURFACE (the types do not exist before the item).
 */
class ListPageTest {
    // ---------------------------------------------------------------- ListPage invariants

    @Test
    fun `S11 a complete page has no next and is not truncated`() {
        val page = ListPage(listOf("a", "b"), total = 2, truncated = false)
        assertEquals(listOf("a", "b"), page.items)
        assertEquals(2, page.total)
        assertFalse(page.truncated)
        assertFalse(page.capped, "capped defaults to false")
        assertNull(page.next)
    }

    @Test
    fun `S11 a truncated page carries a next cursor`() {
        val page = ListPage(listOf("a", "b"), total = 143, truncated = true, next = "c")
        assertTrue(page.truncated)
        assertEquals("c", page.next)
    }

    @Test
    fun `S11 an empty page of an empty result is valid`() {
        val page = ListPage(emptyList<String>(), total = 0, truncated = false)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `S11 an empty page of a non-empty result is valid when truncated`() {
        val page = ListPage(emptyList<String>(), total = 5, truncated = true, next = "n")
        assertTrue(page.truncated)
    }

    @Test
    fun `S11 more items than total is rejected`() {
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a", "b", "c"), total = 2, truncated = false) }
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a"), total = 0, truncated = false) }
    }

    @Test
    fun `S11 a negative total is rejected`() {
        assertFailsWith<IllegalArgumentException> { ListPage(emptyList<String>(), total = -1, truncated = false) }
    }

    // Envelope amendment U22: truncated means items exist after this page (offset + items.size < total), so the
    // constructor no longer ties truncated to items.size < total; ListPage.of derives it from the PageRequest.
    @Test
    fun `S11 U22 the factory marks a page truncated exactly when items exist after it`() {
        val first = ListPage.of(listOf("a", "b"), total = 3, page = PageRequest(limit = 2, offset = 0))
        assertTrue(first.truncated)
        assertEquals(CursorCodec.encode(2), first.next)
        assertEquals(3, first.total)

        val last = ListPage.of(listOf("c"), total = 3, page = PageRequest(limit = 2, offset = 2))
        assertFalse(last.truncated)
        assertNull(last.next)
        assertEquals(3, last.total, "total is the full count on every page")

        val past = ListPage.of(emptyList<String>(), total = 3, page = PageRequest(limit = 2, offset = 4))
        assertFalse(past.truncated)
        assertNull(past.next)

        val capped = ListPage.of(listOf("a"), total = 5, page = PageRequest(limit = 1, offset = 0), capped = true)
        assertTrue(capped.capped && capped.truncated)
    }

    @Test
    fun `S11 next is present exactly when truncated - both mismatching directions are rejected`() {
        // truncated but no cursor
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a"), total = 2, truncated = true, next = null) }
        // a cursor on a complete page
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a", "b"), total = 2, truncated = false, next = "n") }
    }

    @Test
    fun `S11 capped keeps both rules - a capped complete page and a capped truncated page are valid, mismatches are not`() {
        val completeCapped = ListPage(listOf("a", "b"), total = 2, truncated = false, capped = true)
        assertTrue(completeCapped.capped)
        assertNull(completeCapped.next)

        val truncatedCapped = ListPage(listOf("a"), total = 2, truncated = true, capped = true, next = "n")
        assertTrue(truncatedCapped.capped)
        assertEquals("n", truncatedCapped.next)

        // (U22: a capped final page of 1 of 2 is valid, so that former mismatch case is gone)
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a", "b"), total = 2, truncated = false, capped = true, next = "n") }
        assertFailsWith<IllegalArgumentException> { ListPage(listOf("a", "b", "c"), total = 2, truncated = false, capped = true) }
    }

    // ---------------------------------------------------------------- PageRequest

    @Test
    fun `S11 the declared limits are 50 and 200`() {
        assertEquals(50, PageRequest.DEFAULT_LIMIT)
        assertEquals(200, PageRequest.MAX_LIMIT)
        assertEquals(PageRequest(limit = 50, offset = 0), PageRequest())
    }

    private fun ok(outcome: Outcome<PageRequest>): PageRequest {
        assertIs<Outcome.Ok<PageRequest>>(outcome, "expected Ok but was $outcome")
        return outcome.value
    }

    private fun limitViolation(outcome: Outcome<PageRequest>): ErrorDetail.InvalidRequest {
        assertIs<Outcome.Err>(outcome, "expected Err but was $outcome")
        assertEquals(ErrorCode.INVALID_REQUEST, outcome.error.code)
        val detail = outcome.error.detail
        assertIs<ErrorDetail.InvalidRequest>(detail)
        return detail
    }

    @Test
    fun `S11 absent limit and absent cursor default to 50 at offset 0`() {
        assertEquals(PageRequest(50, 0), ok(PageRequest.from(null, null)))
    }

    @Test
    fun `S11 the boundary limits 1 and 200 are accepted`() {
        assertEquals(1, ok(PageRequest.from(1, null)).limit)
        assertEquals(200, ok(PageRequest.from(200, null)).limit)
        assertEquals(199, ok(PageRequest.from(199, null)).limit)
    }

    @Test
    fun `S11 limit 0 and limit 201 are invalid_request on field limit`() {
        for (bad in listOf(0, 201)) {
            val detail = limitViolation(PageRequest.from(bad, null))
            assertEquals(1, detail.fields.size, "exactly one violation for limit=$bad")
            val violation = detail.fields.single()
            assertEquals("limit", violation.field)
            assertTrue(violation.reason.isNotBlank(), "reason for limit=$bad")
            assertTrue(violation.received?.contains(bad.toString()) == true, "received echoes $bad, was ${violation.received}")
        }
    }

    @Test
    fun `S11 probe negative and extreme limits are invalid_request`() {
        for (bad in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val detail = limitViolation(PageRequest.from(bad, null))
            assertEquals("limit", detail.fields.single().field, "limit=$bad")
        }
    }

    @Test
    fun `S10 a valid cursor supplies the offset and keeps the limit`() {
        val request = ok(PageRequest.from(25, CursorCodec.encode(100)))
        assertEquals(PageRequest(limit = 25, offset = 100), request)
    }

    @Test
    fun `S10 a cursor alone keeps the default limit`() {
        assertEquals(PageRequest(limit = 50, offset = 50), ok(PageRequest.from(null, "eyJvZmZzZXQiOjUwfQ")))
    }

    @Test
    fun `S10 an undecodable cursor is invalid_cursor even with a valid limit`() {
        val outcome = PageRequest.from(10, "not-a-cursor!!")
        assertIs<Outcome.Err>(outcome)
        assertEquals(ErrorCode.INVALID_CURSOR, outcome.error.code)
        assertNull(outcome.error.detail, "invalid_cursor defines no detail")
    }

    @Test
    fun `S10 an empty cursor string is invalid_cursor, not an absent cursor`() {
        val outcome = PageRequest.from(null, "")
        assertIs<Outcome.Err>(outcome)
        assertEquals(ErrorCode.INVALID_CURSOR, outcome.error.code)
    }
}

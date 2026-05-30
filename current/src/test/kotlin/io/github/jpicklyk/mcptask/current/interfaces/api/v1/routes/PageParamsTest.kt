package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.DEFAULT_PAGE
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.DEFAULT_PAGE_SIZE
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.MAX_PAGE_SIZE
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.buildPageDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.pageParams
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination.PageParams]
 * and [buildPageDto].
 *
 * Test coverage:
 * - Default page and pageSize applied when params absent
 * - pageSize capped at MAX_PAGE_SIZE
 * - page clamped to minimum 1
 * - hasMore=true when more items exist
 * - hasMore=false when on last page
 * - hasMore computed from totalItems when provided
 * - hasMore inferred from item count when totalItems null
 */
class PageParamsTest {
    @Test
    fun `pageParams returns defaults when no query params`() =
        testApplication {
            var capturedPage = -1
            var capturedSize = -1
            application {
                configureTestApp {
                    get("/test-pagination") {
                        val pp = call.pageParams()
                        capturedPage = pp.page
                        capturedSize = pp.pageSize
                        call.respond(HttpStatusCode.OK, "{}")
                    }
                }
            }
            client.get("/api/v1/test-pagination") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(DEFAULT_PAGE, capturedPage)
            assertEquals(DEFAULT_PAGE_SIZE, capturedSize)
        }

    @Test
    fun `pageParams caps pageSize at MAX_PAGE_SIZE`() =
        testApplication {
            var capturedSize = -1
            application {
                configureTestApp {
                    get("/test-pagination") {
                        capturedSize = call.pageParams().pageSize
                        call.respond(HttpStatusCode.OK, "{}")
                    }
                }
            }
            client.get("/api/v1/test-pagination?pageSize=99999") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(MAX_PAGE_SIZE, capturedSize)
        }

    @Test
    fun `pageParams clamps page to minimum 1`() =
        testApplication {
            var capturedPage = -1
            application {
                configureTestApp {
                    get("/test-pagination") {
                        capturedPage = call.pageParams().page
                        call.respond(HttpStatusCode.OK, "{}")
                    }
                }
            }
            client.get("/api/v1/test-pagination?page=-5") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(1, capturedPage)
        }

    @Test
    fun `buildPageDto hasMore true when totalItems exceeds offset plus count`() {
        val items = listOf("a", "b")
        val pp =
            io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination
                .PageParams(page = 1, pageSize = 2)
        val dto = buildPageDto(items, pp, totalItems = 5L)
        assertTrue(dto.hasMore, "Expected hasMore=true when 5 total, offset 0 + 2 returned")
    }

    @Test
    fun `buildPageDto hasMore false when all items returned`() {
        val items = listOf("a", "b")
        val pp =
            io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination
                .PageParams(page = 1, pageSize = 10)
        val dto = buildPageDto(items, pp, totalItems = 2L)
        assertFalse(dto.hasMore, "Expected hasMore=false when 2 total and 2 returned")
    }

    @Test
    fun `buildPageDto hasMore inferred from item count when totalItems null`() {
        // pageSize=2, got 2 items → infer hasMore=true (may be false positive on last page)
        val items = listOf("a", "b")
        val pp =
            io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination
                .PageParams(page = 1, pageSize = 2)
        val dto = buildPageDto(items, pp, totalItems = null)
        assertTrue(dto.hasMore, "Expected hasMore=true (inferred) when items.size == pageSize")
    }

    @Test
    fun `buildPageDto hasMore false inferred when fewer items than pageSize`() {
        val items = listOf("a")
        val pp =
            io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination
                .PageParams(page = 1, pageSize = 10)
        val dto = buildPageDto(items, pp, totalItems = null)
        assertFalse(dto.hasMore, "Expected hasMore=false when fewer items than pageSize")
    }

    @Test
    fun `buildPageDto includes correct page and pageSize`() {
        val items = listOf("x", "y", "z")
        val pp =
            io.github.jpicklyk.mcptask.current.interfaces.api.v1.pagination
                .PageParams(page = 3, pageSize = 7)
        val dto = buildPageDto(items, pp, totalItems = 25L)
        assertEquals(3, dto.page)
        assertEquals(7, dto.pageSize)
        assertEquals(25L, dto.totalItems)
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S12 (frozen test-plan `b703941a`, item 71dea46e): REST `Idempotency-Key` rejection is
 * UNCHANGED parity, not a new gate — [WriteIdempotency.parseIdempotencyKey] already rejects a
 * malformed key with 400 before this bug wave (it is not among the implementer's changed files
 * for 71dea46e). This test LOCKS IN that pre-existing contract across the three write endpoints
 * so it cannot silently regress alongside the new MCP-side `requestId` gate, and adds the hard
 * "resource NOT created/modified" assertion the pre-existing `malformed Idempotency-Key returns
 * 400` test in [WriteRoutesTest] (POST /items only) does not make.
 *
 * Oracle: O3 `current/docs/api-rest.md` §5 L241 "Malformed key -> 400";
 * `WriteIdempotency.kt:105-113` (`parseIdempotencyKey`) / `:58-80` (`runWithIdempotency`) — an
 * [IdempotencyKeyResult.Invalid] short-circuits the route (`return@post` / `return@patch` /
 * `return@put`) before the write path ever runs.
 */
class IdempotencyKeyRejectionTest {
    @Test
    fun `POST items with malformed Idempotency-Key returns 400 validation_error and creates nothing`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", "not-a-uuid")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Be Created"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error"), "Should report validation_error: $body")

            // Hard assertion: nothing was created.
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(items is Result.Success)
            assertTrue(
                (items as Result.Success).data.items.none { it.title == "Should Not Be Created" },
                "Malformed Idempotency-Key must not create an item"
            )
        }

    @Test
    fun `PATCH items id with malformed Idempotency-Key returns 400 validation_error and leaves item unmodified`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Untouched Title", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    header("Idempotency-Key", "not-a-uuid")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Be Applied"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error"), "Should report validation_error: $body")

            // Hard assertion: the patch was never applied.
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "Untouched Title",
                (persisted as Result.Success).data.title,
                "Malformed Idempotency-Key must leave the item unmodified"
            )
        }

    @Test
    fun `PUT note with malformed Idempotency-Key returns 400 validation_error and creates no note`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Note Host", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.put("/api/v1/items/${item.id}/notes/should-not-exist") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", "not-a-uuid")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"Should Not Be Written"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error"), "Should report validation_error: $body")

            // Hard assertion: no note was created.
            val noteResult =
                runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "should-not-exist") }
            assertTrue(noteResult is Result.Success)
            assertNull(
                (noteResult as Result.Success).data,
                "Malformed Idempotency-Key must not create the note"
            )
        }
}

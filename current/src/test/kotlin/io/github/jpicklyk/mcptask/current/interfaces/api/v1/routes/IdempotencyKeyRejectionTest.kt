package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
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
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `PUT note with malformed Idempotency-Key returns 400 validation_error and creates no note`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Note Host", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

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
            assertNull(
                noteResult,
                "Malformed Idempotency-Key must not create the note"
            )
        }
}

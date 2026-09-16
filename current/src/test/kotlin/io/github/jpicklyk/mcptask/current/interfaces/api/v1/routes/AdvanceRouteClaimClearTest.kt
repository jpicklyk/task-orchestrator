package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Bug `3785f37a`, REST surface (S9 of the frozen test-plan): a claim held by one MCP agent must
 * still be cleared when a DIFFERENT identity — the REST API, which runs `enforceOwnership = false`
 * (`ItemWriteRoutes.kt:767` -> `advanceService.advance(..., enforceOwnership = false, ...)`) —
 * drives the item to TERMINAL via `POST /items/{id}/advance`.
 *
 * EXISTING-SURFACE: the route and [AdvanceRequestDto] are unchanged; only the shared
 * [io.github.jpicklyk.mcptask.current.application.service.AdvanceService] pipeline behind them
 * now clears claim fields at terminal. A narrowest revert of that pipeline change alone turns
 * this test red.
 *
 * Oracle O5 (`api-reference.md:1135` / `:599-600`): REST does not enforce claim ownership, so the
 * advance must succeed despite the foreign claim — same precondition as
 * [WriteRoutesTest]'s `POST advance on item CLAIMED by another MCP agent` test, which stays green
 * here because that test advances QUEUE->WORK (non-terminal) and this one advances WORK->TERMINAL.
 *
 * Fixture note: claim fields are set directly via `create()` (H2 supports persisting them; only
 * `claim()`'s SQLite-dialect SQL does not run on H2 — see [SQLiteWorkItemClaimFieldsTest] /
 * [WriteRoutesTest]'s identical fixture technique), truncated to millisecond precision so the
 * persisted round-trip equality check is exact.
 */
class AdvanceRouteClaimClearTest {
    @Test
    fun `S9 REST complete on an item claimed by a different MCP agent clears the claim and discloses nothing`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "Claimed then REST-completed",
                                role = Role.WORK,
                                depth = 0,
                                claimedBy = "agent-A",
                                claimedAt = now,
                                claimExpiresAt = now.plusSeconds(900),
                                originalClaimedAt = now,
                            ),
                        ).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    // WRITE_TOKEN is the API identity — a different actor than the "agent-A" holder.
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"complete"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "REST advance must succeed despite the foreign claim")
            val body = response.bodyAsText()
            assertFalse(body.contains("claimedBy"), "response must not disclose claimedBy: $body")
            assertFalse(body.contains("agent-A"), "response must not leak the claim holder id: $body")

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            val data = (persisted as Result.Success).data
            assertEquals(Role.TERMINAL, data.role, "item must have reached TERMINAL")
            assertNull(data.claimedBy, "claimedBy must be cleared on the persisted row")
            assertNull(data.claimedAt, "claimedAt must be cleared on the persisted row")
            assertNull(data.claimExpiresAt, "claimExpiresAt must be cleared on the persisted row")
            assertNull(data.originalClaimedAt, "originalClaimedAt must be cleared on the persisted row")
        }
}

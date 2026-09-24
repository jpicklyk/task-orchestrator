package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Independent test-author coverage for item `128de55f` — REST parity scenarios S11-S12 of the
 * frozen `test-plan` note (queue phase): `GET /api/v1/items` forwards `orderBy`/`orderDir` into
 * the same repository sort mapping `query_items` uses, and rejects unresolvable values with a
 * structured 400 before any repository call. sortBy/sortOrder tool-level scenarios S1-S10 live in
 * `QueryItemsToolSortByTest`; global-overview terminal-retention scenarios S13-S14 live in
 * `QueryItemsOverviewTerminalRetentionTest`.
 *
 * Oracles: `test-plan` note ("[DX] same repo mapping" for S11; the declarations block's route
 * KDoc for S12: an `orderBy` `ItemSortFields.canonicalField(...)` cannot resolve -> HTTP 400 with
 * `ErrorDto("bad_request", "Invalid orderBy: <value>")`; an `orderDir` whose lowercase is not in
 * `ItemSortFields.ORDERS` -> HTTP 400 with `ErrorDto("bad_request", "Invalid orderDir: <value>")`,
 * both checks running before any repository call).
 *
 * Both scenarios are EXISTING-SURFACE per `test-plan`: `GET /api/v1/items`, `ErrorDto` and the
 * `orderBy`/`orderDir` query params all predate this fix (only their validation/mapping behavior
 * changes), so a plain revert of the fix yields behavioural red directly.
 *
 * BLINDNESS: authored from the item's `diagnosis`/`test-plan` notes (queue-phase, frozen before
 * implementation) and the orchestrator-supplied declarations block (`ErrorDto`, the `ItemRoutes`
 * `orderBy`/`orderDir` KDoc), plus existing test conventions in this package (`ItemRoutesTest.kt`'s
 * `buildH2RepositoryProvider`/`configureTestApp`/`TEST_TOKEN` harness and JSON-body pagination
 * assertions, `PaginationBoundsTest.kt`'s `Json.parseToJsonElement` + `ErrorDto` field assertions).
 * No `src/main` file was opened to author this suite.
 */
class ItemRoutesOrderByTest {
    // ──────────────────────────────────────────────
    // S11 — orderBy/orderDir forwarded into the same repository sort mapping
    // ──────────────────────────────────────────────

    @Test
    fun `S11 GET items orderBy modifiedAt orderDir desc orders newest-modified first`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val t1 = Instant.parse("2025-01-01T00:00:00Z")
            val t2 = Instant.parse("2025-06-01T00:00:00Z")
            val t3 = Instant.parse("2025-12-01T00:00:00Z")
            runBlocking {
                // createdAt: A < B < C. modifiedAt: C < B < A (reversed) — same fixture as
                // QueryItemsToolSortByTest S1.
                repo.workItemRepository().create(
                    WorkItem(title = "A", depth = 0, createdAt = t1, modifiedAt = t3, roleChangedAt = t1)
                )
                repo.workItemRepository().create(
                    WorkItem(title = "B", depth = 0, createdAt = t2, modifiedAt = t2, roleChangedAt = t2)
                )
                repo.workItemRepository().create(
                    WorkItem(title = "C", depth = 0, createdAt = t3, modifiedAt = t1, roleChangedAt = t3)
                )
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }

            val response =
                client.get("/api/v1/items?orderBy=modifiedAt&orderDir=desc") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val titles = json["items"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            assertEquals(listOf("A", "B", "C"), titles, "Expected newest-modified (A) first: $titles")
        }

    // ──────────────────────────────────────────────
    // S12 — invalid orderBy / orderDir rejected with structured 400 before any repository call
    // ──────────────────────────────────────────────

    @Test
    fun `S12 GET items rejects an unresolvable orderBy with 400 bad_request`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Only Item", depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }

            val response =
                client.get("/api/v1/items?orderBy=bogus") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "bad_request",
                json["error"]?.jsonPrimitive?.content,
                "error code: $json"
            )
        }

    @Test
    fun `S12 GET items rejects an unresolvable orderDir with 400 bad_request`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Only Item", depth = 0))
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }

            val response =
                client.get("/api/v1/items?orderDir=sideways") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "bad_request",
                json["error"]?.jsonPrimitive?.content,
                "error code: $json"
            )
        }
}

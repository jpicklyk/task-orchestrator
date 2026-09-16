package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `c7751104` (frozen `test-plan` note, queue phase),
 * route-level half: the two `ItemWriteRoutes` scenarios the plan assigns to this file.
 *
 * Oracles (frozen before this file existed): `current/docs/api-rest.md` §5 idempotency contract —
 * replay is keyed by `(actorId, Idempotency-Key)` with NO body hash, and returns the FIRST
 * request's captured status/body verbatim regardless of what a same-key request's body contains
 * (the `diagnosis` note's "Correction to the dispatch prompt" — there is no
 * same-key-different-body 409/422 conflict here). The PATCH status-precedence oracle
 * (`ItemWriteRoutes.kt:436-441` per the dispatch contract's declarations, restated in
 * `diagnosis`'s Risk flag 1): a missing `If-Match` is `400 precondition_required` and must be
 * detected BEFORE the body is ever parsed, even when the body is malformed JSON — hoisting the
 * body read out of the idempotency lock must never let `validation_error` race ahead of it.
 *
 * `WriteRoutesTest.kt`'s `IdempotencyTest` class (same-key/same-body replay, different-key
 * re-execution, malformed-key 400) and `ItemPatchConflictMappingTest.kt`'s S5
 * (missing-If-Match/precondition_required with a well-formed body) are untouched and stay green;
 * this file adds the different-body replay and the malformed+no-If-Match ordering they do not
 * cover.
 *
 * Arbitration record: a prior test author disclosed a src/main over-read and was replaced before
 * any test was written for this item — this file is a fresh, independent authorship pass.
 */
class ItemWriteIdempotencyConcurrencyTest {
    // -------------------------------------------------------------------------
    // S5 HAPPY / EXISTING-SURFACE — same key, different bodies: verbatim replay of the FIRST
    // response, exactly one item persisted
    // -------------------------------------------------------------------------

    @Test
    fun `S5 same Idempotency-Key with different bodies replays the first response verbatim`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val key = UUID.randomUUID().toString()
            val first =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"First Body"}""")
                }
            val second =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Second Body Completely Different"}""")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(first.status, second.status)
            val firstBody = first.bodyAsText()
            val secondBody = second.bodyAsText()
            assertEquals(
                firstBody,
                secondBody,
                "a same-key replay must be byte-identical to the FIRST response regardless of the second body",
            )
            assertTrue(firstBody.contains("First Body"), "the cached response must reflect the FIRST request's title: $firstBody")
            assertFalse(
                secondBody.contains("Second Body Completely Different"),
                "the second body must never be executed — this is not a 409/422 conflict contract",
            )

            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val firstTitleCount = (items as Result.Success).data.items.count { it.title == "First Body" }
            val secondTitleCount = items.data.items.count { it.title == "Second Body Completely Different" }
            assertEquals(1, firstTitleCount, "exactly one item must be persisted for a replayed idempotency key")
            assertEquals(0, secondTitleCount, "the second body must never reach persistence")
        }

    // -------------------------------------------------------------------------
    // S6 FAILURE / EXISTING-SURFACE — malformed body, no If-Match: precondition_required must
    // still win, proving the hoisted body read did not reorder status precedence
    // -------------------------------------------------------------------------

    @Test
    fun `S6 malformed body without If-Match returns precondition_required not validation_error`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "S6 Target", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{ this is not valid json """)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "precondition_required",
                json["error"]?.jsonPrimitive?.content,
                "an absent If-Match must short-circuit BEFORE the body is ever parsed, even when the body is malformed JSON",
            )

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "S6 Target",
                (persisted as Result.Success).data.title,
                "a malformed, never-parsed body must never be applied to the item",
            )
        }

    // -------------------------------------------------------------------------
    // Probe (per test-plan: record every probe attempted, including no-finding ones)
    // -------------------------------------------------------------------------

    @Test
    fun `probe mixed-case Idempotency-Key hex resolves to the same cache entry`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val key = UUID.randomUUID()
            val lower = key.toString().lowercase()
            val upper = key.toString().uppercase()

            val first =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", lower)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Mixed Case First"}""")
                }
            val second =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", upper)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Mixed Case Second"}""")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(
                first.bodyAsText(),
                second.bodyAsText(),
                "a case-only difference in the UUID hex must resolve to the same idempotency entry",
            )

            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (items as Result.Success).data.items.count { it.title == "Mixed Case First" }
            assertEquals(1, matching)
        }
}

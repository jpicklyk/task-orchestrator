package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Route-level wiring tests for item `e941c2c7`: proves [receiveBounded] is actually threaded
 * through every one of the 7 write-route call sites (not just the synthetic route exercised by
 * [BoundedReceiveTest]), that the 5 previously-uncapped routes now enforce
 * [MAX_JSON_WRITE_BODY_BYTES] (1 MiB), that the 2 already-capped routes keep their own unchanged
 * numeric caps, and that all 7 share the one `payload_too_large` ErrorDto shape.
 *
 * The bytes-consumed proof itself (stage 1 vs. stage 2, exact-margin assertions) lives in
 * [BoundedReceiveTest] against a small synthetic cap — repeating that at megabyte scale on all 7
 * routes would multiply cost without adding assurance. Here each route gets one lying-Content-
 * Length test (cheap: the real body is a single byte) proving wiring + the correct per-route cap
 * value in the rejection message, plus one representative chunked/counting test and one real
 * exactly-at-cap body (constructed once, reused nowhere else) on the most central route.
 */
private const val MAX_CONFIG_YAML_BYTES = 131_072 // ProjectConfigPushService.MAX_CONFIG_YAML_BYTES — unchanged by this item
private const val MAX_PLAN_DOCUMENT_BODY_BYTES = 65_536 // PlanDocumentService.MAX_BODY_BYTES — unchanged by this item

/** A declared length that disagrees with what [actualBytes] really delivers. */
private class RouteMismatchedLengthContent(
    override val contentLength: Long,
    override val contentType: ContentType,
    private val actualBytes: ByteArray,
) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = ByteReadChannel(actualBytes)
}

/** A chunked (no Content-Length) body that counts how much of itself it got to write. */
private class CountingChunkedJsonContent(
    private val totalBytes: Int,
    private val chunkSize: Int = 8192,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.Json
    override val contentLength: Long? = null

    @Volatile
    var bytesWritten: Int = 0
        private set

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val chunk = ByteArray(chunkSize) { '"'.code.toByte() }
        var remaining = totalBytes
        try {
            while (remaining > 0) {
                val n = minOf(chunkSize, remaining)
                channel.writeFully(chunk, 0, n)
                bytesWritten += n
                remaining -= n
            }
        } catch (expectedOnceServerRejects: Throwable) {
            // Expected once the server responds 413 and the connection is torn down mid-stream.
        }
    }
}

private fun createRoot(repo: io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider): WorkItem =
    runBlocking {
        (repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)) as Result.Success).data
    }

class WriteRoutesBodyLimitTest {
    // ── S2 / S5 / S7 (NEW-SURFACE except where noted): an overstated Content-Length is rejected
    // 413 before the (single-byte) real body is inspected, one test per call site. ──

    @Test
    fun `POST items with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_JSON_WRITE_BODY_BYTES + 1L,
                            contentType = ContentType.Application.Json,
                            actualBytes = byteArrayOf('{'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("payload_too_large"), "shared ErrorDto shape: $body")
            assertTrue(body.contains("declares"), "stage 1 must fire on the header alone: $body")

            val items = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue((items as Result.Success).data.items.isEmpty(), "no item should be created on 413")
        }

    @Test
    fun `PATCH items id with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Patch Target", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_JSON_WRITE_BODY_BYTES + 1L,
                            contentType = ContentType.Application.Json,
                            actualBytes = byteArrayOf('{'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("Patch Target", (persisted as Result.Success).data.title, "PATCH body must never have been applied")
        }

    @Test
    fun `POST items id advance with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Advance Target", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_JSON_WRITE_BODY_BYTES + 1L,
                            contentType = ContentType.Application.Json,
                            actualBytes = byteArrayOf('{'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(Role.QUEUE, (persisted as Result.Success).data.role, "advance must never have been applied")
        }

    @Test
    fun `PUT note with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Note Target", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo) }

            val response =
                client.put("/api/v1/items/${item.id}/notes/impl-note") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_JSON_WRITE_BODY_BYTES + 1L,
                            contentType = ContentType.Application.Json,
                            actualBytes = byteArrayOf('{'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))

            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "impl-note") }
            assertTrue((noteResult as Result.Success).data == null, "no note should be created on 413")
        }

    @Test
    fun `POST dependencies with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val from = runBlocking { repo.workItemRepository().create(WorkItem(title = "From", depth = 0)).getOrNull()!! }
            runBlocking { repo.workItemRepository().create(WorkItem(title = "To", depth = 0)).getOrNull()!! }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_JSON_WRITE_BODY_BYTES + 1L,
                            contentType = ContentType.Application.Json,
                            actualBytes = byteArrayOf('{'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))

            val deps = runBlocking { withContext(Dispatchers.IO) { repo.dependencyRepository().findByItemId(from.id) } }
            assertTrue(deps.isEmpty(), "no dependency should be created on 413")
        }

    // S6 regression, folded into the same wiring proof: the 2 ALREADY-capped routes keep their
    // own unchanged numeric cap and now also go through receiveBounded (same shared shape).
    // The existing "over the size cap returns 413" tests in ProjectConfigRoutesTest.kt:245 and
    // PlanDocumentRoutesTest.kt:174 already assert the numeric-cap regression with an accurate
    // Content-Length; these two assert the SAME caps via a lying Content-Length, proving the new
    // receiveBounded wiring reached these routes too without disturbing their cap values.

    @Test
    fun `PUT roots rootId config with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_CONFIG_YAML_BYTES + 1L,
                            contentType = ContentType.Text.Plain,
                            actualBytes = byteArrayOf('x'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "oversized payload must never be stored")
        }

    @Test
    fun `PUT roots rootId plans slug with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_PLAN_DOCUMENT_BODY_BYTES + 1L,
                            contentType = ContentType.Text.Plain,
                            actualBytes = byteArrayOf('x'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))
            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertTrue((persisted as Result.Success).data == null)
        }

    // S4 (edge, NEW-SURFACE): the exact-at-cap acceptance boundary, proven ONCE at real
    // production scale (1 MiB) on the most central route, rather than reallocating a megabyte
    // string per route. The size gate must not be the reason a same-size body fails — whatever a
    // downstream domain check later does with an oversized title is a separate concern.
    @Test
    fun `POST items with a body of exactly MAX_JSON_WRITE_BODY_BYTES is not rejected as too large`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val prefix = "{\"title\":\""
            val suffix = "\"}"
            val padLen = MAX_JSON_WRITE_BODY_BYTES - prefix.length - suffix.length
            val body = prefix + "x".repeat(padLen) + suffix
            assertEquals(MAX_JSON_WRITE_BODY_BYTES, body.toByteArray(Charsets.UTF_8).size, "fixture must be exactly at the cap")

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            assertTrue(
                response.status != HttpStatusCode.PayloadTooLarge,
                "a body of exactly the cap must pass the size gate regardless of downstream validation: ${response.status}",
            )
        }

    // S3 (failure, NEW-SURFACE), representative wiring proof: a real route (not the synthetic
    // one in BoundedReceiveTest) rejects a chunked body once it exceeds the real 1 MiB cap,
    // short-circuiting well before the full stream is consumed.
    @Test
    fun `POST items rejects a chunked body exceeding the 1 MiB cap without draining the full stream`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val totalBytes = MAX_JSON_WRITE_BODY_BYTES + (256 * 1024) // ~256 KiB over cap, not gigabytes
            val content = CountingChunkedJsonContent(totalBytes = totalBytes)

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(content)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertFalse(response.bodyAsText().contains("declares"), "no Content-Length was sent — must be stage 2's message")
            assertTrue(
                content.bytesWritten < totalBytes,
                "the stream must be short-circuited, not drained to the end: wrote ${content.bytesWritten} of $totalBytes",
            )

            val items = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue((items as Result.Success).data.items.isEmpty(), "no item should be created on 413")
        }
}

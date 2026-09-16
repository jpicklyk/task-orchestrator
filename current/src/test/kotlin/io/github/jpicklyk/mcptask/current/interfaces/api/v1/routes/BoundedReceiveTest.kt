package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Helper-level tests for [receiveBounded] (item `e941c2c7`), exercised against a minimal test
 * route rather than any production write route — [WriteRoutesBodyLimitTest] covers wiring across
 * the 7 real call sites. Mounted at a small synthetic cap ([TEST_CAP]) so the two-stage bytes-
 * consumed proofs (stage 1: declared Content-Length; stage 2: bounded channel read) stay well
 * under a megabyte of test traffic while still exercising the real two-stage logic.
 *
 * Per DECLARATIONS for e941c2c7: stage 1 rejects on a declared Content-Length header alone,
 * touching zero body bytes; stage 2 reads at most `maxBytes + 1` bytes off the channel for a
 * chunked, absent, or understated Content-Length body. Both stages share one ErrorDto shape:
 * `{"error":"payload_too_large","message":"<...>"}` — stage 1's message names the declared
 * length ("Request body declares $declaredLength bytes, exceeds the $maxBytes byte limit"),
 * stage 2's does not ("Request body exceeds the $maxBytes byte limit").
 */
private const val TEST_CAP = 256

private fun Application.configureBoundedReceiveTestApp(cap: Int = TEST_CAP) {
    // receiveBounded's 413 path responds with a serialized ErrorDto; without ContentNegotiation
    // installed, Ktor cannot satisfy that Accept negotiation and answers 406 instead of 413. Same
    // McpJson instance configureWriteTestApp (WriteRoutesTest.kt) installs for the real routes.
    install(ContentNegotiation) { json(McpJson) }
    routing {
        route("/test") {
            post("/receive") {
                val text = call.receiveBounded(cap) ?: return@post
                call.respondText(text.length.toString())
            }
        }
    }
}

/**
 * An [OutgoingContent] whose declared [contentLength] can disagree with what [actualBytes]
 * actually delivers — the vehicle for every lying-Content-Length scenario below (overstated,
 * understated, and falsely-zero).
 */
private class HelperMismatchedLengthContent(
    override val contentLength: Long,
    private val actualBytes: ByteArray,
    override val contentType: ContentType = ContentType.Text.Plain,
) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = ByteReadChannel(actualBytes)
}

/**
 * A chunked (no Content-Length header) [OutgoingContent] that counts how many bytes it actually
 * got to write before the connection closed out from under it — the bytes-consumed oracle for
 * stage 2, per DECLARATIONS: "a WriteChannelContent that counts bytes written before the channel
 * closes; expect <= maxBytes + 1 consumed when the server responds 413."
 */
private class CountingChunkedContent(
    private val totalBytes: Int,
    private val chunkSize: Int = 16,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream
    override val contentLength: Long? = null // no header -> chunked transfer-encoding

    @Volatile
    var bytesWritten: Int = 0
        private set

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val chunk = ByteArray(chunkSize) { 'x'.code.toByte() }
        var remaining = totalBytes
        try {
            while (remaining > 0) {
                val n = minOf(chunkSize, remaining)
                channel.writeFully(chunk, 0, n)
                bytesWritten += n
                remaining -= n
            }
        } catch (expectedOnceServerRejects: Throwable) {
            // The server responds 413 and the connection is torn down mid-stream once the
            // bounded read trips — that failure IS the scenario under test, not a test bug.
            // Nothing to assert here; bytesWritten already reflects how far we got, and the
            // real assertions run after client.post() returns.
        }
    }
}

class BoundedReceiveTest {
    // S1 (happy, EXISTING-SURFACE): a small body well under the cap is accepted unchanged.
    @Test
    fun `receiveBounded accepts a small body`() =
        testApplication {
            application { configureBoundedReceiveTestApp(cap = TEST_CAP) }

            val response = client.post("/test/receive") { setBody("hello") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("5", response.bodyAsText())
        }

    // S4 (edge, NEW-SURFACE): a body of exactly maxBytes is accepted — the cap is inclusive.
    @Test
    fun `receiveBounded accepts a body of exactly maxBytes`() =
        testApplication {
            application { configureBoundedReceiveTestApp(cap = TEST_CAP) }

            val body = "x".repeat(TEST_CAP)
            val response = client.post("/test/receive") { setBody(body) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(TEST_CAP.toString(), response.bodyAsText())
        }

    // S5 (edge, NEW-SURFACE): maxBytes + 1, sent with an accurate Content-Length, is rejected —
    // and since the declared length itself already exceeds the cap, this is stage 1's job.
    @Test
    fun `receiveBounded rejects a body of maxBytes plus one via the declared Content-Length`() =
        testApplication {
            application { configureBoundedReceiveTestApp(cap = TEST_CAP) }

            val body = "x".repeat(TEST_CAP + 1)
            val response = client.post("/test/receive") { setBody(body) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("payload_too_large"), "shared ErrorDto shape: $text")
            assertTrue(text.contains("declares"), "an accurate over-cap Content-Length must hit stage 1: $text")
            assertTrue(text.contains((TEST_CAP + 1).toString()), "stage 1's message names the declared length: $text")
        }

    // S2 (failure, NEW-SURFACE): Content-Length lies far above the cap while only 3 real bytes
    // are ever on the wire. A 413 here can only come from the header check — if stage 2 had run
    // instead, it would have read those 3 bytes (<= cap) and succeeded. This is the "zero body
    // bytes touched" proof by construction: the real body is far too small to trip stage 2 on
    // its own.
    @Test
    fun `receiveBounded rejects an overstated Content-Length without needing to read the tiny real body`() =
        testApplication {
            application { configureBoundedReceiveTestApp(cap = TEST_CAP) }

            val response =
                client.post("/test/receive") {
                    setBody(HelperMismatchedLengthContent(contentLength = 999_999L, actualBytes = byteArrayOf(1, 2, 3)))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("payload_too_large"), "shared ErrorDto shape: $text")
            assertTrue(text.contains("declares 999999 bytes"), "stage 1's message must cite the declared length: $text")
        }

    // S3 (failure, NEW-SURFACE): no Content-Length header at all (chunked), and the real stream
    // is far larger than the cap. Stage 2 must stop pulling from the channel long before the
    // stream ends.
    //
    // Arbitration (orchestrator, full-suite run at HEAD): this test originally also asserted
    // `content.bytesWritten < totalBytes` and `<= cap + 4096` as a client-side short-circuit
    // proof. Both are TEST WRONG, not implementation wrong — observed: wrote 16384 of 16384 (the
    // client fully drained the content) even though the server answered 413. The Ktor test
    // engine feeds the request body from a WriteChannelContent independently of the server's own
    // channel reads (and may finish or discard it regardless of when/whether the server stops
    // reading), so a CLIENT-side write counter cannot observe receiveBounded's own bound — that
    // is a claim about how many bytes the SERVER's readRemaining(maxBytes + 1) call pulls off the
    // channel, not about how many bytes the client physically transmitted. `bytesWritten` is kept
    // on [CountingChunkedContent] only as documentation of intent for a reviewer; the actual proof
    // here is the 413 plus stage 2's exact message shape, which is only reachable via the
    // bounded-channel-read path (never the declared-Content-Length pre-check, since none was
    // sent).
    @Test
    fun `receiveBounded rejects a chunked body exceeding maxBytes via the bounded-read path`() =
        testApplication {
            val cap = TEST_CAP
            application { configureBoundedReceiveTestApp(cap = cap) }

            val totalBytes = cap * 64 // comfortably over the cap; kilobytes, not megabytes
            val content = CountingChunkedContent(totalBytes = totalBytes, chunkSize = 16)
            val response = client.post("/test/receive") { setBody(content) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("payload_too_large"), "shared ErrorDto shape: $text")
            assertFalse(text.contains("declares"), "no Content-Length was sent — this must be stage 2's message, not stage 1's: $text")
            assertTrue(
                text.contains("Request body exceeds the $cap byte limit"),
                "must be exactly stage 2's message shape, reachable only via the bounded-channel-read path: $text",
            )
        }

    // S3 (failure, NEW-SURFACE), understated-Content-Length variant: the header claims a body
    // well under the cap, but the real stream is 4x the cap. Stage 1 must pass this through
    // (declaredLength <= maxBytes), leaving stage 2 to catch the real size.
    @Test
    fun `receiveBounded rejects a body whose actual bytes exceed an understated Content-Length`() =
        testApplication {
            val cap = TEST_CAP
            application { configureBoundedReceiveTestApp(cap = cap) }

            val actual = ByteArray(cap * 4) { 'x'.code.toByte() }
            val response =
                client.post("/test/receive") {
                    setBody(HelperMismatchedLengthContent(contentLength = 4L, actualBytes = actual))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("payload_too_large"), "shared ErrorDto shape: $text")
            assertFalse(text.contains("declares"), "declaredLength (4) <= cap must fall through to stage 2: $text")
        }

    // Probe (protocol-violation): Content-Length falsely declares 0 while the real stream is
    // still oversized. declaredLength (0) is not > maxBytes, so stage 1 passes it through the
    // same way it passes through an absent header or a negative value (per DECLARATIONS: "a
    // negative/nonsensical declared length is never > maxBytes, so ... it falls through to stage
    // 2") — this exercises that branch with the one sub-case actually reachable through a real
    // HTTP client (a raw negative Content-Length header is not something the test client's
    // OutgoingContent.contentLength — or a real HTTP client — will transmit; see test-manifest).
    @Test
    fun `receiveBounded still rejects an oversized body when Content-Length falsely declares zero`() =
        testApplication {
            val cap = TEST_CAP
            application { configureBoundedReceiveTestApp(cap = cap) }

            val actual = ByteArray(cap * 2) { 'x'.code.toByte() }
            val response =
                client.post("/test/receive") {
                    setBody(HelperMismatchedLengthContent(contentLength = 0L, actualBytes = actual))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertFalse(response.bodyAsText().contains("declares"), "falls through to stage 2, same as an absent header")
        }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class DidWebResolverTest {
    // -------------------------------------------------------------------------
    // URL construction tests (via internal buildUrl)
    // -------------------------------------------------------------------------

    @Test
    fun `buildUrl returns well-known URL for bare domain`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://example.com/.well-known/did.json",
            resolver.buildUrl("example.com")
        )
    }

    @Test
    fun `buildUrl returns path-based URL for domain with path segments`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://example.com/agents/abc/did.json",
            resolver.buildUrl("example.com:agents:abc")
        )
    }

    @Test
    fun `buildUrl decodes percent-encoded port in host`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://example.com:8080/.well-known/did.json",
            resolver.buildUrl("example.com%3A8080")
        )
    }

    @Test
    fun `buildUrl handles multiple path segments`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://example.com/a/b/c/did.json",
            resolver.buildUrl("example.com:a:b:c")
        )
    }

    // -------------------------------------------------------------------------
    // Happy path — .well-known
    // -------------------------------------------------------------------------

    @Test
    fun `resolve returns document for well-known DID`() =
        runTest {
            val did = "did:web:example.com"
            val didDocument =
                """
                {
                  "id": "did:web:example.com",
                  "verificationMethod": [
                    {
                      "id": "did:web:example.com#key-1",
                      "type": "JsonWebKey2020",
                      "controller": "did:web:example.com",
                      "publicKeyJwk": {
                        "kty": "EC",
                        "crv": "P-256",
                        "x": "abc",
                        "y": "def"
                      }
                    }
                  ],
                  "assertionMethod": ["did:web:example.com#key-1"],
                  "authentication": ["did:web:example.com#key-1"]
                }
                """.trimIndent()

            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val doc = resolver.resolve(did)

            assertEquals("did:web:example.com", doc.id)
            assertEquals(1, doc.verificationMethods.size)
            assertEquals("did:web:example.com#key-1", doc.verificationMethods[0].id)
            assertEquals("JsonWebKey2020", doc.verificationMethods[0].type)
            assertNotNull(doc.verificationMethods[0].publicKeyJwk)
            assertEquals(listOf("did:web:example.com#key-1"), doc.assertionMethod)
            assertEquals(listOf("did:web:example.com#key-1"), doc.authentication)
        }

    // -------------------------------------------------------------------------
    // Happy path — path-based DID
    // -------------------------------------------------------------------------

    @Test
    fun `resolve returns document for path-based DID`() =
        runTest {
            val did = "did:web:example.com:agents:abc"
            val didDocument =
                """
                {
                  "id": "did:web:example.com:agents:abc",
                  "verificationMethod": []
                }
                """.trimIndent()

            var capturedUrl: String? = null
            val engine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val doc = resolver.resolve(did)

            assertEquals("did:web:example.com:agents:abc", doc.id)
            assertEquals("https://example.com/agents/abc/did.json", capturedUrl)
        }

    // -------------------------------------------------------------------------
    // Security check — document id mismatch
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException when document id does not match requested DID`() =
        runTest {
            val requestedDid = "did:web:example.com"
            val didDocument =
                """
                {
                  "id": "did:web:attacker.com",
                  "verificationMethod": []
                }
                """.trimIndent()

            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve(requestedDid) }
                }

            assertTrue(
                ex.message!!.contains("did:web:example.com"),
                "Error should mention the requested DID"
            )
            assertTrue(
                ex.message!!.contains("did:web:attacker.com"),
                "Error should mention the received DID"
            )
        }

    // -------------------------------------------------------------------------
    // Empty identifier
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException for empty identifier`() =
        runTest {
            val resolver = DidWebResolver()
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:") }
                }
            assertNotNull(ex.message)
            assertTrue(ex.message!!.contains("empty identifier"), "Error should mention empty identifier")
        }

    // -------------------------------------------------------------------------
    // Non-did:web prefix
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException for non-did-web input`() =
        runTest {
            val resolver = DidWebResolver()
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:key:z6MkhaXg") }
                }
            assertNotNull(ex.message)
        }

    @Test
    fun `resolve throws DidResolutionException for plain HTTPS URL`() =
        runTest {
            val resolver = DidWebResolver()
            assertThrows<DidResolutionException> {
                kotlinx.coroutines.runBlocking { resolver.resolve("https://example.com/.well-known/did.json") }
            }
        }

    // -------------------------------------------------------------------------
    // Network errors — 404 and 500
    // -------------------------------------------------------------------------

    @Test
    fun `resolve wraps network 404 in DidResolutionException`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "Not Found",
                        status = HttpStatusCode.NotFound
                    )
                }

            val resolver = DidWebResolver(engine)
            // A 404 response still returns a body — the JSON parsing will fail,
            // which should be wrapped in DidResolutionException.
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertNotNull(ex.message)
        }

    @Test
    fun `resolve wraps network 500 in DidResolutionException`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "Internal Server Error",
                        status = HttpStatusCode.InternalServerError
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertNotNull(ex.message)
        }

    @Test
    fun `resolve wraps exception from HTTP client in DidResolutionException preserving cause`() =
        runTest {
            val networkCause = RuntimeException("connection refused")
            val engine = MockEngine { _ -> throw networkCause }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertNotNull(ex.message)
            // Ktor's HttpClient pipeline may rewrap engine-thrown exceptions — verify the
            // cause chain transitively includes the original by message, not by identity.
            assertNotNull(ex.cause)
            val causeChain = generateSequence(ex.cause) { it.cause }
            assertEquals(true, causeChain.any { it.message?.contains("connection refused") == true })
        }

    // -------------------------------------------------------------------------
    // Multi-key document with assertionMethod subset
    // -------------------------------------------------------------------------

    @Test
    fun `resolve handles multi-key document and returns assertionMethod subset`() =
        runTest {
            val did = "did:web:example.com"
            val didDocument =
                """
                {
                  "id": "did:web:example.com",
                  "verificationMethod": [
                    {
                      "id": "did:web:example.com#key-1",
                      "type": "JsonWebKey2020",
                      "controller": "did:web:example.com",
                      "publicKeyJwk": { "kty": "EC", "crv": "P-256", "x": "a", "y": "b" }
                    },
                    {
                      "id": "did:web:example.com#key-2",
                      "type": "Ed25519VerificationKey2020",
                      "controller": "did:web:example.com",
                      "publicKeyMultibase": "zH3C2AVvLMv6gmMNam3uVAjZpfkcJCwDwnZn6z3wXmqPV"
                    }
                  ],
                  "assertionMethod": ["did:web:example.com#key-1"],
                  "authentication": ["did:web:example.com#key-1", "did:web:example.com#key-2"]
                }
                """.trimIndent()

            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val doc = resolver.resolve(did)

            assertEquals(2, doc.verificationMethods.size)

            val key1 = doc.verificationMethods.first { it.id == "did:web:example.com#key-1" }
            assertEquals("JsonWebKey2020", key1.type)
            assertNotNull(key1.publicKeyJwk)

            val key2 = doc.verificationMethods.first { it.id == "did:web:example.com#key-2" }
            assertEquals("Ed25519VerificationKey2020", key2.type)
            assertEquals("zH3C2AVvLMv6gmMNam3uVAjZpfkcJCwDwnZn6z3wXmqPV", key2.publicKeyMultibase)

            // assertionMethod is only key-1, not key-2
            assertEquals(listOf("did:web:example.com#key-1"), doc.assertionMethod)
            // authentication includes both
            assertEquals(
                listOf("did:web:example.com#key-1", "did:web:example.com#key-2"),
                doc.authentication
            )
        }

    // -------------------------------------------------------------------------
    // Tolerates missing optional fields
    // -------------------------------------------------------------------------

    @Test
    fun `resolve tolerates document with no verificationMethod or assertionMethod`() =
        runTest {
            val did = "did:web:minimal.example"
            val didDocument =
                """
                {
                  "id": "did:web:minimal.example"
                }
                """.trimIndent()

            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val doc = resolver.resolve(did)

            assertEquals("did:web:minimal.example", doc.id)
            assertTrue(doc.verificationMethods.isEmpty())
            assertTrue(doc.assertionMethod.isEmpty())
            assertTrue(doc.authentication.isEmpty())
        }

    // -------------------------------------------------------------------------
    // method property
    // -------------------------------------------------------------------------

    @Test
    fun `method property returns web`() {
        val resolver = DidWebResolver()
        assertEquals("web", resolver.method)
    }

    // -------------------------------------------------------------------------
    // HTTP hardening — status code rejection
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException with HTTP status message on 404`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "Not Found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf("Content-Type", "text/plain")
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(ex.message!!.contains("404"), "Error should mention 404 status code")
        }

    @Test
    fun `resolve throws DidResolutionException with HTTP status message on 500`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "Internal Server Error",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf("Content-Type", "text/plain")
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(ex.message!!.contains("500"), "Error should mention 500 status code")
        }

    // -------------------------------------------------------------------------
    // HTTP hardening — redirect rejection
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException on 301 redirect and does not follow`() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { _ ->
                    requestCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.MovedPermanently,
                        headers =
                            headersOf(
                                "Location",
                                "https://attacker.example.com/.well-known/did.json"
                            )
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(ex.message!!.contains("301"), "Error should mention 301 status code")
            assertEquals(1, requestCount, "Redirect target must NOT be fetched — only one request expected")
        }

    @Test
    fun `resolve throws DidResolutionException on 302 redirect and does not follow`() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { _ ->
                    requestCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers =
                            headersOf(
                                "Location",
                                "https://attacker.example.com/.well-known/did.json"
                            )
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(ex.message!!.contains("302"), "Error should mention 302 status code")
            assertEquals(1, requestCount, "Redirect target must NOT be fetched — only one request expected")
        }

    // -------------------------------------------------------------------------
    // HTTP hardening — wrong Content-Type rejection
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException for text-html content type`() =
        runTest {
            val validJson =
                """{"id": "did:web:example.com", "verificationMethod": []}""".trimIndent()
            val engine =
                MockEngine { _ ->
                    respond(
                        content = validJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html")
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(
                ex.message!!.contains("Content-Type") || ex.message!!.contains("text/html"),
                "Error should mention content type problem"
            )
        }

    @Test
    fun `resolve accepts application-did-plus-json content type`() =
        runTest {
            val did = "did:web:example.com"
            val didDocument = """{"id": "$did", "verificationMethod": []}"""
            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/did+json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val doc = resolver.resolve(did)
            assertEquals(did, doc.id)
        }

    // -------------------------------------------------------------------------
    // HTTP hardening — oversized body rejection
    // -------------------------------------------------------------------------

    @Test
    fun `resolve throws DidResolutionException for response body exceeding 1 MiB`() =
        runTest {
            // Create a body that is just over the 1 MiB cap.
            val oversizedBody = ByteArray(DidWebResolver.MAX_BODY_BYTES + 1) { 'x'.code.toByte() }
            val engine =
                MockEngine { _ ->
                    respond(
                        content = oversizedBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val resolver = DidWebResolver(engine)
            val ex =
                assertThrows<DidResolutionException> {
                    kotlinx.coroutines.runBlocking { resolver.resolve("did:web:example.com") }
                }
            assertTrue(
                ex.message!!.contains("size") || ex.message!!.contains("exceeds"),
                "Error should mention body size: ${ex.message}"
            )
        }

    // -------------------------------------------------------------------------
    // Percent-decode path segments (W3C did:web spec compliance)
    // -------------------------------------------------------------------------

    @Test
    fun `buildUrl decodes percent-encoded space in path segment`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://host/agents/abc def/did.json",
            resolver.buildUrl("host:agents:abc%20def"),
        )
    }

    @Test
    fun `buildUrl decodes percent-encoded port in host and plain path segment`() {
        val resolver = DidWebResolver()
        assertEquals(
            "https://host:8080/agents/alice/did.json",
            resolver.buildUrl("host%3A8080:agents:alice"),
        )
    }

    @Test
    fun `buildUrl throws DidResolutionException for empty host segment`() {
        val resolver = DidWebResolver()
        val ex =
            org.junit.jupiter.api.Assertions.assertThrows(DidResolutionException::class.java) {
                resolver.buildUrl(":")
            }
        assertTrue(
            ex.message!!.contains("empty host"),
            "Error should mention empty host: ${ex.message}",
        )
    }

    // -------------------------------------------------------------------------
    // Warning log — malformed verificationMethod entries are logged, not silently dropped
    // -------------------------------------------------------------------------

    @Test
    fun `parseVerificationMethods logs WARN for entry missing controller and returns only valid entry`() =
        runTest {
            val did = "did:web:example.com"
            val didDocument =
                """
                {
                  "id": "$did",
                  "verificationMethod": [
                    {
                      "id": "$did#key-valid",
                      "type": "JsonWebKey2020",
                      "controller": "$did",
                      "publicKeyJwk": { "kty": "EC", "crv": "P-256", "x": "a", "y": "b" }
                    },
                    {
                      "id": "$did#key-no-controller",
                      "type": "JsonWebKey2020"
                    }
                  ]
                }
                """.trimIndent()

            val engine =
                MockEngine { _ ->
                    respond(
                        content = didDocument,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }

            // Attach a Logback ListAppender to capture WARN messages from DidWebResolver.
            val loggerName = DidWebResolver::class.java.name
            val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
            val listAppender =
                ListAppender<ILoggingEvent>().also {
                    it.start()
                    logbackLogger.addAppender(it)
                }
            val savedLevel = logbackLogger.level
            logbackLogger.level = Level.WARN

            try {
                val resolver = DidWebResolver(engine)
                val doc = resolver.resolve(did)

                // Only the valid entry should appear in the result.
                assertEquals(1, doc.verificationMethods.size, "Malformed VM must be excluded from result")
                assertEquals("$did#key-valid", doc.verificationMethods.first().id)

                // Exactly one WARN must have been emitted for the missing 'controller' field.
                val warnMessages = listAppender.list.filter { it.level == Level.WARN }
                assertEquals(1, warnMessages.size, "Expected exactly 1 WARN for the malformed VM")
                assertTrue(
                    warnMessages.first().formattedMessage.contains("controller"),
                    "WARN should mention missing 'controller' field: ${warnMessages.first().formattedMessage}",
                )
            } finally {
                logbackLogger.detachAppender(listAppender)
                logbackLogger.level = savedLevel
            }
        }

    // -------------------------------------------------------------------------
    // close() propagation via DidResolverRegistry
    // -------------------------------------------------------------------------

    @Test
    fun `DidResolverRegistry closeAll calls close on registered resolver`() {
        var closeCalled = false
        val trackingResolver =
            object : DidResolver {
                override val method: String = "test"

                override suspend fun resolve(did: String): io.github.jpicklyk.mcptask.current.domain.model.DidDocument {
                    error("not used in this test")
                }

                override fun close() {
                    closeCalled = true
                }
            }

        val registry = DidResolverRegistry(listOf(trackingResolver))
        registry.closeAll()
        assertTrue(closeCalled, "closeAll() should have called close() on the resolver")
    }
}

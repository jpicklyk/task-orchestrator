package io.github.jpicklyk.mcptask.current.infrastructure.config

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
}

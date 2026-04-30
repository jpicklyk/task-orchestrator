package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DidDocumentJwksExtractorTest {
    // -------------------------------------------------------------------------
    // Shared test fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val DOC_ID = "did:web:example.com"

        /** EC P-256 key used to build publicKeyJwk maps in tests. */
        private val ecKey1: ECKey = ECKeyGenerator(Curve.P_256).keyID("key-1").generate()
        private val ecKey2: ECKey = ECKeyGenerator(Curve.P_256).keyID("key-2").generate()

        /** Convert an ECKey's public portion to the Map<String, Any> form DidDocument uses. */
        private fun ECKey.toJwkMap(): Map<String, Any> = toPublicJWK().toJSONObject().mapValues { (_, v) -> v as Any }
    }

    private val extractor = DidDocumentJwksExtractor()
    private val lenientExtractor = DidDocumentJwksExtractor(strictRelationship = false)

    // -------------------------------------------------------------------------
    // W3C example: 2 VMs, 1 referenced in assertionMethod
    // -------------------------------------------------------------------------

    @Test
    fun `strict mode includes only assertionMethod-referenced keys`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                        VerificationMethod(
                            id = "$DOC_ID#key-2",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey2.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-1"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "Strict mode should include only the asserted key")
        assertEquals("key-1", result.keys.first().keyID)
    }

    @Test
    fun `lenient mode includes all verification methods`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                        VerificationMethod(
                            id = "$DOC_ID#key-2",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey2.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-1"),
            )

        val result = lenientExtractor.extract(doc)

        assertEquals(2, result.keys.size, "Lenient mode should include both keys")
        val kids = result.keys.map { it.keyID }.toSet()
        assertTrue(kids.contains("key-1"))
        assertTrue(kids.contains("key-2"))
    }

    // -------------------------------------------------------------------------
    // Empty assertionMethod in strict mode → empty JWKSet, no warnings needed
    // -------------------------------------------------------------------------

    @Test
    fun `strict mode returns empty JWKSet when assertionMethod is empty`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                assertionMethod = emptyList(),
            )

        val result = extractor.extract(doc)

        assertTrue(result.keys.isEmpty(), "Empty assertionMethod in strict mode must yield empty JWKSet")
    }

    // -------------------------------------------------------------------------
    // Empty verification methods → empty JWKSet
    // -------------------------------------------------------------------------

    @Test
    fun `empty verification methods returns empty JWKSet`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods = emptyList(),
                assertionMethod = emptyList(),
            )

        val result = extractor.extract(doc)
        assertTrue(result.keys.isEmpty())
    }

    @Test
    fun `lenient mode with empty verification methods returns empty JWKSet`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods = emptyList(),
                assertionMethod = emptyList(),
            )

        val result = lenientExtractor.extract(doc)
        assertTrue(result.keys.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Controller mismatch
    // -------------------------------------------------------------------------

    @Test
    fun `controller mismatch causes method to be skipped`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = "did:web:other.example.com",
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                        VerificationMethod(
                            id = "$DOC_ID#key-2",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey2.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-1", "$DOC_ID#key-2"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "Controller-mismatched key should be skipped")
        assertEquals("key-2", result.keys.first().keyID)
    }

    // -------------------------------------------------------------------------
    // publicKeyMultibase-only → skipped
    // -------------------------------------------------------------------------

    @Test
    fun `multibase-only verification method is skipped`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-multibase",
                            type = "Ed25519VerificationKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = null,
                            publicKeyMultibase = "zH3C2AVvLMv6gmMNam3uVAjZpfkcJCwDwnZn6z3wXmqPV",
                        ),
                        VerificationMethod(
                            id = "$DOC_ID#key-jwk",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-multibase", "$DOC_ID#key-jwk"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "Multibase-only method should be skipped")
        assertEquals("key-jwk", result.keys.first().keyID)
    }

    // -------------------------------------------------------------------------
    // Mixed: publicKeyJwk wins when both fields are present
    // -------------------------------------------------------------------------

    @Test
    fun `publicKeyJwk wins when both publicKeyJwk and publicKeyMultibase are present`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-mixed",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                            publicKeyMultibase = "zH3C2AVvLMv6gmMNam3uVAjZpfkcJCwDwnZn6z3wXmqPV",
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-mixed"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "publicKeyJwk should be used, not multibase")
        assertEquals("key-mixed", result.keys.first().keyID)
    }

    // -------------------------------------------------------------------------
    // kid correctness — both fully-qualified and bare-fragment id forms
    // -------------------------------------------------------------------------

    @Test
    fun `kid is bare fragment for fully-qualified VM id`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#my-key",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#my-key"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size)
        assertEquals("my-key", result.keys.first().keyID, "kid should be bare fragment, not full DID URL")
    }

    @Test
    fun `kid is preserved when VM id has no hash fragment`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "standalone-key-id",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                assertionMethod = listOf("standalone-key-id"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size)
        assertEquals("standalone-key-id", result.keys.first().keyID)
    }

    @Test
    fun `assertionMethod bare-fragment reference resolves to correct verification method`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                // Bare-fragment reference form
                assertionMethod = listOf("#key-1"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "Bare-fragment assertionMethod reference should resolve")
        assertEquals("key-1", result.keys.first().keyID)
    }

    // -------------------------------------------------------------------------
    // Duplicate assertionMethod references produce only one key
    // -------------------------------------------------------------------------

    @Test
    fun `duplicate assertionMethod references produce only one JWK entry`() {
        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-1",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = ecKey1.toJwkMap(),
                        ),
                    ),
                // Same key referenced twice — once by full id, once by fragment
                assertionMethod = listOf("$DOC_ID#key-1", "#key-1"),
            )

        val result = extractor.extract(doc)

        assertEquals(1, result.keys.size, "Duplicate references should not produce duplicate JWK entries")
    }
}

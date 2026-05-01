package io.github.jpicklyk.mcptask.current.infrastructure.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class DidDocumentJwksExtractorTest {
    // -------------------------------------------------------------------------
    // Shared test fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val DOC_ID = "did:web:example.com"

        /** EC P-256 key used to build publicKeyJwk JsonObjects in tests. */
        private val ecKey1: ECKey = ECKeyGenerator(Curve.P_256).keyID("key-1").generate()
        private val ecKey2: ECKey = ECKeyGenerator(Curve.P_256).keyID("key-2").generate()

        private val jsonParser = Json { ignoreUnknownKeys = true }

        /** Convert an ECKey's public portion to the JsonObject form DidDocument uses. */
        private fun ECKey.toJwkMap(): JsonObject = jsonParser.parseToJsonElement(toPublicJWK().toJSONString()).jsonObject
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

    // -------------------------------------------------------------------------
    // JsonObject round-trip regression — arrays and booleans must survive intact
    // -------------------------------------------------------------------------

    @Test
    fun `key_ops array survives the JsonObject round-trip without re-quoting elements`() {
        // Regression test for the old Map<String, Any> path.
        // The old code called `v.map { it.toString() }` on JsonArray elements, which
        // re-serialized each string element with surrounding quotes:
        //   ["verify"] became ["\"verify\""] in the JSON fed to JWK.parse().
        // Nimbus would then reject the JWK or store the wrong key_ops value.
        //
        // With the new JsonObject path (no Map round-trip), the array serialises cleanly
        // and Nimbus correctly parses "verify" as the KeyOperation.
        val rawJwkJson =
            """
            {
              "kty": "EC",
              "crv": "P-256",
              "x": "${ecKey1.toPublicJWK().x}",
              "y": "${ecKey1.toPublicJWK().y}",
              "key_ops": ["verify"]
            }
            """.trimIndent()
        val jwkJsonObject = jsonParser.parseToJsonElement(rawJwkJson).jsonObject

        val doc =
            DidDocument(
                id = DOC_ID,
                verificationMethods =
                    listOf(
                        VerificationMethod(
                            id = "$DOC_ID#key-ops-test",
                            type = "JsonWebKey2020",
                            controller = DOC_ID,
                            publicKeyJwk = jwkJsonObject,
                        ),
                    ),
                assertionMethod = listOf("$DOC_ID#key-ops-test"),
            )

        val result = lenientExtractor.extract(doc)
        assertEquals(1, result.keys.size, "Should produce exactly one JWK")

        val jwk = result.keys.first()

        // key_ops must be preserved as a proper KeyOperation set, not mangled strings.
        // Nimbus parses key_ops as Set<KeyOperation>; if the elements were double-quoted
        // (old bug: "\"verify\"") Nimbus would return null or an unrecognised op.
        val keyOperations = jwk.keyOperations
        assertNotNull(keyOperations, "key_ops should be preserved through round-trip")
        assertEquals(1, keyOperations!!.size, "Expected exactly one key operation")
        assertEquals(
            KeyOperation.VERIFY,
            keyOperations.first(),
            "key_ops element must be the VERIFY operation, not a mangled double-quoted string",
        )
    }

    // -------------------------------------------------------------------------
    // Warning log test — malformed verification method logs a WARN
    // -------------------------------------------------------------------------

    @Test
    fun `extract_skipsVerificationMethodWithControllerMismatch_andLogsWarning`() {
        // Use a Logback ListAppender to capture WARN messages from DidDocumentJwksExtractor.
        // This verifies that operator-visible logging is emitted when a verification method's
        // controller field does not match the document id (a controller *mismatch*, not a
        // missing field — the extractor warns and drops the offending method).
        val loggerName = DidDocumentJwksExtractor::class.java.name
        val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN

        try {
            val doc =
                DidDocument(
                    id = DOC_ID,
                    verificationMethods =
                        listOf(
                            // Valid method — controller matches document id.
                            VerificationMethod(
                                id = "$DOC_ID#key-good",
                                type = "JsonWebKey2020",
                                controller = DOC_ID,
                                publicKeyJwk = ecKey1.toJwkMap(),
                            ),
                            // Cross-controller method — controller mismatch triggers a WARN.
                            VerificationMethod(
                                id = "$DOC_ID#key-foreign",
                                type = "JsonWebKey2020",
                                controller = "did:web:foreign.example.com",
                                publicKeyJwk = ecKey2.toJwkMap(),
                            ),
                        ),
                    assertionMethod = listOf("$DOC_ID#key-good", "$DOC_ID#key-foreign"),
                )

            val result = extractor.extract(doc)

            // Only the valid key should survive.
            assertEquals(1, result.keys.size, "Cross-controller key must be excluded from JWKSet")
            assertEquals("key-good", result.keys.first().keyID)

            // Extractor must have logged exactly one WARN about the controller mismatch.
            val warnMessages = listAppender.list.filter { it.level == Level.WARN }
            assertEquals(1, warnMessages.size, "Expected exactly one WARN log for the controller mismatch")
            assertTrue(
                warnMessages.first().formattedMessage.contains("controller"),
                "WARN message should mention 'controller': ${warnMessages.first().formattedMessage}"
            )
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }
}

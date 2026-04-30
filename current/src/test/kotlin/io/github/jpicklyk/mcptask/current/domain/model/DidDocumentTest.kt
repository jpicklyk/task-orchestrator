package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DidDocumentTest {

    private val verificationMethod = VerificationMethod(
        id = "did:web:example.com#key-1",
        type = "JsonWebKey2020",
        controller = "did:web:example.com",
        publicKeyJwk = mapOf("kty" to "EC", "crv" to "P-256", "x" to "abc", "y" to "def")
    )

    private val didDocument = DidDocument(
        id = "did:web:example.com",
        verificationMethods = listOf(verificationMethod),
        assertionMethod = listOf("did:web:example.com#key-1"),
        authentication = listOf("did:web:example.com#key-1")
    )

    @Test
    fun `DidDocument equality - equal instances are equal`(): Unit {
        val copy = didDocument.copy()
        assertEquals(didDocument, copy)
    }

    @Test
    fun `DidDocument copy - modified copy differs from original`(): Unit {
        val modified = didDocument.copy(id = "did:web:other.com")
        assertEquals("did:web:other.com", modified.id)
        assertEquals(didDocument.verificationMethods, modified.verificationMethods)
    }

    @Test
    fun `DidDocument default lists are non-null empty lists`(): Unit {
        val minimal = DidDocument(
            id = "did:web:minimal.com",
            verificationMethods = emptyList()
        )
        assertNotNull(minimal.assertionMethod)
        assertNotNull(minimal.authentication)
        assertEquals(emptyList<String>(), minimal.assertionMethod)
        assertEquals(emptyList<String>(), minimal.authentication)
    }

    @Test
    fun `VerificationMethod equality - equal instances are equal`(): Unit {
        val copy = verificationMethod.copy()
        assertEquals(verificationMethod, copy)
    }

    @Test
    fun `VerificationMethod copy - modified copy has updated field`(): Unit {
        val modified = verificationMethod.copy(type = "Ed25519VerificationKey2020")
        assertEquals("Ed25519VerificationKey2020", modified.type)
        assertEquals(verificationMethod.id, modified.id)
    }

    @Test
    fun `VerificationMethod publicKeyJwk defaults to null`(): Unit {
        val method = VerificationMethod(
            id = "did:web:example.com#key-2",
            type = "Ed25519VerificationKey2020",
            controller = "did:web:example.com"
        )
        assertNull(method.publicKeyJwk)
        assertNull(method.publicKeyMultibase)
    }

    @Test
    fun `VerificationMethod publicKeyMultibase can be set`(): Unit {
        val method = VerificationMethod(
            id = "did:web:example.com#key-3",
            type = "Ed25519VerificationKey2020",
            controller = "did:web:example.com",
            publicKeyMultibase = "z6Mk..."
        )
        assertNull(method.publicKeyJwk)
        assertEquals("z6Mk...", method.publicKeyMultibase)
    }

    @Test
    fun `VerificationMethod publicKeyJwk accepts arbitrary JWK shapes`(): Unit {
        val rsaJwk: Map<String, Any> = mapOf(
            "kty" to "RSA",
            "n" to "modulus",
            "e" to "AQAB"
        )
        val method = verificationMethod.copy(publicKeyJwk = rsaJwk)
        assertEquals(rsaJwk, method.publicKeyJwk)
    }
}

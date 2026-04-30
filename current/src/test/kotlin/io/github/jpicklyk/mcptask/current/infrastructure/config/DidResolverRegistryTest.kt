package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DidResolverRegistryTest {

    // -------------------------------------------------------------------------
    // parseMethod tests
    // -------------------------------------------------------------------------

    @Test
    fun `parseMethod returns method for well-formed DID`() {
        val registry = DidResolverRegistry(emptyList())
        assertEquals("web", registry.parseMethod("did:web:example.com"))
    }

    @Test
    fun `parseMethod returns key method`() {
        val registry = DidResolverRegistry(emptyList())
        assertEquals("key", registry.parseMethod("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"))
    }

    @Test
    fun `parseMethod returns null for non-DID string`() {
        val registry = DidResolverRegistry(emptyList())
        assertNull(registry.parseMethod("not-a-did"))
    }

    @Test
    fun `parseMethod returns null for bare did-colon with no method`() {
        val registry = DidResolverRegistry(emptyList())
        assertNull(registry.parseMethod("did:"))
    }

    @Test
    fun `parseMethod returns null for did-colon-colon (empty method segment)`() {
        // "did::" -> afterPrefix = ":", colon at index 0 which is not > 0
        val registry = DidResolverRegistry(emptyList())
        assertNull(registry.parseMethod("did::something"))
    }

    // -------------------------------------------------------------------------
    // resolve dispatch tests
    // -------------------------------------------------------------------------

    @Test
    fun `resolve dispatches to the correct resolver by method`() = runTest {
        val expectedDoc = DidDocument(
            id = "did:web:example.com",
            verificationMethods = emptyList()
        )
        val webResolver = mockk<DidResolver>().apply {
            every { method } returns "web"
            coEvery { resolve("did:web:example.com") } returns expectedDoc
        }

        val registry = DidResolverRegistry(listOf(webResolver))
        val result = registry.resolve("did:web:example.com")

        assertEquals(expectedDoc, result)
    }

    @Test
    fun `resolve dispatches to the right resolver when multiple resolvers are registered`() = runTest {
        val webDoc = DidDocument(id = "did:web:example.com", verificationMethods = emptyList())
        val keyDoc = DidDocument(id = "did:key:z6Mk...", verificationMethods = emptyList())

        val webResolver = mockk<DidResolver>().apply {
            every { method } returns "web"
            coEvery { resolve("did:web:example.com") } returns webDoc
        }
        val keyResolver = mockk<DidResolver>().apply {
            every { method } returns "key"
            coEvery { resolve("did:key:z6Mk...") } returns keyDoc
        }

        val registry = DidResolverRegistry(listOf(webResolver, keyResolver))

        assertEquals(webDoc, registry.resolve("did:web:example.com"))
        assertEquals(keyDoc, registry.resolve("did:key:z6Mk..."))
    }

    @Test
    fun `resolve throws DidResolutionException for unknown method`() = runTest {
        val webResolver = mockk<DidResolver>().apply {
            every { method } returns "web"
        }
        val registry = DidResolverRegistry(listOf(webResolver))

        val ex = assertThrows(DidResolutionException::class.java) {
            kotlinx.coroutines.runBlocking { registry.resolve("did:peer:1zQmYtqd2") }
        }
        assertNotNull(ex.message)
        assert(ex.message!!.contains("peer")) { "Expected error to mention the unknown method 'peer'" }
    }

    @Test
    fun `resolve throws DidResolutionException for non-DID input`() = runTest {
        val registry = DidResolverRegistry(emptyList())

        val ex = assertThrows(DidResolutionException::class.java) {
            kotlinx.coroutines.runBlocking { registry.resolve("https://example.com") }
        }
        assertNotNull(ex.message)
    }

    // -------------------------------------------------------------------------
    // DidResolutionException cause-chaining
    // -------------------------------------------------------------------------

    @Test
    fun `DidResolutionException supports cause chaining`() {
        val cause = RuntimeException("network error")
        val ex = DidResolutionException("resolution failed", cause)
        assertEquals("resolution failed", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `DidResolutionException can be created without cause`() {
        val ex = DidResolutionException("no cause")
        assertEquals("no cause", ex.message)
        assertNull(ex.cause)
    }
}

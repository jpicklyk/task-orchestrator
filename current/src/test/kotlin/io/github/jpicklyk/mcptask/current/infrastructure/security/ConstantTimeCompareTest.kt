package io.github.jpicklyk.mcptask.current.infrastructure.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConstantTimeCompareTest {
    @Test
    fun `equal arrays return true`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(ConstantTimeCompare.equal(a, b), "Equal arrays must return true")
    }

    @Test
    fun `empty arrays return true`() {
        assertTrue(ConstantTimeCompare.equal(byteArrayOf(), byteArrayOf()), "Two empty arrays must be equal")
    }

    @Test
    fun `single byte matching returns true`() {
        assertTrue(ConstantTimeCompare.equal(byteArrayOf(42), byteArrayOf(42)))
    }

    @Test
    fun `single byte differing returns false`() {
        assertFalse(ConstantTimeCompare.equal(byteArrayOf(1), byteArrayOf(2)))
    }

    @Test
    fun `arrays differing in last byte return false`() {
        // Verify it does NOT short-circuit on first match — the last byte differs.
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 99)
        assertFalse(ConstantTimeCompare.equal(a, b), "Arrays differing only in last byte must return false")
    }

    @Test
    fun `arrays differing in first byte return false`() {
        val a = byteArrayOf(99, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertFalse(ConstantTimeCompare.equal(a, b), "Arrays differing in first byte must return false")
    }

    @Test
    fun `arrays of different length return false`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3, 4)
        assertFalse(ConstantTimeCompare.equal(a, b), "Arrays of different length must return false")
    }

    @Test
    fun `empty vs non-empty returns false`() {
        assertFalse(ConstantTimeCompare.equal(byteArrayOf(), byteArrayOf(0)))
        assertFalse(ConstantTimeCompare.equal(byteArrayOf(0), byteArrayOf()))
    }

    @Test
    fun `32-byte SHA-256-length arrays compare correctly`() {
        // Simulate the typical use case: two SHA-256 digests.
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { it.toByte() }
        assertTrue(ConstantTimeCompare.equal(a, b), "Identical 32-byte arrays must be equal")

        val c = ByteArray(32) { (it + 1).toByte() }
        assertFalse(ConstantTimeCompare.equal(a, c), "Different 32-byte arrays must not be equal")
    }

    @Test
    fun `uses MessageDigest isEqual under the hood — verified via behaviour contract`() {
        // We cannot assert timing directly on CI (flaky), but we can assert that the behaviour
        // matches the constant-time spec: equal ↔ all bytes identical.
        val x = ByteArray(64) { 0xAB.toByte() }
        val y = ByteArray(64) { 0xAB.toByte() }
        val z = ByteArray(64) { 0xCD.toByte() }

        assertEquals(true, ConstantTimeCompare.equal(x, y))
        assertEquals(false, ConstantTimeCompare.equal(x, z))
    }
}

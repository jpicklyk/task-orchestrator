package io.github.jpicklyk.mcptask.current.application.telemetry

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests for item 8abb69e2 (P10): [ReqId]. Scenario S1 plus the alphabet probes.
 *
 * Oracle: plan section 3.8 and envelope section 2 (frozen in test-plan S1): a reqId is 8 characters of the
 * lowercase Crockford base32 alphabet `0123456789abcdefghjkmnpqrstvwxyz` (the envelope example `k7f3q9ab`
 * contains a digit 9, so it is not RFC 4648), 40 bits, and is never a UUID.
 */
class ReqIdTest {
    private val crockford = "0123456789abcdefghjkmnpqrstvwxyz"

    @Test
    fun `S1 the published alphabet and length are the frozen ones`() {
        assertEquals(crockford, ReqId.ALPHABET)
        assertEquals(8, ReqId.LENGTH)
        assertEquals(32, ReqId.ALPHABET.toSet().size, "32 distinct symbols")
    }

    @Test
    fun `S1 generate x1000 yields 8 characters from the alphabet and all distinct`() {
        val ids = List(1000) { ReqId.generate() }
        assertTrue(ids.all { it.length == 8 }, "every id has 8 characters")
        assertTrue(
            ids.all { id ->
                id.all { it in crockford }
            },
            "every character is in the Crockford alphabet: ${ids.firstOrNull { id ->
                id.any { it !in crockford }
            }}"
        )
        assertEquals(1000, ids.toSet().size, "1000 draws from 40 bits must not collide")
        assertTrue(ids.none { runCatching { UUID.fromString(it) }.isSuccess }, "a reqId is never a UUID")
    }

    @Test
    fun `S1 alphabet excludes i l o and u and uppercase never appears`() {
        val chars = List(1000) { ReqId.generate() }.flatMap { it.toList() }.toSet()
        for (banned in "ilouILOU") assertFalse(banned in chars, "'$banned' must never be generated")
        assertTrue(chars.none { it.isUpperCase() }, "lowercase only")
    }

    @Test
    fun `S1 every symbol of the alphabet is used and every position varies`() {
        val ids = List(1000) { ReqId.generate() }
        assertEquals(crockford.toSet(), ids.flatMap { it.toList() }.toSet(), "all 32 symbols must occur over 8000 characters")
        for (pos in 0 until 8) {
            val distinct = ids.map { it[pos] }.toSet().size
            assertTrue(distinct >= 28, "position $pos used only $distinct of 32 symbols over 1000 draws (bits not spread)")
        }
    }

    @Test
    fun `S1 fromBits renders 40 bits as 8 symbols`() {
        assertEquals("00000000", ReqId.fromBits(0L))
        assertEquals("zzzzzzzz", ReqId.fromBits((1L shl 40) - 1))
        val low5 = ReqId.fromBits(31L)
        assertEquals(1, low5.count { it == 'z' }, "5 set bits are exactly one symbol 'z': $low5")
        assertEquals(7, low5.count { it == '0' }, "the other seven symbols are '0': $low5")
        assertNotEquals(ReqId.fromBits(1L), ReqId.fromBits(2L), "different bit patterns give different ids")
        assertTrue(ReqId.isValid(ReqId.fromBits(0x0123456789L)))
    }

    @Test
    fun `isValid accepts the envelope example and every generated id`() {
        assertTrue(ReqId.isValid("k7f3q9ab"), "the envelope section 2 example")
        assertTrue(ReqId.isValid("00000000"))
        assertTrue(ReqId.isValid("zzzzzzzz"))
        assertTrue(List(200) { ReqId.generate() }.all { ReqId.isValid(it) })
    }

    @Test
    fun `isValid rejects null empty wrong length wrong case and symbols outside the alphabet`() {
        val bad =
            listOf(
                null,
                "",
                "k7f3q9a",
                "k7f3q9abc",
                "K7F3Q9AB",
                "k7f3q9aI",
                "k7f3q9ai",
                "k7f3q9al",
                "k7f3q9ao",
                "k7f3q9au",
                "k7f3q9a-",
                " k7f3q9a",
                "k7f3q9a ",
                "k7f3q9a\n",
                UUID.randomUUID().toString()
            )
        for (candidate in bad) assertFalse(ReqId.isValid(candidate), "must be rejected: '$candidate'")
    }
}

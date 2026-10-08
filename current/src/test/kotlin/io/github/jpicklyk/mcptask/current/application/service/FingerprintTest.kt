package io.github.jpicklyk.mcptask.current.application.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) for [Fingerprint.of].
 *
 * Oracle: task-scope section 2 "Fingerprint": SHA-256 lowercase hex of the UTF-8 canonical JSON; keys sorted by
 * `String.compareTo` (UTF-16 code-unit order), arrays in order, no whitespace, primitives as written (1 != 1.0),
 * nulls kept, any object under an `actor` key reduced to {id, kind, parent} (proof dropped). The expected hex
 * digests below were computed by an independent run of Python `hashlib.sha256` over the canonical text named next to
 * each constant - they are NOT read from the implementation.
 */
class FingerprintTest {
    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun fp(text: String): String = Fingerprint.of(parse(text))

    private val eAcute = 0xE9.toChar().toString()

    // S14 - the digest is the SHA-256 of the canonical text
    @Test
    fun `S14 digest equals the independently computed SHA-256 of the canonical JSON`() {
        // canonical text: {"a":true,"b":[1,{"x":"<e-acute>","y":null}]}
        val unsorted = """{ "b" : [ 1 , { "y" : null , "x" : "$eAcute" } ] , "a" : true }"""
        assertEquals("6b4f4c5289ae7601763a7cc2adac2f681511d8937dd6db8bb4680dbca8243fab", fp(unsorted))
    }

    @Test
    fun `S14 an empty object has the SHA-256 of the two characters braces`() {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", fp("{}"))
    }

    @Test
    fun `S14 fingerprint is 64 lowercase hex characters`() {
        val value = fp("""{"anything":[1,2,3]}""")
        assertEquals(64, value.length)
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }, "not lowercase hex: $value")
    }

    // S14 - key order / whitespace
    @Test
    fun `S14 key order and whitespace do not change the fingerprint`() {
        assertEquals(fp("""{"a":1,"b":{"c":2,"d":3}}"""), fp("""{ "b" : { "d":3, "c":2 }, "a" : 1 }"""))
    }

    @Test
    fun `S14 keys are ordered by UTF-16 code units, not by code point`() {
        // canonical text: {"<U+1F600>":1,"<U+FF5E>":2} - the surrogate pair (D83D) sorts BEFORE U+FF5E in UTF-16 order.
        val emojiKey = String(Character.toChars(0x1F600))
        val fullwidthTilde = 0xFF5E.toChar().toString()
        assertEquals(
            "5848fd4854a6ce2c6f23359368eef2f1a8d667176e91a9b74a92b7086fa7b207",
            fp("""{"$fullwidthTilde":2,"$emojiKey":1}""")
        )
    }

    // S14 - array order, numbers, null versus absent
    @Test
    fun `S14 array order is significant`() {
        assertEquals("01530d164d479cf08e26d3b1ad9bdba927120d97e2d057a6d792db778780d720", fp("""{"a":[1,2]}"""))
        assertEquals("439e3fbf35fa867d156db2626f94efbd435852a97bbe16660ee529d9bda0e502", fp("""{"a":[2,1]}"""))
    }

    @Test
    fun `S14 the numbers 1 and 1 point 0 produce different fingerprints`() {
        assertEquals("2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd", fp("""{"n":1}"""))
        assertEquals("3b6b06ecd1c968c8e738e0f11c4bb361fca80a9a694de22fe66a05286afbd081", fp("""{"n":1.0}"""))
        assertNotEquals(fp("""{"n":1}"""), fp("""{"n":1.0}"""))
    }

    @Test
    fun `S14 an explicit null differs from an absent member`() {
        assertEquals("5b4da02351c1c20974b216a5e3a4edb59ac51cbda6be3e9d82952b4f5beea463", fp("""{"n":null}"""))
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", fp("{}"))
        assertNotEquals(fp("""{"n":null}"""), fp("{}"))
    }

    @Test
    fun `S14 the string 1 differs from the number 1`() {
        assertNotEquals(fp("""{"n":"1"}"""), fp("""{"n":1}"""))
    }

    // S14 - actor proof ignored
    @Test
    fun `S14 an actor object is reduced to id kind and parent`() {
        // canonical text: {"actor":{"id":"a1","kind":"subagent","parent":"p1"},"x":1}
        val withProof = """{"x":1,"actor":{"proof":"sig-1","parent":"p1","kind":"subagent","id":"a1","extra":true}}"""
        assertEquals("1e7a06f4eeecd0aa294cb849dad92cedeca521aa03b4600df66f5b764c4b8010", fp(withProof))
    }

    @Test
    fun `S14 changing only actor proof leaves the fingerprint unchanged`() {
        val one = fp("""{"actor":{"id":"a","kind":"k","proof":"AAA"},"v":1}""")
        val two = fp("""{"actor":{"id":"a","kind":"k","proof":"BBB"},"v":1}""")
        assertEquals(one, two)
    }

    @Test
    fun `S14 an actor nested inside an element is also reduced`() {
        val one = fp("""{"element":{"actor":{"id":"a","kind":"k","proof":"AAA"},"t":"start"}}""")
        val two = fp("""{"element":{"actor":{"id":"a","kind":"k"},"t":"start"}}""")
        assertEquals(one, two)
    }

    @Test
    fun `S14 changing the actor id or kind or parent changes the fingerprint`() {
        val base = fp("""{"actor":{"id":"a","kind":"k","parent":"p"}}""")
        assertNotEquals(base, fp("""{"actor":{"id":"b","kind":"k","parent":"p"}}"""))
        assertNotEquals(base, fp("""{"actor":{"id":"a","kind":"j","parent":"p"}}"""))
        assertNotEquals(base, fp("""{"actor":{"id":"a","kind":"k","parent":"q"}}"""))
    }

    @Test
    fun `S14 an actor member that is not an object is kept as written`() {
        // A string under a key named actor is not an actor object: it is part of the content.
        assertNotEquals(fp("""{"actor":"x"}"""), fp("""{"actor":"y"}"""))
    }

    @Test
    fun `S14 a changed leaf value changes the fingerprint`() {
        assertNotEquals(fp("""{"title":"A"}"""), fp("""{"title":"B"}"""))
    }
}

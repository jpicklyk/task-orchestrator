package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item f1b003a6) for [CursorCodec], scenario S10.
 *
 * Oracle: envelope section 2 (the cursor example `eyJvZmZzZXQiOjUwfQ`) and section 7.2 (opaque base64 of `{offset}`),
 * recomputed independently here with `java.util.Base64` over the bytes of the JSON text `{"offset":50}`; every cursor that
 * does not decode to exactly `{"offset": <non-negative Int>}` is `invalid_cursor` (error code table, section 4, no detail).
 * NEW-SURFACE: the codec does not exist before the item.
 */
class CursorCodecTest {
    private fun b64(text: String): String = Base64.getEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun assertInvalid(
        cursor: String,
        label: String = cursor
    ) {
        val outcome = CursorCodec.decode(cursor)
        assertIs<Outcome.Err>(outcome, "cursor '$label' must be rejected but was $outcome")
        assertEquals(ErrorCode.INVALID_CURSOR, outcome.error.code, "cursor '$label'")
        assertNull(outcome.error.detail, "invalid_cursor defines no detail ('$label')")
    }

    private fun assertDecodes(
        cursor: String,
        offset: Int
    ) {
        val outcome = CursorCodec.decode(cursor)
        assertIs<Outcome.Ok<Int>>(outcome, "cursor '$cursor' must decode but was $outcome")
        assertEquals(offset, outcome.value)
    }

    @Test
    fun `S10 encode of 50 is the envelope example and equals an independent base64 computation`() {
        assertEquals("eyJvZmZzZXQiOjUwfQ", CursorCodec.encode(50))
        assertEquals(b64("""{"offset":50}"""), CursorCodec.encode(50), "independent computation of the same bytes")
    }

    @Test
    fun `S10 encode matches the independent base64 for other offsets and carries no padding`() {
        for (n in listOf(0, 1, 9, 10, 99, 100, 143, 200, 1000, 65535, Int.MAX_VALUE)) {
            val encoded = CursorCodec.encode(n)
            assertEquals(b64("""{"offset":$n}"""), encoded, "offset $n")
            assertEquals(false, encoded.contains('='), "no padding for $n")
        }
    }

    @Test
    fun `S10 decode inverts encode across the range including zero and Int MAX_VALUE`() {
        for (n in listOf(0, 1, 2, 49, 50, 51, 200, 4096, 1_000_000, Int.MAX_VALUE)) {
            assertDecodes(CursorCodec.encode(n), n)
        }
    }

    @Test
    fun `S10 decode of the envelope example is 50`() {
        assertDecodes("eyJvZmZzZXQiOjUwfQ", 50)
    }

    @Test
    fun `S10 probe padding is optional on decode`() {
        assertDecodes("eyJvZmZzZXQiOjUwfQ==", 50)
        assertDecodes(Base64.getEncoder().encodeToString("""{"offset":7}""".toByteArray()), 7)
    }

    @Test
    fun `S10 not base64 at all is invalid_cursor`() {
        assertInvalid("!!!notbase64!!!")
        assertInvalid("eyJvZmZzZXQiOjUwfQ%")
        assertInvalid("eyJv ZmZzZXQiOjUwfQ", "embedded space")
    }

    @Test
    fun `S10 empty and blank strings are invalid_cursor`() {
        assertInvalid("")
        assertInvalid("   ", "blank")
    }

    @Test
    fun `S10 base64 of non-JSON text is invalid_cursor`() {
        assertInvalid(b64("offset=50"), "base64 of offset=50")
        assertInvalid(b64("not json"), "base64 of not json")
    }

    @Test
    fun `S10 a negative offset is invalid_cursor while zero is valid`() {
        assertInvalid(b64("""{"offset":-1}"""), "offset -1")
        assertInvalid(b64("""{"offset":-50}"""), "offset -50")
        assertDecodes(b64("""{"offset":0}"""), 0)
    }

    @Test
    fun `S10 an offset that is a string, a fraction, null or a boolean is invalid_cursor`() {
        assertInvalid(b64("""{"offset":"5"}"""), "string offset")
        assertInvalid(b64("""{"offset":1.5}"""), "fractional offset")
        assertInvalid(b64("""{"offset":null}"""), "null offset")
        assertInvalid(b64("""{"offset":true}"""), "boolean offset")
        assertInvalid(b64("""{"offset":[5]}"""), "array offset")
    }

    @Test
    fun `S10 an offset beyond Int range is invalid_cursor`() {
        assertInvalid(b64("""{"offset":2147483648}"""), "offset Int.MAX_VALUE + 1")
        assertInvalid(b64("""{"offset":99999999999999999999}"""), "offset far beyond Long")
    }

    @Test
    fun `S10 missing or extra keys are invalid_cursor`() {
        assertInvalid(b64("{}"), "empty object")
        assertInvalid(b64("""{"offset":1,"x":2}"""), "extra key")
        assertInvalid(b64("""{"off":1}"""), "wrong key name")
        assertInvalid(b64("""{"Offset":1}"""), "wrong key case")
        assertInvalid(b64("""{"x":1}"""), "other key only")
    }

    @Test
    fun `S10 a JSON value that is not an object is invalid_cursor`() {
        assertInvalid(b64("5"), "bare number")
        assertInvalid(b64("[50]"), "array")
        assertInvalid(b64("\"offset\""), "bare string")
        assertInvalid(b64("null"), "null literal")
    }
}

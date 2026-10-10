package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for [RequestDecoder] and [IdRef], scenarios S12 to S14.
 *
 * Oracles: plan section 3.2 Q1a and the frozen task-scope decisions D3 and D10 (input must be an object, else
 * `invalid_request` on field `$`; reserved top-level keys such as `actor` are removed and never reported; every unknown key
 * at every depth, including objects inside arrays, is collected as a JSON-pointer path with `~0`/`~1` escaping, sorted, and
 * reported as `unknown_parameter`; otherwise every type mismatch, bad enum, bad instant, bad id, missing required field and
 * null for a non-nullable field is collected into `invalid_request.fields[]` sorted by pointer; unknown wins over invalid;
 * strict parsing with no coercion). IdRef oracle: U5 (an id is a canonical 36-char UUID, any case, or a dash-free hex
 * string of 8 to 32 characters; format only, stored as given). NEW-SURFACE: the decoder and IdRef do not exist before the
 * item.
 *
 * Fixtures are the test's own `@Serializable` types; the decoder reads their serial descriptors.
 */
class RequestDecoderTest {
    private val decoder = RequestDecoder()

    @Serializable
    enum class Mode { FAST, SLOW }

    @Serializable
    data class Filter(
        val tag: String? = null,
        val limit: Int? = null
    )

    @Serializable
    data class Op(
        val id: IdRef,
        val title: String? = null
    )

    @Serializable
    data class Req(
        val name: String,
        val count: Int = 1,
        val size: Int = 10,
        val mode: Mode = Mode.FAST,
        @Serializable(with = IsoInstantSerializer::class) val at: Instant? = null,
        val ref: IdRef? = null,
        val filter: Filter? = null,
        val ops: List<Op> = emptyList()
    )

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun decode(
        text: String,
        reserved: Set<String>? = null
    ): Outcome<Req> =
        if (reserved == null) {
            decoder.decode(serializer<Req>(), parse(text))
        } else {
            decoder.decode(serializer<Req>(), parse(text), reserved)
        }

    private fun okValue(outcome: Outcome<Req>): Req {
        assertIs<Outcome.Ok<Req>>(outcome, "expected Ok but was $outcome")
        return outcome.value
    }

    private fun unknownParameters(outcome: Outcome<Req>): List<String> {
        assertIs<Outcome.Err>(outcome, "expected Err but was $outcome")
        assertEquals(ErrorCode.UNKNOWN_PARAMETER, outcome.error.code, "error was ${outcome.error}")
        val detail = outcome.error.detail
        assertIs<ErrorDetail.UnknownParameter>(detail)
        return detail.parameters
    }

    private fun violations(outcome: Outcome<Req>): List<FieldViolation> {
        assertIs<Outcome.Err>(outcome, "expected Err but was $outcome")
        assertEquals(ErrorCode.INVALID_REQUEST, outcome.error.code, "error was ${outcome.error}")
        val detail = outcome.error.detail
        assertIs<ErrorDetail.InvalidRequest>(detail)
        return detail.fields
    }

    // ---------------------------------------------------------------- S12 valid decode

    @Test
    fun `S12 a valid body decodes every field`() {
        val value =
            okValue(
                decode(
                    """{"name":"n","count":3,"size":7,"mode":"SLOW","at":"2026-10-10T12:30:00Z",
                       "ref":"123e4567-e89b-12d3-a456-426614174000","filter":{"tag":"t","limit":5},
                       "ops":[{"id":"abcdef12","title":"x"},{"id":"abcdef1234567890abcdef1234567890"}]}"""
                )
            )
        assertEquals("n", value.name)
        assertEquals(3, value.count)
        assertEquals(7, value.size)
        assertEquals(Mode.SLOW, value.mode)
        assertEquals(Instant.parse("2026-10-10T12:30:00Z"), value.at)
        assertEquals(IdRef("123e4567-e89b-12d3-a456-426614174000"), value.ref)
        assertEquals(Filter("t", 5), value.filter)
        assertEquals(listOf(Op(IdRef("abcdef12"), "x"), Op(IdRef("abcdef1234567890abcdef1234567890"), null)), value.ops)
    }

    @Test
    fun `S12 a minimal body applies the defaults`() {
        val value = okValue(decode("""{"name":"n"}"""))
        assertEquals(Req(name = "n"), value)
    }

    @Test
    fun `S12 the reserved actor key is removed and never reported`() {
        val value = okValue(decode("""{"name":"n","actor":{"id":"agent-1","kind":"subagent","extra":"ignored"}}"""))
        assertEquals(Req(name = "n"), value)
        // a non-object actor is also lifted away
        assertEquals(Req(name = "n"), okValue(decode("""{"name":"n","actor":"just-a-string"}""")))
    }

    @Test
    fun `S12 actor is stripped only at the top level - a nested actor key is an unknown parameter`() {
        assertEquals(listOf("/filter/actor"), unknownParameters(decode("""{"name":"n","filter":{"actor":"x"}}""")))
        assertEquals(listOf("/ops/0/actor"), unknownParameters(decode("""{"name":"n","ops":[{"id":"abcdef12","actor":"x"}]}""")))
    }

    @Test
    fun `S12 a custom reserved set replaces the default - actor becomes unknown, the named key is stripped`() {
        assertEquals(Req(name = "n"), okValue(decode("""{"name":"n","requestId":"r1"}""", reserved = setOf("requestId"))))
        assertEquals(listOf("/actor"), unknownParameters(decode("""{"name":"n","actor":{}}""", reserved = setOf("requestId"))))
        assertEquals(listOf("/actor"), unknownParameters(decode("""{"name":"n","actor":{}}""", reserved = emptySet())))
    }

    @Test
    fun `S12 a reserved key is stripped before validation - it can hide neither a fault nor create one`() {
        // the reserved key carries a value that would fail every type rule if it were validated
        assertEquals(Req(name = "n"), okValue(decode("""{"name":"n","actor":[1,2,3]}""")))
        // the real fault is still reported next to a reserved key
        assertEquals(listOf("/name"), violations(decode("""{"actor":{"id":"a"}}""")).map { it.field })
    }

    @Test
    fun `S12 null for a nullable field and absent are both accepted and equal`() {
        assertEquals(Req(name = "n"), okValue(decode("""{"name":"n","filter":null,"at":null,"ref":null}""")))
    }

    @Test
    fun `S12 an empty string stays an empty string - empty, absent and null are three states`() {
        val withEmpty = okValue(decode("""{"name":"","filter":{"tag":""}}"""))
        assertEquals("", withEmpty.name)
        assertEquals("", withEmpty.filter?.tag)
        val absentTag = okValue(decode("""{"name":"n","filter":{}}"""))
        assertNull(absentTag.filter?.tag)
        assertNotNull(absentTag.filter, "an empty object is not null")
    }

    // ---------------------------------------------------------------- S13 unknown parameters

    @Test
    fun `S13 unknown keys at three depths are all collected as pointers and sorted`() {
        val parameters =
            unknownParameters(
                decode("""{"name":"n","foo":1,"filter":{"foo":2},"ops":[{"id":"abcdef12"},{"id":"abcdef12","foo":3}]}""")
            )
        assertEquals(setOf("/foo", "/filter/foo", "/ops/1/foo"), parameters.toSet())
        assertEquals(3, parameters.size, "each unknown key is reported exactly once: $parameters")
        assertEquals(parameters.sorted(), parameters, "pointers are sorted")
    }

    @Test
    fun `S13 several unknown keys at the top level are all reported`() {
        val parameters = unknownParameters(decode("""{"name":"n","zeta":1,"alpha":2,"mid":3}"""))
        assertEquals(listOf("/alpha", "/mid", "/zeta"), parameters)
    }

    @Test
    fun `S13 every array element is walked - unknown keys in the first and the last element are both found`() {
        val parameters =
            unknownParameters(
                decode("""{"name":"n","ops":[{"id":"abcdef12","a":1},{"id":"abcdef12"},{"id":"abcdef12","b":2}]}""")
            )
        assertEquals(listOf("/ops/0/a", "/ops/2/b"), parameters)
    }

    @Test
    fun `S13 probe pointer escaping - a slash in a key is escaped as tilde 1 and a tilde as tilde 0`() {
        val parameters = unknownParameters(decode("""{"name":"n","a/b":1,"c~d":2,"e~/f":3}"""))
        assertEquals(setOf("/a~1b", "/c~0d", "/e~0~1f"), parameters.toSet())
        assertEquals(3, parameters.size)
    }

    @Test
    fun `S13 probe key casing is exact - Name is unknown even though name exists`() {
        val parameters = unknownParameters(decode("""{"name":"n","Name":"x","Count":2}"""))
        assertEquals(listOf("/Count", "/Name"), parameters)
    }

    @Test
    fun `S13 an unknown key whose value is itself an object is reported once at the key, not descended into`() {
        val parameters = unknownParameters(decode("""{"name":"n","extra":{"deep":{"deeper":1}}}"""))
        assertEquals(listOf("/extra"), parameters)
    }

    @Test
    fun `S13 an unknown key under a nullable object that is null is not a fault`() {
        assertEquals(Req(name = "n"), okValue(decode("""{"name":"n","filter":null}""")))
    }

    // ---------------------------------------------------------------- S14 value faults

    @Test
    fun `S14 six value faults are all collected, sorted by pointer`() {
        // /name missing, /count string for int, /size null for a non-null int, /mode bad enum, /at bad instant, /ref bad id
        val fields = violations(decode("""{"count":"x","size":null,"mode":"TURBO","at":"yesterday","ref":"xyz"}"""))
        assertEquals(listOf("/at", "/count", "/mode", "/name", "/ref", "/size"), fields.map { it.field })
        assertTrue(fields.all { it.reason.isNotBlank() }, "every violation has a reason: $fields")
        assertTrue(fields.first { it.field == "/mode" }.received?.contains("TURBO") == true, "received echoes the bad enum: $fields")
        assertTrue(fields.first { it.field == "/ref" }.received?.contains("xyz") == true, "received echoes the bad id: $fields")
    }

    @Test
    fun `S14 a fault inside an array element carries the full pointer`() {
        val fields = violations(decode("""{"name":"n","ops":[{"id":"abcdef12"},{"id":"zz"},{"title":"no id"}]}"""))
        assertEquals(listOf("/ops/1/id", "/ops/2/id"), fields.map { it.field })
    }

    @Test
    fun `S14 a fault inside a nested object carries the full pointer`() {
        val fields = violations(decode("""{"name":"n","filter":{"limit":"many","tag":5}}"""))
        assertEquals(listOf("/filter/limit", "/filter/tag"), fields.map { it.field })
    }

    @Test
    fun `S14 no coercion - a numeric string for an int, an int for a string and a fraction for an int are all faults`() {
        assertEquals(listOf("/count"), violations(decode("""{"name":"n","count":"5"}""")).map { it.field })
        assertEquals(listOf("/name"), violations(decode("""{"name":5}""")).map { it.field })
        assertEquals(listOf("/count"), violations(decode("""{"name":"n","count":5.5}""")).map { it.field })
        assertEquals(listOf("/count"), violations(decode("""{"name":"n","count":true}""")).map { it.field })
        assertEquals(listOf("/count"), violations(decode("""{"name":"n","count":[1]}""")).map { it.field })
    }

    @Test
    fun `S14 an enum value of the wrong case is a fault`() {
        assertEquals(listOf("/mode"), violations(decode("""{"name":"n","mode":"fast"}""")).map { it.field })
    }

    @Test
    fun `S14 an instant without a zone or in another format is a fault, a valid ISO instant is accepted`() {
        assertEquals(listOf("/at"), violations(decode("""{"name":"n","at":"2026-10-10"}""")).map { it.field })
        assertEquals(listOf("/at"), violations(decode("""{"name":"n","at":12345}""")).map { it.field })
        assertEquals(Instant.parse("2026-10-10T00:00:00.123Z"), okValue(decode("""{"name":"n","at":"2026-10-10T00:00:00.123Z"}""")).at)
    }

    @Test
    fun `S14 an id with a fault is reported while a well-formed id never is`() {
        assertEquals(listOf("/ref"), violations(decode("""{"name":"n","ref":"1234567"}""")).map { it.field })
        assertEquals(listOf("/ref"), violations(decode("""{"name":"n","ref":12345678}""")).map { it.field })
        assertEquals(IdRef("12345678"), okValue(decode("""{"name":"n","ref":"12345678"}""")).ref)
    }

    @Test
    fun `S14 unknown parameters win over value faults - only the unknown keys are reported`() {
        val outcome = decode("""{"name":"n","count":"x","bogus":1,"mode":"TURBO"}""")
        assertEquals(listOf("/bogus"), unknownParameters(outcome))
    }

    @Test
    fun `S14 a missing required field alone is invalid_request on that pointer`() {
        assertEquals(listOf("/name"), violations(decode("""{}""")).map { it.field })
        assertEquals(listOf("/name"), violations(decode("""{"count":2}""")).map { it.field })
    }

    @Test
    fun `S14 a non-object input is invalid_request on the root pointer`() {
        for (input in listOf<JsonElement>(
            JsonArray(listOf(JsonPrimitive(1))),
            JsonPrimitive("text"),
            JsonPrimitive(5),
            JsonNull,
            JsonArray(emptyList())
        )) {
            val outcome = decoder.decode(serializer<Req>(), input)
            assertIs<Outcome.Err>(outcome, "input $input must be rejected")
            assertEquals(ErrorCode.INVALID_REQUEST, outcome.error.code, "input $input")
            val detail = assertIs<ErrorDetail.InvalidRequest>(outcome.error.detail)
            assertEquals(listOf("$"), detail.fields.map { it.field }, "input $input")
        }
    }

    @Test
    fun `S14 an empty object for a type with no required fields decodes`() {
        val outcome = decoder.decode(serializer<Filter>(), JsonObject(emptyMap()))
        assertEquals(Outcome.Ok(Filter()), outcome)
    }

    // ---------------------------------------------------------------- IdRef (S12 boundaries)

    @Test
    fun `S12 IdRef accepts a canonical UUID in any case and keeps the raw text as given`() {
        for (raw in listOf(
            "123e4567-e89b-12d3-a456-426614174000",
            "123E4567-E89B-12D3-A456-426614174000",
            "123e4567-E89B-12d3-A456-426614174000"
        )) {
            assertEquals(IdRef(raw), IdRef.parse(raw), raw)
            assertEquals(raw, IdRef.parse(raw)?.raw, "no normalization of $raw")
        }
    }

    @Test
    fun `S12 IdRef accepts dash-free hex of 8 to 32 characters at and around the boundaries`() {
        for (length in listOf(8, 9, 12, 16, 31, 32)) {
            val raw = "abcdef0123456789abcdef0123456789".take(length)
            assertEquals(IdRef(raw), IdRef.parse(raw), "length $length")
        }
        assertEquals(IdRef("ABCDEF01"), IdRef.parse("ABCDEF01"), "uppercase hex is accepted and kept as given")
    }

    @Test
    fun `S12 IdRef rejects hex shorter than 8 and longer than 32`() {
        assertNull(IdRef.parse("abcdef0"), "7 hex")
        assertNull(IdRef.parse("abcdef01234567890abcdef012345678a"), "33 hex")
        assertNull(IdRef.parse("a"), "1 hex")
        assertNull(IdRef.parse(""), "empty")
    }

    @Test
    fun `S12 IdRef rejects non-hex characters, whitespace and mis-grouped UUIDs`() {
        assertNull(IdRef.parse("ghijklmn"), "non-hex letters, 8 chars")
        assertNull(IdRef.parse("abcdef0g"), "one bad char")
        assertNull(IdRef.parse(" abcdef01"), "leading space")
        assertNull(IdRef.parse("abcdef01 "), "trailing space")
        assertNull(IdRef.parse("123e4567e-89b-12d3-a456-426614174000"), "mis-grouped UUID")
        assertNull(IdRef.parse("123e4567-e89b-12d3-a456-42661417400"), "UUID one char short")
        assertNull(IdRef.parse("123e4567-e89b-12d3-a456-4266141740000"), "UUID one char long")
        assertNull(IdRef.parse("123e4567-e89b-12d3-a456-42661417400g"), "UUID with a non-hex char")
        assertNull(IdRef.parse("abcd-ef01"), "a dash inside a short hex")
        assertNull(IdRef.parse("{123e4567-e89b-12d3-a456-426614174000}"), "braced UUID")
    }
}

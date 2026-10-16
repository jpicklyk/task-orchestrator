package io.github.jpicklyk.mcptask.current.interfaces.shared.envelope

import io.github.jpicklyk.mcptask.current.application.api.BatchExecution
import io.github.jpicklyk.mcptask.current.application.port.ListPage
import io.github.jpicklyk.mcptask.current.domain.error.BatchResult
import io.github.jpicklyk.mcptask.current.domain.error.ConfigViolation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.MissingNote
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.ResourceRef
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for [EnvelopeEncoder], scenarios S1 to S9.
 *
 * Oracles: `plans/v4-response-envelope.md` section 2 (Success, List data, Error, Batch results; `retryAfterMs` is
 * error-level on the wire), section 3 (the status is a pure function of `error.code`: the table below is transcribed from it,
 * not read from the enum), section 4 (the detail shapes: the member names are the names printed in the catalog table),
 * section 8 (U15 `page.capped`, U18) and the frozen task-scope contract (fixed key order, `fix` only when the domain error
 * has one, nulls never emitted, `retryAfterMs` copied to error level, atomic failure carries `index` and no `data`,
 * `partial_failure` carries both `error` and `data.results`). Every test body is fixture-driven; no expected value is read
 * from the encoder. NEW-SURFACE: the encoder does not exist before the item.
 */
class EnvelopeEncoderTest {
    private val reqId = "k7f3q9ab"
    private val i1 = ErrorFixtures.id1.toString()
    private val i2 = ErrorFixtures.id2.toString()

    // envelope section 3, transcribed per wire code
    private val statusByWire: Map<String, Int> =
        mapOf(
            "partial_failure" to 207,
            "invalid_request" to 400,
            "unknown_parameter" to 400,
            "invalid_cursor" to 400,
            "unauthenticated" to 401,
            "forbidden" to 403,
            "seat_forbidden" to 403,
            "note_owned_by_other" to 403,
            "not_claim_holder" to 403,
            "not_found" to 404,
            "version_conflict" to 409,
            "duplicate" to 409,
            "ambiguous_id" to 409,
            "invalid_transition" to 409,
            "gate_blocked" to 409,
            "dependency_unmet" to 409,
            "cycle_detected" to 409,
            "claim_held" to 409,
            "resource_unavailable" to 409,
            "run_closed" to 409,
            "run_paused" to 409,
            "run_at_capacity" to 409,
            "invalid_run_state" to 409,
            "decision_closed" to 409,
            "idempotency_mismatch" to 409,
            "schema_pinned_conflict" to 409,
            "payload_too_large" to 413,
            "note_too_long" to 413,
            "schema_violation" to 422,
            "config_invalid" to 422,
            "internal" to 500,
            "unavailable" to 503
        )

    private val errorKeyOrder = listOf("code", "kind", "message", "detail", "fix", "retryAfterMs", "index")

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.member(key: String): JsonObject = getValue(key).jsonObject

    private fun keys(o: JsonObject): List<String> = o.keys.toList()

    private fun err(code: ErrorCode): DomainError = ErrorFixtures.error(code)

    private fun assertNoJsonNull(
        element: JsonElement,
        where: String = "$"
    ) {
        when (element) {
            is JsonNull -> throw AssertionError("JsonNull emitted at $where")
            is JsonObject -> element.forEach { (k, v) -> assertNoJsonNull(v, "$where.$k") }
            is JsonArray -> element.forEachIndexed { i, v -> assertNoJsonNull(v, "$where[$i]") }
            else -> Unit
        }
    }

    private fun assertWellFormed(
        envelope: EncodedEnvelope,
        label: String
    ) {
        EnvelopeConformance.assertConforms(envelope.body, label)
        assertNoJsonNull(envelope.body)
        assertEquals(
            envelope.body,
            Json.parseToJsonElement(envelope.body.toString()),
            "$label: toString is parseable JSON equal to the body"
        )
    }

    // ---------------------------------------------------------------- S1 success

    @Test
    fun `S1 a success with data has the fixed key order ok, data, meta and status 200`() {
        val envelope = EnvelopeEncoder.outcome(Outcome.Ok(5), reqId) { JsonPrimitive("v$it") }

        assertEquals("""{"ok":true,"data":"v5","meta":{"reqId":"k7f3q9ab"}}""", envelope.body.toString())
        assertEquals(200, envelope.httpStatus)
        assertWellFormed(envelope, "success")
    }

    @Test
    fun `S1 object data is embedded as given`() {
        val envelope = EnvelopeEncoder.outcome(Outcome.Ok("x"), reqId) { buildJsonObject { put("id", it) } }

        assertEquals("""{"ok":true,"data":{"id":"x"},"meta":{"reqId":"k7f3q9ab"}}""", envelope.body.toString())
    }

    @Test
    fun `S1 a mapper returning null omits data entirely and never emits null`() {
        val envelope = EnvelopeEncoder.outcome(Outcome.Ok("x"), reqId) { null }

        assertEquals("""{"ok":true,"meta":{"reqId":"k7f3q9ab"}}""", envelope.body.toString())
        assertFalse("data" in envelope.body)
        assertWellFormed(envelope, "no-data success")
    }

    @Test
    fun `S1 meta replayed is present only when replayed is true`() {
        val replayed = EnvelopeEncoder.outcome(Outcome.Ok("x"), reqId, replayed = true) { JsonPrimitive("v") }
        assertEquals("""{"ok":true,"data":"v","meta":{"reqId":"k7f3q9ab","replayed":true}}""", replayed.body.toString())

        val plain = EnvelopeEncoder.outcome(Outcome.Ok("x"), reqId, replayed = false) { JsonPrimitive("v") }
        assertFalse("replayed" in plain.body.member("meta"), "replayed=false must not emit the key")
        assertEquals(setOf("reqId"), plain.body.member("meta").keys)

        val defaulted = EnvelopeEncoder.outcome(Outcome.Ok("x"), reqId) { JsonPrimitive("v") }
        assertEquals(plain.body, defaulted.body)
        assertWellFormed(replayed, "replayed success")
    }

    @Test
    fun `S1 an Err outcome delegates to the error encoding and never invokes the data mapper`() {
        val failure = err(ErrorCode.GATE_BLOCKED)
        var invoked = 0
        val envelope =
            EnvelopeEncoder.outcome(Outcome.Err(failure), reqId) {
                invoked++
                JsonPrimitive("never")
            }

        assertEquals(0, invoked)
        assertEquals(EnvelopeEncoder.error(failure, reqId), envelope)
        assertEquals(409, envelope.httpStatus)
    }

    @Test
    fun `S1 probe an arbitrary reqId and an escaped message survive encoding as valid JSON`() {
        val awkward = DomainError(ErrorCode.INVALID_CURSOR, "He said \"hi\"\nthen left \u00e9 \\ done", null, emptyMap())
        val envelope = EnvelopeEncoder.error(awkward, "r\"1")

        assertEquals(
            awkward.message,
            envelope.body
                .member("error")
                .getValue("message")
                .jsonPrimitive.content
        )
        assertEquals(
            "r\"1",
            envelope.body
                .member("meta")
                .getValue("reqId")
                .jsonPrimitive.content
        )
        assertEquals(envelope.body, Json.parseToJsonElement(envelope.body.toString()))
    }

    // ---------------------------------------------------------------- S2 error members and status

    @Test
    fun `S2 the status table covers every code exactly`() {
        assertEquals(
            ErrorCode.entries.map { it.wire }.toSet(),
            statusByWire.keys,
            "the transcribed envelope section 3 table covers each code once"
        )
    }

    @Test
    fun `S2 every code encodes the fixed member set, order, kind, fix and the section 3 status`() {
        for (code in ErrorCode.entries) {
            val failure = err(code)
            val envelope = EnvelopeEncoder.error(failure, reqId)
            val label = code.wire

            assertEquals(listOf("ok", "error", "meta"), keys(envelope.body), "$label: top-level key order")
            assertEquals(JsonPrimitive(false), envelope.body.getValue("ok"), label)
            assertEquals(obj("""{"reqId":"k7f3q9ab"}"""), envelope.body.member("meta"), "$label: meta")

            val error = envelope.body.member("error")
            val present = keys(error)
            assertTrue(present.all { it in errorKeyOrder }, "$label: unexpected error members $present")
            assertEquals(present.sortedBy { errorKeyOrder.indexOf(it) }, present, "$label: error member order")
            assertEquals(code.wire, error.getValue("code").jsonPrimitive.content, label)
            assertEquals(code.kind.name.lowercase(), error.getValue("kind").jsonPrimitive.content, "$label: kind")
            val expectedMessage = if (code == ErrorCode.INTERNAL) EnvelopeEncoder.INTERNAL_MESSAGE else failure.message
            assertEquals(
                expectedMessage,
                error.getValue("message").jsonPrimitive.content,
                "$label: message (internal is always the fixed text)"
            )
            assertEquals(code.detailClass != null, "detail" in error, "$label: detail iff the code defines one")
            val fix = failure.fix
            if (fix != null) {
                assertEquals(fix, error.getValue("fix").jsonPrimitive.content, "$label: fix text")
            } else {
                assertFalse("fix" in error, "$label: no fix member without a domain fix")
            }
            assertFalse("index" in error, "$label: index only on an atomic failure")

            assertEquals(statusByWire.getValue(code.wire), envelope.httpStatus, "$label: status")
            assertEquals(code.httpStatus, envelope.httpStatus, "$label: status equals the enum")
            if (code != ErrorCode.PARTIAL_FAILURE) assertWellFormed(envelope, label)
        }
    }

    @Test
    fun `S2 a gate_blocked error carries code kind message detail and fix with status 409`() {
        val envelope = EnvelopeEncoder.error(err(ErrorCode.GATE_BLOCKED), reqId)

        assertEquals(listOf("code", "kind", "message", "detail", "fix"), keys(envelope.body.member("error")))
        assertEquals(409, envelope.httpStatus)
    }

    @Test
    fun `S2 permanent errors other than partial_failure carry a non-blank fix`() {
        for (code in ErrorCode.entries.filter { it.kind.name == "PERMANENT" && it != ErrorCode.PARTIAL_FAILURE }) {
            val fix = EnvelopeEncoder.error(err(code), reqId).body.member("error")["fix"]
            assertNotNull(fix, "${code.wire}: fix is required for a permanent error")
            assertTrue(fix.jsonPrimitive.content.isNotBlank(), code.wire)
        }
    }

    @Test
    fun `S2 an Ok outcome is status 200`() {
        assertEquals(200, EnvelopeEncoder.outcome(Outcome.Ok(1), reqId) { null }.httpStatus)
    }

    @Test
    fun `S2 probe error index zero is emitted, not treated as absent`() {
        val withZero = EnvelopeEncoder.error(err(ErrorCode.NOT_FOUND), reqId, index = 0)
        assertEquals(JsonPrimitive(0), withZero.body.member("error")["index"])
        val without = EnvelopeEncoder.error(err(ErrorCode.NOT_FOUND), reqId)
        assertFalse("index" in without.body.member("error"))
    }

    @Test
    fun `S2 errorMember is the error object of the envelope, with index last when given`() {
        val failure = err(ErrorCode.GATE_BLOCKED)
        assertEquals(EnvelopeEncoder.error(failure, reqId).body.member("error"), EnvelopeEncoder.errorMember(failure))

        val indexed = EnvelopeEncoder.errorMember(failure, index = 2)
        assertEquals(listOf("code", "kind", "message", "detail", "fix", "index"), keys(indexed))
        assertEquals(JsonPrimitive(2), indexed["index"])
        assertEquals(indexed, EnvelopeEncoder.error(failure, reqId, index = 2).body.member("error"))
    }

    // ---------------------------------------------------------------- S3 retryAfterMs

    private fun claimHeld(ms: Long) =
        DomainError(
            ErrorCode.CLAIM_HELD,
            "Held by another agent.",
            ErrorDetail.ClaimHeld(ErrorFixtures.id1, Instant.parse("2026-10-07T12:00:00Z"), ms),
            ErrorFixtures.fixArgs(ErrorCode.CLAIM_HELD)
        )

    private fun resourceUnavailable(ms: Long?) =
        DomainError(
            ErrorCode.RESOURCE_UNAVAILABLE,
            "Resource busy.",
            ErrorDetail.ResourceUnavailable(ErrorFixtures.id1, listOf(ResourceRef("gradle", "exclusive")), ms),
            ErrorFixtures.fixArgs(ErrorCode.RESOURCE_UNAVAILABLE)
        )

    @Test
    fun `S3 claim_held copies retryAfterMs to error level and keeps it in detail`() {
        val error = EnvelopeEncoder.error(claimHeld(1500), reqId).body.member("error")

        assertEquals(JsonPrimitive(1500L), error["retryAfterMs"])
        assertFalse(error.getValue("retryAfterMs").jsonPrimitive.isString, "a number, not a string")
        assertEquals(JsonPrimitive(1500L), error.member("detail")["retryAfterMs"])
        val order = keys(error)
        assertTrue(order.indexOf("fix") < order.indexOf("retryAfterMs"), "retryAfterMs follows fix: $order")
    }

    @Test
    fun `S3 resource_unavailable and unavailable copy a present retryAfterMs`() {
        val resource = EnvelopeEncoder.error(resourceUnavailable(800), reqId).body.member("error")
        assertEquals(JsonPrimitive(800L), resource["retryAfterMs"])
        assertEquals(JsonPrimitive(800L), resource.member("detail")["retryAfterMs"])

        val unavailable = EnvelopeEncoder.error(err(ErrorCode.UNAVAILABLE), reqId).body.member("error")
        assertEquals(JsonPrimitive(250L), unavailable["retryAfterMs"], "the fixture detail carries 250")
        assertEquals(JsonPrimitive(250L), unavailable.member("detail")["retryAfterMs"])
    }

    @Test
    fun `S3 resource_unavailable without an estimate has no retryAfterMs at either level`() {
        val error = EnvelopeEncoder.error(resourceUnavailable(null), reqId).body.member("error")

        assertFalse("retryAfterMs" in error)
        assertFalse("retryAfterMs" in error.member("detail"))
    }

    @Test
    fun `S3 codes without a retry estimate never emit retryAfterMs`() {
        for (code in listOf(
            ErrorCode.NOT_FOUND,
            ErrorCode.INTERNAL,
            ErrorCode.PARTIAL_FAILURE,
            ErrorCode.GATE_BLOCKED,
            ErrorCode.INVALID_CURSOR
        )) {
            assertFalse("retryAfterMs" in EnvelopeEncoder.error(err(code), reqId).body.member("error"), code.wire)
        }
    }

    @Test
    fun `S3 across all fixtures error level retryAfterMs is present exactly when the detail carries one, with the same value`() {
        for (code in ErrorCode.entries) {
            val error = EnvelopeEncoder.error(err(code), reqId).body.member("error")
            val inDetail = (error["detail"] as? JsonObject)?.get("retryAfterMs")
            assertEquals(inDetail, error["retryAfterMs"], "${code.wire}: error-level retryAfterMs mirrors the detail")
        }
    }
    // ---------------------------------------------------------------- S4 detail serialization

    private val detailByCode: Map<ErrorCode, JsonObject> =
        mapOf(
            ErrorCode.INVALID_REQUEST to obj("""{"fields":[{"field":"title","reason":"must not be blank"}]}"""),
            ErrorCode.UNKNOWN_PARAMETER to obj("""{"parameters":["bogus"]}"""),
            ErrorCode.NOT_FOUND to obj("""{"kind":"item","id":"$i1"}"""),
            ErrorCode.AMBIGUOUS_ID to obj("""{"prefix":"ab12","candidates":["$i1","$i2"]}"""),
            ErrorCode.VERSION_CONFLICT to obj("""{"kind":"item","id":"$i1","expected":3,"actual":4}"""),
            ErrorCode.DUPLICATE to obj("""{"kind":"dependency","existingId":"$i2"}"""),
            ErrorCode.IDEMPOTENCY_MISMATCH to obj("""{"idempotencyKey":"key-1"}"""),
            ErrorCode.INVALID_TRANSITION to obj("""{"itemId":"$i1","fromRole":"queue","trigger":"complete","allowed":["start"]}"""),
            ErrorCode.GATE_BLOCKED to
                obj("""{"itemId":"$i1","role":"queue","missing":[{"key":"task-scope","role":"queue","seat":"planner"}]}"""),
            ErrorCode.DEPENDENCY_UNMET to obj("""{"itemId":"$i1","blockers":[{"id":"$i2","role":"work"}]}"""),
            ErrorCode.CYCLE_DETECTED to obj("""{"path":["$i1","$i2","$i1"]}"""),
            ErrorCode.CLAIM_HELD to obj("""{"itemId":"$i1","expiresAt":"2026-10-07T12:00:00Z","retryAfterMs":5000}"""),
            ErrorCode.NOT_CLAIM_HOLDER to obj("""{"itemId":"$i1"}"""),
            ErrorCode.SEAT_FORBIDDEN to obj("""{"itemId":"$i1","seat":"reviewer","action":"write","allowedSeats":["planner"]}"""),
            ErrorCode.NOTE_OWNED_BY_OTHER to obj("""{"itemId":"$i1","key":"task-scope"}"""),
            ErrorCode.NOTE_TOO_LONG to obj("""{"key":"task-scope","max":100,"actual":101}"""),
            ErrorCode.RESOURCE_UNAVAILABLE to obj("""{"itemId":"$i1","resources":[{"name":"gradle","mode":"exclusive"}]}"""),
            ErrorCode.RUN_CLOSED to obj("""{"runId":"$i1","terminalReason":"cancelled"}"""),
            ErrorCode.RUN_PAUSED to obj("""{"runId":"$i1","pauseReason":"manual"}"""),
            ErrorCode.RUN_AT_CAPACITY to obj("""{"runId":"$i1","active":2,"max":2}"""),
            ErrorCode.INVALID_RUN_STATE to obj("""{"runId":"$i1","status":"paused","allowed":["close"]}"""),
            ErrorCode.DECISION_CLOSED to obj("""{"decisionId":"$i1","status":"answered"}"""),
            ErrorCode.SCHEMA_VIOLATION to obj("""{"reason":"unknown type"}"""),
            ErrorCode.SCHEMA_PINNED_CONFLICT to obj("""{"itemId":"$i1","pinnedVersion":"v1","currentVersion":"v2"}"""),
            ErrorCode.CONFIG_INVALID to obj("""{"errors":[{"path":"traits[0]","reason":"unknown key"}]}"""),
            ErrorCode.PAYLOAD_TOO_LARGE to obj("""{"max":1000,"actual":1001}"""),
            ErrorCode.FORBIDDEN to obj("""{"scope":"root","required":"root-1"}"""),
            ErrorCode.UNAVAILABLE to obj("""{"retryAfterMs":250}""")
        )

    @Test
    fun `S4 the detail table covers every code that defines a detail`() {
        assertEquals(ErrorCode.entries.filter { it.detailClass != null }.toSet(), detailByCode.keys)
    }

    @Test
    fun `S4 detail of each fixture detail has the catalog member names and value types`() {
        for ((code, expected) in detailByCode) {
            val detail = assertNotNull(ErrorFixtures.detail(code), code.wire)
            assertEquals(expected, EnvelopeEncoder.detail(detail), "${code.wire}: detail()")
            assertEquals(
                expected,
                EnvelopeEncoder
                    .error(err(code), reqId)
                    .body
                    .member("error")
                    .member("detail"),
                "${code.wire}: through error()"
            )
        }
    }

    @Test
    fun `S4 null members are omitted - an unset seat, an absent received value, an absent owner`() {
        val noSeat = ErrorDetail.GateBlocked(ErrorFixtures.id1, "queue", listOf(MissingNote("task-scope", "queue")))
        assertEquals(
            obj("""{"itemId":"$i1","role":"queue","missing":[{"key":"task-scope","role":"queue"}]}"""),
            EnvelopeEncoder.detail(noSeat)
        )

        val withReceived = ErrorDetail.InvalidRequest(listOf(FieldViolation("limit", "must be at most 200", "201")))
        assertEquals(
            obj("""{"fields":[{"field":"limit","reason":"must be at most 200","received":"201"}]}"""),
            EnvelopeEncoder.detail(withReceived)
        )

        val noOwner = ErrorDetail.NoteOwnedByOther(ErrorFixtures.id1, "k", null)
        assertFalse("ownerSeat" in EnvelopeEncoder.detail(noOwner))
        val owner = ErrorDetail.NoteOwnedByOther(ErrorFixtures.id1, "k", "planner")
        assertEquals(obj("""{"itemId":"$i1","key":"k","ownerSeat":"planner"}"""), EnvelopeEncoder.detail(owner))
    }

    @Test
    fun `S4 every EntityKind and ForbiddenScope value is lowercase on the wire`() {
        for (kind in EntityKind.entries) {
            val detail = EnvelopeEncoder.detail(ErrorDetail.NotFound(kind, "x"))
            assertEquals(kind.name.lowercase(), detail.getValue("kind").jsonPrimitive.content)
        }
        for (scope in ForbiddenScope.entries) {
            val detail = EnvelopeEncoder.detail(ErrorDetail.Forbidden(scope, "r"))
            assertEquals(scope.name.lowercase(), detail.getValue("scope").jsonPrimitive.content)
        }
    }

    @Test
    fun `S4 an Instant is its ISO-8601 Z text with full precision and a long stays a number`() {
        val precise = ErrorDetail.ClaimHeld(ErrorFixtures.id1, Instant.parse("2026-10-07T12:00:00.123456Z"), 7L)
        val encoded = EnvelopeEncoder.detail(precise)
        assertEquals("2026-10-07T12:00:00.123456Z", encoded.getValue("expiresAt").jsonPrimitive.content)
        assertTrue(encoded.getValue("expiresAt").jsonPrimitive.isString)

        val big = ErrorDetail.PayloadTooLarge(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val bigJson = EnvelopeEncoder.detail(big)
        assertEquals("9223372036854775806", bigJson.getValue("max").jsonPrimitive.content)
        assertFalse(bigJson.getValue("max").jsonPrimitive.isString, "numbers stay numbers")
        assertEquals("9223372036854775807", bigJson.getValue("actual").jsonPrimitive.content)
    }

    @Test
    fun `S4 a ConfigInvalid detail uses errors and an InvalidRequest detail uses fields`() {
        val config = EnvelopeEncoder.detail(ErrorDetail.ConfigInvalid(listOf(ConfigViolation("a", "r1"), ConfigViolation("b", "r2"))))
        assertEquals(listOf("errors"), keys(config))
        assertEquals(2, (config.getValue("errors") as JsonArray).size)

        val invalid = EnvelopeEncoder.detail(ErrorDetail.InvalidRequest(listOf(FieldViolation("a", "r1"), FieldViolation("b", "r2"))))
        assertEquals(listOf("fields"), keys(invalid))
        assertEquals(2, (invalid.getValue("fields") as JsonArray).size)
    }

    @Test
    fun `S4 no encoded error of any fixture contains a JSON null`() {
        for (code in ErrorCode.entries) {
            assertNoJsonNull(EnvelopeEncoder.error(err(code), reqId).body, code.wire)
        }
    }

    // ---------------------------------------------------------------- S5 internal

    @Test
    fun `S5 internal is the exact fixed bytes with status 500`() {
        val envelope = EnvelopeEncoder.internal(reqId)

        assertEquals(
            """{"ok":false,"error":{"code":"internal","kind":"transient","message":"Internal server error"},"meta":{"reqId":"k7f3q9ab"}}""",
            envelope.body.toString()
        )
        assertEquals(500, envelope.httpStatus)
        assertEquals("Internal server error", EnvelopeEncoder.INTERNAL_MESSAGE)
        assertWellFormed(envelope, "internal")
    }

    @Test
    fun `S5 internal carries no detail, no fix and no retryAfterMs`() {
        val error = EnvelopeEncoder.internal(reqId).body.member("error")
        assertEquals(listOf("code", "kind", "message"), keys(error))
    }

    @Test
    fun `S5 internal differs between calls only in the reqId`() {
        val a = EnvelopeEncoder.internal("aaaaaaaa").body.toString()
        val b = EnvelopeEncoder.internal("bbbbbbbb").body.toString()
        assertEquals(a.replace("aaaaaaaa", "bbbbbbbb"), b)
    }

    // ---------------------------------------------------------------- S6 pages

    private fun pageBody(envelope: EncodedEnvelope): JsonObject = envelope.body.member("data")

    @Test
    fun `S6 a truncated page has items, total, truncated and next`() {
        val envelope =
            EnvelopeEncoder.page(
                ListPage(listOf("a", "b"), total = 143, truncated = true, next = "c"),
                reqId
            ) { JsonPrimitive(it) }

        assertEquals(
            """{"ok":true,"data":{"items":["a","b"],"page":{"total":143,"truncated":true,"next":"c"}},"meta":{"reqId":"k7f3q9ab"}}""",
            envelope.body.toString()
        )
        assertEquals(200, envelope.httpStatus)
        assertWellFormed(envelope, "truncated page")
    }

    @Test
    fun `S6 a complete page has no next and no capped, and the item mapper runs per item`() {
        val envelope = EnvelopeEncoder.page(ListPage(listOf(1, 2), total = 2, truncated = false), reqId) { JsonPrimitive(it * 10) }

        assertEquals("""{"total":2,"truncated":false}""", pageBody(envelope).member("page").toString())
        assertEquals(JsonArray(listOf(JsonPrimitive(10), JsonPrimitive(20))), pageBody(envelope)["items"])
    }

    @Test
    fun `S6 capped true is emitted between truncated and next, capped false is absent`() {
        val capped =
            EnvelopeEncoder.page(ListPage(listOf("a"), total = 2, truncated = true, capped = true, next = "n"), reqId) {
                JsonPrimitive(it)
            }
        assertEquals("""{"total":2,"truncated":true,"capped":true,"next":"n"}""", pageBody(capped).member("page").toString())
        assertWellFormed(capped, "capped page")

        val notCapped =
            EnvelopeEncoder.page(ListPage(listOf("a"), total = 2, truncated = true, capped = false, next = "n"), reqId) {
                JsonPrimitive(it)
            }
        assertFalse("capped" in pageBody(notCapped).member("page"), "capped=false is omitted")

        val cappedComplete =
            EnvelopeEncoder.page(
                ListPage(listOf("a"), total = 1, truncated = false, capped = true),
                reqId
            ) { JsonPrimitive(it) }
        assertEquals("""{"total":1,"truncated":false,"capped":true}""", pageBody(cappedComplete).member("page").toString())
    }

    @Test
    fun `S6 an empty result is ok with an empty items array and no next`() {
        val envelope = EnvelopeEncoder.page(ListPage(emptyList<String>(), total = 0, truncated = false), reqId) { JsonPrimitive(it) }

        assertEquals(
            """{"ok":true,"data":{"items":[],"page":{"total":0,"truncated":false}},"meta":{"reqId":"k7f3q9ab"}}""",
            envelope.body.toString()
        )
        assertWellFormed(envelope, "empty page")
    }

    @Test
    fun `S6 items may be objects`() {
        val envelope =
            EnvelopeEncoder.page(
                ListPage(listOf("x"), total = 1, truncated = false),
                reqId
            ) { buildJsonObject { put("id", it) } }

        assertEquals(obj("""{"id":"x"}"""), (pageBody(envelope).getValue("items") as JsonArray)[0])
    }

    // ---------------------------------------------------------------- S7 / S8 / S9 batches

    private val notFound = err(ErrorCode.NOT_FOUND)

    private fun elementOf(value: String): JsonObject = buildJsonObject { put("id", value) }

    @Test
    fun `S7 an all-ok batch is ok with results in order, a summary and status 200`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Ok("b"), Outcome.Ok("c"))), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(
            """{"ok":true,"data":{"results":[{"ok":true,"id":"a"},{"ok":true,"id":"b"},{"ok":true,"id":"c"}],"summary":{"ok":3,"failed":0}},"meta":{"reqId":"k7f3q9ab"}}""",
            envelope.body.toString()
        )
        assertEquals(200, envelope.httpStatus)
        assertWellFormed(envelope, "ok batch")
    }

    @Test
    fun `S7 a replayed index marks only that element`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Ok("b"), Outcome.Ok("c"))), setOf(1))
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        val results = envelope.body.member("data").getValue("results") as JsonArray
        assertFalse("replayed" in results[0].jsonObject)
        assertEquals(listOf("ok", "id", "replayed"), keys(results[1].jsonObject))
        assertEquals(JsonPrimitive(true), results[1].jsonObject["replayed"])
        assertFalse("replayed" in results[2].jsonObject)
        assertFalse("replayed" in envelope.body.member("meta"), "batch replay is per element, not whole-call")
        assertWellFormed(envelope, "replayed batch")
    }

    @Test
    fun `S7 an element mapper that emits ok, error or replayed is rejected`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"))), emptySet())
        for (reserved in listOf("ok", "error", "replayed")) {
            assertFailsWith<IllegalArgumentException>("mapper emitting '$reserved'") {
                EnvelopeEncoder.batch(execution, reqId) { buildJsonObject { put(reserved, "x") } }
            }
        }
    }

    @Test
    fun `S8 a non-atomic batch with a failure is partial_failure 207 carrying both error and data results`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(notFound), Outcome.Ok("c"))), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(207, envelope.httpStatus)
        assertEquals(listOf("ok", "error", "data", "meta"), keys(envelope.body))
        assertEquals(JsonPrimitive(false), envelope.body.getValue("ok"))

        val top = envelope.body.member("error")
        assertEquals("partial_failure", top.getValue("code").jsonPrimitive.content)
        assertEquals("permanent", top.getValue("kind").jsonPrimitive.content)
        assertTrue(
            top
                .getValue("message")
                .jsonPrimitive.content
                .isNotBlank()
        )
        assertEquals(listOf("code", "kind", "message"), keys(top), "no fix, no detail, no index on partial_failure")

        val data = envelope.body.member("data")
        assertEquals(listOf("results", "summary"), keys(data))
        assertEquals(obj("""{"ok":2,"failed":1}"""), data.member("summary"))
        val results = data.getValue("results") as JsonArray
        assertEquals(3, results.size, "positions are never compacted")
        assertEquals(obj("""{"ok":true,"id":"a"}"""), results[0])
        assertEquals(obj("""{"ok":true,"id":"c"}"""), results[2])

        val failed = results[1].jsonObject
        assertEquals(listOf("ok", "error"), keys(failed))
        assertEquals(EnvelopeEncoder.errorMember(notFound), failed.member("error"))
        assertFalse("index" in failed.member("error"), "per-element errors carry no index")
        assertEquals(
            "not_found",
            failed
                .member("error")
                .getValue("code")
                .jsonPrimitive.content
        )
        assertEquals(obj("""{"kind":"item","id":"$i1"}"""), failed.member("error").member("detail"))
        assertEquals(
            notFound.fix,
            failed
                .member("error")
                .getValue("fix")
                .jsonPrimitive.content
        )

        assertWellFormed(envelope, "partial failure")
    }

    @Test
    fun `S8 every element failing is still partial_failure with all positions as errors`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Err(notFound), Outcome.Err(err(ErrorCode.GATE_BLOCKED)))), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(207, envelope.httpStatus)
        assertEquals(obj("""{"ok":0,"failed":2}"""), envelope.body.member("data").member("summary"))
        val results = envelope.body.member("data").getValue("results") as JsonArray
        assertEquals(
            "not_found",
            results[0]
                .jsonObject
                .member("error")
                .getValue("code")
                .jsonPrimitive.content
        )
        assertEquals(
            "gate_blocked",
            results[1]
                .jsonObject
                .member("error")
                .getValue("code")
                .jsonPrimitive.content
        )
        assertWellFormed(envelope, "all-failed batch")
    }

    @Test
    fun `S8 a replayed element in a partial failure keeps its marker`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(notFound))), setOf(0))
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        val results = envelope.body.member("data").getValue("results") as JsonArray
        assertEquals(JsonPrimitive(true), results[0].jsonObject["replayed"])
        assertFalse("replayed" in results[1].jsonObject)
    }

    @Test
    fun `S9 an atomic failure is the failing element's error with its index, no data, and that code's status`() {
        val failure = err(ErrorCode.GATE_BLOCKED)
        val execution =
            BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(failure), Outcome.Err(failure)), atomic = true), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(409, envelope.httpStatus)
        assertEquals(listOf("ok", "error", "meta"), keys(envelope.body))
        assertFalse("data" in envelope.body, "an atomic failure never emits per-element results")
        val error = envelope.body.member("error")
        assertEquals(listOf("code", "kind", "message", "detail", "fix", "index"), keys(error))
        assertEquals("gate_blocked", error.getValue("code").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), error["index"])
        assertFalse("index" in error.member("detail"), "index is error-level, never inside detail")
        assertEquals(EnvelopeEncoder.error(failure, reqId, index = 1).body, envelope.body)
        assertWellFormed(envelope, "atomic failure")
    }

    @Test
    fun `S9 an atomic failure at index 0 carries index 0, and another code gives its own status`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Err(notFound), Outcome.Err(notFound)), atomic = true), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(404, envelope.httpStatus)
        assertEquals(JsonPrimitive(0), envelope.body.member("error")["index"])
        assertFalse("data" in envelope.body)
    }

    @Test
    fun `S9 an atomic batch that succeeded is a normal ok batch`() {
        val execution = BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Ok("b")), atomic = true), emptySet())
        val envelope = EnvelopeEncoder.batch(execution, reqId, ::elementOf)

        assertEquals(200, envelope.httpStatus)
        assertEquals(JsonPrimitive(true), envelope.body.getValue("ok"))
        assertEquals(obj("""{"ok":2,"failed":0}"""), envelope.body.member("data").member("summary"))
        assertNull(envelope.body["error"])
    }

    // ---------------------------------------------------------------- envelope section 6

    @Test
    fun `S1 no encoder output ever carries a top-level success, message or metadata key`() {
        val outputs =
            listOf(
                EnvelopeEncoder.outcome(Outcome.Ok(1), reqId) { JsonPrimitive(1) },
                EnvelopeEncoder.outcome(Outcome.Ok(1), reqId) { null },
                EnvelopeEncoder.error(notFound, reqId),
                EnvelopeEncoder.internal(reqId),
                EnvelopeEncoder.page(ListPage(listOf("a"), total = 1, truncated = false), reqId) { JsonPrimitive(it) },
                EnvelopeEncoder.batch(BatchExecution(BatchResult(listOf(Outcome.Ok("a"))), emptySet()), reqId, ::elementOf),
                EnvelopeEncoder.batch(BatchExecution(BatchResult(listOf(Outcome.Err(notFound))), emptySet()), reqId, ::elementOf)
            )
        for (envelope in outputs) {
            for (forbidden in listOf("success", "message", "metadata")) {
                assertFalse(forbidden in envelope.body, "forbidden top-level '$forbidden' in ${envelope.body}")
            }
        }
    }
}

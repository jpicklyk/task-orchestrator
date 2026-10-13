package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.DependencyCommandService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ITEM_A = "0f8fad5b-d9cb-469f-a165-70867728950e"
private const val ITEM_B = "6ba7b810-9dad-41d1-80b4-00c04fd430c8"
private const val DOC_C = "c9bf9e57-1685-4c89-bafb-ff5af830be8a"

/**
 * Independent tests (test-author seat, item df10fafb) for [CallLogFields.lifecycleFreeRejections], scenario S11 of the frozen
 * test-plan and its probes, on hand-built envelope bodies.
 *
 * Oracle: the frozen task-scope item 1. The count is taken from the encoded envelope: when `data.results` is an array, each
 * element with `ok:false` is one candidate; otherwise the top-level `error` is the one candidate (never both). A candidate counts
 * when `code == invalid_transition` and `detail.fromRole == "none"`, or `code == invalid_request` with a `detail.fields[].reason`
 * equal to [DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON]. The result is null when nothing counts, never 0. The body
 * shapes follow api-reference.md "Batch answers" (`results[i]` is `{ok:true,...}` or `{ok:false, error}`) and the
 * `invalid_transition` / `invalid_request` details of the error catalog (`{itemId, fromRole, trigger, allowed}`; `fields[]` of
 * `{field, reason, received?}`).
 *
 * Forbidden-construct declaration: none.
 */
class CallLogFieldsLifecycleFreeTest {
    private fun error(
        code: String,
        detail: JsonObject? = null
    ): JsonObject =
        buildJsonObject {
            put("code", code)
            put("message", "probe")
            if (detail != null) put("detail", detail)
        }

    private fun transitionDetail(fromRole: String): JsonObject =
        buildJsonObject {
            put("itemId", ITEM_A)
            put("fromRole", fromRole)
            put("trigger", "start")
            putJsonArray("allowed") {}
        }

    private fun fieldsDetail(vararg reasons: String): JsonObject =
        buildJsonObject {
            putJsonArray("fields") {
                reasons.forEachIndexed { i, reason ->
                    add(
                        buildJsonObject {
                            put("field", "/ops/0/to$i")
                            put("reason", reason)
                            put("received", ITEM_B)
                        }
                    )
                }
            }
        }

    private fun lfTransition(): JsonObject = error("invalid_transition", transitionDetail("none"))

    private fun roleBearing(role: String = "terminal"): JsonObject = error("invalid_transition", transitionDetail(role))

    private fun blocksEndpoint(): JsonObject =
        error("invalid_request", fieldsDetail(DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON))

    private fun failed(error: JsonObject): JsonObject =
        buildJsonObject {
            put("ok", false)
            put("error", error)
        }

    private fun succeeded(): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("id", ITEM_B)
        }

    private fun batch(
        results: List<JsonElement>,
        top: JsonObject? = null
    ): JsonObject =
        buildJsonObject {
            put("ok", top == null && results.all { it.jsonObject["ok"]?.jsonPrimitive?.content == "true" })
            putJsonObject("data") {
                put("results", JsonArray(results))
                putJsonObject("summary") {
                    put("ok", results.count { it.jsonObject["ok"]?.jsonPrimitive?.content == "true" })
                    put("failed", results.count { it.jsonObject["ok"]?.jsonPrimitive?.content == "false" })
                }
            }
            if (top != null) put("error", top)
        }

    private fun single(error: JsonObject): JsonObject =
        buildJsonObject {
            put("ok", false)
            put("error", error)
        }

    private fun count(body: JsonObject?): Int? = CallLogFields.lifecycleFreeRejections(body)

    @Test
    fun `S11 the shared reason constant holds the documented text`() {
        assertEquals(
            "a lifecycle-free item cannot be an endpoint of a BLOCKS dependency; use RELATES_TO",
            DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON
        )
    }

    @Test
    fun `S11 a null body counts nothing`() {
        assertNull(count(null))
    }

    @Test
    fun `S11 an ok body without errors is null and never zero`() {
        assertNull(count(buildJsonObject { put("ok", true) }))
        assertNull(count(batch(listOf(succeeded(), succeeded()))), "all elements ok")
        assertNull(
            count(
                buildJsonObject {
                    put("ok", true)
                    putJsonObject("data") { put("items", JsonArray(emptyList())) }
                }
            ),
            "an ok list answer has no results array and no error"
        )
    }

    @Test
    fun `S11 a top-level invalid_transition from none counts one`() {
        assertEquals(1, count(single(lfTransition())))
    }

    @Test
    fun `S11 an atomic failure carries only the failing element's error with its index and still counts one`() {
        val atomic =
            error("invalid_transition", transitionDetail("none")).let {
                JsonObject(it + ("index" to JsonPrimitive(1)))
            }
        assertEquals(1, count(single(atomic)))
    }

    @Test
    fun `S11 a top-level invalid_transition from a role-bearing role is not counted`() {
        for (role in listOf("queue", "work", "review", "terminal", "blocked")) {
            assertNull(count(single(roleBearing(role))), "fromRole $role is an ordinary rejection")
        }
    }

    @Test
    fun `S11 probe - fromRole must be exactly the lowercase word none`() {
        for (variant in listOf("None", "NONE", " none", "none ", "")) {
            assertNull(count(single(error("invalid_transition", transitionDetail(variant)))), "fromRole '$variant'")
        }
    }

    @Test
    fun `S11 probe - a none fromRole under another error code is not counted`() {
        assertNull(count(single(error("not_found", transitionDetail("none")))))
        assertNull(count(single(error("invalid_request", transitionDetail("none")))))
        assertNull(count(single(error("gate_blocked", transitionDetail("none")))))
    }

    @Test
    fun `S11 probe - an invalid_transition without a detail or with an unrelated detail is not counted and does not throw`() {
        assertNull(count(single(error("invalid_transition"))))
        assertNull(count(single(error("invalid_transition", buildJsonObject { put("itemId", ITEM_A) }))))
        assertNull(count(single(error("invalid_transition", buildJsonObject { put("fromRole", JsonNull) }))))
    }

    @Test
    fun `S11 an invalid_request whose field reason is the BLOCKS endpoint text counts one`() {
        assertEquals(1, count(single(blocksEndpoint())))
    }

    @Test
    fun `S11 an invalid_request with another reason is not counted`() {
        assertNull(count(single(error("invalid_request", fieldsDetail("must not be blank")))))
        assertNull(count(single(error("invalid_request", fieldsDetail("a cycle would be created")))))
    }

    @Test
    fun `S11 probe - the reason is matched exactly, not by prefix, suffix or case`() {
        val text = DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON
        for (variant in listOf(text + " ", " $text", text + ".", text.uppercase(), text.substring(0, text.length - 1))) {
            assertNull(count(single(error("invalid_request", fieldsDetail(variant)))), "reason '$variant'")
        }
    }

    @Test
    fun `S11 any field of the detail may carry the reason and one rejection counts once however many fields repeat it`() {
        val text = DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON
        assertEquals(1, count(single(error("invalid_request", fieldsDetail("must not be blank", text)))), "second field matches")
        assertEquals(1, count(single(error("invalid_request", fieldsDetail(text, text)))), "two fields, one rejection")
    }

    @Test
    fun `S11 the lifecycle-free reason under another code is not counted`() {
        val text = DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON
        assertNull(count(single(error("invalid_transition", fieldsDetail(text)))))
        assertNull(count(single(error("schema_violation", fieldsDetail(text)))))
    }

    @Test
    fun `S11 a non-atomic batch counts each qualifying failed element and excludes role-bearing ones and successes`() {
        val body =
            batch(
                listOf(
                    failed(lfTransition()),
                    failed(error("invalid_transition", transitionDetail("none"))),
                    succeeded(),
                    failed(roleBearing("terminal")),
                    failed(error("not_found"))
                ),
                top = error("partial_failure")
            )
        assertEquals(2, count(body))
    }

    @Test
    fun `S11 a batch mixes both qualifying shapes`() {
        assertEquals(2, count(batch(listOf(failed(lfTransition()), failed(blocksEndpoint()), succeeded()), top = error("partial_failure"))))
        val three = listOf(failed(lfTransition()), failed(lfTransition()), failed(blocksEndpoint()))
        assertEquals(3, count(batch(three, top = error("partial_failure"))))
    }

    @Test
    fun `S11 a top-level error that duplicates the first failure is not counted again when results are present`() {
        // The non-atomic batch answers partial_failure next to the full data; a top-level error may repeat the first failure.
        val body = batch(listOf(failed(lfTransition()), succeeded()), top = lfTransition())
        assertEquals(1, count(body), "one element failed for a lifecycle-free item; the top-level copy is not a second rejection")
    }

    @Test
    fun `S11 results with nothing qualifying are null even when the top-level error would qualify`() {
        val body = batch(listOf(failed(roleBearing("terminal")), succeeded()), top = lfTransition())
        assertNull(count(body), "when data.results is an array only its elements are judged")
    }

    @Test
    fun `S11 a top-level error is judged when data holds no results array`() {
        val body =
            buildJsonObject {
                put("ok", false)
                putJsonObject("data") { put("items", JsonArray(emptyList())) }
                put("error", lfTransition())
            }
        assertEquals(1, count(body))
    }

    @Test
    fun `S11 a results member that is not an array is not read as the batch`() {
        val body =
            buildJsonObject {
                put("ok", false)
                putJsonObject("data") { put("results", "not an array") }
                put("error", lfTransition())
            }
        assertEquals(1, count(body), "no results array: the top-level error is the candidate")
    }

    @Test
    fun `S11 an ok element is never counted even when a sibling failed for a lifecycle-free item`() {
        assertEquals(1, count(batch(listOf(succeeded(), failed(lfTransition()), succeeded()), top = error("partial_failure"))))
    }

    @Test
    fun `S11 the count is a pure function of the body`() {
        val body = batch(listOf(failed(lfTransition()), failed(blocksEndpoint())), top = error("partial_failure"))
        assertEquals(count(body), count(body))
        val reparsed = Json.parseToJsonElement(body.toString()).jsonObject
        assertEquals(count(body), count(reparsed), "an encoded and re-parsed body counts the same")
    }
}

/**
 * Independent tests (test-author seat, item df10fafb) for [CallLogFields.hitSignalsJson] and for how [CallLogFields.record]
 * carries the two new columns.
 *
 * Oracle: the frozen task-scope item 1: `hit_signals` is a JSON array in page order of `{"id":"<canonical uuid>","kind":"item" or
 * "document","signals":[...]}`, NULL for a related call whose page is empty and for every other call, and it never carries a title
 * or any text. The `record` columns are the frozen task-scope item 2 (`hitSignals` filled from telemetry; `lifecycleFreeRejections`
 * is the parameter, default null).
 *
 * Forbidden-construct declaration: none.
 */
class CallLogFieldsHitSignalsTest {
    private fun hit(
        id: String,
        kind: String,
        vararg signals: String
    ) = CallTelemetry.HitSignals(UUID.fromString(id), kind, signals.toList())

    private fun parse(json: String?): JsonArray =
        Json.parseToJsonElement(checkNotNull(json) { "expected a JSON array, got null" }).jsonArray

    @Test
    fun `S1 a null hit list is null`() {
        assertNull(CallLogFields.hitSignalsJson(null))
    }

    @Test
    fun `S3 an empty hit list is null and never the text of an empty array`() {
        assertNull(CallLogFields.hitSignalsJson(emptyList()))
    }

    @Test
    fun `S1 one hit becomes a one element array with id kind and signals`() {
        val json = CallLogFields.hitSignalsJson(listOf(hit(ITEM_A, "item", "edges", "text")))
        val expected =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", ITEM_A)
                        put("kind", "item")
                        putJsonArray("signals") {
                            add(JsonPrimitive("edges"))
                            add(JsonPrimitive("text"))
                        }
                    }
                )
            }
        assertEquals(expected, parse(json))
    }

    @Test
    fun `S1 hits keep the page order and each hit keeps its signal order`() {
        val json =
            CallLogFields.hitSignalsJson(
                listOf(
                    hit(DOC_C, "document", "links"),
                    hit(ITEM_B, "item", "edges", "links", "tags", "text"),
                    hit(ITEM_A, "item", "tags", "text")
                )
            )
        val array = parse(json)
        assertEquals(
            listOf(DOC_C, ITEM_B, ITEM_A),
            array.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }
        )
        assertEquals(
            listOf("document", "item", "item"),
            array.map {
                it.jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content
            }
        )
        assertEquals(
            listOf(listOf("links"), listOf("edges", "links", "tags", "text"), listOf("tags", "text")),
            array.map { e ->
                e.jsonObject
                    .getValue("signals")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            }
        )
    }

    @Test
    fun `S1 the id is the canonical lowercase UUID text`() {
        val upper = UUID.fromString(ITEM_A.uppercase())
        val json = CallLogFields.hitSignalsJson(listOf(CallTelemetry.HitSignals(upper, "item", listOf("edges"))))
        assertEquals(
            ITEM_A,
            parse(json)
                .single()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        )
        assertEquals(ITEM_A, ITEM_A.lowercase(), "fixture: the expected text is already lowercase")
    }

    @Test
    fun `S5 each element has exactly the keys id kind and signals and nothing else`() {
        val json = CallLogFields.hitSignalsJson(listOf(hit(ITEM_A, "item", "edges"), hit(DOC_C, "document", "text")))
        for (element in parse(json)) {
            assertEquals(setOf("id", "kind", "signals"), element.jsonObject.keys, "no title, slug or text member")
        }
    }

    @Test
    fun `S1 the same hits produce the same text`() {
        val hits = listOf(hit(ITEM_A, "item", "edges"), hit(DOC_C, "document", "links", "text"))
        assertEquals(CallLogFields.hitSignalsJson(hits), CallLogFields.hitSignalsJson(hits.toList()))
    }

    // ------------------------------------------------------------------ record()

    private fun recordOf(
        telemetry: CallTelemetry,
        lifecycleFreeRejections: Int? = null,
        useDefault: Boolean = false
    ): CallLogRecord =
        if (useDefault) {
            CallLogFields.record(
                telemetry = telemetry,
                at = Instant.parse("2026-03-04T05:06:07.089Z"),
                tool = "items_read",
                operation = "related",
                targetIds = null,
                requestShape = null,
                isError = false,
                errorCode = null,
                latencyMs = 5,
                requestBytes = 10,
                responseBytes = 100,
                batchSize = null,
                failedCount = null,
                resultCount = null,
                eligibleCount = null
            )
        } else {
            CallLogFields.record(
                telemetry = telemetry,
                at = Instant.parse("2026-03-04T05:06:07.089Z"),
                tool = "advance",
                operation = "start",
                targetIds = null,
                requestShape = null,
                isError = true,
                errorCode = "invalid_transition",
                latencyMs = 5,
                requestBytes = 10,
                responseBytes = 100,
                batchSize = 1,
                failedCount = 1,
                resultCount = null,
                eligibleCount = null,
                lifecycleFreeRejections = lifecycleFreeRejections
            )
        }

    @Test
    fun `S1 record takes hitSignals from the call's telemetry`() {
        val t = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = "s1")
        val hits = listOf(hit(ITEM_A, "item", "edges"), hit(DOC_C, "document", "links"))
        t.setHitSignals(hits)

        val record = recordOf(t, useDefault = true)

        assertEquals(parse(CallLogFields.hitSignalsJson(hits)), parse(record.hitSignals))
        assertNull(record.lifecycleFreeRejections, "the parameter defaults to null")
    }

    @Test
    fun `S3 record without recorded hits has a null hitSignals`() {
        val t = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = "s1")
        assertNull(recordOf(t, useDefault = true).hitSignals)
    }

    @Test
    fun `S6 record carries the lifecycleFreeRejections it is given`() {
        val t = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = "s1")
        assertEquals(3, recordOf(t, lifecycleFreeRejections = 3).lifecycleFreeRejections)
        assertNull(recordOf(t, lifecycleFreeRejections = null).lifecycleFreeRejections)
        assertNull(recordOf(t, lifecycleFreeRejections = 3).hitSignals, "a non-related call has no hit signals")
    }
}

/**
 * Independent tests (test-author seat, item df10fafb) for the hit-signals member of [CallTelemetry].
 *
 * Oracle: the frozen task-scope item 2: `val hitSignals: List<HitSignals>?`, `setHitSignals` (first wins) and
 * `suspend fun recordCallHitSignals(...)`, the same pattern as the existing related-signals counts (a recording made outside any
 * call is harmless).
 *
 * Forbidden-construct declaration: none.
 */
class CallTelemetryHitSignalsTest {
    private fun telemetry() = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = null)

    private fun hits(vararg ids: String) = ids.map { CallTelemetry.HitSignals(UUID.fromString(it), "item", listOf("edges")) }

    @Test
    fun `S1 hitSignals starts null`() {
        assertNull(telemetry().hitSignals)
    }

    @Test
    fun `S1 setHitSignals stores the list and the first one wins`() {
        val t = telemetry()
        val first = hits(ITEM_A, ITEM_B)
        t.setHitSignals(first)
        assertEquals(first, t.hitSignals)

        t.setHitSignals(hits(DOC_C))

        assertEquals(first, t.hitSignals, "a second set does not replace the first")
    }

    @Test
    fun `S1 recordCallHitSignals inside a call context reaches that call's telemetry`() {
        val t = telemetry()
        val recorded = hits(ITEM_A)

        runBlocking { withContext(t) { recordCallHitSignals(recorded) } }

        assertEquals(recorded, t.hitSignals)
    }

    @Test
    fun `S1 recordCallHitSignals outside any call does nothing and does not throw`() {
        val t = telemetry()

        runBlocking { recordCallHitSignals(hits(ITEM_A)) }

        assertNull(t.hitSignals, "an unrelated telemetry object is untouched")
    }

    @Test
    fun `S1 two calls keep their hit signals apart`() {
        val a = telemetry()
        val b = CallTelemetry(reqId = "abcd2345", surface = CallLogRecord.SURFACE_REST, sessionId = null)

        runBlocking {
            withContext(a) { recordCallHitSignals(hits(ITEM_A)) }
            withContext(b) { recordCallHitSignals(hits(ITEM_B)) }
        }

        assertEquals(hits(ITEM_A), a.hitSignals)
        assertEquals(hits(ITEM_B), b.hitSignals)
    }
}

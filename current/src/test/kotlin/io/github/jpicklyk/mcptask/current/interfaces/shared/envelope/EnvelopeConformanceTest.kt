package io.github.jpicklyk.mcptask.current.interfaces.shared.envelope

import io.github.jpicklyk.mcptask.current.application.api.BatchExecution
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.domain.error.BatchResult
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for the envelope conformance harness
 * (`test/EnvelopeConformance.kt`) and for the claim that every [EnvelopeEncoder] output passes it (acceptance criterion 1).
 *
 * Oracle: `plans/v4-response-envelope.md` section 2 examples (copied from the document as literals) and section 6 (the keys
 * `success`, `message` and `metadata` are forbidden at the top level). The checker is itself under test: every negative case
 * is one mutation of a positive example that the same test first proves conforms, so a rejection is attributable to the
 * mutation and never to a broken base.
 */
class EnvelopeConformanceTest {
    private val reqId = "k7f3q9ab"

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val specSuccess = """{"ok":true,"data":{"id":"x"},"meta":{"reqId":"k7f3q9ab"}}"""
    private val specList =
        """{"ok":true,"data":{"items":[1,2],"page":{"total":143,"truncated":true,"next":"eyJvZmZzZXQiOjUwfQ"}},"meta":{"reqId":"k7f3q9ab"}}"""
    private val specError =
        """{"ok":false,"error":{"code":"gate_blocked","kind":"permanent","message":"Item 08a42ea3 cannot leave queue.","detail":{"itemId":"08a42ea3"},"fix":"Fill the missing notes, then retry."},"meta":{"reqId":"k7f3q9ab"}}"""
    private val specBatch =
        """{"ok":true,"data":{"results":[{"ok":true,"id":"a"},{"ok":true,"id":"b","replayed":true}],"summary":{"ok":2,"failed":0}},"meta":{"reqId":"k7f3q9ab"}}"""
    private val specPartial =
        """{"ok":false,"error":{"code":"partial_failure","kind":"permanent","message":"1 of 2 failed."},"data":{"results":[{"ok":true,"id":"a"},{"ok":false,"error":{"code":"not_found","kind":"permanent","message":"No such item.","fix":"Check the id."}}],"summary":{"ok":1,"failed":1}},"meta":{"reqId":"k7f3q9ab"}}"""
    private val specAtomic =
        """{"ok":false,"error":{"code":"not_found","kind":"permanent","message":"No such item.","fix":"Check the id.","index":1},"meta":{"reqId":"k7f3q9ab"}}"""

    private val positives =
        mapOf(
            "success" to specSuccess,
            "list" to specList,
            "error" to specError,
            "batch" to specBatch,
            "partial" to specPartial,
            "atomic" to specAtomic
        )

    @Test
    fun `the spec examples conform`() {
        for ((name, text) in positives) {
            assertEquals(emptyList(), EnvelopeConformance.violations(parse(text)), "spec example '$name'")
        }
    }

    private fun assertRejected(
        label: String,
        base: String,
        mutate: (String) -> String
    ) {
        assertEquals(emptyList(), EnvelopeConformance.violations(parse(base)), "$label: the unmutated base must conform")
        val mutated = mutate(base)
        assertTrue(mutated != base, "$label: the mutation must change the document")
        assertTrue(EnvelopeConformance.violations(parse(mutated)).isNotEmpty(), "$label: the mutated document must be rejected: $mutated")
    }

    @Test
    fun `a forbidden top-level success, message or metadata key is rejected`() {
        assertRejected("success key", specSuccess) { it.replaceFirst("{\"ok\":true,", "{\"ok\":true,\"success\":true,") }
        assertRejected("message key", specSuccess) { it.replaceFirst("{\"ok\":true,", "{\"ok\":true,\"message\":\"hi\",") }
        assertRejected("metadata key", specSuccess) { it.replaceFirst("{\"ok\":true,", "{\"ok\":true,\"metadata\":{},") }
    }

    @Test
    fun `an unknown top-level key, a non-boolean ok and a missing meta are rejected`() {
        assertRejected("unknown key", specSuccess) { it.replaceFirst("{\"ok\":true,", "{\"ok\":true,\"extra\":1,") }
        assertRejected("ok as string", specSuccess) { it.replace("\"ok\":true,\"data\"", "\"ok\":\"true\",\"data\"") }
        assertRejected("meta missing", specSuccess) { it.replace(",\"meta\":{\"reqId\":\"k7f3q9ab\"}", "") }
        assertRejected("reqId missing", specSuccess) { it.replace("\"reqId\":\"k7f3q9ab\"", "\"other\":\"k7f3q9ab\"") }
        assertRejected("replayed false", specSuccess) { it.replace("\"reqId\":\"k7f3q9ab\"", "\"reqId\":\"k7f3q9ab\",\"replayed\":false") }
    }

    @Test
    fun `a null data, an ok true with an error and an ok false without an error are rejected`() {
        assertRejected("null data", specSuccess) { it.replace("\"data\":{\"id\":\"x\"}", "\"data\":null") }
        assertRejected("ok true with error", specSuccess) {
            it.replace("\"data\"", "\"error\":{\"code\":\"x\",\"kind\":\"permanent\",\"message\":\"m\",\"fix\":\"f\"},\"data\"")
        }
        assertRejected("ok false without error", specError) { it.replace("\"ok\":false", "\"ok\":true") }
    }

    @Test
    fun `an error with a bad kind, a blank message, a missing fix on a permanent error or a null detail is rejected`() {
        assertRejected("bad kind", specError) { it.replace("\"kind\":\"permanent\"", "\"kind\":\"fatal\"") }
        assertRejected("blank message", specError) { it.replace("\"message\":\"Item 08a42ea3 cannot leave queue.\"", "\"message\":\"\"") }
        assertRejected("permanent without fix", specError) { it.replace(",\"fix\":\"Fill the missing notes, then retry.\"", "") }
        assertRejected("detail as array", specError) { it.replace("\"detail\":{\"itemId\":\"08a42ea3\"}", "\"detail\":[]") }
        assertRejected("uppercase code", specError) { it.replace("\"gate_blocked\"", "\"GATE_BLOCKED\"") }
        assertRejected("unknown error key", specError) { it.replace("\"kind\":\"permanent\"", "\"kind\":\"permanent\",\"hint\":\"h\"") }
    }

    @Test
    fun `a list page that breaks the truncated, next or capped rules is rejected`() {
        assertRejected("truncated mismatch", specList) { it.replace("\"truncated\":true", "\"truncated\":false") }
        assertRejected("next missing while truncated", specList) { it.replace(",\"next\":\"eyJvZmZzZXQiOjUwfQ\"", "") }
        assertRejected("capped false", specList) { it.replace("\"truncated\":true", "\"truncated\":true,\"capped\":false") }
        assertRejected("negative total", specList) { it.replace("\"total\":143", "\"total\":-1") }
        assertRejected(
            "page missing",
            specList
        ) { it.replace(",\"page\":{\"total\":143,\"truncated\":true,\"next\":\"eyJvZmZzZXQiOjUwfQ\"}", "") }
    }

    @Test
    fun `a batch whose summary or entries are inconsistent is rejected`() {
        assertRejected(
            "summary mismatch",
            specBatch
        ) { it.replace("\"summary\":{\"ok\":2,\"failed\":0}", "\"summary\":{\"ok\":1,\"failed\":1}") }
        assertRejected("ok batch with a failed entry", specBatch) {
            it.replace(
                "{\"ok\":true,\"id\":\"a\"}",
                "{\"ok\":false,\"error\":{\"code\":\"x\",\"kind\":\"permanent\",\"message\":\"m\",\"fix\":\"f\"}}"
            )
        }
        assertRejected("replayed false on an element", specBatch) { it.replace("\"replayed\":true", "\"replayed\":false") }
        assertRejected("failed entry without error", specPartial) {
            it.replace(
                ",\"error\":{\"code\":\"not_found\",\"kind\":\"permanent\",\"message\":\"No such item.\",\"fix\":\"Check the id.\"}",
                ""
            )
        }
        assertRejected("index on an element error", specPartial) {
            it.replace("\"fix\":\"Check the id.\"", "\"fix\":\"Check the id.\",\"index\":1")
        }
    }

    @Test
    fun `partial_failure must carry data, no fix and kind permanent, and an atomic failure must carry no data`() {
        assertRejected("partial without data", specPartial) { it.replace(Regex(",\"data\":\\{.*\\},\"meta\""), ",\"meta\"") }
        assertRejected("partial with fix", specPartial) {
            it.replace("\"message\":\"1 of 2 failed.\"", "\"message\":\"1 of 2 failed.\",\"fix\":\"f\"")
        }
        assertRejected("partial transient", specPartial) { it.replaceFirst("\"kind\":\"permanent\"", "\"kind\":\"transient\"") }
        assertRejected("atomic with data", specAtomic) { it.replace(",\"meta\"", ",\"data\":{\"results\":[]},\"meta\"") }
        assertRejected("non-partial error with data", specError) { it.replace(",\"meta\"", ",\"data\":{\"id\":\"x\"},\"meta\"") }
    }

    @Test
    fun `a non-object body is rejected`() {
        assertTrue(EnvelopeConformance.violations(Json.parseToJsonElement("[1]")).isNotEmpty())
        assertTrue(EnvelopeConformance.violations(JsonPrimitive("ok")).isNotEmpty())
    }

    // ---------------------------------------------------------------- encoder output passes the harness (acceptance 1)

    private fun conforms(
        envelope: EncodedEnvelope,
        label: String
    ) {
        EnvelopeConformance.assertConforms(envelope.body, label)
    }

    private fun element(value: String): JsonObject = buildJsonObject { put("id", value) }

    @Test
    fun `every encoder output shape passes EnvelopeConformance`() {
        val failure = ErrorFixtures.error(ErrorCode.NOT_FOUND)
        conforms(EnvelopeEncoder.outcome(Outcome.Ok(1), reqId) { JsonPrimitive("d") }, "ok with data")
        conforms(EnvelopeEncoder.outcome(Outcome.Ok(1), reqId) { null }, "ok without data")
        conforms(EnvelopeEncoder.outcome(Outcome.Ok(1), reqId, replayed = true) { JsonPrimitive("d") }, "replayed ok")
        conforms(EnvelopeEncoder.outcome(Outcome.Err(failure), reqId) { null }, "error via outcome")
        conforms(EnvelopeEncoder.internal(reqId), "internal")
        conforms(
            EnvelopeEncoder.page(ListPage(listOf("a"), total = 3, truncated = true, next = "n"), reqId) {
                JsonPrimitive(it)
            },
            "truncated page"
        )
        conforms(
            EnvelopeEncoder.page(ListPage(listOf("a"), total = 3, truncated = true, capped = true, next = "n"), reqId) {
                JsonPrimitive(it)
            },
            "capped page"
        )
        conforms(
            EnvelopeEncoder.page(ListPage(emptyList<String>(), total = 0, truncated = false), reqId) { JsonPrimitive(it) },
            "empty page"
        )
        conforms(
            EnvelopeEncoder.batch(BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Ok("b"))), setOf(1)), reqId, ::element),
            "ok batch"
        )
        conforms(
            EnvelopeEncoder.batch(BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(failure))), emptySet()), reqId, ::element),
            "partial failure"
        )
        conforms(
            EnvelopeEncoder.batch(
                BatchExecution(BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(failure), Outcome.Err(failure)), atomic = true), emptySet()),
                reqId,
                ::element
            ),
            "atomic failure"
        )
    }

    @Test
    fun `every error code encoded as a standalone error passes EnvelopeConformance except partial_failure which needs results`() {
        for (code in ErrorCode.entries) {
            val envelope = EnvelopeEncoder.error(ErrorFixtures.error(code), reqId)
            val violations = EnvelopeConformance.violations(envelope.body)
            if (code == ErrorCode.PARTIAL_FAILURE) {
                assertTrue(violations.isNotEmpty(), "a standalone partial_failure lacks data.results and must be flagged")
            } else {
                assertEquals(emptyList(), violations, code.wire)
            }
        }
    }

    @Test
    fun `the harness agrees with the wire bytes - parsing toString of an encoder output conforms`() {
        val envelope = EnvelopeEncoder.page(ListPage(listOf("a"), total = 2, truncated = true, next = "n"), reqId) { JsonPrimitive(it) }
        val reparsed: JsonElement = Json.parseToJsonElement(envelope.body.toString())
        assertEquals(emptyList(), EnvelopeConformance.violations(reparsed))
    }
}

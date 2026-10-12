package io.github.jpicklyk.mcptask.current.test

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.fail

/**
 * Structural conformance checker for the 4.0 response envelope (test support for `EnvelopeConformanceTest`).
 *
 * Oracle: `plans/v4-response-envelope.md` section 2 (Success, List data, Error, Batch results), section 6 (the keys
 * `success`, `message` and `metadata` are forbidden at the top level) and section 8 (U15 `page.capped`, U18). The checker
 * is deliberately a plain Kotlin walk, not a JSON Schema document; it states every rule once, so a body that breaks a
 * rule yields a named violation.
 */
object EnvelopeConformance {
    private val forbiddenTopLevel = setOf("success", "message", "metadata")
    private val allowedTopLevel = setOf("ok", "data", "error", "meta")
    private val kinds = setOf("transient", "permanent", "shedding")
    private val allowedErrorKeys = setOf("code", "kind", "message", "detail", "fix", "retryAfterMs", "index")
    private val codePattern = Regex("^[a-z][a-z_]*$")

    /**
     * Every violation found in [body]; an empty list means the body conforms. [continuation] marks the answer to a
     * cursor request: `page.total` still counts every item, so a later page has fewer items than the total without being
     * truncated, and only "truncated implies items.length < total" is checked.
     */
    fun violations(
        body: JsonElement,
        continuation: Boolean = false
    ): List<String> {
        val out = mutableListOf<String>()
        if (body !is JsonObject) return listOf("the envelope is not a JSON object")
        checkNoNulls(body, "$", out)
        for (key in forbiddenTopLevel) if (key in body) out += "forbidden top-level key '$key'"
        for (key in body.keys) if (key !in allowedTopLevel && key !in forbiddenTopLevel) out += "unknown top-level key '$key'"

        val ok = body["ok"].asBoolean()
        if (ok == null) out += "'ok' must be a boolean"

        checkMeta(body["meta"], out)

        val data = body["data"]
        val error = body["error"]

        if (ok == true) {
            if (error != null) out += "ok:true must not carry 'error'"
        } else if (ok == false) {
            if (error == null) {
                out += "ok:false requires 'error'"
            } else {
                checkError(error, topLevel = true, out)
            }
        }

        if (data != null) checkData(data, continuation, out)

        if (ok == false && error is JsonObject) {
            val code = (error["code"] as? JsonPrimitive)?.content
            val hasIndex = "index" in error
            if (code == "partial_failure") {
                val results = (data as? JsonObject)?.get("results")
                if (results !is JsonArray) out += "partial_failure requires data.results"
                if (error["kind"].asString() != "permanent") out += "partial_failure must be kind permanent"
                for (forbidden in listOf("fix", "detail", "index")) {
                    if (forbidden in error) out += "partial_failure must not carry '$forbidden'"
                }
            } else if (data != null) {
                out += "only partial_failure may carry 'data' next to an error (code=$code)"
            }
            if (hasIndex && data != null) out += "an atomic failure (error.index) must not carry 'data'"
        }
        return out
    }

    /** Fails with every violation when [body] does not conform. */
    fun assertConforms(
        body: JsonElement,
        label: String = "envelope",
        continuation: Boolean = false
    ) {
        val found = violations(body, continuation)
        if (found.isNotEmpty()) fail("$label does not conform: $found; body=$body")
    }

    private fun checkNoNulls(
        element: JsonElement,
        path: String,
        out: MutableList<String>
    ) {
        when (element) {
            is JsonNull -> out += "null at $path (absent members are omitted, never null)"
            is JsonObject -> element.forEach { (k, v) -> checkNoNulls(v, "$path.$k", out) }
            is JsonArray -> element.forEachIndexed { i, v -> checkNoNulls(v, "$path[$i]", out) }
            else -> Unit
        }
    }

    private fun checkMeta(
        meta: JsonElement?,
        out: MutableList<String>
    ) {
        if (meta !is JsonObject) {
            out += "'meta' must be an object"
            return
        }
        for (key in meta.keys) if (key != "reqId" && key != "replayed") out += "unknown meta key '$key'"
        val reqId = meta["reqId"]
        if (reqId !is JsonPrimitive || !reqId.isString || reqId.content.isBlank()) out += "meta.reqId must be a non-blank string"
        if ("replayed" in meta && meta["replayed"].asBoolean() != true) out += "meta.replayed is present only as true"
    }

    private fun checkError(
        error: JsonElement,
        topLevel: Boolean,
        out: MutableList<String>
    ) {
        if (error !is JsonObject) {
            out += "'error' must be an object"
            return
        }
        for (key in error.keys) if (key !in allowedErrorKeys) out += "unknown error key '$key'"
        if (!topLevel && "index" in error) out += "'index' belongs to the top-level error of an atomic failure only"
        val code = error["code"].asString()
        if (code == null || !codePattern.matches(code)) out += "error.code must be a snake_case string, was $code"
        val kind = error["kind"].asString()
        if (kind !in kinds) out += "error.kind must be one of $kinds, was $kind"
        val message = error["message"].asString()
        if (message.isNullOrBlank()) out += "error.message must be a non-blank string"
        if ("detail" in error && error["detail"] !is JsonObject) out += "error.detail must be an object"
        if ("fix" in error && error["fix"].asString().isNullOrBlank()) out += "error.fix must be a non-blank string"
        if (kind == "permanent" && code != "partial_failure" && "fix" !in error) out += "a permanent error requires 'fix' (code=$code)"
        if ("retryAfterMs" in error) {
            val retry = error["retryAfterMs"]
            if (retry !is JsonPrimitive || retry.isString || retry.content.toLongOrNull()?.let { it >= 0 } != true) {
                out += "error.retryAfterMs must be a non-negative integer"
            }
        }
        if ("index" in error) {
            val index = error["index"]
            if (index !is JsonPrimitive || index.isString || index.content.toIntOrNull()?.let { it >= 0 } != true) {
                out += "error.index must be a non-negative integer"
            }
        }
    }

    private fun checkData(
        data: JsonElement,
        continuation: Boolean,
        out: MutableList<String>
    ) {
        if (data is JsonObject && ("items" in data || "page" in data)) checkPage(data, continuation, out)
        if (data is JsonObject && ("results" in data || "summary" in data)) checkBatch(data, out)
    }

    private fun checkPage(
        data: JsonObject,
        continuation: Boolean,
        out: MutableList<String>
    ) {
        val items = data["items"]
        val page = data["page"]
        if (items !is JsonArray) out += "list data requires an items array"
        if (page !is JsonObject) {
            out += "list data requires a page object"
            return
        }
        for (key in page.keys) if (key !in setOf("total", "truncated", "capped", "next")) out += "unknown page key '$key'"
        val total = page["total"].asInt()
        if (total == null || total < 0) out += "page.total must be a non-negative integer"
        val truncated = page["truncated"].asBoolean()
        if (truncated == null) out += "page.truncated must be a boolean"
        if ("capped" in page && page["capped"].asBoolean() != true) out += "page.capped is present only as true"
        val firstPageRuleBroken = !continuation && truncated != (items as? JsonArray)?.let { it.size < (total ?: 0) }
        val continuationRuleBroken = continuation && truncated == true && items is JsonArray && total != null && items.size >= total
        if (items is JsonArray && total != null && truncated != null && (firstPageRuleBroken || continuationRuleBroken)) {
            out += "page.truncated must equal items.length < total (items=${items.size}, total=$total, truncated=$truncated)"
        }
        if (truncated == true && page["next"].asString().isNullOrBlank()) out += "page.next is required when truncated"
        if (truncated == false && "next" in page) out += "page.next is present only when truncated"
    }

    private fun checkBatch(
        data: JsonObject,
        out: MutableList<String>
    ) {
        val results = data["results"]
        val summary = data["summary"]
        if (results !is JsonArray || results.isEmpty()) {
            out += "batch data requires a non-empty results array"
            return
        }
        if (summary !is JsonObject) {
            out += "batch data requires a summary object"
            return
        }
        var okCount = 0
        var failedCount = 0
        results.forEachIndexed { i, entry ->
            if (entry !is JsonObject) {
                out += "results[$i] must be an object"
                return@forEachIndexed
            }
            when (entry["ok"].asBoolean()) {
                true -> {
                    okCount++
                    if ("error" in entry) out += "results[$i] is ok:true but carries 'error'"
                }
                false -> {
                    failedCount++
                    val error = entry["error"]
                    if (error == null) out += "results[$i] is ok:false but has no 'error'" else checkError(error, topLevel = false, out)
                }
                null -> out += "results[$i].ok must be a boolean"
            }
            if ("replayed" in entry && entry["replayed"].asBoolean() != true) out += "results[$i].replayed is present only as true"
        }
        if (summary["ok"].asInt() != okCount || summary["failed"].asInt() != failedCount) {
            out += "summary $summary does not match results (ok=$okCount, failed=$failedCount)"
        }
    }

    private fun JsonElement?.asBoolean(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}

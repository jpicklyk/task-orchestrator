package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.TokenEstimate
import io.github.jpicklyk.mcptask.current.application.service.DependencyCommandService
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.lifecycle.NONE_ROLE_WIRE
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

/**
 * Pure helpers that derive `call_log` column values from a call's request arguments and result payload (plan
 * section 3.8). Both transports use them, so a column means the same on the MCP and the REST surface.
 */
object CallLogFields {
    /** Param keys whose UUID values name the items a call touched. */
    private val TARGET_KEYS =
        setOf(
            "itemId",
            "id",
            "ids",
            "parentId",
            "fromItemId",
            "toItemId",
            "noteId",
            "from",
            "to",
            "item",
            "itemIds",
            "rootId",
            "anchorId",
            "ancestorId",
            "fromIds",
            "toIds"
        )

    /** Top-level arrays whose length is the call's batch size, in precedence order. */
    private val BATCH_KEYS = listOf("items", "notes", "transitions", "claims", "releases", "dependencies", "ids")

    const val MAX_TARGET_IDS = 50

    /** UTF-8 length of [text] without allocating the encoded bytes (a surrogate pair counts 4, an unpaired one 3). */
    fun utf8Length(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    private val OPERATION_PATTERN = Regex("^[a-z_]{1,64}$")
    private val SHAPE_KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /** Marker stored for a supplied `operation` that is not a plain operation name. */
    const val INVALID_OPERATION = "invalid"

    /** Most flags kept in `request_shape`. */
    const val MAX_SHAPE_KEYS = 16

    /** The `request_shape` key naming the MCP tool profile a call was served under (server-derived). */
    const val PROFILE_KEY = "profile"

    /** The `request_shape` key of an `items_read` call's accepted `fields` names (server-derived). */
    const val FIELDS_KEY = "fields"

    /** The `request_shape` key set to true when a call touched more than one root (server-derived). */
    const val MULTI_ROOT_KEY = "multiRoot"

    /** The `request_shape` key of a `related` call's per-signal page contribution counts. */
    const val RELATED_SIGNALS_KEY = "relatedSignals"

    /**
     * The top-level `operation` when it is a plain name (`^[a-z_]{1,64}$`), [INVALID_OPERATION] for any other
     * supplied string value, else null. Client text beyond that is never stored.
     */
    fun operation(arguments: JsonObject?): String? {
        val op = arguments?.get("operation") as? JsonPrimitive ?: return null
        if (!op.isString) return null
        return if (OPERATION_PATTERN.matches(op.content)) op.content else INVALID_OPERATION
    }

    /**
     * JSON array text of the distinct UUIDs named under [TARGET_KEYS] at the top level and in the elements of
     * top-level arrays, in first-seen order, at most [MAX_TARGET_IDS]. Null when there are none.
     */
    fun targetIdsJson(arguments: JsonObject?): String? = targetIdsJson(arguments, emptyList())

    /** [targetIdsJson] with extra ids (REST path segments) appended after the argument-derived ones. */
    fun targetIdsJson(
        arguments: JsonObject?,
        extra: List<String>
    ): String? {
        val seen = LinkedHashSet<String>()

        fun offer(value: JsonElement?) {
            if (seen.size >= MAX_TARGET_IDS) return
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            parseUuid(text)?.let { seen.add(it) }
        }

        fun scan(obj: JsonObject) {
            for ((key, value) in obj) {
                if (key !in TARGET_KEYS) continue
                if (value is JsonArray) value.forEach { offer(it) } else offer(value)
            }
        }
        if (arguments != null) {
            scan(arguments)
            for (value in arguments.values) {
                if (value is JsonArray) value.forEach { if (it is JsonObject) scan(it) }
            }
        }
        extra.forEach { if (seen.size < MAX_TARGET_IDS) parseUuid(it)?.let(seen::add) }
        if (seen.isEmpty()) return null
        return buildJsonArray { seen.forEach { add(JsonPrimitive(it)) } }.toString()
    }

    private fun parseUuid(text: String): String? =
        if (text.length == 36) {
            try {
                UUID.fromString(text).toString()
            } catch (e: IllegalArgumentException) {
                null
            }
        } else {
            null
        }

    /** JSON object text of id to version for the versions the call observed, else null. */
    fun targetVersionsJson(versions: Map<UUID, Long>): String? {
        if (versions.isEmpty()) return null
        return buildJsonObject {
            versions.entries.sortedBy { it.key.toString() }.forEach { put(it.key.toString(), JsonPrimitive(it.value)) }
        }.toString()
    }

    /**
     * JSON object text, keys sorted, of the top-level boolean params plus a numeric `limit`; null if empty.
     * Only the param SHAPE is kept, never a string or an id. A key must match `^[A-Za-z0-9_.-]{1,64}$` (others are
     * dropped silently) and at most [MAX_SHAPE_KEYS] keys (the first in sorted order) are kept.
     */
    fun requestShapeJson(arguments: JsonObject?): String? = requestShapeJson(arguments, null)

    /**
     * [requestShapeJson] plus the server-derived [searchShape] of a search call (`queryHash`, `termCount`,
     * `matchMode`, and `hitsByKind` once the search ran) and the [relatedSignals] of a `related` call
     * (`relatedSignals`: per signal, the returned-page hits it contributed to) and the MCP tool [profile] wire value
     * (`profile`), the accepted `fields` names (`fields`) and `multiRoot: true`, which always survive the
     * [MAX_SHAPE_KEYS] limit and replace a client flag of the same name. The query text is never part of it.
     */
    fun requestShapeJson(
        arguments: JsonObject?,
        searchShape: CallTelemetry.SearchShape?,
        relatedSignals: Map<String, Int>? = null,
        profile: String? = null,
        fields: List<String>? = null,
        multiRoot: Boolean = false
    ): String? {
        val extras = (searchShape?.let { searchShapeFields(it) } ?: emptyMap()).toMutableMap()
        profile?.let { extras[PROFILE_KEY] = JsonPrimitive(it) }
        fields?.takeIf { it.isNotEmpty() }?.let { names ->
            extras[FIELDS_KEY] = JsonArray(names.toSortedSet().map { JsonPrimitive(it) })
        }
        if (multiRoot) extras[MULTI_ROOT_KEY] = JsonPrimitive(true)
        relatedSignals?.let { signals ->
            extras[RELATED_SIGNALS_KEY] = JsonObject(signals.toSortedMap().mapValues { JsonPrimitive(it.value) })
        }
        val client = clientShape(arguments).filterKeys { it !in extras }
        if (client.isEmpty() && extras.isEmpty()) return null
        val kept = client.entries.take(MAX_SHAPE_KEYS - extras.size).associate { it.key to it.value }
        return JsonObject((kept + extras).toSortedMap()).toString()
    }

    /** The `request_shape` fields of a search call. */
    fun searchShapeFields(shape: CallTelemetry.SearchShape): Map<String, JsonElement> {
        val fields =
            mutableMapOf<String, JsonElement>(
                "queryHash" to JsonPrimitive(shape.queryHash),
                "termCount" to JsonPrimitive(shape.termCount),
                "matchMode" to JsonPrimitive(shape.matchMode)
            )
        shape.hitsByKind?.let { mix ->
            fields["hitsByKind"] = JsonObject(mix.toSortedMap().mapValues { JsonPrimitive(it.value) })
        }
        return fields
    }

    private fun clientShape(arguments: JsonObject?): Map<String, JsonElement> {
        if (arguments == null) return emptyMap()
        val shape = sortedMapOf<String, JsonElement>()
        for ((key, value) in arguments) {
            if (!SHAPE_KEY_PATTERN.matches(key)) continue
            val primitive = value as? JsonPrimitive ?: continue
            if (primitive.isString) continue
            if (primitive.content == "true" || primitive.content == "false") {
                shape[key] = JsonPrimitive(primitive.content == "true")
            } else if (key == "limit") {
                primitive.longOrNull?.let { shape[key] = JsonPrimitive(it) }
            }
        }
        return shape
    }

    /** Length of the first present top-level array among the batch keys, else null. */
    fun batchSize(arguments: JsonObject?): Int? {
        if (arguments == null) return null
        for (key in BATCH_KEYS) {
            val array = arguments[key] as? JsonArray ?: continue
            return array.size
        }
        return null
    }

    /** `summary.failed`, else `failed` (an int) of a result payload; null when neither is present. */
    fun failedCount(data: JsonObject?): Int? {
        if (data == null) return null
        ((data["summary"] as? JsonObject)?.get("failed") as? JsonPrimitive)?.intOrNull?.let { return it }
        return (data["failed"] as? JsonPrimitive)?.intOrNull
    }

    /** `returned` (an int) of a result payload, else null. */
    fun returnedCount(data: JsonObject?): Int? = (data?.get("returned") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    /**
     * JSON array text of a `related` call's returned page, hit by hit:
     * `[{"id":"<uuid>","kind":"item"|"document","signals":[...]}]`, in page order. Null when [hits] is null or empty
     * (never `[]`). Ids and signal names only, never a title or any text.
     */
    fun hitSignalsJson(hits: List<CallTelemetry.HitSignals>?): String? {
        if (hits.isNullOrEmpty()) return null
        return buildJsonArray {
            for (hit in hits) {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(hit.id.toString()))
                        put("kind", JsonPrimitive(hit.kind))
                        put("signals", JsonArray(hit.signals.map { JsonPrimitive(it) }))
                    }
                )
            }
        }.toString()
    }

    /**
     * How many rejections in the encoded response [body] were caused by a lifecycle-free item; null when none (never
     * 0). When `data.results` is an array its `ok:false` elements are judged, else the top-level `error` (never both, so
     * a non-atomic batch's `partial_failure` is not counted on top of its elements). An error counts when it is an
     * `invalid_transition` with `detail.fromRole` "none" (a transition, claim, heartbeat or flip refused for a
     * lifecycle-free item), or an `invalid_request` whose `detail.fields[].reason` is
     * [DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON] (a BLOCKS edge with a lifecycle-free endpoint).
     */
    fun lifecycleFreeRejections(body: JsonObject?): Int? {
        if (body == null) return null
        val results = (body["data"] as? JsonObject)?.get("results") as? JsonArray
        val count =
            if (results != null) {
                results.count { element ->
                    val entry = element as? JsonObject ?: return@count false
                    (entry["ok"] as? JsonPrimitive)?.contentOrNull == "false" && isLifecycleFree(entry["error"] as? JsonObject)
                }
            } else {
                if (isLifecycleFree(body["error"] as? JsonObject)) 1 else 0
            }
        return count.takeIf { it > 0 }
    }

    private fun isLifecycleFree(error: JsonObject?): Boolean {
        if (error == null) return false
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull
        val detail = error["detail"] as? JsonObject ?: return false
        return when (code) {
            ErrorCode.INVALID_TRANSITION.wire ->
                (detail["fromRole"] as? JsonPrimitive)?.contentOrNull == NONE_ROLE_WIRE
            ErrorCode.INVALID_REQUEST.wire ->
                (detail["fields"] as? JsonArray).orEmpty().any { field ->
                    ((field as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull ==
                        DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON
                }
            else -> false
        }
    }

    /** `error.code` of an error payload, else null. */
    fun errorCode(payload: JsonObject?): String? = ((payload?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull

    /**
     * Assembles the record. [responseBytes] / [requestBytes] null means unknown (the token estimate is then null too).
     * `hit_signals` comes from [telemetry]; [lifecycleFreeRejections] is the transport's [lifecycleFreeRejections] of
     * the response body.
     */
    fun record(
        telemetry: CallTelemetry,
        at: Instant,
        tool: String,
        operation: String?,
        targetIds: String?,
        requestShape: String?,
        isError: Boolean,
        errorCode: String?,
        latencyMs: Long,
        requestBytes: Long?,
        responseBytes: Long?,
        batchSize: Int?,
        failedCount: Int?,
        resultCount: Int?,
        eligibleCount: Int?,
        lifecycleFreeRejections: Int? = null
    ): CallLogRecord {
        val principal = telemetry.principal
        return CallLogRecord(
            reqId = telemetry.reqId,
            at = at,
            principalId = principal?.id,
            principalKind = principal?.kind,
            proofStatus = principal?.proofStatus,
            host = telemetry.host,
            sessionId = telemetry.sessionId,
            runId = telemetry.runId,
            seat = telemetry.seat,
            surface = telemetry.surface,
            tool = tool,
            operation = operation,
            targetIds = targetIds,
            targetVersions = targetVersionsJson(telemetry.targetVersions),
            requestShape = requestShape,
            outcome = if (isError) CallLogRecord.OUTCOME_ERROR else CallLogRecord.OUTCOME_OK,
            errorCode = if (isError) errorCode else null,
            attempts = 1 + telemetry.retries,
            latencyMs = latencyMs.coerceAtLeast(0),
            requestBytes = requestBytes,
            responseBytes = responseBytes,
            responseTokensEst = responseBytes?.let { TokenEstimate.of(it) },
            tokenMethod = responseBytes?.let { TokenEstimate.METHOD },
            replayed = telemetry.replayed,
            batchSize = batchSize,
            failedCount = failedCount,
            resultCount = telemetry.resultCount ?: resultCount,
            eligibleCount = telemetry.eligibleCount ?: eligibleCount,
            hitSignals = hitSignalsJson(telemetry.hitSignals),
            rootId = telemetry.rootId,
            lifecycleFreeRejections = lifecycleFreeRejections
        )
    }
}

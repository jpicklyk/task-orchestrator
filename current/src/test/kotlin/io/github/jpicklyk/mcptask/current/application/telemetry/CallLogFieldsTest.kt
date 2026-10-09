package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.TokenEstimate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for item 8abb69e2 (P10): the pure column-derivation functions ([CallLogFields], [TokenEstimate]).
 *
 * Oracles (all from the frozen task-scope "Column sources" table and What-to-build item 4, plan section 2 Q3):
 * - token estimate = ceil(utf8Bytes / 4), method string `bytes/4`;
 * - operation = `arguments.operation` string, else null;
 * - target_ids = JSON array, distinct, first-seen order, max 50, of UUID strings under keys
 *   itemId,id,ids,parentId,fromItemId,toItemId,noteId at the top level and in elements of top-level arrays;
 * - request_shape = JSON object with sorted keys of every top-level boolean param plus numeric `limit`, null if empty;
 * - batch_size = length of the first present top-level array among items, notes, transitions, claims, releases,
 *   dependencies, ids, else null; failed_count = data.summary.failed, else data.failed (int), else null;
 *   result_count also = data.returned when present; error_code = structuredContent.error.code.
 * Return types `String?`/`Int?` in the supplied declarations are the public evidence that "none found" is null.
 */
class CallLogFieldsTest {
    private fun uuid(n: Int): String = UUID.nameUUIDFromBytes("call-log-fields-$n".toByteArray()).toString()

    private fun idList(json: String?): List<String> =
        json?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } } ?: emptyList()

    // ------------------------------------------------------------------ token estimate (S5)

    @Test
    fun `S5 TokenEstimate is ceil of bytes over 4 and the method is bytes slash 4`() {
        assertEquals("bytes/4", TokenEstimate.METHOD)
        val expected = mapOf(0L to 0L, 1L to 1L, 3L to 1L, 4L to 1L, 5L to 2L, 8L to 2L, 9L to 3L, 4000L to 1000L, 4001L to 1001L)
        for ((bytes, tokens) in expected) assertEquals(tokens, TokenEstimate.of(bytes), "ceil($bytes / 4)")
    }

    @Test
    fun `S5 TokenEstimate does not overflow near Long MAX_VALUE`() {
        // 9223372036854775807 / 4 = 2305843009213693951.75, so the ceiling is 2305843009213693952.
        assertEquals(2305843009213693952L, TokenEstimate.of(Long.MAX_VALUE))
        // MAX - 3 = 9223372036854775804 is divisible by 4: exactly 2305843009213693951, no rounding up.
        assertEquals(2305843009213693951L, TokenEstimate.of(Long.MAX_VALUE - 3))
        assertEquals(2305843009213693951L, TokenEstimate.of(Long.MAX_VALUE - 4))
        // 2^53 + 1 = 9007199254740993; /4 = 2251799813685248.25, so the exact ceiling is 2251799813685249 (a double cannot hold it).
        assertEquals(2251799813685249L, TokenEstimate.of(9007199254740993L))
    }

    @Test
    fun `S5 utf8Length counts bytes not characters`() {
        assertEquals(0L, CallLogFields.utf8Length(""))
        assertEquals(1L, CallLogFields.utf8Length("a"))
        assertEquals(2L, CallLogFields.utf8Length("é"), "U+00E9 is two bytes")
        assertEquals(3L, CallLogFields.utf8Length("é"), "e plus combining acute is 1 + 2 bytes")
        assertEquals(3L, CallLogFields.utf8Length("✓"), "U+2713 is three bytes")
        assertEquals(4L, CallLogFields.utf8Length("😀"), "U+1F600 (a surrogate pair) is four bytes, not 2 x 3")
        val mixed = "aé✓😀"
        assertEquals(1L + 2L + 3L + 4L, CallLogFields.utf8Length(mixed))
        assertEquals(mixed.toByteArray(Charsets.UTF_8).size.toLong(), CallLogFields.utf8Length(mixed))
        assertEquals(10_000L, CallLogFields.utf8Length("x".repeat(10_000)))
    }

    // ------------------------------------------------------------------ operation

    @Test
    fun `operation is the string argument else null`() {
        assertEquals("create", CallLogFields.operation(buildJsonObject { put("operation", "create") }))
        assertNull(CallLogFields.operation(null), "absent arguments")
        assertNull(CallLogFields.operation(JsonObject(emptyMap())), "empty arguments")
        assertNull(CallLogFields.operation(buildJsonObject { put("operation", JsonNull) }), "explicit null")
        assertNull(CallLogFields.operation(buildJsonObject { put("title", "x") }), "operation key absent")
    }

    @Test
    fun `F1 an operation that does not match the lowercase-underscore pattern is stored as invalid`() {
        val conforming = listOf("create", "get_next", "a", "a".repeat(64))
        for (op in conforming) assertEquals(op, CallLogFields.operation(buildJsonObject { put("operation", op) }), "conforming: $op")
        val rejected = listOf("a".repeat(65), "a".repeat(5000), "Create", "has space", "dash-ed", "digit1", "x;DROP", "\u00e9", "")
        for (op in rejected) {
            assertEquals(
                "invalid",
                CallLogFields.operation(buildJsonObject { put("operation", op) }),
                "supplied but non-conforming operation (length ${op.length}) is stored as invalid"
            )
        }
        assertNull(CallLogFields.operation(JsonObject(emptyMap())), "absent stays null, it is not invalid")
        assertNull(CallLogFields.operation(null), "no arguments stays null")
    }

    @Test
    fun `F1 request shape keeps at most 16 conforming keys, sorted, and drops the non-conforming ones`() {
        val many =
            buildJsonObject {
                for (i in 19 downTo 0) put("flag" + i.toString().padStart(2, '0'), i % 2 == 0)
                put("bad key", true)
                put("x".repeat(65), true)
                put("caf\u00e9", true)
            }
        val parsed = Json.parseToJsonElement(CallLogFields.requestShapeJson(many)!!).jsonObject
        assertEquals(16, parsed.size, "20 conforming keys are capped at 16: ${parsed.keys}")
        assertEquals(parsed.keys.sorted(), parsed.keys.toList(), "sorted")
        assertTrue(parsed.keys.all { Regex("^[A-Za-z0-9_.-]{1,64}$").matches(it) }, "only conforming keys: ${parsed.keys}")
        assertTrue(parsed.keys.all { it.startsWith("flag") })
        assertEquals(
            parsed.keys.toList(),
            Json
                .parseToJsonElement(CallLogFields.requestShapeJson(many)!!)
                .jsonObject.keys
                .toList(),
            "deterministic"
        )

        val onlyBad =
            buildJsonObject {
                put("bad key", true)
                put("x".repeat(65), false)
            }
        assertNull(CallLogFields.requestShapeJson(onlyBad), "nothing conforming leaves nothing to store")
        val boundary = buildJsonObject { put("k".repeat(64), true) }
        assertEquals(
            listOf("k".repeat(64)),
            Json
                .parseToJsonElement(CallLogFields.requestShapeJson(boundary)!!)
                .jsonObject.keys
                .toList()
        )
    }

    // ------------------------------------------------------------------ target ids

    @Test
    fun `target ids pick UUIDs under the documented keys in first-seen order`() {
        val args =
            buildJsonObject {
                put("itemId", uuid(1))
                put("id", uuid(2))
                putJsonArray("ids") {
                    add(JsonPrimitive(uuid(3)))
                    add(JsonPrimitive(uuid(4)))
                }
                put("parentId", uuid(5))
                put("fromItemId", uuid(6))
                put("toItemId", uuid(7))
                put("noteId", uuid(8))
            }
        assertEquals((1..8).map { uuid(it) }, idList(CallLogFields.targetIdsJson(args)))
    }

    @Test
    fun `target ids also read elements of top-level arrays`() {
        val args =
            buildJsonObject {
                put("parentId", uuid(1))
                putJsonArray("items") {
                    add(buildJsonObject { put("id", uuid(2)) })
                    add(buildJsonObject { put("id", uuid(3)) })
                }
                putJsonArray("notes") {
                    add(
                        buildJsonObject {
                            put("itemId", uuid(3))
                            put("key", "k")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemId", uuid(4))
                            put("key", "k2")
                        }
                    )
                }
            }
        assertEquals(listOf(uuid(1), uuid(2), uuid(3), uuid(4)), idList(CallLogFields.targetIdsJson(args)), "distinct, first-seen order")
    }

    @Test
    fun `target ids exclude non-UUID text non-strings and deeper nesting`() {
        val args =
            buildJsonObject {
                put("itemId", "abcd1234")
                put("id", "not-a-uuid")
                put("parentId", 5)
                put("noteId", JsonNull)
                putJsonArray("ids") {
                    add(JsonPrimitive("zzz"))
                    add(JsonPrimitive(uuid(1)))
                }
                putJsonArray("items") {
                    add(buildJsonObject { putJsonArray("nested") { add(buildJsonObject { put("id", uuid(2)) }) } })
                    add(buildJsonObject { put("meta", buildJsonObject { put("id", uuid(3)) }) })
                }
            }
        assertEquals(listOf(uuid(1)), idList(CallLogFields.targetIdsJson(args)), "only the real UUID under a documented key counts")
    }

    @Test
    fun `target ids are null when there are none`() {
        assertNull(CallLogFields.targetIdsJson(null), "absent arguments")
        assertNull(CallLogFields.targetIdsJson(JsonObject(emptyMap())), "empty arguments")
        assertNull(CallLogFields.targetIdsJson(buildJsonObject { put("title", "x") }), "no id keys")
        assertNull(CallLogFields.targetIdsJson(buildJsonObject { put("itemId", "abcd1234") }), "only a non-UUID")
    }

    @Test
    fun `target ids are deduplicated and capped at 50`() {
        val dup =
            buildJsonObject {
                putJsonArray("ids") { repeat(60) { add(JsonPrimitive(uuid(it % 3))) } }
            }
        assertEquals(listOf(uuid(0), uuid(1), uuid(2)), idList(CallLogFields.targetIdsJson(dup)), "60 entries over 3 distinct ids")

        fun withDistinct(n: Int) = buildJsonObject { putJsonArray("ids") { repeat(n) { add(JsonPrimitive(uuid(it))) } } }
        assertEquals(50, CallLogFields.MAX_TARGET_IDS)
        assertEquals(49, idList(CallLogFields.targetIdsJson(withDistinct(49))).size)
        assertEquals(50, idList(CallLogFields.targetIdsJson(withDistinct(50))).size)
        val capped = idList(CallLogFields.targetIdsJson(withDistinct(51)))
        assertEquals((0 until 50).map { uuid(it) }, capped, "the 51st id is dropped and the first 50 keep their order")
        assertEquals((0 until 50).map { uuid(it) }, idList(CallLogFields.targetIdsJson(withDistinct(500))))
    }

    @Test
    fun `target ids with extra ids merge without duplicates`() {
        assertEquals(listOf(uuid(1)), idList(CallLogFields.targetIdsJson(null, listOf(uuid(1)))))
        val merged = idList(CallLogFields.targetIdsJson(buildJsonObject { put("itemId", uuid(1)) }, listOf(uuid(1), uuid(2))))
        assertEquals(setOf(uuid(1), uuid(2)), merged.toSet())
        assertEquals(2, merged.size, "the id present in both sources appears once")
        assertNull(CallLogFields.targetIdsJson(null, emptyList()))
    }

    // ------------------------------------------------------------------ target versions

    @Test
    fun `target versions render as an id to version object and are null when empty`() {
        val a = UUID.fromString(uuid(1))
        val b = UUID.fromString(uuid(2))
        val json = CallLogFields.targetVersionsJson(linkedMapOf(a to 3L, b to 12L))
        val parsed = Json.parseToJsonElement(json!!).jsonObject
        assertEquals(setOf(a.toString(), b.toString()), parsed.keys)
        assertEquals(3L, parsed[a.toString()]!!.jsonPrimitive.content.toLong())
        assertEquals(12L, parsed[b.toString()]!!.jsonPrimitive.content.toLong())
        assertNull(CallLogFields.targetVersionsJson(emptyMap()))
    }

    // ------------------------------------------------------------------ request shape

    @Test
    fun `request shape holds booleans and numeric limit with sorted keys`() {
        val args =
            buildJsonObject {
                put("zeta", false)
                put("title", "x")
                put("includeChildren", true)
                put("count", 5)
                put("limit", 25)
                put("alpha", true)
                putJsonArray("items") { add(JsonPrimitive(true)) }
            }
        val json = CallLogFields.requestShapeJson(args)!!
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(listOf("alpha", "includeChildren", "limit", "zeta"), parsed.keys.toList(), "sorted keys, nothing else: $json")
        assertEquals(true, parsed["alpha"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("true", parsed["includeChildren"]!!.jsonPrimitive.content)
        assertEquals("false", parsed["zeta"]!!.jsonPrimitive.content)
        assertEquals(25, parsed["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `request shape is null when no boolean or numeric limit is present`() {
        assertNull(CallLogFields.requestShapeJson(null))
        assertNull(CallLogFields.requestShapeJson(JsonObject(emptyMap())))
        assertNull(
            CallLogFields.requestShapeJson(
                buildJsonObject {
                    put("title", "x")
                    put("count", 5)
                }
            )
        )
        assertNull(CallLogFields.requestShapeJson(buildJsonObject { put("limit", "5") }), "a string limit is not numeric")
        assertNull(CallLogFields.requestShapeJson(buildJsonObject { put("flag", JsonNull) }), "null is not a boolean")
    }

    // ------------------------------------------------------------------ batch size

    @Test
    fun `batch size is the length of the first present array in the documented order`() {
        fun arr(n: Int): JsonArray = buildJsonArray { repeat(n) { add(JsonPrimitive(it)) } }
        assertEquals(3, CallLogFields.batchSize(buildJsonObject { put("items", arr(3)) }))
        assertEquals(2, CallLogFields.batchSize(buildJsonObject { put("notes", arr(2)) }))
        assertEquals(4, CallLogFields.batchSize(buildJsonObject { put("transitions", arr(4)) }))
        assertEquals(5, CallLogFields.batchSize(buildJsonObject { put("claims", arr(5)) }))
        assertEquals(6, CallLogFields.batchSize(buildJsonObject { put("releases", arr(6)) }))
        assertEquals(7, CallLogFields.batchSize(buildJsonObject { put("dependencies", arr(7)) }))
        assertEquals(8, CallLogFields.batchSize(buildJsonObject { put("ids", arr(8)) }))
        assertEquals(
            5,
            CallLogFields.batchSize(
                buildJsonObject {
                    put("notes", arr(2))
                    put("items", arr(5))
                }
            ),
            "items precedes notes"
        )
        assertEquals(
            2,
            CallLogFields.batchSize(
                buildJsonObject {
                    put("transitions", arr(1))
                    put("notes", arr(2))
                }
            ),
            "notes precedes transitions"
        )
        assertEquals(0, CallLogFields.batchSize(buildJsonObject { put("items", arr(0)) }), "an empty array is present with length 0")
    }

    @Test
    fun `batch size is null without a batch array`() {
        assertNull(CallLogFields.batchSize(null))
        assertNull(CallLogFields.batchSize(JsonObject(emptyMap())))
        assertNull(CallLogFields.batchSize(buildJsonObject { put("title", "x") }))
        assertEquals(
            3,
            CallLogFields.batchSize(
                buildJsonObject {
                    put("items", "not-an-array")
                    putJsonArray("notes") { repeat(3) { add(JsonPrimitive(it)) } }
                }
            ),
            "a non-array items does not count as present"
        )
    }

    // ------------------------------------------------------------------ failed / returned / error code

    @Test
    fun `failed count prefers summary failed then failed then null`() {
        assertEquals(
            2,
            CallLogFields.failedCount(
                buildJsonObject {
                    put("summary", buildJsonObject { put("failed", 2) })
                    put("failed", 9)
                }
            )
        )
        assertEquals(4, CallLogFields.failedCount(buildJsonObject { put("failed", 4) }))
        assertEquals(
            0,
            CallLogFields.failedCount(
                buildJsonObject {
                    put("summary", buildJsonObject { put("failed", 0) })
                }
            ),
            "zero is a value, not absence"
        )
        assertNull(CallLogFields.failedCount(JsonObject(emptyMap())))
        assertNull(CallLogFields.failedCount(null))
        assertNull(CallLogFields.failedCount(buildJsonObject { put("summary", buildJsonObject { put("total", 3) }) }))
    }

    @Test
    fun `returned count reads data returned`() {
        assertEquals(7, CallLogFields.returnedCount(buildJsonObject { put("returned", 7) }))
        assertEquals(0, CallLogFields.returnedCount(buildJsonObject { put("returned", 0) }))
        assertNull(CallLogFields.returnedCount(JsonObject(emptyMap())))
        assertNull(CallLogFields.returnedCount(null))
    }

    @Test
    fun `error code reads structuredContent error code`() {
        val payload =
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("code", "VALIDATION_ERROR")
                        put("message", "bad")
                    }
                )
            }
        assertEquals("VALIDATION_ERROR", CallLogFields.errorCode(payload))
        assertNull(CallLogFields.errorCode(buildJsonObject { put("success", true) }))
        assertNull(CallLogFields.errorCode(null))
        assertNull(CallLogFields.errorCode(buildJsonObject { put("error", buildJsonObject { put("message", "no code") }) }))
    }

    // ------------------------------------------------------------------ record assembly

    @Test
    fun `record assembles every column from the telemetry and the supplied values`() {
        val id = UUID.fromString(uuid(1))
        val t = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = "session-9")
        t.setPrincipal(CallTelemetry.Principal("agent-1", "subagent", "absent"))
        t.incrementRetry()
        t.incrementRetry()
        t.setReplayed()
        t.setTargetVersion(id, 6)
        t.setResultCounts(4, 9)
        val at = Instant.parse("2026-03-04T05:06:07.089Z")

        val record =
            CallLogFields.record(
                telemetry = t,
                at = at,
                tool = "advance_item",
                operation = "start",
                targetIds = "[\"${id}\"]",
                requestShape = "{\"includeAncestors\":true}",
                isError = false,
                errorCode = null,
                latencyMs = 41,
                requestBytes = 310,
                responseBytes = 1290,
                batchSize = 5,
                failedCount = 1,
                resultCount = 4,
                eligibleCount = 9
            )

        assertEquals("k7f3q9ab", record.reqId)
        assertEquals(at, record.at)
        assertEquals("agent-1", record.principalId)
        assertEquals("subagent", record.principalKind)
        assertEquals("absent", record.proofStatus)
        assertEquals("session-9", record.sessionId)
        assertEquals(CallLogRecord.SURFACE_MCP, record.surface)
        assertEquals("advance_item", record.tool)
        assertEquals("start", record.operation)
        assertEquals("[\"${id}\"]", record.targetIds)
        assertEquals(
            setOf(id.toString()),
            Json.parseToJsonElement(record.targetVersions!!).jsonObject.keys,
            "target versions come from the telemetry"
        )
        assertEquals("{\"includeAncestors\":true}", record.requestShape)
        assertEquals(CallLogRecord.OUTCOME_OK, record.outcome)
        assertNull(record.errorCode)
        assertEquals(3, record.attempts, "1 + two BUSY retries")
        assertEquals(41, record.latencyMs)
        assertEquals(310L, record.requestBytes)
        assertEquals(1290L, record.responseBytes)
        assertEquals(323L, record.responseTokensEst, "ceil(1290 / 4)")
        assertEquals("bytes/4", record.tokenMethod)
        assertTrue(record.replayed)
        assertEquals(5, record.batchSize)
        assertEquals(1, record.failedCount)
        assertEquals(4, record.resultCount)
        assertEquals(9, record.eligibleCount)
        assertNull(record.host, "host stays null until W4/W5")
        assertNull(record.runId, "run_id stays null until W4/W5")
        assertNull(record.seat, "seat stays null until W4/W5")
    }

    @Test
    fun `record of an error call carries outcome error and the code and without response bytes has no token estimate`() {
        val t = CallTelemetry(reqId = "abcd2345", surface = CallLogRecord.SURFACE_REST, sessionId = null)
        val record =
            CallLogFields.record(
                telemetry = t,
                at = Instant.parse("2026-03-04T05:06:07.089Z"),
                tool = "GET /api/v1/items/{id}",
                operation = null,
                targetIds = null,
                requestShape = null,
                isError = true,
                errorCode = "not_found",
                latencyMs = 3,
                requestBytes = null,
                responseBytes = null,
                batchSize = null,
                failedCount = null,
                resultCount = null,
                eligibleCount = null
            )
        assertEquals(CallLogRecord.OUTCOME_ERROR, record.outcome)
        assertEquals("not_found", record.errorCode)
        assertEquals(1, record.attempts, "no retries means one attempt")
        assertNull(record.responseBytes)
        assertNull(record.responseTokensEst, "no bytes, no estimate")
        assertNull(record.tokenMethod, "no bytes, no method")
        assertTrue(!record.replayed)
        assertNull(record.principalId, "no principal recorded")
        assertNull(record.principalKind)
        assertNull(record.proofStatus)
        assertNull(record.sessionId)
        assertEquals(CallLogRecord.SURFACE_REST, record.surface)
    }

    @Test
    fun `record of a zero-byte response still has a token estimate of zero`() {
        val t = CallTelemetry(reqId = "abcd2345", surface = CallLogRecord.SURFACE_REST, sessionId = null)
        val record =
            CallLogFields.record(
                telemetry = t,
                at = Instant.EPOCH,
                tool = "GET /api/v1/health",
                operation = null,
                targetIds = null,
                requestShape = null,
                isError = false,
                errorCode = null,
                latencyMs = 0,
                requestBytes = 0,
                responseBytes = 0,
                batchSize = null,
                failedCount = null,
                resultCount = null,
                eligibleCount = null
            )
        assertEquals(0L, record.responseBytes)
        assertEquals(0L, record.responseTokensEst, "zero bytes is a measured zero, not an absent measurement")
        assertEquals("bytes/4", record.tokenMethod)
        assertEquals(0L, record.requestBytes)
    }
}

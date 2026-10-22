package io.github.jpicklyk.mcptask.current.application.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 6a52466e, R2) for the widened `target_ids` key set and the server-derived
 * `request_shape` keys `fields` and `multiRoot`, at the pure-function level ([CallLogFields.targetIdsJson],
 * [CallLogFields.requestShapeJson]).
 *
 * Oracles (frozen at queue phase): task-scope point 6 and AC4 (`target_ids` includes UUIDs under `from`, `to`, `item`, `itemIds`,
 * `rootId`, `anchorId`, `ancestorId`, `fromIds`, `toIds`, at the top level and in top-level array elements; distinct, first-seen
 * order, at most 50, 8-hex prefixes and other non-UUID text are not ids); the public deps_write op shapes of
 * current/docs/api-reference.md (`fan_out` has `from` and `toIds`, `fan_in` has `fromIds` and `to`); point 4 (`multiRoot: true`
 * only when the call was multi-root, `fields` an array of the accepted names, both survive MAX_SHAPE_KEYS like `profile` and
 * replace a same-named client key). The first-seen order test lists the arguments in the order of the declared TARGET_KEYS
 * constant, so it holds whether first-seen means argument order or key-set order.
 *
 * Not asserted (the oracle does not fix it): what happens to a client-sent `fields` or `multiRoot` key when the server has no
 * value for it, and the order/de-duplication of the names handed to the function (asserted end to end in McpCallLogRootTest).
 *
 * Forbidden-construct declaration: none.
 */
class CallLogFieldsRootKeysTest {
    private fun uuid(n: Int): String = UUID.nameUUIDFromBytes("root-keys-$n".toByteArray()).toString()

    private fun idList(json: String?): List<String> =
        json?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } } ?: emptyList()

    private val newScalarKeys = listOf("from", "to", "item", "rootId", "anchorId", "ancestorId")
    private val newArrayKeys = listOf("itemIds", "fromIds", "toIds")

    // ------------------------------------------------------------------ S9 target ids

    @Test
    fun `S9 a UUID under each new scalar key is a target id in first-seen order`() {
        val args = buildJsonObject { newScalarKeys.forEachIndexed { i, key -> put(key, uuid(i)) } }
        assertEquals(newScalarKeys.indices.map { uuid(it) }, idList(CallLogFields.targetIdsJson(args)))
    }

    @Test
    fun `S9 each new scalar key alone is recorded`() {
        for (key in newScalarKeys) {
            val args = buildJsonObject { put(key, uuid(99)) }
            assertEquals(listOf(uuid(99)), idList(CallLogFields.targetIdsJson(args)), "key $key")
        }
    }

    @Test
    fun `S9 UUIDs in an array under each new plural key are target ids`() {
        for ((n, key) in newArrayKeys.withIndex()) {
            val args =
                buildJsonObject {
                    putJsonArray(key) {
                        add(JsonPrimitive(uuid(10 * n + 1)))
                        add(JsonPrimitive(uuid(10 * n + 2)))
                    }
                }
            assertEquals(listOf(uuid(10 * n + 1), uuid(10 * n + 2)), idList(CallLogFields.targetIdsJson(args)), "key $key")
        }
    }

    @Test
    fun `S9 the declared key order gives one first-seen list across old and new keys`() {
        val args =
            buildJsonObject {
                put("itemId", uuid(1))
                put("id", uuid(2))
                putJsonArray("ids") { add(JsonPrimitive(uuid(3))) }
                put("parentId", uuid(4))
                put("fromItemId", uuid(5))
                put("toItemId", uuid(6))
                put("noteId", uuid(7))
                put("from", uuid(8))
                put("to", uuid(9))
                put("item", uuid(10))
                putJsonArray("itemIds") { add(JsonPrimitive(uuid(11))) }
                put("rootId", uuid(12))
                put("anchorId", uuid(13))
                put("ancestorId", uuid(14))
                putJsonArray("fromIds") { add(JsonPrimitive(uuid(15))) }
                putJsonArray("toIds") { add(JsonPrimitive(uuid(16))) }
            }
        assertEquals((1..16).map { uuid(it) }, idList(CallLogFields.targetIdsJson(args)))
    }

    @Test
    fun `S9 new keys are also read inside elements of a top-level array`() {
        val args =
            buildJsonObject {
                putJsonArray("ops") {
                    add(
                        buildJsonObject {
                            put("op", "create")
                            put("from", uuid(1))
                            put("to", uuid(2))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("op", "fan_out")
                            put("from", uuid(3))
                            putJsonArray("toIds") {
                                add(JsonPrimitive(uuid(4)))
                                add(JsonPrimitive(uuid(5)))
                            }
                        }
                    )
                    add(
                        buildJsonObject {
                            put("op", "fan_in")
                            putJsonArray("fromIds") {
                                add(JsonPrimitive(uuid(6)))
                                add(JsonPrimitive(uuid(7)))
                            }
                            put("to", uuid(8))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("item", uuid(9))
                            put("rootId", uuid(10))
                            put("anchorId", uuid(11))
                            put("ancestorId", uuid(12))
                            putJsonArray("itemIds") { add(JsonPrimitive(uuid(13))) }
                        }
                    )
                }
            }
        val ids = idList(CallLogFields.targetIdsJson(args))
        assertEquals((1..13).map { uuid(it) }.toSet(), ids.toSet(), "every new key inside an element is recorded")
        assertEquals(13, ids.size, "distinct")
    }

    @Test
    fun `S9 probe - an 8-hex prefix or other non-UUID text under a new key is not a target id`() {
        for (key in newScalarKeys) {
            assertNull(CallLogFields.targetIdsJson(buildJsonObject { put(key, "0f8fad5b") }), "prefix under $key")
            assertNull(CallLogFields.targetIdsJson(buildJsonObject { put(key, "not-a-uuid") }), "text under $key")
        }
        for (key in newArrayKeys) {
            val args =
                buildJsonObject {
                    putJsonArray(key) {
                        add(JsonPrimitive("0f8fad5b"))
                        add(JsonPrimitive(uuid(1)))
                    }
                }
            assertEquals(listOf(uuid(1)), idList(CallLogFields.targetIdsJson(args)), "only the real UUID under $key")
        }
    }

    @Test
    fun `S9 probe - a UUID nested two levels deep under a new key is not scanned`() {
        val args =
            buildJsonObject {
                putJsonArray("ops") {
                    add(buildJsonObject { put("meta", buildJsonObject { put("from", uuid(1)) }) })
                    add(buildJsonObject { putJsonArray("nested") { add(buildJsonObject { put("to", uuid(2)) }) } })
                }
                put("wrapper", buildJsonObject { put("rootId", uuid(3)) })
            }
        assertNull(CallLogFields.targetIdsJson(args), "no top-level key and no top-level array element holds a target directly")
    }

    @Test
    fun `S9 probe - the same UUID under several keys is listed once`() {
        val args =
            buildJsonObject {
                put("from", uuid(1))
                put("item", uuid(1))
                put("rootId", uuid(1))
                putJsonArray("toIds") {
                    add(JsonPrimitive(uuid(1)))
                    add(JsonPrimitive(uuid(2)))
                }
            }
        assertEquals(listOf(uuid(1), uuid(2)), idList(CallLogFields.targetIdsJson(args)))
    }

    @Test
    fun `S9 probe - the cap of 50 still holds for the new keys`() {
        assertEquals(50, CallLogFields.MAX_TARGET_IDS)
        val args = buildJsonObject { putJsonArray("itemIds") { repeat(60) { add(JsonPrimitive(uuid(it))) } } }
        assertEquals((0 until 50).map { uuid(it) }, idList(CallLogFields.targetIdsJson(args)))
        val mixed =
            buildJsonObject {
                put("from", uuid(1000))
                putJsonArray("toIds") { repeat(60) { add(JsonPrimitive(uuid(it))) } }
            }
        val ids = idList(CallLogFields.targetIdsJson(mixed))
        assertEquals(50, ids.size)
        assertEquals(uuid(1000), ids.first(), "first-seen order: the scalar key was seen before the array")
    }

    @Test
    fun `S9 the extra ids overload also sees the new keys`() {
        val merged = idList(CallLogFields.targetIdsJson(buildJsonObject { put("from", uuid(1)) }, listOf(uuid(1), uuid(2))))
        assertEquals(setOf(uuid(1), uuid(2)), merged.toSet())
        assertEquals(2, merged.size)
    }

    // ------------------------------------------------------------------ request shape: multiRoot and fields

    private fun shape(json: String?): JsonObject = Json.parseToJsonElement(assertNotNull(json, "request_shape must not be null")).jsonObject

    @Test
    fun `multiRoot true is recorded as a boolean even when the arguments hold nothing else`() {
        val parsed = shape(CallLogFields.requestShapeJson(null, null, multiRoot = true))
        assertEquals(true, parsed["multiRoot"]?.jsonPrimitive?.booleanOrNull, "$parsed")
    }

    @Test
    fun `no multiRoot key when the call was not multi-root`() {
        val flagged = shape(CallLogFields.requestShapeJson(buildJsonObject { put("includeChildren", true) }, null, multiRoot = false))
        assertTrue("multiRoot" !in flagged, "$flagged")
        assertEquals(true, flagged["includeChildren"]?.jsonPrimitive?.booleanOrNull, "control: the client flag is still recorded")
    }

    @Test
    fun `fields are recorded as an array of the names`() {
        val parsed = shape(CallLogFields.requestShapeJson(null, null, fields = listOf("ancestors")))
        assertEquals(listOf("ancestors"), (parsed["fields"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `fields and multiRoot sit next to the client flags and the profile`() {
        val args = buildJsonObject { put("includeChildren", true) }
        val parsed =
            shape(CallLogFields.requestShapeJson(args, null, profile = "orchestration", fields = listOf("ancestors"), multiRoot = true))
        assertEquals("orchestration", parsed["profile"]?.jsonPrimitive?.content)
        assertEquals(listOf("ancestors"), parsed["fields"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(true, parsed["multiRoot"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, parsed["includeChildren"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `fields and multiRoot survive the 16 key cap that sorts them behind twenty client flags`() {
        val flags = buildJsonObject { for (i in 1..20) put("a" + i.toString().padStart(2, '0'), true) }
        val parsed =
            shape(CallLogFields.requestShapeJson(flags, null, profile = "orchestration", fields = listOf("ancestors"), multiRoot = true))
        assertEquals(listOf("ancestors"), parsed["fields"]?.jsonArray?.map { it.jsonPrimitive.content }, "$parsed")
        assertEquals(true, parsed["multiRoot"]?.jsonPrimitive?.booleanOrNull, "$parsed")
        assertEquals("orchestration", parsed["profile"]?.jsonPrimitive?.content, "$parsed")
    }

    @Test
    fun `a client key of the same name is replaced by the server value`() {
        val args =
            buildJsonObject {
                put("multiRoot", false)
                put("fields", true)
            }
        val parsed = shape(CallLogFields.requestShapeJson(args, null, fields = listOf("ancestors"), multiRoot = true))
        assertEquals(true, parsed["multiRoot"]?.jsonPrimitive?.booleanOrNull, "server multiRoot wins over a client false: $parsed")
        val fields = parsed.getValue("fields")
        assertTrue(fields is JsonArray, "server fields array wins over a client boolean: $parsed")
        assertEquals(listOf("ancestors"), fields.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `existing behavior - no fields no multiRoot and no flags still gives a null shape`() {
        assertNull(CallLogFields.requestShapeJson(buildJsonObject { put("title", "x") }, null))
        assertNull(CallLogFields.requestShapeJson(null, null))
        // an array holds no boolean and no numeric limit, so it is no shape either
        assertNull(CallLogFields.requestShapeJson(buildJsonObject { put("list", buildJsonArray { }) }, null))
    }
}

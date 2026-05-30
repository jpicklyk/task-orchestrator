package io.github.jpicklyk.mcptask.current.application.service.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [MergePatchApplier] covering the 10 RFC 7396 cases
 * required by the Phase 5 spec §5.4.1.
 */
class MergePatchApplierTest {
    // 1. Scalar overwrite: present string in patch replaces target value
    @Test
    fun `scalar overwrite replaces field value`() {
        val target = buildJsonObject { put("title", "Original") }
        val patch = buildJsonObject { put("title", "Updated") }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertEquals("Updated", result["title"]?.jsonPrimitive?.content)
    }

    // 2. Null delete: explicit null in patch removes the key
    @Test
    fun `null value in patch removes key from result`() {
        val target =
            buildJsonObject {
                put("title", "Title")
                put("description", "To Remove")
            }
        val patch = buildJsonObject { put("description", JsonNull) }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertTrue(result.containsKey("title"), "title should remain")
        assertFalse(result.containsKey("description"), "description should be removed by null patch")
    }

    // 3. Absent key in patch: field is left unchanged
    @Test
    fun `absent key in patch leaves field untouched`() {
        val target =
            buildJsonObject {
                put("title", "Keep")
                put("summary", "Also Keep")
            }
        val patch = buildJsonObject { put("title", "Changed") } // summary absent
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertEquals("Changed", result["title"]?.jsonPrimitive?.content)
        assertEquals("Also Keep", result["summary"]?.jsonPrimitive?.content, "absent key should be untouched")
    }

    // 4. Nested object merge: sub-objects merge recursively
    @Test
    fun `nested objects merge recursively`() {
        val target =
            buildJsonObject {
                put(
                    "nested",
                    buildJsonObject {
                        put("a", "1")
                        put("b", "2")
                    }
                )
            }
        val patch =
            buildJsonObject {
                put(
                    "nested",
                    buildJsonObject {
                        put("b", "updated")
                        put("c", "new")
                    }
                )
            }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        val nested = result["nested"]?.jsonObject!!
        assertEquals("1", nested["a"]?.jsonPrimitive?.content, "a should be preserved")
        assertEquals("updated", nested["b"]?.jsonPrimitive?.content, "b should be updated")
        assertEquals("new", nested["c"]?.jsonPrimitive?.content, "c should be added")
    }

    // 5. Array replace: arrays are replaced wholesale (not element-merged)
    @Test
    fun `array in patch replaces target array wholesale`() {
        val target = Json.parseToJsonElement("""{"items":["a","b","c"]}""").jsonObject
        val patch = Json.parseToJsonElement("""{"items":["x","y"]}""").jsonObject
        val result = MergePatchApplier.apply(target, patch).jsonObject
        val arr = result["items"]?.jsonArray!!
        assertEquals(2, arr.size, "array should be replaced wholesale")
        assertEquals("x", arr[0].jsonPrimitive.content)
        assertEquals("y", arr[1].jsonPrimitive.content)
    }

    // 6. Non-object patch value replaces target wholesale (RFC 7396 §2)
    @Test
    fun `non-object patch replaces entire target`() {
        val target = buildJsonObject { put("title", "old") }
        val patch = JsonPrimitive("new-scalar")
        val result = MergePatchApplier.apply(target, patch)
        assertEquals("new-scalar", (result as JsonPrimitive).content)
    }

    // 7. Null patch value when key absent: key simply does not appear in result
    @Test
    fun `null patch on absent key does not add key`() {
        val target = buildJsonObject { put("x", "1") }
        val patch = buildJsonObject { put("absent_key", JsonNull) }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertFalse(result.containsKey("absent_key"), "absent key nulled in patch should not appear")
        assertEquals("1", result["x"]?.jsonPrimitive?.content)
    }

    // 8. Empty patch leaves target unchanged
    @Test
    fun `empty patch object leaves target unchanged`() {
        val target =
            buildJsonObject {
                put("a", "1")
                put("b", "2")
            }
        val patch = JsonObject(emptyMap())
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertEquals(target, result)
    }

    // 9. Nested null removes sub-key
    @Test
    fun `null in nested patch removes sub-key`() {
        val target =
            buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("k1", "v1")
                        put("k2", "v2")
                    }
                )
            }
        val patch =
            buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("k1", JsonNull) // remove k1
                    }
                )
            }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        val meta = result["meta"]?.jsonObject!!
        assertFalse(meta.containsKey("k1"), "k1 should be removed")
        assertEquals("v2", meta["k2"]?.jsonPrimitive?.content, "k2 should remain")
    }

    // 10. Multiple simultaneous operations in one patch
    @Test
    fun `multiple operations in single patch apply correctly`() {
        val target =
            buildJsonObject {
                put("title", "old")
                put("description", "remove-me")
                put("summary", "keep-me")
            }
        val patch =
            buildJsonObject {
                put("title", "new")
                put("description", JsonNull) // delete
                // summary absent → unchanged
            }
        val result = MergePatchApplier.apply(target, patch).jsonObject
        assertEquals("new", result["title"]?.jsonPrimitive?.content)
        assertFalse(result.containsKey("description"), "description should be deleted")
        assertEquals("keep-me", result["summary"]?.jsonPrimitive?.content)
    }
}

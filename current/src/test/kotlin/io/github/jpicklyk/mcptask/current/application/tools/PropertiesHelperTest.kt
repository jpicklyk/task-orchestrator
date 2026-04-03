package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertiesHelperTest {

    // ────────────────────────────────────────────────────────
    // extractTraits
    // ────────────────────────────────────────────────────────

    @Test
    fun `extractTraits returns empty list for null properties`() {
        assertEquals(emptyList(), PropertiesHelper.extractTraits(null))
    }

    @Test
    fun `extractTraits returns empty list for empty string`() {
        assertEquals(emptyList(), PropertiesHelper.extractTraits(""))
    }

    @Test
    fun `extractTraits returns empty list for blank string`() {
        assertEquals(emptyList(), PropertiesHelper.extractTraits("   "))
    }

    @Test
    fun `extractTraits returns empty list for empty JSON object`() {
        assertEquals(emptyList(), PropertiesHelper.extractTraits("{}"))
    }

    @Test
    fun `extractTraits returns traits from valid JSON`() {
        val properties = """{"traits": ["a", "b"]}"""
        assertEquals(listOf("a", "b"), PropertiesHelper.extractTraits(properties))
    }

    @Test
    fun `extractTraits returns empty list when no traits key`() {
        val properties = """{"other": 1}"""
        assertEquals(emptyList(), PropertiesHelper.extractTraits(properties))
    }

    @Test
    fun `extractTraits returns empty list for malformed JSON`() {
        assertEquals(emptyList(), PropertiesHelper.extractTraits("not json"))
    }

    @Test
    fun `extractTraits returns empty list when traits value is not an array`() {
        // "traits" exists but is a string, not an array — jsonArray access throws
        val properties = """{"traits": "not-an-array"}"""
        assertEquals(emptyList(), PropertiesHelper.extractTraits(properties))
    }

    @Test
    fun `extractTraits returns empty list for empty traits array`() {
        val properties = """{"traits": []}"""
        assertEquals(emptyList(), PropertiesHelper.extractTraits(properties))
    }

    @Test
    fun `extractTraits preserves order of traits`() {
        val properties = """{"traits": ["z", "a", "m"]}"""
        assertEquals(listOf("z", "a", "m"), PropertiesHelper.extractTraits(properties))
    }

    // ────────────────────────────────────────────────────────
    // mergeTraits
    // ────────────────────────────────────────────────────────

    @Test
    fun `mergeTraits with null existing properties produces valid JSON with traits`() {
        val result = PropertiesHelper.mergeTraits(null, listOf("a"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a"), traits)
    }

    @Test
    fun `mergeTraits with empty object produces JSON with traits`() {
        val result = PropertiesHelper.mergeTraits("{}", listOf("a"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a"), traits)
    }

    @Test
    fun `mergeTraits preserves existing keys and adds traits`() {
        val existing = """{"other": 1}"""
        val result = PropertiesHelper.mergeTraits(existing, listOf("a"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a"), traits)
        assertTrue(json.containsKey("other"), "Should preserve 'other' key")
    }

    @Test
    fun `mergeTraits deduplicates trait names`() {
        val existing = """{"traits": ["a"]}"""
        val result = PropertiesHelper.mergeTraits(existing, listOf("a", "b"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b"), traits)
    }

    @Test
    fun `mergeTraits with duplicate input traits deduplicates them`() {
        val result = PropertiesHelper.mergeTraits(null, listOf("a", "a", "b"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b"), traits)
    }

    @Test
    fun `mergeTraits with empty traits list produces empty traits array`() {
        val result = PropertiesHelper.mergeTraits(null, emptyList())
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(emptyList(), traits)
    }

    @Test
    fun `mergeTraits recovers from malformed JSON and returns valid JSON with traits`() {
        val result = PropertiesHelper.mergeTraits("not json", listOf("a"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a"), traits)
    }

    @Test
    fun `mergeTraits replaces existing traits key entirely with new traits`() {
        val existing = """{"traits": ["old1", "old2"]}"""
        val result = PropertiesHelper.mergeTraits(existing, listOf("new1"))
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("new1"), traits)
    }

    @Test
    fun `mergeTraits preserves multiple existing keys`() {
        val existing = """{"key1": "val1", "key2": 42, "traits": ["old"]}"""
        val result = PropertiesHelper.mergeTraits(existing, listOf("new"))
        val json = Json.parseToJsonElement(result).jsonObject
        assertTrue(json.containsKey("key1"), "Should preserve key1")
        assertTrue(json.containsKey("key2"), "Should preserve key2")
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("new"), traits)
    }

    // ──────────────────────────────────────────────
    // mergeTraitsFromString
    // ──────────────────────────────────────────────

    @Test
    fun `mergeTraitsFromString returns null when both inputs are null`() {
        assertNull(PropertiesHelper.mergeTraitsFromString(null, null))
    }

    @Test
    fun `mergeTraitsFromString returns existing properties when traits string is null`() {
        val existing = """{"other": 1}"""
        assertEquals(existing, PropertiesHelper.mergeTraitsFromString(existing, null))
    }

    @Test
    fun `mergeTraitsFromString parses comma-separated traits into JSON`() {
        val result = PropertiesHelper.mergeTraitsFromString(null, "a,b,c")!!
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "c"), traits)
    }

    @Test
    fun `mergeTraitsFromString trims whitespace from trait names`() {
        val result = PropertiesHelper.mergeTraitsFromString(null, " a , b , c ")!!
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "c"), traits)
    }

    @Test
    fun `mergeTraitsFromString filters empty segments`() {
        val result = PropertiesHelper.mergeTraitsFromString(null, "a,,b, ,c")!!
        val json = Json.parseToJsonElement(result).jsonObject
        val traits = json["traits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "c"), traits)
    }

    @Test
    fun `mergeTraitsFromString preserves existing properties keys`() {
        val existing = """{"other": 42}"""
        val result = PropertiesHelper.mergeTraitsFromString(existing, "a")!!
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals("42", json["other"]!!.jsonPrimitive.content)
        assertEquals(listOf("a"), json["traits"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `mergeTraitsFromString with empty string produces empty traits array`() {
        val result = PropertiesHelper.mergeTraitsFromString(null, "")!!
        val json = Json.parseToJsonElement(result).jsonObject
        assertTrue(json["traits"]!!.jsonArray.isEmpty())
    }
}

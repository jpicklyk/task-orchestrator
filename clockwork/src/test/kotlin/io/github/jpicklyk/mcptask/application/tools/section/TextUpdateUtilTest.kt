package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.utils.TextUpdateUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextUpdateUtilTest {

    @Test
    fun `extractTextReplacements should return null for null input`() {
        val result = TextUpdateUtil.extractTextReplacements(JsonNull)
        assertNull(result)
    }

    @Test
    fun `extractTextReplacements should return null for input without textUpdates`() {
        val jsonObject = buildJsonObject {
            put("id", "some-id")
            put("content", "Some content")
        }
        val result = TextUpdateUtil.extractTextReplacements(jsonObject)
        assertNull(result)
    }

    @Test
    fun `extractTextReplacements should return null for empty textUpdates array`() {
        val jsonObject = buildJsonObject {
            put("id", "some-id")
            put("textUpdates", JsonArray(emptyList()))
        }
        val result = TextUpdateUtil.extractTextReplacements(jsonObject)
        assertNull(result)
    }

    @Test
    fun `extractTextReplacements should parse valid textUpdates`() {
        val jsonObject = buildJsonObject {
            put("id", "some-id")
            put(
                "textUpdates", JsonArray(
                    listOf(
                buildJsonObject {
                    put("oldText", "original text")
                    put("newText", "updated text")
                },
                buildJsonObject {
                    put("oldText", "another original")
                    put("newText", "another update")
                }
            )))
        }

        val result = TextUpdateUtil.extractTextReplacements(jsonObject)

        assertEquals(2, result?.size)
        assertEquals("original text", result?.get(0)?.oldText)
        assertEquals("updated text", result?.get(0)?.newText)
        assertEquals("another original", result?.get(1)?.oldText)
        assertEquals("another update", result?.get(1)?.newText)
    }

    @Test
    fun `applyReplacements should return original content when replacements list is empty`() {
        val content = "This is some content"
        val replacements = emptyList<TextUpdateUtil.TextReplacement>()

        val (updatedContent, success) = TextUpdateUtil.applyReplacements(content, replacements)

        assertEquals(content, updatedContent)
        assertTrue(success)
    }

    @Test
    fun `applyReplacements should replace text when found`() {
        val content = "This is some content with special text and more special text."
        val replacements = listOf(
            TextUpdateUtil.TextReplacement("special text", "replacement text")
        )

        val (updatedContent, success) = TextUpdateUtil.applyReplacements(content, replacements)

        assertEquals("This is some content with replacement text and more replacement text.", updatedContent)
        assertTrue(success)
    }

    @Test
    fun `applyReplacements should handle multiple replacements`() {
        val content = "This is some content with special text and more different content."
        val replacements = listOf(
            TextUpdateUtil.TextReplacement("special text", "replacement text"),
            TextUpdateUtil.TextReplacement("different content", "updated content")
        )

        val (updatedContent, success) = TextUpdateUtil.applyReplacements(content, replacements)

        assertEquals("This is some content with replacement text and more updated content.", updatedContent)
        assertTrue(success)
    }

    @Test
    fun `applyReplacements should fail if text not found`() {
        val content = "This is some content without the text we're looking for."
        val replacements = listOf(
            TextUpdateUtil.TextReplacement("non-existent text", "replacement text")
        )

        val (updatedContent, success) = TextUpdateUtil.applyReplacements(content, replacements)

        assertEquals(content, updatedContent) // Content should remain unchanged
        assertFalse(success)
    }

    @Test
    fun `applyReplacements should stop on first failure`() {
        val content = "This is some content with special text but without other text."
        val replacements = listOf(
            TextUpdateUtil.TextReplacement("special text", "replacement text"),
            TextUpdateUtil.TextReplacement("non-existent text", "replacement text")
        )

        val (updatedContent, success) = TextUpdateUtil.applyReplacements(content, replacements)

        assertEquals(content, updatedContent) // Content should remain unchanged
        assertFalse(success)
    }

    @Test
    fun `truncateContent should return original content if shorter than max length`() {
        val content = "Short content"
        val maxLength = 20

        val result = TextUpdateUtil.truncateContent(content, maxLength)

        assertEquals(content, result)
    }

    @Test
    fun `truncateContent should truncate content if longer than max length`() {
        val content = "This is a longer piece of content that should be truncated"
        val maxLength = 20

        val result = TextUpdateUtil.truncateContent(content, maxLength)

        // Check it starts with the first maxLength characters
        assertTrue(result.startsWith(content.substring(0, maxLength)))
        // Check it has ellipsis at the end
        assertTrue(result.endsWith("..."))
        // Check total length (maxLength + 3 for the ellipsis)
        assertEquals(maxLength + 3, result.length)
    }
}
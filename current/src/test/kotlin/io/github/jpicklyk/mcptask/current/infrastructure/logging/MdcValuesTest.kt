package io.github.jpicklyk.mcptask.current.infrastructure.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage of [MdcValues.bounded] — the O9 length cap on self-reported MDC values (item
 * b5081c9b task-scope, scenarios L11-L14). See [MdcValuesTest.bounded and end-to-end callers]
 * ([io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapterMdcTest],
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.RequestCorrelationTest]) for the
 * caller-side application of this cap.
 */
class MdcValuesTest {
    @Test
    fun `L11 - a value at exactly the max length is returned unchanged`() {
        val value = "a".repeat(128)
        assertEquals(value, MdcValues.bounded(value, max = 128))
    }

    @Test
    fun `L12 - one character over the max is truncated with the marker`() {
        val value = "a".repeat(129)
        val result = MdcValues.bounded(value, max = 128)
        assertEquals("a".repeat(128) + "...[truncated]", result)
        assertEquals(142, result.length)
    }

    @Test
    fun `L13 - a 10000-char value truncates to a fixed length of 142`() {
        val value = "x".repeat(10_000)
        val result = MdcValues.bounded(value, max = 128)
        assertEquals(142, result.length)
        assertTrue(result.startsWith("x".repeat(128)))
        assertTrue(result.endsWith("...[truncated]"))
    }

    @Test
    fun `L14 - a surrogate pair straddling the cut boundary is never split`() {
        // U+1F600 (an astral emoji) is a high+low surrogate pair. Place it so the pair would be
        // split exactly at index `max` if the cut were naive.
        val astral = "😀" // high surrogate + low surrogate
        val value = "a".repeat(127) + astral + "b".repeat(50)
        val result = MdcValues.bounded(value, max = 128)

        // The cut must land BEFORE the high surrogate at index 127, not between the surrogate
        // pair's two chars (which would produce an unpaired/invalid surrogate in the output).
        val truncatedPortion = result.removeSuffix("...[truncated]")
        assertEquals("a".repeat(127), truncatedPortion)
        assertTrue(!truncatedPortion.any { Character.isSurrogate(it) }, "No dangling surrogate half in the output")
    }

    @Test
    fun `a value shorter than max is returned unchanged`() {
        assertEquals("short", MdcValues.bounded("short", max = 128))
    }
}

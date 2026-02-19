package io.github.jpicklyk.mcptask.current.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleTest {

    // --- fromString ---

    @Test
    fun `fromString returns QUEUE for queue`() {
        assertEquals(Role.QUEUE, Role.fromString("queue"))
    }

    @Test
    fun `fromString returns WORK for work`() {
        assertEquals(Role.WORK, Role.fromString("work"))
    }

    @Test
    fun `fromString returns REVIEW for review`() {
        assertEquals(Role.REVIEW, Role.fromString("review"))
    }

    @Test
    fun `fromString returns BLOCKED for blocked`() {
        assertEquals(Role.BLOCKED, Role.fromString("blocked"))
    }

    @Test
    fun `fromString returns TERMINAL for terminal`() {
        assertEquals(Role.TERMINAL, Role.fromString("terminal"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(Role.QUEUE, Role.fromString("QUEUE"))
        assertEquals(Role.WORK, Role.fromString("Work"))
        assertEquals(Role.REVIEW, Role.fromString("REVIEW"))
        assertEquals(Role.BLOCKED, Role.fromString("Blocked"))
        assertEquals(Role.TERMINAL, Role.fromString("Terminal"))
    }

    @Test
    fun `fromString returns null for invalid value`() {
        assertNull(Role.fromString("invalid"))
        assertNull(Role.fromString(""))
        assertNull(Role.fromString("pending"))
        assertNull(Role.fromString("completed"))
    }

    // --- VALID_NAMES ---

    @Test
    fun `VALID_NAMES contains all lowercase role names`() {
        val expected = setOf("queue", "work", "review", "blocked", "terminal")
        assertEquals(expected, Role.VALID_NAMES)
    }

    @Test
    fun `VALID_NAMES has exactly 5 entries`() {
        assertEquals(5, Role.VALID_NAMES.size)
    }

    // --- PROGRESSION ---

    @Test
    fun `PROGRESSION is QUEUE WORK REVIEW TERMINAL`() {
        val expected = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)
        assertEquals(expected, Role.PROGRESSION)
    }

    @Test
    fun `PROGRESSION does not contain BLOCKED`() {
        assertFalse(Role.BLOCKED in Role.PROGRESSION)
    }

    @Test
    fun `PROGRESSION has exactly 4 entries`() {
        assertEquals(4, Role.PROGRESSION.size)
    }

    // --- isAtOrBeyond ---

    @Test
    fun `WORK is at or beyond QUEUE`() {
        assertTrue(Role.isAtOrBeyond(Role.WORK, Role.QUEUE))
    }

    @Test
    fun `QUEUE is not at or beyond WORK`() {
        assertFalse(Role.isAtOrBeyond(Role.QUEUE, Role.WORK))
    }

    @Test
    fun `TERMINAL is at or beyond anything in progression`() {
        assertTrue(Role.isAtOrBeyond(Role.TERMINAL, Role.QUEUE))
        assertTrue(Role.isAtOrBeyond(Role.TERMINAL, Role.WORK))
        assertTrue(Role.isAtOrBeyond(Role.TERMINAL, Role.REVIEW))
        assertTrue(Role.isAtOrBeyond(Role.TERMINAL, Role.TERMINAL))
    }

    @Test
    fun `BLOCKED is never at or beyond anything`() {
        assertFalse(Role.isAtOrBeyond(Role.BLOCKED, Role.QUEUE))
        assertFalse(Role.isAtOrBeyond(Role.BLOCKED, Role.WORK))
        assertFalse(Role.isAtOrBeyond(Role.BLOCKED, Role.REVIEW))
        assertFalse(Role.isAtOrBeyond(Role.BLOCKED, Role.TERMINAL))
    }

    @Test
    fun `same role is at or beyond itself`() {
        assertTrue(Role.isAtOrBeyond(Role.QUEUE, Role.QUEUE))
        assertTrue(Role.isAtOrBeyond(Role.WORK, Role.WORK))
        assertTrue(Role.isAtOrBeyond(Role.REVIEW, Role.REVIEW))
        assertTrue(Role.isAtOrBeyond(Role.TERMINAL, Role.TERMINAL))
    }

    @Test
    fun `REVIEW is at or beyond QUEUE`() {
        assertTrue(Role.isAtOrBeyond(Role.REVIEW, Role.QUEUE))
    }

    @Test
    fun `REVIEW is at or beyond WORK`() {
        assertTrue(Role.isAtOrBeyond(Role.REVIEW, Role.WORK))
    }

    @Test
    fun `QUEUE is not at or beyond REVIEW`() {
        assertFalse(Role.isAtOrBeyond(Role.QUEUE, Role.REVIEW))
    }

    @Test
    fun `isAtOrBeyond returns false when threshold is BLOCKED`() {
        // BLOCKED is not in PROGRESSION so thresholdIndex = -1
        assertFalse(Role.isAtOrBeyond(Role.WORK, Role.BLOCKED))
    }
}

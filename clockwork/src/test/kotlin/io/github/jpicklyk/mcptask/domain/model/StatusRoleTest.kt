package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusRoleTest {

    // ========== fromString tests ==========

    @Test
    fun `fromString with lowercase queue returns QUEUE`() {
        assertEquals(StatusRole.QUEUE, StatusRole.fromString("queue"))
    }

    @Test
    fun `fromString with uppercase WORK returns WORK`() {
        assertEquals(StatusRole.WORK, StatusRole.fromString("WORK"))
    }

    @Test
    fun `fromString with mixed case Review returns REVIEW`() {
        assertEquals(StatusRole.REVIEW, StatusRole.fromString("Review"))
    }

    @Test
    fun `fromString with terminal returns TERMINAL`() {
        assertEquals(StatusRole.TERMINAL, StatusRole.fromString("terminal"))
    }

    @Test
    fun `fromString with blocked returns BLOCKED`() {
        assertEquals(StatusRole.BLOCKED, StatusRole.fromString("blocked"))
    }

    @Test
    fun `fromString with invalid string returns null`() {
        assertNull(StatusRole.fromString("invalid"))
    }

    @Test
    fun `fromString with empty string returns null`() {
        assertNull(StatusRole.fromString(""))
    }

    // ========== isRoleAtOrBeyond (enum overload) tests ==========

    @Test
    fun `QUEUE is at or beyond QUEUE`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.QUEUE, StatusRole.QUEUE))
    }

    @Test
    fun `WORK is at or beyond QUEUE`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.WORK, StatusRole.QUEUE))
    }

    @Test
    fun `QUEUE is not at or beyond WORK`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.QUEUE, StatusRole.WORK))
    }

    @Test
    fun `TERMINAL is at or beyond QUEUE`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.TERMINAL, StatusRole.QUEUE))
    }

    @Test
    fun `TERMINAL is at or beyond WORK`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.TERMINAL, StatusRole.WORK))
    }

    @Test
    fun `TERMINAL is at or beyond REVIEW`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.TERMINAL, StatusRole.REVIEW))
    }

    @Test
    fun `TERMINAL is at or beyond TERMINAL`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.TERMINAL, StatusRole.TERMINAL))
    }

    @Test
    fun `REVIEW is at or beyond WORK`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.REVIEW, StatusRole.WORK))
    }

    @Test
    fun `REVIEW is not at or beyond TERMINAL`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.REVIEW, StatusRole.TERMINAL))
    }

    @Test
    fun `BLOCKED never satisfies QUEUE threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.BLOCKED, StatusRole.QUEUE))
    }

    @Test
    fun `BLOCKED never satisfies WORK threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.BLOCKED, StatusRole.WORK))
    }

    @Test
    fun `BLOCKED never satisfies TERMINAL threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.BLOCKED, StatusRole.TERMINAL))
    }

    @Test
    fun `BLOCKED satisfies BLOCKED threshold`() {
        assertTrue(StatusRole.isRoleAtOrBeyond(StatusRole.BLOCKED, StatusRole.BLOCKED))
    }

    @Test
    fun `WORK does not satisfy BLOCKED threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.WORK, StatusRole.BLOCKED))
    }

    @Test
    fun `TERMINAL does not satisfy BLOCKED threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.TERMINAL, StatusRole.BLOCKED))
    }

    @Test
    fun `QUEUE does not satisfy BLOCKED threshold`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(StatusRole.QUEUE, StatusRole.BLOCKED))
    }

    // ========== isRoleAtOrBeyond (string overload) tests ==========

    @Test
    fun `string overload with valid roles works`() {
        assertTrue(StatusRole.isRoleAtOrBeyond("work", "queue"))
    }

    @Test
    fun `string overload with null current returns false`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(null, "queue"))
    }

    @Test
    fun `string overload with null threshold returns false`() {
        assertFalse(StatusRole.isRoleAtOrBeyond("work", null))
    }

    @Test
    fun `string overload with both null returns false`() {
        assertFalse(StatusRole.isRoleAtOrBeyond(null as String?, null as String?))
    }

    @Test
    fun `string overload with invalid current returns false`() {
        assertFalse(StatusRole.isRoleAtOrBeyond("invalid", "queue"))
    }

    @Test
    fun `string overload with invalid threshold returns false`() {
        assertFalse(StatusRole.isRoleAtOrBeyond("work", "invalid"))
    }

    @Test
    fun `string overload is case insensitive`() {
        assertTrue(StatusRole.isRoleAtOrBeyond("TERMINAL", "queue"))
    }

    // ========== VALID_ROLE_NAMES tests ==========

    @Test
    fun `VALID_ROLE_NAMES contains all five roles`() {
        assertEquals(5, StatusRole.VALID_ROLE_NAMES.size)
        assertTrue(StatusRole.VALID_ROLE_NAMES.contains("queue"))
        assertTrue(StatusRole.VALID_ROLE_NAMES.contains("work"))
        assertTrue(StatusRole.VALID_ROLE_NAMES.contains("review"))
        assertTrue(StatusRole.VALID_ROLE_NAMES.contains("terminal"))
        assertTrue(StatusRole.VALID_ROLE_NAMES.contains("blocked"))
    }

    @Test
    fun `VALID_ROLE_NAMES are all lowercase`() {
        for (roleName in StatusRole.VALID_ROLE_NAMES) {
            assertEquals(roleName.lowercase(), roleName, "Role name '$roleName' should be lowercase")
        }
    }

    // ========== ordinal_ tests ==========

    @Test
    fun `ordinal values are correct`() {
        assertEquals(0, StatusRole.QUEUE.ordinal_)
        assertEquals(1, StatusRole.WORK.ordinal_)
        assertEquals(2, StatusRole.REVIEW.ordinal_)
        assertEquals(3, StatusRole.TERMINAL.ordinal_)
        assertEquals(-1, StatusRole.BLOCKED.ordinal_)
    }
}

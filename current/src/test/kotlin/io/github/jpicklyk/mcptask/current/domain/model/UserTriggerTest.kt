package io.github.jpicklyk.mcptask.current.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserTriggerTest {
    // --- fromString ---

    @Test
    fun `fromString returns START for start`() {
        assertEquals(UserTrigger.START, UserTrigger.fromString("start"))
    }

    @Test
    fun `fromString returns COMPLETE for complete`() {
        assertEquals(UserTrigger.COMPLETE, UserTrigger.fromString("complete"))
    }

    @Test
    fun `fromString returns BLOCK for block`() {
        assertEquals(UserTrigger.BLOCK, UserTrigger.fromString("block"))
    }

    @Test
    fun `fromString returns HOLD for hold`() {
        assertEquals(UserTrigger.HOLD, UserTrigger.fromString("hold"))
    }

    @Test
    fun `fromString returns RESUME for resume`() {
        assertEquals(UserTrigger.RESUME, UserTrigger.fromString("resume"))
    }

    @Test
    fun `fromString returns CANCEL for cancel`() {
        assertEquals(UserTrigger.CANCEL, UserTrigger.fromString("cancel"))
    }

    @Test
    fun `fromString returns REOPEN for reopen`() {
        assertEquals(UserTrigger.REOPEN, UserTrigger.fromString("reopen"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(UserTrigger.START, UserTrigger.fromString("START"))
        assertEquals(UserTrigger.COMPLETE, UserTrigger.fromString("Complete"))
        assertEquals(UserTrigger.CANCEL, UserTrigger.fromString("CANCEL"))
    }

    @Test
    fun `fromString returns null for cascade (system-internal trigger)`() {
        // cascade is intentionally excluded from UserTrigger — it must never be reachable
        // from the public advance_item API
        assertNull(UserTrigger.fromString("cascade"))
    }

    @Test
    fun `fromString returns null for unknown string`() {
        assertNull(UserTrigger.fromString("unknown"))
        assertNull(UserTrigger.fromString(""))
        assertNull(UserTrigger.fromString("advance"))
    }

    @Test
    fun `triggerString matches expected lowercase values`() {
        assertEquals("start", UserTrigger.START.triggerString)
        assertEquals("complete", UserTrigger.COMPLETE.triggerString)
        assertEquals("block", UserTrigger.BLOCK.triggerString)
        assertEquals("hold", UserTrigger.HOLD.triggerString)
        assertEquals("resume", UserTrigger.RESUME.triggerString)
        assertEquals("cancel", UserTrigger.CANCEL.triggerString)
        assertEquals("reopen", UserTrigger.REOPEN.triggerString)
    }

    @Test
    fun `UserTrigger has exactly 7 entries`() {
        assertEquals(7, UserTrigger.entries.size)
    }

    @Test
    fun `all entries roundtrip through fromString`() {
        for (trigger in UserTrigger.entries) {
            val parsed = UserTrigger.fromString(trigger.triggerString)
            assertNotNull(parsed, "fromString(${trigger.triggerString}) returned null")
            assertEquals(trigger, parsed)
        }
    }
}

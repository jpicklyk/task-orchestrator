package io.github.jpicklyk.mcptask.current.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LifecycleModeTest {

    // --- fromString happy paths ---

    @Test
    fun `fromString returns AUTO for 'auto'`() {
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("auto"))
    }

    @Test
    fun `fromString returns MANUAL for 'manual'`() {
        assertEquals(LifecycleMode.MANUAL, LifecycleMode.fromString("manual"))
    }

    @Test
    fun `fromString returns AUTO_REOPEN for 'auto-reopen'`() {
        assertEquals(LifecycleMode.AUTO_REOPEN, LifecycleMode.fromString("auto-reopen"))
    }

    @Test
    fun `fromString returns AUTO_REOPEN for 'AUTO_REOPEN'`() {
        assertEquals(LifecycleMode.AUTO_REOPEN, LifecycleMode.fromString("AUTO_REOPEN"))
    }

    @Test
    fun `fromString returns PERMANENT for 'permanent'`() {
        assertEquals(LifecycleMode.PERMANENT, LifecycleMode.fromString("permanent"))
    }

    // --- case insensitivity ---

    @Test
    fun `fromString is case-insensitive for AUTO`() {
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("Auto"))
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("AUTO"))
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("aUtO"))
    }

    @Test
    fun `fromString is case-insensitive for MANUAL`() {
        assertEquals(LifecycleMode.MANUAL, LifecycleMode.fromString("MANUAL"))
        assertEquals(LifecycleMode.MANUAL, LifecycleMode.fromString("Manual"))
    }

    // --- hyphen-to-underscore normalization ---

    @Test
    fun `fromString handles hyphen in auto-reopen`() {
        assertEquals(LifecycleMode.AUTO_REOPEN, LifecycleMode.fromString("auto-reopen"))
    }

    @Test
    fun `fromString handles mixed case with hyphen`() {
        assertEquals(LifecycleMode.AUTO_REOPEN, LifecycleMode.fromString("Auto-Reopen"))
    }

    // --- leading/trailing whitespace ---

    @Test
    fun `fromString trims whitespace`() {
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("  auto  "))
    }

    // --- unknown values ---

    @Test
    fun `fromString returns null for unknown value`() {
        assertNull(LifecycleMode.fromString("unknown"))
    }

    @Test
    fun `fromString returns null for empty string`() {
        assertNull(LifecycleMode.fromString(""))
    }

    @Test
    fun `fromString returns null for blank string`() {
        assertNull(LifecycleMode.fromString("   "))
    }
}

package io.github.jpicklyk.mcptask.current.application.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item b9527ef9) for [PrincipalKind.parse] and the wire vocabularies of [PrincipalKind] and
 * [ProofStatus] (scenarios S2, S3 and the S3 casing/whitespace probes of the frozen test-plan; NEW-SURFACE).
 *
 * Oracles: task-scope decision D2 (`PrincipalKind.parse` is trim plus case-insensitive; an unknown value is `null`), the
 * task-scope item 1 declaration of the three kinds and the four proof statuses with their lowercase wire spellings
 * (`human`, `agent`, `service`; `verified`, `verified_stale`, `issued`, `none`), and the R10 plan paragraph (kind
 * `{human, agent, service}`, proofStatus `{verified, verified_stale, issued, none}`). Expected values are literals.
 *
 * Negative-assertion conditions: every `null` expectation below fails if `parse` accepted the input (for example by prefix
 * matching, by `contains`, or by defaulting to SERVICE), because the same file also asserts the accepted forms next to them.
 *
 * Domain invariants satisfied by construction: no fixture is built; the inputs are plain strings.
 */
class PrincipalKindParseTest {
    @Test
    fun `S2 each lowercase wire value parses to its kind`() {
        assertEquals(PrincipalKind.HUMAN, PrincipalKind.parse("human"))
        assertEquals(PrincipalKind.AGENT, PrincipalKind.parse("agent"))
        assertEquals(PrincipalKind.SERVICE, PrincipalKind.parse("service"))
    }

    @Test
    fun `S3 parsing ignores case`() {
        assertEquals(PrincipalKind.HUMAN, PrincipalKind.parse("HUMAN"))
        assertEquals(PrincipalKind.HUMAN, PrincipalKind.parse("Human"))
        assertEquals(PrincipalKind.AGENT, PrincipalKind.parse("aGeNt"))
        assertEquals(PrincipalKind.SERVICE, PrincipalKind.parse("SERVICE"))
    }

    @Test
    fun `S3 parsing trims surrounding whitespace`() {
        assertEquals(PrincipalKind.HUMAN, PrincipalKind.parse(" Human "))
        assertEquals(PrincipalKind.AGENT, PrincipalKind.parse(" \t Agent \n"))
        assertEquals(PrincipalKind.SERVICE, PrincipalKind.parse("\nservice"))
    }

    @Test
    fun `S3 an unknown or malformed value is null, never a default kind`() {
        val rejected =
            listOf(
                "robot",
                "humans",
                "hum an",
                "human agent",
                "human,agent",
                "agent;service",
                "serv",
                "ser vice",
                "user",
                "orchestrator",
                "subagent",
                "external",
                "0",
                "null",
            )
        for (raw in rejected) {
            assertNull(PrincipalKind.parse(raw), "'$raw' is not one of human, agent, service")
        }
    }

    @Test
    fun `S3 probe empty and blank strings are null`() {
        assertNull(PrincipalKind.parse(""))
        assertNull(PrincipalKind.parse(" "))
        assertNull(PrincipalKind.parse("\t\n"))
    }

    @Test
    fun `S3 probe an embedded control character makes the value unknown`() {
        // a NUL inside the word is never whitespace at the ends, so no trim can remove it (a trailing NUL is left unasserted: no oracle)
        assertNull(PrincipalKind.parse("hu\u0000man"))
    }

    @Test
    fun `the kind vocabulary is exactly human, agent and service with those wire spellings`() {
        assertEquals(listOf("human", "agent", "service"), PrincipalKind.entries.map { it.wire })
        assertEquals(setOf("HUMAN", "AGENT", "SERVICE"), PrincipalKind.entries.map { it.name }.toSet())
    }

    @Test
    fun `the proof vocabulary is exactly verified, verified_stale, issued and none with those wire spellings`() {
        assertEquals(listOf("verified", "verified_stale", "issued", "none"), ProofStatus.entries.map { it.wire })
        assertEquals(setOf("VERIFIED", "VERIFIED_STALE", "ISSUED", "NONE"), ProofStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun `every kind parses back from its own wire spelling`() {
        for (kind in PrincipalKind.entries) {
            assertEquals(kind, PrincipalKind.parse(kind.wire), "round trip of ${kind.name}")
        }
    }
}

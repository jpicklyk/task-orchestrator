package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.BindOutcome
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpSessionPrincipals
import io.github.jpicklyk.mcptask.current.interfaces.mcp.fakeSdkSessions
import io.github.jpicklyk.mcptask.current.test.SettableClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Independent tests (test-author seat, item f98690c9) for the single factory [newSessionPrincipals] that builds the
 * [McpSessionPrincipals] of both production sites: scenario S4 of the frozen test-plan.
 *
 * Oracles (frozen diagnosis "Fix" and test-plan S4; none read from an implementation): the idle TTL of the principal store is
 * `appConfig.mcpSessionIdleTimeout` (so it follows the SDK's session idle timeout and the two never disagree); a binding idle
 * for exactly its TTL is ended on the next bind and one idle for TTL minus 1 ms is kept (the boundary rule of the sibling
 * `McpSessionEvictionTest` S8: "idle exactly idleTtl is evicted"); the principal store's own default TTL is 2 hours.
 *
 * Negative-assertion conditions (what makes each fail without the feature): "ended at exactly 10 min" fails if the factory
 * ignores the config (a 2 h or 24 h TTL keeps the binding) or the comparison is `>` instead of `>=`; "not ended at 10 min - 1 ms"
 * fails if the TTL is shorter than the configured timeout; the 2 h pair fails if the default TTL is anything else.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: principals are plain values; every session id handed to the fake is added to its
 * live set before it is bound; the TTL is positive.
 */
class SessionPrincipalsWiringTest {
    private val principal =
        Principal(
            "wired",
            PrincipalKind.AGENT,
            ProofStatus.ISSUED,
            AccessScope.unrestricted(),
            setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
        )

    @Test
    fun `S4 the principal store default idle TTL is 2 hours`() {
        assertEquals(Duration.ofHours(2), McpSessionPrincipals.DEFAULT_IDLE_TTL)
        assertEquals(1024, McpSessionPrincipals.DEFAULT_MAX_SESSIONS)
    }

    @Test
    fun `S4 newSessionPrincipals ends a binding idle for exactly the configured timeout and keeps one idle a millisecond less`() {
        val timeout = 10.minutes
        val config = AppConfig.fromEnv { null }.copy(mcpSessionIdleTimeout = timeout)

        runBlocking {
            val keptFake = fakeSdkSessions("s1", "s2")
            val keptClock = SettableClock()
            val kept = newSessionPrincipals(config, keptFake, keptClock)
            assertEquals(BindOutcome.BOUND, kept.bind("s1", principal))
            keptClock.advance(timeout.toJavaDuration().minusMillis(1))
            assertEquals(BindOutcome.BOUND, kept.bind("s2", principal))
            assertTrue(keptFake.ended.isEmpty(), "10 min - 1 ms idle: s1 is inside the window and nothing was ended")
            assertEquals("wired", kept.principalOf("s1")?.id)

            val endedFake = fakeSdkSessions("s1", "s2")
            val endedClock = SettableClock()
            val expired = newSessionPrincipals(config, endedFake, endedClock)
            assertEquals(BindOutcome.BOUND, expired.bind("s1", principal))
            endedClock.advance(timeout.toJavaDuration())
            assertEquals(BindOutcome.BOUND, expired.bind("s2", principal))
            assertEquals(listOf("s1"), endedFake.ended.toList(), "idle exactly 10 min: s1 is ended on the next bind")
            assertNull(expired.principalOf("s1"))
            assertEquals("wired", expired.principalOf("s2")?.id)
        }
    }

    @Test
    fun `S4 newSessionPrincipals with the default config keeps a binding for 2 hours minus a millisecond and ends it at 2 hours`() {
        val config = AppConfig.fromEnv { null }

        runBlocking {
            val keptFake = fakeSdkSessions("s1", "s2")
            val keptClock = SettableClock()
            val kept = newSessionPrincipals(config, keptFake, keptClock)
            assertEquals(BindOutcome.BOUND, kept.bind("s1", principal))
            keptClock.advance(Duration.ofHours(2).minusMillis(1))
            assertEquals(BindOutcome.BOUND, kept.bind("s2", principal))
            assertTrue(keptFake.ended.isEmpty(), "2 h - 1 ms idle is inside the default window")

            val endedFake = fakeSdkSessions("s1", "s2")
            val endedClock = SettableClock()
            val expired = newSessionPrincipals(config, endedFake, endedClock)
            assertEquals(BindOutcome.BOUND, expired.bind("s1", principal))
            endedClock.advance(Duration.ofHours(2))
            assertEquals(BindOutcome.BOUND, expired.bind("s2", principal))
            assertEquals(listOf("s1"), endedFake.ended.toList(), "2 h exactly is outside it")
        }
    }
}

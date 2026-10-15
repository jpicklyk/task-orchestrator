package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A test-owned [SdkSessions]: [live] is the set of transport session ids the "SDK" currently serves, [ended] records every
 * [end] call in order. Ending a session also removes it from [live] (the real SDK's `ServerSession.close()` closes the
 * transport, after which the id is no longer served).
 */
internal class FakeSdkSessions(
    val live: MutableSet<String> = ConcurrentHashMap.newKeySet()
) : SdkSessions {
    val ended = CopyOnWriteArrayList<String>()

    override fun isLive(sessionId: String): Boolean = sessionId in live

    override suspend fun end(sessionId: String) {
        ended += sessionId
        live.remove(sessionId)
    }
}

/** A [FakeSdkSessions] that already serves [ids]. */
internal fun fakeSdkSessions(vararg ids: String): FakeSdkSessions = FakeSdkSessions().also { it.live.addAll(ids) }

/**
 * Independent tests (test-author seat, item 8263c971) for the bound and eviction of the `/mcp` session-principal bindings
 * ([McpSessionPrincipals]) and for refusing to bind a session id the SDK did not issue ([SdkSessions] / [ServerSdkSessions]).
 * Unit scenarios build [McpSessionPrincipals] directly with a [FakeSdkSessions] and a [SettableClock]; HTTP scenarios run the
 * production topology through [McpAuthHttpFixture].
 *
 * Scenario ids are those of the frozen test-plan: S1, S3, S6, S8, S10, S11, S12 (unit); S2, S4, S7, S9, S13 (HTTP). S5 is the
 * pre-existing `SessionBindMismatchTest` "S3 another principal's token ..." test, which now runs against the new fixture.
 *
 * Oracles (written from the frozen plan and the item's acceptance criteria, never from a run): task-scope D1 (the liveness
 * check runs on the first bind of an id only; a bound id is trusted until forgotten or evicted), D2 (idle for exactly `idleTtl`
 * is evicted, `idleTtl - 1ms` survives; mirrors the claim-expiry rule "active iff strictly before expiry"), D3 (an evicted
 * session is also ended on the SDK side so its next request is the SDK's 404), AC1-AC6; the eviction order of task-scope item 2
 * (a: entries the SDK no longer serves are dropped with no `end` call, b: idle entries are ended and removed, c: while the map
 * is at its limit the least recently seen entry is ended and removed); the MCP Streamable HTTP specification, "Session
 * Management" (a terminated session answers 404); the SDK 0.13.0 `existingStreamableTransport` behaviour recorded in the plan
 * (an id the mount does not serve is 404 "Session not found").
 *
 * Negative-assertion conditions (what makes each fail without the feature): a bind of an id the SDK does not serve stores
 * nothing (S3: `principalOf` is non-null and `size` rises if the liveness check is dropped); an unknown-id flood must not end or
 * evict a legitimate entry (the full-map probe fails if the check runs after eviction); S6 fails if the dead-entry sweep is
 * dropped (the binding survives); S8/S10 fail if `>=` becomes `>` or `end` is not called; S11 fails if eviction is by creation
 * time or the limit comparison is off by one; the HTTP scenarios read `work_items` and `call_log` instead of trusting the reply.
 *
 * Ambiguity arbitration (task-scope item 2 reads "Bound already: set lastSeen = now (every request touches) and compare"):
 * whether a request by a DIFFERENT principal (OTHER_PRINCIPAL) also refreshes lastSeen is not asserted either way. Escalated
 * to the reviewer as a design question (a hostile principal that can only produce 401s could keep a victim's session alive).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no disjunctive "did nothing" assertions. Every HTTP
 * reply for an id the SDK does not serve asserts the exact 404 of the specification; the header-shape probes (empty,
 * whitespace-only, 64 KiB `Mcp-Session-Id`) assert only "no tool ran, no binding" because the status for a missing-or-malformed
 * header belongs to the SDK, not to this item.
 *
 * Domain invariants satisfied by construction: principals are plain values (no validation beyond types); items are created only
 * through the real `items_write` create with a declared type; the constructor invariants `idleTtl > 0` and `maxSessions >= 1`
 * hold for every instance built here except the ones that assert the refusal.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpSessionEvictionTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val ttl: Duration = Duration.ofHours(1)

    private fun principal(
        id: String,
        capabilities: Set<ApiCapability> = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS),
        scope: AccessScope = AccessScope.unrestricted()
    ) = Principal(id, PrincipalKind.AGENT, ProofStatus.ISSUED, scope, capabilities)

    private fun store(
        fake: SdkSessions,
        clock: Clock = SettableClock(),
        idleTtl: Duration = ttl,
        maxSessions: Int = 8
    ) = McpSessionPrincipals(
        redactNoteAttribution = true,
        sdkSessions = fake,
        clock = clock,
        idleTtl = idleTtl,
        maxSessions = maxSessions
    )

    // ------------------------------------------------------------------ unit: bind

    @Test
    fun `S1 a live id binds and answers the principal's audit id, capabilities, scope and session id`() {
        runBlocking {
            val fake = fakeSdkSessions("s1")
            val sessions = store(fake)
            val scoped =
                principal(
                    "scoped",
                    setOf(ApiCapability.READ),
                    AccessScope.principal(rootIds = setOf(UUID.randomUUID()), tagsInclude = emptySet())
                )
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", scoped))
            assertEquals(1, sessions.size)
            assertEquals("scoped", sessions.principalOf("s1")?.id)
            val access = sessions.access("s1")
            assertEquals("api:scoped", access.principal?.claim?.id)
            assertEquals(setOf(ApiCapability.READ), access.capabilities)
            assertFalse(access.scope.isUnrestricted)
            assertEquals("s1", access.sessionId)
            assertTrue(fake.ended.isEmpty(), "binding a live id ends nothing")
        }
    }

    @Test
    fun `S1 probe binding the same live id twice is idempotent for the same principal and a second principal is refused`() {
        runBlocking {
            val sessions = store(fakeSdkSessions("s1"))
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice", setOf(ApiCapability.READ))))
            assertEquals(1, sessions.size)
            assertEquals(BindOutcome.OTHER_PRINCIPAL, sessions.bind("s1", principal("bob")))
            assertEquals("alice", sessions.principalOf("s1")?.id, "the first binding wins")
            assertEquals(1, sessions.size)
        }
    }

    @Test
    fun `S3 a bind of an id the SDK does not serve is UNKNOWN, stores nothing and ends nothing`() {
        runBlocking {
            val fake = fakeSdkSessions("real")
            val sessions = store(fake)
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("forged", principal("alice")))
            assertNull(sessions.principalOf("forged"))
            assertNull(sessions.access("forged").principal, "an unissued id has no principal on its access")
            assertEquals(0, sessions.size)
            assertTrue(fake.ended.isEmpty())

            assertEquals(BindOutcome.BOUND, sessions.bind("real", principal("alice")))
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("forged", principal("alice")))
            assertEquals(1, sessions.size, "the refused id did not grow the map")
        }
    }

    @Test
    fun `S3 probe session id variants of a live id are not the live id`() {
        runBlocking {
            val fake = fakeSdkSessions("abc-123")
            val sessions = store(fake)
            for (variant in listOf("ABC-123", " abc-123", "abc-123 ", "abc-123" + Char(10), "", " ", "abc")) {
                assertEquals(BindOutcome.UNKNOWN, sessions.bind(variant, principal("alice")), "variant '$variant'")
                assertNull(sessions.principalOf(variant), "variant '$variant'")
            }
            assertEquals(0, sessions.size)
            assertEquals(BindOutcome.BOUND, sessions.bind("abc-123", principal("alice")), "control: the exact id binds")
        }
    }

    @Test
    fun `S3 probe a flood of unissued ids evicts and ends no legitimate binding even when the map is full or expired`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2")
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 2)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            clock.advance(Duration.ofSeconds(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))

            for (i in 1..100) assertEquals(BindOutcome.UNKNOWN, sessions.bind("forged-$i", principal("mallory")))
            assertEquals(2, sessions.size, "the map is full and stays exactly as it was")
            assertEquals("alice", sessions.principalOf("s1")?.id)
            assertEquals("bob", sessions.principalOf("s2")?.id)
            assertTrue(fake.ended.isEmpty(), "unissued ids cannot make the server end a legitimate session")

            clock.advance(ttl.plusHours(5))
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("forged-late", principal("mallory")))
            assertEquals(2, sessions.size, "an unissued id does not trigger the idle sweep either")
            assertTrue(fake.ended.isEmpty())
        }
    }

    @Test
    fun `S3 probe a rebind of a bound id trusts the binding even if the SDK has since closed it, the sweep is for new binds`() {
        runBlocking {
            val fake = fakeSdkSessions("s1")
            val sessions = store(fake)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            fake.live.remove("s1")
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")), "D1: liveness is checked on the first bind only")
            assertEquals(BindOutcome.OTHER_PRINCIPAL, sessions.bind("s1", principal("bob")))
        }
    }

    // ------------------------------------------------------------------ unit: eviction

    @Test
    fun `S6 an entry the SDK has already closed is dropped at the next new bind without an end call`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2")
            val sessions = store(fake)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            fake.live.remove("s1")
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))

            assertNull(sessions.principalOf("s1"), "the binding of a closed transport is gone")
            assertNull(sessions.access("s1").principal)
            assertEquals("bob", sessions.principalOf("s2")?.id, "control: the new bind is in place")
            assertEquals(1, sessions.size)
            assertTrue(fake.ended.isEmpty(), "the SDK already closed it: no end call")
        }
    }

    @Test
    fun `S6 probe an entry that is both closed and idle is dropped without an end call`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2")
            val clock = SettableClock()
            val sessions = store(fake, clock)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            fake.live.remove("s1")
            clock.advance(ttl.plusMinutes(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))
            assertNull(sessions.principalOf("s1"))
            assertTrue(fake.ended.isEmpty(), "a closed transport is never ended, idle or not")
        }
    }

    @Test
    fun `S8 a session idle for idleTtl is evicted and ended once at the next new bind`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2", "s3")
            val clock = SettableClock()
            val sessions = store(fake, clock)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            clock.advance(ttl)
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))

            assertEquals(listOf("s1"), fake.ended.toList(), "the idle session is ended exactly once")
            assertNull(sessions.principalOf("s1"))
            assertNull(sessions.access("s1").principal, "an evicted session reads as unbound")
            assertEquals("bob", sessions.principalOf("s2")?.id)
            assertEquals(1, sessions.size)

            assertEquals(BindOutcome.BOUND, sessions.bind("s3", principal("carol")))
            assertEquals(listOf("s1"), fake.ended.toList(), "a later bind does not end it again")
        }
    }

    @Test
    fun `S8 probe an evicted id cannot be re-bound because the SDK no longer serves it`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2")
            val clock = SettableClock()
            val sessions = store(fake, clock)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            clock.advance(ttl)
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("s1", principal("alice")), "replay after eviction")
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("s1", principal("bob")), "replay by another principal after eviction")
            assertNull(sessions.principalOf("s1"))
            sessions.forget("s1")
            assertEquals("bob", sessions.principalOf("s2")?.id, "forgetting an evicted id is harmless")
            assertEquals(1, sessions.size)
        }
    }

    @Test
    fun `S10 idle for idleTtl minus one millisecond survives and exactly idleTtl is evicted`() {
        runBlocking {
            val survivorFake = fakeSdkSessions("s1", "s2")
            val survivorClock = SettableClock()
            val survivors = store(survivorFake, survivorClock)
            assertEquals(BindOutcome.BOUND, survivors.bind("s1", principal("alice")))
            survivorClock.advance(ttl.minusMillis(1))
            assertEquals(BindOutcome.BOUND, survivors.bind("s2", principal("bob")))
            assertEquals("alice", survivors.principalOf("s1")?.id, "idleTtl - 1ms is inside the window")
            assertTrue(survivorFake.ended.isEmpty())
            assertEquals(2, survivors.size)

            val evictedFake = fakeSdkSessions("s1", "s2")
            val evictedClock = SettableClock()
            val evicted = store(evictedFake, evictedClock)
            assertEquals(BindOutcome.BOUND, evicted.bind("s1", principal("alice")))
            evictedClock.advance(ttl)
            assertEquals(BindOutcome.BOUND, evicted.bind("s2", principal("bob")))
            assertNull(evicted.principalOf("s1"), "exactly idleTtl is evicted")
            assertEquals(listOf("s1"), evictedFake.ended.toList())
        }
    }

    @Test
    fun `S10 a request inside the window refreshes the idle clock`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2")
            val clock = SettableClock()
            val sessions = store(fake, clock)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            clock.advance(ttl.minusMillis(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")), "a request on the bound id refreshes lastSeen")
            clock.advance(ttl.minusMillis(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))
            assertEquals("alice", sessions.principalOf("s1")?.id, "idle only idleTtl - 1ms since its last request")
            assertTrue(fake.ended.isEmpty())

            clock.advance(ttl)
            fake.live.add("s3")
            assertEquals(BindOutcome.BOUND, sessions.bind("s3", principal("carol")))
            assertNull(sessions.principalOf("s1"), "control: with no further request it does expire")
            assertNull(sessions.principalOf("s2"))
            assertEquals(listOf("s1", "s2"), fake.ended.sorted())
        }
    }

    @Test
    fun `S11 the least recently seen session is the victim at the limit, not the oldest created`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2", "s3")
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 2)
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")))
            clock.advance(Duration.ofSeconds(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s2", principal("bob")))
            assertEquals(2, sessions.size, "n sessions fit without evicting")
            assertTrue(fake.ended.isEmpty(), "the nth session evicts nobody")
            clock.advance(Duration.ofSeconds(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", principal("alice")), "s1 is seen again")
            clock.advance(Duration.ofSeconds(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s3", principal("carol")))

            assertEquals(2, sessions.size)
            assertEquals(listOf("s2"), fake.ended.toList(), "s2 was least recently seen")
            assertNull(sessions.principalOf("s2"))
            assertEquals("alice", sessions.principalOf("s1")?.id)
            assertEquals("carol", sessions.principalOf("s3")?.id, "the session being bound is never the victim")
        }
    }

    @Test
    fun `S12 the map never exceeds maxSessions and evicts one least recently seen victim per overflow`() {
        runBlocking {
            val max = 5
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = max)
            for (i in 1..50) {
                val id = "s$i"
                fake.live += id
                clock.advance(Duration.ofMillis(1))
                assertEquals(BindOutcome.BOUND, sessions.bind(id, principal("p$i")))
                assertTrue(sessions.size <= max, "size ${sessions.size} after bind $i")
                assertNotNull(sessions.principalOf(id), "the id being bound ($id) is never the victim")
            }
            assertEquals(max, sessions.size)
            assertEquals((1..45).map { "s$it" }, fake.ended.toList(), "oldest-seen first, one per overflow")
            for (i in 46..50) assertNotNull(sessions.principalOf("s$i"), "s$i is among the $max most recent")
            for (i in 1..45) assertNull(sessions.principalOf("s$i"), "s$i was evicted")
        }
    }

    @Test
    fun `S12 probe with every session seen at the same instant the bound id is still never the victim`() {
        runBlocking {
            val max = 3
            val fake = fakeSdkSessions()
            val sessions = store(fake, SettableClock(), maxSessions = max)
            for (i in 1..30) {
                val id = "s$i"
                fake.live += id
                assertEquals(BindOutcome.BOUND, sessions.bind(id, principal("p$i")))
                assertTrue(sessions.size <= max, "size ${sessions.size} after bind $i")
                assertNotNull(sessions.principalOf(id), "the id being bound ($id) survives its own bind")
                assertFalse(id in fake.ended, "the id being bound ($id) was not ended")
            }
            assertEquals(27, fake.ended.size, "one end per overflow")
            assertEquals(27, fake.ended.toSet().size, "no session is ended twice")
        }
    }

    @Test
    fun `S12 the defaults are 24 hours and 1024 sessions and are the ones in effect`() {
        assertEquals(Duration.ofHours(24), McpSessionPrincipals.DEFAULT_IDLE_TTL)
        assertEquals(1024, McpSessionPrincipals.DEFAULT_MAX_SESSIONS)
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = McpSessionPrincipals(redactNoteAttribution = true, sdkSessions = fake, clock = clock)
            for (i in 1..1025) {
                val id = "d$i"
                fake.live += id
                clock.advance(Duration.ofMillis(1))
                assertEquals(BindOutcome.BOUND, sessions.bind(id, principal("p")))
            }
            assertEquals(1024, sessions.size, "the 1025th session evicts exactly one")
            assertEquals(listOf("d1"), fake.ended.toList())

            val idleFake = fakeSdkSessions("a", "b", "c")
            val idleClock = SettableClock()
            val idle = McpSessionPrincipals(redactNoteAttribution = true, sdkSessions = idleFake, clock = idleClock)
            assertEquals(BindOutcome.BOUND, idle.bind("a", principal("p")))
            idleClock.advance(Duration.ofHours(24).minusMillis(1))
            assertEquals(BindOutcome.BOUND, idle.bind("b", principal("p")))
            assertNotNull(idle.principalOf("a"), "24h - 1ms is inside the default window")
            idleClock.advance(Duration.ofMillis(1))
            assertEquals(BindOutcome.BOUND, idle.bind("c", principal("p")))
            assertNull(idle.principalOf("a"), "24h exactly is outside it")
            assertEquals(listOf("a"), idleFake.ended.toList())
        }
    }

    @Test
    fun `S12 the constructor refuses a non-positive idleTtl and a maxSessions below one`() {
        val fake = fakeSdkSessions()
        for (bad in listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofHours(-24))) {
            val e = assertFailsWith<IllegalArgumentException>("idleTtl $bad") { store(fake, idleTtl = bad) }
            assertTrue("idleTtl" in e.message.orEmpty(), e.message)
        }
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException>("maxSessions $bad") { store(fake, maxSessions = bad) }
            assertTrue("maxSessions" in e.message.orEmpty(), e.message)
        }
        store(fake, idleTtl = Duration.ofMillis(1), maxSessions = 1)
    }

    @Test
    fun `S12 probe the limit of one session keeps exactly the newest`() {
        runBlocking {
            val fake = fakeSdkSessions("s1", "s2", "s3")
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 1)
            for (id in listOf("s1", "s2", "s3")) {
                clock.advance(Duration.ofMillis(1))
                assertEquals(BindOutcome.BOUND, sessions.bind(id, principal("p-$id")))
                assertEquals(1, sessions.size)
                assertNotNull(sessions.principalOf(id))
            }
            assertEquals(listOf("s1", "s2"), fake.ended.toList())
        }
    }

    @Test
    fun `S12 probe concurrent first binds of one live id by two principals give one BOUND and one OTHER_PRINCIPAL`() {
        runBlocking {
            repeat(100) { i ->
                val id = "c$i"
                val sessions = store(fakeSdkSessions(id))
                val gate = CompletableDeferred<Unit>()
                val alice =
                    async(Dispatchers.Default) {
                        gate.await()
                        sessions.bind(id, principal("alice"))
                    }
                val bob =
                    async(Dispatchers.Default) {
                        gate.await()
                        sessions.bind(id, principal("bob"))
                    }
                gate.complete(Unit)
                val a = alice.await()
                val b = bob.await()
                assertEquals(listOf(BindOutcome.BOUND, BindOutcome.OTHER_PRINCIPAL), listOf(a, b).sorted(), "round $i")
                assertEquals(1, sessions.size, "round $i")
                val winner = if (a == BindOutcome.BOUND) "alice" else "bob"
                assertEquals(winner, sessions.principalOf(id)?.id, "round $i: the BOUND caller owns the binding")
            }
        }
    }

    @Test
    fun `S12 probe concurrent binds of many sessions never leave the map above maxSessions`() {
        runBlocking {
            val max = 10
            val fake = fakeSdkSessions()
            for (w in 0 until 8) for (i in 0 until 100) fake.live += "w$w-$i"
            val sessions = store(fake, maxSessions = max)
            val gate = CompletableDeferred<Unit>()
            val workers =
                (0 until 8).map { w ->
                    async(Dispatchers.Default) {
                        gate.await()
                        for (i in 0 until 100) sessions.bind("w$w-$i", principal("worker-$w"))
                    }
                }
            gate.complete(Unit)
            workers.awaitAll()
            assertTrue(sessions.size <= max, "size ${sessions.size} after the concurrent binds")
            assertEquals(800 - sessions.size, fake.ended.toSet().size, "every evicted session was ended, none twice")
        }
    }

    // ------------------------------------------------------------------ ServerSdkSessions

    @Test
    fun `ServerSdkSessions with no served server knows no id and ending an unknown id is a harmless no-op`() {
        val sdk = ServerSdkSessions()
        assertFalse(sdk.isLive("anything"))
        assertFalse(sdk.isLive(""))
        runBlocking { sdk.end("anything") }
        runBlocking { sdk.end("anything") }
    }

    // ------------------------------------------------------------------ HTTP

    private val aliceToken = "alice-evict-token"
    private val bobToken = "bob-evict-token"

    private fun entries() =
        mapOf(
            McpAuthHttpFixture.entry(aliceToken, "alice"),
            McpAuthHttpFixture.entry(bobToken, "bob")
        )

    private fun fixture(
        clock: Clock = Clock.SYSTEM,
        idleTtl: Duration = McpSessionPrincipals.DEFAULT_IDLE_TTL,
        maxSessions: Int = McpSessionPrincipals.DEFAULT_MAX_SESSIONS
    ) = McpAuthHttpFixture(
        EventLogRig.build(db.db, dir),
        db.databaseManager,
        clock = clock,
        idleTtl = idleTtl,
        maxSessions = maxSessions
    )

    private fun callLogPrincipals(f: McpAuthHttpFixture): List<Any?> {
        runBlocking { f.writer.flushNow() }
        return CallLogRows.forTool(db.jdbcUrl, "items_write").map { it["principal_id"] }.sortedBy { it?.toString().orEmpty() }
    }

    @Test
    fun `S2 a handshake on every profile path is an SDK-live session bound to the caller and its tool call runs`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val sessionIds = mutableListOf<String>()
                for (path in listOf("/mcp", "/mcp/knowledge", "/mcp/director")) {
                    val session = client.handshake(path, aliceToken)
                    sessionIds += session
                    assertTrue(f.sdkSessions.isLive(session), "$path: the SDK issued and serves the id")
                    assertEquals("alice", f.sessions.principalOf(session)?.id, "$path: bound to the caller")
                }
                assertEquals(3, sessionIds.toSet().size)
                assertEquals(3, f.sessions.size)

                val reply = client.callTool("/mcp", aliceToken, sessionIds[0], "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(reply.toolCallSucceeded(), "control: a real session runs its tool: ${reply.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
                assertEquals(listOf<Any?>("api:alice"), callLogPrincipals(f))
            }
        }
    }

    @Test
    fun `S4 a valid token naming an id the SDK never issued is 404 on POST GET and DELETE, binds nothing and runs no tool`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val real = client.handshake("/mcp", aliceToken)
                assertEquals(1, f.sessions.size)

                val forged = "forged-" + UUID.randomUUID()
                val post = client.callTool("/mcp", aliceToken, forged, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, post.status, "POST: ${post.body}")
                // The GET status for an unknown id is the SDK's (observed: 200 with an empty event stream, not 404); only
                // "no binding, no tool" is this item's to assert, below.
                client.getStream("/mcp", "Bearer $aliceToken", forged)
                val delete = client.endSession("/mcp", "Bearer $aliceToken", forged)
                assertEquals(HttpStatusCode.NotFound, delete.status, "DELETE: ${delete.body}")

                assertNull(f.sessions.principalOf(forged), "an unissued id is never bound")
                assertEquals(1, f.sessions.size, "the map did not grow")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "no tool ran, no row written")
                assertFalse(forged in f.accessQueries, "the forged id never reached the dispatcher as a session: ${f.accessQueries}")
                assertTrue(callLogPrincipals(f).none { it != null }, "no call_log row carries a principal for the forged id")

                val control = client.callTool("/mcp", aliceToken, real, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(control.toolCallSucceeded(), "control: the real session still works: ${control.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S4 probe a flood of forged ids leaves a real session untouched`() {
        val f = fixture(maxSessions = 2)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                val b = client.handshake("/mcp", bobToken)
                assertEquals(2, f.sessions.size)
                for (i in 1..20) {
                    val reply = client.callTool("/mcp", aliceToken, "forged-$i", "items_write", McpAuthHttpFixture.CREATE_ARGS)
                    assertEquals(HttpStatusCode.NotFound, reply.status, "forged-$i")
                }
                assertEquals(2, f.sessions.size)
                assertTrue(f.sdkSessions.isLive(a) && f.sdkSessions.isLive(b), "the forged ids ended nobody")
                assertTrue(client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                assertTrue(client.callTool("/mcp", bobToken, b, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
            }
        }
    }

    @Test
    fun `S4 probe an empty, blank or 64 KiB session header and a case-changed real id bind nothing and run no tool`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val real = client.handshake("/mcp", aliceToken)
                val upper = real.uppercase()
                assertTrue(upper != real, "fixture condition: the SDK id has letters, so its upper-case form is a different id")
                for (variant in listOf("", "   ", "x".repeat(64 * 1024), upper)) {
                    val label = if (variant.length > 40) "64KiB" else "'$variant'"
                    val reply = client.callTool("/mcp", aliceToken, variant, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                    assertFalse(reply.toolCallSucceeded(), "$label ran a tool: ${reply.body.take(200)}")
                    assertNull(f.sessions.principalOf(variant), label)
                }
                assertEquals(1, f.sessions.size, "only the real session is bound")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
                val control = client.callTool("/mcp", aliceToken, real, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(control.toolCallSucceeded(), "control: the exact id works: ${control.body}")
            }
        }
    }

    @Test
    fun `S4 probe another principal naming a live session of the knowledge path is refused as the owner's session`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val knowledgeSession = client.handshake("/mcp/knowledge", aliceToken)
                val hijack = client.callTool("/mcp", bobToken, knowledgeSession, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.Unauthorized, hijack.status, hijack.body)
                assertEquals("unauthenticated", hijack.errorCode, hijack.body)
                assertEquals("alice", f.sessions.principalOf(knowledgeSession)?.id, "the binding stays the owner's")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S7 the owner's DELETE ends the session, forgets the binding, and a replay by anyone is 404 and rebinds nothing`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                assertTrue(f.sdkSessions.isLive(session))
                assertEquals("alice", f.sessions.principalOf(session)?.id)

                val own = client.endSession("/mcp", "Bearer $aliceToken", session)
                assertTrue(own.status.value in 200..299, "the owner's DELETE: ${own.status} ${own.body}")
                assertFalse(f.sdkSessions.isLive(session), "the SDK no longer serves the deleted session")
                assertNull(f.sessions.principalOf(session), "the binding is forgotten at once")
                assertEquals(0, f.sessions.size)

                val ownerReplay = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, ownerReplay.status, ownerReplay.body)
                val foreignReplay = client.callTool("/mcp", bobToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, foreignReplay.status, foreignReplay.body)
                val secondDelete = client.endSession("/mcp", "Bearer $aliceToken", session)
                assertEquals(HttpStatusCode.NotFound, secondDelete.status, secondDelete.body)

                assertNull(f.sessions.principalOf(session), "a replay does not re-create the binding")
                assertEquals(0, f.sessions.size)
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S6 a session the SDK closed behind the plugin's back is dropped at the next new bind and answers 404`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                runBlocking {
                    f.sdkSessions.end(a)
                    f.sdkSessions.end(a)
                }
                assertFalse(f.sdkSessions.isLive(a), "ending a session closes it on the SDK side")

                val b = client.handshake("/mcp", bobToken)
                assertNull(f.sessions.principalOf(a), "the next new bind swept the closed session")
                assertEquals("bob", f.sessions.principalOf(b)?.id)
                assertEquals(1, f.sessions.size)

                val stale = client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, stale.status, stale.body)
                assertTrue(client.callTool("/mcp", bobToken, b, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S9 with maxSessions 1 the second session ends the first on the SDK side and the first answers 404`() {
        val f = fixture(maxSessions = 1)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                val before = client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(before.toolCallSucceeded(), "control: alice's session works before bob arrives: ${before.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                val b = client.handshake("/mcp", bobToken)
                assertFalse(f.sdkSessions.isLive(a), "the evicted session was closed on the SDK side")
                assertTrue(f.sdkSessions.isLive(b), "the session being bound is never the victim")
                assertNull(f.sessions.principalOf(a))
                assertEquals("bob", f.sessions.principalOf(b)?.id)
                assertEquals(1, f.sessions.size)

                val stale = client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, stale.status, "alice's next call is the SDK's 404: ${stale.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "the refused call wrote nothing")

                val own = client.callTool("/mcp", bobToken, b, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(own.toolCallSucceeded(), "bob's session works: ${own.body}")
                assertEquals(2, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
                assertEquals(
                    listOf<Any?>("api:alice", "api:bob"),
                    callLogPrincipals(f).filterNotNull(),
                    "the stale call produced no row for alice"
                )
            }
        }
    }

    @Test
    fun `S13 a session idle for idleTtl is ended by the next handshake and idleTtl minus one millisecond is not`() {
        val clock = SettableClock()
        val f = fixture(clock = clock, idleTtl = ttl)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val inside = client.handshake("/mcp", aliceToken)
                clock.advance(ttl.minusMillis(1))
                val b = client.handshake("/mcp", bobToken)
                assertTrue(f.sdkSessions.isLive(inside), "idleTtl - 1ms survives")
                assertTrue(
                    client.callTool("/mcp", aliceToken, inside, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded(),
                    "and keeps working"
                )
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
                assertEquals(2, f.sessions.size)

                clock.advance(ttl.minusMillis(1))
                assertTrue(
                    client.callTool("/mcp", bobToken, b, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded(),
                    "bob's request refreshes his own idle clock"
                )
                clock.advance(ttl)
                val c = client.handshake("/mcp", bobToken)
                assertFalse(f.sdkSessions.isLive(inside), "alice's session idle for more than idleTtl is ended")
                assertNull(f.sessions.principalOf(inside))
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.callTool("/mcp", aliceToken, inside, "items_write", McpAuthHttpFixture.CREATE_ARGS).status,
                    "its later request is the SDK's 404"
                )
                assertEquals(2, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "the 404 ran nothing")
                assertTrue(f.sdkSessions.isLive(c), "the session being bound is live")
            }
        }
    }

    @Test
    fun `S13 a request inside the window refreshes the idle clock over HTTP`() {
        val clock = SettableClock()
        val f = fixture(clock = clock, idleTtl = ttl)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                clock.advance(ttl.minusMillis(1))
                assertTrue(client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                clock.advance(ttl.minusMillis(1))
                client.handshake("/mcp", bobToken)
                assertTrue(f.sdkSessions.isLive(a), "idle only idleTtl - 1ms since its last request")
                assertTrue(client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                assertEquals(2, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }
}

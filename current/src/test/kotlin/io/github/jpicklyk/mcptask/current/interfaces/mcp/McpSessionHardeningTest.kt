package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 1da600ad) for the `/mcp` session-map hardening follow-ups of 8263c971: the
 * largest-holder eviction of [McpSessionPrincipals] (N4), the closing of the SDK sessions a header-less POST leaves behind
 * ([SdkSessions.endOrphans] / [ServerSdkSessions] / [McpSessionPrincipals.sweepOrphans], N5) and the `lastSeen` rule of a
 * rejected caller (N3). Unit scenarios build [McpSessionPrincipals] directly over a [FakeSdkSessions] and a [SettableClock];
 * HTTP scenarios run the production topology through [McpAuthHttpFixture] with the fixture's clock driving the orphan grace.
 *
 * Scenario ids are those of the frozen test-plan: S1, S4, S5, S7, S8, S9 (unit); S2, S3, S6, S10 (HTTP).
 *
 * Oracles (written from the frozen plan and the item's acceptance criteria, never from a run): AC1 (at capacity the victim is the
 * least-recently-seen binding of the holder with the most bindings, the binder counted +1, ties between holders resolved to the
 * older least-recently-seen binding, a holder with fewer bindings than the binder never loses one, the id being bound is never
 * the victim, the map never exceeds maxSessions, an evicted session is ended on the SDK side), AC2 / D1 (with one holder the
 * order is plain LRU: 8263c971 S11), AC3 / D2 (a header-less POST the SDK answers 400 binds nothing and runs no tool; the
 * ServerSession it leaves is closed by a later header-less POST once it has lived exactly the 30 s grace or more, never earlier;
 * a session with an id is never closed by that sweep), AC4 (an unissued id creates no SDK session), AC5 (a 401 caller does not
 * refresh the victim's lastSeen), D4 (the sweep trigger is a header-less POST), plus the SDK 0.13.0 facts recorded in the
 * plan (a header-less non-initialize POST is 400 and leaves a null-id session; a present unknown id is 404 and creates nothing).
 *
 * SDK 0.16 (item f98690c9, oracle kotlin-sdk 0.16.0 KtorServer.kt `openStreamableSession`): a POST without `Mcp-Session-Id` that
 * does not initialize a session closes the transport it opened once the POST completes, and closing drops the ServerSession from
 * `Server.sessions`. So over HTTP no orphan is ever left: the HTTP scenarios assert that the registry returns to its prior size
 * (polled for up to 2 s, because the close runs after the response is written), and the 30 s grace of [ServerSdkSessions] is
 * pinned by a unit scenario that creates never-initialized sessions directly with `Server.createSession`.
 *
 * Negative-assertion conditions (what makes each fail without the feature): S1/S2/S5/S7 fail if eviction is plain LRU (the
 * flooded-out bystander is the oldest); S8 fails if the binder is not counted +1; S4 fails if a 401 caller refreshes lastSeen
 * (the victim survives past its idle window); the orphan scenarios assert the orphans exist first (`sessions.size` grows by one
 * per ping) so "they are gone" cannot hold vacuously, and S10 fails if the grace comparison is `>` instead of `>=` or the sweep
 * is not wired into the header-less POST path.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no disjunctive "did nothing" assertions. The blank
 * `Mcp-Session-Id` probe asserts only "no session was created" because the status of a blank header belongs to the SDK.
 *
 * Domain invariants satisfied by construction: principals are plain values (no validation beyond types); the fixture's
 * constructor invariants `idleTtl > 0` and `maxSessions >= 1` hold for every instance built here; session ids handed to the
 * fake are added to its live set before they are bound.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpSessionHardeningTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val ttl: Duration = Duration.ofHours(1)

    private fun principal(id: String) =
        Principal(
            id,
            PrincipalKind.AGENT,
            ProofStatus.ISSUED,
            AccessScope.unrestricted(),
            setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
        )

    private fun store(
        fake: SdkSessions,
        clock: SettableClock,
        maxSessions: Int,
        idleTtl: Duration = ttl
    ) = McpSessionPrincipals(
        redactNoteAttribution = true,
        sdkSessions = fake,
        clock = clock,
        idleTtl = idleTtl,
        maxSessions = maxSessions
    )

    /** Registers [id] as SDK-live, moves the clock one second (so every binding has its own lastSeen) and binds it. */
    private suspend fun McpSessionPrincipals.bindNew(
        fake: FakeSdkSessions,
        clock: SettableClock,
        id: String,
        who: Principal
    ): BindOutcome {
        fake.live += id
        clock.advance(Duration.ofSeconds(1))
        return bind(id, who)
    }

    // ------------------------------------------------------------------ unit: largest-holder eviction

    @Test
    fun `S1 at capacity the largest holder loses its least recently seen session and a smaller holder keeps its own`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 4)
            val alice = principal("alice")
            val bob = principal("bob")

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "b1", bob))
            for (id in listOf("a1", "a2", "a3")) assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, id, alice))
            assertEquals(4, sessions.size)
            assertTrue(fake.ended.isEmpty(), "filling the map to its limit evicts nobody")

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "a4", alice))
            assertEquals(listOf("a1"), fake.ended.toList(), "alice holds 3 (+1) against bob's 1: her least recently seen goes")
            assertEquals("bob", sessions.principalOf("b1")?.id, "bob's only session is untouched")
            assertEquals(4, sessions.size)

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "a5", alice))
            assertEquals(listOf("a1", "a2"), fake.ended.toList())
            assertEquals("bob", sessions.principalOf("b1")?.id)

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "b2", bob))
            assertEquals(listOf("a1", "a2", "a3"), fake.ended.toList(), "bob 1+1 against alice's 3: still alice's least recently seen")
            assertEquals("bob", sessions.principalOf("b1")?.id)
            assertEquals("bob", sessions.principalOf("b2")?.id, "the id being bound is never the victim")
            assertNull(sessions.principalOf("a1"))
            assertNull(sessions.principalOf("a2"))
            assertNull(sessions.principalOf("a3"))
            assertEquals(4, sessions.size)
        }
    }

    @Test
    fun `S5 a flooding principal evicts only its own sessions and the map stays at the limit`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 8)
            val bystanders = mapOf("alice" to listOf("a1", "a2"), "bob" to listOf("b1", "b2"), "carol" to listOf("c1", "c2"))
            for ((who, ids) in bystanders) {
                for (id in ids) {
                    assertEquals(
                        BindOutcome.BOUND,
                        sessions.bindNew(fake, clock, id, principal(who))
                    )
                }
            }
            assertEquals(6, sessions.size)

            val mallory = principal("mallory")
            for (i in 1..100) {
                assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "m$i", mallory), "m$i")
                assertTrue(sessions.size <= 8, "size ${sessions.size} after m$i")
                assertNotNull(sessions.principalOf("m$i"), "the id being bound (m$i) is never the victim")
            }

            assertEquals(8, sessions.size)
            assertEquals((1..98).map { "m$it" }, fake.ended.toList(), "only mallory's own sessions were ended, oldest first")
            for ((who, ids) in bystanders) for (id in ids) assertEquals(who, sessions.principalOf(id)?.id, "$who's $id survives the flood")
            assertEquals("mallory", sessions.principalOf("m99")?.id)
            assertEquals("mallory", sessions.principalOf("m100")?.id)
        }
    }

    @Test
    fun `S5 probe one session of a bystander survives 2000 binds of another credential under the default limit`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = McpSessionPrincipals(redactNoteAttribution = true, sdkSessions = fake, clock = clock)
            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "alice-only", principal("alice")))
            for (i in 1..2000) {
                assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "m$i", principal("mallory")), "m$i")
            }
            assertEquals(1024, sessions.size)
            assertEquals("alice", sessions.principalOf("alice-only")?.id, "alice's single session outlives the flood")
            assertFalse("alice-only" in fake.ended, "and was never ended")
            assertEquals(2000 - 1023, fake.ended.size, "every overflow ended exactly one of mallory's sessions")
            assertTrue(fake.ended.all { it.startsWith("m") })
        }
    }

    @Test
    fun `S7 when two holders tie for the most sessions the older least recently seen binding of the two goes`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 4)
            val alice = principal("alice")
            val bob = principal("bob")
            // lastSeen order: a1 < b1 < a2 < b2
            sessions.bindNew(fake, clock, "a1", alice)
            sessions.bindNew(fake, clock, "b1", bob)
            sessions.bindNew(fake, clock, "a2", alice)
            sessions.bindNew(fake, clock, "b2", bob)
            assertEquals(4, sessions.size)

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "c1", principal("carol")))
            assertEquals(listOf("a1"), fake.ended.toList(), "alice 2 = bob 2 > carol 0+1: the older of their least recently seen is a1")

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "c2", principal("carol")))
            assertEquals(listOf("a1", "b1"), fake.ended.toList(), "bob 2 = carol 1+1 > alice 1: b1 is older than c1")
            assertEquals("carol", sessions.principalOf("c1")?.id, "carol's c1 is newer than b1 and is kept")
            assertEquals("carol", sessions.principalOf("c2")?.id)
            assertEquals("alice", sessions.principalOf("a2")?.id)
            assertEquals("bob", sessions.principalOf("b2")?.id)
            assertEquals(4, sessions.size)
        }
    }

    @Test
    fun `S8 the binder counts one more than it holds so a tie with it goes to the older binding even if that is its own`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 3)
            val alice = principal("alice")
            val bob = principal("bob")
            sessions.bindNew(fake, clock, "b1", bob)
            sessions.bindNew(fake, clock, "a1", alice)
            sessions.bindNew(fake, clock, "a2", alice)
            assertEquals(3, sessions.size)

            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "b2", bob))
            assertEquals(listOf("b1"), fake.ended.toList(), "bob 1+1 = alice 2: the tie goes to the older binding, b1, not alice's a1")
            assertEquals("alice", sessions.principalOf("a1")?.id, "alice's sessions are intact")
            assertEquals("alice", sessions.principalOf("a2")?.id)
            assertEquals("bob", sessions.principalOf("b2")?.id)
            assertNull(sessions.principalOf("b1"))
            assertEquals(3, sessions.size)
        }
    }

    @Test
    fun `S9 with a single holder the order is plain least recently seen, as in 8263c971 S11`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 2)
            assertEquals("local", LOCAL_PRINCIPAL.id)
            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "s1", LOCAL_PRINCIPAL))
            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "s2", LOCAL_PRINCIPAL))
            clock.advance(Duration.ofSeconds(1))
            assertEquals(BindOutcome.BOUND, sessions.bind("s1", LOCAL_PRINCIPAL), "s1 is seen again")
            assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "s3", LOCAL_PRINCIPAL))

            assertEquals(listOf("s2"), fake.ended.toList(), "s2 was the least recently seen")
            assertEquals("local", sessions.principalOf("s1")?.id)
            assertEquals("local", sessions.principalOf("s3")?.id)
            assertNull(sessions.principalOf("s2"))
            assertEquals(2, sessions.size)
        }
    }

    @Test
    fun `S9 probe one non-local holder is also plain LRU across a long run`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 5)
            val solo = principal("solo")
            for (i in 1..40) assertEquals(BindOutcome.BOUND, sessions.bindNew(fake, clock, "s$i", solo))
            assertEquals((1..35).map { "s$it" }, fake.ended.toList())
            assertEquals(5, sessions.size)
        }
    }

    @Test
    fun `AC1 the victims match an independent model of the largest-holder rule over a seeded random run`() {
        runBlocking {
            val max = 6
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = max)
            val holders = listOf("p0", "p1", "p2", "p3", "p4")
            val random = Random(20261015)

            // The model: id -> holder and id -> lastSeen second. It is the rule of AC1 written out, nothing else.
            val owner = LinkedHashMap<String, String>()
            val seen = HashMap<String, Long>()
            val modelVictims = ArrayList<String>()
            var second = 0L
            var nextId = 0

            repeat(400) { step ->
                second += 1
                clock.advance(Duration.ofSeconds(1))
                val touch = owner.isNotEmpty() && random.nextInt(100) < 30
                if (touch) {
                    val id = owner.keys.elementAt(random.nextInt(owner.size))
                    seen[id] = second
                    assertEquals(BindOutcome.BOUND, sessions.bind(id, principal(owner.getValue(id))), "step $step touch $id")
                } else {
                    val who = holders[random.nextInt(holders.size)]
                    val id = "n${nextId++}"
                    if (owner.size >= max) {
                        val counts =
                            owner.values
                                .groupingBy { it }
                                .eachCount()
                                .toMutableMap()
                        counts[who] = (counts[who] ?: 0) + 1
                        val top = counts.values.max()
                        val tied = counts.filterValues { it == top }.keys
                        val victim = owner.keys.filter { owner.getValue(it) in tied }.minBy { seen.getValue(it) }
                        owner.remove(victim)
                        seen.remove(victim)
                        modelVictims += victim
                    }
                    owner[id] = who
                    seen[id] = second
                    fake.live += id
                    assertEquals(BindOutcome.BOUND, sessions.bind(id, principal(who)), "step $step bind $id")
                    assertNotNull(sessions.principalOf(id), "step $step: the id being bound is never the victim")
                }
                assertTrue(sessions.size <= max, "step $step: size ${sessions.size}")
                assertEquals(owner.size, sessions.size, "step $step")
                assertEquals(modelVictims, fake.ended.toList(), "step $step: the ended sessions, in order")
            }
            for ((id, who) in owner) assertEquals(who, sessions.principalOf(id)?.id, "$id is still bound to $who")
            assertTrue(modelVictims.size > 100, "fixture condition: the run overflowed the map often enough to mean something")
        }
    }

    @Test
    fun `an evicted id cannot be replayed because the SDK no longer serves it`() {
        runBlocking {
            val fake = fakeSdkSessions()
            val clock = SettableClock()
            val sessions = store(fake, clock, maxSessions = 2)
            val mallory = principal("mallory")
            sessions.bindNew(fake, clock, "m1", mallory)
            sessions.bindNew(fake, clock, "m2", mallory)
            sessions.bindNew(fake, clock, "m3", mallory)
            assertEquals(listOf("m1"), fake.ended.toList())
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("m1", mallory), "replay of the evicted id")
            assertEquals(BindOutcome.UNKNOWN, sessions.bind("m1", principal("alice")), "replay by another principal")
            assertEquals(2, sessions.size)
        }
    }

    // ------------------------------------------------------------------ unit: N3 lastSeen

    @Test
    fun `S4 a rejected caller does not refresh the owner's idle clock and the owner's own request does`() {
        runBlocking {
            val rejectedFake = fakeSdkSessions("s1", "s2")
            val rejectedClock = SettableClock()
            val rejected = store(rejectedFake, rejectedClock, maxSessions = 8)
            assertEquals(BindOutcome.BOUND, rejected.bind("s1", principal("alice")))
            rejectedClock.advance(ttl.minusMillis(1))
            assertEquals(BindOutcome.OTHER_PRINCIPAL, rejected.bind("s1", principal("bob")), "bob is refused alice's session")
            rejectedClock.advance(Duration.ofMillis(1))
            assertEquals(BindOutcome.BOUND, rejected.bind("s2", principal("carol")))
            assertEquals(
                listOf("s1"),
                rejectedFake.ended.toList(),
                "idle exactly idleTtl since alice's own last request: bob's refusal bought nothing"
            )
            assertNull(rejected.principalOf("s1"))

            val touchedFake = fakeSdkSessions("s1", "s2")
            val touchedClock = SettableClock()
            val touched = store(touchedFake, touchedClock, maxSessions = 8)
            assertEquals(BindOutcome.BOUND, touched.bind("s1", principal("alice")))
            touchedClock.advance(ttl.minusMillis(1))
            assertEquals(BindOutcome.BOUND, touched.bind("s1", principal("alice")), "control: the owner's request refreshes")
            touchedClock.advance(Duration.ofMillis(1))
            assertEquals(BindOutcome.BOUND, touched.bind("s2", principal("carol")))
            assertTrue(touchedFake.ended.isEmpty(), "the same timeline with the owner touching keeps the session")
            assertEquals("alice", touched.principalOf("s1")?.id)
        }
    }

    // ------------------------------------------------------------------ unit: orphan sweep surface

    @Test
    fun `sweepOrphans answers what the SDK side ended`() {
        val sdk =
            object : SdkSessions {
                override fun isLive(sessionId: String): Boolean = false

                override suspend fun end(sessionId: String) = Unit

                override suspend fun endOrphans(): Int = 7
            }
        val sessions = store(sdk, SettableClock(), maxSessions = 4)
        assertEquals(7, runBlocking { sessions.sweepOrphans() })
        assertEquals(0, runBlocking { store(fakeSdkSessions(), SettableClock(), maxSessions = 4).sweepOrphans() })
    }

    @Test
    fun `the orphan grace default is 30 seconds and a ServerSdkSessions with no served server has no orphan`() {
        assertEquals(Duration.ofSeconds(30), ServerSdkSessions.DEFAULT_ORPHAN_GRACE)
        assertEquals(0, runBlocking { ServerSdkSessions().endOrphans() })
        assertEquals(0, runBlocking { ServerSdkSessions(SettableClock(), Duration.ZERO).endOrphans() })
    }

    // ------------------------------------------------------------------ HTTP

    private val aliceToken = "alice-hardening-token"
    private val bobToken = "bob-hardening-token"
    private val ping = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

    /** Polls [server]'s session registry for up to 2 s until it holds [expected] sessions, then asserts it (real time, no bare sleep). */
    private suspend fun awaitSessions(
        server: Server,
        expected: Int,
        message: String
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (server.sessions.size != expected && System.nanoTime() < deadline) {
            withContext(Dispatchers.Default) { delay(20) }
        }
        assertEquals(expected, server.sessions.size, message)
    }

    private fun entries() =
        mapOf(
            McpAuthHttpFixture.entry(aliceToken, "alice"),
            McpAuthHttpFixture.entry(bobToken, "bob")
        )

    private fun fixture(
        clock: SettableClock,
        maxSessions: Int = McpSessionPrincipals.DEFAULT_MAX_SESSIONS
    ) = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager, clock = clock, maxSessions = maxSessions)

    @Test
    fun `S2 at the limit one credential's flood ends its own SDK session and the other credential's session keeps working`() {
        val clock = SettableClock()
        val f = fixture(clock, maxSessions = 2)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val b1 = client.handshake("/mcp", bobToken)
                clock.advance(Duration.ofSeconds(1))
                val a1 = client.handshake("/mcp", aliceToken)
                clock.advance(Duration.ofSeconds(1))
                assertEquals(2, f.sessions.size)
                val a2 = client.handshake("/mcp", aliceToken)

                assertFalse(f.sdkSessions.isLive(a1), "alice holds more sessions: her least recently seen is ended on the SDK side")
                assertNull(f.sessions.principalOf(a1))
                val gone = client.callTool("/mcp", aliceToken, a1, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.NotFound, gone.status, "the SDK answers 404 for the ended session: ${gone.body}")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "no tool ran for the ended session")

                assertTrue(f.sdkSessions.isLive(b1), "bob's session is not the plain-LRU victim")
                assertEquals("bob", f.sessions.principalOf(b1)?.id)
                assertEquals(2, f.sessions.size)
                assertTrue(client.callTool("/mcp", bobToken, b1, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                assertTrue(client.callTool("/mcp", aliceToken, a2, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                assertEquals(2, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S3 header-less pings are 400, bind nothing and leave no SDK session, and a later header-less POST closes no real one`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                val base = f.orchestration.sessions.size
                assertTrue(base >= 1, "fixture condition: the handshake session is served")

                repeat(5) { i ->
                    val reply = client.rpc("/mcp", ping, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.BadRequest, reply.status, "ping $i: ${reply.body}")
                    assertNull(reply.sessionId, "ping $i issued no session id")
                }
                assertEquals(1, f.sessions.size, "nothing was bound by the pings")
                awaitSessions(
                    f.orchestration,
                    base,
                    "SDK 0.16 closes the transport of a header-less non-initialize POST: no session is left"
                )
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                // a successful initialize leaves exactly one real session and no orphan
                val h2 = client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, base + 1, "the initialize added one real session")

                clock.advance(Duration.ofMinutes(5))
                val h3 = client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, base + 2, "the three real sessions remain; nothing was left to close")

                for (session in listOf(a, h2, h3)) {
                    assertTrue(f.sdkSessions.isLive(session), "$session has an id and is never closed by the orphan sweep")
                    assertTrue(
                        client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded()
                    )
                }
                assertEquals(3, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `S10 an orphan is kept until it is exactly 30 seconds old and closed at that instant`() {
        runBlocking {
            val server = Server(Implementation("orphan-grace-test", "1.0.0"), ServerOptions(ServerCapabilities()))
            val clock = SettableClock()
            val sdk = ServerSdkSessions(clock).apply { serve(server) }
            // never-initialized sessions (no session id): what a 0.13 header-less POST left behind
            repeat(3) {
                server.createSession(StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true)))
            }
            assertEquals(3, server.sessions.size, "fixture condition: three orphans exist")

            assertEquals(0, sdk.endOrphans(), "the sweep that first sees them starts their age")
            clock.advance(Duration.ofSeconds(30).minusMillis(1))
            assertEquals(0, sdk.endOrphans(), "29.999 s old: none of the three is closed")
            assertEquals(3, server.sessions.size)

            clock.advance(Duration.ofMillis(1))
            assertEquals(3, sdk.endOrphans(), "exactly 30 s old: the three are closed")
            assertEquals(0, server.sessions.size)
        }
    }

    @Test
    fun `S10 probe a trigger that is not a header-less POST leaves the registry unchanged`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val a = client.handshake("/mcp", aliceToken)
                repeat(3) { client.rpc("/mcp", ping, "Bearer $aliceToken") }
                client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, 2, "fixture condition: the two real sessions, the three pings left nothing")
                val live = f.orchestration.sessions.size
                clock.advance(Duration.ofMinutes(5))

                val get = client.getStream("/mcp", "Bearer $aliceToken")
                assertEquals(HttpStatusCode.BadRequest, get.status, "a header-less GET is the SDK's 400: ${get.body}")
                awaitSessions(f.orchestration, live, "a header-less GET creates nothing")
                val call = client.callTool("/mcp", aliceToken, a, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(call.toolCallSucceeded(), call.body)
                awaitSessions(f.orchestration, live, "a POST that names a session creates nothing")

                client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, live + 1, "control: a header-less initialize adds exactly its real session")
            }
        }
    }

    @Test
    fun `S6 forged ids, a header-less GET or DELETE and a blank session header create no SDK session`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val real = client.handshake("/mcp", aliceToken)
                val sizes = { listOf(f.orchestration, f.knowledge, f.director).map { it.sessions.size } }
                val before = sizes()
                assertEquals(1, before[0], "fixture condition: the handshake session is the only one")

                for (i in 1..20) {
                    val forged = "forged-$i-" + UUID.randomUUID()
                    for (path in listOf("/mcp", "/mcp/knowledge")) {
                        val reply = client.rpc(path, ping, "Bearer $aliceToken", forged)
                        assertEquals(HttpStatusCode.NotFound, reply.status, "$path $forged: ${reply.body}")
                    }
                }
                assertEquals(before, sizes(), "forged ids created no SDK session on any server")

                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val get = client.getStream(path, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.BadRequest, get.status, "header-less GET $path: ${get.body}")
                    val delete = client.endSession(path, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.BadRequest, delete.status, "header-less DELETE $path: ${delete.body}")
                }
                assertEquals(before, sizes(), "a header-less GET or DELETE creates nothing")

                for (blank in listOf("", "   ")) {
                    val reply = client.rpc("/mcp", ping, "Bearer $aliceToken", blank)
                    assertFalse(reply.status.value in 200..299, "blank header '$blank': ${reply.body}")
                    assertEquals(before, sizes(), "a blank session header '$blank' created no SDK session")
                }
                assertEquals(1, f.sessions.size, "only the real session is bound")
                assertTrue(client.callTool("/mcp", aliceToken, real, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
            }
        }
    }

    @Test
    fun `S10 probe header-less pings on the knowledge path leave no session there`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                repeat(3) {
                    val reply = client.rpc("/mcp/knowledge", ping, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.BadRequest, reply.status, reply.body)
                }
                awaitSessions(f.knowledge, 0, "SDK 0.16 leaves no session behind on the knowledge path")
                client.handshake("/mcp", aliceToken)
                clock.advance(Duration.ofMinutes(5))
                client.handshake("/mcp", aliceToken)
                awaitSessions(f.knowledge, 0, "a header-less POST on /mcp finds nothing on the knowledge server")
                awaitSessions(f.orchestration, 2, "the two handshakes are the only orchestration sessions")
            }
        }
    }

    @Test
    fun `S10 probe an initialize the SDK rejects leaves no orphan`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                client.handshake("/mcp", aliceToken)
                val base = f.orchestration.sessions.size
                val rejected =
                    client.post("/mcp") {
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.Authorization, "Bearer $aliceToken")
                        contentType(ContentType.Application.Json)
                        setBody(f.initializeBody)
                    }
                assertNull(rejected.headers["Mcp-Session-Id"], "the SDK refused the initialize: ${rejected.bodyAsText()}")
                awaitSessions(f.orchestration, base, "SDK 0.16 closes the transport of an initialize it refused: no orphan")
                assertEquals(1, f.sessions.size)

                client.handshake("/mcp", aliceToken)
                clock.advance(Duration.ofMinutes(5))
                client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, base + 2, "only the two real sessions were added")
            }
        }
    }

    @Test
    fun `S10 probe 200 concurrent header-less pings leave no session behind`() {
        val clock = SettableClock()
        val f = fixture(clock)
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                client.handshake("/mcp", aliceToken)
                val base = f.orchestration.sessions.size
                val statuses =
                    coroutineScope {
                        (0 until 8)
                            .map {
                                async { (0 until 25).map { client.rpc("/mcp", ping, "Bearer $aliceToken").status } }
                            }.awaitAll()
                            .flatten()
                    }
                assertEquals(200, statuses.size)
                assertTrue(statuses.all { it == HttpStatusCode.BadRequest }, "every ping is the SDK's 400: ${statuses.toSet()}")
                awaitSessions(f.orchestration, base, "all 200 header-less pings were closed by the SDK: none is left")

                client.handshake("/mcp", aliceToken)
                clock.advance(Duration.ofMinutes(5))
                client.handshake("/mcp", aliceToken)
                awaitSessions(f.orchestration, base + 2, "only the two new real sessions were added")
                assertEquals(3, f.sessions.size, "the flood bound nothing: only the three handshake sessions are bound")
            }
        }
    }
}

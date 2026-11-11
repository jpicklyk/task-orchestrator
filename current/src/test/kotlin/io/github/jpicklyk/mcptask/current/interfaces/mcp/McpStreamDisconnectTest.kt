package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.RawSseConnection
import io.github.jpicklyk.mcptask.current.test.awaitCondition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * Independent tests (test-author seat, item f98690c9, issue 682 "CLOSE_WAIT") for the `/mcp` GET stream after the kotlin-sdk
 * 0.16.0 bump: a client that goes away without a `DELETE` frees its socket, the stream carries a heartbeat, an idle session
 * expires, and the GET guard answers header-less and unknown ids before an SSE stream commits. Scenario ids are those of the
 * frozen test-plan: S1, S2, S3, S4 (mount default), S6.
 *
 * Harness: S1-S4 run a real CIO engine on port 0 (`embeddedServer`) with the production [installMcpStreamableHttp] and the
 * [McpAuthHttpFixture] topology; the GET is a raw `java.net.Socket` ([RawSseConnection]) so the test controls a half-close and a
 * close. Ktor's `testApplication` cannot see a client disconnect (Ktor 3.6.1 HttpRequestLifecycle.kt:60), so it is used only
 * for the scenarios that need no disconnect (S6 and the refusal probes). A Monitoring-phase interceptor in the engine's
 * application counts the GET calls that started and ended, which is how "the GET call's job ended" is observed.
 *
 * Oracles (written from the frozen plan and the SDK/Ktor sources, never from a run): S1 kotlin-sdk 0.16.0 KtorServer.kt:220 and
 * :560-572 (cancelCallOnClose is installed on the mount, so the engine cancels the GET call when its client disconnects) and
 * StreamableHttpSessionManager.kt:115 (a stream does not hold the session open; the disconnect does not end the session, so a
 * ping with the id stays 200); S2 Ktor ServerSSESession.kt:172-177 and :199-201 (first heartbeat at once, then one per period,
 * as `: heartbeat` comment lines) with KtorServer.kt:226 (the mount passes sseHeartbeatConfig); S3 KtorServer.kt:256-258 and
 * :274-276 plus StreamableHttpSessionManager.kt:36-39 and :213 (a session idle for `sessionIdleTimeout` is forgotten, so its id
 * answers 404, and its transport is closed, which ends its GET stream; a GET only touches the session; any request in flight or
 * finished refreshes the idle clock); the item's `McpSessionPrincipals` rule (a binding idle for its TTL is dropped on the next
 * bind); S4 diagnosis "Fix" (the mount's idle timeout defaults to `appConfig.mcpSessionIdleTimeout`); S6 KtorServer.kt:491-503
 * and :574-589 (a GET without `Mcp-Session-Id` is 400 `Bad Request: No valid session ID provided`, an unknown id is 404, both
 * before any SSE stream commits and with no session created).
 *
 * Negative-assertion conditions (what makes each fail without the fix): the EOF/job-ended assertions of S1 fail on SDK 0.13.0,
 * whose GET has no HttpRequestLifecycle (the handler just waits, the socket stays in CLOSE_WAIT); each is preceded by the live
 * state (status 200, the GET call counted as started and not ended, the stream still open). The heartbeat count of S2 fails when
 * no heartbeat is configured (0 lines) and the 30 s run fails when the period is not honoured (more than one line in 1.5 s). The
 * 404 / EOF / binding-dropped assertions of S3 each first prove the session was live (a 200 ping, a 200 stream head, a bound
 * principal) and fail if expiry is not wired (the session stays live) or the GET keeps it alive (stream never ends). S6 fails
 * on 0.13.0, which committed 200 before the guard.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no disjunctive "did nothing" assertions; every wait is a
 * bounded poll against a deadline.
 *
 * Domain invariants satisfied by construction: `McpSessionPrincipals` idleTtl > 0 and maxSessions >= 1 for every fixture; the
 * heartbeat and idle durations passed to the mount are positive.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpStreamDisconnectTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val token = "disconnect-token"
    private val ping = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

    private fun entries() = mapOf(McpAuthHttpFixture.entry(token, "disc"))

    private fun fixture(
        appConfig: AppConfig = AppConfig.fromEnv { null },
        idleTtl: java.time.Duration = McpSessionPrincipals.DEFAULT_IDLE_TTL
    ) = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager, appConfig = appConfig, idleTtl = idleTtl)

    /** A running real engine plus the observation of its GET calls. */
    private class LiveEngine(
        val port: Int,
        val getStarted: AtomicInteger,
        val getEnded: AtomicInteger,
        private val stopper: () -> Unit
    ) : AutoCloseable {
        val base: String get() = "http://127.0.0.1:$port"

        override fun close() = stopper()
    }

    /** Starts the production `/mcp` mount on a real CIO engine; [idleTimeout] null leaves the mount's own default. */
    private fun startEngine(
        f: McpAuthHttpFixture,
        heartbeat: Duration = 30.seconds,
        idleTimeout: Duration? = null
    ): LiveEngine {
        val started = AtomicInteger()
        val ended = AtomicInteger()
        val mode = McpAuthHttpFixture.credentialMode(entries())
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                intercept(ApplicationCallPipeline.Monitoring) {
                    val isGet = call.request.httpMethod == HttpMethod.Get
                    if (isGet) started.incrementAndGet()
                    try {
                        proceed()
                    } finally {
                        if (isGet) ended.incrementAndGet()
                    }
                }
                if (idleTimeout != null) {
                    installMcpStreamableHttp(
                        f.orchestration,
                        f.appConfig,
                        knowledgeServer = f.knowledge,
                        directorServer = f.director,
                        mcpAuth = mode,
                        sessionPrincipals = f.sessions,
                        heartbeat = heartbeat,
                        idleTimeout = idleTimeout
                    )
                } else {
                    installMcpStreamableHttp(
                        f.orchestration,
                        f.appConfig,
                        knowledgeServer = f.knowledge,
                        directorServer = f.director,
                        mcpAuth = mode,
                        sessionPrincipals = f.sessions,
                        heartbeat = heartbeat
                    )
                }
            }
        server.start(wait = false)
        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        return LiveEngine(port, started, ended) { server.stop(0, 500) }
    }

    private fun McpAuthHttpFixture.handshakeOn(
        live: LiveEngine,
        client: HttpClient
    ): String = runBlocking { with(this@handshakeOn) { client.handshake("${live.base}/mcp", token) } }

    private fun McpAuthHttpFixture.pingOn(
        live: LiveEngine,
        client: HttpClient,
        session: String
    ): McpAuthReply = runBlocking { with(this@pingOn) { client.rpc("${live.base}/mcp", ping, "Bearer $token", session) } }

    private fun openStream(
        live: LiveEngine,
        session: String
    ) = RawSseConnection(live.port, "/mcp", mapOf("Authorization" to "Bearer $token", "Mcp-Session-Id" to session))

    // ------------------------------------------------------------------ S1 the disconnect frees the stream

    @Test
    fun `S1 a client that half-closes its GET stream is closed by the server and its GET call ends, the session survives`() {
        val f = fixture()
        startEngine(f).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                openStream(live, session).use { sse ->
                    assertTrue(sse.awaitHead(3000), "the GET answers its head: ${sse.text}")
                    assertEquals(200, sse.status, "fixture condition: the stream is open (status 200)")
                    assertTrue(awaitCondition(3000) { live.getStarted.get() == 1 }, "the GET call started")
                    assertEquals(0, live.getEnded.get(), "fixture condition: the GET call is running before the client leaves")
                    assertFalse(sse.readFor(300), "fixture condition: the server holds the stream open while the client is connected")

                    sse.halfClose()
                    assertTrue(sse.awaitEof(3000), "the server closes the connection of a client that sent FIN (EOF within 3 s)")
                    assertTrue(awaitCondition(3000) { live.getEnded.get() == 1 }, "the GET call ended")
                }
                assertEquals(200, f.pingOn(live, client, session).status.value, "a disconnect is not a session end: the id still works")
            }
        }
    }

    @Test
    fun `S1 a client that closes its GET stream ends the GET call on the server and the session survives`() {
        val f = fixture()
        startEngine(f).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                val sse = openStream(live, session)
                try {
                    assertTrue(sse.awaitHead(3000), "the GET answers its head: ${sse.text}")
                    assertEquals(200, sse.status, "fixture condition: the stream is open (status 200)")
                    assertTrue(awaitCondition(3000) { live.getStarted.get() == 1 }, "the GET call started")
                    assertEquals(0, live.getEnded.get(), "fixture condition: the GET call is running before the client leaves")
                } finally {
                    sse.close()
                }
                assertTrue(awaitCondition(3000) { live.getEnded.get() == 1 }, "the engine cancels the GET call of a gone client (3 s)")
                assertEquals(200, f.pingOn(live, client, session).status.value, "the id still works after the disconnect")
            }
        }
    }

    @Test
    fun `S1 probe a second GET with the same id replaces the first, which the server ends`() {
        val f = fixture()
        startEngine(f).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                openStream(live, session).use { first ->
                    assertTrue(first.awaitHead(3000))
                    assertEquals(200, first.status)
                    openStream(live, session).use { second ->
                        assertTrue(second.awaitHead(3000))
                        assertEquals(200, second.status, "never 409: a second GET is accepted")
                        assertTrue(
                            first.awaitStreamEnd(3000),
                            "the replaced stream is ended by the server (its chunked response completes)"
                        )
                        assertFalse(second.readFor(300), "the replacing stream stays open")
                    }
                }
                assertEquals(200, f.pingOn(live, client, session).status.value)
            }
        }
    }

    // ------------------------------------------------------------------ S2 heartbeat

    @Test
    fun `S2 with a 200 ms heartbeat at least two heartbeat comments arrive within 2 seconds`() {
        val f = fixture()
        startEngine(f, heartbeat = 200.milliseconds).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                openStream(live, session).use { sse ->
                    assertTrue(sse.awaitHead(3000))
                    assertEquals(200, sse.status)
                    sse.readFor(2000)
                    assertFalse(sse.closedByServer, "the stream stays open while heartbeats flow")
                    val beats = sse.count(": heartbeat")
                    assertTrue(beats >= 2, "expected at least 2 heartbeat comments in 2 s at a 200 ms period, got $beats: ${sse.text}")
                }
            }
        }
    }

    @Test
    fun `S2 with the production 30 second heartbeat exactly one heartbeat arrives in the first 1_5 seconds`() {
        val f = fixture()
        startEngine(f).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                openStream(live, session).use { sse ->
                    assertTrue(sse.awaitHead(3000))
                    assertEquals(200, sse.status)
                    sse.readFor(1500)
                    assertEquals(
                        1,
                        sse.count(": heartbeat"),
                        "Ktor sends the first heartbeat at once, the next one 30 s later: ${sse.text}"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ S3 idle expiry

    @Test
    fun `S3 a session idle past the idle timeout answers 404 and its SDK session is closed`() {
        val f = fixture()
        startEngine(f, idleTimeout = 300.milliseconds).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                assertTrue(f.sdkSessions.isLive(session), "fixture condition: the session is live after the handshake")
                // no request while waiting: a poll with pings would keep the session alive
                assertTrue(awaitCondition(4000) { !f.sdkSessions.isLive(session) }, "an idle session is closed within the sweep interval")
                val gone = f.pingOn(live, client, session)
                assertEquals(404, gone.status.value, "the expired id answers 404: ${gone.body}")
            }
        }
    }

    @Test
    fun `S3 an open GET stream does not keep an idle session alive and its stream ends at expiry`() {
        val f = fixture()
        startEngine(f, idleTimeout = 300.milliseconds).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                openStream(live, session).use { sse ->
                    assertTrue(sse.awaitHead(3000), "the GET answers its head: ${sse.text}")
                    assertEquals(200, sse.status, "fixture condition: the stream opened")
                    assertTrue(
                        sse.awaitStreamEnd(4000),
                        "expiry closes the transport and with it the stream even though the client is connected"
                    )
                }
                assertTrue(awaitCondition(2000) { !f.sdkSessions.isLive(session) }, "the session behind the stream is closed")
                assertEquals(404, f.pingOn(live, client, session).status.value)
            }
        }
    }

    @Test
    fun `S3 edge requests inside the idle window keep the session alive past the timeout`() {
        val f = fixture()
        startEngine(f, idleTimeout = 2.seconds).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                repeat(10) { i ->
                    Thread.sleep(400)
                    val reply = f.pingOn(live, client, session)
                    assertEquals(
                        200,
                        reply.status.value,
                        "ping $i at ${(i + 1) * 400} ms: each ping refreshes the 2 s idle clock: ${reply.body}"
                    )
                }
                assertTrue(f.sdkSessions.isLive(session), "4 s of life across a 2 s idle timeout: kept alive only by the pings")
            }
        }
    }

    @Test
    fun `S3 the next handshake after an expiry drops the binding of the expired session`() {
        val f = fixture(idleTtl = java.time.Duration.ofMillis(300))
        startEngine(f, idleTimeout = 300.milliseconds).use { live ->
            HttpClient(ClientCIO).use { client ->
                val first = f.handshakeOn(live, client)
                assertEquals("disc", f.sessions.principalOf(first)?.id, "fixture condition: the session is bound to its principal")
                assertTrue(awaitCondition(4000) { !f.sdkSessions.isLive(first) }, "the SDK expires the idle session")

                val second = f.handshakeOn(live, client)
                assertNotNull(second)
                assertEquals(null, f.sessions.principalOf(first), "the next bind drops the binding of the idle, expired session")
                assertFalse(f.sdkSessions.isLive(first))
                assertEquals("disc", f.sessions.principalOf(second)?.id, "the new session is bound")
            }
        }
    }

    // ------------------------------------------------------------------ S4 the mount default

    @Test
    fun `S4 a mount given no idle timeout takes it from appConfig mcpSessionIdleTimeout`() {
        val f = fixture(appConfig = AppConfig.fromEnv { null }.copy(mcpSessionIdleTimeout = 300.milliseconds))
        startEngine(f, idleTimeout = null).use { live ->
            HttpClient(ClientCIO).use { client ->
                val session = f.handshakeOn(live, client)
                assertTrue(f.sdkSessions.isLive(session), "fixture condition: the session is live after the handshake")
                assertTrue(awaitCondition(4000) { !f.sdkSessions.isLive(session) }, "the 300 ms from appConfig is in effect")
                assertEquals(404, f.pingOn(live, client, session).status.value)
            }
        }
    }

    @Test
    fun `S4 probe a zero idle timeout is refused when the mount is installed`() {
        val f = fixture()
        val thrown =
            runCatching {
                testApplication {
                    application {
                        installMcpStreamableHttp(
                            f.orchestration,
                            f.appConfig,
                            mcpAuth = McpAuthHttpFixture.credentialMode(entries()),
                            sessionPrincipals = f.sessions,
                            idleTimeout = Duration.ZERO
                        )
                    }
                    startApplication()
                }
            }.exceptionOrNull()
        assertNotNull(thrown, "a non-positive session idle timeout must fail the install")
        val chain = generateSequence(thrown) { it.cause }.toList()
        assertTrue(
            chain.any { it is IllegalArgumentException },
            "expected an IllegalArgumentException in the cause chain, got ${chain.map { it::class.simpleName }}"
        )
    }

    // ------------------------------------------------------------------ S6 the GET guard

    @Test
    fun `S6 a header-less GET on mcp and mcp knowledge is 400 with the SDK message and creates nothing`() {
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries())) } }
            with(f) {
                val sizes = { listOf(f.orchestration, f.knowledge, f.director).map { it.sessions.size } }
                val before = sizes()
                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val reply = client.getStream(path, "Bearer $token")
                    assertEquals(HttpStatusCode.BadRequest, reply.status, "$path: ${reply.body}")
                    assertEquals(
                        "Bad Request: No valid session ID provided",
                        reply.errorObject
                            ?.get("message")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                        "$path: ${reply.body}"
                    )
                }
                assertEquals(before, sizes(), "a header-less GET created no SDK session")
                assertEquals(0, f.sessions.size, "and bound nothing")
            }
        }
    }

    @Test
    fun `S6 probe a GET naming an id the mount never issued is 404 on both paths and creates nothing`() {
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries())) } }
            with(f) {
                val before = listOf(f.orchestration, f.knowledge, f.director).map { it.sessions.size }
                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val reply = client.getStream(path, "Bearer $token", "never-issued-" + UUID.randomUUID())
                    assertEquals(HttpStatusCode.NotFound, reply.status, "$path: ${reply.body}")
                }
                assertEquals(before, listOf(f.orchestration, f.knowledge, f.director).map { it.sessions.size })
                assertEquals(0, f.sessions.size)
            }
        }
    }

    @Test
    fun `S6 probe an empty and a blank Mcp-Session-Id on a GET are refused before a stream commits`() {
        val f = fixture()
        startEngine(f).use { live ->
            // RFC 9110 5.5: leading and trailing whitespace is not part of a field value, so a blank header is an empty one:
            // "no valid session id" (400), refused by the GET guard before any stream commits
            for (value in listOf("", "   ")) {
                RawSseConnection(live.port, "/mcp", mapOf("Authorization" to "Bearer $token", "Mcp-Session-Id" to value)).use { sse ->
                    assertTrue(sse.awaitHead(3000), "head for '$value': ${sse.text}")
                    assertEquals(400, sse.status, "GET with Mcp-Session-Id '$value': ${sse.text}")
                    assertTrue(sse.text.contains("Bad Request: No valid session ID provided"), "the SDK's message: ${sse.text}")
                }
            }
            assertEquals(0, f.orchestration.sessions.size)
        }
    }

    @Test
    fun `S4 probe an infinite idle timeout installs and serves a handshake`() {
        val f = fixture()
        testApplication {
            application {
                installMcpStreamableHttp(
                    f.orchestration,
                    f.appConfig,
                    mcpAuth = McpAuthHttpFixture.credentialMode(entries()),
                    sessionPrincipals = f.sessions,
                    idleTimeout = Duration.INFINITE
                )
            }
            with(f) {
                val session = client.handshake("/mcp", token)
                assertTrue(f.sdkSessions.isLive(session))
                assertEquals(200, client.rpc("/mcp", ping, "Bearer $token", session).status.value)
            }
        }
    }
}

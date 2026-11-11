package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.test.RawSseConnection
import io.github.jpicklyk.mcptask.current.test.awaitCondition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f98690c9, issue 682 "CLOSE_WAIT") for the `GET /api/v1/events` SSE stream: a client
 * that goes away without a clean end frees its [ApiEventBus] subscription and its socket, and the stream carries a heartbeat.
 * Scenario S5 of the frozen test-plan.
 *
 * Harness: a real CIO engine on port 0 with `install(SSE)` and [eventRoutes] (the declared full-wiring of the events route:
 * Ktor's `testApplication` cannot see a client disconnect, Ktor 3.6.1 HttpRequestLifecycle.kt:60), the client a raw
 * `java.net.Socket` ([RawSseConnection]) so the test controls a half-close and a close. The route is mounted under
 * [ApiAuthConfig.Unauthenticated] so the connection needs no credential and the stream is the only thing under test.
 *
 * Oracles (written from the frozen plan and the diagnosis "D2", never from a run): the events handler used to wait in
 * `flow.collect` with no heartbeat and no lifecycle plugin, so a vanished client was noticed only on the next event write, which
 * on an idle bus is never (the subscription and the socket leaked); the fix gives the route Ktor's `HttpRequestLifecycle` with
 * `cancelCallOnClose` (the engine cancels the call when its client disconnects, which cancels the collection and unsubscribes)
 * and an SSE heartbeat (Ktor ServerSSESession.kt:172-177 and :199-201: the first `: heartbeat` comment at once, then one per
 * period). The bus's `subscriberCount()` is the public observation of the unsubscribe.
 *
 * Negative-assertion conditions (what makes each fail without the fix): the count must first be seen at 1 (the subscription
 * exists, so "0 afterwards" is not vacuous); without the lifecycle plugin the idle bus never writes, the call is never cancelled
 * and the count stays at 1 past the 3 s deadline; without the heartbeat no `: heartbeat` line ever arrives.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no disjunctive "did nothing" assertions; every wait is a
 * bounded poll against a deadline.
 *
 * Domain invariants satisfied by construction: the [ApiEventBus] is built with its documented defaults over the test database's
 * event store; no work item or event is written, so no entity invariant is in play.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EventStreamDisconnectTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private class LiveEngine(
        val port: Int,
        private val stopper: () -> Unit
    ) : AutoCloseable {
        override fun close() = stopper()
    }

    private fun startEngine(bus: ApiEventBus): LiveEngine {
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(bus, authConfig = ApiAuthConfig.Unauthenticated, redactAttribution = true)
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
        return LiveEngine(port) { server.stop(0, 500) }
    }

    @Test
    fun `S5 a client that half-closes the events stream is unsubscribed and its socket is closed`() {
        val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
        startEngine(bus).use { live ->
            RawSseConnection(live.port, "/events").use { sse ->
                assertTrue(sse.awaitHead(3000), "the stream answers its head: ${sse.text}")
                assertEquals(200, sse.status, "fixture condition: the stream is open")
                assertTrue(awaitCondition(3000) { bus.subscriberCount() == 1 }, "fixture condition: the connection is subscribed")
                assertFalse(sse.readFor(300), "fixture condition: the server holds the stream open while the client is connected")

                sse.halfClose()
                assertTrue(awaitCondition(3000) { bus.subscriberCount() == 0 }, "the subscription is freed within 3 s of the FIN")
                assertTrue(sse.awaitEof(3000), "the server closes the connection of a client that sent FIN")
            }
        }
    }

    @Test
    fun `S5 a client that closes the events stream is unsubscribed`() {
        val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
        startEngine(bus).use { live ->
            val sse = RawSseConnection(live.port, "/events")
            try {
                assertTrue(sse.awaitHead(3000), "the stream answers its head: ${sse.text}")
                assertEquals(200, sse.status, "fixture condition: the stream is open")
                assertTrue(awaitCondition(3000) { bus.subscriberCount() == 1 }, "fixture condition: the connection is subscribed")
            } finally {
                sse.close()
            }
            assertTrue(awaitCondition(3000) { bus.subscriberCount() == 0 }, "the subscription is freed within 3 s of the close")
        }
    }

    @Test
    fun `S5 the first bytes of the events stream carry a heartbeat comment`() {
        val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
        startEngine(bus).use { live ->
            RawSseConnection(live.port, "/events").use { sse ->
                assertTrue(sse.awaitHead(3000), "the stream answers its head: ${sse.text}")
                assertEquals(200, sse.status)
                sse.awaitText(3000) { it.contains(": heartbeat") }
                assertTrue(sse.count(": heartbeat") >= 1, "the idle stream carries a heartbeat comment: ${sse.text}")
                assertFalse(sse.closedByServer)
            }
        }
    }

    @Test
    fun `S5 probe two clients that leave are both unsubscribed and a third that stays is not`() {
        val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
        startEngine(bus).use { live ->
            val stay = RawSseConnection(live.port, "/events")
            val leaveA = RawSseConnection(live.port, "/events")
            val leaveB = RawSseConnection(live.port, "/events")
            try {
                for (c in listOf(stay, leaveA, leaveB)) {
                    assertTrue(c.awaitHead(3000), "head: ${c.text}")
                    assertEquals(200, c.status)
                }
                assertTrue(awaitCondition(3000) { bus.subscriberCount() == 3 }, "fixture condition: three subscribers")
                leaveA.halfClose()
                leaveB.close()
                assertTrue(awaitCondition(3000) { bus.subscriberCount() == 1 }, "exactly the two departed clients are unsubscribed")
                assertFalse(stay.readFor(300), "the client that stayed is still connected")
                assertEquals(1, bus.subscriberCount())
            } finally {
                stay.close()
                leaveA.close()
                leaveB.close()
            }
        }
    }
}

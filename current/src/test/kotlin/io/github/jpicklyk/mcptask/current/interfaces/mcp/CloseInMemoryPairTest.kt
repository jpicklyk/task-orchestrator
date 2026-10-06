package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Regression for bug f664fd4f: tearing down an in-memory client/server pair must not race the
 * server transport's own event-loop close(), neither by throwing from teardown nor by leaking an
 * uncaught exception that kotlinx-coroutines-test pins on a later test.
 */
class CloseInMemoryPairTest {
    private class Pair(
        val client: Client,
        val server: Server,
    )

    private class Captured {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val thrown = CopyOnWriteArrayList<Throwable>()

        fun assertClean() {
            assertTrue(uncaught.isEmpty(), "uncaught exceptions: $uncaught")
            assertTrue(thrown.isEmpty(), "teardown threw: $thrown")
        }
    }

    private suspend fun connectedPair(toolName: String? = null): Pair {
        val server =
            Server(
                serverInfo = Implementation(name = "close-probe-server", version = "1.0.0"),
                options = inMemoryTestServerOptions(),
            )
        val client =
            Client(
                clientInfo = Implementation(name = "close-probe-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)
        client.connect(clientTransport)
        if (toolName != null) {
            server.addTool(name = toolName, description = "probe") { CallToolResult(content = emptyList()) }
        }
        return Pair(client, server)
    }

    private fun capturing(body: (Captured) -> Unit) {
        val captured = Captured()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> captured.uncaught += e }
        try {
            body(captured)
            // Event-loop close() runs on Dispatchers.Default; give a late failure time to surface.
            Thread.sleep(1000)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        captured.assertClean()
    }

    @Test
    fun `S1 parallel teardown of many pairs throws nothing and leaks nothing`() {
        capturing { captured ->
            runBlocking(Dispatchers.Default) {
                (0 until PARALLEL_ITERATIONS).chunked(PARALLELISM).forEach { chunk ->
                    chunk
                        .map { i ->
                            async {
                                try {
                                    val pair = connectedPair("probe_$i")
                                    closeInMemoryPair(pair.client, pair.server)
                                } catch (t: Throwable) {
                                    captured.thrown += t
                                }
                            }
                        }.awaitAll()
                }
            }
        }
    }

    @Test
    fun `S2 round trip then teardown succeeds repeatedly`() {
        capturing { captured ->
            repeat(SEQUENTIAL_ITERATIONS) { i ->
                runBlocking {
                    try {
                        val pair = connectedPair("echo_$i")
                        val result = pair.client.callTool(name = "echo_$i", arguments = emptyMap())
                        assertFalse(result.isError == true, "tool call reported isError")
                        closeInMemoryPair(pair.client, pair.server)
                    } catch (t: Throwable) {
                        captured.thrown += t
                    }
                }
            }
        }
    }

    @Test
    fun `S3 after teardown the server has no sessions and the client cannot call`() {
        capturing { captured ->
            runBlocking {
                try {
                    val pair = connectedPair("post_probe")
                    closeInMemoryPair(pair.client, pair.server)
                    assertTrue(pair.server.sessions.isEmpty(), "server sessions not empty: ${pair.server.sessions}")
                    assertFailsWith<Throwable>("callTool must fail after teardown") {
                        pair.client.callTool(name = "post_probe", arguments = emptyMap())
                    }
                } catch (t: Throwable) {
                    captured.thrown += t
                }
            }
        }
    }

    @Test
    fun `S4 server without sessions and never-connected client returns normally`() {
        capturing { captured ->
            runBlocking {
                try {
                    val server =
                        Server(
                            serverInfo = Implementation(name = "idle-server", version = "1.0.0"),
                            options = inMemoryTestServerOptions(),
                        )
                    val client =
                        Client(
                            clientInfo = Implementation(name = "idle-client", version = "1.0.0"),
                            options = ClientOptions(capabilities = ClientCapabilities()),
                        )
                    val elapsed = measureTime { closeInMemoryPair(client, server, 2000.milliseconds) }
                    assertTrue(elapsed < 4000.milliseconds, "helper took $elapsed")
                } catch (t: Throwable) {
                    captured.thrown += t
                }
            }
        }
    }

    @Test
    fun `S5 a session that never closes makes the helper fail loudly within the timeout`() {
        capturing { captured ->
            runBlocking {
                var client2: Client? = null
                var server2: Server? = null
                try {
                    val server =
                        Server(
                            serverInfo = Implementation(name = "two-client-server", version = "1.0.0"),
                            options = inMemoryTestServerOptions(),
                        )

                    server2 = server

                    suspend fun attach(label: String): Client {
                        val c =
                            Client(
                                clientInfo = Implementation(name = label, version = "1.0.0"),
                                options = ClientOptions(capabilities = ClientCapabilities()),
                            )
                        val (ct, st) = ChannelTransport.createLinkedPair()
                        server.createSession(st)
                        c.connect(ct)
                        return c
                    }
                    val client1 = attach("client-1")
                    client2 = attach("client-2")

                    var failure: Throwable? = null
                    val elapsed =
                        measureTime {
                            try {
                                closeInMemoryPair(client1, server, 300.milliseconds)
                            } catch (t: Throwable) {
                                failure = t
                            }
                        }
                    assertTrue(failure is TimeoutCancellationException, "expected timeout but got $failure")
                    assertTrue(elapsed < 2000.milliseconds, "helper took $elapsed")
                } catch (t: Throwable) {
                    captured.thrown += t
                } finally {
                    try {
                        val c2 = client2
                        val s2 = server2
                        if (c2 != null && s2 != null) {
                            // Closing client2 ends session 2; the helper then finds nothing left but the server.
                            closeInMemoryPair(c2, s2)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    @Test
    fun `S5b a timed-out helper does not close the server`() {
        capturing { captured ->
            runBlocking {
                var client2: Client? = null
                var server2: Server? = null
                try {
                    val server =
                        Server(
                            serverInfo = Implementation(name = "two-client-server-b", version = "1.0.0"),
                            options = inMemoryTestServerOptions(),
                        )
                    server2 = server

                    suspend fun attach(label: String): Client {
                        val c =
                            Client(
                                clientInfo = Implementation(name = label, version = "1.0.0"),
                                options = ClientOptions(capabilities = ClientCapabilities()),
                            )
                        val (ct, st) = ChannelTransport.createLinkedPair()
                        server.createSession(st)
                        c.connect(ct)
                        return c
                    }
                    val client1 = attach("client-1")
                    val client2Live = attach("client-2")
                    client2 = client2Live
                    server.addTool(name = "still_alive", description = "probe") { CallToolResult(content = emptyList()) }

                    var failure: Throwable? = null
                    try {
                        closeInMemoryPair(client1, server, 300.milliseconds)
                    } catch (t: Throwable) {
                        failure = t
                    }
                    assertTrue(failure is TimeoutCancellationException, "expected timeout but got $failure")

                    // server.close() was not called: client2's session is still registered and serving.
                    assertTrue(server.sessions.isNotEmpty(), "server sessions were cleared after timeout")
                    val result = client2Live.callTool(name = "still_alive", arguments = emptyMap())
                    assertFalse(result.isError == true, "client2 tool call reported isError")
                } catch (t: Throwable) {
                    captured.thrown += t
                } finally {
                    try {
                        val c2 = client2
                        val s2 = server2
                        if (c2 != null && s2 != null) {
                            closeInMemoryPair(c2, s2)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    @Test
    fun `S6 calling the helper twice on the same pair is fine`() {
        capturing { captured ->
            runBlocking {
                try {
                    val pair = connectedPair("twice")
                    closeInMemoryPair(pair.client, pair.server)
                    closeInMemoryPair(pair.client, pair.server)
                } catch (t: Throwable) {
                    captured.thrown += t
                }
            }
        }
    }

    private companion object {
        const val PARALLEL_ITERATIONS = 2000
        const val PARALLELISM = 32
        const val SEQUENTIAL_ITERATIONS = 200
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Regression for bug 947cc2ec: adding a tool to a connected in-memory session and tearing the
 * session down immediately — exactly what every `registerToolWithServer`-after-connect adapter test
 * does — must not leak an uncaught `McpException: Transport is not ready` from the SDK's
 * list_changed notification job. With `listChanged = true` in [inMemoryTestServerOptions] this
 * leaked on most of the iterations below; the leak is what kotlinx-coroutines-test surfaced as
 * `UncaughtExceptionsBeforeTest` in an unrelated later `runTest` test.
 */
class InMemoryTestServerOptionsTest {
    @Test
    fun `adding a tool to a connected session and tearing down at once leaks no uncaught exception`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            repeat(ITERATIONS) { i ->
                runBlocking {
                    val server =
                        Server(
                            serverInfo = Implementation(name = "leak-probe-server", version = "1.0.0"),
                            options = inMemoryTestServerOptions(),
                        )
                    val client =
                        Client(
                            clientInfo = Implementation(name = "leak-probe-client", version = "1.0.0"),
                            options = ClientOptions(capabilities = ClientCapabilities()),
                        )
                    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
                    server.createSession(serverTransport)
                    client.connect(clientTransport)
                    server.addTool(name = "probe_$i", description = "probe") { CallToolResult(content = emptyList()) }
                    closeInMemoryPair(client, server)
                }
            }
            // The notification job runs on Dispatchers.Default after teardown; give it time to fail.
            Thread.sleep(500)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertTrue(uncaught.isEmpty(), "uncaught exceptions leaked from the SDK notification job: $uncaught")
    }

    private companion object {
        const val ITERATIONS = 50
    }
}

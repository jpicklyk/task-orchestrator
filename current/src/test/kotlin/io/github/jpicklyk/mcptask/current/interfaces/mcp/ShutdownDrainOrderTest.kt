package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S17 (AR-77 evidence / plan v4-phase1-core 3.10): the shutdown drain is LIFO, so for the server to
 * stop HTTP first, then close the MCP server, and close the database LAST, the cleanup actions must
 * be REGISTERED database first, then MCP server, then (HTTP only) the HTTP server. A spy on the
 * coordinator records registration order; the LIFO drain itself is proven in ShutdownCoordinatorTest.
 */
class ShutdownDrainOrderTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun recordingCoordinator(names: MutableList<String>): ShutdownCoordinator {
        val spy = spyk(ShutdownCoordinator())
        every { spy.addCleanupAction(any(), any()) } answers {
            names += firstArg<String>()
            callOriginal()
        }
        return spy
    }

    private fun config(
        tempDir: Path,
        transport: String
    ): AppConfig {
        val env =
            mapOf(
                "DATABASE_PATH" to tempDir.resolve("drain-${System.nanoTime()}.db").toString(),
                "MCP_TRANSPORT" to transport,
                "MCP_HTTP_HOST" to "127.0.0.1",
                "MCP_HTTP_PORT" to "0",
                "READINESS_FILE" to tempDir.resolve("ready").toString()
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `S17 stdio registers Close Database first and Close MCP Server after it, with no HTTP stop`(
        @TempDir tempDir: Path
    ) {
        val names = CopyOnWriteArrayList<String>()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = recordingCoordinator(names),
                appConfig = config(tempDir, "stdio"),
                stdioInput = { ByteArrayInputStream(ByteArray(0)) },
                stdioOutput = { ByteArrayOutputStream() }
            )

        assertEquals(Started, server.run())

        val db = names.indexOf("Close Database")
        val mcp = names.indexOf("Close MCP Server")
        assertTrue(db >= 0 && mcp >= 0, "both cleanup actions must be registered, got $names")
        assertEquals(0, db, "the database must be registered FIRST so a LIFO drain closes it last: $names")
        assertTrue(db < mcp, "Close MCP Server must be registered after Close Database: $names")
        assertTrue("Stop HTTP Server" !in names, "stdio must not register an HTTP stop: $names")
    }

    @Test
    @Timeout(90, unit = TimeUnit.SECONDS)
    fun `S17 http registers Close Database then Close MCP Server then Stop HTTP Server`(
        @TempDir tempDir: Path
    ) {
        val names = CopyOnWriteArrayList<String>()
        val coordinator = recordingCoordinator(names)
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig = config(tempDir, "http")
            )
        val thread = Thread { runCatching { server.run() } }
        thread.isDaemon = true
        thread.start()
        try {
            val deadline = System.currentTimeMillis() + 60_000
            while ("Stop HTTP Server" !in names && System.currentTimeMillis() < deadline) Thread.sleep(50)

            val db = names.indexOf("Close Database")
            val mcp = names.indexOf("Close MCP Server")
            val http = names.indexOf("Stop HTTP Server")
            assertTrue(db >= 0 && mcp >= 0 && http >= 0, "all three cleanup actions must be registered, got $names")
            assertEquals(0, db, "Close Database must be registered first (drained last): $names")
            assertTrue(db < mcp && mcp < http, "registration order must be Database, MCP, HTTP (drain = HTTP, MCP, Database): $names")
        } finally {
            coordinator.initiateShutdown("test cleanup")
            coordinator.awaitCompletion(20_000)
            thread.join(20_000)
        }
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding F-009: in stdio mode the server never exited when stdin reached EOF. Oracle: the finding
 * and the frozen decisions D1-D6 - on EOF run() must return Started, the readiness marker must be
 * cleared, and the shutdown coordinator (if any) must have run its cleanup. While stdin is open the
 * server must keep running with the marker present.
 *
 * run() is executed on a daemon thread with bounded joins so a hang fails the test instead of
 * wedging the suite. The stdio streams are supplied through the stdioInput/stdioOutput seams.
 */
class StdioEofShutdownTest {
    private val joinTimeoutMs = 15_000L

    private fun appConfigFor(
        tempDir: Path,
        readinessFile: Path
    ): AppConfig {
        val dbPath = tempDir.resolve("stdio-eof-${System.nanoTime()}.db").toString()
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "MCP_TRANSPORT" to "stdio",
                "READINESS_FILE" to readinessFile.toString()
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    private class Running(
        val thread: Thread,
        val outcome: AtomicReference<Any?>,
        val failure: AtomicReference<Throwable?>
    )

    private fun startRun(server: CurrentMcpServer): Running {
        val outcome = AtomicReference<Any?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val thread =
            Thread {
                try {
                    outcome.set(server.run())
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        thread.isDaemon = true
        thread.name = "stdio-eof-test-run"
        thread.start()
        return Running(thread, outcome, failure)
    }

    private fun awaitFile(
        path: Path,
        exists: Boolean,
        timeoutMs: Long = 10_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path) == exists) return true
            Thread.sleep(50)
        }
        return Files.exists(path) == exists
    }

    private fun assertReturnedStarted(r: Running) {
        r.thread.join(joinTimeoutMs)
        assertFalse(r.thread.isAlive, "run() did not return within ${joinTimeoutMs}ms of stdin EOF")
        assertEquals(null, r.failure.get(), "run() threw: ${r.failure.get()}")
        assertEquals(Started, r.outcome.get(), "expected Started, got ${r.outcome.get()}")
    }

    @Test
    fun `S1 empty stdin returns Started and leaves no readiness marker`(
        @TempDir tempDir: Path
    ) {
        val marker = tempDir.resolve("ready")
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = appConfigFor(tempDir, marker),
                stdioInput = { ByteArrayInputStream(ByteArray(0)) },
                stdioOutput = { ByteArrayOutputStream() }
            )

        assertReturnedStarted(startRun(server))
        assertFalse(Files.exists(marker), "readiness marker must be cleared after EOF shutdown")
    }

    @Test
    fun `S2 stdin EOF initiates coordinator shutdown and cleanup completes`(
        @TempDir tempDir: Path
    ) {
        val marker = tempDir.resolve("ready")
        val coordinator = ShutdownCoordinator()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig = appConfigFor(tempDir, marker),
                stdioInput = { ByteArrayInputStream(ByteArray(0)) },
                stdioOutput = { ByteArrayOutputStream() }
            )

        assertReturnedStarted(startRun(server))
        assertTrue(coordinator.isShutdownInitiated(), "stdin EOF must initiate shutdown via the coordinator")
        assertTrue(coordinator.awaitCompletion(5000), "coordinator cleanup must complete after EOF shutdown")
        assertFalse(Files.exists(marker), "readiness marker must be cleared after EOF shutdown")
    }

    @Test
    fun `S3 server keeps running while stdin is open and exits only when it closes`(
        @TempDir tempDir: Path
    ) {
        val marker = tempDir.resolve("ready")
        val pipeIn = PipedInputStream(8192)
        val pipeOut = PipedOutputStream(pipeIn)
        val coordinator = ShutdownCoordinator()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig = appConfigFor(tempDir, marker),
                stdioInput = { pipeIn },
                stdioOutput = { ByteArrayOutputStream() }
            )

        val r = startRun(server)
        try {
            assertTrue(awaitFile(marker, exists = true), "readiness marker must appear once the transport is up")
            Thread.sleep(1500)
            assertTrue(r.thread.isAlive, "run() must keep running while stdin is open")
            assertTrue(Files.exists(marker), "marker must stay present while stdin is open")
            assertFalse(coordinator.isShutdownInitiated(), "no shutdown may start before EOF")
        } finally {
            pipeOut.close()
        }

        assertReturnedStarted(r)
        assertTrue(awaitFile(marker, exists = false), "marker must be cleared after stdin closes")
        assertTrue(coordinator.isShutdownInitiated(), "closing stdin must initiate shutdown")
    }

    @Test
    fun `S4 stdin closed after an initialize request still shuts down`(
        @TempDir tempDir: Path
    ) {
        val marker = tempDir.resolve("ready")
        val coordinator = ShutdownCoordinator()
        val initialize =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                """"capabilities":{},"clientInfo":{"name":"eof-test","version":"1"}}}""" + "\n"
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig = appConfigFor(tempDir, marker),
                stdioInput = { ByteArrayInputStream(initialize.toByteArray(Charsets.UTF_8)) },
                stdioOutput = { ByteArrayOutputStream() }
            )

        assertReturnedStarted(startRun(server))
        assertTrue(coordinator.isShutdownInitiated(), "EOF after a session must initiate shutdown")
        assertTrue(coordinator.awaitCompletion(5000), "coordinator cleanup must complete")
        assertFalse(Files.exists(marker), "readiness marker must be cleared")
    }
}

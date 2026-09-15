package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 56593660 — an exception thrown while starting the stdio
 * or http transport must yield [Failed] with [Reason.TRANSPORT_START] and the frozen detail
 * "Failed to start <stdio|http> transport: <message>", the readiness marker must be absent
 * afterwards even if a stale one existed before, and `CurrentMain` must map the outcome to a
 * non-zero exit via [StartupFailedException]. Before the fix, [CurrentMcpServer.run] reported
 * [Started] even when the transport never came up.
 *
 * Oracle source: the item's frozen `test-plan` note (queue phase) plus
 * `current/docs/fleet-deployment.md`'s "Startup, Readiness, and Health Checks" section — never the
 * implementation. The seam under test is the constructor parameter
 * `internal val onBeforeTransportStart: (String) -> Unit = {}` on [CurrentMcpServer], invoked as
 * the first statement inside the try that wraps the real transport start for each transport
 * (`"stdio"` / `"http"`). A hook that throws stands in for a genuine transport-bind failure without
 * actually binding a port or blocking on real stdin.
 *
 * ## Scope
 * S1-S9 and the adversarial probes below are all covered here. Not covered: a live-transport
 * `Started` outcome after a successful bind, and clearing the marker after a *post-start* failure
 * — both need a real bind/real stdin, the same limit `StartupOutcomeTest` documents for its own
 * S1. `ReadinessMarkerTest` covers `markReady`/`clear` at the unit level.
 */
class TransportStartFailureTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Real, freshly migrated temp-file SQLite database (mirrors `StartupOutcomeTest`'s
     * `invalidTransportAppConfig`) plus a `READINESS_FILE` pointed at a location under [tempDir].
     */
    private fun appConfigFor(
        tempDir: Path,
        transport: String,
        readinessFile: Path = tempDir.resolve("ready")
    ): AppConfig {
        val dbPath = tempDir.resolve("transport-start-failure-${System.nanoTime()}.db").toString()
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "MCP_TRANSPORT" to transport,
                "READINESS_FILE" to readinessFile.toString()
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    // ---- S1 / S2: a transport-start exception maps to Failed(TRANSPORT_START), not Started ----

    @Test
    fun `S1 http transport start throwing fails startup as TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http"),
                onBeforeTransportStart = { throw IllegalStateException("bind boom") }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
        assertTrue(outcome.detail.contains("http"), "detail must name the transport: ${outcome.detail}")
        assertTrue(outcome.detail.contains("bind boom"), "detail must carry the exception message: ${outcome.detail}")
    }

    @Test
    fun `S2 stdio transport start throwing fails startup as TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "stdio"),
                onBeforeTransportStart = { throw IllegalStateException("bind boom") }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
        assertTrue(outcome.detail.contains("stdio"), "detail must name the transport: ${outcome.detail}")
        assertTrue(outcome.detail.contains("bind boom"), "detail must carry the exception message: ${outcome.detail}")
    }

    // ---- S3: no readiness marker after either transport-start failure ----

    @Test
    fun `S3 readiness marker absent after a transport-start failure`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")

        val httpServer =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http", readinessFile),
                onBeforeTransportStart = { throw IllegalStateException("bind boom") }
            )
        httpServer.run()
        assertFalse(
            Files.exists(readinessFile),
            "readiness marker must not exist after an http transport-start failure"
        )

        val stdioServer =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "stdio", readinessFile),
                onBeforeTransportStart = { throw IllegalStateException("bind boom") }
            )
        stdioServer.run()
        assertFalse(
            Files.exists(readinessFile),
            "readiness marker must not exist after a stdio transport-start failure"
        )
    }

    // ---- S4: a STALE pre-existing marker is cleared by a transport-start failure ----

    @Test
    fun `S4 a stale pre-existing readiness marker is cleared by a transport-start failure`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")
        Files.writeString(readinessFile, "ready")
        assertTrue(Files.exists(readinessFile), "precondition: stale marker must exist before run()")

        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http", readinessFile),
                onBeforeTransportStart = { throw IllegalStateException("bind boom") }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
        assertFalse(
            Files.exists(readinessFile),
            "a never-served process must not report healthy -- a stale marker must be cleared on failure"
        )
    }

    // ---- S5: StartupFailedException wraps a TRANSPORT_START Failed as a plain RuntimeException ----

    @Test
    fun `S5 StartupFailedException wraps a TRANSPORT_START Failed outcome`() {
        val failure = Failed(Reason.TRANSPORT_START, "Failed to start http transport: bind boom")

        val exception: RuntimeException = StartupFailedException(failure)

        assertEquals(failure, (exception as StartupFailedException).failure)
        assertEquals(failure.detail, exception.message)
        // main() itself is not invoked here -- same out-of-scope limit as wave-1's S6.
    }

    // ---- S6: no regression -- the pre-existing failure reasons are untouched ----

    @Test
    fun `S6 database initialize failure still reports DATABASE_INIT, not TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns false

        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http")
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATABASE_INIT, outcome.reason)
        assertTrue(outcome.reason != Reason.TRANSPORT_START)
    }

    @Test
    fun `S6 schema update failure still reports SCHEMA_UPDATE, not TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false

        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http")
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
        assertTrue(outcome.reason != Reason.TRANSPORT_START)
    }

    @Test
    fun `S6 unknown transport still reports UNKNOWN_TRANSPORT, not TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "sse")
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.UNKNOWN_TRANSPORT, outcome.reason)
        assertTrue(outcome.reason != Reason.TRANSPORT_START)
    }

    @Test
    fun `S6 Reason still declares all four wave-1 names`() {
        val names = Reason.entries.map { it.name }.toSet()
        assertTrue(
            names.containsAll(listOf("DATABASE_INIT", "SCHEMA_UPDATE", "UNKNOWN_TRANSPORT", "READINESS_MARKER")),
            "Reason must still declare every wave-1 name, got $names"
        )
    }

    // ---- S7: the default onBeforeTransportStart hook is a true no-op ----

    @Test
    fun `S7 default onBeforeTransportStart hook returns normally`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http")
            )

        // Exercising the seam directly, not through run(), avoids binding a real transport. No
        // exception == the default lambda is a genuine no-op.
        server.onBeforeTransportStart("http")
    }

    // ---- Adversarial probes ----

    @Test
    fun `probe null exception message still fails as TRANSPORT_START with a 'null' detail`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http"),
                onBeforeTransportStart = { throw IllegalStateException(null as String?) }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
        assertTrue(
            outcome.detail.contains("null"),
            "detail must render a null exception message as 'null': ${outcome.detail}"
        )
    }

    @Test
    fun `probe CancellationException from the hook still fails as TRANSPORT_START`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http"),
                onBeforeTransportStart = { throw CancellationException("cancelled") }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
    }

    @Test
    fun `probe an Error from the hook propagates instead of being silently swallowed`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "http"),
                onBeforeTransportStart = { throw Error("fatal") }
            )

        assertFailsWith<Error> { server.run() }
    }

    @Test
    fun `probe MCP_TRANSPORT=HTTP dispatches the http branch and passes the lowercased literal to the hook`(
        @TempDir tempDir: Path
    ) {
        var observed: String? = null
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = appConfigFor(tempDir, "HTTP"),
                onBeforeTransportStart = { transport ->
                    observed = transport
                    throw IllegalStateException("bind boom")
                }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason)
        assertEquals("http", observed)
    }
}

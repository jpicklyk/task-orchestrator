package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 56ac1690 — every startup failure branch of
 * [CurrentMcpServer.run] must yield a [Failed] outcome instead of the pre-fix behavior of logging
 * and returning (which left `main()` exiting 0 on a server that never came up).
 *
 * Oracle source: the item's frozen `test-plan` note (queue phase), not this implementation. Scope
 * per the test-plan's file split: S1, S3-S9 here; S2/S10 in `ReadinessMarkerTest`; S11 in
 * `DockerHealthcheckTest`.
 *
 * ## Scenario coverage and deliberate scope limits
 * - S3/S4 (`DATABASE_INIT`/`SCHEMA_UPDATE`) use `mockkConstructor` on [DatabaseManager] to pin the
 *   exact precondition ("initialize() returns false" / "updateSchema() returns false") — `run()`
 *   returns immediately after either check fails, before touching any other collaborator, so no
 *   other method needs stubbing.
 * - S5/S8/S9/probes (`UNKNOWN_TRANSPORT`) run the REAL [CurrentMcpServer] end-to-end against a
 *   real, freshly migrated temp-file SQLite database (default `useFlyway=true`) — `run()` builds
 *   the full [ServerComposition] object graph before it ever inspects `MCP_TRANSPORT`, so a mocked
 *   `DatabaseManager` would need to fake an unknown-depth call surface through that graph. A real,
 *   disposable DB sidesteps that risk entirely and exercises the true code path.
 * - S1 ("stdio+DB ok -> Started, marker present") and the live-transport half of S7 are
 *   **not covered** here: reaching `Started` requires a transport to actually bind (stdio blocks
 *   on real process stdin; http binds a real port via an embedded Ktor server) — exercising either
 *   deterministically without risking a hung or flaky CI test is out of scope for this suite. The
 *   *parsing* precondition behind S7 (that `"HTTP"` and `"STDIO"` resolve identically to their
 *   lowercase form) is covered directly against [AppConfig]. S2 (marker cleared on clean shutdown)
 *   and S10 (unwritable readiness dir) are covered at the [io.github.jpicklyk.mcptask.current.infrastructure.health.ReadinessMarker]
 *   unit level in `ReadinessMarkerTest`, which does not need a live transport either.
 * - S6 (`main()` maps `Failed` to a thrown [StartupFailedException], never `exitProcess`) is
 *   covered at the exception-contract level: constructing [StartupFailedException] from a [Failed]
 *   and asserting it is a plain [RuntimeException] whose message is the failure detail. Invoking
 *   `CurrentMain.main()` itself would start a real server and is out of scope for the same reason
 *   as S1.
 *
 * S8's "no trim" assumption was verified against [AppConfig]'s own field declaration ordering
 * before writing this suite (`mcpTransport = env("MCP_TRANSPORT")?.lowercase() ?: "stdio"` — no
 * `.trim()`); per the test-plan's arbitration instruction, if a future implementer adds trimming,
 * this must be escalated for arbitration, never silently retargeted.
 */
class StartupOutcomeTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ---- S3 / S4: database-layer failures short-circuit run() before any other collaborator ----

    @Test
    fun `S3 initialize returning false fails startup as DATABASE_INIT`() {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns false

        val server = CurrentMcpServer(version = "test", shutdownCoordinator = null, appConfig = AppConfig.fromEnv { null })
        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATABASE_INIT, outcome.reason)
    }

    @Test
    fun `S4 updateSchema returning false fails startup as SCHEMA_UPDATE`() {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false

        val server = CurrentMcpServer(version = "test", shutdownCoordinator = null, appConfig = AppConfig.fromEnv { null })
        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
    }

    // ---- S5 / S8 / S9 / probes: unrecognized MCP_TRANSPORT, real DB, real transport dispatch ----

    /**
     * Builds a real [AppConfig] pointing at a fresh temp-file SQLite database (so `initialize()`
     * and `updateSchema()` genuinely succeed) with the given (deliberately invalid) transport
     * value. Never pass a value that resolves to "stdio" or "http" after lowercasing — that would
     * dispatch into a real transport instead of the `UNKNOWN_TRANSPORT` branch under test.
     */
    private fun invalidTransportAppConfig(
        tempDir: Path,
        transportValue: String
    ): AppConfig {
        val dbPath = tempDir.resolve("startup-outcome-${System.nanoTime()}.db").toString()
        val env = mapOf("DATABASE_PATH" to dbPath, "MCP_TRANSPORT" to transportValue)
        return AppConfig.fromEnv { key -> env[key] }
    }

    @ParameterizedTest(name = "MCP_TRANSPORT=\"{0}\" -> Failed(UNKNOWN_TRANSPORT)")
    @ValueSource(
        strings = [
            // S5: an unrelated but plausible transport name.
            "sse",
            // S8: lowercase does NOT trim -- surrounding whitespace keeps this invalid.
            " http ",
            // S9: present but empty differs from absent (which defaults to "stdio").
            "",
            // Adversarial probes: boolean-ish and near-miss values, none of which are "stdio"/"http".
            " TRUE ",
            "Yes",
            "on",
            "2",
            "stdio\n",
        ]
    )
    fun `unrecognized or malformed MCP_TRANSPORT fails startup as UNKNOWN_TRANSPORT`(
        transportValue: String,
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = invalidTransportAppConfig(tempDir, transportValue)
            )
        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed for MCP_TRANSPORT='$transportValue', got $outcome")
        assertEquals(Reason.UNKNOWN_TRANSPORT, outcome.reason)
    }

    // ---- S9 (absent case) / S7 / probe "STDIO": parsing-only, no live transport ----

    @Test
    fun `S9 absent MCP_TRANSPORT defaults to stdio (distinct from present-but-empty)`() {
        assertEquals("stdio", AppConfig.fromEnv { null }.mcpTransport)
    }

    @Test
    fun `S7 uppercase HTTP resolves to http via lowercase`() {
        assertEquals(
            "http",
            AppConfig.fromEnv { key -> if (key == "MCP_TRANSPORT") "HTTP" else null }.mcpTransport
        )
    }

    @Test
    fun `probe uppercase STDIO resolves to stdio via lowercase`() {
        assertEquals(
            "stdio",
            AppConfig.fromEnv { key -> if (key == "MCP_TRANSPORT") "STDIO" else null }.mcpTransport
        )
    }

    // ---- S6: main() maps a Failed outcome to a thrown StartupFailedException, never exitProcess ----

    @Test
    fun `S6 StartupFailedException wraps the Failed outcome as a plain RuntimeException`() {
        val failure = Failed(Reason.UNKNOWN_TRANSPORT, "Unknown MCP_TRANSPORT: 'sse'. Valid values: stdio, http")

        // StartupFailedException declares `: RuntimeException(...)` directly (see the NEW file's
        // signature) -- the supertype itself is the guarantee that main()'s existing
        // `catch (e: Exception)` / rethrow keeps working without an exitProcess special-case.
        val exception = StartupFailedException(failure)

        assertEquals(failure, exception.failure)
        assertEquals(failure.detail, exception.message)
    }
}

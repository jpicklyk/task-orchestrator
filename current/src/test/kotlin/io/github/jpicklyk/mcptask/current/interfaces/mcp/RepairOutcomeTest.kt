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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-author suite for item dc7693e1 (AR-77): `FLYWAY_REPAIR=true` must complete as a SUCCESS
 * outcome ([RepairCompleted]), not a [Failed] one, and the process must exit 0 WITHOUT ever binding
 * a transport or writing the readiness marker — [CurrentMcpServer.run] returns before
 * `ServerComposition` is built.
 *
 * Oracle: the item's frozen `task-scope` note (queue phase). [RepairCompleted] is a brand-new
 * success subtype specifically because the previously-considered `Failed(Reason.REPAIR_ONLY)` would
 * make `CurrentMain` exit non-zero, breaking scripts/CI that treat a completed repair as success.
 */
class RepairOutcomeTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun repairAppConfig(
        tempDir: Path,
        useFlyway: String = "true",
        readinessFile: Path = tempDir.resolve("ready")
    ): AppConfig {
        val dbPath = tempDir.resolve("repair-outcome-${System.nanoTime()}.db").toString()
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "FLYWAY_REPAIR" to "true",
                "USE_FLYWAY" to useFlyway,
                "READINESS_FILE" to readinessFile.toString()
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    // ---- S3: FLYWAY_REPAIR=true on a fresh DB yields RepairCompleted, no marker, no transport ----

    @Test
    fun `S3 FLYWAY_REPAIR=true returns RepairCompleted without writing the readiness marker`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")
        var transportStarted = false

        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = repairAppConfig(tempDir, readinessFile = readinessFile),
                onBeforeTransportStart = { transportStarted = true }
            )

        val outcome = server.run()

        assertEquals(RepairCompleted, outcome, "expected RepairCompleted, got $outcome")
        assertFalse(Files.exists(readinessFile), "readiness marker must not be written for a repair-only run")
        assertFalse(transportStarted, "no transport should ever be started for a repair-only run")
    }

    @Test
    fun `S3 RepairCompleted is not a Failed outcome`(
        @TempDir tempDir: Path
    ) {
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = repairAppConfig(tempDir)
            )

        val outcome = server.run()

        assertTrue(outcome !is Failed, "a completed repair must not be a Failed outcome, got $outcome")
    }

    // ---- S4: repair failure (FlywayException from an unusable DB path) still fails as SCHEMA_UPDATE ----

    @Test
    fun `S4 a repair failure still reports Failed SCHEMA_UPDATE, not RepairCompleted`(
        @TempDir tempDir: Path
    ) {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false

        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = repairAppConfig(tempDir)
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
    }

    // ---- S5: FLYWAY_REPAIR=true + USE_FLYWAY=false does not short-circuit (repair is ignored) ----
    //
    // NOTE: every case here MUST pin MCP_TRANSPORT to a value that fails fast (an unrecognized
    // transport name) rather than leaving it unset. With USE_FLYWAY=false the repair short-circuit
    // correctly does not fire, so run() proceeds to the transport dispatch -- an unset/"stdio"
    // MCP_TRANSPORT would then start a REAL stdio transport and block forever waiting on this test
    // JVM's stdin (this hung the full suite once; see repairAppConfig's lack of use here).

    @Test
    fun `S5 probe FLYWAY_REPAIR=true with USE_FLYWAY=false proceeds past the repair check to transport dispatch`(
        @TempDir tempDir: Path
    ) {
        val dbPath = tempDir.resolve("repair-outcome-probe-${System.nanoTime()}.db").toString()
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "FLYWAY_REPAIR" to "true",
                "USE_FLYWAY" to "false",
                "MCP_TRANSPORT" to "sse"
            )
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = null,
                appConfig = AppConfig.fromEnv { key -> env[key] }
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(
            Reason.UNKNOWN_TRANSPORT,
            outcome.reason,
            "reaching UNKNOWN_TRANSPORT proves the repair short-circuit did not fire under USE_FLYWAY=false"
        )
    }
}

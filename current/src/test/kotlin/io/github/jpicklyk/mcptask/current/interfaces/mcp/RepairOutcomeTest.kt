package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
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
        extraEnv: Map<String, String> = emptyMap(),
        readinessFile: Path = tempDir.resolve("ready")
    ): AppConfig {
        val dbPath = tempDir.resolve("repair-outcome-${System.nanoTime()}.db").toString()
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "FLYWAY_REPAIR" to "true",
                "READINESS_FILE" to readinessFile.toString()
            ) + extraEnv
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
                shutdownCoordinator = ShutdownCoordinator(),
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
                shutdownCoordinator = ShutdownCoordinator(),
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
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = repairAppConfig(tempDir)
            )

        val outcome = server.run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
    }

    // ---- S5 (rewritten for Flyway-only): USE_FLYWAY=false is ignored, so repair still completes ----
    //
    // Oracle: plan v4-phase1-core section 6 ("USE_FLYWAY is ignored") and 3.10 -- there is no Direct
    // mode left to short-circuit the repair path, so FLYWAY_REPAIR=true yields RepairCompleted
    // whatever USE_FLYWAY says. Readiness marker and transport stay untouched, as in S3.

    @Test
    fun `S5 FLYWAY_REPAIR=true with USE_FLYWAY=false still returns RepairCompleted`(
        @TempDir tempDir: Path
    ) {
        val readinessFile = tempDir.resolve("ready")
        var transportStarted = false
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = repairAppConfig(tempDir, readinessFile = readinessFile, extraEnv = mapOf("USE_FLYWAY" to "false")),
                onBeforeTransportStart = { transportStarted = true }
            )

        val outcome = server.run()

        assertEquals(RepairCompleted, outcome, "USE_FLYWAY=false must not change the repair outcome, got $outcome")
        assertFalse(Files.exists(readinessFile), "readiness marker must not be written for a repair-only run")
        assertFalse(transportStarted, "no transport should be started for a repair-only run")
    }
}

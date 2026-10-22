package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.BackupOutcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item ff08e631 (P7), scenario S6: a failed pre-upgrade backup stops startup as
 * [Reason.UPGRADE_BACKUP] before the schema is touched.
 *
 * Oracles: the item's frozen `test-plan` (S6; O4 the StartupOutcome contract in fleet-deployment "Startup, Readiness,
 * and Health Checks": a failed startup step yields a [Failed] outcome and no readiness marker) and `task-scope`
 * acceptance (c): "`Reason.UPGRADE_BACKUP` is produced, and `updateSchema()` is never reached, when the backup fails".
 * D1: the backup runs between `initialize` and `updateSchema`.
 *
 * Same constructor-mock pattern as `StartupOutcomeTest` (S3/S4): `run()` returns right after the failing step, before
 * any other collaborator, so only the three database calls need stubbing. The control tests pin that the gate is
 * specific: every non-failed outcome still reaches `updateSchema`.
 */
class UpgradeBackupOutcomeTest {
    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun server(): CurrentMcpServer {
        val env = mapOf("READINESS_FILE" to dir.resolve("ready").toString())
        return CurrentMcpServer(
            version = "test",
            shutdownCoordinator = ShutdownCoordinator(),
            appConfig = AppConfig.fromEnv { key -> env[key] }
        )
    }

    @Test
    fun `S6 a failed backup fails startup as UPGRADE_BACKUP without reaching updateSchema or the readiness marker`() {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().runPreUpgradeBackup() } returns BackupOutcome.Failed("boom")
        every { anyConstructed<DatabaseManager>().updateSchema() } returns true

        val outcome = server().run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.UPGRADE_BACKUP, outcome.reason)
        assertTrue(outcome.detail.contains("boom"), "the backup's detail is carried into the outcome: ${outcome.detail}")
        verify(exactly = 1) { anyConstructed<DatabaseManager>().runPreUpgradeBackup() }
        verify(exactly = 0) { anyConstructed<DatabaseManager>().updateSchema() }
        assertFalse(Files.exists(dir.resolve("ready")), "no readiness marker after a failed backup")
    }

    @Test
    fun `S6 the backup runs after initialize and before updateSchema`() {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().runPreUpgradeBackup() } returns BackupOutcome.SkippedFreshDatabase
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false

        val outcome = server().run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason)
        verifyOrder {
            anyConstructed<DatabaseManager>().initialize(any())
            anyConstructed<DatabaseManager>().runPreUpgradeBackup()
            anyConstructed<DatabaseManager>().updateSchema()
        }
    }

    @Test
    fun `S6 a failed initialize never attempts the backup`() {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns false
        every { anyConstructed<DatabaseManager>().runPreUpgradeBackup() } returns BackupOutcome.Failed("must not be asked")

        val outcome = server().run()

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.DATABASE_INIT, outcome.reason)
        verify(exactly = 0) { anyConstructed<DatabaseManager>().runPreUpgradeBackup() }
    }

    private fun assertReachesUpdateSchema(backup: BackupOutcome) {
        mockkConstructor(DatabaseManager::class)
        every { anyConstructed<DatabaseManager>().initialize(any()) } returns true
        every { anyConstructed<DatabaseManager>().runPreUpgradeBackup() } returns backup
        every { anyConstructed<DatabaseManager>().updateSchema() } returns false

        val outcome = server().run()

        assertTrue(outcome is Failed, "expected Failed (updateSchema stubbed false), got $outcome")
        assertEquals(Reason.SCHEMA_UPDATE, outcome.reason, "backup outcome $backup must not stop startup as UPGRADE_BACKUP")
        verify(exactly = 1) { anyConstructed<DatabaseManager>().updateSchema() }
    }

    @Test
    fun `S6 control Copied proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.Copied(File("x.bak")))
    }

    @Test
    fun `S6 control SkippedNotFileDb proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedNotFileDb)
    }

    @Test
    fun `S6 control SkippedFreshDatabase proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedFreshDatabase)
    }

    @Test
    fun `S6 control SkippedAlreadyUpgraded proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedAlreadyUpgraded)
    }

    @Test
    fun `S6 control SkippedDisabled proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedDisabled)
    }

    @Test
    fun `S6 control SkippedNoMigration proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedNoMigration)
    }

    @Test
    fun `S6 control SkippedSchemaMode proceeds to updateSchema`() {
        assertReachesUpdateSchema(BackupOutcome.SkippedSchemaMode)
    }
}

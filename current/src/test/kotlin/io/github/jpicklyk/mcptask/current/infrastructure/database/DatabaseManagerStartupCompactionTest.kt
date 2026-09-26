package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item f8a592df: wiring scenarios for the one-time startup
 * compaction gate at the [DatabaseManager] integration level (mode selection and opt-out flags),
 * as opposed to [StartupCompactionTest] which exercises `StartupCompaction.runOnce` directly.
 *
 * Oracle: the item's `task-scope` note — `DatabaseManager.updateSchema()` invokes
 * `StartupCompaction.runOnce` only when `schemaManager is FlywayDatabaseSchemaManager &&
 * !appConfig.flywayRepair && appConfig.dbCompactOnUpgrade`; `AppConfig.dbCompactOnUpgrade`
 * defaults to `true` and is backed by the `DB_COMPACT_ON_UPGRADE` env var
 * (`EnvBoolean.parse("DB_COMPACT_ON_UPGRADE", env("DB_COMPACT_ON_UPGRADE"), true)`).
 *
 * All scenarios here are NEW-SURFACE: `AppConfig.dbCompactOnUpgrade` is a field this item
 * introduces. Per the test-plan's narrowest-revert recipe, red is obtained by reverting only the
 * `updateSchema()` call site that invokes `StartupCompaction.runOnce` (or by defaulting
 * `dbCompactOnUpgrade` off) while leaving the field itself and `StartupCompaction` intact.
 *
 * Follows the `AppConfig.fromEnv { env[key] }` construction pattern used by
 * `RepairOutcomeTest.repairAppConfig` — `AppConfig` is a data class with many required
 * constructor parameters, so tests build it through the same `fromEnv` factory the production
 * code uses, rather than naming every field directly.
 */
class DatabaseManagerStartupCompactionTest {
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun buildAppConfig(
        tempDir: Path,
        dbFileName: String,
        extraEnv: Map<String, String> = emptyMap()
    ): AppConfig {
        val dbPath = tempDir.resolve(dbFileName).toString()
        val readinessFile = tempDir.resolve("ready-${System.nanoTime()}")
        val env =
            mapOf(
                "DATABASE_PATH" to dbPath,
                "USE_FLYWAY" to "true",
                "READINESS_FILE" to readinessFile.toString()
            ) + extraEnv
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun readUserVersion(manager: DatabaseManager): Int {
        val db = manager.getDatabase()
        return transaction(db) {
            var value = -1
            exec("PRAGMA user_version") { rs -> if (rs.next()) value = rs.getInt(1) }
            value
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S5 — Flyway mode, defaults: updateSchema() succeeds and compaction runs, setting
    // user_version to COMPACTED_USER_VERSION. [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 updateSchema with Flyway enabled and default config compacts the fresh database`(
        @TempDir tempDir: Path
    ) {
        val appConfig = buildAppConfig(tempDir, "s5.db")
        val manager = DatabaseManager(appConfig = appConfig)
        managers += manager

        assertTrue(manager.initialize(appConfig.databasePath), "initialize must succeed for a fresh file DB")
        assertTrue(manager.updateSchema(), "updateSchema must succeed")

        assertEquals(
            StartupCompaction.COMPACTED_USER_VERSION,
            readUserVersion(manager),
            "expected updateSchema() to trigger compaction and set user_version on a fresh Flyway-mode DB"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S11 — Direct mode (USE_FLYWAY=false): updateSchema() succeeds but compaction never runs,
    // since it is gated on `schemaManager is FlywayDatabaseSchemaManager`. [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S11 updateSchema with Flyway disabled succeeds without ever running compaction`(
        @TempDir tempDir: Path
    ) {
        val appConfig = buildAppConfig(tempDir, "s11.db", extraEnv = mapOf("USE_FLYWAY" to "false"))
        val manager = DatabaseManager(appConfig = appConfig)
        managers += manager

        assertTrue(manager.initialize(appConfig.databasePath), "initialize must succeed in Direct mode")
        assertTrue(manager.updateSchema(), "updateSchema must succeed in Direct mode")

        assertEquals(
            0,
            readUserVersion(manager),
            "Direct mode must never invoke StartupCompaction (gated on FlywayDatabaseSchemaManager); user_version must remain 0"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S12a — DB_COMPACT_ON_UPGRADE=false opts out of compaction even in Flyway mode. [D]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S12a updateSchema with DB_COMPACT_ON_UPGRADE=false never runs compaction`(
        @TempDir tempDir: Path
    ) {
        val appConfig =
            buildAppConfig(tempDir, "s12a.db", extraEnv = mapOf("DB_COMPACT_ON_UPGRADE" to "false"))
        val manager = DatabaseManager(appConfig = appConfig)
        managers += manager

        assertTrue(manager.initialize(appConfig.databasePath), "initialize must succeed")
        assertTrue(manager.updateSchema(), "updateSchema must succeed even when compaction is opted out")

        assertEquals(
            0,
            readUserVersion(manager),
            "DB_COMPACT_ON_UPGRADE=false must leave user_version at 0 — the opt-out must be honored"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S12b — FLYWAY_REPAIR=true on an already-migrated DB never runs compaction, even though the
    // schema is otherwise fully at V17+. [D]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S12b updateSchema with FLYWAY_REPAIR=true on a pre-migrated database never runs compaction`(
        @TempDir tempDir: Path
    ) {
        // First bring the database to the full current schema via a normal (non-repair) manager,
        // WITHOUT compaction so the later repair-mode assertion isn't confounded by an
        // already-set user_version from this setup step.
        val setupConfig =
            buildAppConfig(tempDir, "s12b.db", extraEnv = mapOf("DB_COMPACT_ON_UPGRADE" to "false"))
        val setupManager = DatabaseManager(appConfig = setupConfig)
        managers += setupManager
        assertTrue(setupManager.initialize(setupConfig.databasePath))
        assertTrue(setupManager.updateSchema())
        assertEquals(0, readUserVersion(setupManager), "sanity: setup phase must not have compacted")
        setupManager.shutdown()

        // Now reopen the same file in repair mode.
        val repairConfig =
            buildAppConfig(
                tempDir,
                "s12b.db",
                extraEnv = mapOf("FLYWAY_REPAIR" to "true", "DB_COMPACT_ON_UPGRADE" to "true")
            )
        val repairManager = DatabaseManager(appConfig = repairConfig)
        managers += repairManager

        assertTrue(repairManager.initialize(repairConfig.databasePath), "initialize must succeed in repair mode")
        repairManager.updateSchema()

        assertEquals(
            0,
            readUserVersion(repairManager),
            "FLYWAY_REPAIR=true must never trigger compaction, regardless of DB_COMPACT_ON_UPGRADE"
        )
    }
}

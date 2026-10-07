package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade

import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S14-S16: the checked-in golden V17 database (a 3.16-format file built from synthetic literals, see
 * [GoldenV17Generator]) is intact, upgrades through the bare chain AND through the production startup path with
 * every seeded row readable via the repositories, and a copy that lost its Flyway history is baselined at 17.
 */
class GoldenV17UpgradeTest {
    @TempDir
    lateinit var dir: File

    private val seeds = MigrationSeed.discover()

    /** Rewrites declared by migrations after V17 (the golden is at V17, so only later seeds apply). */
    private val laterRewrites get() = seeds.filter { it.version > 17 }.flatMap { it.rewrites }.toSet()

    @Test
    fun `S14 the golden file matches its manifest checksum, size budget and row counts`() {
        val manifest = GoldenV17.manifest()
        val golden = GoldenV17.copyTo(File(dir, "check.sqlite"))
        val sha = MessageDigest.getInstance("SHA-256").digest(golden.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.getValue("sha256"), sha, "golden database changed without regenerating its manifest")
        assertTrue(golden.length() <= 512 * 1024, "golden database is ${golden.length()} bytes; budget is 512 KiB")
        assertEquals("17", manifest.getValue("schema_version"))
        DriverManager.getConnection(UpgradeHarness.urlFor(golden)).use { conn ->
            val tables = UpgradeHarness.userTables(conn)
            assertEquals(8, tables.size, "golden must carry all 8 V17 tables, got $tables")
            for (t in tables) {
                val n =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM $t").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                assertEquals(manifest.getValue("count.$t").toInt(), n, "row count of $t differs from the manifest")
            }
        }
    }

    @Test
    fun `S15 the bare chain upgrades the golden database and preserves every row, FK and FTS index`() {
        val golden = GoldenV17.copyTo(File(dir, "bare.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }
        val probe = UpgradeHarness.migrate(url)
        val failures =
            DriverManager.getConnection(url).use { conn ->
                // Nothing may be pending at V17, so the probe only fires once a later migration exists.
                UpgradeHarness.checkUpgrade(conn, before, laterRewrites, probe.takeIf { it.observed.isNotEmpty() })
            }
        assertTrue(failures.isEmpty(), "golden upgrade failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `S15 production startup upgrades the golden database and the repositories read every seeded row`() {
        val golden = GoldenV17.copyTo(File(dir, "prod.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        assertTrue(FlywayDatabaseSchemaManager(url).updateSchema(), "production updateSchema must accept the golden database")

        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
        try {
            assertTrue(manager.initialize(url))
            val provider = DefaultRepositoryProvider(manager)
            runBlocking {
                for (name in BaselineDataset.ITEM_NAMES) {
                    val r = provider.workItemRepository().getById(BaselineDataset.uuid(name))
                    assertTrue(r is Result.Success, "work item $name unreadable through the repository: $r")
                }
                val note = provider.noteRepository().getById(BaselineDataset.uuid("note:task1:task-scope"))
                assertTrue(note is Result.Success, "attributed note unreadable: $note")
                val deps = provider.dependencyRepository().findByItemId(BaselineDataset.uuid("task1"))
                assertTrue(deps.size >= 2, "task1 must have its BLOCKS and RELATES_TO edges, got ${deps.size}")
                val transitions = provider.roleTransitionRepository().findByItemId(BaselineDataset.uuid("task2"))
                assertTrue(transitions is Result.Success && transitions.data.size == 2, "task2 transitions unreadable: $transitions")
                val config = provider.projectConfigRepository().get(BaselineDataset.uuid("root"))
                assertTrue(config is Result.Success && config.data != null, "project_config unreadable: $config")
                val plan = provider.planDocumentRepository().get(BaselineDataset.uuid("root"), "adopted-plan")
                assertTrue(plan is Result.Success && plan.data != null, "plan document unreadable: $plan")
            }
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `S16 the golden database without flyway_schema_history is baselined at 17 and then migrated`() {
        val golden = GoldenV17.copyTo(File(dir, "direct.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        DriverManager.getConnection(url).use { conn -> conn.createStatement().use { it.execute("DROP TABLE flyway_schema_history") } }
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }

        assertTrue(FlywayDatabaseSchemaManager(url).updateSchema(), "a V17-shaped database without history must be baselined, not refused")

        DriverManager.getConnection(url).use { conn ->
            val baselineVersion =
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT version FROM flyway_schema_history WHERE type = 'BASELINE'").use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }
            assertEquals("17", baselineVersion, "history must record a baseline at version 17")
            val failures = UpgradeHarness.checkUpgrade(conn, before, laterRewrites, null)
            assertTrue(failures.isEmpty(), "baselined upgrade failures:\n" + failures.joinToString("\n"))
        }
    }
}

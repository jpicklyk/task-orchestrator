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

    /** Seeds of migrations after V17 (the golden is at V17, so only later seeds' transforms apply). */
    private val laterSeeds get() = seeds.filter { it.version > 17 }

    /** Migration versions the golden still has to apply (none until a V18 exists). */
    private val pendingVersions get() = UpgradeHarness.migrationVersions(dir).filter { it > 17 }

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
        val pending = pendingVersions
        val failures =
            DriverManager.getConnection(url).use { conn ->
                // At V17 nothing is pending, so the probe cannot fire; the moment a V18+ migration exists the
                // never-fired guard is switched on (a probe that did not fire then fails the check).
                UpgradeHarness.checkUpgrade(conn, before, laterSeeds, probe.takeIf { pending.isNotEmpty() })
            }
        assertTrue(failures.isEmpty(), "golden upgrade failures:\n" + failures.joinToString("\n"))
    }

    private fun scalar(
        conn: java.sql.Connection,
        sql: String
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Test
    fun `S15 real startup (initialize, updateSchema, cycle check, first-start compaction) upgrades the golden database`() {
        val golden = GoldenV17.copyTo(File(dir, "prod.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }
        assertEquals(0, DriverManager.getConnection(url).use { scalar(it, "PRAGMA user_version") }, "the golden has never been compacted")
        // The golden carries a rowid gap (the generator deletes a sacrificial first row), so rowids are not the row
        // position. Observed: this SQLite's VACUUM keeps the gap (it does not renumber), so the rebuild is asserted
        // directly instead: the FTS indexes are wiped in the copy first, and only a startup rebuild can restore them.
        DriverManager.getConnection(url).use { conn ->
            assertEquals(2, scalar(conn, "SELECT min(rowid) FROM work_items"), "golden work_items must start at rowid 2 (gap)")
            assertEquals(2, scalar(conn, "SELECT min(rowid) FROM notes"), "golden notes must start at rowid 2 (gap)")
            for (fts in listOf("work_items_fts_trigram", "work_items_fts_text", "notes_fts_trigram", "notes_fts_text")) {
                conn.createStatement().use { it.execute("INSERT INTO $fts($fts) VALUES('delete-all')") }
            }
            assertEquals(
                0,
                scalar(
                    conn,
                    "SELECT count(*) FROM work_items_fts_text WHERE work_items_fts_text MATCH '${BaselineDataset.ITEM_FTS_TOKEN}'"
                ),
                "the wiped index must not match before startup"
            )
        }

        // The exact startup order of CurrentMain: initialize, then updateSchema (Flyway + cycle check + the one-time
        // compaction gated by DB_COMPACT_ON_UPGRADE, switched on explicitly here).
        val manager =
            DatabaseManager(appConfig = AppConfig.fromEnv { name -> if (name == "DB_COMPACT_ON_UPGRADE") "true" else null })
        try {
            assertTrue(manager.initialize(url), "initialize must accept the golden database")
            assertTrue(manager.updateSchema(), "production updateSchema must accept the golden database")

            DriverManager.getConnection(url).use { conn ->
                assertEquals(
                    1,
                    scalar(conn, "PRAGMA user_version"),
                    "the first start after the upgrade must run StartupCompaction (VACUUM + FTS rebuild) and record it"
                )
                val failures = UpgradeHarness.checkUpgrade(conn, before, laterSeeds, null)
                assertTrue(failures.isEmpty(), "rows, FK or FTS failures after real startup:\n" + failures.joinToString("\n"))
                // The wiped external-content FTS tables key on implicit rowids (here offset by the gap). Prove the rebuilt
                // indexes still point at the right rows: a MATCH must resolve, through the rowid, to the seeded titles.
                val itemHits =
                    scalar(
                        conn,
                        "SELECT count(*) FROM work_items WHERE rowid IN " +
                            "(SELECT rowid FROM work_items_fts_text WHERE work_items_fts_text MATCH '${BaselineDataset.ITEM_FTS_TOKEN}') " +
                            "AND title LIKE '%${BaselineDataset.ITEM_FTS_TOKEN}%'"
                    )
                assertEquals(1, itemHits, "work_items FTS must resolve the seeded token to its row after compaction")
                val noteHits =
                    scalar(
                        conn,
                        "SELECT count(*) FROM notes WHERE rowid IN " +
                            "(SELECT rowid FROM notes_fts_trigram WHERE notes_fts_trigram MATCH '${BaselineDataset.NOTE_FTS_TOKEN}') " +
                            "AND body LIKE '%${BaselineDataset.NOTE_FTS_TOKEN}%'"
                    )
                assertEquals(1, noteHits, "notes FTS must resolve the seeded token to its row after compaction")
            }

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
    fun `S14 regenerating the golden reproduces the committed file (a stale golden fails loudly)`() {
        val manifest = GoldenV17.manifest()
        val outDir = File(dir, "regenerated")
        val regenerated = GoldenV17.generate(outDir)
        val sha = MessageDigest.getInstance("SHA-256").digest(regenerated.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(
            manifest.getValue("sha256"),
            sha,
            "the committed golden does not match what GoldenV17.generate() now produces from BaselineDataset and the V17 " +
                "schema; regenerate it (REGENERATE_GOLDEN_V17=true, see GoldenV17Generator) and commit the new golden and manifest"
        )
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
            val failures = UpgradeHarness.checkUpgrade(conn, before, laterSeeds, null)
            assertTrue(failures.isEmpty(), "baselined upgrade failures:\n" + failures.joinToString("\n"))
        }
    }
}

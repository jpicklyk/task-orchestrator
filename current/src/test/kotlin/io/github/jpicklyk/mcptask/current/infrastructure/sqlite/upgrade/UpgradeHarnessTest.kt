package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S10-S13: the harness passes on the real chain, fails on seeded breakage, and cannot pass vacuously. */
class UpgradeHarnessTest {
    @TempDir
    lateinit var dir: File

    private val seeds = MigrationSeed.discover()

    private fun maxVersion(url: String): Int =
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `S4 copyAt yields exactly version N and migrate to N+1 applies only N+1`() {
        val url = UpgradeHarness.copyAt(9, File(dir, "copy-at-9.db"))
        assertEquals(9, maxVersion(url))
        UpgradeHarness.migrate(url, target = 10)
        assertEquals(10, maxVersion(url))
        val second = UpgradeHarness.copyAt(9, File(dir, "copy-at-9-again.db"))
        assertEquals(9, maxVersion(second), "a second copy must be a pristine version-9 database")
    }

    @Test
    fun `S10 the harness passes for every migration step from 2 to latest`() {
        val versions = UpgradeHarness.migrationVersions(dir)
        assertTrue(versions.size >= 17 && versions.first() == 1, "expected migrations V1..V17+, got $versions")
        val failures = mutableListOf<String>()
        for (n in versions.filter { it >= 2 }) {
            val step = UpgradeHarness.runStep(n, dir, seeds)
            step.failures.forEach { failures += "step $n: $it" }
        }
        assertTrue(failures.isEmpty(), "upgrade harness failures:\n" + failures.joinToString("\n"))
    }

    /** Migrates a fresh file to latest, seeds the baseline, snapshots, then applies [scratchSql] as an extra V999 migration. */
    private fun applyScratchMigration(
        name: String,
        scratchSql: String,
        transforms: List<MigrationSeed> = emptyList()
    ): List<String> {
        val file = File(dir, "$name.db")
        val url = UpgradeHarness.urlFor(file)
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { BaselineDataset.seed(it) }
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }
        val scratch = File(dir, "scratch-$name").also { it.mkdirs() }
        File(scratch, "V999__scratch.sql").writeText(scratchSql)
        val probe = UpgradeHarness.FkProbe()
        FlywayDatabaseSchemaManager(url)
            .flywayConfiguration()
            .locations(FlywayDatabaseSchemaManager.MIGRATION_LOCATION, "filesystem:${scratch.absolutePath}")
            .callbacks(probe)
            .load()
            .migrate()
        return DriverManager.getConnection(url).use { UpgradeHarness.checkUpgrade(it, before, transforms, probe) }
    }

    @Test
    fun `S11 a migration that deletes IS_BLOCKED_BY rows fails the harness`() {
        val failures = applyScratchMigration("deletes-edges", "DELETE FROM dependencies WHERE type = 'IS_BLOCKED_BY';")
        assertTrue(failures.any { it.startsWith("dependencies row") && "was lost" in it }, "lost edge not reported: $failures")
    }

    @Test
    fun `S11 a migration that drops a trigger fails the harness`() {
        val failures = applyScratchMigration("drops-trigger", "DROP TRIGGER work_items_cycle_check;")
        assertTrue(
            failures.any { it.startsWith("inventory:") && "work_items_cycle_check" in it },
            "dropped trigger not reported: $failures"
        )
    }

    @Test
    fun `S11 a migration that rewrites a baseline column fails unless declared`() {
        val failures = applyScratchMigration("rewrites-title", "UPDATE work_items SET title = title || '!';")
        assertTrue(failures.any { "column title changed" in it }, "undeclared rewrite not reported: $failures")
    }

    /** A stand-in seed for a scratch migration that appends '!' to every title. */
    private fun titleBangSeed(suffix: String) =
        object : MigrationSeed(999) {
            override fun expected(
                table: String,
                row: Map<String, Any?>
            ): Map<String, Any?> = if (table == "work_items") mapOf("title" to row["title"].toString() + suffix) else emptyMap()
        }

    @Test
    fun `S11 a declared transform is verified exactly, not skipped`() {
        val sql = "UPDATE work_items SET title = title || '!';"
        val ok = applyScratchMigration("declared-ok", sql, listOf(titleBangSeed("!")))
        assertTrue(ok.isEmpty(), "the exact declared transform must pass: $ok")
        val wrong = applyScratchMigration("declared-wrong", sql, listOf(titleBangSeed("?")))
        assertTrue(wrong.any { "column title changed" in it }, "a wrong expected value must fail, not be excluded: $wrong")
    }

    @Test
    fun `S11 a seed transform applies only to steps whose chain includes its migration`() {
        val seed17 = seeds.first { it.version == 17 }
        assertEquals(listOf(13, 17), UpgradeHarness.applicableSeeds(2, seeds).map { it.version }.filter { it <= 17 })
        assertEquals(listOf(17), UpgradeHarness.applicableSeeds(14, seeds).map { it.version }.filter { it <= 17 })
        assertTrue(UpgradeHarness.applicableSeeds(18, seeds).none { it === seed17 }, "step 18 must not carry the V17 transform")
    }

    @Test
    fun `S12 a migration of version 18 or later without a seed fails coverage`() {
        val problems = MigrationSeed.coverageProblems((1..22).toList(), seeds)
        assertTrue(problems.any { "V22" in it && "no MigrationSeed" in it }, "missing SeedV22 not reported: $problems")
        assertEquals(
            emptyList(),
            MigrationSeed.coverageProblems(UpgradeHarness.migrationVersions(dir), seeds),
            "real chain must be covered"
        )
        val dangling = MigrationSeed.coverageProblems((1..12).toList(), seeds)
        assertTrue(dangling.any { "has no migration" in it }, "a seed for a missing migration must be reported: $dangling")
    }

    @Test
    fun `S12 seeds are discovered from the seeds package`() {
        assertEquals(listOf(13, 17), seeds.map { it.version }.filter { it < MigrationSeed.FIRST_SEED_REQUIRED })
    }

    @Test
    fun `S13 the foreign-key probe is non-vacuous`() {
        val enforcing = File(dir, "fk-on.db")
        val probe = UpgradeHarness.FkProbe()
        Flyway
            .configure()
            .dataSource(
                SQLiteDataSource(SQLiteConfig().apply { enforceForeignKeys(true) }).also { it.url = UpgradeHarness.urlFor(enforcing) }
            ).locations(FlywayDatabaseSchemaManager.MIGRATION_LOCATION)
            .target("1")
            .callbacks(probe)
            .load()
            .migrate()
        assertTrue(probe.observed.isNotEmpty(), "probe must fire")
        assertTrue(probe.violations().isNotEmpty(), "a foreign-key-enforcing connection must be flagged, saw ${probe.observed}")

        val migrated = File(dir, "fk-off.db")
        val url = UpgradeHarness.urlFor(migrated)
        val ok = UpgradeHarness.migrate(url)
        assertTrue(ok.observed.isNotEmpty() && ok.violations().isEmpty(), "production chain must record foreign_keys=0, saw ${ok.observed}")
        val vacuous =
            DriverManager.getConnection(url).use { UpgradeHarness.checkUpgrade(it, emptyMap(), emptyList(), UpgradeHarness.FkProbe()) }
        assertTrue(vacuous.any { "never fired" in it }, "an unfired probe must fail the check: $vacuous")
    }
}

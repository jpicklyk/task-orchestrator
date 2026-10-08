package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The migration upgrade harness: proves a migration preserves data and invariants on a POPULATED database.
 *
 * Per migration N (2 to latest): migrate a fresh file to N-1 with plain Flyway, seed [BaselineDataset] and the
 * [MigrationSeed] for N, snapshot every row, run the bare migration chain to latest (plain Flyway, never a
 * production startup path), then check:
 * - every pre-existing row is still there with unchanged values in every column present before and after,
 *   compared exactly, where a [MigrationSeed.expected] transform (when a seed's migration rewrites a column)
 *   supplies the exact post-migration value instead of the pre-migration one;
 * - `PRAGMA foreign_key_check` is empty;
 * - per FTS table: the indexed-document count equals the backing table, `integrity-check` (rank=1) passes
 *   and a seeded token is found by MATCH. This runs BEFORE any production startup, because
 *   [StartupIntegrity.checkFts] auto-rebuilds and would mask a broken index;
 * - [StartupIntegrity.verifyInventory] does not throw (expected FTS tables and triggers all exist);
 * - a Flyway BEFORE_EACH_MIGRATE callback saw `foreign_keys = 0` on Flyway's own connection every time.
 */
object UpgradeHarness {
    /** Records `PRAGMA foreign_keys` on Flyway's own connection before each migration. */
    class FkProbe : Callback {
        val observed = mutableListOf<String>()

        /** Observed values that are not 0 (foreign-key enforcement was ON during a migration). */
        fun violations(): List<String> = observed.filter { it != "0" }

        override fun supports(
            event: Event?,
            context: Context?
        ): Boolean = event == Event.BEFORE_EACH_MIGRATE

        override fun canHandleInTransaction(
            event: Event?,
            context: Context?
        ): Boolean = true

        override fun handle(
            event: Event?,
            context: Context?
        ) {
            context!!.connection.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_keys").use { rs -> observed += if (rs.next()) rs.getString(1) else "?" }
            }
        }

        override fun getCallbackName(): String = "fkProbe"
    }

    fun urlFor(file: File): String = "jdbc:sqlite:" + file.absolutePath.replace(File.separatorChar, '/')

    private val templates = java.util.concurrent.ConcurrentHashMap<Int, File>()

    /**
     * Copies a database migrated to exactly [version] (plain Flyway, no seed) to [dest]. The per-version
     * template is built once per JVM (migrate then `VACUUM INTO`, like SqliteTemplate) and copied per call,
     * so a test pays Flyway once per version, not once per test. Returns the JDBC URL of [dest].
     */
    fun copyAt(
        version: Int,
        dest: File
    ): String {
        val template =
            templates.computeIfAbsent(version) {
                val dir =
                    java.nio.file.Files
                        .createTempDirectory("to-upgrade-template-$version-")
                        .toFile()
                dir.deleteOnExit()
                val migrated = File(dir, "migrated.db")
                migrate(urlFor(migrated), target = version)
                val flat = File(dir, "template.db")
                val target = flat.absolutePath.replace(File.separatorChar, '/').replace("'", "''")
                DriverManager.getConnection(urlFor(migrated)).use { c -> c.createStatement().use { it.execute("VACUUM INTO '$target'") } }
                migrated.delete()
                flat.deleteOnExit()
                flat
            }
        dest.parentFile?.mkdirs()
        java.nio.file.Files
            .copy(template.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return urlFor(dest)
    }

    /** Plain Flyway through the production configuration (never a startup path): migrate to [target] or latest. */
    fun migrate(
        url: String,
        target: Int? = null,
        probe: FkProbe = FkProbe()
    ): FkProbe {
        FlywayDatabaseSchemaManager(url)
            .flywayConfiguration(target = target?.toString())
            .callbacks(probe)
            .load()
            .migrate()
        return probe
    }

    /** Every migration version Flyway finds on the classpath, ascending. */
    fun migrationVersions(scratchDir: File): List<Int> {
        val url = urlFor(File(scratchDir, "versions.db"))
        return FlywayDatabaseSchemaManager(url)
            .flywayConfiguration()
            .load()
            .info()
            .all()
            .map { it.version.version.toInt() }
            .sorted()
    }

    /** One step's outcome: [failures] is empty when the upgrade preserved everything. */
    class StepResult(
        val version: Int,
        val failures: List<String>,
        val probe: FkProbe
    )

    /** Row snapshot: table to (hex id to (column to value)). */
    typealias Dump = Map<String, Map<String, Map<String, Any?>>>

    fun userTables(conn: Connection): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildList {
                    while (rs.next()) {
                        val n = rs.getString(1)
                        if (n != FlywayDatabaseSchemaManager.HISTORY_TABLE && n !in ftsAndShadowNames()) add(n)
                    }
                }
            }
        }

    private fun ftsAndShadowNames(): Set<String> =
        StartupIntegrity.FTS_TABLES
            .flatMap { fts -> listOf(fts) + listOf("_data", "_idx", "_content", "_docsize", "_config").map { fts + it } }
            .toSet()

    fun dump(conn: Connection): Dump =
        userTables(conn).associateWith { table ->
            val rows = linkedMapOf<String, Map<String, Any?>>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM $table").use { rs ->
                    val meta = rs.metaData
                    while (rs.next()) {
                        val row = linkedMapOf<String, Any?>()
                        for (i in 1..meta.columnCount) {
                            val v = rs.getObject(i)
                            row[meta.getColumnName(i)] = if (v is ByteArray) v.joinToString("") { "%02x".format(it) } else v
                        }
                        val id = row["id"]
                        rows[id?.toString() ?: row.toString()] = row
                    }
                }
            }
            rows
        }

    /** The row after the [seeds] transforms (see [MigrationSeed.expected]), applied in ascending version order. */
    private fun expectedRow(
        table: String,
        row: Map<String, Any?>,
        seeds: List<MigrationSeed>
    ): Map<String, Any?> {
        var current = row
        for (seed in seeds.sortedBy { it.version }) {
            val overrides = seed.expected(table, current)
            if (overrides.isNotEmpty()) current = current + overrides
        }
        return current
    }

    /**
     * Every failure of the post-migration invariants against [before]. [transforms] are the seeds whose migrations
     * ran in this chain (see [applicableSeeds]); their [MigrationSeed.expected] values are compared exactly.
     */
    fun checkUpgrade(
        conn: Connection,
        before: Dump,
        transforms: List<MigrationSeed>,
        probe: FkProbe?
    ): List<String> {
        val failures = mutableListOf<String>()
        if (probe != null && probe.observed.isEmpty()) failures += "the foreign-key probe never fired (vacuous check)"
        probe?.violations()?.forEach { failures += "foreign_keys was '$it' (not 0) on Flyway's connection during a migration" }

        val after = dump(conn)
        for ((table, rows) in before) {
            val afterRows = after[table]
            if (afterRows == null) {
                failures += "table $table disappeared"
                continue
            }
            for ((id, beforeRow) in rows) {
                val row = expectedRow(table, beforeRow, transforms)
                val now = afterRows[id]
                if (now == null) {
                    failures += "$table row $id was lost"
                    continue
                }
                for ((col, value) in row) {
                    if (col !in now) continue
                    if (now[col] != value) failures += "$table row $id column $col changed: '$value' to '${now[col]}'"
                }
            }
        }

        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                while (rs.next()) failures += "foreign_key_check: ${rs.getString(1)} row ${rs.getString(2)} -> ${rs.getString(3)}"
            }
        }

        failures += ftsFailures(conn)
        runCatching { StartupIntegrity.verifyInventory(conn) }.onFailure { failures += "inventory: ${it.message}" }
        return failures
    }

    private fun ftsFailures(conn: Connection): List<String> {
        val failures = mutableListOf<String>()
        for (fts in StartupIntegrity.FTS_TABLES) {
            val backing = if (fts.startsWith("work_items")) "work_items" else "notes"
            try {
                val indexed = scalar(conn, "SELECT count(*) FROM ${fts}_docsize")
                val actual = scalar(conn, "SELECT count(*) FROM $backing")
                if (indexed != actual) failures += "$fts indexes $indexed documents but $backing has $actual rows"
                conn.createStatement().use { it.execute("INSERT INTO $fts($fts, rank) VALUES('integrity-check', 1)") }
                val token = if (backing == "work_items") BaselineDataset.ITEM_FTS_TOKEN else BaselineDataset.NOTE_FTS_TOKEN
                if (scalar(conn, "SELECT count(*) FROM $fts WHERE $fts MATCH '$token'") < 1) {
                    failures += "$fts does not find the seeded token '$token'"
                }
            } catch (e: Exception) {
                failures += "$fts check failed: ${e.message}"
            }
        }
        return failures
    }

    private fun scalar(
        conn: Connection,
        sql: String
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Seeds whose migration is applied by the step for [version]: step N migrates N-1 to latest, so seeds with version >= N. */
    fun applicableSeeds(
        version: Int,
        seeds: List<MigrationSeed>
    ): List<MigrationSeed> = seeds.filter { it.version >= version }

    /**
     * Runs the whole step for migration [version]: fresh file at version-1, baseline plus that version's seed,
     * bare migrate to latest, then [checkUpgrade] and the seed's own [MigrationSeed.verify].
     */
    fun runStep(
        version: Int,
        dir: File,
        seeds: List<MigrationSeed>
    ): StepResult {
        val file = File(dir, "step-$version.db")
        val url = urlFor(file)
        migrate(url, target = version - 1)
        val ownSeed = seeds.firstOrNull { it.version == version }
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.seed(conn)
            ownSeed?.seed(conn)
        }
        val before = DriverManager.getConnection(url).use { dump(it) }
        val probe = migrate(url)
        val failures =
            DriverManager.getConnection(url).use { conn ->
                val found = checkUpgrade(conn, before, applicableSeeds(version, seeds), probe).toMutableList()
                if (ownSeed != null) {
                    runCatching { ownSeed.verify(conn) }.onFailure { found += "seed V$version verify: ${it.message}" }
                }
                found
            }
        return StepResult(version, failures, probe)
    }
}

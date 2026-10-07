package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager

/**
 * Regenerates the checked-in golden V17 (3.16-format) database and its manifest. DISABLED unless the
 * environment variable `REGENERATE_GOLDEN_V17=true` is set (environment variables reach the forked test JVM,
 * `-D` flags do not):
 *
 * ```
 * REGENERATE_GOLDEN_V17=true ./gradlew :current:test --tests "*GoldenV17Generator*"
 * ```
 *
 * The database is NEVER a copy or sanitization of a real user database. It is built from nothing: a fresh file
 * migrated by Flyway to target 17 (the 3.16 schema; migration bytes are identical, see
 * `MigrationLocationContinuityTest`), then synthetic rows written with raw JDBC from [BaselineDataset]'s literals,
 * which use both timestamp shapes the 3.16 server wrote. Flyway's own history rows are normalized (installed_by,
 * installed_on, execution_time) so the file carries no machine or user detail. The result is flattened with
 * `VACUUM INTO` in journal_mode=DELETE so it is a single file.
 */
@EnabledIfEnvironmentVariable(named = "REGENERATE_GOLDEN_V17", matches = "true")
class GoldenV17Generator {
    @Test
    fun regenerate() {
        GoldenV17.generate(File(System.getProperty("user.dir"), "src/test/resources/upgrade"))
    }
}

/** Names and loader for the golden V17 resources. */
object GoldenV17 {
    const val DB_NAME = "golden-v17-3.16.sqlite"
    const val MANIFEST_NAME = "golden-v17-3.16.manifest.txt"

    /**
     * Builds the golden database and its manifest into [outDir] (deterministic: fixed literals, normalized history).
     * The checked-in copy is produced with the outDir `src/test/resources/upgrade`; `GoldenV17UpgradeTest` calls this
     * with a temp directory to prove the committed golden is not stale.
     */
    fun generate(outDir: File): File {
        val work = Files.createTempDirectory("golden-v17-gen-").toFile()
        try {
            val url = UpgradeHarness.urlFor(File(work, "v17.db"))
            UpgradeHarness.migrate(url, target = 17)
            DriverManager.getConnection(url).use { conn ->
                // Rowid gap: a sacrificial work item and note are inserted BEFORE the baseline and deleted after, so the
                // seeded rows start at rowid 2 and rowids differ from row position. The bundled SQLite's VACUUM keeps
                // implicit rowids (observed: the gap survives), so S15 asserts the FTS rebuild through rowids directly.
                // See GoldenV17UpgradeTest (real startup).
                BaselineDataset.insert(
                    conn,
                    "work_items",
                    "id" to BaselineDataset.id("gap-item"),
                    "root_id" to BaselineDataset.id("gap-item"),
                    "title" to "Gap item",
                    "depth" to 0,
                    "created_at" to BaselineDataset.TS,
                    "modified_at" to BaselineDataset.TS_PLUS_1,
                    "role_changed_at" to BaselineDataset.TS_PLUS_2
                )
                BaselineDataset.insert(
                    conn,
                    "notes",
                    "id" to BaselineDataset.id("gap-note"),
                    "work_item_id" to BaselineDataset.id("gap-item"),
                    "key" to "gap",
                    "role" to "queue",
                    "body" to "Gap note",
                    "created_at" to BaselineDataset.TS,
                    "modified_at" to BaselineDataset.TS_PLUS_1
                )
                BaselineDataset.seed(conn)
                conn.createStatement().use { st ->
                    st.execute("DELETE FROM notes WHERE id = x'${BaselineDataset.id("gap-note").joinToString("") { "%02x".format(it) }}'")
                    st.execute(
                        "DELETE FROM work_items WHERE id = x'${BaselineDataset.id("gap-item").joinToString("") { "%02x".format(it) }}'"
                    )
                    st.execute(
                        "UPDATE flyway_schema_history SET installed_by = 'golden-generator', " +
                            "installed_on = '2026-01-01 00:00:00', execution_time = 1"
                    )
                    st.execute("PRAGMA journal_mode = DELETE")
                }
            }
            outDir.mkdirs()
            val golden = File(outDir, DB_NAME)
            golden.delete()
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { it.execute("VACUUM INTO '" + golden.absolutePath.replace(File.separatorChar, '/') + "'") }
            }

            val counts =
                DriverManager.getConnection(UpgradeHarness.urlFor(golden)).use { conn ->
                    UpgradeHarness.userTables(conn).sorted().associateWith { t ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT count(*) FROM $t").use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    }
                }
            val sqliteVersion =
                DriverManager.getConnection(url).use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT sqlite_version()").use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                }
            val sha = MessageDigest.getInstance("SHA-256").digest(golden.readBytes()).joinToString("") { "%02x".format(it) }
            val manifest =
                buildString {
                    appendLine("# Golden V17 (3.16-format) database. Regenerate with GoldenV17Generator; never edit by hand.")
                    appendLine("# Synthetic rows only (BaselineDataset literals); never derived from a real database.")
                    appendLine("file=${DB_NAME}")
                    appendLine("sha256=$sha")
                    appendLine("size_bytes=${golden.length()}")
                    appendLine("schema_version=17")
                    appendLine("flyway=${java.io.File(Flyway::class.java.protectionDomain.codeSource.location.path).name}")
                    appendLine("sqlite_jdbc=${org.sqlite.SQLiteJDBCLoader.getVersion()}")
                    appendLine("sqlite=$sqliteVersion")
                    appendLine("generator_base_commit=${gitHead()}")
                    counts.forEach { (t, n) -> appendLine("count.$t=$n") }
                }
            File(outDir, MANIFEST_NAME).writeText(manifest, Charsets.UTF_8)
            return golden
        } finally {
            work.deleteRecursively()
        }
    }

    private fun gitHead(): String =
        runCatching {
            val p = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
            val out =
                p.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            p.waitFor()
            out
        }.getOrDefault("unknown")

    fun manifest(): Map<String, String> {
        val stream = GoldenV17::class.java.getResourceAsStream("/upgrade/$MANIFEST_NAME") ?: error("missing /upgrade/$MANIFEST_NAME")
        return stream
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** Copies the golden database to [target] (a fresh file) and returns it. */
    fun copyTo(target: File): File {
        val stream = GoldenV17::class.java.getResourceAsStream("/upgrade/$DB_NAME") ?: error("missing /upgrade/$DB_NAME")
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}

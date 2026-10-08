package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.migrated
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.query
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration-location continuity (plan v4-phase1-core 3.10: "histories validate unchanged").
 *
 * Existing 3.x databases recorded their scripts as bare `V1__...sql` names with CRC32 checksums. The
 * snapshot resource `migrations/flyway-checksum-snapshot.txt` was captured from the migration files
 * at base commit 327690d2 (before the move into `db/migration/sqlite`), computed independently of
 * Flyway as the CRC32 over each line (Flyway's documented SQL checksum rule). A fresh migrate must
 * reproduce exactly those scripts and checksums, or an upgraded database would fail validation.
 */
class MigrationLocationContinuityTest {
    @TempDir
    lateinit var dir: Path

    private data class Entry(
        val version: String,
        val script: String,
        val checksum: Int
    )

    private fun snapshot(): List<Entry> {
        val text =
            requireNotNull(javaClass.classLoader.getResourceAsStream("migrations/flyway-checksum-snapshot.txt")) {
                "snapshot resource missing"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (v, s, c) = line.split("|")
                Entry(v, s, c.toInt())
            }.toList()
    }

    @Test
    fun `fresh migrate records bare script names and checksums equal to the base snapshot`() {
        val url = migrated(dir)
        val actual =
            query(
                url,
                "SELECT version, script, checksum FROM flyway_schema_history WHERE type = 'SQL' AND success = 1 ORDER BY installed_rank"
            ) { Entry(it.getString(1), it.getString(2), it.getInt(3)) }

        val expected = snapshot()
        assertEquals(17, expected.size, "snapshot must list V1..V17")
        assertEquals(expected, actual, "history scripts (bare names) and checksums must equal the snapshot captured at 327690d2")
        assertTrue(actual.none { it.script.contains("/") }, "script names must be bare, never sqlite/V1__...")
    }

    @Test
    fun `a tampered checksum is rejected so a drifted migration file cannot start`() {
        val url = migrated(dir)
        SchemaTestSupport.exec(url, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '9'")
        assertTrue(
            !FlywayDatabaseSchemaManager(url, repair = false, schemaMode = SchemaMode.VALIDATE).updateSchema(),
            "validate must reject a changed checksum"
        )
    }

    @Test
    fun `migration sources are exactly V1 to V17 in the single sqlite folder with nothing at the parent level`() {
        val parent = File("src/main/resources/db/migration")
        assertTrue(parent.isDirectory, "run from the current module directory: ${parent.absolutePath}")
        assertEquals(
            listOf("sqlite"),
            parent.list()!!.sorted(),
            "the parent location must hold only the sqlite folder (no second scan root)"
        )
        val names = File(parent, "sqlite").list()!!.toList()
        val versions =
            names.map {
                Regex("^V(\\d+)__.+\\.sql$")
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: error("unexpected file $it")
            }
        assertEquals((1..17).toList(), versions.sorted(), "versions must be contiguous V1..V17 with no gaps or extras")
        assertEquals(snapshot().map { it.script }.sorted(), names.sorted(), "file names must equal the snapshot scripts")
    }
}

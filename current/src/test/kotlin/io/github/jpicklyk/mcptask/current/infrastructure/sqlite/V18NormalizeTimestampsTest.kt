package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.GoldenV17
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7) S12: the data-only V18 migration "Normalize timestamps" rewrites every legacy timestamp shape in
 * the nineteen timestamp columns to canonical UTC text and leaves everything else alone.
 *
 * Oracles (frozen test-plan and migration-assessment): the SQLite date-and-time function specification (a `Z` or
 * `+-HH:MM` suffix is converted to UTC; fractional seconds render as `SS.SSS`; an unparseable value yields NULL and is
 * therefore left untouched), task-scope D1 (canonical `yyyy-MM-dd HH:mm:ss.SSS`, milliseconds truncated), plan 3.12
 * l.355-358. The expected text for every injected shape is written out below from that specification; the migration
 * file and its seed are never consulted.
 *
 * EXISTING-SURFACE: only the pre-existing upgrade harness is used, so deleting V18 yields behavioral red (legacy text
 * stays legacy), not a compile failure.
 */
class V18NormalizeTimestampsTest {
    @TempDir
    lateinit var dir: File

    /** (table, column, nullable) for the nineteen columns; table and column names are those of the committed V17 schema snapshots. */
    private val timestampColumns: List<Triple<String, String, Boolean>> =
        listOf(
            Triple("work_items", "created_at", false),
            Triple("work_items", "modified_at", false),
            Triple("work_items", "role_changed_at", false),
            Triple("work_items", "claimed_at", true),
            Triple("work_items", "claim_expires_at", true),
            Triple("work_items", "original_claimed_at", true),
            Triple("notes", "created_at", false),
            Triple("notes", "modified_at", false),
            Triple("dependencies", "created_at", false),
            Triple("plan_documents", "created_at", false),
            Triple("plan_documents", "modified_at", false),
            Triple("project_config", "updated_at", false),
            Triple("role_transitions", "transitioned_at", false),
            Triple("resource_leases", "acquired_at", false),
            Triple("resource_leases", "expires_at", false),
            Triple("resource_leases", "original_acquired_at", false),
            Triple("resource_lease_history", "acquired_at", false),
            Triple("resource_lease_history", "expires_at", false),
            Triple("resource_lease_history", "released_at", true)
        )

    /** An injected stored value and the text it must read as after V18 (null = stays NULL). */
    private data class Shape(
        val label: String,
        val input: Any?,
        val expected: String?
    )

    private val shapes: List<Shape> =
        listOf(
            Shape("no fraction (datetime() shape)", "2026-03-01 10:15:30", "2026-03-01 10:15:30.000"),
            Shape("T separator and Z", "2026-03-01T10:15:30Z", "2026-03-01 10:15:30.000"),
            Shape("positive offset", "2026-03-01T10:15:30.123+05:30", "2026-03-01 04:45:30.123"),
            Shape("negative offset crossing midnight", "2026-03-01T23:30:00-08:00", "2026-03-02 07:30:00.000"),
            Shape("six fraction digits", "2026-03-01 10:15:30.123456", "2026-03-01 10:15:30.123"),
            Shape("four fraction digits truncated not rounded", "2026-03-01 10:15:30.9996", "2026-03-01 10:15:30.999"),
            Shape("already canonical", "2026-03-01 10:15:30.123", "2026-03-01 10:15:30.123"),
            Shape("NULL", null, null),
            Shape("integer epoch seconds", 1709287530L, "1709287530"),
            Shape("unparseable text", "garbage", "garbage")
        )

    private fun nextShape(
        start: Int,
        nullable: Boolean
    ): Shape {
        var index = start % shapes.size
        while (shapes[index].input == null && !nullable) index = (index + 1) % shapes.size
        return shapes[index]
    }

    private data class Cell(
        val table: String,
        val rowid: Long,
        val column: String
    )

    private fun inject(url: String): Map<Cell, String?> {
        val expected = linkedMapOf<Cell, String?>()
        DriverManager.getConnection(url).use { conn ->
            val tables = timestampColumns.map { it.first }.distinct()
            for (table in tables) {
                val rowids =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT rowid FROM $table ORDER BY rowid").use { rs ->
                            buildList { while (rs.next()) add(rs.getLong(1)) }
                        }
                    }
                val columns = timestampColumns.filter { it.first == table }
                rowids.forEachIndexed { rowIndex, rowid ->
                    columns.forEachIndexed { colIndex, (_, column, nullable) ->
                        val shape = nextShape(rowIndex + colIndex, nullable)
                        conn.prepareStatement("UPDATE $table SET $column = ? WHERE rowid = ?").use { ps ->
                            when (val v = shape.input) {
                                null -> ps.setNull(1, java.sql.Types.NULL)
                                is Long -> ps.setLong(1, v)
                                else -> ps.setString(1, v.toString())
                            }
                            ps.setLong(2, rowid)
                            assertEquals(1, ps.executeUpdate())
                        }
                        expected[Cell(table, rowid, column)] = shape.expected
                    }
                }
            }
        }
        return expected
    }

    private fun readCell(
        url: String,
        cell: Cell
    ): String? = P7Raw.text(url, "SELECT CAST(${cell.column} AS TEXT) FROM ${cell.table} WHERE rowid = ?", cell.rowid)

    private fun rowCounts(url: String): Map<String, Int> =
        timestampColumns.map { it.first }.distinct().associateWith {
            P7Raw.query(url, "SELECT count(*) FROM $it") { rs -> rs.getInt(1) }.single()
        }

    private fun assertV18Applied(url: String) {
        val applied =
            P7Raw
                .query(
                    url,
                    "SELECT count(*) FROM ${FlywayDatabaseSchemaManager.HISTORY_TABLE} WHERE version = '18' AND success = 1"
                ) { it.getInt(1) }
                .single()
        assertEquals(1, applied, "Flyway must have applied migration 18")
    }

    @Test
    fun `S12 every injected legacy shape in every timestamp column normalizes to the specified canonical text`() {
        val url = UpgradeHarness.copyAt(17, File(dir, "shapes.db"))
        DriverManager.getConnection(url).use { BaselineDataset.seed(it) }
        val countsBefore = rowCounts(url)
        val expected = inject(url)
        assertTrue(expected.size >= timestampColumns.size, "at least one row per column must have been injected")
        // The injection must really have covered every shape, otherwise the check below is partly vacuous.
        val covered = expected.values.toSet()
        shapes.forEach { assertTrue(it.expected in covered, "shape '${it.label}' was never injected") }

        val probe = UpgradeHarness.migrate(url)

        assertTrue(probe.observed.isNotEmpty(), "the foreign-key probe must have fired")
        assertEquals(emptyList(), probe.violations())
        assertV18Applied(url)
        val failures = mutableListOf<String>()
        expected.forEach { (cell, want) ->
            val got = readCell(url, cell)
            if (got != want) failures += "${cell.table}.${cell.column} rowid=${cell.rowid}: expected '$want' but found '$got'"
        }
        assertTrue(failures.isEmpty(), "V18 normalization mismatches:\n" + failures.joinToString("\n"))
        assertEquals(countsBefore, rowCounts(url), "a data-only migration must neither add nor drop rows")
    }

    @Test
    fun `S12 re-running the migration chain on an already normalized database changes nothing`() {
        val url = UpgradeHarness.copyAt(17, File(dir, "replay.db"))
        DriverManager.getConnection(url).use { BaselineDataset.seed(it) }
        val expected = inject(url)
        UpgradeHarness.migrate(url)
        val afterFirst = expected.keys.associateWith { readCell(url, it) }

        UpgradeHarness.migrate(url)

        assertV18Applied(url)
        assertEquals(afterFirst, expected.keys.associateWith { readCell(url, it) })
    }

    @Test
    fun `S12 the golden V17 database upgrades to canonical text with only fraction-less values changed`() {
        val golden = GoldenV17.copyTo(File(dir, "golden.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        val fractionLess = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
        val before = linkedMapOf<Cell, String?>()
        timestampColumns.forEach { (table, column, _) ->
            val rows = P7Raw.query(url, "SELECT rowid, CAST($column AS TEXT) FROM $table") { rs -> rs.getLong(1) to rs.getString(2) }
            rows.forEach { (rowid, text) -> before[Cell(table, rowid, column)] = text }
        }
        assertTrue(before.values.any { it != null && fractionLess.matches(it) }, "the golden must contain datetime()-shaped values")
        assertTrue(before.values.any { it != null && it.length == 23 }, "the golden must contain Exposed-shaped values")

        UpgradeHarness.migrate(url)

        assertV18Applied(url)
        before.forEach { (cell, old) ->
            val want = if (old != null && fractionLess.matches(old)) "$old.000" else old
            assertEquals(want, readCell(url, cell), "${cell.table}.${cell.column} rowid=${cell.rowid} (was '$old')")
        }
        timestampColumns.forEach { (table, column, _) ->
            val offenders =
                P7Raw
                    .query(
                        url,
                        "SELECT count(*) FROM $table WHERE $column IS NOT NULL AND " +
                            "NOT ($column GLOB '${P7Raw.CANONICAL_GLOB}' AND length($column) = 23)"
                    ) { it.getInt(1) }
                    .single()
            assertEquals(0, offenders, "$table.$column still holds non-canonical text after V18")
        }
    }
}

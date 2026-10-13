package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S14 (carry-in n5, item 6a52466e): [UpgradeHarness.dump] keys a table by its `id` column or else its declared primary key; a row
 * whose key column is NULL must not collide with another NULL-key row, or the harness silently drops one of them from every
 * before/after comparison.
 *
 * Oracle: the contract documented on [UpgradeHarness.dump] and in the frozen task-scope (a NULL key column keys the whole row
 * instead, so two rows that differ in any column are two dump entries). SQLite accepts NULL in a non-INTEGER PRIMARY KEY column of
 * an ordinary rowid table (a documented legacy quirk), which is how the scratch table below holds two NULL-key rows.
 *
 * Negative / absence assertion and the fixture condition that fails it without the fix: the two NULL-key rows differ in `payload`,
 * and before the fix both rows were keyed by the string "null", so the dump held one entry and the size assertion fails.
 *
 * Forbidden-construct declaration: none.
 */
class UpgradeHarnessNullKeyTest {
    @TempDir
    lateinit var dir: File

    private fun dumpOf(statements: List<String>): UpgradeHarness.Dump {
        val url = UpgradeHarness.urlFor(File(dir, "null-key-${System.nanoTime()}.db"))
        return DriverManager.getConnection(url).use { conn: Connection ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
            UpgradeHarness.dump(conn)
        }
    }

    @Test
    fun `S14 two rows whose primary key is NULL are both kept by the dump`() {
        val dump =
            dumpOf(
                listOf(
                    "CREATE TABLE scratch_nullpk (code TEXT PRIMARY KEY, payload TEXT)",
                    "INSERT INTO scratch_nullpk (code, payload) VALUES (NULL, 'first')",
                    "INSERT INTO scratch_nullpk (code, payload) VALUES (NULL, 'second')"
                )
            )

        val rows = dump.getValue("scratch_nullpk")
        assertEquals(2, rows.size, "both NULL-key rows survive: $rows")
        assertEquals(setOf("first", "second"), rows.values.map { it["payload"] }.toSet())
    }

    @Test
    fun `S14 a NULL-key row next to a keyed row leaves the keyed row under its key`() {
        val dump =
            dumpOf(
                listOf(
                    "CREATE TABLE scratch_nullpk (code TEXT PRIMARY KEY, payload TEXT)",
                    "INSERT INTO scratch_nullpk (code, payload) VALUES ('k1', 'keyed')",
                    "INSERT INTO scratch_nullpk (code, payload) VALUES (NULL, 'unkeyed-a')",
                    "INSERT INTO scratch_nullpk (code, payload) VALUES (NULL, 'unkeyed-b')"
                )
            )

        val rows = dump.getValue("scratch_nullpk")
        assertEquals(3, rows.size, "$rows")
        assertEquals("keyed", rows["k1"]?.get("payload"), "a non-NULL key still identifies its row: ${rows.keys}")
    }

    @Test
    fun `S14 a composite key with one NULL part keeps both rows`() {
        val dump =
            dumpOf(
                listOf(
                    "CREATE TABLE scratch_composite (a TEXT, b TEXT, payload TEXT, PRIMARY KEY (a, b))",
                    "INSERT INTO scratch_composite VALUES ('x', NULL, 'one')",
                    "INSERT INTO scratch_composite VALUES ('x', NULL, 'two')",
                    "INSERT INTO scratch_composite VALUES ('x', 'y', 'three')"
                )
            )

        val rows = dump.getValue("scratch_composite")
        assertEquals(3, rows.size, "$rows")
        assertEquals(setOf("one", "two", "three"), rows.values.map { it["payload"] }.toSet())
    }

    @Test
    fun `S14 control - a table keyed by non-NULL primary keys is keyed by them`() {
        val dump =
            dumpOf(
                listOf(
                    "CREATE TABLE scratch_pk (code TEXT PRIMARY KEY, payload TEXT)",
                    "INSERT INTO scratch_pk VALUES ('a', 'one')",
                    "INSERT INTO scratch_pk VALUES ('b', 'two')"
                )
            )

        val rows = dump.getValue("scratch_pk")
        assertEquals(setOf("a", "b"), rows.keys)
        assertTrue(rows.getValue("a")["payload"] == "one" && rows.getValue("b")["payload"] == "two")
    }
}

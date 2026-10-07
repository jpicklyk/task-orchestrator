package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1 guard: the upgrade harness compares values, so a column that [BaselineDataset] leaves NULL (or at its
 * declared default) in every row is invisible to it: a table recreation whose `INSERT ... SELECT` drops or
 * mis-maps that column would pass. This test enumerates every column of the LATEST schema (from
 * `PRAGMA table_xinfo` on a fully migrated database), seeds the baseline into it, and fails when a column has
 * no seeded value that is non-null, non-empty and different from its literal default, unless the column is on
 * [EXEMPT] with a reason. A future migration that adds a column therefore fails here until the dataset seeds it
 * (or the author adds a reasoned exemption).
 */
class BaselineColumnCoverageTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        /**
         * Columns that cannot carry a seeded value at the latest schema. Every entry needs a reason; an entry whose
         * column no longer exists, or that is in fact seeded, fails the test so the list cannot rot.
         */
        val EXEMPT: Map<String, String> =
            mapOf(
                "notes.actor_proof" to
                    "NULL by invariant since V17: the scrub NULLs every raw bearer proof and 3.16 never writes one " +
                    "(proofs are stored as actor_proof_sha256 / actor_proof_claims). Below V17 the dataset does seed " +
                    "raw proofs so the scrub is exercised; at the latest schema the value must be NULL.",
                "role_transitions.actor_proof" to
                    "NULL by invariant since V17, same as notes.actor_proof."
            )

        /**
         * Column pairs ("table.a=table.b") that may legitimately be equal in every row. Every entry needs a reason; a
         * stale entry (the pair is distinguishable, or a column is gone) fails the test.
         */
        val PAIR_EXEMPT: Map<String, String> = emptyMap()
    }

    private data class Col(
        val table: String,
        val name: String,
        val default: String?
    ) {
        val key get() = "$table.$name"
    }

    private fun columns(conn: Connection): List<Col> =
        UpgradeHarness.userTables(conn).flatMap { table ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_xinfo($table)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            if (rs.getInt("hidden") == 0) add(Col(table, rs.getString("name"), rs.getString("dflt_value")))
                        }
                    }
                }
            }
        }

    /** A literal default (string or number) usable in SQL; expressions such as `(randomblob(16))` and NULL are not literals. */
    private fun literalDefault(default: String?): String? = default?.takeIf { it.matches(Regex("'.*'|-?[0-9]+(\\.[0-9]+)?")) }

    private fun seededCount(
        conn: Connection,
        col: Col
    ): Int {
        val notDefault = literalDefault(col.default)?.let { " AND ${col.name} IS NOT $it" } ?: ""
        val sql = "SELECT count(*) FROM ${col.table} WHERE ${col.name} IS NOT NULL AND ${col.name} != ''$notDefault"
        return conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    /** Pairs of columns of one table that are equal (SQL `IS`, NULL-safe) in every row of a non-empty table. */
    private fun identicalPairs(conn: Connection): List<String> =
        columns(conn)
            .groupBy { it.table }
            .flatMap { (table, cols) ->
                val rows =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM $table").use {
                            it.next()
                            it.getInt(1)
                        }
                    }
                if (rows == 0) return@flatMap emptyList<String>()
                cols.flatMapIndexed { i, a ->
                    cols.drop(i + 1).mapNotNull { b ->
                        val differing =
                            conn.createStatement().use { st ->
                                st.executeQuery("SELECT count(*) FROM $table WHERE ${a.name} IS NOT ${b.name}").use {
                                    it.next()
                                    it.getInt(1)
                                }
                            }
                        if (differing == 0) "$table.${a.name}=$table.${b.name}" else null
                    }
                }
            }

    @Test
    fun `no two columns of a table hold the same value in every seeded row`() {
        val url = UpgradeHarness.urlFor(File(dir, "pairwise.db"))
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.seed(conn)
            val identical = identicalPairs(conn)
            val unexplained = identical.filter { it !in PAIR_EXEMPT }
            assertTrue(
                unexplained.isEmpty(),
                "BaselineDataset seeds identical values in every row for: $unexplained. A recreation that mis-maps one " +
                    "onto the other would pass; give each column its own literal in at least one row, or add a reasoned " +
                    "entry to PAIR_EXEMPT."
            )
            assertEquals(emptyList(), PAIR_EXEMPT.keys.filter { it !in identical }, "PAIR_EXEMPT has stale entries")
        }
    }

    @Test
    fun `the pairwise check is non-vacuous (a seeded identical pair is reported)`() {
        val url = UpgradeHarness.urlFor(File(dir, "pairwise-vacuity.db"))
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.seed(conn)
            conn.createStatement().use { it.execute("UPDATE work_items SET modified_at = created_at") }
            assertTrue(
                "work_items.created_at=work_items.modified_at" in identicalPairs(conn),
                "an identical pair must be reported"
            )
        }
    }

    @Test
    fun `every column of the latest schema has a seeded non-default value or a reasoned exemption`() {
        val url = UpgradeHarness.urlFor(File(dir, "coverage.db"))
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.seed(conn)
            val all = columns(conn)
            val uncovered = all.filter { it.key !in EXEMPT && seededCount(conn, it) == 0 }.map { it.key }
            assertTrue(
                uncovered.isEmpty(),
                "BaselineDataset seeds no distinct non-default value for: $uncovered. Give each a value in at least one " +
                    "row (the upgrade harness cannot see a column that is NULL or default in every row), or add a " +
                    "reasoned entry to EXEMPT."
            )
            val existing = all.map { it.key }.toSet()
            val stale = EXEMPT.keys.filter { it !in existing }
            assertEquals(emptyList(), stale, "EXEMPT names columns that no longer exist")
            val seededAnyway = EXEMPT.keys.filter { it in existing && seededCount(conn, all.first { c -> c.key == it }) > 0 }
            assertEquals(emptyList(), seededAnyway, "EXEMPT columns that are seeded after all; remove the exemption")
        }
    }

    @Test
    fun `the coverage check is non-vacuous (a null-only or default-only column is reported)`() {
        val url = UpgradeHarness.urlFor(File(dir, "vacuity.db"))
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.seed(conn)
            conn.createStatement().use { it.execute("UPDATE work_items SET type = NULL") }
            val type = columns(conn).first { it.key == "work_items.type" }
            assertEquals(0, seededCount(conn, type), "a column NULL in every row must count as uncovered")
            conn.createStatement().use { it.execute("UPDATE work_items SET priority = 'medium'") }
            val priority = columns(conn).first { it.key == "work_items.priority" }
            assertEquals(0, seededCount(conn, priority), "a column at its declared default in every row must count as uncovered")
        }
    }
}

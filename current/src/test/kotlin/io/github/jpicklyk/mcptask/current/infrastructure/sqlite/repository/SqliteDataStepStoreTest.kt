package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests for item 6b998895 (Q2a): [SqliteDataStepStore] over the real V23 `data_steps` table.
 *
 * Oracle: migration-assessment section 1 (the frozen DDL: `name` TEXT NOT NULL PRIMARY KEY with a 1..64 length CHECK,
 * `applied_at` the canonical 23-character `yyyy-MM-dd HH:mm:ss.SSS` UTC text, `rows_affected` INTEGER >= 0,
 * `binary_version` free text) and task-scope section 1 (`DataStepStore`: both methods run on the AMBIENT transaction of
 * the enclosing unit, and a write outside a unit throws under [OutsideUnitPolicy.FAIL]).
 *
 * The rejection tests prove the row did not land by reading the table over a separate JDBC connection; the fixture
 * that makes each of them fail without the constraint is named in the test (an existing row, a negative count, a
 * 65-character name).
 */
class SqliteDataStepStoreTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val store get() = SqliteDataStepStore(sqlite.databaseManager)

    private val at = Instant.parse("2026-03-04T05:06:07.123Z")

    /** Runs [block] in one write unit; true when the unit committed, false when it threw or ended in an error. */
    private fun committed(block: suspend () -> Unit): Boolean =
        runCatching {
            runBlocking {
                sqlite.unitOfWork().write("test.dataStepStore") {
                    block()
                    Outcome.Ok(Unit)
                }
            }
        }.getOrNull() is Outcome.Ok

    private fun record(
        name: String,
        appliedAt: Instant = at,
        rows: Int = 1,
        version: String = "4.0.0-test"
    ): Boolean = committed { store.record(name, appliedAt, rows, version) }

    private fun applied(): Set<String> =
        runBlocking {
            when (val outcome = sqlite.unitOfWork().write("test.dataStepApplied") { Outcome.Ok(store.appliedNames()) }) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> error("unit failed: ${outcome.error.message}")
            }
        }

    private fun rowsAt(url: String): List<Map<String, Any?>> =
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name, applied_at, rows_affected, binary_version FROM data_steps ORDER BY name").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                mapOf(
                                    "name" to rs.getString(1),
                                    "applied_at" to rs.getString(2),
                                    "rows_affected" to rs.getInt(3),
                                    "binary_version" to rs.getString(4)
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun rows() = rowsAt(sqlite.jdbcUrl)

    @Test
    fun `a fresh database has no applied names`() {
        assertEquals(emptySet(), applied())
    }

    @Test
    fun `a recorded step round-trips every column exactly`() {
        assertTrue(record("round-trip", Instant.parse("2026-03-04T05:06:07.123456789Z"), 42, "4.0.0-SNAPSHOT+abc"))

        val row = rows().single()
        assertEquals("round-trip", row["name"])
        assertEquals("2026-03-04 05:06:07.123", row["applied_at"], "canonical 23-character UTC text, millisecond precision")
        assertEquals(42, row["rows_affected"])
        assertEquals("4.0.0-SNAPSHOT+abc", row["binary_version"])
        assertEquals(setOf("round-trip"), applied())
    }

    @Test
    fun `probe an instant with no fraction is stored as 23 characters with zero milliseconds`() {
        assertTrue(record("whole-second", Instant.parse("2026-03-04T05:06:07Z")))
        val appliedAt = rows().single()["applied_at"] as String
        assertEquals("2026-03-04 05:06:07.000", appliedAt)
        assertEquals(23, appliedAt.length)
    }

    @Test
    fun `appliedNames returns exactly the recorded names`() {
        assertTrue(record("zeta-step"))
        assertTrue(record("alpha-step"))
        assertTrue(record("mid-step"))
        assertEquals(setOf("alpha-step", "mid-step", "zeta-step"), applied())
    }

    @Test
    fun `appliedNames reads the table, not a cache, so a row inserted behind the store's back is seen`() {
        assertEquals(emptySet(), applied())
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO data_steps (name, applied_at, rows_affected, binary_version) VALUES ('raw-row', '2026-01-02 03:04:05.678', 0, 'raw')"
                )
            }
        }
        assertEquals(setOf("raw-row"), applied())
    }

    @Test
    fun `a second record of the same name is rejected and the first row is kept unchanged`() {
        assertTrue(record("dup-step", Instant.parse("2026-03-04T05:06:07.123Z"), 5, "first-version"))

        val second = record("dup-step", Instant.parse("2026-04-05T06:07:08.456Z"), 9, "second-version")

        assertFalse(second, "the primary key must reject the duplicate")
        val row = rows().single()
        assertEquals("2026-03-04 05:06:07.123", row["applied_at"], "the stored row is not overwritten")
        assertEquals(5, row["rows_affected"])
        assertEquals("first-version", row["binary_version"])
    }

    @Test
    fun `rows_affected below zero is rejected, zero and the integer maximum are accepted`() {
        assertFalse(record("negative-rows", rows = -1), "rows_affected >= 0 CHECK")
        assertEquals(emptyList(), rows())
        assertTrue(record("zero-rows", rows = 0))
        assertTrue(record("max-rows", rows = Int.MAX_VALUE))
        assertEquals(listOf(Int.MAX_VALUE, 0), rows().map { it["rows_affected"] })
    }

    @Test
    fun `name length is bounded to 1 through 64 characters`() {
        assertFalse(record(""), "an empty name violates length(name) >= 1")
        assertFalse(record("a".repeat(65)), "65 characters violates length(name) <= 64")
        assertEquals(emptyList(), rows())
        assertTrue(record("a"))
        assertTrue(record("a".repeat(64)))
        assertEquals(setOf("a", "a".repeat(64)), applied())
    }

    @Test
    fun `record and appliedNames share the ambient unit so the unit sees its own uncommitted row`() {
        var seenInside: Set<String> = emptySet()
        assertTrue(
            committed {
                store.record("same-unit", at, 1, "v")
                seenInside = store.appliedNames()
            }
        )
        assertEquals(setOf("same-unit"), seenInside, "a read in the same unit must see the row written earlier in it")
    }

    @Test
    fun `record joins the ambient unit so a throw after it rolls the row back and frees the name`() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                sqlite.unitOfWork().write<Unit>("test.dataStepRollback") {
                    store.record("rolled-back", at, 1, "v")
                    throw IllegalStateException("boom after record")
                }
            }
        }
        assertEquals(emptyList(), rows(), "the row written inside the failed unit must not be committed")
        assertEquals(emptySet(), applied())
        assertTrue(record("rolled-back"), "and the name is free afterwards")
    }

    @Test
    fun `a record outside any unit throws under the production FAIL policy and writes nothing`() {
        SqliteTestDatabase.open(OutsideUnitPolicy.FAIL).use { db ->
            val strictStore = SqliteDataStepStore(db.databaseManager)

            assertFails { runBlocking { strictStore.record("outside-unit", at, 1, "v") } }

            assertEquals(emptyList(), rowsAt(db.jdbcUrl), "a write outside a unit must not land")
        }
    }
}

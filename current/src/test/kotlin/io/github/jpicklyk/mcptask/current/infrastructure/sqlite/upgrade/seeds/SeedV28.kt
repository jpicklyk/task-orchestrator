package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V28 adds `notes.version INTEGER NOT NULL DEFAULT 1` (the note version of Q11): a schema-only `ALTER TABLE ... ADD COLUMN`,
 * so no row is rewritten and every pre-existing note reads version 1 afterwards.
 *
 * Oracle: migration-assessment sections 1, 3 and 5 (frozen): one new column named `version`, type INTEGER, NOT NULL, default 1,
 * no new index, no CHECK, the other notes columns, the notes indexes and the six notes FTS triggers untouched, every existing
 * note at version 1, and the note count unchanged. The migration changes no pre-existing column, so [expected] is empty (the
 * pre-migration dump has no `version` column to compare); [verify] pins the new column.
 *
 * [seed] adds two notes at the pre-migration version: one with the actor and verification columns set, one with a multi-line
 * body (the shapes an upsert later bumps).
 */
object SeedV28 : MigrationSeed(28) {
    private const val ACTOR_NOTE = "s28-actor-note"
    private const val MULTILINE_NOTE = "s28-multiline-note"

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    private fun note(
        conn: Connection,
        key: String,
        body: String,
        withActor: Boolean
    ) = BaselineDataset.insert(
        conn,
        "notes",
        "id" to BaselineDataset.id("note:feature:$key"),
        "work_item_id" to BaselineDataset.id("feature"),
        "key" to key,
        "role" to "work",
        "body" to body,
        "created_at" to BaselineDataset.TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "actor_id" to if (withActor) "s28-actor" else null,
        "actor_kind" to if (withActor) "subagent" else null,
        "actor_parent" to if (withActor) "s28-parent" else null,
        "verification_status" to if (withActor) "VERIFIED" else null,
        "verification_verifier" to if (withActor) "jwks" else null
    )

    override fun seed(conn: Connection) {
        note(conn, ACTOR_NOTE, "A note written by an identified actor", withActor = true)
        note(conn, MULTILINE_NOTE, "line one\nline two\r\nline three", withActor = false)
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    override fun verify(conn: Connection) {
        verifyColumn(conn)
        verifyRows(conn)
        verifyNothingElseChanged(conn)
        verifyConstraint(conn)
    }

    private fun verifyColumn(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(notes)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))
                            )
                        }
                    }
                }
            }
        val version = columns.filter { it[0] == "version" }
        assertEquals(listOf(listOf("version", "INTEGER", 1, "1")), version, "notes.version (name, type, notnull, default)")
        assertEquals("version", columns.last()[0], "ADD COLUMN appends: version is the last notes column")
    }

    private fun verifyRows(conn: Connection) {
        val rows =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT key, version FROM notes ORDER BY key").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) }
                }
            }
        assertTrue(rows.isNotEmpty(), "the baseline and the seeded notes exist")
        assertTrue(rows.any { it.first == ACTOR_NOTE } && rows.any { it.first == MULTILINE_NOTE }, "the seeded notes survived: $rows")
        assertEquals(emptyList(), rows.filter { it.second != 1L }, "every pre-existing note reads version 1")
        val body =
            conn.prepareStatement("SELECT body FROM notes WHERE key = ?").use { ps ->
                ps.setString(1, MULTILINE_NOTE)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    rs.getString(1)
                }
            }
        assertEquals("line one\nline two\r\nline three", body, "a body is not rewritten byte for byte")
    }

    private fun verifyNothingElseChanged(conn: Connection) {
        val expected = checkNotNull(before) { "seed() must run before verify()" }
        for ((table, rows) in expected) {
            if (table == "data_steps") continue // the production data steps run after the chain and record their own rows
            assertEquals(rows, count(conn, table), "row count of $table is unchanged by V28")
        }
        val triggers =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'notes' ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        assertEquals(
            listOf(
                "notes_fts_text_ad",
                "notes_fts_text_ai",
                "notes_fts_text_au",
                "notes_fts_trigram_ad",
                "notes_fts_trigram_ai",
                "notes_fts_trigram_au"
            ),
            triggers,
            "the six notes FTS triggers are untouched"
        )
        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(notes)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
        assertEquals(
            listOf("idx_notes_item", "idx_notes_item_key", "idx_notes_role"),
            indexes
                .filter {
                    it.startsWith("idx_")
                }.sorted(),
            "no new index: $indexes"
        )
    }

    private fun verifyConstraint(conn: Connection) {
        // The default fills an insert that omits the column; an explicit NULL is refused (NOT NULL).
        BaselineDataset.insert(
            conn,
            "notes",
            "id" to BaselineDataset.id("note:feature:s28-default-probe"),
            "work_item_id" to BaselineDataset.id("feature"),
            "key" to "s28-default-probe",
            "role" to "work",
            "body" to "probe",
            "created_at" to BaselineDataset.TS,
            "modified_at" to BaselineDataset.TS_PLUS_1
        )
        val probed =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version FROM notes WHERE key = 's28-default-probe'").use { rs ->
                    assertTrue(rs.next())
                    rs.getLong(1)
                }
            }
        assertEquals(1L, probed, "an insert without a version reads 1")
        assertFailsWith<SQLException>("version is NOT NULL") {
            conn.createStatement().use { it.executeUpdate("UPDATE notes SET version = NULL WHERE key = 's28-default-probe'") }
        }
        conn.createStatement().use { it.executeUpdate("DELETE FROM notes WHERE key = 's28-default-probe'") }
    }

    private fun count(
        conn: Connection,
        table: String
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use {
                it.next()
                it.getInt(1)
            }
        }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V34 adds `notes.owner_id TEXT` (decision r21-debate, Ruling 2): a nullable column with no default, so the ADD COLUMN is
 * schema-only, no row is rewritten and no data step runs. Every pre-4.0 note therefore reads a NULL owner (unowned), and its
 * 3.x `actor_*` and `verification_*` columns are untouched.
 *
 * Oracle: decision.md Ruling 2 (the five [verify] assertions, labelled below). The migration changes no pre-existing column,
 * so [expected] keeps its default (an empty map): the harness's exact comparison of every common column proves the actor
 * columns are unchanged, and [verify] pins the new column.
 *
 * [seed] adds, at V33, one note carrying the actor and verification columns and one with every actor column NULL.
 */
object SeedV34 : MigrationSeed(34) {
    private const val ACTOR_NOTE = "s34-actor-note"
    private const val BARE_NOTE = "s34-bare-note"

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    /** The notes index names and trigger (name, sql) pairs at the pre-migration version. */
    @Volatile
    private var objectsBefore: Pair<List<String>, List<Pair<String, String?>>>? = null

    private fun note(
        conn: Connection,
        key: String,
        withActor: Boolean
    ) = BaselineDataset.insert(
        conn,
        "notes",
        "id" to BaselineDataset.id("note:feature:$key"),
        "work_item_id" to BaselineDataset.id("feature"),
        "key" to key,
        "role" to "work",
        "body" to "body of $key",
        "created_at" to BaselineDataset.TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "actor_id" to if (withActor) "s34-actor" else null,
        "actor_kind" to if (withActor) "subagent" else null,
        "verification_status" to if (withActor) "VERIFIED" else null,
        "verification_verifier" to if (withActor) "jwks" else null
    )

    override fun seed(conn: Connection) {
        note(conn, ACTOR_NOTE, withActor = true)
        note(conn, BARE_NOTE, withActor = false)
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        objectsBefore = notesObjects(conn)
    }

    override fun verify(conn: Connection) {
        verifyColumn(conn)
        verifyNoNoteWasOwned(conn)
        verifyActorColumnsUntouched(conn)
        verifyRowCounts(conn)
        verifyIndexesAndTriggers(conn)
    }

    /** Assertion 1: `owner_id` TEXT, notnull 0, default NULL, pk 0; and the last V34 column (ADD COLUMN appends). */
    private fun verifyColumn(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(notes)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(
                                    rs.getString("name"),
                                    rs.getString("type"),
                                    rs.getInt("notnull"),
                                    rs.getString("dflt_value"),
                                    rs.getInt("pk")
                                )
                            )
                        }
                    }
                }
            }
        assertEquals(
            listOf(listOf("owner_id", "TEXT", 0, null, 0)),
            columns.filter { it[0] == "owner_id" },
            "notes.owner_id (name, type, notnull, default, pk)"
        )
        // V35 appends notes.seat and notes.owner_seat after it, and this verify runs at the latest schema
        val untilV34 = columns.filter { it[0] != "seat" && it[0] != "owner_seat" }
        assertEquals("owner_id", untilV34.last()[0], "ADD COLUMN appends: owner_id is the last notes column of V34")
    }

    /** Assertion 2: nothing was backfilled, so no note has an owner. */
    private fun verifyNoNoteWasOwned(conn: Connection) {
        assertTrue(count(conn, "notes") >= 2, "the baseline and the two seeded notes exist")
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM notes WHERE owner_id IS NOT NULL"), "no pre-existing note has an owner")
    }

    private fun attribution(
        conn: Connection,
        key: String
    ): List<String?> =
        conn
            .prepareStatement("SELECT actor_id, actor_kind, verification_status, verification_verifier FROM notes WHERE key = ?")
            .use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the note $key survived the migration")
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                }
            }

    /** Assertion 3: the seeded attribution survives unchanged. */
    private fun verifyActorColumnsUntouched(conn: Connection) {
        assertEquals(
            listOf("s34-actor", "subagent", "VERIFIED", "jwks"),
            attribution(conn, ACTOR_NOTE),
            "the 3.x attribution of the seeded note is untouched"
        )
        assertEquals(listOf(null, null, null, null), attribution(conn, BARE_NOTE), "a note with no actor still has no actor")
    }

    /** Assertion 4: every user table keeps its row count (data_steps records the production steps, which run after the chain). */
    private fun verifyRowCounts(conn: Connection) {
        val counts = before ?: error("SeedV34.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V34")
        }
    }

    /** Assertion 5: the notes index set and the six notes FTS triggers are unchanged. */
    private fun verifyIndexesAndTriggers(conn: Connection) {
        val (indexes, triggers) = objectsBefore ?: error("SeedV34.seed did not run before the migration")
        val now = notesObjects(conn)
        assertEquals(indexes, now.first, "the notes index set is unchanged by V34")
        assertEquals(triggers, now.second, "the notes triggers are unchanged by V34")
        assertEquals(6, now.second.size, "the six notes FTS triggers exist")
    }

    private fun notesObjects(conn: Connection): Pair<List<String>, List<Pair<String, String?>>> =
        conn.createStatement().use { st ->
            val indexes =
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'notes' ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            val triggers =
                st
                    .executeQuery(
                        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'notes' ORDER BY name"
                    ).use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
            indexes to triggers
        }

    private fun count(
        conn: Connection,
        table: String
    ): Int = scalar(conn, "SELECT count(*) FROM $table").toInt()

    private fun scalar(
        conn: Connection,
        sql: String
    ): Long = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
}

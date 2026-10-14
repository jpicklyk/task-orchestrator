package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V35 adds one new table, `run_seats`, with its indexes, and three nullable columns: `work_items.claim_assignment_id BLOB`,
 * `notes.seat TEXT` (attribution) and `notes.owner_seat TEXT` (the seat half of the note owner; R21 ruling 8, orchestrator-decisions
 * note). No existing column is modified or dropped and no row is rewritten.
 *
 * Oracle: the frozen migration-assessment of item 95a22e6e, sections 1 to 4 (the column list with types, nullability, defaults and
 * the vocabulary and length CHECKs, the four named indexes, the two foreign keys, both ADD COLUMNs nullable with no default, no
 * data migration) and the verification list (a) to (g) of section 4. Nothing is read back from the migration file or from the
 * schema snapshot; the table definition below is written from the assessment alone.
 *
 * [verify] runs after the chain reaches the LATEST schema, so a column a later migration appends must not fail it: the new columns
 * are located by their position after every pre-V35 column (captured by [seed]), never by "is the last column".
 *
 * FTS integrity and the startup inventory are also checked by the harness itself ([UpgradeHarness.checkUpgrade]); [verify] adds
 * the explicit before/after trigger comparison of assessment (g).
 */
object SeedV35 : MigrationSeed(35) {
    private val ROOT = BaselineDataset.id("seed35-root")
    private val ITEM_A = BaselineDataset.id("seed35-item-a")
    private val ITEM_B = BaselineDataset.id("seed35-item-b")
    private val RUN_ONE = BaselineDataset.id("seed35-run-one")
    private val RUN_TWO = BaselineDataset.id("seed35-run-two")
    private val SEAT_PROBE = BaselineDataset.id("seed35-seat-probe")
    private val TS = BaselineDataset.TS

    private const val CLAIMED_ITEM = "s35-claimed"
    private const val FREE_ITEM = "s35-free"
    private const val OWNED_NOTE = "s35-owned-note"
    private const val BARE_NOTE = "s35-bare-note"

    private val HASH_A = "a".repeat(64)
    private val HASH_B = "b".repeat(64)

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    /** The work_items and notes column names at the pre-migration version. */
    @Volatile
    private var columnsBefore: Map<String, List<String>>? = null

    /** The work_items and notes index names and trigger (name, sql) pairs at the pre-migration version. */
    @Volatile
    private var objectsBefore: Map<String, Pair<List<String>, List<Pair<String, String?>>>>? = null

    override fun seed(conn: Connection) {
        // One claimed and one unclaimed item (the claim columns copy the baseline's claimed row), one note with an owner and one without.
        BaselineDataset.extraItem(conn, CLAIMED_ITEM, "queue", null)
        conn
            .prepareStatement(
                "UPDATE work_items SET claimed_by = 's35-agent', claimed_at = ?, claim_expires_at = '2026-03-01 11:15:30', " +
                    "original_claimed_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, TS)
                ps.setString(2, BaselineDataset.DB_TS)
                ps.setBytes(3, BaselineDataset.id(CLAIMED_ITEM))
                ps.executeUpdate()
            }
        BaselineDataset.extraItem(conn, FREE_ITEM, "queue", null)
        note(conn, OWNED_NOTE, owner = "s35-owner")
        note(conn, BARE_NOTE, owner = null)
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        columnsBefore = listOf("work_items", "notes").associateWith { names(conn, it) }
        objectsBefore = listOf("work_items", "notes").associateWith { objects(conn, it) }
    }

    private fun note(
        conn: Connection,
        key: String,
        owner: String?
    ) = BaselineDataset.insert(
        conn,
        "notes",
        "id" to BaselineDataset.id("note:feature:$key"),
        "work_item_id" to BaselineDataset.id("feature"),
        "key" to key,
        "role" to "work",
        "body" to "body of $key",
        "created_at" to TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "owner_id" to owner
    )

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyRunSeatsColumns(conn)
        verifyNewColumns(conn)
        verifyNoBackfill(conn)
        verifyIndexes(conn)
        verifyForeignKeys(conn)
        verifyExistingTablesUntouched(conn)
        verifyChecks(conn)
        verifyDefaults(conn)
        verifyPartialUniqueness(conn)
        verifyCascades(conn)
    }

    // ---------------------------------------------------------------------------------------------------------- columns

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    private fun col(
        name: String,
        type: String,
        notNull: Int = 0,
        default: String? = null,
        pk: Int = 0
    ) = Column(name, type, notNull, default, pk)

    /** Assessment section 1 / verification (a): the frozen column list of `run_seats`. */
    private fun verifyRunSeatsColumns(conn: Connection) {
        val expected =
            listOf(
                col("id", "BLOB", notNull = 1, pk = 1),
                col("run_id", "BLOB", notNull = 1),
                col("item_id", "BLOB"),
                col("seat", "TEXT", notNull = 1),
                col("attempt", "INTEGER", notNull = 1),
                col("principal_id", "TEXT", notNull = 1),
                col("token_hash", "TEXT", notNull = 1),
                col("status", "TEXT", notNull = 1),
                col("outcome", "TEXT"),
                col("summary", "TEXT"),
                col("links", "TEXT"),
                col("hints", "TEXT"),
                col("assigned_at", "TEXT", notNull = 1),
                col("ended_at", "TEXT"),
                col("last_seen_at", "TEXT"),
                col("usage_tokens", "INTEGER", notNull = 1, default = "0"),
                col("usage_cost_micro", "INTEGER", notNull = 1, default = "0")
            )
        assertEquals(expected, tableInfo(conn, "run_seats"), "run_seats has exactly the frozen columns, types, nullability and defaults")
    }

    /** Assessment verification (a): both ADD COLUMNs are nullable with no default, placed after every pre-V35 column. */
    private fun verifyNewColumns(conn: Connection) {
        val pre = columnsBefore ?: error("SeedV35.seed did not run before the migration")
        val workItems = tableInfo(conn, "work_items")
        val notes = tableInfo(conn, "notes")
        assertEquals(
            col("claim_assignment_id", "BLOB"),
            workItems.single { it.name == "claim_assignment_id" },
            "work_items.claim_assignment_id (BLOB, notnull 0, default NULL, pk 0)"
        )
        assertEquals(col("seat", "TEXT"), notes.single { it.name == "seat" }, "notes.seat (TEXT, notnull 0, default NULL, pk 0)")
        assertEquals(
            col("owner_seat", "TEXT"),
            notes.single { it.name == "owner_seat" },
            "notes.owner_seat (TEXT, notnull 0, default NULL, pk 0; R21 ruling 8)"
        )
        // ADD COLUMN appends: the pre-V35 columns keep their order and the new column follows them (later migrations may append more).
        val workItemsBefore = pre.getValue("work_items")
        assertEquals(workItemsBefore, workItems.map { it.name }.take(workItemsBefore.size), "work_items keeps its columns")
        assertEquals("claim_assignment_id", workItems[workItemsBefore.size].name, "claim_assignment_id is appended right after them")
        val notesBefore = pre.getValue("notes")
        assertEquals(notesBefore, notes.map { it.name }.take(notesBefore.size), "notes keeps its columns")
        assertEquals("seat", notes[notesBefore.size].name, "seat is appended right after them")
        assertEquals("owner_seat", notes[notesBefore.size + 1].name, "owner_seat follows seat")
    }

    /** Assessment section 3 / verification (b): nothing is backfilled and the seeded claim and owner values are unchanged. */
    private fun verifyNoBackfill(conn: Connection) {
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM work_items WHERE claim_assignment_id IS NOT NULL"),
            "no claim has an assignment"
        )
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM notes WHERE seat IS NOT NULL"), "no note has an attribution seat")
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM notes WHERE owner_seat IS NOT NULL"), "no note has an owner seat")
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM notes WHERE seat IS NOT NULL OR owner_seat IS NOT NULL"),
            "R21 ruling 8: seat and owner_seat are both NULL on every pre-existing note, the owned one included"
        )
        assertEquals(
            "s35-agent",
            string(conn, "SELECT claimed_by FROM work_items WHERE id = x'${hex(BaselineDataset.id(CLAIMED_ITEM))}'"),
            "the seeded claimant is untouched"
        )
        assertNull(
            string(conn, "SELECT claimed_by FROM work_items WHERE id = x'${hex(BaselineDataset.id(FREE_ITEM))}'"),
            "the unclaimed item is still unclaimed"
        )
        assertEquals(
            "s35-owner",
            string(conn, "SELECT owner_id FROM notes WHERE key = '$OWNED_NOTE'"),
            "the seeded note owner is untouched"
        )
        assertNull(string(conn, "SELECT owner_id FROM notes WHERE key = '$BARE_NOTE'"), "the unowned note is still unowned")
        assertEquals(0, count(conn, "run_seats"), "the migration creates run_seats empty (no backfill)")
    }

    // --------------------------------------------------------------------------------------------- indexes, keys, tables

    private data class IndexInfo(
        val unique: Int,
        val partial: Int
    )

    /** Assessment section 1 / verification (c): the four named indexes with flags and column order, plus the two implicit ones. */
    private fun verifyIndexes(conn: Connection) {
        val indexes = indexList(conn, "run_seats")

        fun named(
            name: String,
            columns: List<String>,
            unique: Int,
            partial: Int
        ) {
            val info = indexes[name] ?: error("$name missing: ${indexes.keys}")
            assertEquals(columns, indexColumns(conn, name), "$name column order")
            assertEquals(unique, info.unique, "$name unique flag")
            assertEquals(partial, info.partial, "$name partial flag")
        }
        named("idx_run_seats_run_status", listOf("run_id", "status"), unique = 0, partial = 0)
        named("idx_run_seats_item", listOf("item_id"), unique = 0, partial = 0)
        named("uq_run_seats_active", listOf("run_id", "item_id", "seat"), unique = 1, partial = 1)
        named("uq_run_seats_coordinator", listOf("run_id"), unique = 1, partial = 1)

        // The remaining indexes are SQLite's own: the primary key (id) and the UNIQUE token_hash, both unique and full.
        val implicit = indexes.filterKeys { it.startsWith("sqlite_autoindex_") }
        assertEquals(2, implicit.size, "only the primary key and the token_hash UNIQUE are implicit: ${implicit.keys}")
        assertEquals(
            setOf(listOf("id"), listOf("token_hash")),
            implicit.keys.map { indexColumns(conn, it) }.toSet(),
            "the implicit indexes cover the id and the token hash"
        )
        assertTrue(implicit.values.all { it.unique == 1 && it.partial == 0 }, "implicit indexes are unique and full")
        assertEquals(6, indexes.size, "run_seats has exactly these six indexes: ${indexes.keys}")
    }

    private data class FkRow(
        val from: String,
        val table: String,
        val to: String,
        val onDelete: String
    )

    /** Assessment verification (d): exactly the two cascading foreign keys. */
    private fun verifyForeignKeys(conn: Connection) {
        assertEquals(
            listOf(
                FkRow(from = "item_id", table = "work_items", to = "id", onDelete = "CASCADE"),
                FkRow(from = "run_id", table = "runs", to = "id", onDelete = "CASCADE")
            ),
            foreignKeys(conn, "run_seats").sortedBy { it.from },
            "run_seats references runs(id) and work_items(id), both ON DELETE CASCADE, and nothing else"
        )
    }

    /** Assessment verification (g): every other table keeps its rows and the work_items and notes triggers and indexes are unchanged. */
    private fun verifyExistingTablesUntouched(conn: Connection) {
        val counts = before ?: error("SeedV35.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V35")
        }
        val was = objectsBefore ?: error("SeedV35.seed did not run before the migration")
        for ((table, objectsThen) in was) {
            assertEquals(objectsThen, objects(conn, table), "the $table indexes and triggers are unchanged by V35")
        }
        assertEquals(8, was.getValue("work_items").second.size, "work_items keeps its 6 + 2 triggers")
        assertEquals(6, was.getValue("notes").second.size, "notes keeps its 6 triggers")
    }

    // ----------------------------------------------------------------------------------------------------------- checks

    /** Assessment verification (e): control insert first, then each CHECK rejection and each boundary acceptance. */
    private fun verifyChecks(conn: Connection) {
        // Control first: the baseline insert is accepted, so every rejection below is attributable to its one changed value.
        insertSeat(conn)
        deleteSeats(conn)

        fun rejected(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            assertFailsWith<SQLException>("$what must be rejected") { insertSeat(conn, *overrides) }
            deleteSeats(conn)
        }

        fun accepted(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            insertSeat(conn, *overrides)
            assertEquals(1, count(conn, "run_seats"), "$what must be accepted and stored")
            deleteSeats(conn)
        }

        val ended = arrayOf<Pair<String, Any?>>("status" to "ended", "outcome" to "done", "ended_at" to TS)

        rejected("status 'Active' (case-sensitive vocabulary)", "status" to "Active")
        rejected("status 'finished'", "status" to "finished")
        rejected("an active row with an outcome", "outcome" to "done")
        rejected("an active row with an ended_at", "ended_at" to TS)
        rejected("an ended row without an ended_at", "status" to "ended", "outcome" to "done")
        rejected("an ended row without an outcome", "status" to "ended", "ended_at" to TS)
        rejected("outcome 'bogus' on an ended row", "status" to "ended", "outcome" to "bogus", "ended_at" to TS)
        rejected("attempt 0", "attempt" to 0)
        rejected("attempt -1", "attempt" to -1)
        rejected("an empty seat", "seat" to "")
        rejected("a 65-character seat", "seat" to "s".repeat(65))
        rejected("a 501-character summary", *ended, "summary" to "x".repeat(501))
        rejected("a 22-character assigned_at", "assigned_at" to TS.dropLast(1))
        rejected("a 24-character assigned_at", "assigned_at" to TS + "0")
        rejected("a 22-character ended_at", "status" to "ended", "outcome" to "done", "ended_at" to TS.dropLast(1))
        rejected("a 22-character last_seen_at", "last_seen_at" to TS.dropLast(1))
        rejected("a 63-character token hash", "token_hash" to "c".repeat(63))
        rejected("a 65-character token hash", "token_hash" to "c".repeat(65))
        rejected("a negative usage_tokens", "usage_tokens" to -1)
        rejected("a negative usage_cost_micro", "usage_cost_micro" to -1)

        // The boundaries and every vocabulary member are accepted, so the rejections above are not an over-strict table.
        accepted("a 500-character summary", *ended, "summary" to "x".repeat(500))
        accepted("attempt 1", "attempt" to 1)
        accepted("a 64-character seat", "seat" to "s".repeat(64))
        accepted("a 1-character seat", "seat" to "s")
        accepted("a 64-character token hash", "token_hash" to "c".repeat(64))
        accepted("usage 0 and 0", "usage_tokens" to 0, "usage_cost_micro" to 0)
        accepted("a 23-character last_seen_at", "last_seen_at" to TS)
        for (outcome in listOf("done", "failed", "abandoned", "superseded", "run_closed")) {
            accepted("outcome $outcome on an ended row", "status" to "ended", "outcome" to outcome, "ended_at" to TS)
        }

        // The token hash is UNIQUE: a second row with the same hash is rejected, a different hash is accepted.
        val second = BaselineDataset.id("seed35-seat-probe-2")
        insertSeat(conn, "id" to SEAT_PROBE)
        assertFailsWith<SQLException>("a duplicate token hash must be rejected") { insertSeat(conn, "id" to second, "seat" to "planner") }
        insertSeat(conn, "id" to second, "seat" to "planner", "token_hash" to HASH_B)
        assertEquals(2, count(conn, "run_seats"), "control: the second hash is accepted")
        deleteSeats(conn)
    }

    /** Assessment section 1: the two counters default to 0 and every nullable column reads NULL when not supplied. */
    private fun verifyDefaults(conn: Connection) {
        insertSeat(conn)
        try {
            val row =
                conn
                    .prepareStatement(
                        "SELECT usage_tokens, usage_cost_micro, outcome, summary, links, hints, ended_at, last_seen_at " +
                            "FROM run_seats WHERE id = ?"
                    ).use { ps ->
                        ps.setBytes(1, SEAT_PROBE)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "probe seat vanished" }
                            (1..8).map { rs.getObject(it) }
                        }
                    }
            assertEquals(listOf(0, 0), row.take(2).map { (it as Number).toInt() }, "usage_tokens and usage_cost_micro default 0")
            row.drop(2).forEachIndexed { i, v -> assertNull(v, "nullable column #${i + 2} reads NULL when not supplied") }
        } finally {
            deleteSeats(conn)
        }
    }

    // ------------------------------------------------------------------------------------------------ partial uniqueness

    /** Assessment verification (e): the active triple and the active coordinator are unique, ended duplicates are not. */
    private fun verifyPartialUniqueness(conn: Connection) {
        val ended = arrayOf<Pair<String, Any?>>("status" to "ended", "outcome" to "superseded", "ended_at" to TS)

        fun id(name: String) = BaselineDataset.id("seed35-uq-$name")

        insertSeat(conn, "id" to id("1"), "token_hash" to HASH_A)
        assertFailsWith<SQLException>("a second ACTIVE row for the same (run, item, seat) must be rejected") {
            insertSeat(conn, "id" to id("2"), "token_hash" to HASH_B)
        }
        // Controls: the same triple ENDED, another seat, another item and another run are all accepted.
        insertSeat(conn, "id" to id("3"), "token_hash" to "1".repeat(64), *ended)
        insertSeat(conn, "id" to id("4"), "token_hash" to "2".repeat(64), "seat" to "planner")
        insertSeat(conn, "id" to id("5"), "token_hash" to "3".repeat(64), "item_id" to ITEM_B)
        insertSeat(conn, "id" to id("6"), "token_hash" to "4".repeat(64), "run_id" to RUN_TWO)
        assertEquals(5, count(conn, "run_seats"), "control: exactly the five accepted rows exist")
        deleteSeats(conn)

        // The coordinator rows carry no item (NULL item_id is distinct in a unique index), so a second index bounds them per run.
        fun coordinator(
            name: String,
            hash: String,
            run: ByteArray,
            vararg more: Pair<String, Any?>
        ) = insertSeat(conn, "id" to id(name), "token_hash" to hash, "run_id" to run, "item_id" to null, "seat" to "coordinator", *more)

        coordinator("c1", HASH_A, RUN_ONE)
        assertFailsWith<SQLException>("a second ACTIVE coordinator row for one run must be rejected") { coordinator("c2", HASH_B, RUN_ONE) }
        coordinator("c3", "5".repeat(64), RUN_ONE, "status" to "ended", "outcome" to "run_closed", "ended_at" to TS)
        coordinator("c4", "6".repeat(64), RUN_TWO)
        assertEquals(3, count(conn, "run_seats"), "control: an ended coordinator row and another run's coordinator are accepted")
        deleteSeats(conn)
    }

    // ---------------------------------------------------------------------------------------------------------- cascades

    /** Assessment verification (f): both cascade directions under PRAGMA foreign_keys = ON, and an empty foreign_key_check. */
    private fun verifyCascades(conn: Connection) {
        withForeignKeys(conn) {
            insertItem(conn, ROOT, parent = null, depth = 0)
            insertItem(conn, ITEM_A, parent = ROOT, depth = 1)
            insertItem(conn, ITEM_B, parent = ROOT, depth = 1)
            insertRun(conn, RUN_ONE)
            insertSeat(conn, "id" to BaselineDataset.id("seed35-cas-a"), "token_hash" to HASH_A, "item_id" to ITEM_A)
            insertSeat(conn, "id" to BaselineDataset.id("seed35-cas-b"), "token_hash" to HASH_B, "item_id" to ITEM_B)
            insertSeat(
                conn,
                "id" to BaselineDataset.id("seed35-cas-c"),
                "token_hash" to "7".repeat(64),
                "item_id" to null,
                "seat" to "coordinator"
            )
            assertEquals(3, count(conn, "run_seats"), "control: all three seat rows exist")

            // 1. Deleting an item drops its seat rows and leaves the run and the other rows.
            deleteItem(conn, ITEM_A)
            assertEquals(2, count(conn, "run_seats"), "deleting an item drops exactly its seat row")
            assertEquals(1L, scalar(conn, "SELECT count(*) FROM runs WHERE id = x'${hex(RUN_ONE)}'"), "deleting an item leaves the run")

            // 2. Deleting a run drops its seat rows (the coordinator row with a NULL item included) and leaves the items.
            deleteRun(conn, RUN_ONE)
            assertEquals(0, count(conn, "run_seats"), "deleting a run drops all its seat rows")
            assertEquals(
                1L,
                scalar(conn, "SELECT count(*) FROM work_items WHERE id = x'${hex(ITEM_B)}'"),
                "deleting a run leaves its items"
            )

            // 3. The foreign keys are real: a seat naming a run or an item that does not exist is rejected while enforcement is on.
            insertRun(conn, RUN_ONE)
            val ghost = BaselineDataset.id("seed35-no-such-row")
            assertFailsWith<SQLException>("a seat naming no run must be rejected") { insertSeat(conn, "run_id" to ghost) }
            assertFailsWith<SQLException>("a seat naming no item must be rejected") { insertSeat(conn, "item_id" to ghost) }
            assertEquals(0, count(conn, "run_seats"), "the rejected seats left no row")

            deleteRun(conn, RUN_ONE)
            deleteItem(conn, ITEM_B)
            deleteItem(conn, ROOT)
        }
        assertEquals(0, count(conn, "run_seats"), "the round trip leaves run_seats empty again")
        assertEquals(
            0,
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                    var n = 0
                    while (rs.next()) n++
                    n
                }
            },
            "PRAGMA foreign_key_check is empty after the migration and the round trip"
        )
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private fun withForeignKeys(
        conn: Connection,
        block: () -> Unit
    ) {
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        try {
            block()
        } finally {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        }
    }

    private fun insertItem(
        conn: Connection,
        id: ByteArray,
        parent: ByteArray?,
        depth: Int
    ) {
        conn
            .prepareStatement(
                "INSERT INTO work_items (id, parent_id, title, role, lifecycle, depth, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, ?, 'seed35 probe', 'queue', NULL, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setBytes(1, id)
                if (parent == null) ps.setNull(2, java.sql.Types.BLOB) else ps.setBytes(2, parent)
                ps.setInt(3, depth)
                ps.setString(4, TS)
                ps.setString(5, TS)
                ps.setString(6, TS)
                ps.executeUpdate()
            }
    }

    private fun deleteItem(
        conn: Connection,
        id: ByteArray
    ) {
        conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use { ps ->
            ps.setBytes(1, id)
            ps.executeUpdate()
        }
    }

    private fun insertRun(
        conn: Connection,
        id: ByteArray
    ) {
        conn
            .prepareStatement("INSERT INTO runs (id, root_id, status, opened_at, stall_after_s) VALUES (?, ?, 'open', ?, 600)")
            .use { ps ->
                ps.setBytes(1, id)
                ps.setBytes(2, ROOT)
                ps.setString(3, TS)
                ps.executeUpdate()
            }
    }

    private fun deleteRun(
        conn: Connection,
        id: ByteArray
    ) {
        conn.prepareStatement("DELETE FROM runs WHERE id = ?").use { ps ->
            ps.setBytes(1, id)
            ps.executeUpdate()
        }
    }

    /** Inserts a seat with the minimal valid active values; [overrides] replace or add columns by name. */
    private fun insertSeat(
        conn: Connection,
        vararg overrides: Pair<String, Any?>
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "id" to SEAT_PROBE,
                "run_id" to RUN_ONE,
                "item_id" to ITEM_A,
                "seat" to "implementer",
                "attempt" to 1,
                "principal_id" to "s35-principal",
                "token_hash" to HASH_A,
                "status" to "active",
                "assigned_at" to TS
            )
        for ((k, v) in overrides) values[k] = v
        val columns = values.keys.joinToString()
        val marks = values.keys.joinToString { "?" }
        conn.prepareStatement("INSERT INTO run_seats ($columns) VALUES ($marks)").use { ps ->
            values.values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is ByteArray -> ps.setBytes(i + 1, v)
                    is Int -> ps.setInt(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }

    private fun deleteSeats(conn: Connection) {
        conn.createStatement().use { it.execute("DELETE FROM run_seats") }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun tableInfo(
        conn: Connection,
        table: String
    ): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_xinfo($table)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Column(
                                rs.getString("name"),
                                rs.getString("type").uppercase(),
                                rs.getInt("notnull"),
                                rs.getString("dflt_value"),
                                rs.getInt("pk")
                            )
                        )
                    }
                }
            }
        }

    private fun names(
        conn: Connection,
        table: String
    ): List<String> = tableInfo(conn, table).map { it.name }

    private fun indexList(
        conn: Connection,
        table: String
    ): Map<String, IndexInfo> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list($table)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), IndexInfo(rs.getInt("unique"), rs.getInt("partial"))) }
            }
        }

    private fun indexColumns(
        conn: Connection,
        index: String
    ): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_info($index)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }

    private fun foreignKeys(
        conn: Connection,
        table: String
    ): List<FkRow> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_list($table)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(FkRow(rs.getString("from"), rs.getString("table"), rs.getString("to"), rs.getString("on_delete")))
                    }
                }
            }
        }

    /** The index names and (trigger name, sql) pairs of [table], ordered by name. */
    private fun objects(
        conn: Connection,
        table: String
    ): Pair<List<String>, List<Pair<String, String?>>> =
        conn.createStatement().use { st ->
            val indexes =
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            val triggers =
                st
                    .executeQuery(
                        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = '$table' ORDER BY name"
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

    private fun string(
        conn: Connection,
        sql: String
    ): String? = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}

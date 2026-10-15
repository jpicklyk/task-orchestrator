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
 * V36 adds one new table, `decision_requests`, with four named indexes. No existing object is touched, no column is modified or
 * dropped, no table is recreated and no row is rewritten or backfilled.
 *
 * Oracle: the frozen migration-assessment of item a53e6875, sections 1 to 4 (the column list with types, nullability, defaults and
 * the vocabulary, length and answered-consistency CHECKs, the four named indexes, the two foreign keys and the deliberate absence of
 * a `run_id` foreign key, no data migration) and the verification list (a) to (h) of section 4. Nothing is read back from the
 * migration file or from the schema snapshot; the table definition below is written from the assessment alone.
 *
 * (h), the FTS integrity check, and the startup inventory are checked by the harness itself ([UpgradeHarness.checkUpgrade]); [verify]
 * adds the explicit before/after comparison of the work_items and notes triggers and indexes.
 */
object SeedV36 : MigrationSeed(36) {
    private val ROOT = BaselineDataset.id("seed36-root")
    private val ROOT_TWO = BaselineDataset.id("seed36-root-two")
    private val ITEM_A = BaselineDataset.id("seed36-item-a")
    private val ITEM_B = BaselineDataset.id("seed36-item-b")
    private val RUN_ONE = BaselineDataset.id("seed36-run-one")
    private val PROBE = BaselineDataset.id("seed36-decision-probe")
    private val TS = BaselineDataset.TS

    private const val OPTIONS = """[{"key":"a","label":"A"}]"""

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    /** The work_items and notes index names and trigger (name, sql) pairs at the pre-migration version. */
    @Volatile
    private var objectsBefore: Map<String, Pair<List<String>, List<Pair<String, String?>>>>? = null

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        objectsBefore = listOf("work_items", "notes").associateWith { objects(conn, it) }
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyColumns(conn)
        verifyEmptyAndUntouched(conn)
        verifyIndexes(conn)
        verifyForeignKeys(conn)
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

    /** Assessment section 1 / verification (a): the frozen column list of `decision_requests`, in order. */
    private fun verifyColumns(conn: Connection) {
        val expected =
            listOf(
                col("id", "BLOB", notNull = 1, pk = 1),
                col("root_id", "BLOB", notNull = 1),
                col("run_id", "BLOB"),
                col("item_id", "BLOB"),
                col("class", "TEXT", notNull = 1),
                col("answerers", "TEXT", notNull = 1),
                col("severity", "TEXT", notNull = 1),
                col("status", "TEXT", notNull = 1),
                col("dedupe_key", "TEXT", notNull = 1),
                col("scope_key", "TEXT", notNull = 1),
                col("question", "TEXT", notNull = 1),
                col("options", "TEXT", notNull = 1),
                col("recommended", "TEXT"),
                col("links", "TEXT"),
                col("context", "TEXT"),
                col("raised_by_id", "TEXT", notNull = 1),
                col("raised_by_kind", "TEXT", notNull = 1),
                col("raised_by_seat", "TEXT"),
                col("raised_at", "TEXT", notNull = 1),
                col("expires_at", "TEXT"),
                col("on_expiry", "TEXT", notNull = 1),
                col("ack_by_id", "TEXT"),
                col("ack_by_kind", "TEXT"),
                col("ack_at", "TEXT"),
                col("answer_option", "TEXT"),
                col("answer_text", "TEXT"),
                col("answer_rationale", "TEXT"),
                col("answered_by_id", "TEXT"),
                col("answered_by_kind", "TEXT"),
                col("answered_by_proof", "TEXT"),
                col("answered_host", "TEXT"),
                col("answered_session_id", "TEXT"),
                col("answered_run_id", "BLOB"),
                col("answered_seat", "TEXT"),
                col("answered_profile", "TEXT"),
                col("answered_at", "TEXT"),
                col("after_expiry", "INTEGER", notNull = 1, default = "0"),
                col("version", "INTEGER", notNull = 1, default = "1")
            )
        assertEquals(
            expected,
            tableInfo(conn, "decision_requests"),
            "decision_requests has exactly the frozen columns, types, nullability and defaults"
        )
    }

    /** Assessment section 3 / verification (b): the table is created empty and every other table keeps its rows and objects. */
    private fun verifyEmptyAndUntouched(conn: Connection) {
        assertEquals(0, count(conn, "decision_requests"), "the migration creates decision_requests empty (no backfill)")
        val counts = before ?: error("SeedV36.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V36")
        }
        val was = objectsBefore ?: error("SeedV36.seed did not run before the migration")
        for ((table, objectsThen) in was) {
            assertEquals(objectsThen, objects(conn, table), "the $table indexes and triggers are unchanged by V36")
        }
        assertEquals(8, was.getValue("work_items").second.size, "work_items keeps its 6 + 2 triggers")
        assertEquals(6, was.getValue("notes").second.size, "notes keeps its 6 triggers")
    }

    // --------------------------------------------------------------------------------------------- indexes, keys

    private data class IndexInfo(
        val unique: Int,
        val partial: Int
    )

    /** Assessment section 1 / verification (c): the four named indexes with flags and column order, plus the primary key's own. */
    private fun verifyIndexes(conn: Connection) {
        val indexes = indexList(conn, "decision_requests")

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
        named("uq_decision_requests_key", listOf("root_id", "scope_key", "dedupe_key"), unique = 1, partial = 1)
        named("idx_decision_requests_run_status", listOf("run_id", "status"), unique = 0, partial = 0)
        named("idx_decision_requests_item", listOf("item_id"), unique = 0, partial = 0)
        named("idx_decision_requests_root_status_severity", listOf("root_id", "status", "severity"), unique = 0, partial = 0)

        // The remaining index is SQLite's own: the primary key (id), unique and full.
        val implicit = indexes.filterKeys { it.startsWith("sqlite_autoindex_") }
        assertEquals(1, implicit.size, "only the primary key is implicit: ${implicit.keys}")
        assertEquals(listOf("id"), indexColumns(conn, implicit.keys.single()), "the implicit index covers the id")
        assertTrue(implicit.values.all { it.unique == 1 && it.partial == 0 }, "the implicit index is unique and full")
        assertEquals(5, indexes.size, "decision_requests has exactly these five indexes: ${indexes.keys}")
    }

    private data class FkRow(
        val from: String,
        val table: String,
        val to: String,
        val onDelete: String
    )

    /** Assessment verification (d): exactly the two cascading foreign keys to work_items; no run_id foreign key. */
    private fun verifyForeignKeys(conn: Connection) {
        assertEquals(
            listOf(
                FkRow(from = "item_id", table = "work_items", to = "id", onDelete = "CASCADE"),
                FkRow(from = "root_id", table = "work_items", to = "id", onDelete = "CASCADE")
            ),
            foreignKeys(conn, "decision_requests").sortedBy { it.from },
            "decision_requests references work_items(id) through root_id and item_id, both ON DELETE CASCADE, and nothing else (no run_id key)"
        )
    }

    // ----------------------------------------------------------------------------------------------------------- checks

    /** Assessment verification (e): control insert first, then each CHECK rejection and each boundary acceptance. */
    private fun verifyChecks(conn: Connection) {
        // Control first: the baseline insert is accepted, so every rejection below is attributable to its one changed value.
        insertDecision(conn)
        assertEquals(1, count(conn, "decision_requests"), "control: the baseline decision is accepted")
        deleteDecisions(conn)

        fun rejected(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            assertFailsWith<SQLException>("$what must be rejected") { insertDecision(conn, *overrides) }
            deleteDecisions(conn)
        }

        fun accepted(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            insertDecision(conn, *overrides)
            assertEquals(1, count(conn, "decision_requests"), "$what must be accepted and stored")
            deleteDecisions(conn)
        }

        val answered = arrayOf<Pair<String, Any?>>("status" to "answered", "answered_at" to TS, "answered_by_id" to "s36-answerer")

        rejected("status 'Open' (case-sensitive vocabulary)", "status" to "Open")
        rejected("status 'closed'", "status" to "closed")
        rejected("answerers 'human'", "answerers" to "human")
        rejected("answerers 'Any-Actor'", "answerers" to "Any-Actor")
        rejected("severity 'urgent'", "severity" to "urgent")
        rejected("severity 'High'", "severity" to "High")
        rejected("on_expiry 'default'", "on_expiry" to "default")
        rejected("an empty class", "class" to "")
        rejected("a 65-character class", "class" to "c".repeat(65))
        rejected("an empty dedupe_key", "dedupe_key" to "")
        rejected("a 129-character dedupe_key", "dedupe_key" to "k".repeat(129))
        rejected("an empty scope_key", "scope_key" to "")
        rejected("a 37-character scope_key", "scope_key" to "s".repeat(37))
        rejected("an empty question", "question" to "")
        rejected("a 501-character question", "question" to "q".repeat(501))
        rejected("a 1001-character context", "context" to "x".repeat(1001))
        rejected("a 33-character recommended", "recommended" to "r".repeat(33))
        rejected("a 33-character answer_option", *answered, "answer_option" to "o".repeat(33))
        rejected("a 1001-character answer_text", *answered, "answer_text" to "t".repeat(1001))
        rejected("a 1001-character answer_rationale", *answered, "answer_rationale" to "r".repeat(1001))
        rejected("a 22-character raised_at", "raised_at" to TS.dropLast(1))
        rejected("a 24-character raised_at", "raised_at" to TS + "0")
        rejected("a 22-character expires_at", "expires_at" to TS.dropLast(1))
        rejected("a 24-character expires_at", "expires_at" to TS + "0")
        rejected("a 22-character ack_at", "status" to "acknowledged", "ack_at" to TS.dropLast(1))
        rejected("a 22-character answered_at", "status" to "answered", "answered_at" to TS.dropLast(1), "answered_by_id" to "s36-answerer")
        rejected("an answered row without an answered_at", "status" to "answered", "answered_by_id" to "s36-answerer")
        rejected("an answered row without an answered_by_id", "status" to "answered", "answered_at" to TS)
        rejected("an open row with an answered_at", "answered_at" to TS)
        rejected("an open row with an answered_by_id", "answered_by_id" to "s36-answerer")
        rejected("an acknowledged row with an answered_at", "status" to "acknowledged", "answered_at" to TS)
        rejected("a withdrawn row with an answered_by_id", "status" to "withdrawn", "answered_by_id" to "s36-answerer")
        rejected("after_expiry 2", "after_expiry" to 2)
        rejected("after_expiry -1", "after_expiry" to -1)
        rejected("a NULL question", "question" to null)
        rejected("a NULL options", "options" to null)
        rejected("a NULL raised_by_id", "raised_by_id" to null)
        rejected("a NULL raised_by_kind", "raised_by_kind" to null)
        rejected("a NULL root_id", "root_id" to null)

        // The boundaries and every vocabulary member are accepted, so the rejections above are not an over-strict table.
        accepted("a 500-character question", "question" to "q".repeat(500))
        accepted("a 1-character question", "question" to "q")
        accepted("a 1000-character context", "context" to "x".repeat(1000))
        accepted("a 128-character dedupe_key", "dedupe_key" to "k".repeat(128))
        accepted("a 1-character dedupe_key", "dedupe_key" to "k")
        accepted("a 36-character scope_key", "scope_key" to "s".repeat(36))
        accepted("a 1-character scope_key", "scope_key" to "s")
        accepted("a 64-character class", "class" to "c".repeat(64))
        accepted("a 1-character class", "class" to "c")
        accepted("a 32-character recommended", "recommended" to "r".repeat(32))
        accepted("a 23-character expires_at", "expires_at" to TS)
        accepted("a 23-character ack_at", "status" to "acknowledged", "ack_at" to TS, "ack_by_id" to "s36-acker", "ack_by_kind" to "human")
        accepted("after_expiry 0", "after_expiry" to 0)
        accepted("after_expiry 1", *answered, "after_expiry" to 1)
        accepted("a 32-character answer_option", *answered, "answer_option" to "o".repeat(32))
        accepted("a 1000-character answer_text", *answered, "answer_text" to "t".repeat(1000))
        accepted("a 1000-character answer_rationale", *answered, "answer_rationale" to "r".repeat(1000))
        accepted(
            "a 1000-character answer_text with a 1000-character rationale",
            *answered,
            "answer_text" to "t".repeat(1000),
            "answer_rationale" to "r".repeat(1000)
        )
        for (status in listOf("open", "acknowledged", "withdrawn")) {
            accepted("status $status", "status" to status)
        }
        accepted("status answered with answered_at and answered_by_id", *answered)
        for (answerers in listOf("human-only", "director-or-human", "any-actor")) {
            accepted("answerers $answerers", "answerers" to answerers)
        }
        for (severity in listOf("high", "normal", "low")) {
            accepted("severity $severity", "severity" to severity)
        }
        accepted("a run-scoped row with no item", "run_id" to RUN_ONE, "item_id" to null, "scope_key" to "run")
        accepted("an item-scoped row", "item_id" to ITEM_A, "scope_key" to "item")
        accepted("an answer that names an answering run", *answered, "answered_run_id" to RUN_ONE)
    }

    /** Assessment section 1: `version` defaults to 1, `after_expiry` to 0, and every nullable column reads NULL when not supplied. */
    private fun verifyDefaults(conn: Connection) {
        insertDecision(conn)
        try {
            val nullable =
                listOf(
                    "run_id",
                    "item_id",
                    "recommended",
                    "links",
                    "context",
                    "raised_by_seat",
                    "expires_at",
                    "ack_by_id",
                    "ack_by_kind",
                    "ack_at",
                    "answer_option",
                    "answer_text",
                    "answer_rationale",
                    "answered_by_id",
                    "answered_by_kind",
                    "answered_by_proof",
                    "answered_host",
                    "answered_session_id",
                    "answered_run_id",
                    "answered_seat",
                    "answered_profile",
                    "answered_at"
                )
            val row =
                conn
                    .prepareStatement("SELECT version, after_expiry, ${nullable.joinToString()} FROM decision_requests WHERE id = ?")
                    .use { ps ->
                        ps.setBytes(1, PROBE)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "probe decision vanished" }
                            (1..(2 + nullable.size)).map { rs.getObject(it) }
                        }
                    }
            assertEquals(listOf(1, 0), row.take(2).map { (it as Number).toInt() }, "version defaults to 1 and after_expiry to 0")
            nullable.forEachIndexed { i, name ->
                assertEquals(null, row[2 + i], "nullable column $name reads NULL when not supplied")
            }
        } finally {
            deleteDecisions(conn)
        }
    }

    // ------------------------------------------------------------------------------------------------ partial uniqueness

    /** Assessment verification (f): the key is unique among non-withdrawn rows and a withdrawn row falls out of the index. */
    private fun verifyPartialUniqueness(conn: Connection) {
        fun id(name: String) = BaselineDataset.id("seed36-uq-$name")

        insertDecision(conn, "id" to id("1"))
        assertFailsWith<SQLException>("a second OPEN row with the same (root, scope, dedupe) key must be rejected") {
            insertDecision(conn, "id" to id("2"))
        }
        for (status in listOf("acknowledged")) {
            assertFailsWith<SQLException>("a second $status row with the same key must be rejected") {
                insertDecision(conn, "id" to id("2"), "status" to status)
            }
        }
        assertFailsWith<SQLException>("a second ANSWERED row with the same key must be rejected") {
            insertDecision(
                conn,
                "id" to id("2"),
                "status" to "answered",
                "answered_at" to TS,
                "answered_by_id" to "s36-answerer"
            )
        }
        // Controls: the same key WITHDRAWN (twice), another scope, another dedupe key and another root are all accepted.
        insertDecision(conn, "id" to id("3"), "status" to "withdrawn")
        insertDecision(conn, "id" to id("4"), "status" to "withdrawn")
        insertDecision(conn, "id" to id("5"), "scope_key" to "other-scope")
        insertDecision(conn, "id" to id("6"), "dedupe_key" to "other-key")
        insertDecision(conn, "id" to id("7"), "root_id" to ROOT_TWO)
        assertEquals(6, count(conn, "decision_requests"), "control: exactly the accepted rows exist")

        // A row that leaves the index by being withdrawn lets a fresh open row take its key.
        conn.prepareStatement("UPDATE decision_requests SET status = 'withdrawn' WHERE id = ?").use { ps ->
            ps.setBytes(1, id("1"))
            ps.executeUpdate()
        }
        insertDecision(conn, "id" to id("8"))
        assertEquals(7, count(conn, "decision_requests"), "a re-raise after a withdraw creates a new row")
        // A withdrawn row cannot come back to open while a live row holds its key.
        assertFailsWith<SQLException>("reopening a withdrawn row over a live duplicate must be rejected") {
            conn.prepareStatement("UPDATE decision_requests SET status = 'open' WHERE id = ?").use { ps ->
                ps.setBytes(1, id("1"))
                ps.executeUpdate()
            }
        }
        deleteDecisions(conn)

        // NULL run_id and item_id play no part in the key: scope_key is never NULL, so equal keys collide whatever the ids.
        insertDecision(conn, "id" to id("9"), "run_id" to RUN_ONE)
        assertFailsWith<SQLException>("the key collides whatever run_id and item_id say") {
            insertDecision(conn, "id" to id("10"), "run_id" to null, "item_id" to ITEM_A)
        }
        deleteDecisions(conn)
    }

    // ---------------------------------------------------------------------------------------------------------- cascades

    /** Assessment verification (g): both cascade directions under PRAGMA foreign_keys = ON, the missing run_id key, and an empty check. */
    private fun verifyCascades(conn: Connection) {
        withForeignKeys(conn) {
            insertItem(conn, ROOT, parent = null, depth = 0)
            insertItem(conn, ITEM_A, parent = ROOT, depth = 1)
            insertItem(conn, ITEM_B, parent = ROOT, depth = 1)
            insertDecision(conn, "id" to BaselineDataset.id("seed36-cas-item"), "item_id" to ITEM_A, "scope_key" to "item-a")
            insertDecision(conn, "id" to BaselineDataset.id("seed36-cas-run"), "run_id" to RUN_ONE, "scope_key" to "run-one")
            insertDecision(conn, "id" to BaselineDataset.id("seed36-cas-root"), "scope_key" to "root")
            assertEquals(
                3,
                count(conn, "decision_requests"),
                "control: all three decisions exist (a run_id naming no run is legal: no run key)"
            )

            // 1. Deleting an item drops its decisions and leaves the run-scoped and root-scoped ones.
            deleteItem(conn, ITEM_A)
            assertEquals(2, count(conn, "decision_requests"), "deleting an item drops exactly its decisions")
            assertEquals(
                0L,
                scalar(conn, "SELECT count(*) FROM decision_requests WHERE item_id = x'${hex(ITEM_A)}'"),
                "the item-scoped decision is gone"
            )
            deleteItem(conn, ITEM_B)
            assertEquals(2, count(conn, "decision_requests"), "deleting an item without decisions leaves every decision")

            // 2. The foreign keys are real: a decision naming a root or an item that does not exist is rejected while enforcement is on.
            val ghost = BaselineDataset.id("seed36-no-such-row")
            assertFailsWith<SQLException>("a decision naming no root must be rejected") {
                insertDecision(conn, "id" to BaselineDataset.id("seed36-ghost-root"), "root_id" to ghost, "scope_key" to "ghost-root")
            }
            assertFailsWith<SQLException>("a decision naming no item must be rejected") {
                insertDecision(conn, "id" to BaselineDataset.id("seed36-ghost-item"), "item_id" to ghost, "scope_key" to "ghost-item")
            }
            assertEquals(2, count(conn, "decision_requests"), "the rejected decisions left no row")

            // 3. Deleting the root drops every decision of the root.
            deleteItem(conn, ROOT)
            assertEquals(0, count(conn, "decision_requests"), "deleting the root drops every decision of the root")
        }
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
                    "VALUES (?, ?, 'seed36 probe', 'queue', NULL, ?, ?, ?, ?)"
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

    /** Inserts a decision with the minimal valid open values; [overrides] replace or add columns by name. */
    private fun insertDecision(
        conn: Connection,
        vararg overrides: Pair<String, Any?>
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "id" to PROBE,
                "root_id" to ROOT,
                "class" to "question",
                "answerers" to "any-actor",
                "severity" to "low",
                "status" to "open",
                "dedupe_key" to "k1",
                "scope_key" to "root",
                "question" to "Which one?",
                "options" to OPTIONS,
                "raised_by_id" to "s36-principal",
                "raised_by_kind" to "agent",
                "raised_at" to TS,
                "on_expiry" to "escalate"
            )
        for ((k, v) in overrides) values[k] = v
        val columns = values.keys.joinToString { "\"$it\"" }
        val marks = values.keys.joinToString { "?" }
        conn.prepareStatement("INSERT INTO decision_requests ($columns) VALUES ($marks)").use { ps ->
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

    private fun deleteDecisions(conn: Connection) {
        conn.createStatement().use { it.execute("DELETE FROM decision_requests") }
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
}

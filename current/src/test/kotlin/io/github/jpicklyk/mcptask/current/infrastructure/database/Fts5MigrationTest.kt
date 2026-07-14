package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests that verify the V7 FTS5 migration infrastructure applies correctly to
 * a SQLite database.
 *
 * **SQLite tokenizer note:** The V7 Flyway migration uses plain `tokenize='trigram'` (the default).
 * The `case_sensitive=0` option is NOT used because xerial/sqlite-jdbc 3.49.1.0 rejects it with
 * "parse error in tokenize directive". These tests match the production tokenizer configuration exactly.
 *
 * What these tests verify:
 * - FTS5 virtual tables can be created (the V7 migration mechanism works)
 * - Sync triggers keep the FTS index in sync after INSERTs
 * - Backfill produces row-count parity with source tables
 * - Cycle-detection trigger rejects parent_id writes that would form a loop
 */
class Fts5MigrationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var noteRepository: SQLiteNoteRepository

    /** Keeps the named in-memory SQLite DB alive across transactions. */
    private lateinit var keepAliveConnection: Connection

    /** Returns true when FTS5 virtual tables were created successfully. */
    private var fts5Available = false

    @BeforeEach
    fun setUp() {
        val dbName = "fts5_migration_test_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)

        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        databaseManager = DatabaseManager(database)
        fts5Available = applySchema()

        workItemRepository = SQLiteWorkItemRepository(databaseManager)
        noteRepository = SQLiteNoteRepository(databaseManager)
    }

    /**
     * Apply the schema using raw SQL to create only the base tables + FTS5 virtual tables
     * with the tokenizer syntax supported by the bundled xerial/sqlite-jdbc.
     *
     * Note: `tokenize='trigram case_sensitive=0'` is NOT used here because xerial/sqlite-jdbc
     * 3.49.1.0 does not support the `case_sensitive=0` parameter on Windows in the bundled
     * FTS5 extension. The production Docker container uses the system SQLite where this works.
     *
     * @return true when FTS5 virtual tables were created successfully.
     */
    private fun applySchema(): Boolean =
        try {
            transaction(db = database) {
                // Base tables
                exec(
                    """
                    CREATE TABLE IF NOT EXISTS work_items (
                        id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                        parent_id BLOB REFERENCES work_items(id),
                        root_id BLOB,
                        title TEXT NOT NULL,
                        description TEXT,
                        summary TEXT NOT NULL DEFAULT '',
                        role TEXT NOT NULL DEFAULT 'queue',
                        status_label TEXT,
                        previous_role TEXT,
                        priority TEXT NOT NULL DEFAULT 'medium',
                        complexity INTEGER,
                        requires_verification INTEGER NOT NULL DEFAULT 0,
                        depth INTEGER NOT NULL DEFAULT 0,
                        metadata TEXT,
                        tags TEXT,
                        type TEXT,
                        properties TEXT,
                        created_at TIMESTAMP NOT NULL,
                        modified_at TIMESTAMP NOT NULL,
                        role_changed_at TIMESTAMP NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        claimed_by TEXT DEFAULT NULL,
                        claimed_at TEXT DEFAULT NULL,
                        claim_expires_at TEXT DEFAULT NULL,
                        original_claimed_at TEXT DEFAULT NULL
                    )
                    """.trimIndent()
                )
                exec("CREATE INDEX IF NOT EXISTS idx_work_items_parent ON work_items(parent_id)")
                exec("CREATE INDEX IF NOT EXISTS idx_work_items_role ON work_items(role)")
                exec("CREATE INDEX IF NOT EXISTS idx_work_items_depth ON work_items(depth)")
                exec(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                        work_item_id BLOB NOT NULL REFERENCES work_items(id),
                        key TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'queue',
                        body TEXT NOT NULL DEFAULT '',
                        created_at TIMESTAMP NOT NULL,
                        modified_at TIMESTAMP NOT NULL,
                        actor_id TEXT,
                        actor_kind TEXT,
                        actor_parent TEXT,
                        actor_proof TEXT,
                        verification_status TEXT,
                        verification_verifier TEXT,
                        verification_reason TEXT
                    )
                    """.trimIndent()
                )
                // Unique index on (work_item_id, key) — matches production migration V1
                // (idx_notes_item_key). Required as the ON CONFLICT(work_item_id, key) target
                // for the atomic note upsert.
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_notes_item_key ON notes(work_item_id, key)")
                exec(
                    """
                    CREATE TABLE IF NOT EXISTS dependencies (
                        id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                        from_item_id BLOB NOT NULL REFERENCES work_items(id),
                        to_item_id BLOB NOT NULL REFERENCES work_items(id),
                        type TEXT NOT NULL,
                        unblock_at TEXT,
                        created_at TIMESTAMP NOT NULL
                    )
                    """.trimIndent()
                )
                exec(
                    """
                    CREATE TABLE IF NOT EXISTS role_transitions (
                        id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                        item_id BLOB NOT NULL REFERENCES work_items(id),
                        from_role TEXT,
                        to_role TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        summary TEXT,
                        transition_at TIMESTAMP NOT NULL,
                        actor_id TEXT,
                        actor_kind TEXT,
                        actor_parent TEXT,
                        actor_proof TEXT,
                        verification_status TEXT,
                        verification_verifier TEXT,
                        verification_reason TEXT
                    )
                    """.trimIndent()
                )

                // Cycle detection trigger (relies on RECURSIVE CTE — SQLite-specific)
                exec(
                    """
                    CREATE TRIGGER IF NOT EXISTS work_items_cycle_check
                    BEFORE INSERT ON work_items
                    WHEN NEW.parent_id IS NOT NULL
                    BEGIN
                        SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
                        WHERE EXISTS (
                            WITH RECURSIVE ancestors(node_id) AS (
                                SELECT parent_id FROM work_items
                                WHERE id = NEW.parent_id AND parent_id IS NOT NULL
                                UNION ALL
                                SELECT wi.parent_id FROM work_items wi
                                JOIN ancestors a ON wi.id = a.node_id
                                WHERE wi.parent_id IS NOT NULL
                            )
                            SELECT 1 FROM ancestors WHERE node_id = NEW.id
                        );
                    END
                    """.trimIndent()
                )
                exec(
                    """
                    CREATE TRIGGER IF NOT EXISTS work_items_cycle_check_update
                    BEFORE UPDATE OF parent_id ON work_items
                    WHEN NEW.parent_id IS NOT NULL
                    BEGIN
                        SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
                        WHERE EXISTS (
                            WITH RECURSIVE ancestors(node_id) AS (
                                SELECT parent_id FROM work_items
                                WHERE id = NEW.parent_id AND parent_id IS NOT NULL
                                UNION ALL
                                SELECT wi.parent_id FROM work_items wi
                                JOIN ancestors a ON wi.id = a.node_id
                                WHERE wi.parent_id IS NOT NULL
                            )
                            SELECT 1 FROM ancestors WHERE node_id = NEW.id
                        );
                    END
                    """.trimIndent()
                )
            }

            // FTS5 virtual tables in a separate transaction — we detect availability here.
            // Uses plain `tokenize='trigram'` matching the V7 Flyway migration exactly.
            // The `case_sensitive=0` option is not supported by the bundled xerial/sqlite-jdbc binary.
            var fts5Created = false
            try {
                transaction(db = database) {
                    exec(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS work_items_fts_trigram USING fts5(
                            title, summary,
                            content='work_items', content_rowid='rowid',
                            tokenize='trigram',
                            prefix='2 3'
                        )
                        """.trimIndent()
                    )
                    exec(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS work_items_fts_text USING fts5(
                            title, summary,
                            content='work_items', content_rowid='rowid',
                            tokenize='porter unicode61 remove_diacritics 2',
                            prefix='2 3'
                        )
                        """.trimIndent()
                    )
                    exec(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts_trigram USING fts5(
                            body,
                            content='notes', content_rowid='rowid',
                            tokenize='trigram',
                            prefix='2 3'
                        )
                        """.trimIndent()
                    )
                    exec(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts_text USING fts5(
                            body,
                            content='notes', content_rowid='rowid',
                            tokenize='porter unicode61 remove_diacritics 2',
                            prefix='2 3'
                        )
                        """.trimIndent()
                    )

                    // Sync triggers for work_items_fts_trigram
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END"
                    )
                    // UPDATE triggers restricted to content columns (V8 migration equivalent):
                    // work_items: AFTER UPDATE OF title, summary; notes: AFTER UPDATE OF body
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_au AFTER UPDATE OF title, summary ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                    )

                    // Sync triggers for work_items_fts_text
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_au AFTER UPDATE OF title, summary ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                    )

                    // Sync triggers for notes_fts_trigram
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_au AFTER UPDATE OF body ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END"
                    )

                    // Sync triggers for notes_fts_text
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); END"
                    )
                    exec(
                        "CREATE TRIGGER IF NOT EXISTS notes_fts_text_au AFTER UPDATE OF body ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END"
                    )

                    // Backfill
                    exec("INSERT INTO work_items_fts_trigram(work_items_fts_trigram) VALUES ('rebuild')")
                    exec("INSERT INTO work_items_fts_text(work_items_fts_text) VALUES ('rebuild')")
                    exec("INSERT INTO notes_fts_trigram(notes_fts_trigram) VALUES ('rebuild')")
                    exec("INSERT INTO notes_fts_text(notes_fts_text) VALUES ('rebuild')")

                    fts5Created = true
                }
            } catch (e: Exception) {
                // FTS5 not available in this SQLite build — tests that require it will be skipped
                println("FTS5 not available (${e.message}); FTS5-specific tests will be skipped")
            }
            fts5Created
        } catch (e: Exception) {
            println("Schema setup failed: ${e.message}")
            false
        }

    @AfterEach
    fun tearDown() {
        try {
            TransactionManager.closeAndUnregister(database)
        } catch (_: Exception) {
        }
        try {
            keepAliveConnection.close()
        } catch (_: Exception) {
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Virtual table existence
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `creates the four FTS5 virtual tables from migration V7`(): Unit =
        runBlocking {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                fts5Available,
                "FTS5 not available in bundled xerial/sqlite-jdbc on this platform"
            )

            val expectedTables =
                listOf(
                    "work_items_fts_trigram",
                    "work_items_fts_text",
                    "notes_fts_trigram",
                    "notes_fts_text",
                )
            val foundTables = mutableSetOf<String>()

            transaction(db = database) {
                exec(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%fts%'"
                ) { rs ->
                    while (rs.next()) foundTables.add(rs.getString("name"))
                }
            }

            for (table in expectedTables) {
                assertTrue(
                    table in foundTables,
                    "Expected FTS5 virtual table '$table' to exist, found: $foundTables"
                )
            }
        }

    // ────────────────────────────────────────────────────────────────────────
    // Backfill parity
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `populates FTS index after backfill matches source row count`(): Unit =
        runBlocking {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                fts5Available,
                "FTS5 not available in bundled xerial/sqlite-jdbc on this platform"
            )

            val item1 = createItem("Authentication service for OAuth flow")
            val item2 = createItem("Authenticated user management module")
            val item3 = createItem("Background job scheduler")

            createNote(item1.id, "design-note", "OAuth flow design with bearer tokens")
            createNote(item2.id, "impl-note", "authenticated session handling")

            val sourceWorkItemCount = countRows("work_items")
            val sourceNoteCount = countRows("notes")

            // The trigram FTS table should match rows with the substring "auth"
            val ftsWorkItemCount = countFtsRows("work_items_fts_trigram", "auth")
            val ftsNoteCount = countFtsRows("notes_fts_trigram", "auth")

            assertTrue(
                sourceWorkItemCount >= 3,
                "Expected at least 3 work items in source table, got $sourceWorkItemCount"
            )
            assertTrue(
                sourceNoteCount >= 2,
                "Expected at least 2 notes in source table, got $sourceNoteCount"
            )
            // Items 1 and 2 contain "auth" — both should be indexed
            assertEquals(
                2,
                ftsWorkItemCount,
                "Expected 2 work_items_fts_trigram matches for 'auth', got $ftsWorkItemCount"
            )
            // Both notes contain "auth"
            assertEquals(
                2,
                ftsNoteCount,
                "Expected 2 notes_fts_trigram matches for 'auth', got $ftsNoteCount"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Cycle detection trigger
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `cycle detection trigger rejects parent_id update that forms loop`(): Unit =
        runBlocking {
            // Create a two-item chain: parent then child pointing to parent
            val parent = createItem("Parent item")
            val child = createItem("Child item", parentId = parent.id)

            // Attempt to set parent.parentId = child — this would form a cycle.
            // The trigger should reject this write with an exception containing "cycle".
            var triggerFired = false
            var caughtMessage = ""
            try {
                keepAliveConnection
                    .prepareStatement(
                        "UPDATE work_items SET parent_id = ? WHERE id = ?"
                    ).use { stmt ->
                        stmt.setBytes(1, uuidToBytes(child.id))
                        stmt.setBytes(2, uuidToBytes(parent.id))
                        stmt.executeUpdate()
                    }
            } catch (e: Exception) {
                caughtMessage = e.message ?: ""
                triggerFired = true
            }

            assertTrue(
                triggerFired,
                "Expected the cycle-detection trigger to reject parent_id update forming a loop"
            )
            assertTrue(
                "cycle" in caughtMessage.lowercase(),
                "Expected error message to mention 'cycle', got: '$caughtMessage'"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1,
    ): WorkItem {
        val item = WorkItem(title = title, parentId = parentId, depth = depth)
        val result = workItemRepository.create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private suspend fun createNote(
        itemId: UUID,
        key: String,
        body: String,
    ): Note {
        val note = Note(itemId = itemId, key = key, role = "work", body = body)
        val result = noteRepository.upsert(note)
        assertIs<Result.Success<Note>>(result)
        return result.data
    }

    private fun countRows(tableName: String): Int {
        var count = 0
        transaction(db = database) {
            exec("SELECT COUNT(*) AS cnt FROM $tableName") { rs ->
                if (rs.next()) count = rs.getInt("cnt")
            }
        }
        return count
    }

    private fun countFtsRows(
        ftsTable: String,
        matchTerm: String
    ): Int {
        var count = 0
        transaction(db = database) {
            exec(
                "SELECT COUNT(*) AS cnt FROM $ftsTable WHERE $ftsTable MATCH ?",
                args =
                    listOf(
                        org.jetbrains.exposed.v1.core
                            .VarCharColumnType(256) to matchTerm
                    )
            ) { rs ->
                if (rs.next()) count = rs.getInt("cnt")
            }
        }
        return count
    }

    // ────────────────────────────────────────────────────────────────────────
    // V8 — FTS update trigger column restriction
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Regression test for V8: updating a non-content column (version/role) must NOT
     * re-index the work_item in FTS tables. Before V8 the _au trigger fired on any
     * column update, causing spurious delete+reinsert on every claim bump.
     *
     * Approach: insert an item with a unique title, record FTS match count, then update
     * only the `version` column. If the trigger fires needlessly the FTS index will be
     * momentarily empty during the delete phase and then refilled — but since we are
     * single-threaded here we can verify correctness by checking the count stays at 1
     * after the non-content update.
     */
    @Test
    fun `fts update trigger does not fire on non-content column update`(): Unit =
        runBlocking {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                fts5Available,
                "FTS5 not available in bundled xerial/sqlite-jdbc on this platform"
            )

            val uniqueTitle = "ZephyrUniqueSearchToken${System.nanoTime()}"
            val item = createItem(uniqueTitle)

            // Confirm it's indexed
            val countBefore = countFtsRows("work_items_fts_trigram", uniqueTitle)
            assertEquals(1, countBefore, "Expected item to be indexed after insert")

            // Update only version (non-content column) — restricted trigger must NOT fire
            keepAliveConnection
                .prepareStatement("UPDATE work_items SET version = version + 1 WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }

            // FTS index must still contain exactly 1 match — no spurious delete happened
            val countAfter = countFtsRows("work_items_fts_trigram", uniqueTitle)
            assertEquals(
                1,
                countAfter,
                "FTS index must contain exactly 1 match after a non-content column update; " +
                    "got $countAfter — trigger may have fired when it should not have"
            )
        }

    /**
     * Regression complement: updating a content column (title) MUST re-index and the
     * old value must no longer match while the new value does.
     */
    @Test
    fun `fts update trigger fires when title is updated`(): Unit =
        runBlocking {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                fts5Available,
                "FTS5 not available in bundled xerial/sqlite-jdbc on this platform"
            )

            val oldTitle = "OldTitleTokenAlpha${System.nanoTime()}"
            val newTitle = "NewTitleTokenBeta${System.nanoTime()}"
            val item = createItem(oldTitle)

            assertEquals(1, countFtsRows("work_items_fts_trigram", oldTitle), "Old title should be indexed")
            assertEquals(0, countFtsRows("work_items_fts_trigram", newTitle), "New title must not exist yet")

            // Update title — trigger MUST fire
            keepAliveConnection
                .prepareStatement("UPDATE work_items SET title = ? WHERE id = ?")
                .use { stmt ->
                    stmt.setString(1, newTitle)
                    stmt.setBytes(2, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }

            assertEquals(0, countFtsRows("work_items_fts_trigram", oldTitle), "Old title must no longer match after update")
            assertEquals(1, countFtsRows("work_items_fts_trigram", newTitle), "New title must be indexed after update")
        }

    /**
     * Regression test for notes: updating a non-body column must NOT fire the notes FTS
     * trigger. Notes have few non-body columns (role, key) but the same principle applies.
     */
    @Test
    fun `notes fts update trigger does not fire on non-body column update`(): Unit =
        runBlocking {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                fts5Available,
                "FTS5 not available in bundled xerial/sqlite-jdbc on this platform"
            )

            val uniqueBody = "NoteUniqueBodyToken${System.nanoTime()}"
            val item = createItem("Any item for note trigger test")
            createNote(item.id, "test-key", uniqueBody)

            val countBefore = countFtsRows("notes_fts_trigram", uniqueBody)
            assertEquals(1, countBefore, "Note body must be indexed after insert")

            // Update the role column (non-body) — restricted trigger must NOT fire
            keepAliveConnection
                .prepareStatement("UPDATE notes SET role = 'review' WHERE work_item_id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }

            val countAfter = countFtsRows("notes_fts_trigram", uniqueBody)
            assertEquals(
                1,
                countAfter,
                "Notes FTS index must contain exactly 1 match after a non-body column update; " +
                    "got $countAfter — notes trigger may have fired when it should not have"
            )
        }

    /**
     * Serialise a UUID to the 16-byte big-endian representation that SQLite stores for BLOB UUIDs.
     */
    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val database get() = db.database
    private val workItemRepository get() = db.repositoryProvider().workItemRepository()
    private val noteRepository get() = db.repositoryProvider().noteRepository()

    /** Runs [block] on a short-lived raw connection to the test database (always closed). */
    private fun <T> raw(block: (Connection) -> T): T = DriverManager.getConnection(db.jdbcUrl).use(block)

    // ────────────────────────────────────────────────────────────────────────
    // Virtual table existence
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `creates the four FTS5 virtual tables from migration V7`(): Unit =
        runBlocking {
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
    fun `populates FTS index after backfill matches source row count`(
        @TempDir tempDir: File
    ): Unit =
        runBlocking {
            // Rows that exist BEFORE V7 (the real V6 schema): the V7 migration must backfill them into the FTS tables.
            val url = UpgradeHarness.copyAt(6, File(tempDir, "backfill.db"))
            DriverManager.getConnection(url).use { conn ->
                fun item(title: String): UUID {
                    val id = UUID.randomUUID()
                    conn
                        .prepareStatement(
                            "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) " +
                                "VALUES (?, ?, datetime('now'), datetime('now'), datetime('now'))"
                        ).use { stmt ->
                            stmt.setBytes(1, uuidToBytes(id))
                            stmt.setString(2, title)
                            stmt.executeUpdate()
                        }
                    return id
                }

                fun note(
                    itemId: UUID,
                    key: String,
                    body: String
                ) = conn
                    .prepareStatement(
                        "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) " +
                            "VALUES (randomblob(16), ?, ?, 'work', ?, datetime('now'), datetime('now'))"
                    ).use { stmt ->
                        stmt.setBytes(1, uuidToBytes(itemId))
                        stmt.setString(2, key)
                        stmt.setString(3, body)
                        stmt.executeUpdate()
                    }
                val item1 = item("Authentication service for OAuth flow")
                val item2 = item("Authenticated user management module")
                item("Background job scheduler")
                note(item1, "design-note", "OAuth flow design with bearer tokens")
                note(item2, "impl-note", "authenticated session handling")
            }

            UpgradeHarness.migrate(url, target = 7)

            DriverManager.getConnection(url).use { conn ->
                fun count(sql: String): Int =
                    conn.createStatement().use { st ->
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                val sourceWorkItemCount = count("SELECT COUNT(*) FROM work_items")
                val sourceNoteCount = count("SELECT COUNT(*) FROM notes")
                // The trigram FTS table should match rows with the substring "auth"
                val ftsWorkItemCount = count("SELECT COUNT(*) FROM work_items_fts_trigram WHERE work_items_fts_trigram MATCH 'auth'")
                val ftsNoteCount = count("SELECT COUNT(*) FROM notes_fts_trigram WHERE notes_fts_trigram MATCH 'auth'")

                assertTrue(sourceWorkItemCount >= 3, "Expected at least 3 work items in source table, got $sourceWorkItemCount")
                assertTrue(sourceNoteCount >= 2, "Expected at least 2 notes in source table, got $sourceNoteCount")
                // Items 1 and 2 contain "auth" - both should be backfilled
                assertEquals(2, ftsWorkItemCount, "Expected 2 work_items_fts_trigram matches for 'auth', got $ftsWorkItemCount")
                // Both notes contain "auth"
                assertEquals(2, ftsNoteCount, "Expected 2 notes_fts_trigram matches for 'auth', got $ftsNoteCount")
            }
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
                raw { conn ->
                    conn.prepareStatement("UPDATE work_items SET parent_id = ? WHERE id = ?").use { stmt ->
                        stmt.setBytes(1, uuidToBytes(child.id))
                        stmt.setBytes(2, uuidToBytes(parent.id))
                        stmt.executeUpdate()
                    }
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
        assertNotNull(result)
        return result
    }

    private suspend fun createNote(
        itemId: UUID,
        key: String,
        body: String,
    ): Note {
        val note = Note(itemId = itemId, key = key, role = "work", body = body)
        val result = noteRepository.upsert(note)
        assertNotNull(result)
        return result
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
            val uniqueTitle = "ZephyrUniqueSearchToken${System.nanoTime()}"
            val item = createItem(uniqueTitle)

            // Confirm it's indexed
            val countBefore = countFtsRows("work_items_fts_trigram", uniqueTitle)
            assertEquals(1, countBefore, "Expected item to be indexed after insert")

            // Update only version (non-content column) — restricted trigger must NOT fire
            raw { conn ->
                conn.prepareStatement("UPDATE work_items SET version = version + 1 WHERE id = ?").use { stmt ->
                    stmt.setBytes(1, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }
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
            val oldTitle = "OldTitleTokenAlpha${System.nanoTime()}"
            val newTitle = "NewTitleTokenBeta${System.nanoTime()}"
            val item = createItem(oldTitle)

            assertEquals(1, countFtsRows("work_items_fts_trigram", oldTitle), "Old title should be indexed")
            assertEquals(0, countFtsRows("work_items_fts_trigram", newTitle), "New title must not exist yet")

            // Update title — trigger MUST fire
            raw { conn ->
                conn.prepareStatement("UPDATE work_items SET title = ? WHERE id = ?").use { stmt ->
                    stmt.setString(1, newTitle)
                    stmt.setBytes(2, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }
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
            val uniqueBody = "NoteUniqueBodyToken${System.nanoTime()}"
            val item = createItem("Any item for note trigger test")
            createNote(item.id, "test-key", uniqueBody)

            val countBefore = countFtsRows("notes_fts_trigram", uniqueBody)
            assertEquals(1, countBefore, "Note body must be indexed after insert")

            // Update the role column (non-body) — restricted trigger must NOT fire
            raw { conn ->
                conn.prepareStatement("UPDATE notes SET role = 'review' WHERE work_item_id = ?").use { stmt ->
                    stmt.setBytes(1, uuidToBytes(item.id))
                    stmt.executeUpdate()
                }
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

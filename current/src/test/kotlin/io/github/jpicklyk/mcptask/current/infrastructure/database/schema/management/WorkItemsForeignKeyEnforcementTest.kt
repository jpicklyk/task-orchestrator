package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S7/S8 — FK enforcement on the production-opened SQLite fixture (`PRAGMA foreign_keys=ON` comes from
 * `DatabaseManager`). Uses the fixture's own `database` directly — no second database needed for this pair.
 */
class WorkItemsForeignKeyEnforcementTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val database get() = db.database

    private fun countNotesWithKey(key: String): Int {
        var count = 0
        transaction(db = database) {
            val escaped = key.replace("'", "''")
            val result: Int? =
                exec("SELECT COUNT(*) AS c FROM notes WHERE key = '$escaped'") { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            count = result ?: 0
        }
        return count
    }

    @Test
    fun `S7 dangling parent insert now throws once foreign_keys is ON`() {
        val failure =
            assertThrows(Exception::class.java) {
                transaction(db = database) {
                    exec(
                        """
                        INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at)
                        VALUES (randomblob(16), randomblob(16), 'dangling-note', 'queue', 'body', datetime('now'), datetime('now'))
                        """.trimIndent(),
                    )
                }
            }
        val message = (failure.message ?: "") + (failure.cause?.message ?: "")
        assertTrue(
            message.contains("FOREIGN KEY", ignoreCase = true),
            "expected a FOREIGN KEY constraint violation once PRAGMA foreign_keys=ON is enabled on " +
                "this base, got: $message",
        )
    }

    @Test
    fun `S8 deleting a work item cascades to its notes`() {
        transaction(db = database) {
            exec(
                """
                INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at)
                VALUES (randomblob(16), 'fk-cascade-parent', datetime('now'), datetime('now'), datetime('now'))
                """.trimIndent(),
            )
            exec(
                """
                INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at)
                SELECT randomblob(16), id, 'fk-cascade-test-note', 'queue', 'body', datetime('now'), datetime('now')
                FROM work_items WHERE title = 'fk-cascade-parent'
                """.trimIndent(),
            )
        }
        assertEquals(
            1,
            countNotesWithKey("fk-cascade-test-note"),
            "fixture setup: expected exactly one note before the parent work_item is deleted",
        )

        transaction(db = database) {
            exec("DELETE FROM work_items WHERE title = 'fk-cascade-parent'")
        }

        assertEquals(
            0,
            countNotesWithKey("fk-cascade-test-note"),
            "deleting the parent work_item should cascade-delete its notes " +
                "(V1__Current_Initial_Schema.sql ON DELETE CASCADE) now that foreign_keys is ON",
        )
    }
}

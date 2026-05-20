package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.RoleTransitionsTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Development mode schema manager that creates tables directly via Exposed ORM.
 * Tables are created in foreign-key dependency order.
 *
 * Table dependency graph:
 * 1. WorkItemsTable (no external dependencies, self-referencing parentId)
 * 2. NotesTable (depends on WorkItemsTable)
 * 3. DependenciesTable (depends on WorkItemsTable)
 * 4. RoleTransitionsTable (depends on WorkItemsTable)
 *
 * Virtual tables (FTS5, follow their backing tables):
 * 5. work_items_fts_trigram (external-content over WorkItemsTable)
 * 6. work_items_fts_text    (external-content over WorkItemsTable)
 * 7. notes_fts_trigram      (external-content over NotesTable)
 * 8. notes_fts_text         (external-content over NotesTable)
 *
 * Triggers (registered after their backing tables):
 * - work_items_cycle_check, work_items_cycle_check_update (cycle detection on parent_id)
 * - work_items_fts_trigram_ai/ad/au, work_items_fts_text_ai/ad/au (FTS sync for work_items)
 * - notes_fts_trigram_ai/ad/au, notes_fts_text_ai/ad/au (FTS sync for notes)
 */
class DirectDatabaseSchemaManager : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(DirectDatabaseSchemaManager::class.java)

    // Tables in foreign-key dependency order
    private val tables =
        arrayOf(
            WorkItemsTable,
            NotesTable,
            DependenciesTable,
            RoleTransitionsTable,
        )

    // FTS5 virtual table DDL statements (external-content mode, SQLite >= 3.45)
    private val ftsVirtualTables =
        listOf(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS work_items_fts_trigram USING fts5(
                title, summary,
                content='work_items', content_rowid='rowid',
                tokenize='trigram case_sensitive=0',
                prefix='2 3'
            )
            """.trimIndent(),
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS work_items_fts_text USING fts5(
                title, summary,
                content='work_items', content_rowid='rowid',
                tokenize='porter unicode61 remove_diacritics 2',
                prefix='2 3'
            )
            """.trimIndent(),
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts_trigram USING fts5(
                body,
                content='notes', content_rowid='rowid',
                tokenize='trigram case_sensitive=0',
                prefix='2 3'
            )
            """.trimIndent(),
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts_text USING fts5(
                body,
                content='notes', content_rowid='rowid',
                tokenize='porter unicode61 remove_diacritics 2',
                prefix='2 3'
            )
            """.trimIndent(),
        )

    // Cycle-detection and FTS sync triggers
    private val triggers =
        listOf(
            // Cycle detection — BEFORE INSERT
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
            """.trimIndent(),
            // Cycle detection — BEFORE UPDATE OF parent_id
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
            """.trimIndent(),
            // FTS sync: work_items_fts_trigram
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END",
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END",
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_au AFTER UPDATE ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END",
            // FTS sync: work_items_fts_text
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END",
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END",
            "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_au AFTER UPDATE ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END",
            // FTS sync: notes_fts_trigram
            "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END",
            "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); END",
            "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END",
            // FTS sync: notes_fts_text
            "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END",
            "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); END",
            "CREATE TRIGGER IF NOT EXISTS notes_fts_text_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END",
        )

    override fun updateSchema(): Boolean =
        try {
            logger.info("Creating/updating database schema via Direct mode...")

            transaction {
                SchemaUtils.create(*tables)
                ftsVirtualTables.forEach { exec(it) }
                triggers.forEach { exec(it) }
            }

            logger.info("Database schema created/updated successfully via Direct mode")
            true
        } catch (e: Exception) {
            logger.error("Failed to create/update schema: ${e.message}", e)
            false
        }
}

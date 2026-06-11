package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DDL drift guard: asserts that DirectDatabaseSchemaManager's FTS update trigger SQL is
 * consistent with the canonical V8 Flyway migration SQL.
 *
 * Background: DirectDatabaseSchemaManager hand-maintains the same FTS DDL as Kotlin strings
 * for dev/direct mode. The Flyway migrations are the authoritative source for production.
 * Without a sync guard, the two can drift silently — a fix applied to the migration file
 * may be missed in DirectDatabaseSchemaManager (or vice versa), causing dev and prod to
 * behave differently.
 *
 * This test normalises whitespace and compares the _au (UPDATE) trigger SQL from
 * DirectDatabaseSchemaManager against the expected V8 canonical forms. If they diverge,
 * this test fails CI, preventing silent drift.
 *
 * Only _au triggers are checked here because they are the ones changed by V8 (column
 * restriction). The _ai and _ad triggers are stable; they are not expected to change.
 */
class DirectDatabaseSchemaManagerDriftTest {

    /** Canonical V8 trigger SQL (matches V8__Restrict_FTS_Update_Triggers.sql exactly). */
    private val canonicalWorkItemsTrigramAu =
        "CREATE TRIGGER work_items_fts_trigram_au AFTER UPDATE OF title, summary ON work_items " +
            "BEGIN " +
            "INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) " +
            "VALUES ('delete', old.rowid, old.title, old.summary); " +
            "INSERT INTO work_items_fts_trigram(rowid, title, summary) " +
            "VALUES (new.rowid, new.title, new.summary); " +
            "END"

    private val canonicalWorkItemsTextAu =
        "CREATE TRIGGER work_items_fts_text_au AFTER UPDATE OF title, summary ON work_items " +
            "BEGIN " +
            "INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) " +
            "VALUES ('delete', old.rowid, old.title, old.summary); " +
            "INSERT INTO work_items_fts_text(rowid, title, summary) " +
            "VALUES (new.rowid, new.title, new.summary); " +
            "END"

    private val canonicalNotesTrigramAu =
        "CREATE TRIGGER notes_fts_trigram_au AFTER UPDATE OF body ON notes " +
            "BEGIN " +
            "INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) " +
            "VALUES ('delete', old.rowid, old.body); " +
            "INSERT INTO notes_fts_trigram(rowid, body) " +
            "VALUES (new.rowid, new.body); " +
            "END"

    private val canonicalNotesTextAu =
        "CREATE TRIGGER notes_fts_text_au AFTER UPDATE OF body ON notes " +
            "BEGIN " +
            "INSERT INTO notes_fts_text(notes_fts_text, rowid, body) " +
            "VALUES ('delete', old.rowid, old.body); " +
            "INSERT INTO notes_fts_text(rowid, body) " +
            "VALUES (new.rowid, new.body); " +
            "END"

    /** Normalise whitespace: collapse all runs of whitespace to a single space and trim. */
    private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    /**
     * Reflectively read the private `triggers` list from DirectDatabaseSchemaManager
     * and extract only the CREATE TRIGGER statements for the four _au triggers.
     * DROP TRIGGER IF EXISTS entries are intentionally skipped — we only compare CREATE bodies.
     */
    private fun extractCreateTriggerSql(manager: DirectDatabaseSchemaManager): Map<String, String> {
        val field = manager::class.java.getDeclaredField("triggers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val triggers = field.get(manager) as List<String>

        val createPattern = Regex("CREATE TRIGGER\\s+(\\S+)", RegexOption.IGNORE_CASE)
        return triggers
            .filter { it.trimStart().startsWith("CREATE TRIGGER", ignoreCase = true) }
            .associateBy { sql ->
                createPattern.find(sql)?.groupValues?.get(1)
                    ?: error("Could not parse trigger name from: $sql")
            }
    }

    @Test
    fun `ftsTriggerDdlMatchesMigrationSql work_items_fts_trigram_au`() {
        val manager = DirectDatabaseSchemaManager()
        val triggers = extractCreateTriggerSql(manager)

        assertTrue(
            "work_items_fts_trigram_au" in triggers,
            "DirectDatabaseSchemaManager must contain a CREATE TRIGGER for work_items_fts_trigram_au"
        )

        assertEquals(
            normalize(canonicalWorkItemsTrigramAu),
            normalize(triggers.getValue("work_items_fts_trigram_au")),
            "work_items_fts_trigram_au DDL in DirectDatabaseSchemaManager diverges from V8 migration canonical form"
        )
    }

    @Test
    fun `ftsTriggerDdlMatchesMigrationSql work_items_fts_text_au`() {
        val manager = DirectDatabaseSchemaManager()
        val triggers = extractCreateTriggerSql(manager)

        assertTrue(
            "work_items_fts_text_au" in triggers,
            "DirectDatabaseSchemaManager must contain a CREATE TRIGGER for work_items_fts_text_au"
        )

        assertEquals(
            normalize(canonicalWorkItemsTextAu),
            normalize(triggers.getValue("work_items_fts_text_au")),
            "work_items_fts_text_au DDL in DirectDatabaseSchemaManager diverges from V8 migration canonical form"
        )
    }

    @Test
    fun `ftsTriggerDdlMatchesMigrationSql notes_fts_trigram_au`() {
        val manager = DirectDatabaseSchemaManager()
        val triggers = extractCreateTriggerSql(manager)

        assertTrue(
            "notes_fts_trigram_au" in triggers,
            "DirectDatabaseSchemaManager must contain a CREATE TRIGGER for notes_fts_trigram_au"
        )

        assertEquals(
            normalize(canonicalNotesTrigramAu),
            normalize(triggers.getValue("notes_fts_trigram_au")),
            "notes_fts_trigram_au DDL in DirectDatabaseSchemaManager diverges from V8 migration canonical form"
        )
    }

    @Test
    fun `ftsTriggerDdlMatchesMigrationSql notes_fts_text_au`() {
        val manager = DirectDatabaseSchemaManager()
        val triggers = extractCreateTriggerSql(manager)

        assertTrue(
            "notes_fts_text_au" in triggers,
            "DirectDatabaseSchemaManager must contain a CREATE TRIGGER for notes_fts_text_au"
        )

        assertEquals(
            normalize(canonicalNotesTextAu),
            normalize(triggers.getValue("notes_fts_text_au")),
            "notes_fts_text_au DDL in DirectDatabaseSchemaManager diverges from V8 migration canonical form"
        )
    }

    @Test
    fun `ftsUpdateTriggersAreRestrictedToContentColumns`() {
        val manager = DirectDatabaseSchemaManager()
        val triggers = extractCreateTriggerSql(manager)

        val auTriggers = listOf(
            "work_items_fts_trigram_au",
            "work_items_fts_text_au",
            "notes_fts_trigram_au",
            "notes_fts_text_au",
        )

        for (triggerName in auTriggers) {
            assertTrue(
                triggerName in triggers,
                "DirectDatabaseSchemaManager must contain CREATE TRIGGER for $triggerName"
            )
            val sql = normalize(triggers.getValue(triggerName))
            assertTrue(
                "AFTER UPDATE ON " !in sql.uppercase(),
                "$triggerName must not use unrestricted AFTER UPDATE ON — it must have an OF column list. Got: $sql"
            )
            assertTrue(
                "AFTER UPDATE OF" in sql.uppercase(),
                "$triggerName must use AFTER UPDATE OF <columns> to restrict firing. Got: $sql"
            )
        }
    }
}

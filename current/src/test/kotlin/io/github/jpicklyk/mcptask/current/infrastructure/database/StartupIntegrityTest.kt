package io.github.jpicklyk.mcptask.current.infrastructure.database

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.exec
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.insertDependency
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.insertItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.insertNote
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.migrated
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.objectNames
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaTestSupport.withConn
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Startup integrity (AR-48 rec 1 and the plan v4-phase1-core 3.10 reports). The expected inventory
 * is derived from the migration SQL (V7 creates the four FTS5 tables, twelve sync triggers and two
 * cycle triggers; V8 only replaces the four `_au` triggers), never from production Kotlin.
 */
class StartupIntegrityTest {
    @TempDir
    lateinit var dir: Path

    private val expectedFtsTables =
        setOf("work_items_fts_trigram", "work_items_fts_text", "notes_fts_trigram", "notes_fts_text")
    private val expectedFtsTriggers =
        expectedFtsTables.flatMap { t -> listOf("ai", "ad", "au").map { "${t}_$it" } }.toSet()
    private val expectedCycleTriggers = setOf("work_items_cycle_check", "work_items_cycle_check_update")

    private fun <T> onConn(
        url: String,
        block: (Connection) -> T
    ): T = withConn(url, block)

    private fun idPrefix(id: UUID) = id.toString().substring(0, 8)

    private fun warnsOf(block: () -> Unit): List<String> = captureLogs(block).at(Level.WARN)

    // ---- inventory constants ----

    @Test
    fun `inventory constants match the objects the migrations create`() {
        assertEquals(expectedFtsTables, StartupIntegrity.FTS_TABLES.toSet())
        assertEquals(expectedFtsTriggers, StartupIntegrity.FTS_TRIGGERS.toSet())
        assertEquals(expectedCycleTriggers, StartupIntegrity.CYCLE_TRIGGERS.toSet())
        assertEquals(4, StartupIntegrity.FTS_TABLES.size, "no duplicates")
        assertEquals(12, StartupIntegrity.FTS_TRIGGERS.size, "no duplicates")
        assertEquals(2, StartupIntegrity.CYCLE_TRIGGERS.size, "no duplicates")
    }

    @Test
    fun `every inventory object really exists in a freshly migrated database`() {
        val url = migrated(dir)
        assertTrue(objectNames(url, "table").containsAll(expectedFtsTables))
        assertTrue(objectNames(url, "trigger").containsAll(expectedFtsTriggers + expectedCycleTriggers))
        onConn(url) { StartupIntegrity.verifyInventory(it) }
    }

    // ---- verifyInventory: hard failure, names every missing object, creates nothing ----

    @Test
    fun `verifyInventory names every missing trigger and recreates nothing`() {
        val url = migrated(dir)
        exec(url, "DROP TRIGGER work_items_cycle_check", "DROP TRIGGER notes_fts_trigram_ad")
        val ex = assertFailsWith<StartupIntegrityException> { onConn(url) { StartupIntegrity.verifyInventory(it) } }
        assertTrue(ex.message!!.contains("work_items_cycle_check"), ex.message)
        assertTrue(ex.message!!.contains("notes_fts_trigram_ad"), ex.message)
        val triggers = objectNames(url, "trigger")
        assertFalse("work_items_cycle_check" in triggers)
        assertFalse("notes_fts_trigram_ad" in triggers)
    }

    @Test
    fun `verifyInventory names a missing FTS virtual table`() {
        val url = migrated(dir)
        exec(url, "DROP TABLE work_items_fts_text")
        val ex = assertFailsWith<StartupIntegrityException> { onConn(url) { StartupIntegrity.verifyInventory(it) } }
        assertTrue(ex.message!!.contains("work_items_fts_text"), ex.message)
    }

    @Test
    fun `run throws StartupIntegrityException when inventory is incomplete`() {
        val url = migrated(dir)
        exec(url, "DROP TRIGGER notes_fts_text_au")
        val ex = assertFailsWith<StartupIntegrityException> { onConn(url) { StartupIntegrity.run(it) } }
        assertTrue(ex.message!!.contains("notes_fts_text_au"), ex.message)
    }

    @Test
    fun `run succeeds on a freshly migrated empty database and on a one-row database`() {
        val url = migrated(dir)
        onConn(url) { StartupIntegrity.run(it) }
        val a = UUID.randomUUID()
        insertItem(url, a, title = "solo")
        insertNote(url, a, "k", "single row body")
        onConn(url) { StartupIntegrity.run(it) }
    }

    // ---- S7: desynced external-content FTS index is rebuilt (AR-48 rec 1) ----

    private fun desyncedNotesDb(): String {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        insertItem(url, a, title = "holder")
        insertNote(url, a, "k", "zebra crossing body")
        assertEquals(1, ftsMatches(url, "zebra"), "fixture: index must find the note before the desync")
        exec(url, "INSERT INTO notes_fts_text(notes_fts_text) VALUES('delete-all')")
        return url
    }

    private fun ftsMatches(
        url: String,
        term: String
    ): Int = scalarInt(url, "SELECT count(*) FROM notes_fts_text WHERE notes_fts_text MATCH '$term'")

    @Test
    fun `S7 fixture is genuinely desynced - default integrity-check passes but rank 1 fails`() {
        // Vacuity guard: if the default (rank-less) check also failed, a rank-less implementation
        // would still pass S7; this proves the fixture needs the rank=1 form to be detected.
        val url = desyncedNotesDb()
        assertEquals(0, ftsMatches(url, "zebra"), "fixture: the delete-all must have emptied the index")
        exec(url, "INSERT INTO notes_fts_text(notes_fts_text) VALUES('integrity-check')")
        assertFailsWith<java.sql.SQLException> {
            exec(url, "INSERT INTO notes_fts_text(notes_fts_text, rank) VALUES('integrity-check', 1)")
        }
    }

    @Test
    fun `S7 checkFts rebuilds a desynced FTS index so MATCH finds the note and rank 1 integrity-check passes`() {
        val url = desyncedNotesDb()
        onConn(url) { StartupIntegrity.checkFts(it) }
        assertEquals(1, ftsMatches(url, "zebra"), "the rebuilt index must find the note")
        exec(url, "INSERT INTO notes_fts_text(notes_fts_text, rank) VALUES('integrity-check', 1)")
    }

    @Test
    fun `S7 restart through the schema manager repairs a desynced FTS index`() {
        val url = desyncedNotesDb()
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertEquals(1, ftsMatches(url, "zebra"))
        exec(url, "INSERT INTO notes_fts_text(notes_fts_text, rank) VALUES('integrity-check', 1)")
    }

    @Test
    fun `checkFts leaves a healthy database untouched`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        insertItem(url, a, title = "healthy")
        insertNote(url, a, "k", "zebra healthy")
        onConn(url) { StartupIntegrity.checkFts(it) }
        assertEquals(1, ftsMatches(url, "zebra"))
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM notes"))
    }

    // ---- S16: WARN-only reports (placement drift, mutual blocks) ----

    @Test
    fun `S16 reportPlacementDrift warns with a count and the id sample and never writes`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val child = UUID.randomUUID()
        insertItem(url, root, depth = 0, rootId = root, title = "root")
        // depth drift: parent depth 0 so the child should be depth 1, stored as 5
        insertItem(url, child, parent = root, depth = 5, rootId = root, title = "child")

        val warns = warnsOf { onConn(url) { StartupIntegrity.reportPlacementDrift(it) } }

        assertTrue(warns.isNotEmpty(), "drift must be reported as a WARN")
        assertTrue(warns.any { it.lowercase().contains(idPrefix(child)) }, "WARN must sample the drifted id: $warns")
        assertTrue(warns.any { Regex("\\b1\\b").containsMatchIn(it) }, "WARN must carry the count 1: $warns")
        assertEquals(5, scalarInt(url, "SELECT depth FROM work_items WHERE title = 'child'"), "rows must never be modified")
        assertEquals(2, scalarInt(url, "SELECT count(*) FROM work_items"))
    }

    @Test
    fun `S16 reportPlacementDrift reports a root_id that differs from the parent root_id and an orphan`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val other = UUID.randomUUID()
        val badRoot = UUID.randomUUID()
        val orphan = UUID.randomUUID()
        insertItem(url, root, rootId = root, title = "root")
        insertItem(url, other, rootId = other, title = "other")
        insertItem(url, badRoot, parent = root, depth = 1, rootId = other, title = "wrong-root")
        // orphan: parent id references no item (foreign keys are off on this raw connection)
        insertItem(url, orphan, parent = UUID.randomUUID(), depth = 1, rootId = root, title = "orphan")

        val warns = warnsOf { onConn(url) { StartupIntegrity.reportPlacementDrift(it) } }.joinToString("\n").lowercase()

        assertTrue(warns.contains(idPrefix(badRoot)), "root_id mismatch must be reported: $warns")
        assertTrue(warns.contains(idPrefix(orphan)), "orphan must be reported: $warns")
        assertEquals(4, scalarInt(url, "SELECT count(*) FROM work_items"))
    }

    @Test
    fun `S16 reportPlacementDrift reports a depth-0 item whose root_id is not its own id`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        insertItem(url, a, depth = 0, rootId = UUID.randomUUID(), title = "bad-root")
        val warns = warnsOf { onConn(url) { StartupIntegrity.reportPlacementDrift(it) } }
        assertTrue(warns.any { it.lowercase().contains(idPrefix(a)) }, "WARN must sample the id: $warns")
    }

    @Test
    fun `S16 reportPlacementDrift is silent on a consistent hierarchy`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val child = UUID.randomUUID()
        insertItem(url, root, depth = 0, rootId = root, title = "root")
        insertItem(url, child, parent = root, depth = 1, rootId = root, title = "child")
        val warns = warnsOf { onConn(url) { StartupIntegrity.reportPlacementDrift(it) } }
        assertEquals(emptyList(), warns, "a consistent hierarchy must produce no WARN")
    }

    @Test
    fun `S16 reportMutualBlocks warns with the count and both ids for BLOCKS A to B and B to A`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertItem(url, a, title = "A")
        insertItem(url, b, title = "B")
        insertDependency(url, a, b, "BLOCKS")
        insertDependency(url, b, a, "BLOCKS")

        val warns = warnsOf { onConn(url) { StartupIntegrity.reportMutualBlocks(it) } }

        val joined = warns.joinToString("\n").lowercase()
        assertTrue(warns.isNotEmpty(), "a mutual block must be reported")
        assertTrue(joined.contains(idPrefix(a)) && joined.contains(idPrefix(b)), "WARN must name both ids: $warns")
        assertTrue(Regex("\\b1\\b").containsMatchIn(joined), "WARN must carry the count of 1 pair: $warns")
        assertEquals(2, scalarInt(url, "SELECT count(*) FROM dependencies"), "rows must never be modified")
    }

    @Test
    fun `S16 reportMutualBlocks normalises IS_BLOCKED_BY so A BLOCKS B plus A IS_BLOCKED_BY B is mutual`() {
        // (A, B, IS_BLOCKED_BY) means B blocks A; together with (A, B, BLOCKS) the pair blocks each other.
        val url = migrated(dir)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertItem(url, a, title = "A")
        insertItem(url, b, title = "B")
        insertDependency(url, a, b, "BLOCKS")
        insertDependency(url, a, b, "IS_BLOCKED_BY")
        val warns = warnsOf { onConn(url) { StartupIntegrity.reportMutualBlocks(it) } }
        assertTrue(warns.isNotEmpty(), "normalised mutual block must be reported")
    }

    @Test
    fun `S16 reportMutualBlocks is silent for a one-way chain and for redundant same-direction edges`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        insertItem(url, a, title = "A")
        insertItem(url, b, title = "B")
        insertItem(url, c, title = "C")
        insertDependency(url, a, b, "BLOCKS")
        insertDependency(url, b, c, "BLOCKS")
        // (B, A, IS_BLOCKED_BY) also means A blocks B: the same direction, not a cycle.
        insertDependency(url, b, a, "IS_BLOCKED_BY")
        insertDependency(url, a, c, "RELATES_TO")
        val warns = warnsOf { onConn(url) { StartupIntegrity.reportMutualBlocks(it) } }
        assertEquals(emptyList(), warns, "no mutual block exists, so no WARN")
    }

    @Test
    fun `S16 run on a drifted but complete database does not throw and leaves every row`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertItem(url, a, depth = 3, rootId = UUID.randomUUID(), title = "A")
        insertItem(url, b, title = "B")
        insertDependency(url, a, b, "BLOCKS")
        insertDependency(url, b, a, "BLOCKS")
        onConn(url) { StartupIntegrity.run(it) }
        assertEquals(2, scalarInt(url, "SELECT count(*) FROM work_items"))
        assertEquals(2, scalarInt(url, "SELECT count(*) FROM dependencies"))
        assertEquals(3, scalarInt(url, "SELECT depth FROM work_items WHERE title = 'A'"))
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrityException
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.exec
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.insertItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.insertNote
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.migrated
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.objectNames
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.uuidBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `FTS_INTEGRITY=startup|off` (task-scope F, D10): [FtsIntegrityMode.parse], its single read site [AppConfig.fromEnv], and
 * what the mode does to [StartupIntegrity] (test-plan S16, S11, S21), plus the six-table FTS inventory and the WARN-only
 * dangling-link report.
 *
 * Oracles: task-scope F ("`OFF` skips `checkFts` only and logs one WARN -- `verifyInventory` always runs"; "unknown value
 * -> default + WARN, the `EnvBoolean.parse` convention": unset is silent, any other unrecognized value including the empty
 * string warns once, per EnvBooleanTest S5/S8; values are trimmed and case-insensitive per that convention). The inventory
 * is derived from the DDL in the `migration-assessment` note (six FTS tables, three sync triggers each).
 */
class FtsIntegrityModeTest {
    @TempDir
    lateinit var dir: Path

    private fun warnsOf(block: () -> Unit): List<String> = captureLogs(block).at(Level.WARN)

    // ---------------------------------------------------------------- FtsIntegrityMode.parse

    @Test
    fun `parse recognizes startup and off case-insensitively and trimmed`() {
        for (raw in listOf("startup", "STARTUP", "Startup", " startup ", "startup\t")) {
            assertEquals(FtsIntegrityMode.STARTUP, FtsIntegrityMode.parse(raw), "'$raw'")
        }
        for (raw in listOf("off", "OFF", "Off", " off ", "\toff\n")) {
            assertEquals(FtsIntegrityMode.OFF, FtsIntegrityMode.parse(raw), "'$raw'")
        }
    }

    @Test
    fun `parse of an unset value is the default and stays silent`() {
        var parsed: FtsIntegrityMode? = null
        val warns = warnsOf { parsed = FtsIntegrityMode.parse(null) }
        assertEquals(FtsIntegrityMode.STARTUP, parsed)
        assertEquals(emptyList(), warns, "an unset variable must never warn")
    }

    @Test
    fun `parse of an unrecognized value falls back to startup and warns once naming the value`() {
        var parsed: FtsIntegrityMode? = null
        val warns = warnsOf { parsed = FtsIntegrityMode.parse("bogus") }
        assertEquals(FtsIntegrityMode.STARTUP, parsed)
        assertEquals(1, warns.size, "exactly one WARN per unrecognized read: $warns")
        assertTrue("bogus" in warns.single(), "the WARN must name the raw value: $warns")
    }

    @Test
    fun `parse treats the boolean spellings and the empty string as unrecognized - startup plus a WARN each`() {
        for (raw in listOf("", "false", "0", "no", "on", "true", "none", "disabled", "startup,off", "of f")) {
            var parsed: FtsIntegrityMode? = null
            val warns = warnsOf { parsed = FtsIntegrityMode.parse(raw) }
            assertEquals(FtsIntegrityMode.STARTUP, parsed, "'$raw' must not silently disable the check")
            assertEquals(1, warns.size, "'$raw' must warn once: $warns")
        }
    }

    // ---------------------------------------------------------------- AppConfig.fromEnv

    @Test
    fun `fromEnv defaults ftsIntegrity to startup and reads FTS_INTEGRITY off`() {
        assertEquals(FtsIntegrityMode.STARTUP, AppConfig.fromEnv { null }.ftsIntegrity)
        assertEquals(FtsIntegrityMode.STARTUP, AppConfig.fromEnv { if (it == "FTS_INTEGRITY") "startup" else null }.ftsIntegrity)
        assertEquals(FtsIntegrityMode.OFF, AppConfig.fromEnv { if (it == "FTS_INTEGRITY") "off" else null }.ftsIntegrity)
        assertEquals(FtsIntegrityMode.OFF, AppConfig.fromEnv { if (it == "FTS_INTEGRITY") " OFF " else null }.ftsIntegrity)
    }

    @Test
    fun `fromEnv with an unknown FTS_INTEGRITY value falls back to startup and warns once`() {
        var config: AppConfig? = null
        val warns = warnsOf { config = AppConfig.fromEnv { if (it == "FTS_INTEGRITY") "sometimes" else null } }
        assertEquals(FtsIntegrityMode.STARTUP, config!!.ftsIntegrity)
        assertEquals(1, warns.count { "sometimes" in it }, "one WARN must name the bad value: $warns")
    }

    @Test
    fun `fromEnv with FTS_INTEGRITY set changes no other setting`() {
        val base = AppConfig.fromEnv { null }
        val off = AppConfig.fromEnv { if (it == "FTS_INTEGRITY") "off" else null }
        assertEquals(base.databasePath, off.databasePath)
        assertEquals(base.mcpTransport, off.mcpTransport)
        assertEquals(base.databaseBusyTimeoutMs, off.databaseBusyTimeoutMs)
        assertEquals(base.flywayRepair, off.flywayRepair)
    }

    // ---------------------------------------------------------------- fixtures

    private fun ftsMatches(
        url: String,
        table: String,
        term: String
    ): Int = scalarInt(url, "SELECT count(*) FROM $table WHERE $table MATCH '$term'")

    /** A migrated database whose notes_fts_text index is emptied behind the triggers' back (a genuine desync). */
    private fun desyncedNotesDb(): String {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        insertItem(url, a, title = "holder")
        insertNote(url, a, "k", "zebra crossing body")
        assertEquals(1, ftsMatches(url, "notes_fts_text", "zebra"), "fixture: the index finds the note before the desync")
        exec(url, "INSERT INTO notes_fts_text(notes_fts_text) VALUES('delete-all')")
        assertEquals(0, ftsMatches(url, "notes_fts_text", "zebra"), "fixture: the delete-all really desynced the index")
        return url
    }

    private fun insertDocument(
        url: String,
        id: UUID,
        root: UUID,
        slug: String,
        body: String
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO documents (id, root_item_id, kind, slug, title, body, content_hash, version, created_at, modified_at) " +
                    "VALUES (?, ?, 'plan', ?, NULL, ?, 'h', 1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(id))
                ps.setBytes(2, uuidBytes(root))
                ps.setString(3, slug)
                ps.setString(4, body)
                ps.executeUpdate()
            }
    }

    private fun insertNoteWithId(
        url: String,
        id: UUID,
        itemId: UUID
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) " +
                    "VALUES (?, ?, 'k', 'queue', 'body', '2026-01-01 00:00:00', '2026-01-01 00:00:00')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(id))
                ps.setBytes(2, uuidBytes(itemId))
                ps.executeUpdate()
            }
    }

    private fun insertLink(
        url: String,
        root: UUID,
        fromKind: String,
        fromId: UUID,
        toKind: String,
        toId: UUID,
        relation: String = "references"
    ) = withConn(url) { c ->
        c
            .prepareStatement(
                "INSERT INTO links (id, root_item_id, from_kind, from_id, to_kind, to_id, relation, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, '2026-01-01 00:00:00.000')"
            ).use { ps ->
                ps.setBytes(1, uuidBytes(UUID.randomUUID()))
                ps.setBytes(2, uuidBytes(root))
                ps.setString(3, fromKind)
                ps.setBytes(4, uuidBytes(fromId))
                ps.setString(5, toKind)
                ps.setBytes(6, uuidBytes(toId))
                ps.setString(7, relation)
                ps.executeUpdate()
            }
    }

    // ---------------------------------------------------------------- S16 FTS_INTEGRITY=off

    @Test
    fun `S16 off skips the FTS check - a desynced index stays desynced and exactly one WARN is logged`() {
        val url = desyncedNotesDb()
        val warns = warnsOf { withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.OFF) } }
        assertEquals(0, ftsMatches(url, "notes_fts_text", "zebra"), "off must not rebuild the index")
        assertEquals(1, warns.size, "off logs exactly one WARN: $warns")
        assertTrue("fts" in warns.single().lowercase(), "the WARN must say what was skipped: $warns")
    }

    @Test
    fun `S16 startup mode rebuilds the same desynced index`() {
        val url = desyncedNotesDb()
        withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.STARTUP) }
        assertEquals(1, ftsMatches(url, "notes_fts_text", "zebra"), "startup repairs the index")
    }

    @Test
    fun `S16 startup mode on a healthy database logs no WARN`() {
        val url = migrated(dir)
        val warns = warnsOf { withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.STARTUP) } }
        assertEquals(emptyList(), warns)
    }

    @Test
    fun `S16 run without a mode argument behaves as startup`() {
        val url = desyncedNotesDb()
        withConn(url) { StartupIntegrity.run(it) }
        assertEquals(1, ftsMatches(url, "notes_fts_text", "zebra"))
    }

    @Test
    fun `S16 off still verifies the inventory - a dropped document trigger aborts startup`() {
        val url = migrated(dir)
        exec(url, "DROP TRIGGER documents_fts_text_au")
        val ex = assertFailsWith<StartupIntegrityException> { withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.OFF) } }
        assertTrue("documents_fts_text_au" in ex.message!!, ex.message)
        assertTrue("documents_fts_text_au" !in objectNames(url, "trigger"), "nothing may be recreated")
    }

    @Test
    fun `S16 off still verifies the inventory - a dropped document FTS table aborts startup`() {
        val url = migrated(dir)
        exec(url, "DROP TABLE documents_fts_trigram")
        val ex = assertFailsWith<StartupIntegrityException> { withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.OFF) } }
        assertTrue("documents_fts_trigram" in ex.message!!, ex.message)
    }

    @Test
    fun `S16 off on a healthy database with documents neither throws nor changes a row`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertDocument(url, UUID.randomUUID(), root, "plan-a", "quagga body")
        withConn(url) { StartupIntegrity.run(it, FtsIntegrityMode.OFF) }
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM documents"))
        assertEquals(1, ftsMatches(url, "documents_fts_text", "quagga"))
    }

    // ---------------------------------------------------------------- S11 six-table inventory

    @Test
    fun `S11 FTS_BACKING names the backing table of every FTS table and covers exactly the inventory`() {
        assertEquals(
            mapOf(
                "work_items_fts_trigram" to "work_items",
                "work_items_fts_text" to "work_items",
                "notes_fts_trigram" to "notes",
                "notes_fts_text" to "notes",
                "documents_fts_trigram" to "documents",
                "documents_fts_text" to "documents"
            ),
            StartupIntegrity.FTS_BACKING
        )
        assertEquals(StartupIntegrity.FTS_TABLES.toSet(), StartupIntegrity.FTS_BACKING.keys)
    }

    @Test
    fun `S11 checkFts covers the document indexes - a desynced document index is rebuilt`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertDocument(url, UUID.randomUUID(), root, "plan-a", "quagga body")
        for (fts in listOf("documents_fts_text", "documents_fts_trigram")) {
            assertEquals(1, ftsMatches(url, fts, "quagga"), "fixture: $fts finds the document")
            exec(url, "INSERT INTO $fts($fts) VALUES('delete-all')")
            assertEquals(0, ftsMatches(url, fts, "quagga"), "fixture: $fts is desynced")
        }

        withConn(url) { StartupIntegrity.checkFts(it) }

        for (fts in listOf("documents_fts_text", "documents_fts_trigram")) {
            assertEquals(1, ftsMatches(url, fts, "quagga"), "$fts must be rebuilt")
            exec(url, "INSERT INTO $fts($fts, rank) VALUES('integrity-check', 1)")
        }
    }

    // ---------------------------------------------------------------- reportDanglingLinks (WARN only)

    @Test
    fun `reportDanglingLinks is silent when every link target exists`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val other = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val note = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertItem(url, other, title = "other")
        insertDocument(url, doc, root, "plan-a", "body")
        insertNoteWithId(url, note, other)
        insertLink(url, root, "document", doc, "item", other, "adopted_by")
        insertLink(url, root, "item", other, "document", doc, "references")
        insertLink(url, root, "document", doc, "note", note, "references")

        val warns = warnsOf { withConn(url) { StartupIntegrity.reportDanglingLinks(it) } }

        assertEquals(emptyList(), warns, "links to existing rows are not dangling")
        assertEquals(3, scalarInt(url, "SELECT count(*) FROM links"))
    }

    @Test
    fun `reportDanglingLinks warns with the count for a target that names no row of its kind and never writes`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val doc = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertDocument(url, doc, root, "plan-a", "body")
        insertLink(url, root, "document", doc, "item", UUID.randomUUID(), "adopted_by")
        insertLink(url, root, "document", doc, "note", UUID.randomUUID(), "references")
        insertLink(url, root, "item", root, "document", UUID.randomUUID(), "references")

        val warns = warnsOf { withConn(url) { StartupIntegrity.reportDanglingLinks(it) } }

        assertTrue(warns.isNotEmpty(), "dangling links must be reported")
        assertTrue(warns.any { Regex("\\b3\\b").containsMatchIn(it) }, "the WARN must carry the count 3: $warns")
        assertEquals(3, scalarInt(url, "SELECT count(*) FROM links"), "rows must never be modified")
    }

    @Test
    fun `reportDanglingLinks reports a link whose target is of another kind than recorded`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val doc = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertDocument(url, doc, root, "plan-a", "body")
        // The id exists, but as a work item, not as a document: the link's to_kind says 'document'.
        insertLink(url, root, "document", doc, "document", root, "references")

        val warns = warnsOf { withConn(url) { StartupIntegrity.reportDanglingLinks(it) } }

        assertTrue(warns.any { Regex("\\b1\\b").containsMatchIn(it) }, "kind mismatch is dangling: $warns")
    }

    @Test
    fun `run on a database with a dangling link does not throw and leaves every row`() {
        val url = migrated(dir)
        val root = UUID.randomUUID()
        val doc = UUID.randomUUID()
        insertItem(url, root, title = "root")
        insertDocument(url, doc, root, "plan-a", "body")
        insertLink(url, root, "document", doc, "item", UUID.randomUUID(), "adopted_by")

        withConn(url) { StartupIntegrity.run(it) }

        assertEquals(1, scalarInt(url, "SELECT count(*) FROM links"))
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM documents"))
    }
}

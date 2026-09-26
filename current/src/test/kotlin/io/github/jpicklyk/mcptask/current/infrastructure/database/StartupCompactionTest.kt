package io.github.jpicklyk.mcptask.current.infrastructure.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item f8a592df: one-time startup compaction after the V17
 * actor-proof scrub (VACUUM + FTS5 rebuild + `wal_checkpoint`), gated by `PRAGMA user_version`.
 *
 * Oracle sources (per the frozen `test-plan` note, item f8a592df):
 *  - [A] the item's task-scope acceptance criteria / decision.md §5.
 *  - [D] the migration-assessment note's SQLite-constraint analysis.
 *  - [V] sqlite.org `lang_vacuum`: VACUUM rebuilds the database into a new file, copying only
 *    live b-tree content — pages already freed (deleted rows, pre-VACUUM UPDATE residue) are not
 *    carried into the new file, and VACUUM may renumber the rowid of any table lacking an
 *    explicit `INTEGER PRIMARY KEY` (both `work_items` and `notes` are BLOB-keyed, so their
 *    rowids may change — this is why every equality check below compares by the `id`/`key`
 *    application column, never by `rowid`).
 *  - [F] sqlite.org fts5: `INSERT INTO t(t) VALUES('rebuild')` repopulates an external-content
 *    FTS5 index from its content table; `'integrity-check'` verifies the index against the
 *    content table and raises an error if they disagree.
 *  - [W] `PRAGMA wal_checkpoint(TRUNCATE)`: copies WAL frames into the main file and truncates
 *    the `-wal` file to zero bytes when successful.
 *
 * All scenarios are NEW-SURFACE per the test-plan (the whole `StartupCompaction` object and
 * `CompactionOutcome` enum are introduced by this item). Per each scenario's narrowest-revert
 * recipe recorded in `test-manifest`, red is obtained by keeping `runOnce`'s enum/constant/table
 * list intact and reverting only the step under test inside its body.
 */
class StartupCompactionTest {
    // ────────────────────────────────────────────────────────────────────────
    // Schema fixture — hand-built, matching the verbatim DDL from the item's declarations
    // (V1 base + V3/V5/V9 work_items ALTERs, V1+V4+V17 notes/role_transitions, V7 FTS5).
    // No existing test helper builds a Flyway DB pinned at an intermediate version (declared
    // NOT DECLARED gap), so per the item's dispatch instructions this schema is built by hand
    // with raw SQL, following the same technique as V17ActorProofScrubMigrationTest.
    // ────────────────────────────────────────────────────────────────────────

    private fun createFullSchema(
        connection: Connection,
        includeFtsTables: Set<String> = StartupCompaction.FTS_TABLES.toSet()
    ) {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE work_items (
                    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    parent_id       BLOB REFERENCES work_items(id),
                    title           TEXT NOT NULL,
                    description     TEXT,
                    summary         TEXT NOT NULL DEFAULT '',
                    role            TEXT NOT NULL DEFAULT 'queue'
                                    CHECK (role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
                    status_label    TEXT,
                    previous_role   TEXT CHECK (previous_role IS NULL OR previous_role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
                    priority        TEXT NOT NULL DEFAULT 'medium'
                                    CHECK (priority IN ('high', 'medium', 'low')),
                    complexity      INTEGER NOT NULL DEFAULT 5,
                    depth           INTEGER NOT NULL DEFAULT 0,
                    metadata        TEXT,
                    tags            TEXT,
                    created_at      TIMESTAMP NOT NULL,
                    modified_at     TIMESTAMP NOT NULL,
                    role_changed_at TIMESTAMP NOT NULL,
                    version         INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            stmt.execute("ALTER TABLE work_items ADD COLUMN type TEXT")
            stmt.execute("ALTER TABLE work_items ADD COLUMN properties TEXT")
            stmt.execute("ALTER TABLE work_items ADD COLUMN claimed_by TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE work_items ADD COLUMN claimed_at TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE work_items ADD COLUMN claim_expires_at TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE work_items ADD COLUMN original_claimed_at TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE work_items ADD COLUMN root_id BLOB")

            stmt.execute(
                """
                CREATE TABLE notes (
                    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    work_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
                    key             VARCHAR(200) NOT NULL,
                    role            VARCHAR(20) NOT NULL,
                    body            TEXT NOT NULL DEFAULT '',
                    created_at      TIMESTAMP NOT NULL,
                    modified_at     TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute("CREATE UNIQUE INDEX idx_notes_item_key ON notes(work_item_id, key)")
            stmt.execute("CREATE INDEX idx_notes_item ON notes(work_item_id)")
            stmt.execute("CREATE INDEX idx_notes_role ON notes(role)")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_id TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_kind TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_parent TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_proof TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN verification_status TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN verification_verifier TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN verification_reason TEXT")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE notes ADD COLUMN actor_proof_claims TEXT DEFAULT NULL")

            stmt.execute(
                """
                CREATE TABLE role_transitions (
                    id                  BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    item_id             BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
                    from_role           VARCHAR(20) NOT NULL,
                    to_role             VARCHAR(20) NOT NULL,
                    from_status_label   TEXT,
                    to_status_label     TEXT,
                    trigger             VARCHAR(50) NOT NULL,
                    summary             TEXT,
                    transitioned_at     TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute("CREATE INDEX idx_role_trans_item ON role_transitions(item_id)")
            stmt.execute("CREATE INDEX idx_role_trans_time ON role_transitions(transitioned_at)")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_id TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_kind TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_parent TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_proof TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN verification_status TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN verification_verifier TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN verification_reason TEXT")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN consumed_credentials TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL")
            stmt.execute("ALTER TABLE role_transitions ADD COLUMN actor_proof_claims TEXT DEFAULT NULL")

            if ("work_items_fts_trigram" in includeFtsTables) {
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE work_items_fts_trigram USING fts5(
                        title,
                        summary,
                        content='work_items',
                        content_rowid='rowid',
                        tokenize='trigram',
                        prefix='2 3'
                    )
                    """.trimIndent()
                )
            }
            if ("work_items_fts_text" in includeFtsTables) {
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE work_items_fts_text USING fts5(
                        title,
                        summary,
                        content='work_items',
                        content_rowid='rowid',
                        tokenize='porter unicode61 remove_diacritics 2',
                        prefix='2 3'
                    )
                    """.trimIndent()
                )
            }
            if ("notes_fts_trigram" in includeFtsTables) {
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE notes_fts_trigram USING fts5(
                        body,
                        content='notes',
                        content_rowid='rowid',
                        tokenize='trigram',
                        prefix='2 3'
                    )
                    """.trimIndent()
                )
            }
            if ("notes_fts_text" in includeFtsTables) {
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE notes_fts_text USING fts5(
                        body,
                        content='notes',
                        content_rowid='rowid',
                        tokenize='porter unicode61 remove_diacritics 2',
                        prefix='2 3'
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    private fun insertWorkItem(
        connection: Connection,
        id: UUID,
        title: String
    ) {
        val now = Timestamp.from(Instant.now())
        connection
            .prepareStatement(
                """
                INSERT INTO work_items (id, title, summary, role, priority, complexity, depth, created_at, modified_at, role_changed_at, version)
                VALUES (?, ?, '', 'queue', 'medium', 5, 0, ?, ?, ?, 1)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setString(2, title)
                stmt.setTimestamp(3, now)
                stmt.setTimestamp(4, now)
                stmt.setTimestamp(5, now)
                stmt.executeUpdate()
            }
    }

    private fun insertNote(
        connection: Connection,
        id: UUID,
        itemId: UUID,
        key: String,
        body: String,
        actorProof: String?
    ) {
        val now = Timestamp.from(Instant.now())
        connection
            .prepareStatement(
                """
                INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at, actor_proof)
                VALUES (?, ?, ?, 'work', ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(itemId))
                stmt.setString(3, key)
                stmt.setString(4, body)
                stmt.setTimestamp(5, now)
                stmt.setTimestamp(6, now)
                stmt.setString(7, actorProof)
                stmt.executeUpdate()
            }
    }

    private fun insertRoleTransition(
        connection: Connection,
        id: UUID,
        itemId: UUID,
        actorProof: String?
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO role_transitions (id, item_id, from_role, to_role, trigger, transitioned_at, actor_proof)
                VALUES (?, ?, 'queue', 'work', 'start', ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(itemId))
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.setString(4, actorProof)
                stmt.executeUpdate()
            }
    }

    private fun readBytesOrEmpty(file: File): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)

    /** Combined raw bytes of the main db file and its `-wal` sidecar, as a byte-preserving string. */
    private fun rawFileAndWalText(dbFile: File): String {
        val mainBytes = readBytesOrEmpty(dbFile)
        val walBytes = readBytesOrEmpty(File(dbFile.absolutePath + "-wal"))
        return String(mainBytes, Charsets.ISO_8859_1) + String(walBytes, Charsets.ISO_8859_1)
    }

    private fun walFile(dbFile: File): File = File(dbFile.absolutePath + "-wal")

    private fun readUserVersion(connection: Connection): Int {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version").use { rs ->
                assertTrue(rs.next(), "PRAGMA user_version must return a row")
                return rs.getInt(1)
            }
        }
    }

    private fun randomToken(
        seed: Int,
        length: Int = 1000
    ): String {
        val builder = StringBuilder(length)
        builder.append("TOK-$seed-${UUID.randomUUID()}-")
        while (builder.length < length) {
            builder.append(('a' + (builder.length % 26)))
        }
        return builder.substring(0, length)
    }

    /**
     * A residue fixture and the connection that built it, kept OPEN (see [buildResidueFixture]).
     *
     * [residueTokens] are values whose ROW no longer holds them as of the point the fixture
     * returns — either superseded by a same-length `UPDATE` or removed by a `DELETE` — so they
     * exist, if at all, only as freed-page/stale-WAL-frame content that a real VACUUM must drop.
     * [liveTokens] are the CURRENT value of some still-existing row (untouched notes, all
     * role_transitions, and the replacement values written by the quarter-`UPDATE`) and MUST
     * survive VACUUM, since VACUUM's whole contract is to preserve every live row. Conflating the
     * two sets is exactly the fixture bug this split fixes: it is meaningless to assert that "all
     * seeded tokens" disappear when 70 of the 90 written here are ordinary live data.
     */
    private class ResidueFixture(
        val residueTokens: List<String>,
        val liveTokens: List<String>,
        val connection: Connection
    ) {
        val allTokens: List<String> get() = residueTokens + liveTokens
    }

    /**
     * Builds a file-backed database with a full post-V17 schema, WAL journal mode, [noteCount]
     * notes (each with a unique ~1000-char `actor_proof` token and a distinct searchable `body`
     * word), a matching number of role_transitions rows carrying their own tokens, then re-upserts
     * a quarter of the notes with a fresh same-length token and deletes another quarter outright
     * (leaving stale page content behind, since `secure_delete` is off) before returning.
     *
     * The connection used to build the fixture is returned OPEN, not closed. This matters: SQLite
     * runs an automatic checkpoint when the LAST connection to a WAL-mode database closes, and for
     * an UPDATE that replaces a column value with a same-length replacement, the page holding that
     * row is rewritten in place — the old value survives only in the WAL frame written by the
     * original INSERT's transaction, which an early close-triggered checkpoint can immediately
     * fold away. `V17ActorProofScrubMigrationTest` avoids exactly this hazard by keeping its own
     * `keepAliveConnection` open across the whole test; the caller here must do the same and close
     * this connection only after it has captured whatever pre-compaction residue evidence it needs
     * (and, in any case, before invoking `StartupCompaction.runOnce`, which needs the file
     * unlocked to VACUUM).
     */
    private fun buildResidueFixture(
        dbFile: File,
        noteCount: Int = 40,
        includeFtsTables: Set<String> = StartupCompaction.FTS_TABLES.toSet()
    ): ResidueFixture {
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        val residueTokens = mutableListOf<String>()
        val liveTokens = mutableListOf<String>()
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        createFullSchema(connection, includeFtsTables)

        val itemId = UUID.randomUUID()
        insertWorkItem(connection, itemId, "residue-fixture-item")

        val quarter = noteCount / 4
        val noteIds = (0 until noteCount).map { UUID.randomUUID() }
        val originalNoteTokens = (0 until noteCount).map { randomToken(it) }
        for (i in 0 until noteCount) {
            insertNote(connection, noteIds[i], itemId, "note-$i", "searchmarker$i unique body text", originalNoteTokens[i])
            // Rows i in [0, 2*quarter) are about to be superseded (UPDATE) or removed (DELETE)
            // below, so their INITIAL token is residue, not live data. Rows from 2*quarter on are
            // never touched again and so remain live.
            if (i >= quarter * 2) liveTokens += originalNoteTokens[i] else residueTokens += originalNoteTokens[i]

            val transitionToken = randomToken(10_000 + i)
            // role_transitions are never updated or deleted by this fixture — every one is live.
            liveTokens += transitionToken
            insertRoleTransition(connection, UUID.randomUUID(), itemId, transitionToken)
        }

        // Re-upsert (UPDATE) a quarter of the notes with a fresh, same-length token — the OLD
        // bytes are not overwritten in place by SQLite's b-tree at the WAL-frame level (the
        // original INSERT's frame already exists), so they linger as page residue until a real
        // VACUUM runs, while the NEW (replacement) value is the row's current, live content.
        for (i in 0 until quarter) {
            val replacement = randomToken(20_000 + i)
            liveTokens += replacement
            connection.prepareStatement("UPDATE notes SET actor_proof = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, replacement)
                stmt.setBytes(2, uuidToBytes(noteIds[i]))
                stmt.executeUpdate()
            }
        }
        // Delete another quarter outright — their original tokens become pure residue.
        for (i in quarter until quarter * 2) {
            connection.prepareStatement("DELETE FROM notes WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(noteIds[i]))
                stmt.executeUpdate()
            }
        }
        return ResidueFixture(residueTokens, liveTokens, connection)
    }

    // ────────────────────────────────────────────────────────────────────────
    // S1 — happy path: VACUUM removes residue, wal_checkpoint truncates the WAL. [A][V][W]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 runOnce compacts a fixture with residue tokens and truncates the WAL`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s1.db").toFile()
        val fixture = buildResidueFixture(dbFile)

        // Anti-vacuity precondition: every token must actually be findable in the raw file bytes
        // before compaction runs — otherwise a post-compaction "absent" result would be
        // meaningless (the token might never have been written to disk at all). Captured while the
        // seeding connection is still open, per buildResidueFixture's KDoc.
        val beforeText = rawFileAndWalText(dbFile)
        val missingBefore = fixture.allTokens.filterNot { beforeText.contains(it) }
        assertTrue(
            missingBefore.isEmpty(),
            "anti-vacuity: all seeded tokens must be present pre-compaction; missing ${missingBefore.size} of ${fixture.allTokens.size}"
        )
        fixture.connection.close()

        val outcome = StartupCompaction.runOnce("jdbc:sqlite:${dbFile.absolutePath}")

        assertEquals(CompactionOutcome.COMPACTED, outcome, "expected a fresh, unlocked file DB to compact")

        val afterText = rawFileAndWalText(dbFile)
        val survivingResidue = fixture.residueTokens.filter { afterText.contains(it) }
        assertTrue(survivingResidue.isEmpty(), "expected VACUUM to drop all freed-page residue; ${survivingResidue.size} token(s) survived")
        val missingLive = fixture.liveTokens.filterNot { afterText.contains(it) }
        assertTrue(
            missingLive.isEmpty(),
            "VACUUM must preserve every live row's current value; ${missingLive.size} live token(s) were lost"
        )

        val wal = walFile(dbFile)
        assertTrue(
            !wal.exists() || wal.length() == 0L,
            "expected wal_checkpoint(TRUNCATE) to leave the -wal file absent or empty; size=${wal.length()}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S2 — FTS5 rebuild + integrity-check; MATCH results identical by id across compaction. [F][V]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 runOnce rebuilds all four FTS5 tables and search hits by id survive compaction`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s2.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            createFullSchema(connection)
            val itemId = UUID.randomUUID()
            insertWorkItem(connection, itemId, "s2-item")
            repeat(10) { i ->
                val noteId = UUID.randomUUID()
                insertNote(connection, noteId, itemId, "s2-note-$i", "zqxmarker$i distinctive search text", null)
            }
            // Populate the FTS index once before compaction so "before" results are meaningful.
            connection.createStatement().use { it.execute("INSERT INTO notes_fts_text(notes_fts_text) VALUES('rebuild')") }
        } finally {
            connection.close()
        }

        fun matchIds(word: String): Set<UUID> {
            val conn = DriverManager.getConnection(jdbcUrl)
            try {
                conn
                    .prepareStatement(
                        """
                        SELECT n.id AS note_id FROM notes n
                        JOIN notes_fts_text f ON f.rowid = n.rowid
                        WHERE notes_fts_text MATCH ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, word)
                        stmt.executeQuery().use { rs ->
                            val ids = mutableSetOf<UUID>()
                            while (rs.next()) {
                                val bytes = rs.getBytes("note_id")
                                val bb = java.nio.ByteBuffer.wrap(bytes)
                                ids += UUID(bb.long, bb.long)
                            }
                            return ids
                        }
                    }
            } finally {
                conn.close()
            }
        }

        val beforeHits = (0 until 10).associateWith { i -> matchIds("zqxmarker$i") }
        assertTrue(beforeHits.values.all { it.size == 1 }, "each seeded marker must match exactly one note before compaction: $beforeHits")

        val outcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, outcome)

        val afterHits = (0 until 10).associateWith { i -> matchIds("zqxmarker$i") }
        assertEquals(
            beforeHits,
            afterHits,
            "MATCH results by note id must be unchanged by compaction, even though VACUUM may renumber rowids"
        )

        // Integrity-check must not raise on any of the four FTS tables post-rebuild.
        val verifyConn = DriverManager.getConnection(jdbcUrl)
        try {
            for (table in StartupCompaction.FTS_TABLES) {
                verifyConn.createStatement().use { stmt ->
                    // Throws SQLException if the shadow index disagrees with its content table.
                    stmt.execute("INSERT INTO $table($table) VALUES('integrity-check')")
                }
            }
        } finally {
            verifyConn.close()
        }

        // FTS_TABLES must name exactly the fts5 virtual tables actually present in the schema.
        val actualFtsTables = mutableSetOf<String>()
        val schemaConn = DriverManager.getConnection(jdbcUrl)
        try {
            schemaConn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='table'").use { rs ->
                    while (rs.next()) {
                        val sql = rs.getString("sql") ?: continue
                        if (sql.contains("USING fts5", ignoreCase = true)) {
                            actualFtsTables += rs.getString("name")
                        }
                    }
                }
            }
        } finally {
            schemaConn.close()
        }
        assertEquals(
            StartupCompaction.FTS_TABLES.toSet(),
            actualFtsTables,
            "FTS_TABLES must name exactly the fts5 tables present in the schema"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S3 — user_version gate is set to COMPACTED_USER_VERSION after a successful run. [A][D]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 runOnce sets PRAGMA user_version to COMPACTED_USER_VERSION on success`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s3.db").toFile()
        buildResidueFixture(dbFile, noteCount = 8).connection.close()

        val outcome = StartupCompaction.runOnce("jdbc:sqlite:${dbFile.absolutePath}")
        assertEquals(CompactionOutcome.COMPACTED, outcome)

        val connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            assertEquals(StartupCompaction.COMPACTED_USER_VERSION, readUserVersion(connection))
        } finally {
            connection.close()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S4 — fresh, empty schema still compacts and sets the gate. [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 runOnce compacts a fresh empty schema and sets user_version`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s4.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            createFullSchema(connection)
        } finally {
            connection.close()
        }

        val outcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, outcome, "an empty but valid schema must still compact successfully")

        val verifyConn = DriverManager.getConnection(jdbcUrl)
        try {
            assertEquals(StartupCompaction.COMPACTED_USER_VERSION, readUserVersion(verifyConn))
        } finally {
            verifyConn.close()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S6 — a concurrent writer holding the file causes FAILED (busy timeout), never a throw;
    // releasing the lock allows a subsequent call to succeed. [A][D]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 runOnce returns FAILED without throwing when the file is locked, then succeeds after release`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s6.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            createFullSchema(connection)
        } finally {
            connection.close()
        }

        val blocker = DriverManager.getConnection(jdbcUrl)
        blocker.autoCommit = false
        try {
            blocker.createStatement().use { it.execute("UPDATE work_items SET title = title") }

            val outcome = StartupCompaction.runOnce(jdbcUrl, busyTimeoutMs = 100L)
            assertEquals(CompactionOutcome.FAILED, outcome, "a held write lock must produce FAILED, not a thrown exception")

            val checkConn = DriverManager.getConnection(jdbcUrl)
            try {
                assertEquals(0, readUserVersion(checkConn), "user_version must remain at the un-compacted default after a failed attempt")
            } finally {
                checkConn.close()
            }
        } finally {
            blocker.rollback()
            blocker.close()
        }

        val retryOutcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, retryOutcome, "once the lock is released, a subsequent call must succeed")
    }

    // ────────────────────────────────────────────────────────────────────────
    // S7 — a missing FTS5 table causes FAILED (rebuild statement errors), never a throw. [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 runOnce returns FAILED without throwing when an expected FTS5 table is missing`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s7.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            // Omit notes_fts_text — one of the four StartupCompaction.FTS_TABLES entries.
            createFullSchema(connection, includeFtsTables = setOf("work_items_fts_trigram", "work_items_fts_text", "notes_fts_trigram"))
        } finally {
            connection.close()
        }

        val outcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.FAILED, outcome, "rebuilding a nonexistent FTS table must fail the run, not throw")

        val verifyConn = DriverManager.getConnection(jdbcUrl)
        try {
            assertEquals(0, readUserVersion(verifyConn), "a failed run must leave user_version at 0 so the next boot retries")
        } finally {
            verifyConn.close()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S8 — free-disk pre-check: 0 usable bytes skips; exactly 2x (db+wal) size still compacts. [D]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 runOnce skips for insufficient disk space and proceeds at exactly twice the file size`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s8.db").toFile()
        val fixture = buildResidueFixture(dbFile, noteCount = 8)
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            val skipOutcome = StartupCompaction.runOnce(jdbcUrl, usableSpaceBytes = { 0L })
            assertEquals(CompactionOutcome.SKIPPED_INSUFFICIENT_DISK, skipOutcome)

            val afterSkipConn = DriverManager.getConnection(jdbcUrl)
            try {
                assertEquals(0, readUserVersion(afterSkipConn), "a disk-space skip must not set user_version")
            } finally {
                afterSkipConn.close()
            }
            // Checked while the seeding connection is still open, so no close-triggered checkpoint
            // has had a chance to fold away any same-length UPDATE's superseded WAL frame.
            val textAfterSkip = rawFileAndWalText(dbFile)
            assertTrue(
                fixture.allTokens.all {
                    textAfterSkip.contains(it)
                },
                "no residue may be removed when the run is skipped for insufficient disk space"
            )
        } finally {
            fixture.connection.close()
        }

        val exactThreshold = 2 * (dbFile.length() + walFile(dbFile).length())
        val compactOutcome = StartupCompaction.runOnce(jdbcUrl, usableSpaceBytes = { exactThreshold })
        assertEquals(
            CompactionOutcome.COMPACTED,
            compactOutcome,
            "exactly 2x the (db+wal) size must be treated as sufficient (inclusive boundary)"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S14 (amended 2026-09-25, user-approved) — a concurrent open READ transaction prevents the
    // pre-VACUUM `wal_checkpoint(TRUNCATE)` from completing (reported busy per its [W] result row);
    // runOnce reports FAILED without throwing and leaves user_version at 0. Once the reader
    // releases, a subsequent call succeeds. [W][A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S14 runOnce returns FAILED when a concurrent reader blocks the pre-VACUUM checkpoint, then succeeds after release`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s14.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val setupConnection = DriverManager.getConnection(jdbcUrl)
        setupConnection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        createFullSchema(setupConnection)
        insertWorkItem(setupConnection, UUID.randomUUID(), "s14-item")

        // Open the reader and start its read transaction (SQLite acquires the read lock lazily, on
        // the first actual read) BEFORE closing the setup connection. A passive checkpoint can run
        // whenever nothing blocks it — not only when the closing connection is the last one open —
        // so closing setupConnection while the reader is merely open but still idle already let a
        // checkpoint fully truncate the WAL in an earlier run of this test. Holding the reader's
        // transaction open first guarantees a blocker is in place before setupConnection closes.
        val reader = DriverManager.getConnection(jdbcUrl)
        reader.autoCommit = false
        reader.createStatement().executeQuery("SELECT COUNT(*) FROM work_items").use { rs -> assertTrue(rs.next()) }

        setupConnection.close()

        // Precondition: the fixture's writes must actually be sitting in the WAL, un-checkpointed —
        // otherwise a "checkpoint reports busy" scenario would not apply, since there would be
        // nothing left to checkpoint.
        val wal = walFile(dbFile)
        assertTrue(
            wal.exists() && wal.length() > 0L,
            "expected un-checkpointed WAL frames while the reader's transaction is open; -wal size=${wal.length()}"
        )

        try {
            val outcome = StartupCompaction.runOnce(jdbcUrl)
            assertEquals(
                CompactionOutcome.FAILED,
                outcome,
                "an open reader blocking the pre-VACUUM checkpoint must produce FAILED, not a thrown exception"
            )

            val checkConn = DriverManager.getConnection(jdbcUrl)
            try {
                assertEquals(
                    0,
                    readUserVersion(checkConn),
                    "user_version must remain at the un-compacted default after a failed checkpoint"
                )
            } finally {
                checkConn.close()
            }
        } finally {
            reader.rollback()
            reader.close()
        }

        val retryOutcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, retryOutcome, "once the reader releases, a subsequent call must succeed")
    }

    // ────────────────────────────────────────────────────────────────────────
    // S9 — idempotency / replay: once compacted, a second call is a no-op that performs no VACUUM
    // (proved by a token planted afterward still being present). [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 a second runOnce after COMPACTED is a no-op and performs no further VACUUM`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s9.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        buildResidueFixture(dbFile, noteCount = 8).connection.close()

        val firstOutcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, firstOutcome)

        // Plant a fresh token and delete the row that holds it, WITHOUT secure_delete — its bytes
        // remain in a freed page unless a real VACUUM runs. The connection is kept open across the
        // presence check AND the replay call (ALREADY_COMPACTED never VACUUMs, so there is no lock
        // conflict) to rule out a close-triggered checkpoint folding the residue away before either
        // assertion runs.
        val plantedToken = randomToken(99_999)
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            val itemId = UUID.randomUUID()
            val noteId = UUID.randomUUID()
            insertWorkItem(connection, itemId, "s9-replay-item")
            insertNote(connection, noteId, itemId, "s9-replay-note", "replay body", plantedToken)
            connection.prepareStatement("DELETE FROM notes WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(noteId))
                stmt.executeUpdate()
            }

            val plantedText = rawFileAndWalText(dbFile)
            assertTrue(plantedText.contains(plantedToken), "anti-vacuity: the planted token must be on disk before the replay call")

            val secondOutcome = StartupCompaction.runOnce(jdbcUrl)
            assertEquals(
                CompactionOutcome.ALREADY_COMPACTED,
                secondOutcome,
                "a DB whose user_version is already set must be reported as ALREADY_COMPACTED"
            )

            val afterReplayText = rawFileAndWalText(dbFile)
            assertTrue(
                afterReplayText.contains(plantedToken),
                "ALREADY_COMPACTED must perform no VACUUM — the planted token must still be present"
            )
        } finally {
            connection.close()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S10 (amended 2026-09-25, user-approved: the earlier `<path>?foo=bar` case is unopenable by
    // sqlite-jdbc at all and therefore unreachable in production; replaced with a `file:` prefix
    // plus `?cache=shared`, a form sqlite-jdbc does accept for a real file) — non-file / in-memory
    // JDBC URL forms are skipped without opening a connection; a `file:`-prefixed URL carrying a
    // query string still resolves to the real file and compacts. [A]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 runOnce recognizes memory and non-sqlite URLs as not-a-file-db`() {
        assertEquals(CompactionOutcome.SKIPPED_NOT_FILE_DB, StartupCompaction.runOnce("jdbc:sqlite::memory:"))
        assertEquals(
            CompactionOutcome.SKIPPED_NOT_FILE_DB,
            StartupCompaction.runOnce("jdbc:sqlite:file:x?mode=memory")
        )
        assertEquals(CompactionOutcome.SKIPPED_NOT_FILE_DB, StartupCompaction.runOnce("jdbc:h2:mem:t"))
    }

    @Test
    fun `S10 runOnce resolves a sqlite file URL using the file colon prefix with a query string and compacts it`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s10.db").toFile()
        val connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            createFullSchema(connection)
        } finally {
            connection.close()
        }

        val outcome = StartupCompaction.runOnce("jdbc:sqlite:file:${dbFile.absolutePath}?cache=shared")
        assertEquals(CompactionOutcome.COMPACTED, outcome, "a file: prefix with a query string must still resolve to the real file")
    }

    // ────────────────────────────────────────────────────────────────────────
    // S13 — a >=100 MB database compacts within the acceptance bound of 30 seconds. [A, amended
    // by user 2026-09-25: 10s->30s after 12.5s measured]; Dockerfile HEALTHCHECK start-period=20s.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S13 runOnce compacts a 100MB-plus database in under 30 seconds`(
        @TempDir tempDir: Path
    ) {
        val dbFile = tempDir.resolve("s13.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            createFullSchema(connection)
            connection.autoCommit = false
            val itemId = UUID.randomUUID()
            insertWorkItem(connection, itemId, "s13-bulk-item")

            // ~1 MiB of moderately varied text per row (avoids a single-trigram degenerate index)
            // x 105 rows ≈ 105 MB, generated cheaply and inserted in one transaction.
            val rowSize = 1_048_576
            val chunk = (0 until 64).joinToString("") { ('a' + (it % 26)).toString() }
            repeat(105) { rowIndex ->
                val body =
                    buildString(rowSize) {
                        append("ROW$rowIndex-")
                        while (length < rowSize) append(chunk)
                    }.take(rowSize)
                insertNote(connection, UUID.randomUUID(), itemId, "bulk-$rowIndex", body, null)
            }
            connection.commit()
        } finally {
            connection.close()
        }

        assertTrue(dbFile.length() >= 100L * 1024 * 1024, "fixture must reach at least 100 MB; got ${dbFile.length()} bytes")

        val elapsedMs =
            kotlin.system.measureTimeMillis {
                val outcome = StartupCompaction.runOnce(jdbcUrl)
                assertEquals(CompactionOutcome.COMPACTED, outcome)
            }

        assertTrue(elapsedMs < 30_000L, "expected a >=100 MB compaction to complete in under 30s; took ${elapsedMs}ms")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Probes with no dedicated scenario above (recorded per test-author skill §6)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe path with spaces in the database directory still compacts`(
        @TempDir tempDir: Path
    ) {
        val spacedDir = tempDir.resolve("dir with spaces").toFile()
        assertTrue(spacedDir.mkdirs())
        val dbFile = File(spacedDir, "probe.db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            createFullSchema(connection)
        } finally {
            connection.close()
        }

        val outcome = StartupCompaction.runOnce("jdbc:sqlite:${dbFile.absolutePath}")
        assertEquals(CompactionOutcome.COMPACTED, outcome, "a path containing spaces must not break URL/file resolution")
    }

    @Test
    fun `probe database file that does not yet exist still resolves to a real file, not a memory skip`(
        @TempDir tempDir: Path
    ) {
        // A path that does not yet exist on disk must still be treated as a file-DB target (SQLite
        // creates the file lazily on connect) rather than misclassified as SKIPPED_NOT_FILE_DB.
        val dbFile = tempDir.resolve("does-not-exist-yet.db").toFile()
        assertFalse(dbFile.exists())
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            createFullSchema(connection)
        } finally {
            connection.close()
        }
        assertNotNull(dbFile, "sanity: file now exists after schema creation")

        val outcome = StartupCompaction.runOnce(jdbcUrl)
        assertEquals(CompactionOutcome.COMPACTED, outcome)
    }
}

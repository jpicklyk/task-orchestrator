package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the V17 migration (`V17__Store_Actor_Proof_Evidence.sql`) — item
 * 983615e7 "Stop persisting raw actor proof JWTs; scrub existing rows".
 *
 * Test-plan scenarios covered: S4 (schema + scrub on the real V16 schema copy), S4b
 * (a single-row `secure_delete` zero-occurrence check on a FILE-backed database), and S4c (a
 * 200-row, explicit-WAL fixture bounding — not eliminating — residual proof-prefix survivors).
 * `secure_delete` is a **partial** scrub at real, multi-row scale: it zeroes the cell/overflow
 * content a page-level UPDATE frees, but SQLite b-tree fragmentation, WAL frames written before a
 * checkpoint, and slack space from prior writes can still leave a small number of stale byte
 * fragments behind. S4b's single-row, zero-occurrence assertion holds because there is nothing
 * else in the file to fragment around; S4c's multi-row assertion is a bound (fewer than N/2
 * distinct token-prefix survivors), never zero — see S4c's own comment for the measured range.
 *
 * Follows the [V14ConsumedCredentialsMigrationTest] / [V9RootIdMigrationTest] pattern: hand-build
 * the pre-migration schema (V1 base tables + the V4 actor-attribution columns, matching the two
 * migration files read verbatim off the classpath for this test), seed rows via raw JDBC, apply
 * the real V17 SQL file off the classpath (not a hand-copied duplicate), then assert.
 *
 * Oracle: migration-assessment note on item 983615e7 (`## 1. Schema changes`, `## 3. Data
 * migration`) — two new nullable columns per table, additive only; the scrub UPDATEs null out
 * `actor_proof` without touching `modified_at`/`transitioned_at`; `PRAGMA secure_delete = ON`
 * precedes the scrub so freed content is zeroed rather than left in a free page.
 */
class V17ActorProofScrubMigrationTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var jdbcUrl: String
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        // The real V16 schema (what a pre-V17 user has), not a hand-built copy.
        jdbcUrl = UpgradeHarness.copyAt(16, File(tempDir, "v17.db"))
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
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

    /** Applies the real V17 migration through Flyway (target 17) on the V16 database at [url]. */
    private fun applyV17Migration(url: String) {
        UpgradeHarness.migrate(url, target = 17)
    }

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    private fun insertWorkItem(
        connection: Connection,
        id: UUID,
        title: String
    ) {
        connection
            .prepareStatement(
                "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, ?, datetime('now'), datetime('now'), datetime('now'))"
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setString(2, title)
                stmt.executeUpdate()
            }
    }

    private fun insertPreV17Note(
        connection: Connection,
        id: UUID,
        itemId: UUID,
        key: String,
        body: String,
        actorProof: String,
        modifiedAt: Timestamp
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO notes
                    (id, work_item_id, key, role, body, created_at, modified_at, actor_proof)
                VALUES (?, ?, ?, 'work', ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(itemId))
                stmt.setString(3, key)
                stmt.setString(4, body)
                stmt.setTimestamp(5, modifiedAt)
                stmt.setTimestamp(6, modifiedAt)
                stmt.setString(7, actorProof)
                stmt.executeUpdate()
            }
    }

    private fun insertPreV17Transition(
        connection: Connection,
        id: UUID,
        itemId: UUID,
        actorProof: String
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO role_transitions
                    (id, item_id, from_role, to_role, trigger, transitioned_at, actor_proof)
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

    // ────────────────────────────────────────────────────────────────────────
    // S4 — schema + scrub, the real V16 schema copy (UpgradeHarness.copyAt)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 V17 adds evidence columns and scrubs existing actor_proof on both tables without touching body or modified_at`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val noteId = UUID.randomUUID()
            val transitionId = UUID.randomUUID()
            // Fixed, distinguishable timestamp so we can assert it is untouched by the scrub.
            val fixedModifiedAt = Timestamp.from(Instant.parse("2026-01-15T10:30:00Z"))

            insertWorkItem(keepAliveConnection, itemId, "Pre-V17 item")
            insertPreV17Note(
                keepAliveConnection,
                noteId,
                itemId,
                key = "pre-v17-note",
                body = "Body untouched by scrub",
                actorProof = "seed-raw-jwt-notes",
                modifiedAt = fixedModifiedAt
            )
            insertPreV17Transition(keepAliveConnection, transitionId, itemId, actorProof = "seed-raw-jwt-transitions")

            applyV17Migration(jdbcUrl)

            // --- Column existence: both new columns on both tables ---
            val notesColumns = mutableSetOf<String>()
            val transitionsColumns = mutableSetOf<String>()
            transaction(db = database) {
                exec("PRAGMA table_info(notes)") { rs -> while (rs.next()) notesColumns.add(rs.getString("name")) }
                exec("PRAGMA table_info(role_transitions)") { rs -> while (rs.next()) transitionsColumns.add(rs.getString("name")) }
            }
            assertTrue("actor_proof_sha256" in notesColumns, "notes must gain actor_proof_sha256; got $notesColumns")
            assertTrue("actor_proof_claims" in notesColumns, "notes must gain actor_proof_claims; got $notesColumns")
            assertTrue(
                "actor_proof_sha256" in transitionsColumns,
                "role_transitions must gain actor_proof_sha256; got $transitionsColumns"
            )
            assertTrue(
                "actor_proof_claims" in transitionsColumns,
                "role_transitions must gain actor_proof_claims; got $transitionsColumns"
            )

            // --- notes row: actor_proof scrubbed, new columns NULL, body/modified_at unchanged ---
            keepAliveConnection
                .prepareStatement("SELECT actor_proof, actor_proof_sha256, actor_proof_claims, body, modified_at FROM notes WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(noteId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "expected the pre-existing note row to still be present")
                        assertNull(rs.getString("actor_proof"), "notes.actor_proof must be scrubbed to NULL by V17")
                        assertNull(rs.getString("actor_proof_sha256"), "historical row gets no hash (migration-assessment ## 3)")
                        assertNull(rs.getString("actor_proof_claims"), "historical row gets no claims (migration-assessment ## 3)")
                        assertEquals("Body untouched by scrub", rs.getString("body"), "scrub must not touch body")
                        assertEquals(fixedModifiedAt, rs.getTimestamp("modified_at"), "scrub must not touch modified_at")
                    }
                }

            // --- role_transitions row: actor_proof scrubbed, new columns NULL ---
            keepAliveConnection
                .prepareStatement("SELECT actor_proof, actor_proof_sha256, actor_proof_claims FROM role_transitions WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(transitionId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "expected the pre-existing transition row to still be present")
                        assertNull(rs.getString("actor_proof"), "role_transitions.actor_proof must be scrubbed to NULL by V17")
                        assertNull(rs.getString("actor_proof_sha256"), "historical row gets no hash (migration-assessment ## 3)")
                        assertNull(rs.getString("actor_proof_claims"), "historical row gets no claims (migration-assessment ## 3)")
                    }
                }
        }

    @Test
    fun `S4 V17 leaves a row with no actor_proof untouched (WHERE actor_proof IS NOT NULL guard)`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val noteId = UUID.randomUUID()
            insertWorkItem(keepAliveConnection, itemId, "No-actor item")
            // Insert a note with NO actor_proof set at all (column left NULL) — the scrub UPDATE's
            // WHERE clause must not error or otherwise disturb a row that was already NULL.
            keepAliveConnection
                .prepareStatement(
                    "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) VALUES (?, ?, 'no-actor', 'work', 'b', ?, ?)"
                ).use { stmt ->
                    val now = Timestamp.from(Instant.now())
                    stmt.setBytes(1, uuidToBytes(noteId))
                    stmt.setBytes(2, uuidToBytes(itemId))
                    stmt.setTimestamp(3, now)
                    stmt.setTimestamp(4, now)
                    stmt.executeUpdate()
                }

            applyV17Migration(jdbcUrl)

            keepAliveConnection
                .prepareStatement("SELECT actor_proof, actor_proof_sha256, actor_proof_claims FROM notes WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(noteId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertNull(rs.getString("actor_proof"))
                        assertNull(rs.getString("actor_proof_sha256"))
                        assertNull(rs.getString("actor_proof_claims"))
                    }
                }
        }

    /** Reads a file's bytes, or an empty array when it does not exist (e.g. no `-wal` sidecar yet). */
    private fun readBytesOrEmpty(file: java.io.File): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)

    /**
     * Combined raw bytes of the main database file and its `-wal` sidecar (if present), decoded as
     * ISO-8859-1 (a byte-preserving single-byte charset) so a plain [String.contains] substring
     * search reliably detects a token regardless of its original encoding.
     */
    private fun rawFileAndWalText(dbFile: java.io.File): String {
        val mainBytes = readBytesOrEmpty(dbFile)
        val walBytes = readBytesOrEmpty(java.io.File(dbFile.absolutePath + "-wal"))
        return String(mainBytes, Charsets.ISO_8859_1) + String(walBytes, Charsets.ISO_8859_1)
    }

    // ────────────────────────────────────────────────────────────────────────
    // S4b — secure_delete zeroes the cell the scrub frees (single row, zero-occurrence)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4b V17 secure_delete zeroes the cell the scrub frees`(): Unit =
        runBlocking {
            // A long, highly unique token so a substring match in the raw file bytes is a reliable
            // signal (not a coincidental byte sequence from other row data or page headers).
            val seededToken = "SEEDED-PROOF-TOKEN-${UUID.randomUUID()}-MUST-NOT-SURVIVE-SCRUB"

            val dbFile = File(tempDir, "v17_test.db")
            val jdbcUrl = UpgradeHarness.copyAt(16, dbFile)

            var fileDatabase: Database? = null
            var fileConnection: Connection? = null
            try {
                fileConnection = DriverManager.getConnection(jdbcUrl)
                fileDatabase = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")

                val itemId = UUID.randomUUID()
                val noteId = UUID.randomUUID()
                insertWorkItem(fileConnection, itemId, "File-backed item")
                insertPreV17Note(
                    fileConnection,
                    noteId,
                    itemId,
                    key = "file-note",
                    body = "File-backed body",
                    actorProof = seededToken,
                    modifiedAt = Timestamp.from(Instant.now())
                )

                // Sanity: before the migration, the token IS present in the connection's view —
                // confirms the seed actually reached storage (not just held in a WAL that never
                // gets written, which would make the post-migration absence check meaningless).
                fileConnection.prepareStatement("SELECT actor_proof FROM notes WHERE id = ?").use { stmt ->
                    stmt.setBytes(1, uuidToBytes(noteId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(seededToken, rs.getString("actor_proof"))
                    }
                }

                applyV17Migration(jdbcUrl)

                // Force any WAL content to be written back into the main database file and
                // truncate the WAL, so what we read from the raw file bytes reflects the
                // post-migration, post-secure_delete state.
                transaction(db = fileDatabase) {
                    exec("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            } finally {
                try {
                    fileDatabase?.let { TransactionManager.closeAndUnregister(it) }
                } catch (_: Exception) {
                }
                try {
                    fileConnection?.close()
                } catch (_: Exception) {
                }
            }

            val rawBytes = dbFile.readBytes()
            val rawText = String(rawBytes, Charsets.ISO_8859_1)
            assertTrue(
                !rawText.contains(seededToken),
                "seeded proof token must not survive anywhere in the raw database file bytes after the " +
                    "secure_delete scrub (migration-assessment ## 3); file size=${rawBytes.size}"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // S4c — multi-row, explicit-WAL bound on residual proof-prefix survivors (partial scrub, not a
    // zero guarantee). Reviewer-directed re-scope of S4b: a single seeded row cannot exercise
    // b-tree fragmentation or WAL-frame residue the way a real, multi-row upgrade would.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4c V17 scrub on 200 notes with 20 percent re-upserts bounds residual proof-prefix survivors below half`(): Unit =
        runBlocking {
            val rowCount = 200
            val reupsertCount = 40 // 20% of rowCount
            val tokenPrefixLength = 40

            // Unique, ~1000-char tokens per row so byte-level substring matching is reliable and
            // near-collision-free. `replacementTokens[i]` supersedes `seedTokens[i]` for i < 40,
            // simulating a same-key application UPDATE prior to this fix (which used to leave the
            // old value's bytes behind on the page until overwritten by later activity).
            val seedTokens = (0 until rowCount).map { i -> "SEED$i-" + "P".repeat(970) + "-${UUID.randomUUID()}" }
            val replacementTokens = (0 until reupsertCount).map { i -> "REPL$i-" + "Q".repeat(970) + "-${UUID.randomUUID()}" }
            val allTokens = seedTokens + replacementTokens

            val dbFile = File(tempDir, "v17_multi_test.db")
            val jdbcUrl = UpgradeHarness.copyAt(16, dbFile)

            var fileDatabase: Database? = null
            var fileConnection: Connection? = null
            try {
                fileConnection = DriverManager.getConnection(jdbcUrl)
                // Set WAL mode via the raw (auto-commit) connection, BEFORE any Exposed
                // transaction opens on this database -- SQLite refuses "PRAGMA journal_mode=WAL"
                // from within an active transaction.
                fileConnection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                fileDatabase = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")

                val itemId = UUID.randomUUID()
                insertWorkItem(fileConnection, itemId, "Multi-row item")
                val noteIds = (0 until rowCount).map { UUID.randomUUID() }
                val now = Timestamp.from(Instant.now())
                for (i in 0 until rowCount) {
                    insertPreV17Note(
                        fileConnection,
                        noteIds[i],
                        itemId,
                        key = "multi-note-$i",
                        body = "multi-row body $i",
                        actorProof = seedTokens[i],
                        modifiedAt = now
                    )
                }
                // 20% re-upserted: same (work_item_id, key), new actor_proof value.
                for (i in 0 until reupsertCount) {
                    fileConnection.prepareStatement("UPDATE notes SET actor_proof = ? WHERE id = ?").use { stmt ->
                        stmt.setString(1, replacementTokens[i])
                        stmt.setBytes(2, uuidToBytes(noteIds[i]))
                        stmt.executeUpdate()
                    }
                }

                // Anti-vacuity: every token ever written (seed AND replacement) must be findable in
                // the combined .db + -wal bytes BEFORE the migration runs — proves this test's
                // byte-scanning methodology actually detects tokens in this file, so a low
                // post-migration count below means the scrub did something, not that the scan is
                // blind.
                val preMigrationText = rawFileAndWalText(dbFile)
                val missingBeforeMigration = allTokens.filterNot { preMigrationText.contains(it.take(tokenPrefixLength)) }
                assertTrue(
                    missingBeforeMigration.isEmpty(),
                    "anti-vacuity: every seeded/replacement token must be present pre-migration; " +
                        "missing ${missingBeforeMigration.size} of ${allTokens.size}"
                )

                applyV17Migration(jdbcUrl)

                transaction(db = fileDatabase) {
                    exec("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            } finally {
                try {
                    fileDatabase?.let { TransactionManager.closeAndUnregister(it) }
                } catch (_: Exception) {
                }
                try {
                    fileConnection?.close()
                } catch (_: Exception) {
                }
            }

            // (1) No non-NULL actor_proof remains on either table (a fresh connection, since the
            // migration connection above was closed).
            val verifyConnection = DriverManager.getConnection(jdbcUrl)
            try {
                verifyConnection.prepareStatement("SELECT COUNT(*) AS cnt FROM notes WHERE actor_proof IS NOT NULL").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0, rs.getInt("cnt"), "no notes row may retain a non-NULL actor_proof after V17")
                    }
                }
                verifyConnection
                    .prepareStatement("SELECT COUNT(*) AS cnt FROM role_transitions WHERE actor_proof IS NOT NULL")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            assertTrue(rs.next())
                            assertEquals(0, rs.getInt("cnt"), "no role_transitions row may retain a non-NULL actor_proof after V17")
                        }
                    }
            } finally {
                verifyConnection.close()
            }

            // (2) Residual byte-level survivors, post-checkpoint, bounded below N/2 (100) — never
            // zero on this multi-row fixture. Per the reviewer/judge's own measurement: 3-14
            // distinct token-prefix survivors with the secure_delete pragma applied (this scrub),
            // versus 200/200 without it (i.e. every token trivially still findable when the scrub
            // does nothing) — so this bound is both far below the un-scrubbed baseline and not
            // flaky against the small amount of real fragmentation secure_delete does not reach.
            val postMigrationText = rawFileAndWalText(dbFile)
            val survivingPrefixCount = allTokens.map { it.take(tokenPrefixLength) }.distinct().count { postMigrationText.contains(it) }
            assertTrue(
                survivingPrefixCount < rowCount / 2,
                "expected fewer than ${rowCount / 2} (N/2) distinct token-prefix survivors after the " +
                    "secure_delete scrub + WAL checkpoint; got $survivingPrefixCount"
            )
        }
}

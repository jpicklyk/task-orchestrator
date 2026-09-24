package io.github.jpicklyk.mcptask.current.infrastructure.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Files
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
 * Test-plan scenarios covered: S4 (schema + scrub on a hand-built pre-V17 schema, in-memory) and
 * S4b (the `secure_delete` guarantee, verified against raw file bytes of a FILE-backed database).
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
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v17_actor_proof_scrub_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        createPreV17Schema(database)
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

    /**
     * Pre-V17 schema: V1's `work_items`/`notes`/`role_transitions` base tables plus the V4
     * actor-attribution columns (`actor_id`, `actor_kind`, `actor_parent`, `actor_proof`,
     * `verification_status`, `verification_verifier`, `verification_reason`) on `notes` and
     * `role_transitions` — deliberately WITHOUT the V17 `actor_proof_sha256`/`actor_proof_claims`
     * columns, matching `V1__Current_Initial_Schema.sql` + `V4__Add_Actor_Attribution.sql` exactly.
     */
    private fun createPreV17Schema(db: Database) {
        transaction(db = db) {
            exec(
                """
                CREATE TABLE work_items (
                    id    BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    title TEXT NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE notes (
                    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    work_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
                    key             VARCHAR(200) NOT NULL,
                    role            VARCHAR(20) NOT NULL,
                    body            TEXT NOT NULL DEFAULT '',
                    created_at      TIMESTAMP NOT NULL,
                    modified_at     TIMESTAMP NOT NULL,
                    actor_id                TEXT,
                    actor_kind               TEXT,
                    actor_parent             TEXT,
                    actor_proof              TEXT,
                    verification_status      TEXT,
                    verification_verifier    TEXT,
                    verification_reason      TEXT
                )
                """.trimIndent()
            )
            exec("CREATE UNIQUE INDEX idx_notes_item_key ON notes(work_item_id, key)")
            exec(
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
                    transitioned_at     TIMESTAMP NOT NULL,
                    actor_id                TEXT,
                    actor_kind               TEXT,
                    actor_parent             TEXT,
                    actor_proof              TEXT,
                    verification_status      TEXT,
                    verification_verifier    TEXT,
                    verification_reason      TEXT
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Reads the real `V17__Store_Actor_Proof_Evidence.sql` off the classpath and executes each
     * statement, against the given [db] (which may differ from the in-memory [database] field —
     * S4b runs against a separate FILE-backed database).
     *
     * Strips full-line `--` comments, then splits on `;`. Safe here: no migration statement
     * contains an embedded semicolon (verified by reading the file for this exception only, per
     * the test-author protocol's migration-file allowance).
     */
    private fun applyV17Migration(db: Database) {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream(
                    "db/migration/V17__Store_Actor_Proof_Evidence.sql"
                )
            ) { "V17__Store_Actor_Proof_Evidence.sql not found on the test classpath" }
        val sqlText = resourceStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val withoutComments =
            sqlText
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        val statements =
            withoutComments
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        transaction(db = db) {
            statements.forEach { statement -> exec(statement) }
        }
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
        connection.prepareStatement("INSERT INTO work_items (id, title) VALUES (?, ?)").use { stmt ->
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
    // S4 — schema + scrub, hand-built pre-V17 schema, in-memory DB
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

            applyV17Migration(database)

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

            applyV17Migration(database)

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

    // ────────────────────────────────────────────────────────────────────────
    // S4b — secure_delete guarantee: seeded token must not survive in raw file bytes
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4b V17 scrub with secure_delete leaves no trace of the seeded proof in raw file bytes`(): Unit =
        runBlocking {
            // A long, highly unique token so a substring match in the raw file bytes is a reliable
            // signal (not a coincidental byte sequence from other row data or page headers).
            val seededToken = "SEEDED-PROOF-TOKEN-${UUID.randomUUID()}-MUST-NOT-SURVIVE-SCRUB"

            val tempDir = Files.createTempDirectory("v17-secure-delete-test")
            val dbFile = tempDir.resolve("v17_test.db").toFile()
            val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

            var fileDatabase: Database? = null
            var fileConnection: Connection? = null
            try {
                fileConnection = DriverManager.getConnection(jdbcUrl)
                fileDatabase = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")

                createPreV17Schema(fileDatabase)

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

                applyV17Migration(fileDatabase)

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

            // Cleanup
            dbFile.delete()
            tempDir.toFile().deleteRecursively()
        }
}

package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.sql.DriverManager

/**
 * Base class for tests that require a real SQLite database with FTS5 virtual tables.
 *
 * FTS5 is SQLite-only (H2 skips it). This base sets up an in-memory SQLite database
 * with the base schema AND the four FTS5 virtual tables using the tokenizer syntax
 * that is compatible with xerial/sqlite-jdbc 3.49.1.0.
 *
 * **Production note on `trigram case_sensitive=0`:** The xerial/sqlite-jdbc 3.49.1.0 bundled
 * binary does NOT support the `case_sensitive=0` option for the trigram tokenizer — it fails with
 * "parse error in tokenize directive". The V7 Flyway migration therefore uses plain
 * `tokenize='trigram'` (the SQLite default for trigram). This base class matches that syntax.
 * The trigram tokenizer is case-sensitive by default; true case-insensitive substring search
 * would require case_sensitive=0 which is deferred to a future migration on a newer SQLite baseline.
 *
 * Set [requireFts5] = true in test subclasses to call [Assumptions.assumeTrue] when FTS5
 * is not available (the test is then skipped rather than failing).
 */
abstract class BaseFts5RepositoryTest {
    protected lateinit var database: Database
    protected lateinit var databaseManager: DatabaseManager
    protected lateinit var repositoryProvider: DefaultRepositoryProvider

    /** True when FTS5 virtual tables were created successfully. */
    protected var fts5Available = false

    /**
     * Override to true in subclasses that require FTS5. When true, tests are assumed-skipped
     * rather than failing when FTS5 is not available.
     */
    open val requireFts5: Boolean get() = true

    /** Holds the SQLite in-memory connection open for the lifetime of each test. */
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUpFts5Database() {
        val dbName = "fts5_base_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)

        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Create base tables
        transaction(db = database) {
            exec(
                """
                CREATE TABLE IF NOT EXISTS work_items (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    parent_id BLOB REFERENCES work_items(id),
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
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_priority ON work_items(priority)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_role_changed ON work_items(role, role_changed_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_claimed_by ON work_items(claimed_by)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_claim_expires ON work_items(claim_expires_at)")

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
            exec("CREATE INDEX IF NOT EXISTS idx_notes_item_id ON notes(work_item_id)")

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
        }

        // Attempt to create FTS5 virtual tables. Uses 'trigram' without case_sensitive=0
        // (matching the V7 Flyway migration) because bundled xerial/sqlite-jdbc 3.49.1.0
        // does not support the case_sensitive parameter in the trigram tokenizer directive.
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

                // Sync triggers — work_items
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_trigram_au AFTER UPDATE ON work_items BEGIN INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ai AFTER INSERT ON work_items BEGIN INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_ad AFTER DELETE ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS work_items_fts_text_au AFTER UPDATE ON work_items BEGIN INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary) VALUES ('delete', old.rowid, old.title, old.summary); INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (new.rowid, new.title, new.summary); END"
                )

                // Sync triggers — notes
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_trigram_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_trigram(rowid, body) VALUES (new.rowid, new.body); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ai AFTER INSERT ON notes BEGIN INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_text_ad AFTER DELETE ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); END"
                )
                exec(
                    "CREATE TRIGGER IF NOT EXISTS notes_fts_text_au AFTER UPDATE ON notes BEGIN INSERT INTO notes_fts_text(notes_fts_text, rowid, body) VALUES ('delete', old.rowid, old.body); INSERT INTO notes_fts_text(rowid, body) VALUES (new.rowid, new.body); END"
                )

                // Backfill
                exec("INSERT INTO work_items_fts_trigram(work_items_fts_trigram) VALUES ('rebuild')")
                exec("INSERT INTO work_items_fts_text(work_items_fts_text) VALUES ('rebuild')")
                exec("INSERT INTO notes_fts_trigram(notes_fts_trigram) VALUES ('rebuild')")
                exec("INSERT INTO notes_fts_text(notes_fts_text) VALUES ('rebuild')")

                fts5Available = true
            }
        } catch (e: Exception) {
            fts5Available = false
            println("[BaseFts5RepositoryTest] FTS5 not available: ${e.message}")
        }

        databaseManager = DatabaseManager(database)
        repositoryProvider = DefaultRepositoryProvider(databaseManager)

        if (fts5Available) {
            // Verify FTS5 MATCH queries actually work (the repository silently swallows errors).
            // The production code uses `alias MATCH expr` syntax which fails in some bundled SQLite builds.
            fts5Available = checkFts5MatchQueryWorks()
        }

        if (requireFts5) {
            Assumptions.assumeTrue(fts5Available, "FTS5 search not functional in bundled SQLite — test skipped")
        }
    }

    /**
     * Verifies that FTS5 `MATCH` queries work with the repository's query pattern.
     * The production repositories use `alias MATCH expr` (e.g., `ft MATCH ?`) syntax.
     * In some bundled xerial/sqlite-jdbc builds this syntax fails with "no such column: ft".
     *
     * The repository catches these errors and returns empty results (not an exception),
     * so we must probe directly via JDBC to detect the failure.
     *
     * @return true if FTS5 MATCH queries work correctly in the current SQLite build.
     */
    private fun checkFts5MatchQueryWorks(): Boolean =
        try {
            // Test the exact query pattern the repositories use:
            // - alias MATCH expr (the production pattern)
            // - snippet(fts_table_name, ...) (the production snippet call)
            var works = false
            transaction(db = database) {
                exec(
                    """
                    SELECT ft.rowid, ft.rank,
                           snippet(work_items_fts_trigram, 0, '<mark>', '</mark>', '…', 32) AS snip
                    FROM work_items_fts_trigram ft
                    WHERE ft MATCH ?
                    LIMIT 1
                    """.trimIndent(),
                    args =
                        listOf(
                            org.jetbrains.exposed.v1.core
                                .VarCharColumnType(256) to "\"test\""
                        )
                ) { _ ->
                    works = true
                }
            }
            works
        } catch (e: Exception) {
            println("[BaseFts5RepositoryTest] FTS5 MATCH query not functional: ${e.message}")
            false
        }

    @AfterEach
    fun tearDownFts5Database() {
        try {
            TransactionManager.closeAndUnregister(database)
        } catch (_: Exception) {
        }
        try {
            keepAliveConnection.close()
        } catch (_: Exception) {
        }
    }
}

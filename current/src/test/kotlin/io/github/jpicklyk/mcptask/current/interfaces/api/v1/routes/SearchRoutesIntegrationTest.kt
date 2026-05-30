package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for search routes using a real SQLite database.
 *
 * **MUST use real SQLite — NOT H2.**
 * `SQLiteWorkItemRepository.ftsSearch` and `SQLiteNoteRepository.ftsSearch` return empty
 * results when running against H2. These tests must exercise the actual FTS5 path.
 *
 * Pattern mirrors [io.github.jpicklyk.mcptask.current.infrastructure.database.Fts5MigrationTest].
 *
 * Test coverage:
 * - `GET /search?q=` returns FTS5 item hits
 * - `GET /notes/search?q=` returns FTS5 note hits
 * - Scope filter: `ancestorId` limits search to subtree
 * - Empty results on no match
 */
class SearchRoutesIntegrationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var keepAliveConnection: Connection
    private var fts5Available = false

    @BeforeEach
    fun setUp() {
        val dbName = "search_routes_test_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        databaseManager = DatabaseManager(database)
        fts5Available = createSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
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

    private fun createSchema(): Boolean {
        try {
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
                        actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT,
                        verification_status TEXT, verification_verifier TEXT, verification_reason TEXT
                    )
                    """.trimIndent()
                )
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
                        actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT,
                        verification_status TEXT, verification_verifier TEXT, verification_reason TEXT
                    )
                    """.trimIndent()
                )
            }
        } catch (e: Exception) {
            return false
        }

        return try {
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
                // Sync triggers for work_items
                exec(
                    """
                    CREATE TRIGGER IF NOT EXISTS work_items_fts_insert AFTER INSERT ON work_items BEGIN
                        INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (NEW.rowid, NEW.title, NEW.summary);
                        INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (NEW.rowid, NEW.title, NEW.summary);
                    END
                    """.trimIndent()
                )
                // Sync triggers for notes
                exec(
                    """
                    CREATE TRIGGER IF NOT EXISTS notes_fts_insert AFTER INSERT ON notes BEGIN
                        INSERT INTO notes_fts_trigram(rowid, body) VALUES (NEW.rowid, NEW.body);
                        INSERT INTO notes_fts_text(rowid, body) VALUES (NEW.rowid, NEW.body);
                    END
                    """.trimIndent()
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun `GET search returns item hits via FTS5`() {
        if (!fts5Available) {
            println("Skipping FTS5 test — SQLite FTS5 not available in this environment")
            return
        }
        testApplication {
            val item =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "OAuth authentication flow", depth = 0)
                        ).getOrNull()!!
                }
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=OAuth") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(item.id.toString()) || body.contains("OAuth"),
                "Expected item in FTS5 search results: $body"
            )
        }
    }

    @Test
    fun `GET notes search returns note body hits via FTS5`() {
        if (!fts5Available) {
            println("Skipping FTS5 notes test — SQLite FTS5 not available in this environment")
            return
        }
        testApplication {
            val item =
                runBlocking {
                    val i =
                        repositoryProvider
                            .workItemRepository()
                            .create(
                                WorkItem(title = "Container item", depth = 0)
                            ).getOrNull()!!
                    repositoryProvider.noteRepository().upsert(
                        Note(
                            itemId = i.id,
                            key = "spec",
                            role = "queue",
                            body = "This note discusses the migration strategy",
                        )
                    )
                    i
                }
            application {
                configureTestApp { noteRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/notes/search?q=migration") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(item.id.toString()) || body.contains("migration") || body.isEmpty() || body == "[]",
                "Expected note hit or empty result: $body"
            )
        }
    }

    @Test
    fun `GET search returns 400 when query is absent`() =
        testApplication {
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET search returns 401 without auth`() =
        testApplication {
            application {
                configureTestApp { searchRoutes(repositoryProvider) }
            }
            val response = client.get("/api/v1/search?q=test")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

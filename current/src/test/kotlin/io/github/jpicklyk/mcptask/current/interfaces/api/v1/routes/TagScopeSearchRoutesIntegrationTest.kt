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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent regression coverage for the `tags_include` scope gap on the FTS5-backed search
 * routes: `GET /search` (S4) and `GET /notes/search` (S5).
 *
 * Authored per the item `ffa12a2f-8028-4a46-8882-3f84fa629ee9` `test-plan` note (oracle O1:
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope] KDoc -- `tagsInclude`
 * applies to the item itself; a tag-scoped principal must never receive a search hit for an
 * item outside its tag allowlist).
 *
 * **MUST use real SQLite -- NOT H2.** `SQLiteWorkItemRepository.ftsSearch` and
 * `SQLiteNoteRepository.ftsSearch` return empty results against H2; this class mirrors the
 * schema/harness setup of [SearchRoutesIntegrationTest] exactly so the FTS5 path is actually
 * exercised (`fts5Available` guard: skip rather than false-pass when FTS5 is unavailable in
 * the CI environment).
 */
class TagScopeSearchRoutesIntegrationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var keepAliveConnection: Connection
    private var fts5Available = false

    @BeforeEach
    fun setUp() {
        val dbName = "tag_scope_search_routes_test_${System.nanoTime()}"
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
                        root_id BLOB,
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
                        actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT, actor_proof_sha256 TEXT, actor_proof_claims TEXT,
                        verification_status TEXT, verification_verifier TEXT, verification_reason TEXT
                    )
                    """.trimIndent()
                )
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_notes_item_key ON notes(work_item_id, key)")
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
                        actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT, actor_proof_sha256 TEXT, actor_proof_claims TEXT,
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
                exec(
                    """
                    CREATE TRIGGER IF NOT EXISTS work_items_fts_insert AFTER INSERT ON work_items BEGIN
                        INSERT INTO work_items_fts_trigram(rowid, title, summary) VALUES (NEW.rowid, NEW.title, NEW.summary);
                        INSERT INTO work_items_fts_text(rowid, title, summary) VALUES (NEW.rowid, NEW.title, NEW.summary);
                    END
                    """.trimIndent()
                )
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

    /**
     * S4: `GET /search` -- a tags_include-scoped principal must receive hits only for the
     * allow-listed tag, even though both items match the FTS5 query term.
     */
    @Test
    fun `S4 GET search with tagsInclude alpha excludes beta-tagged item's hit`() {
        if (!fts5Available) {
            println("Skipping S4 -- SQLite FTS5 not available in this environment")
            return
        }
        testApplication {
            val (itemAlpha, itemBeta) =
                runBlocking {
                    val a =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "TagScopeUniqueTerm741 Alpha", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "TagScopeUniqueTerm741 Beta", tags = "beta", depth = 0))
                            .getOrNull()!!
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { searchRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/search?q=TagScopeUniqueTerm741") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Hard positive assertion first -- without it the exclusion check below would be
            // vacuous (a broken/empty search would trivially "exclude" everything).
            assertTrue(
                body.contains(itemAlpha.id.toString()),
                "Expected alpha-tagged item's hit in scoped search results: $body",
            )
            assertFalse(
                body.contains(itemBeta.id.toString()),
                "Beta-tagged item's hit must be excluded from an alpha-scoped search: $body",
            )
        }
    }

    /**
     * S5: `GET /notes/search` -- same tag-scope exclusion, but for a note hit keyed by its
     * parent item's tags rather than the item's own row.
     */
    @Test
    fun `S5 GET notes search with tagsInclude alpha excludes beta-tagged item's note hit`() {
        if (!fts5Available) {
            println("Skipping S5 -- SQLite FTS5 not available in this environment")
            return
        }
        testApplication {
            val (itemAlpha, itemBeta) =
                runBlocking {
                    val a =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "AlphaNoteContainer", tags = "alpha", depth = 0))
                            .getOrNull()!!
                    val b =
                        repositoryProvider
                            .workItemRepository()
                            .create(WorkItem(title = "BetaNoteContainer", tags = "beta", depth = 0))
                            .getOrNull()!!
                    repositoryProvider.noteRepository().upsert(
                        Note(itemId = a.id, key = "spec", role = "queue", body = "TagScopeNoteTerm852 in alpha item")
                    )
                    repositoryProvider.noteRepository().upsert(
                        Note(itemId = b.id, key = "spec", role = "queue", body = "TagScopeNoteTerm852 in beta item")
                    )
                    a to b
                }
            val authConfig = makeTestAuthConfig(tagsInclude = setOf("alpha"))
            application {
                configureTestApp(authConfig) { noteRoutes(repositoryProvider) }
            }
            val response =
                client.get("/api/v1/notes/search?q=TagScopeNoteTerm852") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains(itemAlpha.id.toString()),
                "Expected alpha item's note hit in scoped notes search results: $body",
            )
            assertFalse(
                body.contains(itemBeta.id.toString()),
                "Beta item's note hit must be excluded from an alpha-scoped notes search: $body",
            )
        }
    }
}

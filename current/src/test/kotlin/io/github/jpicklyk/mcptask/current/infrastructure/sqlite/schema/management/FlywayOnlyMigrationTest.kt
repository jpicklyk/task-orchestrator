package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.exec
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.insertItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.insertNote
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.migrated
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.newUrl
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.objectNames
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.query
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.tableExists
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.userTableCount
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent Flyway-only schema-path tests for item ba942a00 (P2a).
 *
 * Oracles: plan v4-phase1-core 3.10 (baseline-at-17 only on an exact V17 shape; histories validate
 * unchanged; missing-locally tolerated; missing FTS5 is a hard failure; Direct DBs refused with
 * remedy), AR-37 rec 1 (a history ahead of the binary fails closed), AR-86 rec 1 (Flyway's own
 * connection carries the configured busy_timeout and foreign_keys OFF), and the migration SQL under
 * db/migration/sqlite (authoritative DDL; the expected trigger/FTS inventory is derived from it).
 */
class FlywayOnlyMigrationTest {
    @TempDir
    lateinit var dir: Path

    private val registered = mutableListOf<Database>()

    @AfterEach
    fun teardown() {
        registered.forEach { TransactionManager.closeAndUnregister(it) }
        registered.clear()
    }

    private fun manager(
        url: String,
        repair: Boolean = false,
        mode: SchemaMode = SchemaMode.MIGRATE
    ) = FlywayDatabaseSchemaManager(url, repair = repair, schemaMode = mode)

    private fun errorsOf(block: () -> Unit): List<String> = captureLogs(block).at(Level.ERROR)

    private fun historyRows(url: String): List<Triple<String?, String, Boolean>> =
        query(url, "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank") {
            Triple(it.getString(1), it.getString(2), it.getInt(3) == 1)
        }

    private fun directDb(): String {
        val url = newUrl(dir)
        registered += Database.connect(url = url, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        assertTrue(DirectDatabaseSchemaManager(registered.last()).updateSchema(), "fixture: test bridge must build the schema")
        insertItem(url, UUID.randomUUID(), title = "direct-row")
        return url
    }

    private fun pragmaInt(
        c: Connection,
        pragma: String
    ): Int =
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA $pragma").use {
                it.next()
                it.getInt(1)
            }
        }

    // ---- S3: history dropped from an exact V17 database -> baseline at 17, data untouched ----

    @Test
    fun `S3 exact V17 database without history is baselined at 17 and data is unchanged`() {
        val url = migrated(dir)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertItem(url, a, title = "alpha")
        insertItem(url, b, parent = a, depth = 1, rootId = a, title = "beta")
        insertNote(url, a, "k", "zebra body")
        val before = query(url, "SELECT title FROM work_items ORDER BY title") { it.getString(1) }
        exec(url, "DROP TABLE flyway_schema_history")
        assertFalse(tableExists(url, "flyway_schema_history"), "fixture: history must be gone")

        assertTrue(manager(url).updateSchema(), "an exact V17 shape must be baselined, not refused")

        val rows = historyRows(url)
        // Baselined at 17, then the pending V18 (data-only timestamp normalization) is applied on top.
        assertEquals(
            listOf("17" to "BASELINE", "18" to "SQL"),
            rows.map {
                it.first to it.second
            },
            "history must be baseline@17 then V18, got $rows"
        )
        assertTrue(rows.all { it.third })
        assertEquals(before, query(url, "SELECT title FROM work_items ORDER BY title") { it.getString(1) })
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM notes"))
    }

    // ---- S9: one trigger missing -> not exact -> refused, not baselined ----

    @Test
    fun `S9 history-less database missing one trigger is refused and not baselined`() {
        val url = migrated(dir)
        insertItem(url, UUID.randomUUID(), title = "keep")
        exec(url, "DROP TABLE flyway_schema_history", "DROP TRIGGER work_items_cycle_check_update")

        val errors = errorsOf { assertFalse(manager(url).updateSchema(), "a non-exact shape must be refused") }

        assertFalse(tableExists(url, "flyway_schema_history"), "refusal must not create a history table")
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM work_items"), "rows must be untouched")
        assertTrue(errors.isNotEmpty(), "refusal must log an ERROR")
        assertTrue(
            errors.any { it.contains("work_items_cycle_check_update") },
            "ERROR must name the differing object: $errors"
        )
    }

    // ---- S8: a database built by the test bridge is refused with the remedy ----

    @Test
    fun `S8 database created by the Direct bridge is refused, untouched, with the remedy named`() {
        val url = directDb()
        val errors = errorsOf { assertFalse(manager(url).updateSchema()) }

        assertTrue(errors.any { it.contains("DATABASE_PATH") }, "ERROR must name the remedy (DATABASE_PATH): $errors")
        assertFalse(tableExists(url, "flyway_schema_history"), "no history table may appear")
        assertTrue(tableExists(url, "work_items"))
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM work_items"))
    }

    @Test
    fun `S8 refusal is repeatable and repair mode refuses the same database`() {
        val url = directDb()
        errorsOf { assertFalse(manager(url).updateSchema()) }
        errorsOf { assertFalse(manager(url).updateSchema()) }
        errorsOf { assertFalse(manager(url, repair = true).updateSchema()) }
        assertFalse(tableExists(url, "flyway_schema_history"))
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM work_items"))
    }

    @Test
    fun `S8 database with a stub work_items table lacking columns is refused`() {
        val url = newUrl(dir)
        exec(url, "CREATE TABLE work_items (id BLOB PRIMARY KEY, title TEXT NOT NULL)")
        val errors = errorsOf { assertFalse(manager(url).updateSchema()) }
        assertTrue(errors.any { it.contains("DATABASE_PATH") }, "ERROR must name the remedy: $errors")
        assertFalse(tableExists(url, "flyway_schema_history"))
    }

    // ---- S10: history ahead of this binary fails closed (AR-37 rec 1) ----

    private fun insertHistory(
        url: String,
        version: String,
        success: Int
    ) = exec(
        url,
        "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, " +
            "checksum, installed_by, installed_on, execution_time, success) " +
            "VALUES ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '$version', 'x', 'SQL', " +
            "'V${version.replace('.', '_')}__x.sql', 0, 'test', '2026-01-01 00:00:00', 1, $success)"
    )

    @Test
    fun `S10 successful future version 99 in history fails closed`() {
        val url = migrated(dir)
        insertHistory(url, "99", 1)
        val errors = errorsOf { assertFalse(manager(url).updateSchema(), "a database ahead of this binary must be refused") }
        assertTrue(errors.isNotEmpty(), "failure must log an ERROR")
        assertTrue(errors.any { it.contains("99") }, "ERROR must identify the offending version 99: $errors")
    }

    @Test
    fun `S10 probe failed version 99 row also fails`() {
        val url = migrated(dir)
        insertHistory(url, "99", 0)
        errorsOf { assertFalse(manager(url).updateSchema()) }
    }

    // ---- S15: applied version with no local file below the latest is tolerated ----

    @Test
    fun `S15 history row 5_5 with no local migration still starts`() {
        val url = migrated(dir)
        insertHistory(url, "5.5", 1)
        assertTrue(manager(url).updateSchema(), "missing-locally must be tolerated below the latest version")
    }

    // ---- S11 (manager level): VALIDATE never migrates or baselines ----

    @Test
    fun `S11 validate on an empty database fails and creates no tables`() {
        val url = newUrl(dir)
        assertFalse(manager(url, mode = SchemaMode.VALIDATE).updateSchema())
        assertEquals(0, userTableCount(url), "validate must create nothing")
    }

    @Test
    fun `S11 validate passes on a current database`() {
        val url = migrated(dir)
        assertTrue(manager(url, mode = SchemaMode.VALIDATE).updateSchema())
    }

    @Test
    fun `S11 validate fails on a database with a pending migration and does not apply it`() {
        val url = newUrl(dir)
        manager(url).flywayConfiguration(target = "16").load().migrate()
        val before = scalarInt(url, "SELECT count(*) FROM flyway_schema_history")
        assertFalse(manager(url, mode = SchemaMode.VALIDATE).updateSchema(), "pending V17 must fail validate")
        assertEquals(before, scalarInt(url, "SELECT count(*) FROM flyway_schema_history"), "validate must not migrate")
    }

    @Test
    fun `S11 validate refuses a history-less current database and does not baseline it`() {
        val url = migrated(dir)
        exec(url, "DROP TABLE flyway_schema_history")
        assertFalse(manager(url, mode = SchemaMode.VALIDATE).updateSchema())
        assertFalse(tableExists(url, "flyway_schema_history"), "validate must never baseline")
    }

    // ---- S12: missing FTS5 objects are a hard failure naming the object ----

    @Test
    fun `S12 dropped FTS sync trigger fails startup, names it, and is not recreated`() {
        val url = migrated(dir)
        exec(url, "DROP TRIGGER notes_fts_text_ai")
        val errors = errorsOf { assertFalse(manager(url).updateSchema()) }
        assertTrue(errors.any { it.contains("notes_fts_text_ai") }, "ERROR must name the missing trigger: $errors")
        assertFalse("notes_fts_text_ai" in objectNames(url, "trigger"), "nothing may be re-created")
    }

    @Test
    fun `S12 dropped FTS virtual table fails startup and names it`() {
        val url = migrated(dir)
        exec(url, "DROP TABLE notes_fts_text")
        val errors = errorsOf { assertFalse(manager(url).updateSchema()) }
        assertTrue(errors.any { it.contains("notes_fts_text") }, "ERROR must name the missing FTS table: $errors")
        assertFalse("notes_fts_text" in objectNames(url, "table"), "nothing may be re-created")
    }

    // ---- S5: Flyway's own connection (AR-86 rec 1) ----

    @Test
    fun `S5 Flyway data source connection has foreign_keys off and the configured busy_timeout`() {
        val url = newUrl(dir)
        val cfg = FlywayDatabaseSchemaManager(url, repair = false, busyTimeoutMs = 7777L).flywayConfiguration()
        cfg.dataSource.connection.use { c ->
            assertEquals(
                7777,
                pragmaInt(c, "busy_timeout"),
                "busy_timeout must equal DATABASE_BUSY_TIMEOUT_MS, not the driver default 3000"
            )
            assertEquals(0, pragmaInt(c, "foreign_keys"), "foreign_keys must be OFF on the migration connection")
        }
    }

    @Test
    fun `S5 default busy timeout is 5000 ms on the Flyway connection`() {
        val cfg = FlywayDatabaseSchemaManager(newUrl(dir), repair = false).flywayConfiguration()
        cfg.dataSource.connection.use { c ->
            assertEquals(5000, pragmaInt(c, "busy_timeout"))
        }
    }

    @Test
    fun `S5 V7 table recreation does not cascade-delete child rows of a populated V6 database`() {
        // V7 drops and recreates work_items; with foreign_keys ON that DROP would delete notes.
        val url = newUrl(dir)
        manager(url).flywayConfiguration(target = "6").load().migrate()
        val id = UUID.randomUUID()
        withConn(url) { c ->
            c
                .prepareStatement(
                    "INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at) " +
                        "VALUES (?, 't', '2026-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-01 00:00:00')"
                ).use { ps ->
                    ps.setBytes(1, SchemaTestSupport.uuidBytes(id))
                    ps.executeUpdate()
                }
            c
                .prepareStatement(
                    "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) " +
                        "VALUES (?, ?, 'k', 'queue', 'b', '2026-01-01 00:00:00', '2026-01-01 00:00:00')"
                ).use { ps ->
                    ps.setBytes(1, SchemaTestSupport.uuidBytes(UUID.randomUUID()))
                    ps.setBytes(2, SchemaTestSupport.uuidBytes(id))
                    ps.executeUpdate()
                }
        }

        assertTrue(manager(url).updateSchema())

        assertEquals(1, scalarInt(url, "SELECT count(*) FROM work_items"))
        assertEquals(1, scalarInt(url, "SELECT count(*) FROM notes"), "child note must survive the V7 recreation")
    }

    // ---- Flyway configuration contract (declaration KDoc) ----

    @Test
    fun `configuration keeps clean disabled, no baselineOnMigrate and the single sqlite location`() {
        val cfg = FlywayDatabaseSchemaManager("jdbc:sqlite:unused", repair = false).flywayConfiguration()
        assertTrue(cfg.isCleanDisabled)
        assertFalse(cfg.isBaselineOnMigrate)
        assertEquals("classpath:db/migration/sqlite", FlywayDatabaseSchemaManager.MIGRATION_LOCATION)
        assertEquals(listOf("classpath:db/migration/sqlite"), cfg.locations.map { it.descriptor })
        assertEquals("flyway_schema_history", FlywayDatabaseSchemaManager.HISTORY_TABLE)
    }

    @Test
    fun `fresh migrate then repeat migrate and repair all succeed`() {
        val url = migrated(dir)
        assertTrue(manager(url).updateSchema())
        assertTrue(manager(url, repair = true).updateSchema())
    }
}

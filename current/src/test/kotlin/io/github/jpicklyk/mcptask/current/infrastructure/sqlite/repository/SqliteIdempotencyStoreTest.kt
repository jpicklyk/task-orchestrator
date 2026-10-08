package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.Fingerprint
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) for [SqliteIdempotencyStore] and the V19 table.
 *
 * Oracles: the IdempotencyStore port contract in the dispatch declarations (find / upsert = insert or replace /
 * insertIfAbsent = Boolean / deleteExpired(cutoff) = Int), the V19 DDL in migration-assessment (composite primary key
 * (principal_id, operation, key); CHECK length(fingerprint) = 64; CHECK length(created_at) = 23; index on created_at)
 * and the canonical persisted time form `yyyy-MM-dd HH:mm:ss.SSS` in UTC with millisecond truncation (task-scope
 * section 7). SQLite TEXT keys compare with the default BINARY collation (SQLite documentation, datatype3).
 *
 * Every store call runs inside a unit (production OutsideUnitPolicy is FAIL), so the harness is used in its
 * production-like mode where it matters.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteIdempotencyStoreTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val uow: UnitOfWork get() = sqlite.unitOfWork()
    private val t0: Instant = Instant.parse("2026-03-01T10:15:30.123Z")

    private fun fp(text: String): String = Fingerprint.of(JsonPrimitive(text))

    private fun record(
        key: String = "k1",
        op: String = "mcp.test",
        principal: String = "agent-1",
        fingerprint: String = fp("a"),
        resultJson: String = """{"v":1,"ok":true,"value":"r1"}""",
        createdAt: Instant = t0
    ) = IdempotencyRecord(principal, op, key, fingerprint, resultJson, createdAt)

    private suspend fun upsert(r: IdempotencyRecord) {
        uow.write("test.upsert") {
            stores.idempotencyStore().upsert(r)
            Outcome.Ok(Unit)
        }
    }

    private suspend fun insertIfAbsent(r: IdempotencyRecord): Boolean =
        (
            uow.write("test.insertIfAbsent") {
                Outcome.Ok(stores.idempotencyStore().insertIfAbsent(r))
            } as Outcome.Ok<Boolean>
        ).value

    private suspend fun find(
        key: String = "k1",
        op: String = "mcp.test",
        principal: String = "agent-1"
    ): IdempotencyRecord? = uow.read { stores.idempotencyStore().find(principal, op, key) }

    private suspend fun deleteExpired(cutoff: Instant): Int =
        (
            uow.write("test.deleteExpired") {
                Outcome.Ok(stores.idempotencyStore().deleteExpired(cutoff))
            } as Outcome.Ok<Int>
        ).value

    private fun rawQuery(sql: String): List<Map<String, String?>> =
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val names = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    buildList { while (rs.next()) add(names.associateWith { rs.getString(it) }) }
                }
            }
        }

    // ---------------------------------------------------------------- port contract

    @Test
    fun `find returns the upserted record unchanged`() =
        runBlocking {
            val r = record()
            upsert(r)
            assertEquals(r, find())
        }

    @Test
    fun `find returns null when nothing matches principal operation or key`() =
        runBlocking {
            upsert(record())
            assertNull(find(key = "other"))
            assertNull(find(op = "mcp.other"))
            assertNull(find(principal = "agent-2"))
        }

    @Test
    fun `the primary key is principal plus operation plus key - three records coexist`() =
        runBlocking {
            upsert(record(key = "k1", fingerprint = fp("a")))
            upsert(record(key = "k1", op = "mcp.other", fingerprint = fp("b")))
            upsert(record(key = "k1", principal = "agent-2", fingerprint = fp("c")))
            assertEquals(3, sqlite.idempotencyRecordCount())
            assertEquals(fp("b"), find(op = "mcp.other")?.fingerprint)
            assertEquals(fp("c"), find(principal = "agent-2")?.fingerprint)
        }

    @Test
    fun `upsert on an existing key replaces the row`() =
        runBlocking {
            upsert(record(fingerprint = fp("a"), resultJson = """{"v":1,"ok":true,"value":"old"}""", createdAt = t0))
            val newer = record(fingerprint = fp("b"), resultJson = """{"v":1,"ok":true,"value":"new"}""", createdAt = t0.plusSeconds(90000))
            upsert(newer)
            assertEquals(1, sqlite.idempotencyRecordCount())
            assertEquals(newer, find())
        }

    @Test
    fun `insertIfAbsent inserts once and never overwrites`() =
        runBlocking {
            val first = record(resultJson = """{"v":1,"ok":true,"value":"first"}""")
            assertTrue(insertIfAbsent(first), "an absent key is inserted")
            assertFalse(
                insertIfAbsent(record(resultJson = """{"v":1,"ok":true,"value":"second"}""", fingerprint = fp("b"))),
                "a present key is not"
            )
            assertEquals(first, find())
            assertEquals(1, sqlite.idempotencyRecordCount())
        }

    @Test
    fun `deleteExpired deletes rows at or below the cutoff and returns how many`() =
        runBlocking {
            upsert(record(key = "below", createdAt = t0.minusMillis(1)))
            upsert(record(key = "at", createdAt = t0))
            upsert(record(key = "above", createdAt = t0.plusMillis(1)))

            assertEquals(2, deleteExpired(t0))

            assertNull(find(key = "below"))
            assertNull(find(key = "at"))
            assertNotNull(find(key = "above"))
            assertEquals(0, deleteExpired(t0), "nothing left at or below the cutoff")
        }

    @Test
    fun `keys differing only by case are distinct`() =
        runBlocking {
            upsert(record(key = "Key"))
            assertNull(find(key = "key"))
            assertNotNull(find(key = "Key"))
        }

    @Test
    fun `unicode keys principals and results round-trip`() =
        runBlocking {
            val key = "k" + 0xE9.toChar() + String(Character.toChars(0x1F600))
            val r = record(key = key, principal = "p" + 0xFF5E.toChar(), resultJson = """{"v":1,"ok":true,"value":"${0xE9.toChar()}"}""")
            upsert(r)
            assertEquals(r, find(key = key, principal = r.principalId))
        }

    // ---------------------------------------------------------------- persisted time form

    @Test
    fun `created_at is stored as 23 character UTC text with millisecond truncation`() =
        runBlocking {
            upsert(record(createdAt = Instant.parse("2026-03-01T10:15:30.123456789Z")))
            val text = rawQuery("SELECT created_at FROM idempotency_records").single()["created_at"]
            assertEquals("2026-03-01 10:15:30.123", text)
            assertEquals(Instant.parse("2026-03-01T10:15:30.123Z"), find()?.createdAt, "reads back at millisecond precision")
        }

    @Test
    fun `created_at text is UTC under a non-UTC JVM default zone`() =
        runBlocking {
            val original = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
                upsert(record(key = "tz", createdAt = Instant.parse("2026-03-01T23:30:00.000Z")))
                val text = rawQuery("SELECT created_at FROM idempotency_records WHERE key = 'tz'").single()["created_at"]
                assertEquals("2026-03-01 23:30:00.000", text, "a +05:30 zone must not shift the stored text")
                assertEquals(Instant.parse("2026-03-01T23:30:00.000Z"), find(key = "tz")?.createdAt)
            } finally {
                TimeZone.setDefault(original)
            }
        }

    @Test
    fun `text order equals time order so deleteExpired compares correctly across a day boundary`() =
        runBlocking {
            upsert(record(key = "late", createdAt = Instant.parse("2026-03-01T23:59:59.999Z")))
            upsert(record(key = "next", createdAt = Instant.parse("2026-03-02T00:00:00.000Z")))
            assertEquals(1, deleteExpired(Instant.parse("2026-03-01T23:59:59.999Z")))
            assertNull(find(key = "late"))
            assertNotNull(find(key = "next"))
        }

    // ---------------------------------------------------------------- V19 table shape (S18)

    @Test
    fun `S18 the table has exactly the six declared columns with the primary key order principal operation key`() {
        val columns = rawQuery("PRAGMA table_info(idempotency_records)")
        assertEquals(
            listOf("principal_id", "operation", "key", "fingerprint", "result_json", "created_at"),
            columns.map { it["name"] }
        )
        assertEquals(
            listOf("principal_id", "operation", "key"),
            columns.filter { it["pk"] != "0" }.sortedBy { it["pk"]!!.toInt() }.map { it["name"] }
        )
        assertTrue(columns.all { it["notnull"] == "1" }, "every column is NOT NULL")
        assertTrue(columns.all { it["type"] == "TEXT" }, "every column is TEXT")
    }

    @Test
    fun `S18 the created_at index exists`() {
        val names = rawQuery("PRAGMA index_list(idempotency_records)").map { it["name"] }
        assertTrue("idx_idempotency_records_created_at" in names, "indexes: $names")
        val indexed = rawQuery("PRAGMA index_info(idx_idempotency_records_created_at)").map { it["name"] }
        assertEquals(listOf("created_at"), indexed)
    }

    private fun rawInsert(
        key: String,
        fingerprint: String,
        createdAt: String
    ) {
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c
                .prepareStatement(
                    "INSERT INTO idempotency_records (principal_id, operation, key, fingerprint, result_json, created_at) VALUES ('p', 'o', ?, ?, '{}', ?)"
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, fingerprint)
                    ps.setString(3, createdAt)
                    ps.executeUpdate()
                }
        }
    }

    @Test
    fun `S18 the table constraints accept a valid row and reject a short fingerprint a short created_at and a duplicate key`() {
        val goodFp = "a".repeat(64)
        val goodTs = "2026-03-01 10:15:30.123"
        rawInsert("ok", goodFp, goodTs) // control: the valid shape is accepted
        assertEquals(1, sqlite.idempotencyRecordCount())

        assertFailsWith<SQLException> { rawInsert("fp63", "a".repeat(63), goodTs) }
        assertFailsWith<SQLException> { rawInsert("fp65", "a".repeat(65), goodTs) }
        assertFailsWith<SQLException> { rawInsert("ts20", goodFp, "2026-03-01T10:15:30Z") }
        assertFailsWith<SQLException> { rawInsert("ts24", goodFp, "2026-03-01 10:15:30.1234") }
        assertFailsWith<SQLException> { rawInsert("ok", goodFp, goodTs) }
        assertEquals(1, sqlite.idempotencyRecordCount(), "no rejected row was stored")
    }
}

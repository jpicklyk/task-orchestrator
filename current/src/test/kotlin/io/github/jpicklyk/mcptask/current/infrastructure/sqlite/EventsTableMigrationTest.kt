package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independent tests of the V20 `events` table and its store (item ea2b9b63, test-plan S1 and the store half of S12/S13).
 *
 * Oracles: the migration-assessment note and plan section 3.7 (columns, nullability, the four indexes, a floor of
 * 1_000_000_000_000 seeded into sqlite_sequence so the first seq is floor + 1, AUTOINCREMENT so a seq is never reused,
 * no foreign key so rows outlive deleted items, a 23-character canonical UTC `occurred_at`, `root_id` never null) and the
 * declared [EventStore] contract (append returns the records with their seqs; readAfter is exclusive, ascending,
 * optionally root-filtered and limited; maxSeq is the newest seq). Raw SQL is used for the constraint probes so that no
 * production writer can mask a missing constraint.
 *
 * NOT covered here: upgrading a populated V19 database to V20 (the seeded upgrade harness `UpgradeHarnessTest` with
 * `SeedV20` is the existing, implementer-owned coverage of that path).
 */
class EventsTableMigrationTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val floor = 1_000_000_000_000L

    private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(db.jdbcUrl).use(block)

    private fun bytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun long(query: String): Long =
        sql { c ->
            c.createStatement().use { st ->
                st.executeQuery(query).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** Inserts one row with raw SQL; every argument can be broken by the caller to probe a constraint. */
    private fun rawInsert(
        id: UUID? = UUID.randomUUID(),
        occurredAt: String? = "2026-03-04T05:06:07.123",
        rootId: UUID? = UUID.randomUUID(),
        entityKind: String? = "item",
        entityId: UUID? = UUID.randomUUID(),
        type: String? = "item.created",
        data: String? = "{}",
    ) = sql { c ->
        c
            .prepareStatement(
                "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setBytes(1, id?.let(::bytes))
                ps.setString(2, occurredAt)
                ps.setBytes(3, rootId?.let(::bytes))
                ps.setString(4, entityKind)
                ps.setBytes(5, entityId?.let(::bytes))
                ps.setString(6, type)
                ps.setString(7, data)
                ps.executeUpdate()
            }
    }

    private fun record(
        rootId: UUID = UUID.randomUUID(),
        type: String = "item.updated",
        at: Instant = Instant.parse("2026-03-04T05:06:07.123Z"),
    ) = EventRecord(
        id = UUID.randomUUID(),
        occurredAt = at,
        rootId = rootId,
        entityKind = "item",
        entityId = UUID.randomUUID(),
        type = type,
        data = "{}",
    )

    // ---------------------------------------------------------------------------------------------
    // S1 -- schema
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 events starts empty with exactly the documented columns and nullability`() {
        assertEquals(0L, long("SELECT count(*) FROM events"))
        val columns = linkedMapOf<String, Boolean>() // name -> NOT NULL
        sql { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(events)").use { rs ->
                    while (rs.next()) columns[rs.getString("name")] = rs.getInt("notnull") == 1
                }
            }
        }
        assertEquals(
            listOf(
                "seq",
                "id",
                "occurred_at",
                "root_id",
                "entity_kind",
                "entity_id",
                "type",
                "req_id",
                "principal_id",
                "principal_kind",
                "proof_status",
                "host",
                "session_id",
                "run_id",
                "seat",
                "data",
            ),
            columns.keys.toList(),
        )
        val required = listOf("id", "occurred_at", "root_id", "entity_kind", "entity_id", "type", "data")
        val optional = listOf("req_id", "principal_id", "principal_kind", "proof_status", "host", "session_id", "run_id", "seat")
        required.forEach { assertTrue(columns.getValue(it), "$it must be NOT NULL") }
        optional.forEach { assertTrue(!columns.getValue(it), "$it must be nullable") }
    }

    @Test
    fun `S1 the four documented indexes exist`() {
        val indexes = mutableSetOf<String>()
        sql { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(events)").use { rs ->
                    while (rs.next()) indexes += rs.getString("name")
                }
            }
        }
        assertTrue(
            indexes.containsAll(setOf("idx_events_root_seq", "idx_events_entity_seq", "idx_events_type_occurred", "idx_events_req_id")),
            "indexes: $indexes",
        )
    }

    @Test
    fun `S1 the sequence is seeded at the floor so the first seq is floor plus one and a seq is never reused`() {
        assertEquals(floor, EventStore.SEQ_FLOOR, "the declared floor constant is 1e12")
        assertEquals(floor, long("SELECT seq FROM sqlite_sequence WHERE name = 'events'"))

        rawInsert()
        assertEquals(floor + 1, long("SELECT max(seq) FROM events"), "the first row gets floor + 1")
        sql { c -> c.createStatement().use { it.executeUpdate("DELETE FROM events") } }
        rawInsert()
        assertEquals(floor + 2, long("SELECT max(seq) FROM events"), "AUTOINCREMENT: a deleted seq is not reused")
    }

    @Test
    fun `S1 root_id is never null and the other required columns reject null`() {
        assertFailsWith<SQLException> { rawInsert(rootId = null) }
        assertFailsWith<SQLException> { rawInsert(id = null) }
        assertFailsWith<SQLException> { rawInsert(occurredAt = null) }
        assertFailsWith<SQLException> { rawInsert(entityKind = null) }
        assertFailsWith<SQLException> { rawInsert(entityId = null) }
        assertFailsWith<SQLException> { rawInsert(type = null) }
        assertFailsWith<SQLException> { rawInsert(data = null) }
        assertEquals(0L, long("SELECT count(*) FROM events"), "no rejected insert left a row")
        rawInsert() // control: the same helper with valid arguments inserts
        assertEquals(1L, long("SELECT count(*) FROM events"))
    }

    @Test
    fun `S1 occurred_at must be exactly 23 characters and id is unique`() {
        assertFailsWith<SQLException> { rawInsert(occurredAt = "2026-03-04T05:06:07.12") } // 22
        assertFailsWith<SQLException> { rawInsert(occurredAt = "2026-03-04T05:06:07.1234") } // 24
        assertEquals(0L, long("SELECT count(*) FROM events"))
        val id = UUID.randomUUID()
        rawInsert(id = id)
        assertFailsWith<SQLException> { rawInsert(id = id) }
        assertEquals(1L, long("SELECT count(*) FROM events"))
    }

    @Test
    fun `S1 a row outlives the entity it names because there is no foreign key`() {
        rawInsert(entityId = UUID.randomUUID(), rootId = UUID.randomUUID())
        assertEquals(1L, long("SELECT count(*) FROM events"), "no work item exists for the row's entity or root")
        val fks = mutableListOf<String>()
        sql { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_key_list(events)").use { rs -> while (rs.next()) fks += rs.getString("table") }
            }
        }
        assertEquals(emptyList(), fks)
    }

    // ---------------------------------------------------------------------------------------------
    // The store
    // ---------------------------------------------------------------------------------------------

    private val store: EventStore get() = db.repositoryProvider().eventStore()

    @Test
    fun `an empty log reports the floor as its newest seq and reads nothing`(): Unit =
        runBlocking {
            assertEquals(floor, store.maxSeq())
            assertEquals(emptyList(), store.readAfter(0L, null, 10))
        }

    @Test
    fun `append returns the records with ascending seqs above the floor and a round-tripped timestamp`(): Unit =
        runBlocking {
            val at = Instant.parse("2026-03-04T05:06:07.123Z")
            val appended = store.append(List(3) { record(at = at) })

            assertEquals(listOf(floor + 1, floor + 2, floor + 3), appended.map { it.seq })
            assertEquals(floor + 3, store.maxSeq())
            val read = store.readAfter(0L, null, 10)
            assertEquals(appended.map { it.id }, read.map { it.id })
            assertEquals(listOf(at, at, at), read.map { it.occurredAt }, "occurred_at round-trips at millisecond precision")
            assertEquals(23L, long("SELECT length(occurred_at) FROM events LIMIT 1"))
            assertEquals(emptyList(), store.append(emptyList()), "appending nothing appends nothing")
            assertEquals(floor + 3, store.maxSeq())
        }

    @Test
    fun `readAfter is exclusive ascending limited and root-filtered`(): Unit =
        runBlocking {
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val appended =
                store.append(
                    listOf(
                        record(rootA),
                        record(rootB),
                        record(rootA),
                        record(rootB),
                        record(rootA),
                    ),
                )
            val seqs = appended.map { it.seq }

            assertEquals(seqs.drop(2), store.readAfter(seqs[1], null, 100).map { it.seq }, "afterSeq is exclusive")
            assertEquals(seqs.take(2), store.readAfter(0L, null, 2).map { it.seq }, "limit keeps the lowest seqs")
            assertEquals(listOf(seqs[0], seqs[2], seqs[4]), store.readAfter(0L, setOf(rootA), 100).map { it.seq })
            assertEquals(listOf(seqs[2], seqs[4]), store.readAfter(seqs[0], setOf(rootA), 100).map { it.seq })
            assertEquals(seqs, store.readAfter(0L, setOf(rootA, rootB), 100).map { it.seq })
            assertEquals(emptyList(), store.readAfter(0L, setOf(UUID.randomUUID()), 100), "an unknown root reads nothing")
            assertEquals(emptyList(), store.readAfter(seqs.last(), null, 100), "nothing after the newest")
        }
}

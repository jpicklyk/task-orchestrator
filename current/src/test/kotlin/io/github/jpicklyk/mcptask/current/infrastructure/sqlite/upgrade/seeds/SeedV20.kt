package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V20 only adds the `events` table (plus its sqlite_sequence seed at 1e12): every existing table is unchanged.
 * Verifies the column shape, the four indexes, the seq floor, and the NOT NULL / CHECK guards.
 */
object SeedV20 : MigrationSeed(20) {
    private const val FLOOR = 1_000_000_000_000L

    override fun verify(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(events)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
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
                "data"
            ),
            columns,
            "events columns"
        )
        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(events)").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("name")) }
                }
            }
        for (name in listOf("idx_events_root_seq", "idx_events_entity_seq", "idx_events_type_occurred", "idx_events_req_id")) {
            assertTrue(name in indexes, "$name missing: $indexes")
        }

        val before = scalarLong(conn, "SELECT COALESCE(MAX(seq), 0) FROM events")
        val floor = scalarLong(conn, "SELECT seq FROM sqlite_sequence WHERE name = 'events'")
        assertTrue(floor >= FLOOR, "the seq counter must start at or above the floor, got $floor")
        val seq = insert(conn, UUID.randomUUID(), root = UUID.randomUUID(), occurredAt = "2026-03-01 10:15:30.123")
        assertTrue(seq > FLOOR && seq > before, "a new row must get a seq above the floor and above every existing row, got $seq")

        assertFailsWith<SQLException>("a null root_id must be rejected") {
            insert(conn, UUID.randomUUID(), root = null, occurredAt = "2026-03-01 10:15:30.123")
        }
        assertFailsWith<SQLException>("a 20-character occurred_at must be rejected") {
            insert(conn, UUID.randomUUID(), root = UUID.randomUUID(), occurredAt = "2026-03-01T10:15:30Z")
        }
        val dupId = UUID.randomUUID()
        insert(conn, dupId, root = UUID.randomUUID(), occurredAt = "2026-03-01 10:15:30.123")
        assertFailsWith<SQLException>("a duplicate event id must be rejected") {
            insert(conn, dupId, root = UUID.randomUUID(), occurredAt = "2026-03-01 10:15:30.123")
        }
        conn.createStatement().use { it.executeUpdate("DELETE FROM events WHERE type = 'seed20.probe'") }
    }

    private fun insert(
        conn: Connection,
        id: UUID,
        root: UUID?,
        occurredAt: String
    ): Long {
        conn
            .prepareStatement(
                "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setBytes(1, bytes(id))
                ps.setString(2, occurredAt)
                if (root == null) ps.setNull(3, java.sql.Types.BLOB) else ps.setBytes(3, bytes(root))
                ps.setString(4, "item")
                ps.setBytes(5, bytes(UUID.randomUUID()))
                ps.setString(6, "seed20.probe")
                ps.setString(7, "{}")
                ps.executeUpdate()
            }
        return scalarLong(conn, "SELECT last_insert_rowid()")
    }

    private fun bytes(uuid: UUID): ByteArray =
        java.nio.ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun scalarLong(
        conn: Connection,
        sql: String
    ): Long =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
}

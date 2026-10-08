package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V19 only adds the empty `idempotency_records` table: every existing table is unchanged. */
object SeedV19 : MigrationSeed(19) {
    private const val FINGERPRINT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    override fun verify(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(idempotency_records)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name") to rs.getInt("pk")) }
                }
            }
        assertEquals(
            listOf("principal_id" to 1, "operation" to 2, "key" to 3, "fingerprint" to 0, "result_json" to 0, "created_at" to 0),
            columns,
            "idempotency_records columns and primary key positions"
        )
        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(idempotency_records)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
        assertTrue("idx_idempotency_records_created_at" in indexes, "created_at index missing: $indexes")

        assertFailsWith<SQLException>("a duplicate primary key must be rejected") {
            insert(conn, "dup", FINGERPRINT, "2026-03-01 10:15:30.123")
            insert(conn, "dup", FINGERPRINT, "2026-03-01 10:15:30.123")
        }
        assertFailsWith<SQLException>("a 63-character fingerprint must be rejected") {
            insert(conn, "short-fp", FINGERPRINT.dropLast(1), "2026-03-01 10:15:30.123")
        }
        assertFailsWith<SQLException>("a 20-character created_at must be rejected") {
            insert(conn, "short-ts", FINGERPRINT, "2026-03-01T10:15:30Z")
        }
        conn.createStatement().use { it.executeUpdate("DELETE FROM idempotency_records WHERE key IN ('dup', 'short-fp', 'short-ts')") }
    }

    private fun insert(
        conn: Connection,
        key: String,
        fingerprint: String,
        createdAt: String
    ) {
        conn
            .prepareStatement(
                "INSERT INTO idempotency_records (principal_id, operation, key, fingerprint, result_json, created_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "seed19")
                ps.setString(2, "mcp.test")
                ps.setString(3, key)
                ps.setString(4, fingerprint)
                ps.setString(5, "{}")
                ps.setString(6, createdAt)
                ps.executeUpdate()
            }
    }
}

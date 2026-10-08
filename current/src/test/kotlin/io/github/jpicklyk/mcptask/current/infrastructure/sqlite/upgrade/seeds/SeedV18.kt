package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V18 rewrites every persisted timestamp to the canonical UTC text `yyyy-MM-dd HH:mm:ss.SSS`. The seed adds rows
 * holding each legacy shape (datetime() text without a fraction, `T`/`Z`, an offset, an over-long fraction) next to
 * canonical and NULL values, across the claim, lease and lease-history columns.
 */
object SeedV18 : MigrationSeed(18) {
    /** The 19 (table, column) pairs V18 normalizes. */
    val COLUMNS: Map<String, List<String>> =
        mapOf(
            "work_items" to listOf("created_at", "modified_at", "role_changed_at", "claimed_at", "claim_expires_at", "original_claimed_at"),
            "notes" to listOf("created_at", "modified_at"),
            "dependencies" to listOf("created_at"),
            "plan_documents" to listOf("created_at", "modified_at"),
            "project_config" to listOf("updated_at"),
            "role_transitions" to listOf("transitioned_at"),
            "resource_leases" to listOf("acquired_at", "expires_at", "original_acquired_at"),
            "resource_lease_history" to listOf("acquired_at", "expires_at", "released_at")
        )

    private val CANONICAL = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    override fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (column in COLUMNS[table].orEmpty()) {
            val value = row[column] as? String ?: continue
            val canonical = runCatching { UtcTimestamp.format(UtcTimestamp.parse(value)) }.getOrNull() ?: continue
            if (canonical != value) out[column] = canonical
        }
        return out
    }

    override fun seed(conn: Connection) {
        BaselineDataset.extraItem(conn, "seed18-shapes", "work", "in-progress")
        BaselineDataset.extraItem(conn, "seed18-canonical", "work", "in-progress")
        BaselineDataset.extraItem(conn, "seed18-null", "queue", "backlog")
        // Shapes the 3.x claim SQL wrote (datetime('now'): no fraction) mixed with legacy offset / T forms.
        conn.createStatement().use { st ->
            st.executeUpdate(
                "UPDATE work_items SET claimed_by = 'agent-18', claimed_at = '2026-03-01 10:15:30'," +
                    " claim_expires_at = '2026-03-01T11:15:30Z', original_claimed_at = '2026-03-01T10:15:30.123+05:30'" +
                    " WHERE id = X'${hex("seed18-shapes")}'"
            )
            st.executeUpdate(
                "UPDATE work_items SET claimed_by = 'agent-18', claimed_at = '2026-03-01 10:15:30.123'," +
                    " claim_expires_at = '2026-03-01 11:15:30.999', original_claimed_at = '2026-03-01 10:15:30.9996'" +
                    " WHERE id = X'${hex("seed18-canonical")}'"
            )
        }
        BaselineDataset.insert(
            conn,
            "resource_leases",
            "id" to BaselineDataset.id("lease:seed18"),
            "resource_key" to "seed18-resource",
            "holder_item_id" to BaselineDataset.id("seed18-shapes"),
            "acquired_by_actor_id" to "actor-one",
            "acquired_at" to "2026-03-01 10:15:30",
            "expires_at" to "2026-03-01 11:15:30.5",
            "original_acquired_at" to "2026-03-01T09:00:00Z",
            "version" to 0
        )
        BaselineDataset.insert(
            conn,
            "resource_lease_history",
            "id" to BaselineDataset.id("history:seed18"),
            "resource_key" to "seed18-resource",
            "holder_item_id" to BaselineDataset.id("seed18-shapes"),
            "acquired_by_actor_id" to "actor-one",
            "acquired_at" to "2026-03-01 10:15:30.123",
            "expires_at" to "2026-03-01 11:15:30.123",
            "released_at" to "2026-03-01 10:45:30",
            "release_reason" to "released",
            "released_by_actor_id" to "actor-one"
        )
    }

    private fun hex(name: String): String = BaselineDataset.id(name).joinToString("") { "%02x".format(it) }

    override fun verify(conn: Connection) {
        for ((table, columns) in COLUMNS) {
            for (column in columns) {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT $column FROM $table WHERE $column IS NOT NULL").use { rs ->
                        while (rs.next()) {
                            val v = rs.getString(1)
                            assertTrue(CANONICAL.matches(v), "$table.$column holds a non-canonical value '$v' after V18")
                        }
                    }
                }
            }
        }

        fun text(sql: String): String? =
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        val id = "X'${hex("seed18-shapes")}'"
        assertEquals("2026-03-01 10:15:30.000", text("SELECT claimed_at FROM work_items WHERE id = $id"))
        assertEquals("2026-03-01 11:15:30.000", text("SELECT claim_expires_at FROM work_items WHERE id = $id"))
        assertEquals("2026-03-01 04:45:30.123", text("SELECT original_claimed_at FROM work_items WHERE id = $id"))
        val canonicalId = "X'${hex("seed18-canonical")}'"
        assertEquals("2026-03-01 10:15:30.123", text("SELECT claimed_at FROM work_items WHERE id = $canonicalId"))
        assertEquals("2026-03-01 10:15:30.999", text("SELECT original_claimed_at FROM work_items WHERE id = $canonicalId"))
        val nullId = "X'${hex("seed18-null")}'"
        assertEquals(null, text("SELECT claimed_at FROM work_items WHERE id = $nullId"))
        assertEquals("2026-03-01 11:15:30.500", text("SELECT expires_at FROM resource_leases WHERE resource_key = 'seed18-resource'"))
        assertEquals(
            "2026-03-01 10:45:30.000",
            text("SELECT released_at FROM resource_lease_history WHERE resource_key = 'seed18-resource'")
        )
    }
}

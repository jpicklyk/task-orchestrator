package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import kotlin.test.assertEquals

/**
 * V13 repairs terminal rows mislabeled `in-progress` to `done` and must leave custom terminal labels,
 * `cancelled` and non-terminal `in-progress` rows alone.
 */
object SeedV13 : MigrationSeed(13) {
    override fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> =
        if (table == "work_items" && row["role"] == "terminal" && row["status_label"] == "in-progress") {
            mapOf("status_label" to "done")
        } else {
            emptyMap()
        }

    override fun seed(conn: Connection) {
        BaselineDataset.extraItem(conn, "seed13-wip-terminal", role = "terminal", statusLabel = "in-progress")
        BaselineDataset.extraItem(conn, "seed13-custom-terminal", role = "terminal", statusLabel = "shipped")
        BaselineDataset.extraItem(conn, "seed13-cancelled", role = "terminal", statusLabel = "cancelled")
        BaselineDataset.extraItem(conn, "seed13-wip-work", role = "work", statusLabel = "in-progress")
    }

    override fun verify(conn: Connection) {
        fun label(name: String): String? =
            conn.prepareStatement("SELECT status_label FROM work_items WHERE id = ?").use { ps ->
                ps.setBytes(1, BaselineDataset.id(name))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else error("seed row $name vanished") }
            }
        assertEquals("done", label("seed13-wip-terminal"), "terminal in-progress must be repaired to done")
        assertEquals("done", label("task3"), "the baseline terminal in-progress row must be repaired too")
        assertEquals("shipped", label("seed13-custom-terminal"), "a custom terminal label must survive")
        assertEquals("cancelled", label("seed13-cancelled"), "cancelled must survive")
        assertEquals("in-progress", label("seed13-wip-work"), "a non-terminal in-progress row must survive")
    }
}

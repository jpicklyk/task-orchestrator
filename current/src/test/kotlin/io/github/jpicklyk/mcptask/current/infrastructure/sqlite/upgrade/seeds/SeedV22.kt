package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V22 stores IS_BLOCKED_BY rows as BLOCKS with the ends swapped (same id) and tightens the type CHECK.
 *
 * The seed adds NO twins: the harness has no expected-deletion contract, so a removed row would be reported as
 * lost. Twin dedupe is covered by the dedicated V22 migration test. The seed adds a lone IS_BLOCKED_BY row with a
 * threshold, a mutual pair (a BLOCKS b plus a IS_BLOCKED_BY b, which becomes b BLOCKS a) and a RELATES_TO row.
 */
object SeedV22 : MigrationSeed(22) {
    private const val TS = "2026-03-01 10:15:30.123"

    override fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> =
        if (table == "dependencies" && row["type"] == "IS_BLOCKED_BY") {
            mapOf("from_item_id" to row["to_item_id"], "to_item_id" to row["from_item_id"], "type" to "BLOCKS")
        } else {
            emptyMap()
        }

    override fun seed(conn: Connection) {
        for (name in listOf("seed22-a", "seed22-b", "seed22-c", "seed22-d", "seed22-e", "seed22-f")) {
            BaselineDataset.extraItem(conn, name, "queue", "backlog")
        }

        fun dep(
            name: String,
            from: String,
            to: String,
            type: String,
            unblockAt: String? = null
        ) = BaselineDataset.insert(
            conn,
            "dependencies",
            "id" to BaselineDataset.id("dep:$name"),
            "from_item_id" to BaselineDataset.id(from),
            "to_item_id" to BaselineDataset.id(to),
            "type" to type,
            "unblock_at" to unblockAt,
            "created_at" to TS
        )
        dep("seed22-lone", "seed22-a", "seed22-b", "IS_BLOCKED_BY", "review")
        dep("seed22-mutual-blocks", "seed22-c", "seed22-d", "BLOCKS")
        dep("seed22-mutual-ibb", "seed22-c", "seed22-d", "IS_BLOCKED_BY")
        dep("seed22-relates", "seed22-e", "seed22-f", "RELATES_TO")
    }

    override fun verify(conn: Connection) {
        fun count(sql: String): Int =
            conn.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }

        fun hex(name: String): String = "X'" + BaselineDataset.id(name).joinToString("") { "%02x".format(it) } + "'"

        assertEquals(0, count("SELECT count(*) FROM dependencies WHERE type = 'IS_BLOCKED_BY'"), "no IS_BLOCKED_BY row survives V22")

        val lone = "id = ${hex("dep:seed22-lone")} AND from_item_id = ${hex("seed22-b")} AND to_item_id = ${hex("seed22-a")}"
        assertEquals(
            1,
            count("SELECT count(*) FROM dependencies WHERE $lone AND type = 'BLOCKS' AND unblock_at = 'review'"),
            "the lone IS_BLOCKED_BY row is rewritten in place: same id, ends swapped, threshold kept"
        )

        assertEquals(
            1,
            count(
                "SELECT count(*) FROM dependencies WHERE from_item_id = ${hex(
                    "seed22-c"
                )} AND to_item_id = ${hex("seed22-d")} AND type = 'BLOCKS'"
            ),
            "the mutual pair keeps c BLOCKS d"
        )
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM dependencies WHERE from_item_id = ${hex(
                    "seed22-d"
                )} AND to_item_id = ${hex("seed22-c")} AND type = 'BLOCKS'"
            ),
            "the mutual pair keeps d BLOCKS c (the former IS_BLOCKED_BY)"
        )

        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'dependencies'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        for (name in listOf("idx_deps_unique", "idx_deps_from", "idx_deps_to")) assertTrue(name in indexes, "$name missing: $indexes")

        assertFailsWith<SQLException>("an IS_BLOCKED_BY insert must violate the tightened type CHECK") {
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO dependencies (from_item_id, to_item_id, type, created_at) VALUES " +
                        "(${hex("seed22-e")}, ${hex("seed22-a")}, 'IS_BLOCKED_BY', '$TS')"
                )
            }
        }
    }
}

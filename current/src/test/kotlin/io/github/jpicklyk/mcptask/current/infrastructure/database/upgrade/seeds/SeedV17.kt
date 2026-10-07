package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade.MigrationSeed
import java.sql.Connection
import kotlin.test.assertEquals

/** V17 scrubs raw bearer proofs (`actor_proof`) from notes and transitions without touching anything else. */
object SeedV17 : MigrationSeed(17) {
    override fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> =
        if ((table == "notes" || table == "role_transitions") && row["actor_proof"] != null) {
            mapOf("actor_proof" to null)
        } else {
            emptyMap()
        }

    override fun seed(conn: Connection) {
        BaselineDataset.insert(
            conn,
            "notes",
            "id" to BaselineDataset.id("note:task6:seed17"),
            "work_item_id" to BaselineDataset.id("task6"),
            "key" to "seed17-note",
            "role" to "work",
            "body" to "Note carrying a raw proof",
            "created_at" to BaselineDataset.TS,
            "modified_at" to BaselineDataset.TS,
            "actor_id" to "actor-two",
            "actor_kind" to "external",
            "actor_proof" to "eyJraWQiOiJrMiJ9.synthetic.seed17-proof"
        )
        BaselineDataset.insert(
            conn,
            "role_transitions",
            "id" to BaselineDataset.id("transition:seed17"),
            "item_id" to BaselineDataset.id("task6"),
            "from_role" to "queue",
            "to_role" to "work",
            "trigger" to "start",
            "transitioned_at" to BaselineDataset.TS,
            "actor_id" to "actor-two",
            "actor_kind" to "external",
            "actor_proof" to "eyJraWQiOiJrMiJ9.synthetic.seed17-transition-proof"
        )
    }

    override fun verify(conn: Connection) {
        for (table in listOf("notes", "role_transitions")) {
            val remaining =
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM $table WHERE actor_proof IS NOT NULL").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertEquals(0, remaining, "$table.actor_proof must be NULL for every row after V17")
        }
        val attributed =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM notes WHERE actor_id IS NOT NULL").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        assertEquals(3, attributed, "V17 must keep actor attribution on notes (two baseline notes plus the seed note)")
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.database.upgrade

import java.nio.ByteBuffer
import java.sql.Connection
import java.util.UUID

/**
 * One fixed, synthetic dataset inserted with raw JDBC into a database at ANY schema version, using only
 * the tables and columns that exist at that version (checked with PRAGMA table_info). The upgrade
 * harness seeds it at version N-1 and then migrates; the golden-V17 generator seeds it at V17.
 *
 * Every id is a deterministic `nameUUIDFromBytes`, every timestamp a fixed literal in one of the two
 * shapes the 3.16 server wrote: the Exposed `Instant` shape (`2026-03-01 10:15:30.123`, probed from
 * `WorkItemsTable` inserts) and the DB-side `datetime('now')` shape (`2026-03-01 10:15:30`) that the
 * claim and lease SQL writes. Nothing here is derived from any real database.
 *
 * Contents: a root, a feature under it, six tasks under the feature, notes (one with actor
 * attribution and a raw `actor_proof` that V17 scrubs), dependencies of every type including one
 * mutual-block pair, transitions with actor columns, a claim, active and expired leases with lease
 * history (including an interval whose holder was deleted), a `project_config` row and plan documents.
 */
object BaselineDataset {
    /** Exposed `Instant` write shape (3.16). */
    const val TS = "2026-03-01 10:15:30.123"

    /** `datetime('now')` write shape (3.16 claim and lease SQL). */
    const val DB_TS = "2026-03-01 10:15:30"

    /** A token present in one seeded work-item title, used for FTS MATCH checks. */
    const val ITEM_FTS_TOKEN = "zebrafish"

    /** A token present in one seeded note body, used for FTS MATCH checks. */
    const val NOTE_FTS_TOKEN = "quokka"

    val ITEM_NAMES = listOf("root", "feature", "task1", "task2", "task3", "task4", "task5", "task6")

    fun uuid(name: String): UUID = UUID.nameUUIDFromBytes(("upgrade-baseline:$name").toByteArray(Charsets.UTF_8))

    fun id(name: String): ByteArray = bytes(uuid(name))

    fun bytes(uuid: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    /** The column names of [table] in [conn], empty when the table does not exist at this version. */
    fun columnsOf(
        conn: Connection,
        table: String
    ): Set<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }

    /** Inserts one row, silently dropping columns the table does not have at this version. */
    fun insert(
        conn: Connection,
        table: String,
        vararg values: Pair<String, Any?>
    ) {
        val existing = columnsOf(conn, table)
        if (existing.isEmpty()) return
        val usable = values.filter { it.first in existing }
        val sql = "INSERT INTO $table (${usable.joinToString { it.first }}) VALUES (${usable.joinToString { "?" }})"
        conn.prepareStatement(sql).use { ps ->
            usable.forEachIndexed { i, (_, v) ->
                when (v) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is ByteArray -> ps.setBytes(i + 1, v)
                    is Int -> ps.setInt(i + 1, v)
                    is Long -> ps.setLong(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }

    /** An additional depth-2 task under the baseline feature, for a [MigrationSeed] that needs its own rows. */
    fun extraItem(
        conn: Connection,
        name: String,
        role: String,
        statusLabel: String?
    ) = insert(
        conn,
        "work_items",
        "id" to id(name),
        "parent_id" to id("feature"),
        "root_id" to id("root"),
        "title" to "Seeded $name",
        "summary" to "",
        "role" to role,
        "status_label" to statusLabel,
        "priority" to "medium",
        "complexity" to 5,
        "requires_verification" to 0,
        "depth" to 2,
        "created_at" to TS,
        "modified_at" to TS,
        "role_changed_at" to TS,
        "version" to 1
    )

    @Suppress("LongMethod")
    fun seed(conn: Connection) {
        fun item(
            name: String,
            parent: String?,
            depth: Int,
            title: String,
            role: String = "queue",
            statusLabel: String? = null,
            previousRole: String? = null,
            claim: Boolean = false
        ) = insert(
            conn,
            "work_items",
            "id" to id(name),
            "parent_id" to parent?.let { id(it) },
            "root_id" to id("root"),
            "title" to title,
            "description" to "Description of $name",
            "summary" to "",
            "role" to role,
            "status_label" to statusLabel,
            "previous_role" to previousRole,
            "priority" to "medium",
            "complexity" to 5,
            "requires_verification" to 0,
            "depth" to depth,
            "metadata" to null,
            "tags" to "baseline,$name",
            "type" to null,
            "properties" to null,
            "created_at" to TS,
            "modified_at" to TS,
            "role_changed_at" to TS,
            "version" to 1,
            "claimed_by" to if (claim) "agent-one" else null,
            "claimed_at" to if (claim) TS else null,
            "claim_expires_at" to if (claim) "2026-03-01 11:15:30" else null,
            "original_claimed_at" to if (claim) DB_TS else null
        )

        item("root", null, 0, "Baseline root alpha", role = "work", statusLabel = "in-progress")
        item("feature", "root", 1, "Baseline feature beta", role = "work", statusLabel = "in-progress")
        item("task1", "feature", 2, "Task one $ITEM_FTS_TOKEN", role = "blocked", previousRole = "work")
        item("task2", "feature", 2, "Task two gamma", role = "terminal", statusLabel = "done")
        item("task3", "feature", 2, "Task three delta", role = "terminal", statusLabel = "in-progress")
        item("task4", "feature", 2, "Task four epsilon", role = "work", statusLabel = "in-progress", claim = true)
        item("task5", "feature", 2, "Task five zeta")
        item("task6", "feature", 2, "Task six eta")

        fun note(
            item: String,
            key: String,
            role: String,
            body: String,
            attributed: Boolean = false
        ) = insert(
            conn,
            "notes",
            "id" to id("note:$item:$key"),
            "work_item_id" to id(item),
            "key" to key,
            "role" to role,
            "body" to body,
            "created_at" to TS,
            "modified_at" to TS,
            "actor_id" to if (attributed) "actor-one" else null,
            "actor_kind" to if (attributed) "subagent" else null,
            "actor_parent" to if (attributed) "orchestrator-one" else null,
            "actor_proof" to if (attributed) "eyJraWQiOiJrMSJ9.synthetic.baseline-proof" else null,
            "verification_status" to if (attributed) "VERIFIED" else null,
            "verification_verifier" to if (attributed) "jwks" else null,
            "verification_reason" to null
        )
        note("feature", "feature-summary", "queue", "Summary of the baseline feature")
        note("feature", "notes-extra", "work", "A second note for the feature")
        note("task1", "task-scope", "queue", "Scope text mentioning the $NOTE_FTS_TOKEN marker", attributed = true)

        fun dep(
            name: String,
            from: String,
            to: String,
            type: String,
            unblockAt: String? = null
        ) = insert(
            conn,
            "dependencies",
            "id" to id("dep:$name"),
            "from_item_id" to id(from),
            "to_item_id" to id(to),
            "type" to type,
            "unblock_at" to unblockAt,
            "created_at" to TS
        )
        dep("blocks", "task1", "task2", "BLOCKS", "terminal")
        dep("blocked-by", "task3", "task2", "IS_BLOCKED_BY")
        dep("relates", "task1", "task3", "RELATES_TO")
        dep("mutual-a", "task5", "task6", "BLOCKS")
        dep("mutual-b", "task6", "task5", "BLOCKS")

        fun transition(
            name: String,
            item: String,
            from: String,
            to: String,
            trigger: String,
            attributed: Boolean
        ) = insert(
            conn,
            "role_transitions",
            "id" to id("transition:$name"),
            "item_id" to id(item),
            "from_role" to from,
            "to_role" to to,
            "from_status_label" to null,
            "to_status_label" to null,
            "trigger" to trigger,
            "summary" to "Baseline transition $name",
            "transitioned_at" to TS,
            "actor_id" to if (attributed) "actor-one" else null,
            "actor_kind" to if (attributed) "subagent" else null,
            "actor_parent" to if (attributed) "orchestrator-one" else null,
            "actor_proof" to if (attributed) "eyJraWQiOiJrMSJ9.synthetic.transition-proof" else null,
            "verification_status" to if (attributed) "VERIFIED" else null,
            "verification_verifier" to if (attributed) "jwks" else null,
            "verification_reason" to null,
            "consumed_credentials" to if (attributed) "[\"vault:baseline-secret\"]" else null
        )
        transition("t2-start", "task2", "queue", "work", "start", attributed = true)
        transition("t2-complete", "task2", "work", "terminal", "complete", attributed = false)
        transition("t4-start", "task4", "queue", "work", "start", attributed = true)

        fun lease(
            name: String,
            key: String,
            holder: String,
            expires: String
        ) = insert(
            conn,
            "resource_leases",
            "id" to id("lease:$name"),
            "resource_key" to key,
            "holder_item_id" to id(holder),
            "acquired_by_actor_id" to "actor-one",
            "acquired_at" to DB_TS,
            "expires_at" to expires,
            "original_acquired_at" to DB_TS,
            "budget_limit" to null,
            "budget_used" to null,
            "budget_window_seconds" to null,
            "version" to 0
        )
        lease("active", "staging-environment", "task4", "2099-01-01 00:00:00")
        lease("expired", "shared-database", "task2", "2020-01-01 00:00:00")

        fun history(
            name: String,
            key: String,
            holder: ByteArray,
            expires: String,
            released: String?,
            reason: String?
        ) = insert(
            conn,
            "resource_lease_history",
            "id" to id("history:$name"),
            "resource_key" to key,
            "holder_item_id" to holder,
            "acquired_by_actor_id" to "actor-one",
            "acquired_at" to DB_TS,
            "expires_at" to expires,
            "released_at" to released,
            "release_reason" to reason,
            "released_by_actor_id" to if (released != null) "actor-one" else null
        )
        history("open", "staging-environment", id("task4"), "2099-01-01 00:00:00", null, null)
        history("closed", "shared-database", id("task2"), "2020-01-01 00:00:00", "2019-12-31 23:00:00", "released")
        // No foreign key on holder_item_id (V16): an interval may outlive its deleted holder.
        history("orphan-holder", "retired-resource", id("deleted-holder"), "2020-06-01 00:00:00", "2020-05-01 00:00:00", "stolen")

        insert(
            conn,
            "project_config",
            "id" to id("project-config"),
            "root_item_id" to id("root"),
            "config_yaml" to "project:\n  name: synthetic-baseline\n",
            "fingerprint" to "baseline-fingerprint-0001",
            "updated_at" to TS,
            "fingerprint_history" to "[\"baseline-fingerprint-0000\"]"
        )

        insert(
            conn,
            "plan_documents",
            "id" to id("plan:pending"),
            "root_item_id" to id("root"),
            "slug" to "pending-plan",
            "body" to "# Pending plan\n",
            "content_hash" to "hash-pending",
            "status" to "pending",
            "adopted_by_item_id" to null,
            "created_at" to TS,
            "modified_at" to TS
        )
        insert(
            conn,
            "plan_documents",
            "id" to id("plan:adopted"),
            "root_item_id" to id("root"),
            "slug" to "adopted-plan",
            "body" to "# Adopted plan\n",
            "content_hash" to "hash-adopted",
            "status" to "adopted",
            "adopted_by_item_id" to id("feature"),
            "created_at" to TS,
            "modified_at" to TS
        )
    }
}

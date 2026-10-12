package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V29 copies every `role_transitions` row that predates the events table (older than the installation of V20) into
 * `events` as an `item.transitioned` row under the same id, then drops `role_transitions`. A row newer than that
 * installation already has its twin event and is not copied.
 *
 * Oracle: the frozen migration-assessment and task-scope section 1 (column mapping, the `data` key order, seq 1..N in
 * `transitioned_at` order below the floor, `backfilled: true` and no `origin`, the V20 boundary). The expected `data` text
 * is built here in Kotlin in the documented key order, never read back from the migration.
 *
 * [seed] (the V28 database) adds three pre-boundary rows on their own items: one VERIFIED with claims, credentials, labels
 * and a parent; one REJECTED with a reason, invalid credentials JSON and an empty claims object; one actorless with NULL
 * labels and summary. It also adds one row stamped an hour AHEAD of the run together with its twin event, which the
 * migration must leave alone.
 */
object SeedV29 : MigrationSeed(29) {
    private const val VERIFIED_ROW = "transition:seed29-verified"
    private const val REJECTED_ROW = "transition:seed29-rejected"
    private const val ACTORLESS_ROW = "transition:seed29-actorless"
    private const val POST_ROW = "transition:seed29-post"
    private const val POST_TWIN = "event:seed29-post-twin"

    private const val EARLY = "2026-03-02 00:00:00.500"

    /** SQL predicate for a backfilled event row. */
    private const val BACKFILLED = "json_extract(data, '$.backfilled') = 1"

    private val json = Json

    /** Value of `sqlite_sequence.events` captured before the migration, to prove the backfill leaves the counter alone. */
    @Volatile
    private var sequenceBefore: Long? = null

    private fun hex(name: String): String = BaselineDataset.id(name).joinToString("") { "%02x".format(it) }

    private fun item(
        conn: Connection,
        name: String
    ) = BaselineDataset.extraItem(conn, name, "work", "in-progress")

    private fun transition(
        conn: Connection,
        name: String,
        item: String,
        at: String,
        vararg more: Pair<String, Any?>
    ) = BaselineDataset.insert(
        conn,
        "role_transitions",
        "id" to BaselineDataset.id(name),
        "item_id" to BaselineDataset.id(item),
        "from_role" to "queue",
        "to_role" to "work",
        "trigger" to "start",
        "transitioned_at" to at,
        *more
    )

    override fun seed(conn: Connection) {
        item(conn, "seed29-verified")
        item(conn, "seed29-rejected")
        item(conn, "seed29-actorless")
        item(conn, "seed29-post")
        transition(
            conn,
            VERIFIED_ROW,
            "seed29-verified",
            EARLY,
            "from_status_label" to "backlog",
            "to_status_label" to "in-progress",
            "summary" to "Seed 29 verified",
            "actor_id" to "actor-29",
            "actor_kind" to "SUBAGENT",
            "actor_parent" to "orchestrator-29",
            "verification_status" to "VERIFIED",
            "verification_verifier" to "jwks",
            "consumed_credentials" to "[\"vault:one\",\"vault:two\"]",
            "actor_proof_sha256" to BaselineDataset.PROOF_SHA256,
            "actor_proof_claims" to BaselineDataset.PROOF_CLAIMS
        )
        transition(
            conn,
            REJECTED_ROW,
            "seed29-rejected",
            BaselineDataset.TS,
            "summary" to "Seed 29 rejected",
            "actor_id" to "actor-29-b",
            "actor_kind" to "external",
            "verification_status" to "REJECTED",
            "verification_verifier" to "jwks",
            "verification_reason" to "audience-mismatch",
            "consumed_credentials" to "not-json",
            "actor_proof_claims" to "{}"
        )
        transition(conn, ACTORLESS_ROW, "seed29-actorless", BaselineDataset.TS)
        val ahead = UtcTimestamp.format(Instant.now().plus(1, ChronoUnit.HOURS))
        transition(conn, POST_ROW, "seed29-post", ahead, "summary" to "Seed 29 post-boundary")
        BaselineDataset.insert(
            conn,
            "events",
            "id" to BaselineDataset.id(POST_TWIN),
            "occurred_at" to ahead,
            "root_id" to BaselineDataset.id("root"),
            "entity_kind" to "item",
            "entity_id" to BaselineDataset.id("seed29-post"),
            "type" to "item.transitioned",
            "data" to "{\"trigger\":\"start\",\"fromRole\":\"queue\",\"toRole\":\"work\",\"origin\":\"user\"}"
        )
        sequenceBefore = scalar(conn, "SELECT seq FROM sqlite_sequence WHERE name = 'events'")
    }

    /**
     * Each older row becomes an `item.transitioned` event under its own id; the post-boundary row is represented by its
     * twin event. [row] is the pre-migration row after the earlier seeds' transforms (V17 scrub, V18 canonical timestamps).
     */
    override fun relocated(
        table: String,
        row: Map<String, Any?>
    ): Relocated? {
        if (table != "role_transitions") return null
        val id = row["id"]?.toString() ?: return null
        if (id == hex(POST_ROW)) return Relocated("events", hex(POST_TWIN), emptyMap())
        val at = row["transitioned_at"] as? String
        return Relocated(
            "events",
            id,
            mapOf(
                "occurred_at" to at?.let { UtcTimestamp.format(UtcTimestamp.parse(it)) },
                "entity_kind" to "item",
                "entity_id" to row["item_id"],
                "type" to "item.transitioned",
                "principal_id" to row["actor_id"],
                "principal_kind" to (row["actor_kind"] as? String)?.lowercase(),
                "proof_status" to (row["verification_status"] as? String)?.lowercase(),
                "data" to expectedData(row)
            )
        )
    }

    /** The `data` text the migration must produce, in the documented key order. */
    private fun expectedData(row: Map<String, Any?>): String {
        fun text(key: String): String = (row[key] as? String)?.let { json.encodeToString(String.serializer(), it) } ?: "null"

        val credentials =
            (row["consumed_credentials"] as? String)
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?.takeIf { it is JsonArray }
                ?.toString() ?: "[]"
        val claims =
            (row["actor_proof_claims"] as? String)
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?.takeIf { it is JsonObject && it.isNotEmpty() }
                ?.toString() ?: "null"
        return "{" +
            "\"trigger\":${text("trigger")}," +
            "\"fromRole\":${text("from_role")}," +
            "\"toRole\":${text("to_role")}," +
            "\"fromStatusLabel\":${text("from_status_label")}," +
            "\"toStatusLabel\":${text("to_status_label")}," +
            "\"summary\":${text("summary")}," +
            "\"consumedCredentials\":$credentials," +
            "\"actorProofSha256\":${text("actor_proof_sha256")}," +
            "\"actorProofClaims\":$claims," +
            "\"verifier\":${text("verification_verifier")}," +
            "\"verificationReason\":${text("verification_reason")}," +
            "\"actorParent\":${text("actor_parent")}," +
            "\"backfilled\":true" +
            "}"
    }

    /**
     * The row counts a snapshot taken BEFORE V29 must show at the latest schema: the `role_transitions` rows (all older than
     * the V20 installation in a harness database) are gone from their table and added to `events`. Other seeds' "nothing
     * else changed" checks compare against this, never against the raw snapshot.
     */
    fun afterRelocation(pre: Map<String, Int>): Map<String, Int> {
        val moved = pre["role_transitions"] ?: return pre
        val out = LinkedHashMap(pre)
        out.remove("role_transitions")
        out["events"]?.let { out["events"] = it + moved }
        return out
    }

    override fun verify(conn: Connection) {
        assertEquals(0, scalar(conn, "SELECT count(*) FROM sqlite_master WHERE name = 'role_transitions'"), "role_transitions is dropped")
        assertEquals(
            0,
            scalar(conn, "SELECT count(*) FROM sqlite_master WHERE name IN ('idx_role_trans_item', 'idx_role_trans_time')"),
            "its indexes go with it"
        )

        val bySeq = ids(conn, "SELECT hex(id) FROM events WHERE $BACKFILLED ORDER BY seq")
        assertTrue(bySeq.size >= 3, "at least the three seeded pre-boundary rows are backfilled: $bySeq")
        val byTime = ids(conn, "SELECT hex(id) FROM events WHERE $BACKFILLED ORDER BY occurred_at, id")
        assertEquals(byTime, bySeq, "backfilled seqs ascend with occurred_at (ties by id)")
        assertEquals(
            (1..bySeq.size).map { it.toLong() },
            longs(conn, "SELECT seq FROM events WHERE $BACKFILLED ORDER BY seq"),
            "backfilled seqs are 1..N"
        )
        assertTrue(
            scalar(conn, "SELECT max(seq) FROM events WHERE $BACKFILLED") < EventStore.SEQ_FLOOR,
            "every backfilled seq is below the floor"
        )
        assertEquals(
            0,
            scalar(conn, "SELECT count(*) FROM events WHERE $BACKFILLED AND json_extract(data, '$.origin') IS NOT NULL"),
            "a backfilled row carries no origin"
        )
        assertEquals(
            sequenceBefore,
            scalar(conn, "SELECT seq FROM sqlite_sequence WHERE name = 'events'"),
            "the backfill leaves the AUTOINCREMENT counter alone"
        )
        assertEquals(
            1,
            scalar(conn, "SELECT count(*) FROM events WHERE type = 'item.transitioned' AND entity_id = X'${hex("seed29-post")}'"),
            "the post-boundary item keeps exactly one event"
        )
        assertEquals(
            0,
            scalar(conn, "SELECT count(*) FROM events WHERE id = X'${hex(POST_ROW)}'"),
            "the post-boundary row is not copied under its own id"
        )
        assertEquals(
            ids(conn, "SELECT hex(root_id) FROM work_items WHERE id = X'${hex("seed29-verified")}'"),
            ids(conn, "SELECT hex(root_id) FROM events WHERE id = X'${hex(VERIFIED_ROW)}'"),
            "root_id is the item's stored root"
        )
    }

    private fun scalar(
        conn: Connection,
        sql: String
    ): Long = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L } }

    private fun ids(
        conn: Connection,
        sql: String
    ): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun longs(
        conn: Connection,
        sql: String
    ): List<Long> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import java.nio.ByteBuffer
import java.sql.Connection
import java.util.UUID

/**
 * One fixed, synthetic dataset inserted with raw JDBC into a database at ANY schema version, using only
 * the tables and columns that exist at that version (checked with PRAGMA table_info). The upgrade
 * harness seeds it at version N-1 and then migrates; the golden-V17 generator seeds it at V17.
 *
 * Every column of every table is given a distinct, non-default value in at least one row (the guard test
 * `BaselineColumnCoverageTest` enforces it against the latest schema, with a short reasoned exemption list).
 *
 * Columns that would otherwise share a literal are given different ones (created_at / modified_at /
 * role_changed_at are TS, TS+1s, TS+2s; original_acquired_at is earlier than acquired_at), so a recreation that
 * mis-maps one onto the other is visible; the pairwise guard in `BaselineColumnCoverageTest` enforces it.
 *
 * Every id is a deterministic `nameUUIDFromBytes`, every timestamp a fixed literal in one of the two
 * shapes the 3.16 server wrote: the Exposed `Instant` shape (`2026-03-01 10:15:30.123`, probed from
 * `WorkItemsTable` inserts) and the DB-side `datetime('now')` shape (`2026-03-01 10:15:30`) that the
 * claim and lease SQL writes. Nothing here is derived from any real database.
 *
 * Contents: a root, a feature under it, six tasks under the feature, notes (one with actor
 * attribution and a raw `actor_proof` below V17, which the V17 scrub removes, and sha256/claims evidence from V17), dependencies of every type including one
 * mutual-block pair, transitions with actor columns, a claim, active and expired leases with lease
 * history (including an interval whose holder was deleted), a `project_config` row and plan documents.
 */
object BaselineDataset {
    /** Exposed `Instant` write shape (3.16). */
    const val TS = "2026-03-01 10:15:30.123"

    /** [TS] plus one second (Exposed shape), so a column seeded with it is distinguishable from one seeded with [TS]. */
    const val TS_PLUS_1 = "2026-03-01 10:15:31.123"

    /** [TS] plus two seconds (Exposed shape). */
    const val TS_PLUS_2 = "2026-03-01 10:15:32.123"

    /** [TS] plus three seconds (Exposed shape); the `work_items.pinned_at` value. */
    const val TS_PLUS_3 = "2026-03-01 10:15:33.123"

    /** `datetime('now')` write shape (3.16 claim and lease SQL). */
    const val DB_TS = "2026-03-01 10:15:30"

    /** Hex SHA-256 evidence a 3.16 server stores in `actor_proof_sha256` (synthetic). */
    const val PROOF_SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    /** `actor_proof_claims` JSON a 3.16 server stores (synthetic). */
    const val PROOF_CLAIMS = "{\"iss\":\"https://issuer.example\",\"sub\":\"actor-one\",\"kid\":\"k1\",\"alg\":\"EdDSA\"}"

    /** A token present in one seeded work-item title, used for FTS MATCH checks. */
    const val ITEM_FTS_TOKEN = "zebrafish"

    /** A token present in one seeded note body, used for FTS MATCH checks. */
    const val NOTE_FTS_TOKEN = "quokka"

    /** A token present in every seeded plan / document body (and in the golden's), used for document FTS MATCH checks. */
    const val DOCUMENT_FTS_TOKEN = "plan"

    /** The FTS MATCH token per FTS backing table. */
    val FTS_TOKENS: Map<String, String> =
        mapOf("work_items" to ITEM_FTS_TOKEN, "notes" to NOTE_FTS_TOKEN, "documents" to DOCUMENT_FTS_TOKEN)

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
        // Typed like every other baseline row: a NULL type would be filled by the production type-backfill step on
        // every harness step and trip checkUpgrade's column-changed check.
        "type" to "feature-task",
        "created_at" to TS,
        "modified_at" to TS_PLUS_1,
        "role_changed_at" to TS_PLUS_2,
        "version" to 1,
        *pinColumns()
    )

    /**
     * The V26 lifecycle and pin set a seeded `work_items` row carries (dropped by [insert] below V26). Every row seeded at
     * V26 or later is pinned, so the production `pin-backfill` data step finds nothing to pin on a harness step and
     * checkUpgrade's column-changed check stays exact. A non-'none' lifecycle needs a non-NULL role (the relation CHECK),
     * so the baseline never seeds a NULL role.
     */
    fun pinColumns(): Array<Pair<String, Any?>> =
        arrayOf(
            "lifecycle" to "auto",
            "pinned_server_cfg" to id("server-config-version"),
            "pinned_root_cfg" to id("config-version"),
            // A key other than the rows' type (a pin may name the "default" schema), so the two columns never coincide.
            "pinned_schema_key" to "default",
            "pinned_schema_layer" to "root",
            "pinned_at" to TS_PLUS_3
        )

    @Suppress("LongMethod")
    fun seed(conn: Connection) {
        // 3.16 (V17) never writes a raw actor_proof: it stores the sha256 and claims instead. Raw proofs are only
        // seeded below V17, where they are the data the V17 scrub must remove.
        val rawProofs = "actor_proof_sha256" !in columnsOf(conn, "notes")
        // From V27 traits are typed (item_traits rows, no properties.traits key); below it they ride in properties.
        val typedTraits = columnsOf(conn, "item_traits").isNotEmpty()

        fun item(
            name: String,
            parent: String?,
            depth: Int,
            title: String,
            role: String = "queue",
            statusLabel: String? = null,
            previousRole: String? = null,
            claim: Boolean = false,
            priority: String = "medium",
            requiresVerification: Boolean = false,
            version: Int = 1
        ) = insert(
            conn,
            "work_items",
            "id" to id(name),
            "parent_id" to parent?.let { id(it) },
            "root_id" to id("root"),
            "title" to title,
            "description" to "Description of $name",
            "summary" to "Summary of $name",
            "role" to role,
            "status_label" to statusLabel,
            "previous_role" to previousRole,
            "priority" to priority,
            "complexity" to 5,
            "requires_verification" to if (requiresVerification) 1 else 0,
            "depth" to depth,
            "metadata" to "{\"seeded\":\"$name\"}",
            "tags" to "baseline,$name",
            "type" to "feature-task",
            "properties" to if (typedTraits) "{\"props\":\"$name\"}" else "{\"traits\":[\"needs-review\",\"trait-$name\"]}",
            "created_at" to TS,
            "modified_at" to TS_PLUS_1,
            "role_changed_at" to TS_PLUS_2,
            "version" to version,
            "claimed_by" to if (claim) "agent-one" else null,
            "claimed_at" to if (claim) TS else null,
            "claim_expires_at" to if (claim) "2026-03-01 11:15:30" else null,
            "original_claimed_at" to if (claim) DB_TS else null,
            // V26 lifecycle and pin set on EVERY row (absent below V26, so insert drops them): see pinColumns.
            *pinColumns()
        )

        item("root", null, 0, "Baseline root alpha", role = "work", statusLabel = "in-progress", priority = "high", version = 4)
        item("feature", "root", 1, "Baseline feature beta", role = "work", statusLabel = "in-progress", priority = "low")
        item("task1", "feature", 2, "Task one $ITEM_FTS_TOKEN", role = "blocked", previousRole = "work")
        item("task2", "feature", 2, "Task two gamma", role = "terminal", statusLabel = "done", requiresVerification = true, version = 3)
        item("task3", "feature", 2, "Task three delta", role = "terminal", statusLabel = "in-progress")
        item("task4", "feature", 2, "Task four epsilon", role = "work", statusLabel = "in-progress", claim = true)
        item("task5", "feature", 2, "Task five zeta")
        item("task6", "feature", 2, "Task six eta")
        if (typedTraits) {
            for (name in ITEM_NAMES) {
                insert(conn, "item_traits", "item_id" to id(name), "position" to 0, "name" to "needs-review")
                insert(conn, "item_traits", "item_id" to id(name), "position" to 1, "name" to "trait-$name")
            }
        }

        fun note(
            item: String,
            key: String,
            role: String,
            body: String,
            proofStatus: String? = null
        ) = insert(
            conn,
            "notes",
            "id" to id("note:$item:$key"),
            "work_item_id" to id(item),
            "key" to key,
            "role" to role,
            "body" to body,
            "created_at" to TS,
            "modified_at" to TS_PLUS_1,
            "actor_id" to if (proofStatus != null) "actor-one" else null,
            "actor_kind" to if (proofStatus != null) "subagent" else null,
            "actor_parent" to if (proofStatus != null) "orchestrator-one" else null,
            "actor_proof" to if (proofStatus != null && rawProofs) "eyJraWQiOiJrMSJ9.synthetic.baseline-proof" else null,
            "verification_status" to proofStatus,
            "verification_verifier" to if (proofStatus != null) "jwks" else null,
            "verification_reason" to if (proofStatus == "REJECTED") "signature-mismatch" else null,
            "actor_proof_sha256" to if (proofStatus != null) PROOF_SHA256 else null,
            "actor_proof_claims" to if (proofStatus != null) PROOF_CLAIMS else null,
            // V28: a non-default version (dropped below V28, where the column does not exist yet).
            "version" to if (key == "notes-extra") 3 else 1
        )
        note("feature", "feature-summary", "queue", "Summary of the baseline feature")
        note("feature", "notes-extra", "work", "A second note for the feature", proofStatus = "REJECTED")
        note("task1", "task-scope", "queue", "Scope text mentioning the $NOTE_FTS_TOKEN marker", proofStatus = "VERIFIED")

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
        // V22 tightened the type CHECK to BLOCKS|RELATES_TO. While the schema still allows IS_BLOCKED_BY (earlier
        // steps) the baseline keeps the alias row; otherwise it seeds the same edge, normalized, under the same id.
        val allowsBlockedBy =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'dependencies'").use { rs ->
                    rs.next() && rs.getString(1).contains("IS_BLOCKED_BY")
                }
            }
        if (allowsBlockedBy) dep("blocked-by", "task3", "task2", "IS_BLOCKED_BY") else dep("blocked-by", "task2", "task3", "BLOCKS")
        dep("relates", "task1", "task3", "RELATES_TO")
        dep("mutual-a", "task5", "task6", "BLOCKS")
        dep("mutual-b", "task6", "task5", "BLOCKS")

        fun transition(
            name: String,
            item: String,
            from: String,
            to: String,
            trigger: String,
            proofStatus: String?,
            fromLabel: String? = null,
            toLabel: String? = null
        ) = insert(
            conn,
            "role_transitions",
            "id" to id("transition:$name"),
            "item_id" to id(item),
            "from_role" to from,
            "to_role" to to,
            "from_status_label" to fromLabel,
            "to_status_label" to toLabel,
            "trigger" to trigger,
            "summary" to "Baseline transition $name",
            "transitioned_at" to TS,
            "actor_id" to if (proofStatus != null) "actor-one" else null,
            "actor_kind" to if (proofStatus != null) "subagent" else null,
            "actor_parent" to if (proofStatus != null) "orchestrator-one" else null,
            "actor_proof" to if (proofStatus != null && rawProofs) "eyJraWQiOiJrMSJ9.synthetic.transition-proof" else null,
            "verification_status" to proofStatus,
            "verification_verifier" to if (proofStatus != null) "jwks" else null,
            "verification_reason" to if (proofStatus == "REJECTED") "audience-mismatch" else null,
            "consumed_credentials" to if (proofStatus != null) "[\"vault:baseline-secret\"]" else null,
            "actor_proof_sha256" to if (proofStatus != null) PROOF_SHA256 else null,
            "actor_proof_claims" to if (proofStatus != null) PROOF_CLAIMS else null
        )
        transition("t2-start", "task2", "queue", "work", "start", proofStatus = "VERIFIED", fromLabel = "backlog", toLabel = "in-progress")
        transition("t2-complete", "task2", "work", "terminal", "complete", proofStatus = null, fromLabel = "in-progress", toLabel = "done")
        transition("t4-start", "task4", "queue", "work", "start", proofStatus = "REJECTED", fromLabel = "backlog", toLabel = "in-progress")

        fun lease(
            name: String,
            key: String,
            holder: String,
            expires: String,
            budget: Boolean = false
        ) = insert(
            conn,
            "resource_leases",
            "id" to id("lease:$name"),
            "resource_key" to key,
            "holder_item_id" to id(holder),
            "acquired_by_actor_id" to "actor-one",
            "acquired_at" to DB_TS,
            "expires_at" to expires,
            "original_acquired_at" to "2026-02-28 09:00:00",
            "budget_limit" to if (budget) 10 else null,
            "budget_used" to if (budget) 3 else null,
            "budget_window_seconds" to if (budget) 3600 else null,
            "version" to if (budget) 2 else 0
        )
        lease("active", "staging-environment", "task4", "2099-01-01 00:00:00", budget = true)
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
            "idempotency_records",
            "principal_id" to "actor-one",
            "operation" to "mcp.manage_items.create",
            "key" to "0b7f6c1e-2d4a-4c3e-9a51-6f0e8d2c4b17:0",
            "fingerprint" to "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "result_json" to "{\"v\":1,\"ok\":true,\"value\":{\"id\":\"baseline\"}}",
            "created_at" to "2026-03-01 10:15:30.123"
        )

        // V20: one event row with every column non-default; root_id != entity_id, principal_id != session_id.
        insert(
            conn,
            "events",
            "id" to id("event:baseline"),
            "occurred_at" to "2026-03-02 11:16:31.456",
            "root_id" to id("root"),
            "entity_kind" to "item",
            "entity_id" to id("task1"),
            "type" to "item.created",
            "req_id" to "req-baseline-0001",
            "principal_id" to "actor-one",
            "principal_kind" to "subagent",
            "proof_status" to "verified",
            "host" to "host-baseline",
            "session_id" to "session-baseline",
            "run_id" to id("run:baseline"),
            "seat" to "implementer",
            "data" to "{\"origin\":\"baseline\"}"
        )

        // V21 (+ V30 and V31 columns): two call_log rows, every column non-default and pairwise distinct (replayed 1 vs 0, attempts 2 vs 3, ...).
        val callTarget = uuid("task1")
        insert(
            conn,
            "call_log",
            "req_id" to "bl0req01",
            "at" to "2026-03-03 12:17:32.789",
            "principal_id" to "actor-one",
            "principal_kind" to "subagent",
            "proof_status" to "verified",
            "host" to "host-call-1",
            "session_id" to "session-call-1",
            "run_id" to id("run:call-1"),
            "seat" to "implementer",
            "surface" to "mcp",
            "tool" to "advance_item",
            "operation" to "start",
            "target_ids" to "[\"$callTarget\"]",
            "target_versions" to "{\"$callTarget\":3}",
            "request_shape" to "{\"includeAncestors\":true}",
            "outcome" to "ok",
            "error_code" to null,
            "attempts" to 2,
            "latency_ms" to 41,
            "request_bytes" to 310,
            "response_bytes" to 1290,
            "response_tokens_est" to 323,
            "token_method" to "bytes/4",
            "replayed" to 1,
            "batch_size" to 5,
            "failed_count" to 1,
            "result_count" to 4,
            "eligible_count" to 9,
            // V30 (absent below V30, so insert drops them).
            "hit_signals" to "[{\"id\":\"$callTarget\",\"kind\":\"item\",\"signals\":[\"edges\",\"text\"]}]",
            "lifecycle_free_rejections" to 7,
            // V31 (absent below V31, so insert drops it).
            "root_id" to id("root:call-1")
        )
        insert(
            conn,
            "call_log",
            "req_id" to "bl0req02",
            "at" to "2026-03-03 12:17:33.790",
            "principal_id" to "api:token-two",
            "principal_kind" to "external",
            "proof_status" to "unverified",
            "host" to "host-call-2",
            "session_id" to "session-call-2",
            "run_id" to id("run:call-2"),
            "seat" to "reviewer",
            "surface" to "rest",
            "tool" to "GET /api/v1/items/{id}",
            "operation" to "get",
            "target_ids" to "[\"$callTarget\"]",
            "target_versions" to "{\"$callTarget\":4}",
            "request_shape" to "{\"includeChildren\":false}",
            "outcome" to "error",
            "error_code" to "not_found",
            "attempts" to 3,
            "latency_ms" to 87,
            "request_bytes" to 205,
            "response_bytes" to 768,
            "response_tokens_est" to 192,
            "token_method" to "bytes/4",
            "replayed" to 0,
            "batch_size" to 6,
            "failed_count" to 2,
            "result_count" to 8,
            "eligible_count" to 11,
            "hit_signals" to "[{\"id\":\"$callTarget\",\"kind\":\"document\",\"signals\":[\"links\"]}]",
            "lifecycle_free_rejections" to 13,
            "root_id" to id("root:call-2")
        )

        // V23: one data_steps row, every column non-default (rows_affected 7). The table is absent below V23, so insert drops it.
        insert(
            conn,
            "data_steps",
            "name" to "baseline-probe",
            "applied_at" to "2026-03-04 13:18:33.123",
            "rows_affected" to 7,
            "binary_version" to "3.16.0"
        )

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

        // V25: the versioned config store. Absent below V25, so insert drops these rows there; project_config is
        // gone from V25 on, so the insert above is dropped there. The golden V17 stays byte-stable.
        insert(
            conn,
            "config_versions",
            "id" to id("config-version"),
            "scope" to "root",
            "root_id" to id("root"),
            "version" to 1,
            "yaml" to "project:\n  name: synthetic-baseline\n",
            "fingerprint" to "baseline-fingerprint-0001",
            "canonical_json" to "{\"project\":{\"name\":\"synthetic-baseline\"}}",
            "model_version" to 1,
            "source" to "push",
            "principal_id" to "baseline-principal",
            "req_id" to "baseline-req",
            "created_at" to TS,
            "warnings_json" to "[\"baseline warning\"]"
        )
        // V26: the server-scope version the pinned item's pinned_server_cfg names (absent below V25).
        insert(
            conn,
            "config_versions",
            "id" to id("server-config-version"),
            "scope" to "server",
            "root_id" to null,
            "version" to 1,
            "yaml" to "traits: {}\n",
            "fingerprint" to "baseline-server-fingerprint-0001",
            "canonical_json" to "{\"traits\":{}}",
            "model_version" to 1,
            "source" to "file-import",
            "principal_id" to "baseline-server-principal",
            "req_id" to "baseline-server-req",
            "created_at" to TS_PLUS_1,
            "warnings_json" to "[\"baseline server warning\"]"
        )
        insert(
            conn,
            "config_heads",
            "scope" to "root",
            "root_id" to id("root"),
            "version_id" to id("config-version"),
            "updated_at" to TS_PLUS_1
        )
        insert(
            conn,
            "config_known_fingerprints",
            "root_id" to id("root"),
            "fingerprint" to "baseline-fingerprint-0000"
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
            "modified_at" to TS_PLUS_1
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
            "modified_at" to TS_PLUS_1
        )

        // V24 tables (skipped below V24, where they do not exist). Every column carries a distinct non-default value so
        // BaselineColumnCoverageTest sees it seeded.
        insert(
            conn,
            "documents",
            "doc_rowid" to 7,
            "id" to id("document:baseline"),
            "root_item_id" to id("root"),
            "kind" to "page",
            "slug" to "baseline-page",
            "title" to "Baseline page",
            "body" to "# Baseline plan page\n",
            "content_hash" to "hash-baseline-page-v2",
            "version" to 2,
            "archived_at" to TS_PLUS_2,
            "created_at" to TS,
            "modified_at" to TS_PLUS_1
        )
        insert(
            conn,
            "document_versions",
            "id" to id("document-version:baseline"),
            "document_id" to id("document:baseline"),
            "version" to 1,
            "body" to "# Baseline plan page, an older body\n",
            "content_hash" to "hash-baseline-page-v1",
            "actor_id" to "actor-one",
            "created_at" to TS_PLUS_2
        )
        insert(
            conn,
            "links",
            "id" to id("link:baseline"),
            "root_item_id" to id("root"),
            "from_kind" to "document",
            "from_id" to id("document:baseline"),
            "to_kind" to "item",
            "to_id" to id("task1"),
            "to_ref" to "task-one",
            "relation" to "references",
            "anchor" to "overview",
            "created_at" to TS_PLUS_2
        )

        // V32 tables (skipped below V32, where they do not exist): a paused run and a closed run on the baseline root,
        // and one membership row each. Every column carries a non-default value in at least one row, and no two columns
        // of a table agree in every row, so BaselineColumnCoverageTest sees each one (all timestamps are 23 characters).
        insert(
            conn,
            "runs",
            "id" to id("run:paused"),
            "root_id" to id("root"),
            "scope_id" to id("feature"),
            "label" to "baseline-paused",
            "title" to "Baseline paused run",
            "status" to "paused",
            "pause_reason" to "manual",
            "pause_decision_id" to id("decision:baseline"),
            "terminal_reason" to null,
            "opened_by" to "actor-one",
            "opened_by_kind" to "subagent",
            "opened_at" to TS,
            "closed_by" to null,
            "closed_at" to null,
            "coordinator_seen_at" to TS_PLUS_1,
            "stall_after_s" to 600,
            "budget" to "{\"maxTokens\":500000}",
            "reported_tokens" to 1200,
            "reported_cost_micro" to 3400,
            "observed_tokens" to 1500,
            "hints" to "{\"concurrency\":2}",
            "marks" to "{\"baseline\":\"2026-03-01T10:15:31.123Z\"}",
            "version" to 3
        )
        insert(
            conn,
            "runs",
            "id" to id("run:closed"),
            "root_id" to id("root"),
            "scope_id" to null,
            "label" to "baseline-closed",
            "title" to "Baseline closed run",
            "status" to "closed",
            "pause_reason" to null,
            "pause_decision_id" to null,
            "terminal_reason" to "completed",
            "opened_by" to "api:token-two",
            "opened_by_kind" to "external",
            "opened_at" to TS_PLUS_1,
            "closed_by" to "actor-two",
            "closed_at" to TS_PLUS_3,
            "coordinator_seen_at" to TS_PLUS_2,
            "stall_after_s" to 900,
            "budget" to null,
            "reported_tokens" to 2100,
            "reported_cost_micro" to 4300,
            "observed_tokens" to 2600,
            "hints" to null,
            "marks" to null,
            "version" to 2
        )
        insert(
            conn,
            "run_items",
            "run_id" to id("run:paused"),
            "item_id" to id("task1"),
            "added_at" to TS_PLUS_2
        )
        insert(
            conn,
            "run_items",
            "run_id" to id("run:closed"),
            "item_id" to id("task2"),
            "added_at" to TS_PLUS_3
        )
    }
}

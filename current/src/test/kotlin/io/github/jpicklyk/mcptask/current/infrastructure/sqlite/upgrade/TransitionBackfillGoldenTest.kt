package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a7fa21a6, Q28b) for the V29 backfill of `role_transitions` into `events` and the
 * drop of the table: scenarios S1 (golden rehearsal), S2 (pre-V20 rows copied, post-V20 rows left to their twin events) and S3
 * (orphan and root-less items, hostile column values) of the frozen test-plan, over raw JDBC, because the rows sit below
 * the seq floor and no production reader returns them as a stream.
 *
 * Oracles (frozen task-scope section 1 and decisions D2-D6, AC1/AC2, migration-assessment s2/s3, none read from the
 * migration): every `role_transitions` row older than the installation of V20 becomes one `events` row under the SAME id with
 * `entity_kind = item`, `type = item.transitioned`, `occurred_at` = the row's canonical `transitioned_at`, `root_id` = the
 * item's stored root else its own id (an orphan keeps `item_id`), `principal_id` = `actor_id`, `principal_kind` and
 * `proof_status` lower-cased, the unused context columns NULL, and `data` a JSON object whose keys are, in this order,
 * trigger, fromRole, toRole, fromStatusLabel, toStatusLabel, summary, consumedCredentials, actorProofSha256,
 * actorProofClaims, verifier, verificationReason, actorParent, backfilled (credentials `[]` when NULL or not a JSON array,
 * claims null when NULL, `{}`, not an object or invalid JSON, `backfilled` JSON true, NO origin). The seqs are 1..N in
 * `transitioned_at` order (tie: id ascending as raw bytes) below [EventStore.SEQ_FLOOR], the AUTOINCREMENT counter is
 * untouched, a row newer than the V20 installation is NOT copied (its twin event is the record), a row whose timestamp does
 * not parse is skipped, and the table is gone. The golden database and its manifest count (3) are the checked-in
 * fixtures; every other value is an input chosen here. Forbidden-construct declaration: none.
 *
 * Fixture condition for the negative assertions: the post-V20 row is dated an hour AHEAD of the run and the V28 template
 * records V20 as installed when the JVM built it, so only a boundary that compares against the V20 installation time keeps
 * that row out; the orphan has no `work_items` row, so only an outer join keeps it in.
 */
class TransitionBackfillGoldenTest {
    @TempDir
    lateinit var dir: File

    private val floor = EventStore.SEQ_FLOOR
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    private val keyOrder =
        listOf(
            "trigger",
            "fromRole",
            "toRole",
            "fromStatusLabel",
            "toStatusLabel",
            "summary",
            "consumedCredentials",
            "actorProofSha256",
            "actorProofClaims",
            "verifier",
            "verificationReason",
            "actorParent",
            "backfilled"
        )

    private class EventRow(
        val seq: Long,
        val id: UUID,
        val occurredAt: String,
        val rootId: UUID,
        val entityKind: String,
        val entityId: UUID,
        val type: String,
        val reqId: String?,
        val host: String?,
        val sessionId: String?,
        val runId: ByteArray?,
        val seat: String?,
        val principalId: String?,
        val principalKind: String?,
        val proofStatus: String?,
        val data: JsonObject
    ) {
        val backfilled: Boolean get() = data["backfilled"] == JsonPrimitive(true)
    }

    private fun events(url: String): List<EventRow> =
        sqlRows(
            url,
            "SELECT seq, id, occurred_at, root_id, entity_kind, entity_id, type, req_id, host, session_id, run_id, seat, " +
                "principal_id, principal_kind, proof_status, data FROM events ORDER BY seq"
        ) {
            EventRow(
                seq = it.getLong(1),
                id = uuidOf(it.getBytes(2))!!,
                occurredAt = it.getString(3),
                rootId = uuidOf(it.getBytes(4))!!,
                entityKind = it.getString(5),
                entityId = uuidOf(it.getBytes(6))!!,
                type = it.getString(7),
                reqId = it.getString(8),
                host = it.getString(9),
                sessionId = it.getString(10),
                runId = it.getBytes(11),
                seat = it.getString(12),
                principalId = it.getString(13),
                principalKind = it.getString(14),
                proofStatus = it.getString(15),
                data = Json.parseToJsonElement(it.getString(16)).jsonObject
            )
        }

    private fun tableExists(
        url: String,
        name: String
    ): Boolean = sqlRows(url, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name'") { it.getString(1) }.isNotEmpty()

    private fun expectedData(
        trigger: String,
        from: String,
        to: String,
        fromLabel: String?,
        toLabel: String?,
        summary: String?,
        credentials: List<String>,
        sha: String?,
        claims: JsonObject?,
        verifier: String?,
        reason: String?,
        parent: String?
    ): JsonObject =
        buildJsonObject {
            put("trigger", trigger)
            put("fromRole", from)
            put("toRole", to)
            put("fromStatusLabel", fromLabel)
            put("toStatusLabel", toLabel)
            put("summary", summary)
            putJsonArray("consumedCredentials") { credentials.forEach { add(JsonPrimitive(it)) } }
            put("actorProofSha256", sha)
            put("actorProofClaims", claims ?: JsonNull)
            put("verifier", verifier)
            put("verificationReason", reason)
            put("actorParent", parent)
            put("backfilled", true)
        }

    private fun idOrder(names: List<String>): List<String> =
        names.sortedWith { a, b -> java.util.Arrays.compareUnsigned(BaselineDataset.id(a), BaselineDataset.id(b)) }

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 the golden database migrated to latest has no role_transitions table and one backfilled event per old row under its old id`() {
        val manifestCount = GoldenV17.manifest().getValue("count.role_transitions").toInt()
        val golden = GoldenV17.copyTo(File(dir, "s1.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        assertEquals(3, manifestCount, "fixture: the golden holds three transitions")

        UpgradeHarness.migrate(url)

        assertFalse(tableExists(url, "role_transitions"), "role_transitions is dropped")
        val rows = events(url)
        val transitioned = rows.filter { it.type == "item.transitioned" }
        assertEquals(manifestCount, transitioned.size, "exactly one item.transitioned event per old row")
        assertEquals(rows.size, transitioned.size, "the backfill writes nothing else")

        val names = listOf("transition:t2-start", "transition:t2-complete", "transition:t4-start")
        assertEquals(names.map { BaselineDataset.uuid(it) }.toSet(), transitioned.map { it.id }.toSet(), "the old ids are kept")

        // All three rows share one timestamp, so the seq order is the id tie-break (ascending raw bytes).
        val expectedOrder = idOrder(names)
        assertEquals(expectedOrder.map { BaselineDataset.uuid(it) }, transitioned.map { it.id }, "seq order: transitioned_at, then id")
        assertEquals(listOf(1L, 2L, 3L), transitioned.map { it.seq }, "seqs are 1..N")
        assertTrue(transitioned.all { it.seq < floor })
        assertEquals(
            floor,
            sqlRows(url, "SELECT seq FROM sqlite_sequence WHERE name = 'events'") { it.getLong(1) }.single(),
            "counter untouched"
        )

        val claims = Json.parseToJsonElement(BaselineDataset.PROOF_CLAIMS).jsonObject
        val expected =
            mapOf(
                "transition:t2-start" to
                    Triple(
                        "task2",
                        expectedData(
                            "start",
                            "queue",
                            "work",
                            "backlog",
                            "in-progress",
                            "Baseline transition t2-start",
                            listOf("vault:baseline-secret"),
                            BaselineDataset.PROOF_SHA256,
                            claims,
                            "jwks",
                            null,
                            "orchestrator-one"
                        ),
                        Triple("actor-one", "subagent", "verified")
                    ),
                "transition:t2-complete" to
                    Triple(
                        "task2",
                        expectedData(
                            "complete",
                            "work",
                            "terminal",
                            "in-progress",
                            "done",
                            "Baseline transition t2-complete",
                            emptyList(),
                            null,
                            null,
                            null,
                            null,
                            null
                        ),
                        Triple(null, null, null)
                    ),
                "transition:t4-start" to
                    Triple(
                        "task4",
                        expectedData(
                            "start",
                            "queue",
                            "work",
                            "backlog",
                            "in-progress",
                            "Baseline transition t4-start",
                            listOf("vault:baseline-secret"),
                            BaselineDataset.PROOF_SHA256,
                            claims,
                            "jwks",
                            "audience-mismatch",
                            "orchestrator-one"
                        ),
                        Triple("actor-one", "subagent", "rejected")
                    )
            )
        for ((name, spec) in expected) {
            val (item, data, principal) = spec
            val row = transitioned.single { it.id == BaselineDataset.uuid(name) }
            assertEquals(BaselineDataset.TS, row.occurredAt, "$name: occurred_at is the canonical transitioned_at")
            assertEquals(BaselineDataset.uuid("root"), row.rootId, "$name: root_id is the item's stored root")
            assertEquals("item", row.entityKind, name)
            assertEquals(BaselineDataset.uuid(item), row.entityId, name)
            assertEquals(principal.first, row.principalId, "$name: principal_id")
            assertEquals(principal.second, row.principalKind, "$name: principal_kind is lower-case")
            assertEquals(principal.third, row.proofStatus, "$name: proof_status is lower-case")
            assertNull(row.reqId, name)
            assertNull(row.host, name)
            assertNull(row.sessionId, name)
            assertNull(row.runId, name)
            assertNull(row.seat, name)
            assertEquals(keyOrder, row.data.keys.toList(), "$name: data keys, in the documented order")
            assertEquals(data, row.data, "$name: data values")
            assertFalse("origin" in row.data, "$name: a backfilled row carries no origin")
            assertTrue(row.backfilled)
        }

        val report =
            mapOf(
                "rows" to manifestCount,
                "backfilled" to rows.count { it.backfilled },
                "skippedPostV20" to 0,
                "orphans" to
                    rows.count { r ->
                        sqlRows(url, "SELECT COUNT(*) FROM work_items WHERE id = ?", {
                            it.setBytes(1, BaselineDataset.bytes(r.entityId))
                        }) { it.getInt(1) }.single() ==
                            0
                    },
                "unparseable" to 0
            )
        println("V29 golden rehearsal report: $report")
        assertEquals(3, report["backfilled"])
        assertEquals(0, report["orphans"], "the golden has no orphan transition")
    }

    @Test
    fun `S1 the stored V20 installation time has the text shape the boundary compares against`() {
        val golden = GoldenV17.copyTo(File(dir, "s1-shape.sqlite"))
        val url = UpgradeHarness.urlFor(golden)

        UpgradeHarness.migrate(url)

        val installedOn = sqlRows(url, "SELECT installed_on FROM flyway_schema_history WHERE version = '20'") { it.getString(1) }.single()
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,3})?""").matches(installedOn),
            "installed_on is UTC text YYYY-MM-DD HH:MM:SS[.SSS], lexicographically comparable with a canonical timestamp: '$installedOn'"
        )
    }

    // ------------------------------------------------------------------ S2

    private fun insertItem(
        conn: Connection,
        name: String
    ) = BaselineDataset.extraItem(conn, name, "work", "in-progress")

    private fun insertTransition(
        conn: Connection,
        name: String,
        item: String,
        at: String,
        trigger: String = "start",
        summary: String? = "summary of $name",
        actorId: String? = null,
        actorKind: String? = null,
        actorParent: String? = null,
        status: String? = null,
        credentials: String? = null,
        sha: String? = null,
        claims: String? = null
    ) = BaselineDataset.insert(
        conn,
        "role_transitions",
        "id" to BaselineDataset.id("transition:$name"),
        "item_id" to BaselineDataset.id(item),
        "from_role" to "queue",
        "to_role" to "work",
        "from_status_label" to "backlog",
        "to_status_label" to "in-progress",
        "trigger" to trigger,
        "summary" to summary,
        "transitioned_at" to at,
        "actor_id" to actorId,
        "actor_kind" to actorKind,
        "actor_parent" to actorParent,
        "verification_status" to status,
        "verification_verifier" to if (status != null) "jwks" else null,
        "consumed_credentials" to credentials,
        "actor_proof_sha256" to sha,
        "actor_proof_claims" to claims
    )

    @Test
    fun `S2 only rows older than the V20 installation are copied and each item ends with exactly one event per transition`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "s2.sqlite"))
        // Time order is the OPPOSITE of id order, so a seq assigned by id would show.
        val (lowId, highId) =
            idOrder(listOf("transition:pre-a", "transition:pre-b")).map { it.removePrefix("transition:") }.let {
                it[0] to
                    it[1]
            }
        val olderName = highId
        val newerName = lowId
        val postAt = stamp.format(Instant.now().plusSeconds(3600))
        val twinId = UUID.randomUUID()
        DriverManager.getConnection(url).use { conn ->
            for (n in listOf("pre-a", "pre-b", "post")) insertItem(conn, n)
            insertTransition(
                conn,
                olderName,
                olderName,
                "2026-03-01 09:00:00.000",
                actorId = "legacy-agent",
                actorKind = "SUBAGENT",
                actorParent = "legacy-parent",
                status = "VERIFIED",
                credentials = "[\"vault:one\",\"vault:two\"]",
                sha = "sha-legacy",
                claims = "{\"iss\":\"https://legacy.example\"}"
            )
            insertTransition(conn, newerName, newerName, "2026-03-01 09:00:05.000")
            insertTransition(conn, "post", "post", postAt)
            BaselineDataset.insert(
                conn,
                "events",
                "seq" to floor + 1,
                "id" to BaselineDataset.bytes(twinId),
                "occurred_at" to postAt,
                "root_id" to BaselineDataset.id("root"),
                "entity_kind" to "item",
                "entity_id" to BaselineDataset.id("post"),
                "type" to "item.transitioned",
                "data" to "{\"trigger\":\"start\",\"origin\":\"user\"}"
            )
        }

        UpgradeHarness.migrate(url)

        assertFalse(tableExists(url, "role_transitions"))
        val rows = events(url)
        assertEquals(3, rows.size, "two backfilled rows plus the untouched twin: ${rows.map { it.id }}")
        val backfilled = rows.filter { it.backfilled }
        assertEquals(
            listOf(BaselineDataset.uuid("transition:$olderName"), BaselineDataset.uuid("transition:$newerName")),
            backfilled.map { it.id },
            "backfilled in transitioned_at order, not id order"
        )
        assertEquals(listOf(1L, 2L), backfilled.map { it.seq })
        assertTrue(rows.none { it.id == BaselineDataset.uuid("transition:post") }, "the post-V20 row is not copied")
        val twin = rows.single { it.id == twinId }
        assertEquals(floor + 1, twin.seq, "the twin keeps its seq")
        assertFalse(twin.backfilled)
        assertEquals("user", (twin.data["origin"] as JsonPrimitive).content)
        for (item in listOf(olderName, newerName, "post")) {
            assertEquals(1, rows.count { it.entityId == BaselineDataset.uuid(item) }, "$item has exactly one event for its one transition")
        }
        assertEquals(
            floor + 1,
            sqlRows(url, "SELECT seq FROM sqlite_sequence WHERE name = 'events'") {
                it.getLong(1)
            }.single(),
            "counter untouched"
        )

        val older = backfilled[0]
        assertEquals("2026-03-01 09:00:00.000", older.occurredAt)
        assertEquals("legacy-agent", older.principalId)
        assertEquals("subagent", older.principalKind, "an upper-case legacy actor_kind is lower-cased")
        assertEquals("verified", older.proofStatus, "an upper-case legacy status is lower-cased")
        assertEquals(
            expectedData(
                "start",
                "queue",
                "work",
                "backlog",
                "in-progress",
                "summary of $olderName",
                listOf("vault:one", "vault:two"),
                "sha-legacy",
                buildJsonObject { put("iss", "https://legacy.example") },
                "jwks",
                null,
                "legacy-parent"
            ),
            older.data
        )
        val newer = backfilled[1]
        assertNull(newer.principalId)
        assertNull(newer.principalKind)
        assertNull(newer.proofStatus)
        assertEquals(
            expectedData(
                "start",
                "queue",
                "work",
                "backlog",
                "in-progress",
                "summary of $newerName",
                emptyList(),
                null,
                null,
                null,
                null,
                null
            ),
            newer.data
        )
    }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 an orphan transition and a root-less item are kept with their own id as root and an unparseable timestamp is skipped`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "s3.sqlite"))
        DriverManager.getConnection(url).use { conn ->
            insertItem(conn, "rootless")
            conn.prepareStatement("UPDATE work_items SET root_id = NULL WHERE id = ?").use {
                it.setBytes(1, BaselineDataset.id("rootless"))
                assertEquals(1, it.executeUpdate())
            }
            insertItem(conn, "rooted")
            insertTransition(conn, "orphan", "orphan-item-without-a-row", "2026-03-01 09:00:00.000")
            insertTransition(conn, "rootless", "rootless", "2026-03-01 09:00:01.000")
            insertTransition(conn, "rooted", "rooted", "2026-03-01 09:00:02.000")
            insertTransition(conn, "garbled", "rooted", "not-a-timestamp")
        }

        UpgradeHarness.migrate(url)

        val rows = events(url)
        val byId = rows.associateBy { it.id }
        val orphan = byId.getValue(BaselineDataset.uuid("transition:orphan"))
        assertEquals(BaselineDataset.uuid("orphan-item-without-a-row"), orphan.entityId)
        assertEquals(orphan.entityId, orphan.rootId, "an orphan keeps its item id as its root")
        val rootless = byId.getValue(BaselineDataset.uuid("transition:rootless"))
        assertEquals(BaselineDataset.uuid("rootless"), rootless.rootId, "an item with no stored root is its own root")
        assertEquals(BaselineDataset.uuid("root"), byId.getValue(BaselineDataset.uuid("transition:rooted")).rootId, "a stored root wins")
        assertFalse(BaselineDataset.uuid("transition:garbled") in byId, "a row whose timestamp does not parse is skipped, not guessed")
        assertEquals(3, rows.size)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.seq })
        assertEquals(
            listOf("transition:orphan", "transition:rootless", "transition:rooted").map { BaselineDataset.uuid(it) },
            rows.map { it.id },
            "seq follows transitioned_at"
        )
    }

    @Test
    fun `S3 hostile column values land as the documented defaults and same-second rows are ordered by id`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "s3-hostile.sqlite"))
        val sameSecond = "2026-03-01 09:00:00.000"
        val tieNames = listOf("tie-a", "tie-b", "tie-c")
        DriverManager.getConnection(url).use { conn ->
            insertItem(conn, "hostile")
            insertTransition(
                conn,
                "bad-creds-text",
                "hostile",
                "2026-03-01 08:00:00.000",
                credentials = "{not json",
                status = "VERIFIED",
                actorId = "a",
                actorKind = "user",
                claims = "nope"
            )
            insertTransition(conn, "creds-object", "hostile", "2026-03-01 08:00:01.000", credentials = "{\"a\":1}", claims = "{}")
            insertTransition(conn, "claims-string", "hostile", "2026-03-01 08:00:02.000", claims = "\"abc\"", credentials = "[]")
            insertTransition(conn, "claims-object", "hostile", "2026-03-01 08:00:03.000", claims = "{\"iss\":\"x\"}")
            insertTransition(conn, "non-ascii", "hostile", "2026-03-01 08:00:04.000", summary = "r\u00e9sum\u00e9 \u2603 \u65e5\u672c")
            for (n in tieNames) insertTransition(conn, n, "hostile", sameSecond)
        }

        UpgradeHarness.migrate(url)

        val byId = events(url).associateBy { it.id }

        fun data(name: String) = byId.getValue(BaselineDataset.uuid("transition:$name")).data

        assertEquals(JsonArray(emptyList()), data("bad-creds-text")["consumedCredentials"], "invalid credentials JSON becomes []")
        assertEquals(JsonNull, data("bad-creds-text")["actorProofClaims"], "invalid claims JSON becomes null")
        assertEquals(JsonArray(emptyList()), data("creds-object")["consumedCredentials"], "credentials that are not an array become []")
        assertEquals(JsonNull, data("creds-object")["actorProofClaims"], "an empty claims object becomes null")
        assertEquals(
            JsonNull,
            data("claims-string")["actorProofClaims"],
            "claims that are a bare JSON string become null, not a string value"
        )
        assertEquals(
            buildJsonObject { put("iss", "x") },
            data("claims-object")["actorProofClaims"],
            "control: real claims are kept as an object"
        )
        assertEquals(JsonPrimitive("r\u00e9sum\u00e9 \u2603 \u65e5\u672c"), data("non-ascii")["summary"], "non-ASCII text survives")
        assertEquals("user", byId.getValue(BaselineDataset.uuid("transition:bad-creds-text")).principalKind)

        val tieRows = tieNames.map { byId.getValue(BaselineDataset.uuid("transition:$it")) }
        val bySeq = tieRows.sortedBy { it.seq }.map { it.id }
        val expectedTieOrder =
            tieNames
                .map { BaselineDataset.id("transition:$it") to BaselineDataset.uuid("transition:$it") }
                .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first, b.first) }
                .map { it.second }
        assertEquals(expectedTieOrder, bySeq, "rows with the same timestamp get ascending seqs in id (raw byte) order")
        assertEquals(listOf(6L, 7L, 8L), tieRows.map { it.seq }.sorted(), "the five earlier-dated rows take seqs 1..5, the tie rows follow")
    }
}

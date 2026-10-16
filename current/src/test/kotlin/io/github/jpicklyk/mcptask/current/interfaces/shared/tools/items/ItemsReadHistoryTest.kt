package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.P11_LABELS_YAML
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a7fa21a6, Q28b) for the `items_read` select `history`: scenarios S5 to S11 of the
 * frozen test-plan, through the production REST topology (`POST /api/v1/ops/items_read`, real bearer principals) and, for the
 * parity and profile checks, the MCP adapter and the dispatcher.
 *
 * Oracles (frozen task-scope sections 1 and 2, decisions D2, D4, D7 to D10, AC4 to AC6; none read from an implementation):
 * `history` with `itemId` answers that item's `item.transitioned` events newest first with an exact total, without `itemId`
 * the whole stream under the caller's scope; `limit` defaults to 50 and is 1..200, `since > until` is `invalid_request` on
 * `/since`, there is NO default time window; an entry is `{seq, id, itemId, occurredAt, trigger, fromRole, toRole,
 * fromStatusLabel?, toStatusLabel?, origin?, summary?, consumedCredentials? (omitted when empty), backfilled?: true,
 * actor?: {id, kind, parent?}, verification?: {status, verifier?, reason?}}` where null and absent are both omitted,
 * `actor`/`verification` are shown only to a caller who may see attribution (an admin; the `notes_read` rule) and the proof
 * hash and claims are never rendered; a restricted scope sees only its roots/tags, an out-of-scope or unknown itemId is
 * `forbidden` (never `not_found`), a row whose item is gone is invisible to a restricted scope and an unrestricted caller
 * gets `not_found` for the gone item's id; the two 3.x REST transition routes answer 404; MCP and `/ops` bodies are equal
 * apart from `meta.reqId`; the knowledge profile does not offer `history`.
 *
 * Rows are produced two ways: real advances through the production advance service (where the recorded inputs are the
 * oracle), and event rows appended with chosen data (where paging, scope and volume are the subject). A backfilled row
 * below the seq floor is inserted with raw SQL in the shape the V29 migration writes.
 *
 * Probes run: same-second ordering, an 8-hex prefix that is ambiguous and one that is not, a non-ASCII summary, a 250-row
 * paging walk, `since == until`, blank and malformed `itemId`. Inapplicable: replay of a write (a read), path/UNC variants.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsReadHistoryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    private val subagent = ActorClaim(id = "agent-q28b", kind = ActorKind.SUBAGENT, parent = "parent-q28b")
    private val verified =
        VerificationResult(
            status = VerificationStatus.VERIFIED,
            verifier = "jwks",
            reason = "reason-q28b",
            proofSha256 = "sha-q28b",
            proofClaims =
                ProofClaims(
                    iss = "https://issuer.q28b.example",
                    sub = "agent-q28b",
                    aud = listOf("aud-q28b"),
                    jti = "jti-q28b",
                    iat = 1_700_000_000L,
                    exp = 1_700_000_300L,
                    kid = "kid-q28b",
                    alg = "RS256"
                )
        )

    private fun h(extra: String = "") = """{"select":"history"$extra}"""

    private fun forItem(
        id: Any,
        extra: String = ""
    ) = h(""","itemId":"$id"$extra""")

    private fun idBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun uuid(text: String): UUID = UUID.fromString(text)

    /** The `data` text of a live `item.transitioned` row as the writers emit it: only keys that have a value. */
    private fun transitionData(
        trigger: String = "start",
        from: String = "queue",
        to: String = "work",
        summary: String? = null
    ): String =
        buildJsonObject {
            put("trigger", trigger)
            put("fromRole", from)
            put("toRole", to)
            put("origin", "user")
            summary?.let { put("summary", it) }
        }.toString()

    private suspend fun EventLogRig.addRow(
        item: WorkItem,
        at: Instant,
        data: String = transitionData(),
        principalId: String? = null,
        principalKind: String? = null,
        proofStatus: String? = null,
        id: UUID = UUID.randomUUID()
    ): EventRecord =
        raw
            .eventStore()
            .append(
                listOf(
                    EventRecord(
                        id = id,
                        occurredAt = at,
                        rootId = item.rootId ?: item.id,
                        entityKind = DomainEvent.KIND_ITEM,
                        entityId = item.id,
                        type = DomainEvent.ITEM_TRANSITIONED,
                        principalId = principalId,
                        principalKind = principalKind,
                        proofStatus = proofStatus,
                        data = data
                    )
                )
            ).single()

    private suspend fun EventLogRig.applied(
        item: WorkItem,
        trigger: String,
        summary: String? = null,
        actor: ActorClaim? = null,
        verification: VerificationResult? = null,
        refs: List<String> = emptyList()
    ) {
        val current = checkNotNull(raw.workItemRepository().getById(item.id))
        val outcome =
            ctx.advanceServiceFactory().forItem(current).advance(
                current,
                trigger,
                summary,
                actor,
                verification,
                credentialRefs = refs
            )
        val success = assertIs<AdvanceOutcome.Success>(outcome, "the advance must apply: $outcome")
        assertTrue(success.result.applied, "the transition must be applied: $outcome")
    }

    private fun JsonObject.number(name: String): Long {
        val p = getValue(name).jsonPrimitive
        assertFalse(p.isString, "$name is a JSON number: $p")
        return p.long
    }

    private fun JsonObject.sub(name: String): JsonObject = getValue(name).jsonObject

    // ------------------------------------------------------------------ S5 / S11

    @Test
    fun `S5 history of an item lists its transitions newest first and every field equals what the advances were given`(): Unit =
        irApp(db.db, dir, yaml = P11_LABELS_YAML) { rig ->
            val item = rig.make("Subject")
            rig.applied(item, "start", summary = "Begin q28b", actor = subagent, verification = verified, refs = listOf("c1", "c2"))
            rig.applied(item, "complete")
            val stored = rig.rows().filter { it.type == DomainEvent.ITEM_TRANSITIONED }
            assertEquals(2, stored.size, "fixture: two applied advances")

            val r = select(forItem(item.id))

            assertEquals(200, r.status, r.raw)
            assertEquals(2, r.total)
            val newest = r.items[0]
            val oldest = r.items[1]
            assertEquals(stored[1].seq, newest.number("seq"))
            assertEquals(stored[0].seq, oldest.number("seq"))
            for ((entry, row) in listOf(newest to stored[1], oldest to stored[0])) {
                assertEquals(item.id.toString(), entry.str("itemId"), "itemId is the full UUID")
                assertEquals(row.occurredAt, Instant.parse(entry.str("occurredAt")), "occurredAt is the recorded instant in ISO-8601 UTC")
                assertTrue(entry.str("occurredAt").endsWith("Z"), entry.str("occurredAt"))
                assertTrue(entry.str("id").startsWith(row.id.toString().substring(0, 8)), "id names the event row: ${entry.str("id")}")
                assertFalse("backfilled" in entry, "a live row is not marked backfilled: $entry")
            }
            assertEquals("complete", newest.str("trigger"))
            assertEquals("work", newest.str("fromRole"))
            assertEquals("terminal", newest.str("toRole"))
            assertEquals("p11-start-label", newest.str("fromStatusLabel"))
            assertEquals("p11-complete-label", newest.str("toStatusLabel"))
            assertFalse("consumedCredentials" in newest, "an empty credential list is omitted: $newest")
            assertFalse("summary" in newest, "a transition with no summary has no summary key: $newest")
            assertFalse("actor" in newest, "a transition with no actor has no actor key: $newest")

            assertEquals("start", oldest.str("trigger"))
            assertEquals("queue", oldest.str("fromRole"))
            assertEquals("work", oldest.str("toRole"))
            assertEquals("p11-start-label", oldest.str("toStatusLabel"))
            assertEquals("Begin q28b", oldest.str("summary"))
            assertEquals(listOf("c1", "c2"), oldest.strings("consumedCredentials"))
            val actor = oldest.sub("actor")
            assertEquals("agent-q28b", actor.str("id"))
            assertEquals("subagent", actor.str("kind"))
            assertEquals("parent-q28b", actor.str("parent"))
            val verification = oldest.sub("verification")
            assertEquals("verified", verification.str("proofStatus"))
            assertEquals("jwks", verification.str("verifier"))
            assertEquals("reason-q28b", verification.str("reason"))

            for (evidence in listOf(
                "sha-q28b",
                "issuer.q28b",
                "jti-q28b",
                "kid-q28b",
                "RS256",
                "aud-q28b",
                "actorProofSha256",
                "actorProofClaims",
                "\"proof\""
            )) {
                assertFalse(evidence in r.raw, "proof evidence ($evidence) is never rendered: ${r.raw}")
            }
        }

    @Test
    fun `S11 one applied advance records exactly one history row`(): Unit =
        irApp(db.db, dir, yaml = P11_LABELS_YAML) { rig ->
            val item = rig.make("Once")
            val before = rig.rows().size

            rig.applied(item, "start")

            assertEquals(1, rig.rows().drop(before).count { it.type == DomainEvent.ITEM_TRANSITIONED }, "one item.transitioned row")
            val r = select(forItem(item.id))
            assertEquals(1, r.total)
            assertEquals(1, r.items.size)
            assertEquals("start", r.items.single().str("trigger"))
        }

    @Test
    fun `S5 an item with no transitions is an ok empty page`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Quiet")

            val r = select(forItem(item.id))

            assertEquals(200, r.status, r.raw)
            assertTrue(r.ok)
            assertEquals(emptyList(), r.items)
            assertEquals(0, r.total)
        }

    @Test
    fun `S5 limit and cursor walk an item's history newest first without gaps or repeats`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Walked")
            val rows = (0 until 5).map { rig.addRow(item, t0.plusSeconds(it.toLong())) }
            val expected = rows.reversed().map { it.seq }

            val seen = mutableListOf<Long>()
            var cursor: String? = null
            var pages = 0
            while (seen.size < 5 && pages < 10) {
                val extra = ""","limit":2""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(forItem(item.id, extra))
                assertEquals(200, r.status, r.raw)
                assertEquals(5, r.total, "the total is exact on every page")
                assertTrue(r.items.size <= 2)
                seen += r.items.map { it.number("seq") }
                if (seen.size < 5) cursor = assertNotNull(r.next, "a truncated page carries next")
                pages++
            }

            assertEquals(expected, seen)
            assertEquals(3, pages)
        }

    @Test
    fun `S5 the default page is 50, limit 200 is the maximum and a 250-row history pages through exactly once`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Long")
            val records =
                (0 until 250).map {
                    EventRecord(
                        id = UUID.randomUUID(),
                        occurredAt = t0.plusSeconds(it.toLong()),
                        rootId = item.id,
                        entityKind = DomainEvent.KIND_ITEM,
                        entityId = item.id,
                        type = DomainEvent.ITEM_TRANSITIONED,
                        data = transitionData()
                    )
                }
            val stored = rig.raw.eventStore().append(records)
            val expected = stored.reversed().map { it.seq }

            val default = select(forItem(item.id))
            assertEquals(50, default.items.size)
            assertEquals(250, default.total)
            assertNotNull(default.next)

            val seen = mutableListOf<Long>()
            var cursor: String? = null
            var pages = 0
            while (seen.size < 250 && pages < 5) {
                val extra = ""","limit":200""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(forItem(item.id, extra))
                assertEquals(250, r.total)
                seen += r.items.map { it.number("seq") }
                if (seen.size < 250) cursor = assertNotNull(r.next)
                pages++
            }
            assertEquals(expected, seen, "newest first, every row exactly once")
            assertEquals(2, pages, "200 then 50")
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 global history has no default time window, orders newest first and counts every visible row`(): Unit =
        irApp(db.db, dir) { rig ->
            val a = rig.make("A")
            val b = rig.make("B")
            val ancient = rig.addRow(a, Instant.parse("2020-01-01T00:00:00Z"))
            val recent = rig.addRow(b, Instant.now().minusSeconds(60))

            val r = select(h())

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf(recent.seq, ancient.seq), r.items.map { it.number("seq") }, "a 2020 row is not cut off by a default window")
            assertEquals(2, r.total)
        }

    @Test
    fun `S6 since and until are inclusive bounds and since equal to until matches the rows at that instant`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Windowed")
            val rows = (0 until 4).map { rig.addRow(item, t0.plusSeconds(it.toLong())) }

            val window = select(h(""","since":"2026-01-01T00:00:01Z","until":"2026-01-01T00:00:02Z""""))
            assertEquals(listOf(rows[2].seq, rows[1].seq), window.items.map { it.number("seq") }, "both ends inclusive")
            assertEquals(2, window.total)

            val point = select(h(""","since":"2026-01-01T00:00:01Z","until":"2026-01-01T00:00:01Z""""))
            assertEquals(listOf(rows[1].seq), point.items.map { it.number("seq") })

            val onlySince = select(h(""","since":"2026-01-01T00:00:02Z""""))
            assertEquals(listOf(rows[3].seq, rows[2].seq), onlySince.items.map { it.number("seq") })

            val onlyUntil = select(h(""","until":"2026-01-01T00:00:00Z""""))
            assertEquals(listOf(rows[0].seq), onlyUntil.items.map { it.number("seq") })
        }

    @Test
    fun `S6 an inverted range is invalid_request on since, a bad timestamp is invalid_request on its own member`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.addRow(rig.make("Any"), t0)

            val inverted = select(h(""","since":"2026-02-01T00:00:00Z","until":"2026-01-01T00:00:00Z""""))
            assertEquals("invalid_request", inverted.code, inverted.raw)
            assertEquals(400, inverted.status)
            assertTrue("/since" in inverted.fields(), inverted.raw)

            val badSince = select(h(""","since":"yesterday""""))
            assertEquals("invalid_request", badSince.code, badSince.raw)
            assertTrue("/since" in badSince.fields(), badSince.raw)

            val badUntil = select(h(""","until":"tomorrow""""))
            assertEquals("invalid_request", badUntil.code, badUntil.raw)
            assertTrue("/until" in badUntil.fields(), badUntil.raw)
        }

    @Test
    fun `S6 limit outside 1 to 200 is invalid_request on limit, a malformed cursor is invalid_cursor and unknown members are refused`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.addRow(rig.make("Any"), t0)

            for (bad in listOf("0", "201", "-1")) {
                val r = select(h(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertEquals(400, r.status)
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            assertEquals(200, select(h(""","limit":200""")).status, "control: 200 is the maximum")
            assertEquals(1, select(h(""","limit":1""")).items.size, "control: 1 is the minimum")

            for (cursor in listOf("not-a-cursor", "!!!")) {
                val r = select(h(""","cursor":"$cursor""""))
                assertEquals("invalid_cursor", r.code, "'$cursor': ${r.raw}")
                assertEquals(400, r.status)
            }

            val unknown = select(h(""","bogus":1"""))
            assertEquals("unknown_parameter", unknown.code, unknown.raw)
            assertTrue(unknown.parameters().any { it.endsWith("bogus") }, unknown.raw)
        }

    @Test
    fun `S6 a malformed or blank itemId is invalid_request on itemId`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.addRow(rig.make("Any"), t0)

            for (raw in listOf("abcdef1", "a".repeat(33), "zzzzzzzz", "abcd-123", "")) {
                val r = select(forItem(raw))
                assertEquals("invalid_request", r.code, "'$raw': ${r.raw}")
                assertEquals(400, r.status, "'$raw'")
                assertTrue("/itemId" in r.fields(), "'$raw': ${r.fields()}")
            }
        }

    @Test
    fun `S6 same-instant rows keep newest-seq-first order`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Tied")
            val first = rig.addRow(item, t0)
            val second = rig.addRow(item, t0)
            val third = rig.addRow(item, t0)

            val r = select(forItem(item.id))

            assertEquals(listOf(third.seq, second.seq, first.seq), r.items.map { it.number("seq") })
        }

    @Test
    fun `S6 a well-formed id that names nothing is not_found for an unrestricted caller and an 8-hex prefix resolves or is ambiguous`(): Unit =
        irApp(db.db, dir) { rig ->
            val a = rig.make("A", id = uuid("abcd1234-0000-4000-8000-000000000001"))
            rig.make("B", id = uuid("abcd1234-1111-4111-8111-111111111111"))
            val c = rig.make("C", id = uuid("beef0000-0000-4000-8000-000000000001"))
            rig.addRow(a, t0)
            rig.addRow(c, t0.plusSeconds(1))

            val missing = select(forItem("99999999-9999-4999-8999-999999999999"))
            assertEquals(404, missing.status, missing.raw)
            assertEquals("not_found", missing.code)

            val ambiguous = select(forItem("abcd1234"))
            assertEquals("ambiguous_id", ambiguous.code, ambiguous.raw)
            assertEquals(ErrorCode.AMBIGUOUS_ID.httpStatus, ambiguous.status)

            val unique = select(forItem("beef0000"))
            assertEquals(200, unique.status, unique.raw)
            assertEquals(1, unique.total)
            assertEquals(c.id.toString(), unique.items.single().str("itemId"))
        }

    @Test
    fun `S6 a non-ASCII summary is rendered intact`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Unicode")
            val summary = "résumé ☃ 日本語"
            rig.addRow(item, t0, data = transitionData(summary = summary))

            val r = select(forItem(item.id))

            assertEquals(summary, r.items.single().str("summary"))
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 a root-scoped token sees only its roots globally and is forbidden for an out-of-scope or unknown itemId`() {
        val r1 = uuid("a1a1a1a1-0000-4000-8000-00000000000a")
        val r2 = uuid("b2b2b2b2-0000-4000-8000-00000000000b")
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val root1 = rig.make("Mine-root", id = r1)
            val own = rig.make("Mine-child", root1)
            val foreign = rig.make("Beta-secret", id = r2)
            val ownRoot = rig.addRow(root1, t0)
            val ownChild = rig.addRow(own, t0.plusSeconds(1))
            rig.addRow(foreign, t0.plusSeconds(2))

            val global = select(h(), TEST_TOKEN)
            assertEquals(200, global.status, global.raw)
            assertEquals(listOf(ownChild.seq, ownRoot.seq), global.items.map { it.number("seq") })
            assertEquals(2, global.total, "the total counts visible rows only")
            assertFalse(foreign.id.toString() in global.raw || "Beta-secret" in global.raw, "no trace of the foreign item: ${global.raw}")

            assertEquals(1, select(forItem(own.id), TEST_TOKEN).total, "control: an in-scope item answers")

            val forbidden = select(forItem(foreign.id), TEST_TOKEN)
            assertEquals(403, forbidden.status, forbidden.raw)
            assertEquals("forbidden", forbidden.code)
            assertEquals("root", forbidden.detail.str("scope"))
            assertEquals(r1.toString(), forbidden.detail.str("required"))
            assertFalse(foreign.id.toString().substring(0, 8) in forbidden.raw, "no trace of the foreign item: ${forbidden.raw}")

            val missing = select(forItem("99999999-9999-4999-8999-999999999999"), TEST_TOKEN)
            assertEquals(403, missing.status, "an unknown id under a restricted scope is forbidden, never not_found: ${missing.raw}")
            assertEquals("forbidden", missing.code)

            assertEquals(3, select(h()).total, "control: the unrestricted caller sees all three rows")
        }
    }

    @Test
    fun `S7 a tag-scoped token never sees an untagged or deleted item's rows and an unrestricted caller still does`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val tagged = rig.make("Tagged", tags = "api")
            val untagged = rig.make("Untagged")
            val gone = rig.make("Gone", tags = "api")
            val tagRow = rig.addRow(tagged, t0)
            rig.addRow(untagged, t0.plusSeconds(1))
            val goneRow = rig.addRow(gone, t0.plusSeconds(2))
            rig.raw.workItemRepository().delete(gone.id)

            val scoped = select(h(), TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf(tagRow.seq), scoped.items.map { it.number("seq") })
            assertEquals(1, scoped.total)

            val untaggedItem = select(forItem(untagged.id), TEST_TOKEN)
            assertEquals(403, untaggedItem.status, untaggedItem.raw)
            assertEquals("forbidden", untaggedItem.code)
            assertEquals("tag", untaggedItem.detail.str("scope"))
            assertEquals("api", untaggedItem.detail.str("required"))

            val goneItem = select(forItem(gone.id), TEST_TOKEN)
            assertEquals("forbidden", goneItem.code, "a deleted item under a restricted scope is forbidden: ${goneItem.raw}")

            val all = select(h())
            assertEquals(3, all.total, "control: the unrestricted caller sees every row, the deleted item's included")
            assertTrue(goneRow.seq in all.items.map { it.number("seq") })
            val goneUnrestricted = select(forItem(gone.id))
            assertEquals(404, goneUnrestricted.status, "the per-item read of a gone item is not_found: ${goneUnrestricted.raw}")
            assertEquals("not_found", goneUnrestricted.code)
        }

    // ------------------------------------------------------------------ S8

    @Test
    fun `S8 attribution is shown to an admin and withheld from a non-admin, and the proof hash and claims are shown to nobody`(): Unit =
        irApp(db.db, dir, yaml = P11_LABELS_YAML) { rig ->
            val item = rig.make("Attributed")
            rig.applied(item, "start", actor = subagent, verification = verified)

            val admin = select(forItem(item.id)).items.single()
            assertEquals("agent-q28b", admin.sub("actor").str("id"))
            assertEquals("verified", admin.sub("verification").str("proofStatus"))

            val reader = select(forItem(item.id), TEST_TOKEN)
            assertEquals(200, reader.status, reader.raw)
            val entry = reader.items.single()
            assertFalse("actor" in entry, "a non-admin sees no actor: $entry")
            assertFalse("verification" in entry, "a non-admin sees no verification: $entry")
            for (hidden in listOf("agent-q28b", "parent-q28b", "reason-q28b", "jwks", "sha-q28b", "issuer.q28b")) {
                assertFalse(hidden in reader.raw, "$hidden is withheld from a non-admin: ${reader.raw}")
            }
            assertEquals("start", entry.str("trigger"), "control: the non-attribution fields are still returned")

            val global = select(h(), TEST_TOKEN)
            assertFalse("agent-q28b" in global.raw, "global mode redacts the same way: ${global.raw}")
        }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a backfilled row below the seq floor renders with backfilled true, no origin and its null members omitted`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Backfilled")
            val rowId = UUID.randomUUID()
            val data =
                """{"trigger":"start","fromRole":"queue","toRole":"work","fromStatusLabel":"backlog","toStatusLabel":null,""" +
                    """"summary":null,"consumedCredentials":["vault:one"],"actorProofSha256":"sha-bf",""" +
                    """"actorProofClaims":{"iss":"https://bf.example"},"verifier":"jwks","verificationReason":"audience-mismatch",""" +
                    """"actorParent":"orchestrator-one","backfilled":true}"""
            DriverManager.getConnection(rig.db.jdbcUrl).use { c ->
                c
                    .prepareStatement(
                        "INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, principal_id, principal_kind, " +
                            "proof_status, data) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                    ).use { ps ->
                        ps.setLong(1, 1L)
                        ps.setBytes(2, idBytes(rowId))
                        ps.setString(3, stamp.format(Instant.parse("2026-03-01T10:15:30.123Z")))
                        ps.setBytes(4, idBytes(item.id))
                        ps.setString(5, "item")
                        ps.setBytes(6, idBytes(item.id))
                        ps.setString(7, "item.transitioned")
                        ps.setString(8, "actor-one")
                        ps.setString(9, "subagent")
                        ps.setString(10, "rejected")
                        ps.setString(11, data)
                        ps.executeUpdate()
                    }
            }
            val live = rig.addRow(item, t0, data = transitionData(trigger = "complete", from = "work", to = "terminal"))

            val perItem = select(forItem(item.id))
            assertEquals(200, perItem.status, perItem.raw)
            assertEquals(2, perItem.total)
            assertEquals(
                listOf(live.seq, 1L),
                perItem.items.map { it.number("seq") },
                "the live row is newer; the backfilled row is history"
            )
            val entry = perItem.items[1]
            assertEquals(true, entry.getValue("backfilled").jsonPrimitive.boolean)
            assertFalse("origin" in entry, "a backfilled row carries no origin: $entry")
            assertTrue(entry.str("id").startsWith(rowId.toString().substring(0, 8)))
            assertEquals(Instant.parse("2026-03-01T10:15:30.123Z"), Instant.parse(entry.str("occurredAt")))
            assertEquals("start", entry.str("trigger"))
            assertEquals("queue", entry.str("fromRole"))
            assertEquals("work", entry.str("toRole"))
            assertEquals("backlog", entry.str("fromStatusLabel"))
            assertFalse("toStatusLabel" in entry, "a null label is omitted: $entry")
            assertFalse("summary" in entry, "a null summary is omitted: $entry")
            assertEquals(listOf("vault:one"), entry.strings("consumedCredentials"))
            val actor = entry.sub("actor")
            assertEquals("actor-one", actor.str("id"))
            assertEquals("subagent", actor.str("kind"))
            assertEquals("orchestrator-one", actor.str("parent"))
            val verification = entry.sub("verification")
            assertEquals("rejected", verification.str("proofStatus"))
            assertEquals("jwks", verification.str("verifier"))
            assertEquals("audience-mismatch", verification.str("reason"))
            assertFalse(
                "sha-bf" in perItem.raw || "bf.example" in perItem.raw,
                "backfilled proof evidence is never rendered: ${perItem.raw}"
            )
            assertFalse("backfilled" in perItem.items[0], "the live row is not marked: ${perItem.items[0]}")

            val global = select(h())
            assertEquals(listOf(live.seq, 1L), global.items.map { it.number("seq") }, "global history includes the backfilled row too")

            val reader = select(forItem(item.id), TEST_TOKEN)
            assertFalse(
                "actor-one" in reader.raw || "audience-mismatch" in reader.raw,
                "a non-admin sees no backfilled attribution: ${reader.raw}"
            )
        }

    // ------------------------------------------------------------------ S10

    @Test
    fun `S10 the two 3x REST transition routes answer 404 for an admin and for a reader`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Routed")
            rig.addRow(item, t0)

            for (token in listOf(ADMIN_TOKEN, TEST_TOKEN)) {
                assertEquals(404, get("/items/${item.id}/transitions", token).status, "GET /items/{id}/transitions")
                assertEquals(404, get("/transitions", token).status, "GET /transitions")
            }
        }

    private fun normalized(
        text: String,
        reqId: String
    ): String = text.replace("\"reqId\":\"$reqId\"", "\"reqId\":\"<REQ>\"")

    private suspend fun bodies(
        rig: EventLogRig,
        client: IrClient,
        args: String,
        restToken: String,
        mcpToken: ParityToken
    ): Pair<String, String> {
        val rest = client.select(args, restToken)
        val mcp = callMcp(rig.ctx, registry, { parityAccess(mcpToken) }, "items_read", Json.parseToJsonElement(args).jsonObject)
        val restReq =
            rest.body
                .getValue("meta")
                .jsonObject
                .str("reqId")
        val mcpReq =
            mcp.structured
                .getValue("meta")
                .jsonObject
                .str("reqId")
        return normalized(rest.raw, restReq) to normalized(mcp.structured.toString(), mcpReq)
    }

    @Test
    fun `S10 an MCP history call and POST ops items_read return the same body apart from the request id`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Parity")
            rig.addRow(item, t0, data = transitionData(summary = "first"), principalId = "actor-p", principalKind = "subagent")
            rig.addRow(item, t0.plusSeconds(1), data = transitionData(trigger = "complete", from = "work", to = "terminal"))
            val admin = ParityToken("hist-admin", setOf(ApiCapability.READ, ApiCapability.ADMIN))

            for (args in listOf(h(), forItem(item.id), forItem(item.id, ""","limit":1"""))) {
                val (rest, mcp) = bodies(rig, this, args, ADMIN_TOKEN, admin)
                assertTrue("\"ok\":true" in rest && "\"total\":2" in rest, "the compared body is a real answer: $rest")
                assertEquals(rest, mcp, "$args: MCP and REST bodies are equal minus reqId")
            }
        }

    @Test
    fun `S10 a scope-forbidden history call is the same body over MCP and over ops`() {
        val r1 = uuid("a1a1a1a1-0000-4000-8000-00000000000a")
        val r2 = uuid("b2b2b2b2-0000-4000-8000-00000000000b")
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            rig.make("Mine", id = r1)
            val foreign = rig.make("Theirs", id = r2)
            rig.addRow(foreign, t0)
            val scoped = ParityToken("hist-scoped", setOf(ApiCapability.READ), rootIds = setOf(r1))

            val (rest, mcp) = bodies(rig, this, forItem(foreign.id), TEST_TOKEN, scoped)

            assertTrue("\"code\":\"forbidden\"" in rest, "the compared body is the forbidden envelope: $rest")
            assertEquals(rest, mcp)
        }
    }

    @Test
    fun `S10 the knowledge profile does not offer history`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Knowledge probe")
            val spec = checkNotNull(registry[ItemsReadTool.NAME])
            val dispatcher = buildToolDispatcher(rig.ctx)

            suspend fun knowledge(args: JsonObject) =
                dispatcher
                    .dispatch(spec, ToolProfile.KNOWLEDGE, args, "probe-req", CallLogRecord.SURFACE_MCP, CallAccess.LOCAL)
                    .envelope.body

            val refused = knowledge(buildJsonObject { put("select", "history") })
            assertEquals("false", (refused["ok"] as JsonPrimitive).content, "history must be refused in the knowledge profile: $refused")
            assertNull(refused["data"], "no data for a hidden select: $refused")

            val control =
                knowledge(
                    buildJsonObject {
                        put("select", "get")
                        put("itemId", item.id.toString())
                    }
                )
            assertEquals("true", (control["ok"] as JsonPrimitive).content, "control: get is offered in the knowledge profile: $control")
        }
}

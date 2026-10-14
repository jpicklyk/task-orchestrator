package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.rollupJson
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Raw-SQL seeding for the rollup tests (test-author seat, item 1be2db5e, R16). Every row goes in on ONE connection and one
 * transaction per writer, so a 40-kit scale fixture is a handful of statements, and every row is built from the columns of the
 * snapshot schema (`schema/snapshot/work_items.txt`, `events.txt`, `notes.txt`), which is public evidence, not implementation.
 *
 * Domain invariants satisfied by construction: a lifecycle-free item is the pair (role NULL, lifecycle 'none') and every other
 * item is (role non-NULL, lifecycle NULL) (the V26 CHECK in the snapshot); a child's depth is its parent's depth plus one and its
 * root is the root of the tree; an event's root is the root of its entity item; a call_log row's request id is 8 characters
 * ([testReqId]).
 */
internal object RollupRaw {
    private val seededAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun id(
        prefix: String,
        n: Int = 1
    ): UUID = UUID.fromString(prefix + "-0000-4000-8000-" + n.toString().padStart(12, '0'))

    /** A JSON array of the given ids as `call_log.target_ids` holds them: lowercase hyphenated UUID text. */
    fun targets(vararg ids: UUID): String = ids.joinToString(",", "[", "]") { "\"" + it.toString() + "\"" }

    class Writer(
        jdbcUrl: String
    ) : AutoCloseable {
        private val conn: Connection = DriverManager.getConnection(jdbcUrl).also { it.autoCommit = false }

        private fun bind(
            ps: java.sql.PreparedStatement,
            args: Array<out Any?>
        ) {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is UUID -> ps.setBytes(i + 1, P7Raw.bytes(a))
                    is Int -> ps.setInt(i + 1, a)
                    is Long -> ps.setLong(i + 1, a)
                    else -> ps.setString(i + 1, a.toString())
                }
            }
        }

        fun sql(
            statement: String,
            vararg args: Any?
        ): Int =
            conn.prepareStatement(statement).use { ps ->
                bind(ps, args)
                ps.executeUpdate()
            }

        /** A work item; a null [role] is the lifecycle-free representation (role NULL, lifecycle 'none'). */
        fun item(
            id: UUID,
            parent: UUID?,
            role: String?,
            depth: Int,
            root: UUID?
        ) {
            val ts = P7Raw.canon(seededAt)
            sql(
                "INSERT INTO work_items (id, parent_id, title, role, lifecycle, depth, root_id, created_at, modified_at, " +
                    "role_changed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                parent,
                "item-" + id.toString().take(8),
                role,
                if (role == null) "none" else null,
                depth,
                root,
                ts,
                ts,
                ts
            )
        }

        fun note(
            id: UUID,
            itemId: UUID,
            key: String
        ) {
            val ts = P7Raw.canon(seededAt)
            sql(
                "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) VALUES (?, ?, ?, 'work', 'body', ?, ?)",
                id,
                itemId,
                key,
                ts,
                ts
            )
        }

        /** One event row; [reqN] becomes the 8-character request id of call number [reqN] (null: no request id). */
        fun event(
            type: String,
            at: Instant,
            entityId: UUID,
            rootId: UUID,
            data: String = "{}",
            reqN: Int? = null,
            kind: String = DomainEvent.KIND_ITEM
        ) {
            sql(
                "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, req_id, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                P7Raw.canon(at),
                rootId,
                kind,
                entityId,
                type,
                reqN?.let { testReqId(it) },
                data
            )
        }

        /** One ok call_log row (outcome ok, token estimate = bytes / 4); [targetIds] is the JSON text of `target_ids` or null. */
        fun call(
            n: Int,
            at: Instant,
            root: UUID?,
            targetIds: String?,
            bytes: Long = 40,
            attempts: Int = 1
        ) {
            sql(
                "INSERT INTO call_log (req_id, at, surface, tool, outcome, attempts, latency_ms, response_bytes, response_tokens_est, " +
                    "token_method, target_ids, root_id) VALUES (?, ?, 'mcp', 'sample_tool', 'ok', ?, 1, ?, ?, 'bytes/4', ?, ?)",
                testReqId(n),
                P7Raw.canon(at),
                attempts,
                bytes,
                bytes / 4,
                targetIds,
                root
            )
        }

        override fun close() {
            conn.commit()
            conn.close()
        }
    }

    inline fun write(
        jdbcUrl: String,
        body: Writer.() -> Unit
    ) {
        Writer(jdbcUrl).use { it.body() }
    }

    fun count(
        jdbcUrl: String,
        sql: String
    ): Long = P7Raw.query(jdbcUrl, sql) { it.getLong(1) }.single()
}

/** The shared fixture of the rollup scenarios. Hand-derived expectations are written next to the rows they come from. */
internal object RollupWorld {
    /** Fixed base instant of the fixture timeline; everything is before [ReportKit.NOW]. */
    val T0: Instant = Instant.parse("2026-10-10T00:00:00Z")

    val R: UUID = ReportKit.R1
    val R2: UUID = ReportKit.R2

    // The anchor F and its subtree: Q queue, W work, V review, B blocked, T terminal under F; L lifecycle-free under F; M under L.
    val F: UUID = RollupRaw.id("f1000001")
    val Q: UUID = RollupRaw.id("f1000002")
    val W: UUID = RollupRaw.id("f1000003")
    val V: UUID = RollupRaw.id("f1000004")
    val B: UUID = RollupRaw.id("f1000005")
    val T: UUID = RollupRaw.id("f1000006")
    val L: UUID = RollupRaw.id("f1000007")
    val M: UUID = RollupRaw.id("f1000008")

    // The sibling subtree X (never part of F's rollup).
    val X: UUID = RollupRaw.id("e1000001")
    val XC: UUID = RollupRaw.id("e1000002")

    // Notes: N1 on W, N2 on M, N3 on XC are current; ND was deleted (no row).
    val N1: UUID = RollupRaw.id("d1000001")
    val N2: UUID = RollupRaw.id("d1000002")
    val N3: UUID = RollupRaw.id("d1000003")
    val ND: UUID = RollupRaw.id("d1000009")

    fun h(
        hours: Long,
        minutes: Long = 0
    ): Instant = T0.plus(Duration.ofHours(hours)).plus(Duration.ofMinutes(minutes))

    private const val APPLIED = """{"from":"queue","to":"work","trigger":"start"}"""
    private const val DIRECT = """{"origin":"direct","from":"work","to":"review","trigger":"complete"}"""
    private const val CASCADE = """{"origin":"cascade","from":"queue","to":"work","trigger":"start"}"""
    private const val REJECTED = """{"trigger":"start","code":"gate_blocked","missingKeys":[],"blockerIds":[]}"""

    private fun noteData(item: UUID) = """{"itemId":"$item","key":"k"}"""

    /**
     * The main world. Item roles: R queue; F work; Q queue; W work; V review; B blocked; T terminal; L lifecycle-free (role
     * NULL); M work (child of L); X queue; XC terminal.
     *
     * Events of F's subtree (timeline from [T0]): E1 Q transitioned +1h (req 1); E2 and E3 upserts of N1 +1h30 and +2h (req 2, 3);
     * E4 W transitioned +2h30 origin direct (req 104); E5 F transitioned +3h origin cascade (req 103); E6 M transitioned +3h30
     * origin cascade (req 5); E7 upsert of N2 +5h (req 113); E9 V rejected +8h (req 8). Events about things outside the subtree:
     * ND (a note that no longer exists) upserted +1h45 and deleted +9h; X transitioned at [T0] (req 110), XC transitioned +10d, X
     * rejected +1h, N3 upserted +1h.
     *
     * Hand sums for F: transitions total 4 (E1, E4, E5, E6), cascades 2 (E5, E6), rejected 1 (E9), notes written 3 (E2, E3, E7).
     */
    suspend fun seedMain(f: ReportFixture) {
        f.root(R, "Alpha root")
        RollupRaw.write(f.database.jdbcUrl) {
            sql("UPDATE work_items SET role = 'queue' WHERE id = ?", R)
            item(F, R, "work", 1, R)
            item(Q, F, "queue", 2, R)
            item(W, F, "work", 2, R)
            item(V, F, "review", 2, R)
            item(B, F, "blocked", 2, R)
            item(T, F, "terminal", 2, R)
            item(L, F, null, 2, R)
            item(M, L, "work", 3, R)
            item(X, R, "queue", 1, R)
            item(XC, X, "terminal", 2, R)
            note(N1, W, "a")
            note(N2, M, "b")
            note(N3, XC, "c")

            event(DomainEvent.ITEM_TRANSITIONED, h(1), Q, R, APPLIED, reqN = 1)
            event(DomainEvent.NOTE_UPSERTED, h(1, 30), N1, R, noteData(W), reqN = 2, kind = "note")
            event(DomainEvent.NOTE_UPSERTED, h(2), N1, R, noteData(W), reqN = 3, kind = "note")
            event(DomainEvent.ITEM_TRANSITIONED, h(2, 30), W, R, DIRECT, reqN = 104)
            event(DomainEvent.ITEM_TRANSITIONED, h(3), F, R, CASCADE, reqN = 103)
            event(DomainEvent.ITEM_TRANSITIONED, h(3, 30), M, R, CASCADE, reqN = 5)
            event(DomainEvent.NOTE_UPSERTED, h(5), N2, R, noteData(M), reqN = 113, kind = "note")
            event(DomainEvent.TRANSITION_REJECTED, h(8), V, R, REJECTED, reqN = 8)

            event(DomainEvent.NOTE_UPSERTED, h(1, 45), ND, R, noteData(W), kind = "note")
            event(DomainEvent.NOTE_DELETED, h(9), ND, R, noteData(W), kind = "note")
            event(DomainEvent.ITEM_TRANSITIONED, h(0), X, R, APPLIED, reqN = 110)
            event(DomainEvent.ITEM_TRANSITIONED, T0.plus(Duration.ofDays(10)), XC, R, APPLIED)
            event(DomainEvent.TRANSITION_REJECTED, h(1), X, R, REJECTED)
            event(DomainEvent.NOTE_UPSERTED, h(1), N3, R, noteData(XC), kind = "note")
        }
        f.seed(*mainCalls().toTypedArray())
    }

    private fun call(
        n: Int,
        at: Instant,
        rootId: UUID?,
        bytes: Long,
        targets: List<UUID> = emptyList(),
        error: String? = null,
        attempts: Int = 1
    ): CallLogRecord {
        val base = ReportKit.rec(n, at, rootId = rootId, responseBytes = bytes, error = error, attempts = attempts)
        return if (targets.isEmpty()) base else base.copy(targetIds = RollupRaw.targets(*targets.toTypedArray()))
    }

    /**
     * call_log rows of the main world, with the explicit window [[T0], [T0] + 1d). Counted for F: 101 (names W), 102 (names Q and M,
     * an error with 3 attempts), 103 (no target; its request wrote E5), 104 (names V and its request wrote E4: both paths, one
     * row), 106 (names Q and XC: one subtree item is enough), 111 (names W, NULL root) and 113 (no target; its request wrote E7,
     * NULL root). Not counted: 105 (names XC only), 107 (at the window end), 108 (1 ms before the window), 109 (unrelated), 110
     * (its request wrote X's event only).
     */
    fun mainCalls(): List<CallLogRecord> =
        listOf(
            call(101, h(1), R, 100, listOf(W)),
            call(102, h(2), R, 40, listOf(Q, M), error = "gate_blocked", attempts = 3),
            call(103, h(3), R, 8),
            call(104, h(4), R, 12, listOf(V)),
            call(105, h(5), R, 1000, listOf(XC)),
            call(106, h(5), R, 20, listOf(Q, XC)),
            call(107, T0.plus(Duration.ofDays(1)), R, 5000, listOf(W)),
            call(108, T0.minusMillis(1), R, 7000, listOf(W)),
            call(109, h(6), R, 9000),
            call(110, h(7), R, 11000),
            call(111, h(2), null, 16, listOf(W)),
            call(113, h(5), null, 24)
        )

    /** A row of another root that names W: it must never be counted for a caller scoped to [R]. */
    fun extraRootCall(): CallLogRecord = call(112, h(1), R2, 77, listOf(W))

    const val SINCE = "2026-10-10T00:00:00Z"
    const val UNTIL = "2026-10-11T00:00:00Z"
}

/**
 * Independent tests (test-author seat, item 1be2db5e, R16) for `ReportService.rollup` over the real SQLite database and the real
 * [io.github.jpicklyk.mcptask.current.application.port.ReportQueries] port: scenarios S1 to S15 of the frozen test-plan (all
 * NEW-SURFACE). The pure assembler (S16, S17) is in `RollupAssemblerTest`, the size guard (S18) in `RollupSizeTest` and the
 * statement count and plans (S19) in `RollupQueryCountTest`; S20 lives in the existing parity and guard tests.
 *
 * Oracles (task-scope acceptance criteria; none read from a run, every number is a hand sum written next to its rows in
 * [RollupWorld]): AC1 (anchorId required, resolved like rootId: hex prefix, forbidden outside the scope, not_found when unknown,
 * any depth), AC2 (the subtree is the anchor plus every descendant; a lifecycle-free item is excluded from `items` but its
 * descendants are counted; byRole omits zero roles), AC3 (half-open window; an explicit window over 31 days is invalid_request at
 * `/since`; the default is first to last subtree event plus 1 ms, clamped to the 31 days ending there with `clamped`, and
 * until = now, since = until - 7 days when there are no events; items are current state and not windowed), AC4 (subtree events
 * are those of a subtree item and of a CURRENT note of a subtree item; transitions, cascades by `data.origin`, rejected, notes
 * written; an upsert of a since-deleted note is not counted), AC5 (cost: DISTINCT call_log rows in the window that name a
 * subtree item in `target_ids` or whose `req_id` wrote a subtree event in the window, one row once; calls, errors, retries =
 * SUM(attempts - 1), bytes, tokens; `basis` always `targets`), AC6 (tag scope is forbidden{tag}; a root scope counts only rows
 * whose root is in the scope, NULL-root rows never), AC7 (the shape: scope ids, `blocked.total` is the blocked role count, run
 * parts absent).
 *
 * Negative-assertion conditions: each exclusion has a seeded row that WOULD be counted if the rule were dropped and a control
 * from the same fixture that does return data. The sibling subtree X holds the transitions, notes and calls that would leak into
 * F (S3 asserts X's own numbers as the control); the lifecycle-free item L would make F 8 items (S2); the calls 105, 107, 108, 109
 * and 110 each carry bytes that would show in the sums (S6); the deleted note's events would raise W's notes to 3 and widen F's
 * default window to +9h (S5, S9).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: see [RollupRaw].
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ReportRollupTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val w = RollupWorld

    private fun req(
        anchor: UUID?,
        since: String? = null,
        until: String? = null
    ) = ReportRollupRequest(anchor?.let { IdRef(it.toString()) }, since, until)

    private suspend fun rollup(
        f: ReportFixture,
        request: ReportRollupRequest,
        ctx: CallContext = f.ctx()
    ): Outcome<RollupReport> = f.service().rollup(ctx, request)

    private suspend fun ok(
        f: ReportFixture,
        anchor: UUID,
        since: String? = RollupWorld.SINCE,
        until: String? = RollupWorld.UNTIL,
        ctx: CallContext = f.ctx()
    ): RollupReport =
        when (val outcome = rollup(f, req(anchor, since, until), ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok rollup for anchor $anchor but got ${outcome.error}")
        }

    private fun err(outcome: Outcome<RollupReport>) = assertIs<Outcome.Err>(outcome).error

    // ------------------------------------------------------------------ S1 items by role

    @Test
    fun `S1 items count the anchor and every descendant by role and omit zero roles`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.F)

            assertEquals(7L, report.items.total, "F, Q, W, V, B, T and M; the lifecycle-free L is not an item of any role")
            assertEquals(
                mapOf("queue" to 1L, "work" to 3L, "review" to 1L, "blocked" to 1L, "terminal" to 1L),
                report.items.byRole
            )
            assertEquals(w.F, report.scope.anchorId)
            assertEquals(w.R, report.scope.rootId)
        }

    @Test
    fun `S1 a leaf anchor shows only its own role and every other role is omitted`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val terminal = ok(f, w.T)
            val review = ok(f, w.V)

            assertEquals(mapOf("terminal" to 1L), terminal.items.byRole, "no zero entries for queue, work, review or blocked")
            assertEquals(1L, terminal.items.total)
            assertEquals(mapOf("review" to 1L), review.items.byRole)
        }

    @Test
    fun `S1 probe a root anchor spans the whole tree including the sibling subtree`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.R)

            assertEquals(10L, report.items.total, "R + the 7 of F + X + XC")
            assertEquals(
                mapOf("queue" to 3L, "work" to 3L, "review" to 1L, "blocked" to 1L, "terminal" to 2L),
                report.items.byRole
            )
            assertEquals(w.R, report.scope.anchorId)
            assertEquals(w.R, report.scope.rootId, "a depth-0 anchor is its own root")
        }

    @Test
    fun `S1 items are current state and are not windowed`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val quiet = ok(f, w.F, since = "2026-10-10T20:00:00Z", until = "2026-10-10T21:00:00Z")

            assertEquals(0L, quiet.transitions.total, "no subtree event in that hour")
            assertEquals(7L, quiet.items.total, "the window does not filter items")
            assertEquals(1L, quiet.blocked.total)
        }

    // ------------------------------------------------------------------ S2 lifecycle-free

    @Test
    fun `S2 a lifecycle-free item is excluded but its descendants are still counted`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.F)

            assertEquals(7L, report.items.total, "counting L itself would give 8")
            assertEquals(3L, report.items.byRole.getValue("work"), "F, W and M: M sits under the lifecycle-free L")
        }

    @Test
    fun `S2 probe the anchor itself lifecycle-free rolls up its descendants`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.L)

            assertEquals(1L, report.items.total, "only M")
            assertEquals(mapOf("work" to 1L), report.items.byRole)
            assertEquals(w.L, report.scope.anchorId)
            assertEquals(w.R, report.scope.rootId)
            // L's events belong to M: E6 (cascade) and E7 (N2 on M)
            assertEquals(1L, report.transitions.total)
            assertEquals(1L, report.transitions.cascades)
            assertEquals(1L, report.notesWritten)
        }

    // ------------------------------------------------------------------ S3 sibling isolation

    @Test
    fun `S3 the sibling subtree never leaks into F and counts in full for itself`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val forF = ok(f, w.F)
            val forX = ok(f, w.X)

            assertEquals(7L, forF.items.total)
            assertEquals(4L, forF.transitions.total, "X's two transitions are not F's")
            assertEquals(1L, forF.transitions.rejected, "X's rejection is not F's")
            assertEquals(3L, forF.notesWritten, "N3 on XC is not F's")
            assertEquals(7L, forF.cost.calls, "calls 105 and 110 name or wrote X only")
            // control: the same events and calls do count for X
            assertEquals(2L, forX.items.total)
            assertEquals(mapOf("queue" to 1L, "terminal" to 1L), forX.items.byRole)
            assertEquals(1L, forX.transitions.total, "X transitioned at T0; XC's transition is +10d, outside the explicit window")
            assertEquals(1L, forX.transitions.rejected)
            assertEquals(1L, forX.notesWritten)
            assertEquals(3L, forX.cost.calls, "105 and 106 name XC, 110 wrote X's event")
            assertEquals(12_020L, forX.cost.responseBytes)
            assertEquals(3_005L, forX.cost.responseTokensEst)
        }

    // ------------------------------------------------------------------ S4 transitions

    @Test
    fun `S4 transitions count applied, cascade by origin and rejected separately`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.F)

            assertEquals(4L, report.transitions.total, "E1, E4, E5 and E6; a rejection is not a transition")
            assertEquals(2L, report.transitions.cascades, "only origin = cascade; E4 has origin direct")
            assertEquals(1L, report.transitions.rejected)
        }

    @Test
    fun `S4 per-anchor transition counts follow the subtree`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val q = ok(f, w.Q).transitions
            val v = ok(f, w.V).transitions
            val wk = ok(f, w.W).transitions
            val ff = ok(f, w.F).transitions

            assertEquals(Triple(1L, 0L, 0L), Triple(q.total, q.cascades, q.rejected))
            assertEquals(Triple(0L, 0L, 1L), Triple(v.total, v.cascades, v.rejected), "a rejection alone leaves total 0")
            assertEquals(Triple(1L, 0L, 0L), Triple(wk.total, wk.cascades, wk.rejected), "origin direct is not a cascade")
            assertEquals(Triple(4L, 2L, 1L), Triple(ff.total, ff.cascades, ff.rejected))
        }

    // ------------------------------------------------------------------ S5 notes

    @Test
    fun `S5 each upsert counts, a delete never counts and a note outside the subtree never counts`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            assertEquals(2L, ok(f, w.W).notesWritten, "N1 upserted twice; ND's upsert and delete are not counted")
            assertEquals(1L, ok(f, w.M).notesWritten, "N2 once")
            assertEquals(3L, ok(f, w.F).notesWritten, "2 + 1; N3 on XC is X's")
            assertEquals(1L, ok(f, w.X).notesWritten, "control: N3 counts for the subtree it belongs to")
        }

    // ------------------------------------------------------------------ S6 cost

    @Test
    fun `S6 cost counts distinct calls by target and by request, each once, and sums the metrics`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val cost = ok(f, w.F).cost

            // 101, 102, 103, 104 (both paths), 106 (names Q and XC), 111 (NULL root), 113 (NULL root, request path)
            assertEquals(7L, cost.calls)
            assertEquals(1L, cost.errors, "only 102 is an error")
            assertEquals(2L, cost.retries, "102 has 3 attempts; every other counted row has 1")
            assertEquals(220L, cost.responseBytes, "100 + 40 + 8 + 12 + 20 + 16 + 24")
            assertEquals(55L, cost.responseTokensEst, "25 + 10 + 2 + 3 + 5 + 4 + 6")
            assertEquals("targets", cost.basis)
        }

    @Test
    fun `S6 per-anchor cost follows the subtree and basis is present with zero calls`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val quiet = ok(f, w.T).cost
            val l = ok(f, w.L).cost
            val wk = ok(f, w.W).cost
            val q = ok(f, w.Q).cost

            assertEquals(
                RollupCost(0, 0, 0, 0, 0, "targets"),
                quiet,
                "no call names T and no event of T has a request: all zero, basis still present"
            )
            assertEquals(RollupCost(2, 1, 2, 64, 16, "targets"), l, "102 names M; 113 wrote E7 of N2 on M")
            assertEquals(
                RollupCost(3, 0, 0, 128, 32, "targets"),
                wk,
                "101 and 111 name W, 104 wrote E4; 107 and 108 are outside the window"
            )
            assertEquals(RollupCost(2, 1, 2, 60, 15, "targets"), q, "102 and 106 name Q")
        }

    // ------------------------------------------------------------------ S7 shape and run parts

    @Test
    fun `S7 the anchor rollup has no run parts, blocked total is the blocked role count and scope ids are short on the wire`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.F)

            assertNull(report.run)
            assertNull(report.decisions)
            assertNull(report.seats)
            assertNull(report.attempts)
            assertNull(report.budget)
            assertNull(report.blocked.byReason)
            assertEquals(1L, report.blocked.total, "B is the one blocked-role item; the role alone decides")
            assertNull(report.scope.runId)

            val json = rollupJson(report)
            assertEquals(listOf("scope", "window", "items", "blocked", "transitions", "notes", "cost"), json.keys.toList())
            val scope = json.getValue("scope").jsonObject
            assertEquals(listOf("anchorId", "rootId"), scope.keys.toList())
            assertEquals("f1000001", scope.getValue("anchorId").jsonPrimitive.content)
            assertEquals("a1a1a1a1", scope.getValue("rootId").jsonPrimitive.content)
            assertEquals(
                listOf("total"),
                json
                    .getValue("blocked")
                    .jsonObject.keys
                    .toList(),
                "byReason is absent on anchor scope"
            )
            assertEquals(
                listOf("calls", "errors", "retries", "responseBytes", "responseTokensEst", "basis"),
                json
                    .getValue("cost")
                    .jsonObject.keys
                    .toList()
            )
            assertEquals(
                listOf("written"),
                json
                    .getValue("notes")
                    .jsonObject.keys
                    .toList()
            )
            assertEquals(
                listOf("total", "cascades", "rejected"),
                json
                    .getValue("transitions")
                    .jsonObject.keys
                    .toList()
            )
            assertEquals(
                listOf("total", "byRole"),
                json
                    .getValue("items")
                    .jsonObject.keys
                    .toList()
            )
        }

    // ------------------------------------------------------------------ S8 window boundaries

    @Test
    fun `S8 a row one millisecond before since is out, at since is in, one millisecond before until is in, at until is out`(): Unit =
        runBlocking {
            val f = fx
            val since = Instant.parse("2026-10-10T06:00:00Z")
            val until = Instant.parse("2026-10-10T18:00:00Z")
            val q8 = RollupRaw.id("c8000001")
            val n8 = RollupRaw.id("c8000002")
            f.root(w.R, "Alpha root")
            RollupRaw.write(f.database.jdbcUrl) {
                item(q8, w.R, "queue", 1, w.R)
                note(n8, q8, "k")
                // transitions at the four boundary instants; each carries the request id of a call below
                event(DomainEvent.ITEM_TRANSITIONED, since.minusMillis(1), q8, w.R, reqN = 150)
                event(DomainEvent.ITEM_TRANSITIONED, since, q8, w.R, reqN = 151)
                event(DomainEvent.ITEM_TRANSITIONED, until.minusMillis(1), q8, w.R, reqN = 152)
                event(DomainEvent.ITEM_TRANSITIONED, until, q8, w.R, reqN = 153)
                // rejections: only the one at since is inside
                event(DomainEvent.TRANSITION_REJECTED, since.minusMillis(1), q8, w.R)
                event(DomainEvent.TRANSITION_REJECTED, since, q8, w.R)
                event(DomainEvent.TRANSITION_REJECTED, until, q8, w.R)
                // note upserts at the four instants: since and until - 1 ms are inside
                for (at in listOf(since.minusMillis(1), since, until.minusMillis(1), until)) {
                    event(DomainEvent.NOTE_UPSERTED, at, n8, w.R, """{"itemId":"$q8","key":"k"}""", kind = "note")
                }
            }

            fun call(
                n: Int,
                at: Instant,
                bytes: Long,
                named: Boolean
            ): CallLogRecord {
                val base = ReportKit.rec(n, at, rootId = w.R, responseBytes = bytes)
                return if (named) base.copy(targetIds = RollupRaw.targets(q8)) else base
            }
            f.seed(
                call(160, since.minusMillis(1), 1000, named = true),
                call(161, since, 40, named = true),
                call(162, until.minusMillis(1), 80, named = true),
                call(164, until, 1000, named = true),
                // request path: the event of 150 is out of the window, so the call 150 (in the window) is not counted
                call(150, since.plus(Duration.ofHours(1)), 1000, named = false),
                // the event of 151 is in the window but the call 151 is 1 ms before it: not counted
                call(151, since.minusMillis(1), 1000, named = false),
                // both inside: counted
                call(152, since.plus(Duration.ofHours(2)), 120, named = false),
                // the event of 153 is at until (out)
                call(153, since.plus(Duration.ofHours(3)), 1000, named = false)
            )

            val report = ok(f, q8, since = since.toString(), until = until.toString())

            assertEquals(2L, report.transitions.total, "since and until - 1 ms")
            assertEquals(1L, report.transitions.rejected, "only the one at since")
            assertEquals(2L, report.notesWritten, "since and until - 1 ms")
            assertEquals(3L, report.cost.calls, "161 and 162 by target, 152 by request; the rest are outside")
            assertEquals(240L, report.cost.responseBytes, "40 + 80 + 120")
            assertEquals(60L, report.cost.responseTokensEst, "10 + 20 + 30")
        }

    // ------------------------------------------------------------------ S9 default window

    @Test
    fun `S9 the default window is the first to the last subtree event plus one millisecond and the sibling does not widen it`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.F, since = null, until = null)

            assertEquals(w.h(1), report.window.since, "E1 at +1h is the first subtree event; X transitioned at T0 and does not count")
            assertEquals(w.h(8).plusMillis(1), report.window.until, "E9 at +8h is the last; ND's delete at +9h and XC at +10d do not count")
            assertFalse(report.window.clamped)
            assertEquals(4L, report.transitions.total, "all four are inside the default window; E1 sits on the inclusive start")
            assertEquals(1L, report.transitions.rejected, "E9 sits one millisecond inside the exclusive end")
            assertEquals(3L, report.notesWritten)
        }

    @Test
    fun `S9 the default window of a root anchor spans the sibling subtree too`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.R, since = null, until = null)

            assertEquals(w.T0, report.window.since, "X transitioned at T0")
            assertEquals(w.T0.plus(Duration.ofDays(10)).plusMillis(1), report.window.until, "XC transitioned at +10d")
            assertFalse(report.window.clamped, "10 days is under the 31 day cap")
        }

    @Test
    fun `S9 the wire window echoes the resolved instants`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val window = rollupJson(ok(f, w.F, since = null, until = null)).getValue("window").jsonObject

            assertEquals(listOf("since", "until"), window.keys.toList(), "clamped appears only when true")
            assertEquals(w.h(1), Instant.parse(window.getValue("since").jsonPrimitive.content))
            assertEquals(w.h(8).plusMillis(1), Instant.parse(window.getValue("until").jsonPrimitive.content))
        }

    // ------------------------------------------------------------------ S10 clamp

    private suspend fun seedLeafWithTransitions(
        f: ReportFixture,
        leaf: UUID,
        vararg at: Instant
    ) {
        f.root(w.R, "Alpha root")
        RollupRaw.write(f.database.jdbcUrl) {
            item(leaf, w.R, "queue", 1, w.R)
            for (instant in at) event(DomainEvent.ITEM_TRANSITIONED, instant, leaf, w.R)
        }
    }

    private val last: Instant = Instant.parse("2026-09-20T00:00:00Z")
    private val thirtyOneDays: Duration = Duration.ofDays(31)

    @Test
    fun `S10 a default span over 31 days is clamped to the 31 days ending at the last event plus one millisecond`(): Unit =
        runBlocking {
            val f = fx
            val leaf = RollupRaw.id("c9000001")
            seedLeafWithTransitions(
                f,
                leaf,
                last.minus(Duration.ofDays(50)),
                last.minus(thirtyOneDays),
                last.minus(thirtyOneDays).plusMillis(1),
                last
            )

            val report = ok(f, leaf, since = null, until = null)

            assertEquals(last.plusMillis(1), report.window.until)
            assertEquals(last.plusMillis(1).minus(thirtyOneDays), report.window.since)
            assertTrue(report.window.clamped)
            assertEquals(
                2L,
                report.transitions.total,
                "the events 50d and exactly 31d before the last fall before the clamped start; +1 ms is the inclusive start"
            )
            val wire = rollupJson(report).getValue("window").jsonObject
            assertEquals(listOf("since", "until", "clamped"), wire.keys.toList())
            assertEquals("true", wire.getValue("clamped").jsonPrimitive.content)
        }

    @Test
    fun `S10 a default span of exactly 31 days is not clamped`(): Unit =
        runBlocking {
            val f = fx
            val leaf = RollupRaw.id("c9000002")
            val first = last.plusMillis(1).minus(thirtyOneDays)
            seedLeafWithTransitions(f, leaf, first, last)

            val report = ok(f, leaf, since = null, until = null)

            assertEquals(first, report.window.since)
            assertEquals(last.plusMillis(1), report.window.until)
            assertFalse(report.window.clamped)
            assertEquals(2L, report.transitions.total)
        }

    @Test
    fun `S10 a default span of 31 days plus one millisecond is clamped and drops the first event`(): Unit =
        runBlocking {
            val f = fx
            val leaf = RollupRaw.id("c9000003")
            val first = last.minus(thirtyOneDays)
            seedLeafWithTransitions(f, leaf, first, last)

            val report = ok(f, leaf, since = null, until = null)

            assertEquals(first.plusMillis(1), report.window.since)
            assertEquals(last.plusMillis(1), report.window.until)
            assertTrue(report.window.clamped)
            assertEquals(1L, report.transitions.total, "the first event is one millisecond before the clamped start")
        }

    // ------------------------------------------------------------------ S11 no events

    @Test
    fun `S11 a subtree without events gets until = now and since = now minus 7 days`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val report = ok(f, w.T, since = null, until = null)

            assertEquals(ReportKit.NOW, report.window.until, "the sibling's events do not matter")
            assertEquals(ReportKit.NOW.minus(Duration.ofDays(7)), report.window.since)
            assertFalse(report.window.clamped)
            assertEquals(0L, report.transitions.total)
            assertEquals(1L, report.items.total)
        }

    // ------------------------------------------------------------------ S12 explicit window validation

    private suspend fun assertInvalid(
        f: ReportFixture,
        request: ReportRollupRequest,
        label: String,
        pointer: String? = null
    ) {
        val error = err(rollup(f, request))
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, label)
        val fields = assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.map { it.field }
        assertTrue(fields.isNotEmpty(), label)
        if (pointer != null) assertTrue(pointer in fields, "$label: $fields")
    }

    @Test
    fun `S12 an explicit window of exactly 31 days is accepted and one millisecond more is invalid at since`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val exact = ok(f, w.F, since = "2026-09-01T00:00:00Z", until = "2026-10-02T00:00:00Z")
            assertEquals(Instant.parse("2026-09-01T00:00:00Z"), exact.window.since)
            assertFalse(exact.window.clamped, "an explicit window is never clamped")

            assertInvalid(f, req(w.F, "2026-09-01T00:00:00Z", "2026-10-02T00:00:00.001Z"), "31d + 1ms", pointer = "/since")
        }

    @Test
    fun `S12 since at or after until is invalid and a window of one millisecond is valid`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            assertInvalid(f, req(w.F, "2026-10-10T00:00:00Z", "2026-10-10T00:00:00Z"), "since == until")
            assertInvalid(f, req(w.F, "2026-10-11T00:00:00Z", "2026-10-10T00:00:00Z"), "since > until")
            assertEquals(0L, ok(f, w.F, since = "2026-10-10T20:00:00Z", until = "2026-10-10T20:00:00.001Z").transitions.total)
        }

    @Test
    fun `S12 probe a malformed, zoneless or date-only instant is invalid while an offset is accepted`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            for (bad in listOf("yesterday", "2026-13-45T00:00:00Z", "2026-10-10", "2026-10-10T00:00:00")) {
                assertInvalid(f, req(w.F, bad, "2026-10-11T00:00:00Z"), "since '$bad'")
                assertInvalid(f, req(w.F, "2026-10-10T00:00:00Z", bad), "until '$bad'")
            }
            val offset = ok(f, w.F, since = "2026-10-10T02:00:00+02:00", until = "2026-10-11T02:00:00+02:00")
            assertEquals(Instant.parse("2026-10-10T00:00:00Z"), offset.window.since, "an offset instant is the same instant")
            assertEquals(4L, offset.transitions.total)
        }

    // ------------------------------------------------------------------ S13 anchor resolution

    @Test
    fun `S13 an absent anchorId is invalid_request at the anchorId pointer`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            assertInvalid(f, ReportRollupRequest(), "no anchorId", pointer = "/anchorId")
            assertInvalid(
                f,
                ReportRollupRequest(since = RollupWorld.SINCE, until = RollupWorld.UNTIL),
                "window but no anchorId",
                pointer = "/anchorId"
            )
        }

    @Test
    fun `S13 an unknown anchor is not_found and a hex prefix or the full id resolves`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val unknown = err(rollup(f, ReportRollupRequest(IdRef("99999999-9999-4999-8999-999999999999"))))
            assertEquals(ErrorCode.NOT_FOUND, unknown.code)

            val full = ok(f, w.F)
            for (ref in listOf("f1000001", "f10000010000", "f1000001000040008000000000000001")) {
                val byRef =
                    assertIs<Outcome.Ok<RollupReport>>(
                        rollup(f, ReportRollupRequest(IdRef(ref), RollupWorld.SINCE, RollupWorld.UNTIL)),
                        "anchorId '$ref'"
                    ).value
                assertEquals(w.F, byRef.scope.anchorId, ref)
                assertEquals(full.items, byRef.items, ref)
                assertEquals(full.cost, byRef.cost, ref)
            }
        }

    @Test
    fun `S13 probe a prefix shared by two items is ambiguous_id`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)
            RollupRaw.write(f.database.jdbcUrl) {
                item(UUID.fromString("e1000001-1111-4111-8111-111111111111"), w.R, "queue", 1, w.R)
            }

            val error = err(rollup(f, ReportRollupRequest(IdRef("e1000001"))))

            assertEquals(ErrorCode.AMBIGUOUS_ID, error.code)
            assertEquals(w.X, ok(f, w.X).scope.anchorId, "control: the full id of either still resolves")
        }

    // ------------------------------------------------------------------ S14 root scope

    @Test
    fun `S14 an anchor outside the root scope is forbidden at the root scope and an unmatched prefix looks the same`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)
            f.root(w.R2, "Beta root")
            val inR2 = f.ctx(AccessScope.principal(setOf(w.R2), emptySet()))

            for (anchor in listOf(w.F, w.X, w.R)) {
                val error = err(rollup(f, req(anchor, RollupWorld.SINCE, RollupWorld.UNTIL), inR2))
                assertEquals(ErrorCode.FORBIDDEN, error.code, "anchor $anchor lives under R, not R2")
                assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(error.detail).scope)
            }
            val unmatched = err(rollup(f, ReportRollupRequest(IdRef("99999999-9999-4999-8999-999999999999")), inR2))
            assertEquals(ErrorCode.FORBIDDEN, unmatched.code, "a restricted scope is no existence oracle")
            val own = ok(f, w.R2, ctx = inR2)
            assertEquals(1L, own.items.total, "control: the own root answers (R2 alone)")
        }

    @Test
    fun `S14 inside a root scope the cost counts only rows of that root and never NULL-root rows`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)
            f.root(w.R2, "Beta root")
            f.seed(RollupWorld.extraRootCall())
            val inR = f.ctx(AccessScope.principal(setOf(w.R), emptySet()))

            val scoped = ok(f, w.F, ctx = inR)

            // 101, 102, 103, 104, 106 are R rows; 111 and 113 are NULL-root rows; 112 is an R2 row naming W
            assertEquals(5L, scoped.cost.calls)
            assertEquals(180L, scoped.cost.responseBytes, "100 + 40 + 8 + 12 + 20")
            assertEquals(45L, scoped.cost.responseTokensEst)
            assertEquals(1L, scoped.cost.errors)
            assertEquals(2L, scoped.cost.retries)
            assertEquals(4L, scoped.transitions.total, "events are not root-filtered away inside the own root")
            assertEquals(7L, scoped.items.total)
            val open = ok(f, w.F)
            assertEquals(7L, open.cost.calls, "control: unrestricted sees the NULL-root rows 111 and 113 as well")
        }

    // ------------------------------------------------------------------ S15 tag scope

    @Test
    fun `S15 a tag-scoped caller is forbidden at the tag scope for every anchor, even an unknown one`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)
            val tagged = f.ctx(AccessScope.principal(null, setOf("api")))

            val anchors =
                listOf(IdRef(w.F.toString()), IdRef("f1000001"), IdRef(w.R.toString()), IdRef("99999999-9999-4999-8999-999999999999"))
            for (anchor in anchors) {
                val error = err(rollup(f, ReportRollupRequest(anchor, RollupWorld.SINCE, RollupWorld.UNTIL), tagged))
                assertEquals(ErrorCode.FORBIDDEN, error.code, "$anchor")
                val detail = assertIs<ErrorDetail.Forbidden>(error.detail)
                assertEquals(ForbiddenScope.TAG, detail.scope, "$anchor")
                assertEquals("api", detail.required, "$anchor")
            }
            assertEquals(7L, ok(f, w.F).items.total, "control: an unrestricted caller reads the same anchor")
        }

    @Test
    fun `S15 a caller scoped by roots and tags is forbidden by the tag`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val error = err(rollup(f, req(w.F), f.ctx(AccessScope.principal(setOf(w.R), setOf("api")))))

            assertEquals(ForbiddenScope.TAG, assertIs<ErrorDetail.Forbidden>(error.detail).scope)
        }

    // ------------------------------------------------------------------ guard: the fixture is not inert

    @Test
    fun `control - the main world holds the rows the exclusions rely on`(): Unit =
        runBlocking {
            val f = fx
            RollupWorld.seedMain(f)

            val events = RollupRaw.count(f.database.jdbcUrl, "SELECT COUNT(*) FROM events")
            val calls = RollupRaw.count(f.database.jdbcUrl, "SELECT COUNT(*) FROM call_log")
            val none = RollupRaw.count(f.database.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE role IS NULL AND lifecycle = 'none'")

            assertEquals(14L, events, "8 subtree events, 2 about the deleted note, 4 about X")
            assertEquals(12L, calls)
            assertEquals(1L, none, "exactly the lifecycle-free L")
            assertTrue(RollupWorld.mainCalls().any { it.targetIds != null }, "the call rows carry target_ids")
        }
}

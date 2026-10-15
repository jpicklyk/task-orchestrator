package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.test.inUnit
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Seeding for the run rollup tests (test-author seat, item 74bb6db9, R28). Every row follows the public snapshot schema
 * (`schema/snapshot/runs.txt`, `run_items.txt`, `run_seats.txt`, `decision_requests.txt`, `events.txt`, `call_log.txt`,
 * `work_items.txt`), which is public evidence, not implementation. Runs and decisions go in through the typed stores the parity
 * harness already uses, so their state fields are consistent by construction; seats, events and blocked columns go in as raw rows
 * because no typed seeder offers `last_seen_at`, `run_id` on an event, or `blocked_reason`.
 *
 * Domain invariants satisfied by construction: a closed run carries a terminal reason, a closing instant and a closer; an ended
 * seat carries an outcome and an end instant, an active seat neither (the CHECK of run_seats); a coordinator seat has no item and
 * an item seat has one; an `awaiting_decision` block carries a decision ref and every other reason carries none; a lifecycle-free
 * item is the pair (role NULL, lifecycle 'none') (see RollupRaw.Writer.item); an event's root is the root of its entity item; a
 * seat's token hash is 64 characters and unique (the id text twice).
 */

/** A raw `run_seats` row: active when [endedAt] is null, ended (with an outcome) otherwise. */
internal fun RollupRaw.Writer.seat(
    id: UUID,
    run: UUID,
    item: UUID?,
    seat: String,
    attempt: Int,
    assignedAt: Instant,
    lastSeenAt: Instant?,
    endedAt: Instant? = null,
    outcome: String = "done"
) {
    val ended = endedAt != null
    sql(
        "INSERT INTO run_seats (id, run_id, item_id, seat, attempt, principal_id, token_hash, status, outcome, assigned_at, " +
            "ended_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        run,
        item,
        seat,
        attempt,
        "api:t",
        id.toString().replace("-", "").repeat(2),
        if (ended) "ended" else "active",
        if (ended) outcome else null,
        P7Raw.canon(assignedAt),
        endedAt?.let { P7Raw.canon(it) },
        lastSeenAt?.let { P7Raw.canon(it) }
    )
}

/** An event row stamped with [run] (null: unstamped). */
internal fun RollupRaw.Writer.runEvent(
    type: String,
    at: Instant,
    entity: UUID,
    root: UUID,
    run: UUID?,
    data: String = "{}",
    kind: String = DomainEvent.KIND_ITEM
) {
    sql(
        "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, run_id, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        P7Raw.canon(at),
        root,
        kind,
        entity,
        type,
        run,
        data
    )
}

/** An `item.transitioned` event that moves [item] to [toRole]; [reason] is the `blockedReason` of a move to `blocked`. */
internal fun RollupRaw.Writer.moved(
    at: Instant,
    item: UUID,
    root: UUID,
    toRole: String,
    reason: String? = null,
    run: UUID? = null,
    origin: String = "direct"
) {
    val data =
        "{\"origin\":\"" + origin + "\",\"toRole\":\"" + toRole + "\"" +
            (if (reason != null) ",\"blockedReason\":\"" + reason + "\"" else "") + "}"
    runEvent(DomainEvent.ITEM_TRANSITIONED, at, item, root, run, data)
}

/** Sets the blocked columns of an item that was inserted with role `blocked`. */
internal fun RollupRaw.Writer.blocked(
    item: UUID,
    reason: String?,
    ref: UUID? = null
) {
    sql("UPDATE work_items SET blocked_reason = ?, blocked_ref = ? WHERE id = ?", reason, ref, item)
}

internal object RunKit {
    val NOW: Instant = ReportKit.NOW
    val R: UUID = ReportKit.R1
    val R2: UUID = ReportKit.R2

    fun id(
        prefix: String,
        n: Int = 1
    ): UUID = RollupRaw.id(prefix, n)

    /** Makes the two roots the rollup worlds use (role queue, like the R16 world does). */
    suspend fun roots(f: ReportFixture) {
        f.root(R, "Alpha root")
        f.root(R2, "Beta root")
        RollupRaw.write(f.database.jdbcUrl) {
            sql("UPDATE work_items SET role = 'queue' WHERE id = ?", R)
            sql("UPDATE work_items SET role = 'queue' WHERE id = ?", R2)
        }
    }

    /** A run row through the typed store, with [members] enrolled; the state fields follow [status]. */
    suspend fun run(
        f: ReportFixture,
        id: UUID,
        root: UUID = R,
        members: List<UUID> = emptyList(),
        status: RunStatus = RunStatus.OPEN,
        terminalReason: RunTerminalReason = RunTerminalReason.COMPLETED,
        openedAt: Instant = NOW.minusSeconds(7_200),
        closedAt: Instant = NOW.minusSeconds(3_600),
        stallAfterSeconds: Int = 600,
        budgetTokens: Long? = null,
        reportedTokens: Long = 0,
        observedTokens: Long = 0
    ): Run {
        val closed = status == RunStatus.CLOSED
        lateinit var created: Run
        f.database.unitOfWork().inUnit("runkit.run") {
            val store = f.database.repositoryProvider().runStore()
            created =
                store.create(
                    Run(
                        id = id,
                        rootId = root,
                        status = status,
                        terminalReason = if (closed) terminalReason else null,
                        openedBy = "api:t",
                        openedByKind = "service",
                        openedAt = openedAt,
                        closedBy = if (closed) "api:t" else null,
                        closedAt = if (closed) closedAt else null,
                        stallAfterSeconds = stallAfterSeconds,
                        budget = budgetTokens?.let { RunBudget(tokens = it) },
                        reportedTokens = reportedTokens,
                        observedTokens = observedTokens
                    )
                )
            if (members.isNotEmpty()) store.addMembers(id, members, openedAt)
        }
        return created
    }

    private fun seeder(f: ReportFixture): ParitySeeder {
        val provider = f.database.repositoryProvider()
        return ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, f.database.unitOfWork())
    }

    /** A decision request through the typed store (the same builder the parity harness uses). */
    suspend fun decision(
        f: ReportFixture,
        id: UUID,
        run: UUID?,
        decisionClass: String = "question",
        severity: DecisionRequestSeverity = DecisionRequestSeverity.NORMAL,
        status: DecisionRequestStatus = DecisionRequestStatus.OPEN,
        item: UUID? = null,
        expiresAt: Instant? = null,
        root: UUID = R
    ) {
        seeder(f).decision(
            id = id,
            rootId = root,
            itemId = item,
            runId = run,
            decisionClass = decisionClass,
            severity = severity,
            status = status,
            raisedAt = NOW.minus(Duration.ofDays(2)),
            expiresAt = expiresAt
        )
    }
}

/**
 * The main run world. Run Q is open since [OPENED], stalls after 600 s, has a token budget of 1,000 with 300 reported and 450
 * observed, and holds twelve role members i1..i12 plus the lifecycle-free member [L]. A second run Q2 on the same root, a
 * non-member X and a blocked non-member XB sit beside it and carry every row that must NOT reach Q's figures.
 *
 * Members: i1 queue; i2 work; i3 review; i4 blocked (legacy: no reason, no ref); i5 blocked dependency; i6 blocked resource; i7
 * blocked awaiting_decision (decision d7 ANSWERED); i8 awaiting_decision (d8 OPEN); i9 awaiting_decision (d9 WITHDRAWN); i10
 * awaiting_decision (ref to no decision at all); i11 awaiting_decision (d11 ACKNOWLEDGED); i12 terminal. Hand sums: 12 role
 * members, blocked 8 = manual 1 (i4), dependency 1, resource 1, awaiting_decision 5; resumable 3 (i7, i9, i10).
 *
 * Decisions of Q (all raised two days before [NOW]): dA question NORMAL OPEN expires at NOW; dA2 question NORMAL OPEN expires at
 * NOW + 1 ms; dB attempt-cap HIGH OPEN; dC question LOW OPEN expired an hour ago; d8 question LOW OPEN; d11 question LOW
 * ACKNOWLEDGED; d7 ANSWERED; d9 WITHDRAWN. At NOW: open+acknowledged 6 = dA dA2 dB dC d8 d11; byClass question 5, attempt-cap 1;
 * effective severity high 2 (dA overdue exactly at NOW, dB), normal 2 (dA2, dC read one step up from low), low 2 (d8, d11).
 *
 * Seats of Q (600 s): sA i1 implementer attempt 2 active last seen 601 s ago; sB i2 implementer attempt 1 active last seen exactly
 * 600 s ago; sC i3 reviewer attempt 3 active never seen, assigned 3,600 s ago; sD i1 implementer attempt 1 ENDED; the coordinator
 * (no item) active last seen 99,999 s ago; Q2's seat sQ2 active last seen 9,999 s ago. Hand sums: active 3 (sA sB sC), stalled
 * sC 3,600,000 ms then sA 601,000 ms, count 2; attempts total 4 (sA sB sC sD), max 3.
 *
 * Events stamped Q: five `item.transitioned` (two cascade; one on the non-member X; one dated a day before the run opened), two
 * `transition.rejected`, three `note.upserted`, one `note.deleted` (not counted). Unstamped events about Q's members and Q2-stamped
 * events carry their own counts that must stay out. Hand sums: transitions 5, cascades 2, rejected 2, notes 3.
 *
 * Calls stamped Q (all 40 days before NOW, so no time window could admit them): c1 ok 100 B; c2 error gate_blocked 40 B 3
 * attempts; c3 ok 8 B; c4 ok no bytes, 2 attempts; c5 ok 12 B on root R2. Hand sums: calls 5, errors 1, retries 3 (0+2+0+1+0),
 * bytes 160, tokens 40 (25+10+2+0+3). Unstamped calls naming Q's members and a Q2-stamped call stay out.
 */
internal object RunWorld {
    val NOW: Instant = RunKit.NOW
    val R: UUID = RunKit.R
    val R2: UUID = RunKit.R2
    val OPENED: Instant = NOW.minusSeconds(7_200)

    val Q: UUID = RunKit.id("f6000001")
    val Q2: UUID = RunKit.id("f6000002")

    fun mem(n: Int): UUID = RunKit.id("f6100000", n)

    val L: UUID = RunKit.id("f6200000")
    val X: UUID = RunKit.id("f6300000")
    val XB: UUID = RunKit.id("f6300001")
    val J1: UUID = RunKit.id("f6400000", 1)
    val J2: UUID = RunKit.id("f6400000", 2)

    val D_A: UUID = RunKit.id("f6500000", 1)
    val D_A2: UUID = RunKit.id("f6500000", 2)
    val D_B: UUID = RunKit.id("f6500000", 3)
    val D_C: UUID = RunKit.id("f6500000", 4)
    val D_7: UUID = RunKit.id("f6500000", 7)
    val D_8: UUID = RunKit.id("f6500000", 8)
    val D_9: UUID = RunKit.id("f6500000", 9)
    val D_11: UUID = RunKit.id("f6500000", 11)
    val D_Q2: UUID = RunKit.id("f6500000", 20)
    val MISSING_REF: UUID = RunKit.id("f6999999")

    val memberIds: List<UUID> get() = (1..12).map { mem(it) } + L

    private fun ago(seconds: Long): Instant = NOW.minusSeconds(seconds)

    suspend fun seedMain(f: ReportFixture) {
        RunKit.roots(f)
        RollupRaw.write(f.database.jdbcUrl) {
            val roles =
                listOf(
                    "queue",
                    "work",
                    "review",
                    "blocked",
                    "blocked",
                    "blocked",
                    "blocked",
                    "blocked",
                    "blocked",
                    "blocked",
                    "blocked",
                    "terminal"
                )
            for ((index, role) in roles.withIndex()) item(mem(index + 1), R, role, 1, R)
            item(L, R, null, 1, R)
            item(X, R, "work", 1, R)
            item(XB, R, "blocked", 1, R)
            item(J1, R, "work", 1, R)
            item(J2, R, "blocked", 1, R)
            blocked(mem(5), "dependency")
            blocked(mem(6), "resource")
            blocked(mem(7), "awaiting_decision", D_7)
            blocked(mem(8), "awaiting_decision", D_8)
            blocked(mem(9), "awaiting_decision", D_9)
            blocked(mem(10), "awaiting_decision", MISSING_REF)
            blocked(mem(11), "awaiting_decision", D_11)
            blocked(XB, "dependency")
            blocked(J2, "manual")
        }
        RunKit.run(
            f,
            Q,
            members = memberIds,
            openedAt = OPENED,
            budgetTokens = 1_000,
            reportedTokens = 300,
            observedTokens = 450
        )
        RunKit.run(f, Q2, members = listOf(J1, J2), openedAt = OPENED)

        RunKit.decision(f, D_A, Q, expiresAt = NOW)
        RunKit.decision(f, D_A2, Q, expiresAt = NOW.plusMillis(1))
        RunKit.decision(f, D_B, Q, decisionClass = "attempt-cap", severity = DecisionRequestSeverity.HIGH)
        RunKit.decision(f, D_C, Q, severity = DecisionRequestSeverity.LOW, expiresAt = NOW.minusSeconds(3_600))
        RunKit.decision(f, D_8, Q, severity = DecisionRequestSeverity.LOW, item = mem(8))
        RunKit.decision(f, D_11, Q, severity = DecisionRequestSeverity.LOW, status = DecisionRequestStatus.ACKNOWLEDGED, item = mem(11))
        RunKit.decision(f, D_7, Q, status = DecisionRequestStatus.ANSWERED, item = mem(7))
        RunKit.decision(f, D_9, Q, status = DecisionRequestStatus.WITHDRAWN, item = mem(9))
        RunKit.decision(f, D_Q2, Q2, severity = DecisionRequestSeverity.HIGH)

        RollupRaw.write(f.database.jdbcUrl) {
            seat(RunKit.id("f6600000", 1), Q, mem(1), "implementer", 2, ago(3_600), ago(601))
            seat(RunKit.id("f6600000", 2), Q, mem(2), "implementer", 1, ago(3_600), ago(600))
            seat(RunKit.id("f6600000", 3), Q, mem(3), "reviewer", 3, ago(3_600), null)
            seat(
                RunKit.id("f6600000", 4),
                Q,
                mem(1),
                "implementer",
                1,
                ago(18_000),
                ago(7_200),
                endedAt = ago(3_600),
                outcome = "superseded"
            )
            seat(RunKit.id("f6600000", 5), Q, null, "coordinator", 1, ago(7_200), ago(99_999))
            seat(RunKit.id("f6600000", 6), Q2, J1, "implementer", 1, ago(7_200), ago(9_999))

            val noteA = RunKit.id("f6700000", 1)
            val data = "{\"itemId\":\"" + mem(1) + "\",\"key\":\"k\"}"
            // stamped Q
            moved(ago(5_400), mem(1), R, "work", run = Q)
            moved(ago(5_300), mem(2), R, "work", run = Q, origin = "cascade")
            moved(ago(5_200), mem(3), R, "review", run = Q, origin = "cascade")
            moved(ago(5_100), X, R, "work", run = Q)
            moved(OPENED.minus(Duration.ofDays(1)), mem(2), R, "work", run = Q)
            runEvent(DomainEvent.TRANSITION_REJECTED, ago(5_000), mem(1), R, Q, "{\"code\":\"gate_blocked\"}")
            runEvent(DomainEvent.TRANSITION_REJECTED, ago(4_900), mem(2), R, Q, "{\"code\":\"gate_blocked\"}")
            for (n in 0..2) runEvent(DomainEvent.NOTE_UPSERTED, ago(4_800L - n), noteA, R, Q, data, kind = "note")
            runEvent(DomainEvent.NOTE_DELETED, ago(4_700), noteA, R, Q, data, kind = "note")
            // unstamped, about Q's members: never counted
            moved(ago(5_400), mem(1), R, "work")
            moved(ago(5_300), mem(1), R, "work", origin = "cascade")
            runEvent(DomainEvent.TRANSITION_REJECTED, ago(5_000), mem(1), R, null, "{\"code\":\"gate_blocked\"}")
            runEvent(DomainEvent.NOTE_UPSERTED, ago(4_800), noteA, R, null, data, kind = "note")
            // stamped Q2: never counted for Q
            moved(ago(5_400), J1, R, "work", run = Q2)
            moved(ago(5_300), J1, R, "work", run = Q2, origin = "cascade")
            runEvent(DomainEvent.TRANSITION_REJECTED, ago(5_000), J1, R, Q2, "{\"code\":\"gate_blocked\"}")
            for (n in 0..1) runEvent(DomainEvent.NOTE_UPSERTED, ago(4_800L - n), noteA, R, Q2, data, kind = "note")
        }
        val old = NOW.minus(Duration.ofDays(40))
        f.seed(
            ReportKit.rec(1, old, runId = Q, rootId = R, responseBytes = 100),
            ReportKit.rec(2, old, runId = Q, rootId = R, responseBytes = 40, error = "gate_blocked", attempts = 3),
            ReportKit.rec(3, old, runId = Q, rootId = R, responseBytes = 8),
            ReportKit.rec(4, old, runId = Q, rootId = R, attempts = 2),
            ReportKit.rec(5, old, runId = Q, rootId = R2, responseBytes = 12),
            ReportKit.rec(6, ago(5_000), rootId = R, responseBytes = 1_000).copy(targetIds = RollupRaw.targets(mem(1))),
            ReportKit.rec(7, ago(4_000), rootId = R, responseBytes = 2_000).copy(targetIds = RollupRaw.targets(mem(2))),
            ReportKit.rec(8, ago(3_000), runId = Q2, rootId = R, responseBytes = 5_000, error = "gate_blocked")
        )
    }
}

/**
 * The dwell world: run QD opened at 02:00 on 2026-10-13, now 12:00. Members D1..D6 and the lifecycle-free DL carry
 * unstamped `item.transitioned` events (the blocks of unbound callers); the non-member DJ carries a one-hour block that must stay out.
 *
 *  D1  blocked `dependency` from 10:00, never released (current role blocked).
 *  D2  `resource` blocks 08:00-08:05 and 09:00-09:10 (current role work).
 *  D3  `awaiting_decision` block opened at 01:00, BEFORE the run opened, released 02:30 (current role work).
 *  D4  a block with no `blockedReason` 11:00-11:30 (current role work).
 *  D5  a `dependency` block 02:00-02:10, starting exactly at the run's opening instant (current role work).
 *  D6  three events at the SAME instant 06:00:00.000 in this insertion (seq) order: blocked manual, work, blocked manual (current
 *      role blocked, column reason manual).
 *
 * Hand sums for an open run (until = now 12:00): dependency D1 2 h 7,200,000 + D5 600,000 = 7,800,000; resource 5 + 10 min = 900,000; awaiting_decision
 * 02:00-02:30 = 1,800,000; manual D4 30 min 1,800,000 + D6 06:00-12:00 21,600,000 = 23,400,000.
 * For a run closed at 11:00 (until = closedAt): dependency D1 1 h 3,600,000 + D5 600,000 = 4,200,000; resource 900,000; awaiting_decision 1,800,000; manual
 * D4 starts at 11:00, the end of the half-open window, so 0, plus D6 06:00-11:00 = 18,000,000.
 */
internal object DwellWorld {
    val NOW: Instant = RunKit.NOW
    val R: UUID = RunKit.R
    val QD: UUID = RunKit.id("f7000001")
    val OPENED: Instant = Instant.parse("2026-10-13T02:00:00Z")
    val CLOSED_AT: Instant = Instant.parse("2026-10-13T11:00:00Z")

    val D1: UUID = RunKit.id("f7100000", 1)
    val D2: UUID = RunKit.id("f7100000", 2)
    val D3: UUID = RunKit.id("f7100000", 3)
    val D4: UUID = RunKit.id("f7100000", 4)
    val D5: UUID = RunKit.id("f7100000", 5)
    val D6: UUID = RunKit.id("f7100000", 6)
    val DL: UUID = RunKit.id("f7100000", 9)
    val DJ: UUID = RunKit.id("f7200000")

    fun t(hhmm: String): Instant = Instant.parse("2026-10-13T$hhmm:00Z")

    suspend fun seed(
        f: ReportFixture,
        closed: Boolean
    ) {
        RunKit.roots(f)
        RollupRaw.write(f.database.jdbcUrl) {
            item(D1, R, "blocked", 1, R)
            item(D2, R, "work", 1, R)
            item(D3, R, "work", 1, R)
            item(D4, R, "work", 1, R)
            item(D5, R, "work", 1, R)
            item(D6, R, "blocked", 1, R)
            item(DL, R, null, 1, R)
            item(DJ, R, "work", 1, R)
            blocked(D1, "dependency")
            blocked(D6, "manual")

            moved(t("10:00"), D1, R, "blocked", "dependency")
            moved(t("08:00"), D2, R, "blocked", "resource")
            moved(t("08:05"), D2, R, "work")
            moved(t("09:00"), D2, R, "blocked", "resource")
            moved(t("09:10"), D2, R, "work")
            moved(t("01:00"), D3, R, "blocked", "awaiting_decision")
            moved(t("02:30"), D3, R, "work")
            moved(t("02:00"), D5, R, "blocked", "dependency")
            moved(t("02:10"), D5, R, "work")
            moved(t("11:00"), D4, R, "blocked")
            moved(t("11:30"), D4, R, "work")
            moved(t("06:00"), D6, R, "blocked", "manual")
            moved(t("06:00"), D6, R, "work")
            moved(t("06:00"), D6, R, "blocked", "manual")
            moved(t("06:00"), DJ, R, "blocked", "dependency")
            moved(t("07:00"), DJ, R, "work")
        }
        RunKit.run(
            f,
            QD,
            members = listOf(D1, D2, D3, D4, D5, D6, DL),
            status = if (closed) RunStatus.CLOSED else RunStatus.OPEN,
            openedAt = OPENED,
            closedAt = CLOSED_AT
        )
    }
}

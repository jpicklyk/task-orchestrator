package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.RunsConfig
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.InFlightSeat
import io.github.jpicklyk.mcptask.current.application.port.RunSeatPort
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.intKey
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.EventClass
import io.github.jpicklyk.mcptask.current.domain.event.EventClasses
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunInFlight
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunReleased
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RUN_T: Instant = Instant.parse("2026-03-01T10:00:00Z")

/** The error of an [Outcome.Err]; any other outcome fails the test with its text. */
private fun <T> Outcome<T>.err(): DomainError = (this as? Outcome.Err)?.error ?: error("expected Outcome.Err, got $this")

private fun DomainError.fields() = assertIs<ErrorDetail.InvalidRequest>(detail, "detail of $this").fields

private fun EventRecord.strings(key: String): List<String> = payload()[key]!!.jsonArray.map { it.jsonPrimitive.content }

private fun EventRecord.intMap(key: String): Map<String, Int> = payload()[key]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }

/** The stub for the seat port R22 fills: the tests choose the counts and holders it reports and record what the cascade asks of it. */
private class StubSeats(
    var active: Int = 0,
    var holders: Set<String> = emptySet(),
    var ended: List<InFlightSeat> = emptyList()
) : RunSeatPort {
    val endedFor = mutableListOf<UUID>()

    override suspend fun activeCount(runId: UUID): Int = active

    override suspend fun assigneeHolders(runId: UUID): Set<String> = holders

    override suspend fun endActive(
        runId: UUID,
        outcome: String
    ): List<InFlightSeat> {
        endedFor += runId
        return ended
    }
}

/** Records each call with the event types visible at call time (the rows the close unit had written by then). */
private class RecordingHook(
    private val peek: suspend () -> List<String>
) : RunCloseHook {
    class Call(
        val run: Run,
        val snapshot: RunEndedSnapshot,
        val typesSeen: List<String>
    )

    val calls = mutableListOf<Call>()

    override suspend fun onClose(
        run: Run,
        snapshot: RunEndedSnapshot
    ) {
        calls += Call(run, snapshot, peek())
    }
}

/** Real SQLite fixture: production stores and unit of work on one settable clock, the real ClaimService, stubbed seat port. */
private class RunRig(
    val db: SqliteTestDatabase,
    val clock: SettableClock,
    runs: RunsConfig? = null
) {
    val provider get() = db.repositoryProvider()
    val uow: UnitOfWork = db.unitOfWork(clock)
    val claims = ClaimService(provider, uow)
    val seats = StubSeats()
    var peekMark: Long = 0

    val hook = RecordingHook { rowsAfter(peekMark).map { it.type } }
    private val resolver = TestConfigResolver.resolver(ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), runs = runs))
    val service =
        RunService(provider, uow, claims, runsConfig = { rootId -> resolver.resolveRunsConfig(rootId) }, seats = seats, onClose = hook)

    val store get() = provider.runStore()

    suspend fun root(title: String = "root"): WorkItem = provider.workItemRepository().create(WorkItem(title = title, depth = 0))

    suspend fun child(
        root: WorkItem,
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem =
        provider.workItemRepository().create(WorkItem(title = title, parentId = root.id, depth = 1, rootId = root.id, role = role))

    suspend fun maxSeq(): Long = provider.eventStore().maxSeq()

    suspend fun rowsAfter(seq: Long): List<EventRecord> = provider.eventStore().readAfter(seq, null, 100_000)

    suspend fun reload(id: UUID): WorkItem = provider.workItemRepository().getById(id) ?: error("item $id vanished")

    suspend fun leasesOf(id: UUID): Int = provider.resourceLeaseRepository().findActiveForItem(id).size

    suspend fun open(
        root: WorkItem,
        vararg items: WorkItem
    ): Run = service.open(RunOpenCommand(rootId = root.id, items = items.map { it.id })).ok()

    suspend fun close(
        run: Run,
        reason: RunTerminalReason,
        by: String? = null
    ): Outcome<RunCloseResult> {
        peekMark = maxSeq()
        return service.close(run.id, reason, by)
    }
}

/** One run over A and B (WORK) and C (QUEUE), plus a same-root WORK item that is not a member. */
private class Cascade(
    val root: WorkItem,
    val a: WorkItem,
    val b: WorkItem,
    val c: WorkItem,
    val outsider: WorkItem,
    val run: Run
)

private suspend fun RunRig.cascade(
    label: String,
    activeSeats: Int
): Cascade {
    val root = root("root $label")
    val a = child(root, "A $label", Role.WORK)
    val b = child(root, "B $label", Role.WORK)
    val c = child(root, "C $label", Role.QUEUE)
    val outsider = child(root, "O $label", Role.WORK)
    claims.claim(a.id, "p#s1", 600).ok()
    claims.claim(b.id, "other", 600).ok()
    claims.claim(outsider.id, "p#s2", 600).ok()
    claims.acquireLeases(b.id, "actor-b", listOf("res-b-$label" to 600)).ok()
    claims.acquireLeases(c.id, "actor-c", listOf("res-c-$label" to 600)).ok()
    claims.acquireLeases(outsider.id, "actor-o", listOf("res-o-$label" to 600)).ok()
    val run = open(root, a, b, c)
    seats.active = activeSeats
    seats.holders = setOf("p#s1", "p#s2")
    seats.ended = if (activeSeats > 0) listOf(InFlightSeat(a.id, "implementer")) else emptyList()
    return Cascade(root, a, b, c, outsider, run)
}

/**
 * Independent tests (test-author seat, item 8a71510c / R17) for [RunService] and the run surface around it: the lifecycle,
 * the close cascade, the error contract, the events, their SSE projection, and the root-delete guard.
 *
 * Scenarios S1-S7, S9-S15, S17-S20 and the probes of the frozen test-plan. Oracles: TS = task-scope ("R17 - Run entity core")
 * and TS-P = its planner additions (D-open, D-err, D-completed, D-cascade scope, D-sse), B = w5 brief 3.1/3.9, MA = the frozen
 * migration-assessment, and the literal fix templates and detail member names declared for the three new error codes.
 * Expected values are written out from those clauses, never read from the service.
 *
 * Unspecified in the oracle and therefore deliberately NOT asserted: the `outcome` string handed to the seat port, whether
 * the close hook fires for a completed close, the exact `allowed[]` wording for a paused-by-hand run beyond the entries named
 * below, clearing a pinned budget, a null (as opposed to empty or absent) runs block, the outcome of one id appearing in both
 * the add and the remove list of an update, and the error shape for a nonexistent or non-root `rootId`.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunServiceTest {
    private val clock = SettableClock(RUN_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig(runs: RunsConfig? = null) = RunRig(sqlite.db, clock, runs)
    // ---------------------------------------------------------------------------------------------
    // S1 -- open
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 opening a run over three queue items stores an open run with the defaults and records one run opened row`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val members = (1..3).map { rig.child(root, "member $it") }
            val mark = rig.maxSeq()

            val run = rig.service.open(RunOpenCommand(rootId = root.id, items = members.map { it.id })).ok()

            assertEquals(RunStatus.OPEN, run.status)
            assertEquals(root.id, run.rootId)
            assertEquals(600, run.stallAfterSeconds, "no config: the default stall_after")
            assertNull(run.budget, "no budget given and none configured: unlimited")
            assertEquals(1L, run.version)
            assertEquals(RUN_T, run.openedAt)
            assertEquals(RUN_T, run.coordinatorSeenAt, "coordinatorSeenAt starts at openedAt")
            assertNull(run.pauseReason)
            assertNull(run.terminalReason)
            assertNull(run.closedAt)
            assertEquals(0L, run.reportedTokens)
            assertEquals(0L, run.observedTokens)
            assertEquals(run, rig.store.getById(run.id), "the returned run is the stored run")
            assertEquals(members.map { it.id }.toSet(), rig.store.members(run.id).toSet())

            val rows = rig.rowsAfter(mark)
            assertEquals(listOf("run.opened"), rows.map { it.type }, "exactly one row: $rows")
            val row = rows.single()
            assertEquals("run", DomainEvent.KIND_RUN)
            assertEquals(DomainEvent.KIND_RUN, row.entityKind)
            assertEquals(run.id, row.entityId)
            assertEquals(root.id, row.rootId)
            assertEquals(members.map { it.id.toString() }.toSet(), row.strings("itemIds").toSet())
        }

    @Test
    fun `S1 the opener identity, label, title and hints are stored as given`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val scope = rig.child(root, "scope")

            val run =
                rig.service
                    .open(
                        RunOpenCommand(
                            rootId = root.id,
                            scopeId = scope.id,
                            label = "wave-7",
                            title = "Wave seven",
                            hints = mapOf("lane" to "fast"),
                            openedBy = "coordinator-1",
                            openedByKind = "agent"
                        )
                    ).ok()

            val stored = assertNotNull(rig.store.getById(run.id))
            assertEquals(scope.id, stored.scopeId)
            assertEquals("wave-7", stored.label)
            assertEquals("Wave seven", stored.title)
            assertEquals(mapOf("lane" to "fast"), stored.hints)
            assertEquals("coordinator-1", stored.openedBy)
            assertEquals("agent", stored.openedByKind)
        }

    @Test
    fun `S1 the configured stall_after applies and an explicit one on the command wins`(): Unit =
        runBlocking {
            val rig = rig(RunsConfig(stallAfterSeconds = 300))
            val root = rig.root()

            val configured = rig.service.open(RunOpenCommand(rootId = root.id)).ok()
            val explicit = rig.service.open(RunOpenCommand(rootId = root.id, stallAfterSeconds = 120)).ok()

            assertEquals(300, configured.stallAfterSeconds)
            assertEquals(120, explicit.stallAfterSeconds)
        }

    @Test
    fun `S1 label and title are checked at 64 and 200 characters and a non-positive stall_after is an invalid_request`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()

            rig.service.open(RunOpenCommand(rootId = root.id, label = "l".repeat(64), title = "t".repeat(200))).ok()
            val longLabel = rig.service.open(RunOpenCommand(rootId = root.id, label = "l".repeat(65))).err()
            val longTitle = rig.service.open(RunOpenCommand(rootId = root.id, title = "t".repeat(201))).err()
            val zeroStall = rig.service.open(RunOpenCommand(rootId = root.id, stallAfterSeconds = 0)).err()
            val negativeStall = rig.service.open(RunOpenCommand(rootId = root.id, stallAfterSeconds = -5)).err()

            assertEquals(ErrorCode.INVALID_REQUEST, longLabel.code)
            assertEquals(listOf("label"), longLabel.fields().map { it.field })
            assertEquals(ErrorCode.INVALID_REQUEST, longTitle.code)
            assertEquals(listOf("title"), longTitle.fields().map { it.field })
            assertEquals(ErrorCode.INVALID_REQUEST, zeroStall.code)
            assertEquals(ErrorCode.INVALID_REQUEST, negativeStall.code)
            assertEquals(1, rig.store.countNotClosedByRoot(root.id), "only the valid run was stored")
        }

    // ---------------------------------------------------------------------------------------------
    // S2 / S11 -- budgets are opt-in and pinned at open
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 a budget under the configured ceiling is pinned and recorded, and the boundary value is accepted`(): Unit =
        runBlocking {
            val rig = rig(RunsConfig(budgets = RunBudget(tokens = 5000)))
            val root = rig.root()
            val mark = rig.maxSeq()

            val run = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 1000))).ok()
            val atCeiling = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 5000))).ok()

            assertEquals(RunBudget(tokens = 1000), run.budget)
            assertEquals(RunBudget(tokens = 5000), atCeiling.budget, "equal to the ceiling is not over it")
            assertEquals(run.budget, rig.store.getById(run.id)?.budget)
            val opened = rig.rowsAfter(mark).first { it.type == "run.opened" }
            assertEquals(
                1000,
                opened
                    .payload()["budget"]!!
                    .jsonObject["tokens"]!!
                    .jsonPrimitive.int
            )
        }

    @Test
    fun `S2 with no ceilings and no budget the run is unlimited, and the server never invents a cap`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()

            val unlimited = rig.service.open(RunOpenCommand(rootId = root.id)).ok()
            val huge = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 900_000_000_000L))).ok()

            assertNull(unlimited.budget)
            assertEquals(RunBudget(tokens = 900_000_000_000L), huge.budget, "no ceiling configured, so any requested value stands")
        }

    @Test
    fun `S11 a budget over the configured ceiling is an invalid_request naming budget tokens and the ceiling`(): Unit =
        runBlocking {
            val rig = rig(RunsConfig(budgets = RunBudget(tokens = 5000)))
            val root = rig.root()

            val over = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 6000))).err()
            val justOver = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 5001))).err()

            for (err in listOf(over, justOver)) {
                assertEquals(ErrorCode.INVALID_REQUEST, err.code)
                val violation = err.fields().single()
                assertEquals("budget.tokens", violation.field)
                assertTrue(violation.reason.contains("5000"), "the reason names the ceiling: ${violation.reason}")
            }
            assertEquals(0, rig.store.countNotClosedByRoot(root.id), "a refused open stores nothing")
        }

    @Test
    fun `S11 each budget field has its own ceiling and the boundary of every one is accepted`(): Unit =
        runBlocking {
            val ceilings = RunBudget(tokens = 5000, costMicroUsd = 500_000, attemptsPerSeat = 2, maxActiveSeats = 3)
            val rig = rig(RunsConfig(budgets = ceilings))
            val root = rig.root()

            val cost = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(costMicroUsd = 500_001))).err()
            val attempts = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(attemptsPerSeat = 3))).err()
            val seatsOver = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(maxActiveSeats = 4))).err()
            val withinAll = rig.service.open(RunOpenCommand(rootId = root.id, budget = ceilings)).ok()

            for (err in listOf(cost, attempts, seatsOver)) {
                assertEquals(ErrorCode.INVALID_REQUEST, err.code)
                assertTrue(
                    err
                        .fields()
                        .single()
                        .field
                        .startsWith("budget."),
                    "field: ${err.fields()}"
                )
            }
            assertEquals(ceilings, withinAll.budget)
        }

    @Test
    fun `S11 a ceiling configured for tokens alone does not cap the cost field`(): Unit =
        runBlocking {
            val rig = rig(RunsConfig(budgets = RunBudget(tokens = 5000)))
            val root = rig.root()

            val free = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(costMicroUsd = 10_000_000))).ok()

            assertEquals(10_000_000L, free.budget?.costMicroUsd)
        }

    // ---------------------------------------------------------------------------------------------
    // S3 -- update
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 update adds and removes members, bumps the version, and records one run updated row`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val b = rig.child(root, "B")
            val c = rig.child(root, "C")
            val run = rig.open(root, a, b)
            val mark = rig.maxSeq()

            val updated = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(c.id), removeItems = listOf(a.id))).ok()

            assertEquals(2L, updated.version)
            assertEquals(setOf(b.id, c.id), rig.store.members(run.id).toSet())
            assertEquals(
                listOf(run.id),
                rig.store
                    .openRunByItem(setOf(c.id))
                    .values
                    .toList()
            )
            assertEquals(emptyMap(), rig.store.openRunByItem(setOf(a.id)), "a removed item is in no open run")
            val rows = rig.rowsAfter(mark)
            assertEquals(listOf("run.updated"), rows.map { it.type }, "$rows")
            val row = rows.single()
            assertEquals(listOf(c.id.toString()), row.strings("added"))
            assertEquals(listOf(a.id.toString()), row.strings("removed"))
            assertEquals("false", row.payload()["budgetChanged"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S3 update may lower a pinned budget but never raise it`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val run = rig.service.open(RunOpenCommand(rootId = root.id, budget = RunBudget(tokens = 1000))).ok()
            val mark = rig.maxSeq()

            val raised = rig.service.update(run.id, RunUpdateCommand(budget = RunBudget(tokens = 2000))).err()
            assertEquals(ErrorCode.INVALID_REQUEST, raised.code)
            assertEquals(RunBudget(tokens = 1000), rig.store.getById(run.id)?.budget, "a refused raise leaves the pin")
            assertEquals(emptyList(), rig.rowsAfter(mark), "a refused update records nothing")

            val lowered = rig.service.update(run.id, RunUpdateCommand(budget = RunBudget(tokens = 400))).ok()
            assertEquals(RunBudget(tokens = 400), lowered.budget)
            val row = rig.rowsAfter(mark).single()
            assertEquals("run.updated", row.type)
            assertEquals("true", row.payload()["budgetChanged"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S18 a stale expectedVersion is a version_conflict naming the run and the current version, a matching one is accepted`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val run = rig.open(root)

            val stale = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(a.id), expectedVersion = 5)).err()
            assertEquals(ErrorCode.VERSION_CONFLICT, stale.code)
            val detail = assertIs<ErrorDetail.VersionConflict>(stale.detail)
            assertEquals(EntityKind.RUN, detail.kind)
            assertEquals(run.id.toString(), detail.id)
            assertEquals(1L, detail.actual)
            assertEquals(emptyList(), rig.store.members(run.id), "the refused update changed nothing")

            val matching = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(a.id), expectedVersion = 1)).ok()
            assertEquals(2L, matching.version)
        }

    @Test
    fun `S18 replaying an update with the version it already consumed is a version_conflict`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val b = rig.child(root, "B")
            val run = rig.open(root)

            val first = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(a.id), expectedVersion = run.version)).ok()
            val second = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(b.id), expectedVersion = first.version)).ok()
            val replay = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(b.id), expectedVersion = run.version)).err()

            assertEquals(3L, second.version)
            assertEquals(ErrorCode.VERSION_CONFLICT, replay.code)
        }

    // ---------------------------------------------------------------------------------------------
    // S4 -- pause / resume
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a manual pause then resume moves open to paused and back with one row each`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val run = rig.open(root)
            val mark = rig.maxSeq()

            val paused = rig.service.pause(run.id).ok()

            assertEquals(RunStatus.PAUSED, paused.status)
            assertEquals(RunPauseReason.MANUAL, paused.pauseReason)
            assertEquals(2L, paused.version)
            val pausedRows = rig.rowsAfter(mark)
            assertEquals(listOf("run.paused"), pausedRows.map { it.type })
            assertEquals("manual", pausedRows.single().str("reason"))
            assertEquals(run.id, pausedRows.single().entityId)

            val resumeMark = rig.maxSeq()
            val resumed = rig.service.resume(run.id).ok()

            assertEquals(RunStatus.OPEN, resumed.status)
            assertNull(resumed.pauseReason, "a resumed run carries no pause reason")
            assertEquals(3L, resumed.version)
            assertEquals(listOf("run.resumed"), rig.rowsAfter(resumeMark).map { it.type })
            assertEquals(resumed, rig.store.getById(run.id))
        }

    @Test
    fun `S4 a paused run still counts as not closed, so its members stay reserved`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val run = rig.open(root, a)

            rig.service.pause(run.id).ok()

            assertEquals(1, rig.store.countNotClosedByRoot(root.id))
            assertEquals(mapOf(a.id to run.id), rig.store.openRunByItem(setOf(a.id)))
        }

    // ---------------------------------------------------------------------------------------------
    // S5 -- the close cascade
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 each non-completed close runs the cascade in order and releases only assignee claims and member work leases`(): Unit =
        runBlocking {
            val rig = rig()
            val reasons =
                listOf(
                    RunTerminalReason.CANCELLED,
                    RunTerminalReason.FAILED,
                    RunTerminalReason.ABANDONED,
                    RunTerminalReason.BUDGET_EXHAUSTED
                )
            for (reason in reasons) {
                val f = rig.cascade(reason.wire, activeSeats = 1)
                clock.advanceSeconds(30)
                val mark = rig.maxSeq()

                val result = rig.close(f.run, reason, "closer-1").ok()
                val rows = rig.rowsAfter(mark)

                assertEquals(
                    listOf("claim.released", "lease.released", "run.ended"),
                    rows.map { it.type },
                    "${reason.wire}: cascade order seats, claims, leases, event: $rows"
                )
                val claimRow = rows[0]
                assertEquals(f.a.id, claimRow.entityId)
                assertEquals("run_closed", claimRow.str("reason"))
                val leaseRow = rows[1]
                assertEquals(f.b.id, leaseRow.entityId)
                assertEquals("res-b-${reason.wire}", leaseRow.str("key"))

                val ended = rows[2]
                assertEquals(DomainEvent.KIND_RUN, ended.entityKind)
                assertEquals(f.run.id, ended.entityId)
                assertEquals(f.root.id, ended.rootId)
                assertEquals(reason.wire, ended.str("reason"))
                assertEquals(mapOf("work" to 2, "queue" to 1), ended.intMap("countsByRole"))
                val inFlight =
                    ended
                        .payload()["inFlight"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single()
                assertEquals(f.a.id.toString(), inFlight["itemId"]!!.jsonPrimitive.content)
                assertEquals("work", inFlight["role"]!!.jsonPrimitive.content)
                assertEquals("implementer", inFlight["seat"]!!.jsonPrimitive.content)
                assertEquals(mapOf("claims" to 1, "leases" to 1, "seats" to 1), ended.intMap("released"))
                assertEquals(0, ended.intKey("openDecisions"))

                assertEquals(
                    RunEndedSnapshot(
                        reason = reason,
                        countsByRole = mapOf("work" to 2, "queue" to 1),
                        inFlight = listOf(RunInFlight(f.a.id, "work", "implementer")),
                        released = RunReleased(claims = 1, leases = 1, seats = 1),
                        openDecisions = 0
                    ),
                    result.snapshot,
                    reason.wire
                )
                val closed = result.run
                assertEquals(RunStatus.CLOSED, closed.status)
                assertEquals(reason, closed.terminalReason)
                assertEquals(clock.now(), closed.closedAt)
                assertEquals("closer-1", closed.closedBy)
                assertEquals(f.run.version + 1, closed.version)
                assertEquals(closed, rig.store.getById(f.run.id))
                assertEquals(f.run.id, rig.seats.endedFor.last(), "the seat port is asked to end this run's active seats")

                assertNull(rig.reload(f.a.id).claimedBy, "${reason.wire}: the assignee's claim on a member is cleared")
                assertEquals("other", rig.reload(f.b.id).claimedBy, "a member claimed by a non-assignee keeps its claim")
                assertEquals("p#s2", rig.reload(f.outsider.id).claimedBy, "an assignee's claim on a non-member keeps its claim")
                assertEquals(0, rig.leasesOf(f.b.id), "the lease of a WORK member is released")
                assertEquals(1, rig.leasesOf(f.c.id), "a QUEUE member's lease is not released")
                assertEquals(1, rig.leasesOf(f.outsider.id), "a non-member's lease is not released")
            }
        }

    @Test
    fun `S5 a replayed close repeats neither the cascade nor the event and keeps the original terminal reason`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("replay", activeSeats = 1)
            rig.close(f.run, RunTerminalReason.CANCELLED).ok()
            val mark = rig.maxSeq()

            val again = rig.close(f.run, RunTerminalReason.CANCELLED).err()
            val different = rig.close(f.run, RunTerminalReason.FAILED).err()

            assertEquals(ErrorCode.RUN_CLOSED, again.code)
            assertEquals(ErrorCode.RUN_CLOSED, different.code)
            assertEquals("cancelled", assertIs<ErrorDetail.RunClosed>(different.detail).terminalReason, "the original reason stands")
            assertEquals(emptyList(), rig.rowsAfter(mark), "a replay records nothing")
            assertEquals(1, rig.seats.endedFor.size)
            assertEquals(1, rig.hook.calls.size)
        }

    @Test
    fun `S5 a close while the seat port reports no assignees clears no claim but still releases member work leases`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("noholders", activeSeats = 0)
            rig.seats.holders = emptySet()

            val result = rig.close(f.run, RunTerminalReason.ABANDONED).ok()

            assertEquals("p#s1", rig.reload(f.a.id).claimedBy, "the live cascade clears none until a seat port reports assignees")
            assertEquals(0, result.snapshot.released.claims)
            assertEquals(1, result.snapshot.released.leases, "the WORK member's lease is still released")
        }

    // ---------------------------------------------------------------------------------------------
    // S6 -- completed close: no cascade
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 closing as completed with no active seat records one run ended row with zero releases and touches nothing`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("completed", activeSeats = 0)
            val mark = rig.maxSeq()

            val result = rig.close(f.run, RunTerminalReason.COMPLETED).ok()

            val rows = rig.rowsAfter(mark)
            assertEquals(listOf("run.ended"), rows.map { it.type }, "no cascade rows: $rows")
            val ended = rows.single()
            assertEquals("completed", ended.str("reason"))
            assertEquals(mapOf("work" to 2, "queue" to 1), ended.intMap("countsByRole"))
            assertEquals(emptyList(), ended.payload()["inFlight"]!!.jsonArray.toList())
            assertEquals(mapOf("claims" to 0, "leases" to 0, "seats" to 0), ended.intMap("released"))
            assertEquals(RunReleased.NONE, result.snapshot.released)
            assertEquals(RunStatus.CLOSED, result.run.status)
            assertEquals(RunTerminalReason.COMPLETED, result.run.terminalReason)
            assertEquals("p#s1", rig.reload(f.a.id).claimedBy)
            assertEquals("other", rig.reload(f.b.id).claimedBy)
            assertEquals(1, rig.leasesOf(f.b.id), "a completed close releases no lease")
            assertEquals(emptyList(), rig.seats.endedFor, "a completed close ends no seat")
        }

    // ---------------------------------------------------------------------------------------------
    // S13 -- completed is refused while a seat is active
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 closing as completed while a seat is active is an invalid_run_state listing the four other closes`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("guard", activeSeats = 1)
            val before = rig.store.getById(f.run.id)
            val mark = rig.maxSeq()

            val refused = rig.close(f.run, RunTerminalReason.COMPLETED).err()

            assertEquals(ErrorCode.INVALID_RUN_STATE, refused.code)
            val detail = assertIs<ErrorDetail.InvalidRunState>(refused.detail)
            assertEquals(f.run.id, detail.runId)
            assertEquals("open", detail.status)
            assertEquals(
                setOf("close:cancelled", "close:failed", "close:abandoned", "close:budget_exhausted"),
                detail.allowed.toSet()
            )
            assertEquals(4, detail.allowed.size)
            assertEquals(emptyList(), rig.rowsAfter(mark), "no run.ended and no cascade rows")
            assertEquals(before, rig.store.getById(f.run.id), "the run is untouched")
            assertEquals("p#s1", rig.reload(f.a.id).claimedBy)
            assertEquals(emptyList(), rig.seats.endedFor)

            rig.seats.active = 0
            val accepted = rig.close(f.run, RunTerminalReason.COMPLETED).ok()
            assertEquals(RunStatus.CLOSED, accepted.run.status, "control: the same close succeeds once no seat is active")
        }

    // ---------------------------------------------------------------------------------------------
    // S7 -- the close hook
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 the close hook fires once inside the close unit after the cascade and before the run ended row`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("hook", activeSeats = 1)

            val result = rig.close(f.run, RunTerminalReason.CANCELLED).ok()

            val call = rig.hook.calls.single()
            assertEquals(f.run.id, call.run.id)
            assertEquals(result.snapshot, call.snapshot, "the hook receives the snapshot the event carries")
            assertEquals(
                listOf("claim.released", "lease.released"),
                call.typesSeen,
                "at hook time the cascade rows exist and the run.ended row does not yet"
            )
        }

    @Test
    fun `S7 a service built without a hook closes runs normally`(): Unit =
        runBlocking {
            val rig = rig()
            val bare = RunService(rig.provider, rig.uow, rig.claims, runsConfig = { RunsConfig() })
            val root = rig.root()
            val run = bare.open(RunOpenCommand(rootId = root.id)).ok()
            val mark = rig.maxSeq()

            val result = bare.close(run.id, RunTerminalReason.COMPLETED).ok()

            assertEquals(RunStatus.CLOSED, result.run.status)
            assertEquals(listOf("run.ended"), rig.rowsAfter(mark).map { it.type })
        }

    @Test
    fun `S7 the close joins an enclosing unit, so rolling that unit back leaves the run open, the claim in place and no event`(): Unit =
        runBlocking {
            val rig = rig()
            val f = rig.cascade("ambient", activeSeats = 1)
            val mark = rig.maxSeq()
            var inner: Outcome<RunCloseResult>? = null

            val outer =
                rig.uow.write<Unit>("S7.outer") {
                    inner = rig.service.close(f.run.id, RunTerminalReason.CANCELLED)
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(outer, "control: the enclosing unit really failed")
            assertIs<RunCloseResult>(inner?.ok(), "control: the close itself reported success inside the unit")
            assertEquals(RunStatus.OPEN, rig.store.getById(f.run.id)?.status, "the close was part of the rolled-back unit")
            assertEquals("p#s1", rig.reload(f.a.id).claimedBy)
            assertEquals(1, rig.leasesOf(f.b.id))
            assertEquals(emptyList(), rig.rowsAfter(mark), "no cascade row and no run.ended survives the rollback")
        }
    // ---------------------------------------------------------------------------------------------
    // S9 / S10 -- open refusals
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 an item already in an open run is a duplicate naming the existing run, and nothing is written`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val free = rig.child(root, "free")
            val first = rig.open(root, a)
            val mark = rig.maxSeq()

            val refused = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(free.id, a.id))).err()

            assertEquals(ErrorCode.DUPLICATE, refused.code)
            val detail = assertIs<ErrorDetail.Duplicate>(refused.detail)
            assertEquals(EntityKind.RUN, detail.kind)
            assertEquals(first.id.toString(), detail.existingId)
            assertEquals(emptyList(), rig.rowsAfter(mark))
            assertEquals(1, rig.store.countNotClosedByRoot(root.id), "no second run was stored")
            assertEquals(emptyMap(), rig.store.openRunByItem(setOf(free.id)), "all or nothing: the free item was not enrolled")
        }

    @Test
    fun `S9 a paused run still holds its members, a closed run releases them for a new run`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val first = rig.open(root, a)
            rig.service.pause(first.id).ok()

            val whilePaused = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id))).err()
            assertEquals(ErrorCode.DUPLICATE, whilePaused.code)
            assertEquals(first.id.toString(), assertIs<ErrorDetail.Duplicate>(whilePaused.detail).existingId)

            rig.service.close(first.id, RunTerminalReason.COMPLETED).ok()
            val second = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id))).ok()

            assertEquals(mapOf(a.id to second.id), rig.store.openRunByItem(setOf(a.id)))
        }

    @Test
    fun `S9 adding to a run an item that another open run holds is also a duplicate`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val holder = rig.open(root, a)
            val other = rig.open(root)

            val refused = rig.service.update(other.id, RunUpdateCommand(addItems = listOf(a.id))).err()

            assertEquals(ErrorCode.DUPLICATE, refused.code)
            assertEquals(holder.id.toString(), assertIs<ErrorDetail.Duplicate>(refused.detail).existingId)
            assertEquals(emptyList(), rig.store.members(other.id))
        }

    @Test
    fun `S10 a lifecycle-free item is refused as invalid_request at its index, with the repaired list accepted`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val b = rig.child(root, "B")
            val lifecycleFree = UUID.randomUUID()
            insertLifecycleFree(rig.db, lifecycleFree, parent = root.id, title = "lifecycle free")

            val refused = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id, lifecycleFree, b.id))).err()

            assertEquals(ErrorCode.INVALID_REQUEST, refused.code)
            assertEquals(listOf("items[1]"), refused.fields().map { it.field })
            assertEquals(0, rig.store.countNotClosedByRoot(root.id))
            val repaired = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id, b.id))).ok()
            assertEquals(setOf(a.id, b.id), rig.store.members(repaired.id).toSet())
        }

    @Test
    fun `S10 five hundred members are accepted and five hundred and one are refused as invalid_request on items`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val members = (1..501).map { rig.child(root, "m$it").id }

            val tooMany = rig.service.open(RunOpenCommand(rootId = root.id, items = members)).err()
            val atLimit = rig.service.open(RunOpenCommand(rootId = root.id, items = members.take(500))).ok()

            assertEquals(ErrorCode.INVALID_REQUEST, tooMany.code)
            assertEquals(listOf("items"), tooMany.fields().map { it.field })
            assertEquals(500, rig.store.members(atLimit.id).size)
        }

    @Test
    fun `S10 an item from another root is refused at its index, and an unknown item is not_found`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root("one")
            val otherRoot = rig.root("two")
            val a = rig.child(root, "A")
            val foreign = rig.child(otherRoot, "foreign")
            val unknown = UUID.randomUUID()

            val crossRoot = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id, foreign.id))).err()
            val missing = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id, unknown))).err()

            assertEquals(ErrorCode.INVALID_REQUEST, crossRoot.code)
            assertEquals(listOf("items[1]"), crossRoot.fields().map { it.field })
            assertEquals(ErrorCode.NOT_FOUND, missing.code)
            val detail = assertIs<ErrorDetail.NotFound>(missing.detail)
            assertEquals(EntityKind.ITEM, detail.kind)
            assertEquals(unknown.toString(), detail.id)
            assertEquals(0, rig.store.countNotClosedByRoot(root.id))
        }

    // ---------------------------------------------------------------------------------------------
    // S12 / S14 -- state errors
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 resuming a budget or attempts pause is an invalid_run_state allowing only close, and the run is unchanged`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            for (reason in listOf(RunPauseReason.BUDGET, RunPauseReason.ATTEMPTS)) {
                val run = rig.open(root)
                val pausedBy =
                    rig.store.update(run.copy(status = RunStatus.PAUSED, pauseReason = reason), run.version)
                        ?: error("fixture: the store refused the seeded pause")
                val mark = rig.maxSeq()

                val refused = rig.service.resume(run.id).err()

                assertEquals(ErrorCode.INVALID_RUN_STATE, refused.code, reason.wire)
                val detail = assertIs<ErrorDetail.InvalidRunState>(refused.detail)
                assertEquals(run.id, detail.runId)
                assertEquals("paused", detail.status)
                assertEquals(listOf("close"), detail.allowed, reason.wire)
                assertEquals(pausedBy, rig.store.getById(run.id), "${reason.wire}: unchanged")
                assertEquals(emptyList(), rig.rowsAfter(mark))
                rig.service.close(run.id, RunTerminalReason.BUDGET_EXHAUSTED).ok()
            }
        }

    @Test
    fun `S12 the same state machine resumes a manual pause, as the control for the refusals`(): Unit =
        runBlocking {
            val rig = rig()
            val run = rig.open(rig.root())
            rig.service.pause(run.id).ok()

            assertEquals(
                RunStatus.OPEN,
                rig.service
                    .resume(run.id)
                    .ok()
                    .status
            )
        }

    @Test
    fun `S14 every operation on a closed run is run_closed carrying the run and its terminal reason`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val run = rig.open(root)
            rig.service.close(run.id, RunTerminalReason.ABANDONED).ok()
            val mark = rig.maxSeq()

            val attempts =
                mapOf(
                    "update" to rig.service.update(run.id, RunUpdateCommand(addItems = listOf(a.id))).err(),
                    "pause" to rig.service.pause(run.id).err(),
                    "resume" to rig.service.resume(run.id).err(),
                    "close" to rig.service.close(run.id, RunTerminalReason.CANCELLED).err()
                )

            for ((op, err) in attempts) {
                assertEquals(ErrorCode.RUN_CLOSED, err.code, op)
                val detail = assertIs<ErrorDetail.RunClosed>(err.detail, op)
                assertEquals(run.id, detail.runId, op)
                assertEquals("abandoned", detail.terminalReason, op)
            }
            assertEquals(emptyList(), rig.rowsAfter(mark), "nothing is recorded for refused operations")
            assertEquals(emptyList(), rig.store.members(run.id), "the refused update added no member")
        }

    @Test
    fun `S14 updating a paused run is run_paused, while pause and resume state errors are invalid_run_state`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val run = rig.open(root)

            val resumeOpen = rig.service.resume(run.id).err()
            assertEquals(ErrorCode.INVALID_RUN_STATE, resumeOpen.code, "resume of an open run")
            val openState = assertIs<ErrorDetail.InvalidRunState>(resumeOpen.detail)
            assertEquals("open", openState.status)
            assertTrue("pause" in openState.allowed, "an open run accepts pause: ${openState.allowed}")

            rig.service.pause(run.id).ok()
            val updatePaused = rig.service.update(run.id, RunUpdateCommand(addItems = listOf(a.id))).err()
            val pauseAgain = rig.service.pause(run.id).err()

            assertEquals(ErrorCode.RUN_PAUSED, updatePaused.code)
            val paused = assertIs<ErrorDetail.RunPaused>(updatePaused.detail)
            assertEquals(run.id, paused.runId)
            assertEquals("manual", paused.pauseReason)
            assertNull(paused.pauseDecisionId)
            assertEquals(ErrorCode.INVALID_RUN_STATE, pauseAgain.code)
            val state = assertIs<ErrorDetail.InvalidRunState>(pauseAgain.detail)
            assertEquals("paused", state.status)
            assertTrue("resume" in state.allowed, "a manually paused run accepts resume: ${state.allowed}")
            assertEquals(emptyList(), rig.store.members(run.id))
        }

    @Test
    fun `S14 a paused run can be closed`(): Unit =
        runBlocking {
            val rig = rig()
            val run = rig.open(rig.root())
            rig.service.pause(run.id).ok()

            val closed = rig.service.close(run.id, RunTerminalReason.CANCELLED).ok()

            assertEquals(RunStatus.CLOSED, closed.run.status)
        }

    @Test
    fun `S14 an unknown run is not_found on every operation`(): Unit =
        runBlocking {
            val rig = rig()
            val ghost = UUID.randomUUID()

            val results =
                listOf(
                    rig.service.update(ghost, RunUpdateCommand()).err(),
                    rig.service.pause(ghost).err(),
                    rig.service.resume(ghost).err(),
                    rig.service.close(ghost, RunTerminalReason.CANCELLED).err()
                )

            for (err in results) {
                assertEquals(ErrorCode.NOT_FOUND, err.code)
                val detail = assertIs<ErrorDetail.NotFound>(err.detail)
                assertEquals(EntityKind.RUN, detail.kind)
                assertEquals(ghost.toString(), detail.id)
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S15 -- root delete guard (production wiring)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 deleting a root with an open or paused run is refused as invalid_request on itemId, and succeeds once the run is closed`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(sqlite.db, dir, clock = clock)
            val root = rig.seed("root")
            val kid = rig.seed("kid", parent = root, role = Role.WORK)
            val runService = rig.ctx.runService
            val items = rig.ctx.itemCommandService
            val run = runService.open(RunOpenCommand(rootId = root.id, items = listOf(kid.id))).ok()

            val whileOpen = items.delete(root.id, recursive = true).err()
            assertRootSurvives(rig, root, kid, run, RunStatus.OPEN, whileOpen)
            runService.pause(run.id).ok()
            val whilePaused = items.delete(root.id, recursive = true).err()
            assertRootSurvives(rig, root, kid, run, RunStatus.PAUSED, whilePaused)

            runService.close(run.id, RunTerminalReason.CANCELLED).ok()
            items.delete(root.id, recursive = true).ok()

            assertNull(rig.raw.workItemRepository().getById(root.id), "the root is gone once the run is closed")
            assertNull(rig.raw.runStore().getById(run.id), "a closed run follows its root away")
            assertEquals(1, rig.rows().count { it.type == "run.ended" }, "the audit row outlives the run row")
        }

    private suspend fun assertRootSurvives(
        rig: EventLogRig,
        root: WorkItem,
        kid: WorkItem,
        run: Run,
        status: RunStatus,
        refused: DomainError
    ) {
        assertEquals(ErrorCode.INVALID_REQUEST, refused.code)
        val violation = refused.fields().single()
        assertEquals("itemId", violation.field)
        assertTrue(violation.reason.contains("open run"), "the reason names the open runs: ${violation.reason}")
        assertNotNull(rig.raw.workItemRepository().getById(root.id), "the root survives while the run is $status")
        assertNotNull(rig.raw.workItemRepository().getById(kid.id), "its member survives while the run is $status")
        assertEquals(
            status,
            rig.raw
                .runStore()
                .getById(run.id)
                ?.status
        )
    }

    @Test
    fun `S15 a closed run alone does not block a root delete, and a member delete never does`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(sqlite.db, dir, clock = clock)
            val closedRoot = rig.seed("closed root")
            val closedRun =
                rig.ctx.runService
                    .open(RunOpenCommand(rootId = closedRoot.id))
                    .ok()
            rig.ctx.runService
                .close(closedRun.id, RunTerminalReason.COMPLETED)
                .ok()

            rig.ctx.itemCommandService
                .delete(closedRoot.id, recursive = true)
                .ok()
            assertNull(rig.raw.workItemRepository().getById(closedRoot.id))

            val root = rig.seed("open root")
            val k1 = rig.seed("k1", parent = root, role = Role.WORK)
            val k2 = rig.seed("k2", parent = root, role = Role.QUEUE)
            val run =
                rig.ctx.runService
                    .open(RunOpenCommand(rootId = root.id, items = listOf(k1.id, k2.id)))
                    .ok()

            rig.ctx.itemCommandService
                .delete(k1.id, recursive = false)
                .ok()

            assertEquals(listOf(k2.id), rig.raw.runStore().members(run.id), "deleting a member drops its membership")
            assertEquals(
                RunStatus.OPEN,
                rig.raw
                    .runStore()
                    .getById(run.id)
                    ?.status,
                "the run itself stays open"
            )
            val result =
                rig.ctx.runService
                    .close(run.id, RunTerminalReason.CANCELLED)
                    .ok()
            assertEquals(mapOf("queue" to 1), result.snapshot.countsByRole, "S18: a member deleted before the close is not counted")
        }

    // ---------------------------------------------------------------------------------------------
    // S18 -- edges
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S18 an empty member list is allowed, duplicate ids collapse to one membership, and removing a non-member is accepted`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val a = rig.child(root, "A")
            val b = rig.child(root, "B")

            val empty = rig.service.open(RunOpenCommand(rootId = root.id)).ok()
            assertEquals(emptyList(), rig.store.members(empty.id))

            val dup = rig.service.open(RunOpenCommand(rootId = root.id, items = listOf(a.id, a.id, a.id))).ok()
            assertEquals(listOf(a.id), rig.store.members(dup.id), "three copies of one id are one membership")

            rig.service.update(dup.id, RunUpdateCommand(removeItems = listOf(b.id))).ok()
            assertEquals(listOf(a.id), rig.store.members(dup.id), "removing a non-member changes no membership")
        }

    @Test
    fun `S18 runs on two different roots hold their items independently`(): Unit =
        runBlocking {
            val rig = rig()
            val r1 = rig.root("one")
            val r2 = rig.root("two")
            val a = rig.child(r1, "A")
            val x = rig.child(r2, "X")

            val run1 = rig.open(r1, a)
            val run2 = rig.open(r2, x)

            assertEquals(mapOf(a.id to run1.id, x.id to run2.id), rig.store.openRunByItem(setOf(a.id, x.id)))
            assertEquals(1, rig.store.countNotClosedByRoot(r1.id))
            assertEquals(1, rig.store.countNotClosedByRoot(r2.id))
        }

    // ---------------------------------------------------------------------------------------------
    // S17 -- error contract of the three new codes, end to end
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 the three run errors render their declared fix templates from the service's own arguments`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val closed = rig.open(root)
            rig.service.close(closed.id, RunTerminalReason.CANCELLED).ok()
            val paused = rig.open(root)
            rig.service.pause(paused.id).ok()
            val open = rig.open(root)

            val runClosed = rig.service.pause(closed.id).err()
            val runPaused = rig.service.update(paused.id, RunUpdateCommand()).err()
            val invalidState = rig.service.resume(open.id).err()

            assertEquals("Run ${closed.id} is closed (cancelled); open a new run to continue.", runClosed.fix)
            assertEquals("Run ${paused.id} is paused (manual); resume or close it before changing it.", runPaused.fix)
            assertEquals("Run ${open.id} is open; use one of the operations listed in detail.allowed.", invalidState.fix)
            for (err in listOf(runClosed, runPaused, invalidState)) {
                assertEquals(409, err.code.httpStatus)
                assertEquals("permanent", err.kind.toJsonString())
            }
        }

    @Test
    fun `S17 the run error details encode with the declared member names, and the paused detail omits an absent decision`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val decision = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val closed = EnvelopeEncoder.detail(ErrorDetail.RunClosed(id, "failed"))
        val paused = EnvelopeEncoder.detail(ErrorDetail.RunPaused(id, "budget", null))
        val pausedByDecision = EnvelopeEncoder.detail(ErrorDetail.RunPaused(id, "budget", decision))
        val state = EnvelopeEncoder.detail(ErrorDetail.InvalidRunState(id, "paused", listOf("close")))

        assertEquals(setOf("runId", "terminalReason"), closed.keys)
        assertEquals(setOf("runId", "pauseReason"), paused.keys)
        assertEquals(setOf("runId", "pauseReason", "pauseDecisionId"), pausedByDecision.keys)
        assertEquals(decision.toString(), pausedByDecision.getValue("pauseDecisionId").jsonPrimitive.content)
        assertEquals(setOf("runId", "status", "allowed"), state.keys)
        assertEquals(listOf("close"), state.getValue("allowed").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `S17 the principal_kind forbidden scope is cataloged and encodes lowercase`() {
        assertTrue(ForbiddenScope.PRINCIPAL_KIND in ForbiddenScope.entries)

        val detail = EnvelopeEncoder.detail(ErrorDetail.Forbidden(ForbiddenScope.PRINCIPAL_KIND, "agent"))

        assertEquals("principal_kind", detail.getValue("scope").jsonPrimitive.content)
        assertEquals("agent", detail.getValue("required").jsonPrimitive.content)
    }

    // ---------------------------------------------------------------------------------------------
    // S19 / S20 -- event classes and the SSE projection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S19 the six run event types are declared and every one classifies as audit`() {
        assertEquals(
            listOf("run.opened", "run.updated", "run.paused", "run.resumed", "run.ended", "run.marked"),
            DomainEvent.RUN_TYPES
        )
        for (type in DomainEvent.RUN_TYPES) assertEquals(EventClass.AUDIT, EventClasses.of(type), type)
        assertEquals("run.ended", DomainEvent.RUN_ENDED)
        assertFalse(DomainEvent.RUN_TYPES.isEmpty())
    }

    @Test
    fun `S20 the projection streams each run event with the type as event, the run id as runId, no item id and the root id`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.root()
            val other = rig.root("other")
            val a = rig.child(root, "A")
            val mark = rig.maxSeq()
            val run = rig.open(root, a)
            rig.service.update(run.id, RunUpdateCommand(removeItems = listOf(a.id))).ok()
            rig.service.pause(run.id).ok()
            rig.service.resume(run.id).ok()
            rig.service.close(run.id, RunTerminalReason.COMPLETED).ok()
            val bus = ApiEventBus(source = rig.provider.eventStore())

            val projected = bus.projectedEvents(afterSeq = mark, rootIds = null).filter { it.event.startsWith("run.") }

            assertEquals(listOf("run.opened", "run.updated", "run.paused", "run.resumed", "run.ended"), projected.map { it.event })
            for (event in projected) {
                assertEquals(run.id.toString(), event.runId, event.event)
                assertNull(event.itemId, "${event.event}: a run event has no item")
                assertEquals(root.id.toString(), event.rootId, event.event)
            }
            val scoped = bus.projectedEvents(afterSeq = mark, rootIds = setOf(root.id)).filter { it.event.startsWith("run.") }
            val elsewhere = bus.projectedEvents(afterSeq = mark, rootIds = setOf(other.id)).filter { it.event.startsWith("run.") }
            assertEquals(5, scoped.size, "the root filter admits the run's own root")
            assertEquals(emptyList(), elsewhere, "and excludes every other root")
        }

    @Test
    fun `S20 the marked event type is projected like the others`() {
        val runId = UUID.randomUUID()
        val rootId = UUID.randomUUID()
        val record =
            EventRecord(
                id = UUID.randomUUID(),
                occurredAt = RUN_T,
                rootId = rootId,
                entityKind = DomainEvent.KIND_RUN,
                entityId = runId,
                type = DomainEvent.RUN_MARKED,
                data = """{"keys":["checkpoint"]}"""
            )

        val projected = assertNotNull(ApiEventBus.project(record))

        assertEquals("run.marked", projected.event)
        assertEquals(runId.toString(), projected.runId)
        assertNull(projected.itemId)
        assertEquals(rootId.toString(), projected.rootId)
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    /** Inserts a lifecycle-free work item (role NULL with lifecycle 'none', satisfying the V26 relation CHECK) under [parent]. */
    private fun insertLifecycleFree(
        db: SqliteTestDatabase,
        id: UUID,
        parent: UUID,
        title: String
    ) {
        DriverManager.getConnection(db.jdbcUrl).use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO work_items (id, parent_id, root_id, title, role, lifecycle, depth, created_at, modified_at, " +
                        "role_changed_at) VALUES (?, ?, ?, ?, NULL, 'none', 1, ?, ?, ?)"
                ).use { ps ->
                    ps.setBytes(1, BaselineDataset.bytes(id))
                    ps.setBytes(2, BaselineDataset.bytes(parent))
                    ps.setBytes(3, BaselineDataset.bytes(parent))
                    ps.setString(4, title)
                    ps.setString(5, BaselineDataset.TS)
                    ps.setString(6, BaselineDataset.TS)
                    ps.setString(7, BaselineDataset.TS)
                    ps.executeUpdate()
                }
        }
    }
}

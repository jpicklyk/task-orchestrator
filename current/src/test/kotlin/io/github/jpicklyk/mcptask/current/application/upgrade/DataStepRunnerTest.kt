package io.github.jpicklyk.mcptask.current.application.upgrade

import io.github.jpicklyk.mcptask.current.application.port.DataStepStore
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteDataStepStore
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests for item 6b998895 (Q2a): [DataStepRunner] over a real, migrated SQLite database.
 *
 * Oracle: the item's frozen `task-scope` acceptance criteria AC1-AC6 and `migration-assessment` section 1 (the
 * `data_steps` DDL), plus the `test-plan` scenarios S1-S8. Expected values are derived from those clauses only:
 * the phase order is the declaration order of [DataStepPhase] (AC2), a ONCE step is applied iff its row exists
 * (AC3), an EVERY_BOOT step is never recorded (AC4), a failing step stops the run (AC5), and the post-run gate
 * names every registered ONCE step that has no row (AC6).
 *
 * Fixture invariants, satisfied by construction: every step name below matches `^[a-z][a-z0-9-]{0,63}$` (the
 * plan-time name rule) except in the tests that exist to prove the rule rejects a name; every `after` set names a
 * registered step of the SAME phase except in the tests that exist to prove it is rejected.
 */
class DataStepRunnerTest {
    private val clock = SettableClock()

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    // ------------------------------------------------------------------ fixtures

    /** A stand-in step. [runs] counts body invocations; [body] receives the unit's write scope. */
    private class FakeStep(
        override val name: String,
        override val phase: DataStepPhase = DataStepPhase.ITEM_BACKFILL,
        override val kind: DataStepKind = DataStepKind.ONCE,
        override val after: Set<String> = emptySet(),
        private val body: suspend (WriteScope) -> Int = { 0 }
    ) : DataStep {
        val runs = AtomicInteger()

        override suspend fun run(scope: WriteScope): Int {
            runs.incrementAndGet()
            return body(scope)
        }
    }

    /** A unit of work that must never be reached: proves a call does not touch the database. */
    private object NoDbUnitOfWork : UnitOfWork {
        override suspend fun <T> write(
            op: String,
            block: suspend WriteScope.() -> Outcome<T>
        ): Outcome<T> = throw AssertionError("the database was touched (write $op)")

        override suspend fun <T> read(block: suspend ReadScope.() -> T): T = throw AssertionError("the database was touched (read)")
    }

    private object NoDbStore : DataStepStore {
        override suspend fun appliedNames(): Set<String> = throw AssertionError("the store was touched (appliedNames)")

        override suspend fun record(
            name: String,
            appliedAt: Instant,
            rowsAffected: Int,
            binaryVersion: String
        ): Unit = throw AssertionError("the store was touched (record $name)")
    }

    /** Forwards to [delegate] but silently drops every record() whose name is in [dropped]. */
    private class DroppingStore(
        private val delegate: DataStepStore,
        private val dropped: Set<String>
    ) : DataStepStore {
        override suspend fun appliedNames(): Set<String> = delegate.appliedNames()

        override suspend fun record(
            name: String,
            appliedAt: Instant,
            rowsAffected: Int,
            binaryVersion: String
        ) {
            if (name !in dropped) delegate.record(name, appliedAt, rowsAffected, binaryVersion)
        }
    }

    /** Forwards to [delegate] and remembers the write-unit ordinal of every call (null = outside a counted write unit). */
    private class OrdinalSpyStore(
        private val delegate: DataStepStore
    ) : DataStepStore {
        val appliedNamesOrdinals: MutableList<Int?> = Collections.synchronizedList(mutableListOf())
        val recordOrdinals: MutableMap<String, Int?> = Collections.synchronizedMap(linkedMapOf())

        override suspend fun appliedNames(): Set<String> {
            appliedNamesOrdinals += CountingUnitOfWork.currentOrdinal()
            return delegate.appliedNames()
        }

        override suspend fun record(
            name: String,
            appliedAt: Instant,
            rowsAffected: Int,
            binaryVersion: String
        ) {
            recordOrdinals[name] = CountingUnitOfWork.currentOrdinal()
            delegate.record(name, appliedAt, rowsAffected, binaryVersion)
        }
    }

    private val realStore get() = SqliteDataStepStore(sqlite.databaseManager)

    private fun planOnly(steps: List<DataStep>) = DataStepRunner(steps, NoDbUnitOfWork, NoDbStore, clock, "9.9.9-test")

    private fun runner(
        steps: List<DataStep>,
        uow: UnitOfWork = sqlite.unitOfWork(),
        store: DataStepStore = realStore,
        version: String = "9.9.9-test"
    ) = DataStepRunner(steps, uow, store, clock, version)

    private fun names(steps: List<DataStep>): List<String> = steps.map { it.name }

    private fun rows(): List<Map<String, Any?>> =
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name, applied_at, rows_affected, binary_version FROM data_steps ORDER BY name").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                mapOf(
                                    "name" to rs.getString(1),
                                    "applied_at" to rs.getString(2),
                                    "rows_affected" to rs.getInt(3),
                                    "binary_version" to rs.getString(4)
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun rowNames(): List<String> = rows().map { it["name"] as String }

    private fun insertRaw(
        name: String,
        appliedAt: String,
        rowCount: Int,
        version: String
    ) {
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.prepareStatement("INSERT INTO data_steps (name, applied_at, rows_affected, binary_version) VALUES (?, ?, ?, ?)").use { ps ->
                ps.setString(1, name)
                ps.setString(2, appliedAt)
                ps.setInt(3, rowCount)
                ps.setString(4, version)
                ps.executeUpdate()
            }
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }
        }

    // ------------------------------------------------------------------ S1 / S2: plan() order (AC2)

    @Test
    fun `S1 plan runs phases in enum declaration order whatever the registration order and names`() {
        // Names are alphabetically the REVERSE of the phase order, so a name-only sort would be wrong.
        val pin = FakeStep("a-pin", DataStepPhase.PIN)
        val backfill = FakeStep("b-backfill", DataStepPhase.ITEM_BACKFILL)
        val import = FakeStep("c-import", DataStepPhase.CONFIG_IMPORT)
        val canon = FakeStep("d-canon", DataStepPhase.CONFIG_CANONICALIZE)
        val expected = listOf("d-canon", "c-import", "b-backfill", "a-pin")
        for (registration in permutations(listOf(pin, backfill, import, canon))) {
            assertEquals(expected, names(planOnly(registration).plan()), "registration ${names(registration)}")
        }
    }

    @Test
    fun `S1 a CONFIG_IMPORT step registered before a CONFIG_CANONICALIZE step still runs after it`() {
        val steps =
            listOf(FakeStep("import-first", DataStepPhase.CONFIG_IMPORT), FakeStep("canon-second", DataStepPhase.CONFIG_CANONICALIZE))
        assertEquals(listOf("canon-second", "import-first"), names(planOnly(steps).plan()))
    }

    @Test
    fun `S2 within a phase a dependent runs after what it names whatever the registration order`() {
        val a = FakeStep("step-a")
        val b = FakeStep("step-b", after = setOf("step-a"))
        val c = FakeStep("step-c", after = setOf("step-b"))
        for (registration in permutations(listOf(a, b, c))) {
            assertEquals(listOf("step-a", "step-b", "step-c"), names(planOnly(registration).plan()), "registration ${names(registration)}")
        }
    }

    @Test
    fun `S2 a dependency whose name sorts LATER still runs first`() {
        // zz-base is the dependency of aa-top: a pure name sort would put aa-top first.
        val plan = planOnly(listOf(FakeStep("aa-top", after = setOf("zz-base")), FakeStep("zz-base"))).plan()
        assertEquals(listOf("zz-base", "aa-top"), names(plan))
    }

    @Test
    fun `S2 independent steps of one phase are ordered by name ascending whatever the registration order`() {
        val z = FakeStep("z-step")
        val y = FakeStep("y-step")
        val m = FakeStep("m-step")
        for (registration in permutations(listOf(z, y, m))) {
            assertEquals(listOf("m-step", "y-step", "z-step"), names(planOnly(registration).plan()), "registration ${names(registration)}")
        }
    }

    @Test
    fun `S2 the ready set is sorted at every step so a newly released smaller name goes before a larger ready one`() {
        // AC2: Kahn with a sorted ready set. Initially ready = {b-root, c-root}; running b-root releases a-child,
        // and a-child < c-root, so the order is b-root, a-child, c-root (a FIFO queue would give b-root, c-root, a-child).
        val steps = listOf(FakeStep("c-root"), FakeStep("a-child", after = setOf("b-root")), FakeStep("b-root"))
        for (registration in permutations(steps)) {
            assertEquals(listOf("b-root", "a-child", "c-root"), names(planOnly(registration).plan()), "registration ${names(registration)}")
        }
    }

    @Test
    fun `S2 a mixed multi-phase graph yields one order for every registration permutation`() {
        val steps =
            listOf(
                FakeStep("pin-one", DataStepPhase.PIN),
                FakeStep("imp-b", DataStepPhase.CONFIG_IMPORT, after = setOf("imp-a")),
                FakeStep("imp-a", DataStepPhase.CONFIG_IMPORT),
                FakeStep("bf-x", DataStepPhase.ITEM_BACKFILL, DataStepKind.EVERY_BOOT),
                FakeStep("can-z", DataStepPhase.CONFIG_CANONICALIZE)
            )
        val expected = listOf("can-z", "imp-a", "imp-b", "bf-x", "pin-one")
        for (registration in permutations(steps)) {
            assertEquals(expected, names(planOnly(registration).plan()), "registration ${names(registration)}")
        }
    }

    @Test
    fun `plan is pure and repeatable, and an empty registration yields an empty plan without touching the database`() {
        val r = planOnly(listOf(FakeStep("only-one")))
        assertEquals(listOf("only-one"), names(r.plan()))
        assertEquals(listOf("only-one"), names(r.plan()), "a second call returns the same plan")
        assertEquals(emptyList(), planOnly(emptyList()).plan())
    }

    // ------------------------------------------------------------------ S6: plan() graph errors (AC1)

    @Test
    fun `S6 a duplicate name is rejected naming the step, also across phases`() {
        val same = assertFailsWith<DataStepException> { planOnly(listOf(FakeStep("dup-step"), FakeStep("dup-step"))).plan() }
        assertTrue("dup-step" in same.message.orEmpty(), "message must name the step: ${same.message}")
        val cross =
            assertFailsWith<DataStepException> {
                planOnly(listOf(FakeStep("dup-step", DataStepPhase.PIN), FakeStep("dup-step", DataStepPhase.CONFIG_IMPORT))).plan()
            }
        assertTrue("dup-step" in cross.message.orEmpty(), "message must name the step: ${cross.message}")
    }

    @Test
    fun `S6 names outside the pattern are rejected naming the step`() {
        val bad = listOf("Bad_Name", "UPPER", "has space", "1leading-digit", "-leading-hyphen", "under_score", "dot.name", "a".repeat(65))
        for (name in bad) {
            val e = assertFailsWith<DataStepException>("name '$name' must be rejected") { planOnly(listOf(FakeStep(name))).plan() }
            assertTrue(name in e.message.orEmpty(), "message must name the step '$name': ${e.message}")
        }
    }

    @Test
    fun `S6 the empty name is rejected`() {
        assertFailsWith<DataStepException> { planOnly(listOf(FakeStep(""))).plan() }
    }

    @Test
    fun `probe names at the pattern limits are accepted`() {
        val ok = listOf("a", "a1", "a-b", "z9-9-", "a".repeat(64), "a" + "9".repeat(63))
        val plan = planOnly(ok.map { FakeStep(it) }).plan()
        assertEquals(ok.sorted(), names(plan), "all boundary-valid names are planned, in name order")
        assertEquals(64, names(plan).maxOf { it.length })
    }

    @Test
    fun `S6 an after naming an unregistered step is rejected naming the step`() {
        val e = assertFailsWith<DataStepException> { planOnly(listOf(FakeStep("needs-ghost", after = setOf("ghost-step")))).plan() }
        assertTrue("needs-ghost" in e.message.orEmpty(), "message must name the step: ${e.message}")
    }

    @Test
    fun `S6 an after naming a step in ANOTHER phase is rejected in both directions`() {
        // Without the explicit cross-phase rule these would fail later as a bogus cycle, so assert the rule's own message.
        val earlier =
            assertFailsWith<DataStepException> {
                planOnly(
                    listOf(
                        FakeStep("early-base", DataStepPhase.CONFIG_CANONICALIZE),
                        FakeStep("late-dependent", DataStepPhase.CONFIG_IMPORT, after = setOf("early-base"))
                    )
                ).plan()
            }
        assertTrue("late-dependent" in earlier.message.orEmpty(), "message must name the step: ${earlier.message}")
        assertTrue("after is within a phase" in earlier.message.orEmpty(), "must be the cross-phase rule, not a cycle: ${earlier.message}")
        val later =
            assertFailsWith<DataStepException> {
                planOnly(
                    listOf(
                        FakeStep("early-dependent", DataStepPhase.CONFIG_CANONICALIZE, after = setOf("late-base")),
                        FakeStep("late-base", DataStepPhase.PIN)
                    )
                ).plan()
            }
        assertTrue("early-dependent" in later.message.orEmpty(), "message must name the step: ${later.message}")
        assertTrue("after is within a phase" in later.message.orEmpty(), "must be the cross-phase rule, not a cycle: ${later.message}")
    }

    @Test
    fun `S6 a two-step cycle is rejected naming a step of the cycle`() {
        val steps = listOf(FakeStep("cyc-a", after = setOf("cyc-b")), FakeStep("cyc-b", after = setOf("cyc-a")), FakeStep("free-step"))
        val e = assertFailsWith<DataStepException> { planOnly(steps).plan() }
        val message = e.message.orEmpty()
        assertTrue("cyc-a" in message || "cyc-b" in message, "message must name a step of the cycle: $message")
    }

    @Test
    fun `S6 a three-step cycle and a self-reference are rejected`() {
        val three =
            assertFailsWith<DataStepException> {
                planOnly(
                    listOf(
                        FakeStep("tri-a", after = setOf("tri-c")),
                        FakeStep("tri-b", after = setOf("tri-a")),
                        FakeStep("tri-c", after = setOf("tri-b"))
                    )
                ).plan()
            }
        assertTrue(
            listOf("tri-a", "tri-b", "tri-c").any { it in three.message.orEmpty() },
            "message must name a cycle member: ${three.message}"
        )
        val self = assertFailsWith<DataStepException> { planOnly(listOf(FakeStep("self-ref", after = setOf("self-ref")))).plan() }
        assertTrue("self-ref" in self.message.orEmpty(), "message must name the step: ${self.message}")
    }

    @Test
    fun `S6 run fails on a bad graph before any step runs or any unit is opened`() {
        val good = FakeStep("good-step", DataStepPhase.CONFIG_CANONICALIZE)
        val counting = CountingUnitOfWork(sqlite.unitOfWork())
        val r = runner(listOf(good, FakeStep("cyc-a", after = setOf("cyc-b")), FakeStep("cyc-b", after = setOf("cyc-a"))), uow = counting)

        assertFailsWith<DataStepException> { runBlocking { r.run() } }

        assertEquals(0, good.runs.get(), "a step of a bad graph must not run, not even one that is itself valid")
        assertEquals(0, counting.writes, "no write unit may open before the graph is validated")
        assertEquals(emptyList(), rows())
    }

    // ------------------------------------------------------------------ S3 / S4 / S5: ONCE and EVERY_BOOT (AC3, AC4)

    @Test
    fun `S3 a ONCE step is recorded with its rows, the unit clock time and the binary version`() {
        val step = FakeStep("count-rows") { 3 }
        val report = runBlocking { runner(listOf(step), version = "7.8.9-rc1").run() }

        assertEquals(DataStepReport(applied = listOf("count-rows"), skipped = emptyList(), ranEveryBoot = emptyList()), report)
        assertEquals(1, step.runs.get())
        val row = rows().single()
        assertEquals("count-rows", row["name"])
        assertEquals("2026-03-01 10:00:00.000", row["applied_at"], "the settable clock's instant as 23-character UTC text")
        assertEquals(3, row["rows_affected"])
        assertEquals("7.8.9-rc1", row["binary_version"])
    }

    @Test
    fun `probe a ONCE step that affects zero rows is recorded as applied, not skipped`() {
        val step = FakeStep("noop-step") { 0 }
        val first = runBlocking { runner(listOf(step)).run() }
        assertEquals(listOf("noop-step"), first.applied)
        assertEquals(0, rows().single()["rows_affected"])
        val second = runBlocking { runner(listOf(step)).run() }
        assertEquals(listOf("noop-step"), second.skipped, "a zero-row step is still applied once")
        assertEquals(1, step.runs.get())
    }

    @Test
    fun `S4 running twice applies a ONCE step once and skips it the second time`() {
        val step = FakeStep("only-once") { 2 }
        val r = runner(listOf(step))

        val first = runBlocking { r.run() }
        val second = runBlocking { r.run() }

        assertEquals(DataStepReport(listOf("only-once"), emptyList(), emptyList()), first)
        assertEquals(DataStepReport(emptyList(), listOf("only-once"), emptyList()), second)
        assertEquals(1, step.runs.get(), "the body must not run again once its row exists")
        assertEquals(listOf("only-once"), rowNames())
        assertEquals(2, rows().single()["rows_affected"], "the first run's row is kept")
    }

    @Test
    fun `probe a third run and a second runner over the same file both skip`() {
        val step = FakeStep("thrice")
        val r = runner(listOf(step))
        runBlocking {
            r.run()
            r.run()
            r.run()
        }
        val fresh = FakeStep("thrice")
        val report = runBlocking { runner(listOf(fresh)).run() }

        assertEquals(1, step.runs.get())
        assertEquals(0, fresh.runs.get(), "a new runner instance over the same database must skip")
        assertEquals(listOf("thrice"), report.skipped)
        assertEquals(1, rows().size)
    }

    @Test
    fun `S4 a ONCE step whose row already exists is skipped without touching that row or unregistered rows`() {
        insertRaw("preapplied", "2026-01-02 03:04:05.678", 5, "1.2.3")
        insertRaw("legacy-unregistered", "2026-01-02 03:04:05.678", 1, "1.2.3")
        val pre = FakeStep("preapplied") { 99 }
        val other = FakeStep("fresh-step") { 4 }

        val report = runBlocking { runner(listOf(pre, other)).run() }

        assertEquals(0, pre.runs.get())
        assertEquals(listOf("fresh-step"), report.applied)
        assertEquals(listOf("preapplied"), report.skipped)
        val byName = rows().associateBy { it["name"] }
        assertEquals(setOf<Any?>("preapplied", "legacy-unregistered", "fresh-step"), byName.keys)
        assertEquals(5, byName.getValue("preapplied")["rows_affected"])
        assertEquals("2026-01-02 03:04:05.678", byName.getValue("preapplied")["applied_at"])
        assertEquals("1.2.3", byName.getValue("preapplied")["binary_version"])
    }

    @Test
    fun `S5 an EVERY_BOOT step runs on every run and is never recorded`() {
        val step = FakeStep("each-boot", kind = DataStepKind.EVERY_BOOT) { 5 }
        val r = runner(listOf(step))

        val first = runBlocking { r.run() }
        val second = runBlocking { r.run() }

        assertEquals(DataStepReport(emptyList(), emptyList(), listOf("each-boot")), first)
        assertEquals(DataStepReport(emptyList(), emptyList(), listOf("each-boot")), second)
        assertEquals(2, step.runs.get())
        assertEquals(emptyList(), rows(), "an EVERY_BOOT step is never written to data_steps")
    }

    @Test
    fun `a mixed registration reports each step in the right list, in run order`() {
        val canon = FakeStep("can-once", DataStepPhase.CONFIG_CANONICALIZE) { 1 }
        val import = FakeStep("imp-boot", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT) { 2 }
        val backfill = FakeStep("bf-once", DataStepPhase.ITEM_BACKFILL) { 3 }
        val pin = FakeStep("pin-boot", DataStepPhase.PIN, DataStepKind.EVERY_BOOT) { 4 }
        val steps = listOf(pin, backfill, import, canon)

        val first = runBlocking { runner(steps).run() }
        val second = runBlocking { runner(steps).run() }

        assertEquals(DataStepReport(listOf("can-once", "bf-once"), emptyList(), listOf("imp-boot", "pin-boot")), first)
        assertEquals(DataStepReport(emptyList(), listOf("can-once", "bf-once"), listOf("imp-boot", "pin-boot")), second)
        assertEquals(listOf("bf-once", "can-once"), rowNames())
    }

    @Test
    fun `an empty step list runs to an empty report and opens no write unit`() {
        val counting = CountingUnitOfWork(sqlite.unitOfWork())

        val report = runBlocking { runner(emptyList(), uow = counting).run() }

        assertEquals(DataStepReport(emptyList(), emptyList(), emptyList()), report)
        assertEquals(0, counting.writes)
        assertEquals(emptyList(), rows())
    }

    // ------------------------------------------------------------------ execution shape (AC2 order at run time, AC3 one unit)

    @Test
    fun `run executes steps in plan order, strictly one after another`() {
        val events = Collections.synchronizedList(mutableListOf<String>())

        fun traced(
            name: String,
            phase: DataStepPhase,
            kind: DataStepKind = DataStepKind.ONCE
        ) = FakeStep(name, phase, kind) {
            events += "$name:start"
            delay(25)
            events += "$name:end"
            1
        }

        val steps =
            listOf(
                traced("p-last", DataStepPhase.PIN),
                traced("b-second", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT),
                traced("c-third", DataStepPhase.ITEM_BACKFILL),
                traced("a-first", DataStepPhase.CONFIG_CANONICALIZE)
            )

        runBlocking { runner(steps).run() }

        assertEquals(
            listOf(
                "a-first:start",
                "a-first:end",
                "b-second:start",
                "b-second:end",
                "c-third:start",
                "c-third:end",
                "p-last:start",
                "p-last:end"
            ),
            events.toList()
        )
    }

    @Test
    fun `the applied check, the step body and the record share ONE write unit labelled data-step-name`() {
        val counting = CountingUnitOfWork(sqlite.unitOfWork())
        val spy = OrdinalSpyStore(realStore)
        val bodyOrdinal = AtomicInteger(-1)
        val onceStep =
            FakeStep("unit-once") {
                bodyOrdinal.set(CountingUnitOfWork.currentOrdinal() ?: -2)
                1
            }

        runBlocking { runner(listOf(onceStep), uow = counting, store = spy).run() }

        val body = bodyOrdinal.get()
        assertTrue(body > 0, "the step body must run inside a counted write unit, got $body")
        assertEquals(body, spy.recordOrdinals["unit-once"], "record() must run in the SAME unit as the body")
        assertTrue(
            body in spy.appliedNamesOrdinals,
            "the applied check must be made INSIDE the step's own unit: ${spy.appliedNamesOrdinals}"
        )
        assertTrue("data-step:unit-once" in counting.ops, "the unit is labelled data-step:<name>: ${counting.ops}")
    }

    @Test
    fun `an EVERY_BOOT step runs in a write unit of its own and never calls record`() {
        val counting = CountingUnitOfWork(sqlite.unitOfWork())
        val spy = OrdinalSpyStore(realStore)
        val bodyOrdinal = AtomicInteger(-1)
        val boot =
            FakeStep("boot-step", kind = DataStepKind.EVERY_BOOT) {
                bodyOrdinal.set(CountingUnitOfWork.currentOrdinal() ?: -2)
                1
            }

        runBlocking { runner(listOf(boot), uow = counting, store = spy).run() }

        assertTrue(bodyOrdinal.get() > 0, "an EVERY_BOOT body runs inside a write unit, got ${bodyOrdinal.get()}")
        assertEquals(emptyMap(), spy.recordOrdinals, "record() must never be called for an EVERY_BOOT step")
    }

    @Test
    fun `two runners racing on one database apply a ONCE step exactly once`() {
        // The applied check is inside the writer unit, so the loser observes the winner's row and skips (AC3).
        val first =
            FakeStep("race-step") {
                delay(150)
                1
            }
        val twin =
            FakeStep("race-step") {
                delay(150)
                1
            }
        val a = runner(listOf(first))
        val b = runner(listOf(twin))

        val reports =
            runBlocking {
                listOf(async(Dispatchers.IO) { a.run() }, async(Dispatchers.IO) { b.run() }).awaitAll()
            }

        assertEquals(1, first.runs.get() + twin.runs.get(), "the body must run exactly once across both runners")
        assertEquals(1, reports.count { it.applied == listOf("race-step") }, "exactly one runner applied it: $reports")
        assertEquals(1, reports.count { it.skipped == listOf("race-step") }, "exactly one runner skipped it: $reports")
        assertEquals(listOf("race-step"), rowNames())
    }

    // ------------------------------------------------------------------ S7: failures stop the run (AC5)

    @Test
    fun `S7 a throwing step stops the run, keeps earlier commits and names the step`() {
        val boom = IllegalStateException("kaboom")
        val first = FakeStep("step-1") { 1 }
        val second = FakeStep("step-2", after = setOf("step-1")) { throw boom }
        val third = FakeStep("step-3", after = setOf("step-2")) { 1 }
        val laterBoot = FakeStep("step-4", DataStepPhase.PIN, DataStepKind.EVERY_BOOT) { 1 }

        val e = assertFailsWith<DataStepException> { runBlocking { runner(listOf(third, laterBoot, second, first)).run() } }

        assertTrue("data step step-2 failed" in e.message.orEmpty(), "message must be 'data step <name> failed: ...': ${e.message}")
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it === boom }, "the original exception must be in the cause chain")
        assertEquals(1, first.runs.get())
        assertEquals(1, second.runs.get())
        assertEquals(0, third.runs.get(), "a step after the failing one must not run")
        assertEquals(0, laterBoot.runs.get(), "an EVERY_BOOT step of a later phase must not run after a failure")
        assertEquals(listOf("step-1"), rowNames(), "step-1 stays committed, step-2 has no row, step-3 never ran")
    }

    @Test
    fun `S7 a throwing EVERY_BOOT step stops the run too`() {
        val failing = FakeStep("boot-fail", DataStepPhase.CONFIG_IMPORT, DataStepKind.EVERY_BOOT) { throw IllegalStateException("nope") }
        val after = FakeStep("after-fail", DataStepPhase.PIN)
        val e = assertFailsWith<DataStepException> { runBlocking { runner(listOf(failing, after)).run() } }
        assertTrue("boot-fail" in e.message.orEmpty(), e.message)
        assertEquals(0, after.runs.get())
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `S7 a failed step is retried on the next run while the applied ones are not re-run`() {
        val failOnce = AtomicInteger(1)
        val first = FakeStep("retry-1") { 1 }
        val second =
            FakeStep("retry-2", after = setOf("retry-1")) {
                if (failOnce.getAndDecrement() > 0) throw IllegalStateException("transient")
                2
            }
        val third = FakeStep("retry-3", after = setOf("retry-2")) { 3 }
        val steps = listOf(first, second, third)

        assertFailsWith<DataStepException> { runBlocking { runner(steps).run() } }
        val report = runBlocking { runner(steps).run() }

        assertEquals(DataStepReport(listOf("retry-2", "retry-3"), listOf("retry-1"), emptyList()), report)
        assertEquals(1, first.runs.get(), "the step applied before the failure is not re-run")
        assertEquals(2, second.runs.get())
        assertEquals(1, third.runs.get())
        assertEquals(listOf("retry-1", "retry-2", "retry-3"), rowNames())
    }

    @Test
    fun `probe a step returning a negative row count cannot be recorded and fails the run`() {
        val bad = FakeStep("negative-rows") { -1 }
        val after = FakeStep("after-negative", DataStepPhase.PIN)

        val e = assertFailsWith<DataStepException> { runBlocking { runner(listOf(bad, after)).run() } }

        assertTrue("negative-rows" in e.message.orEmpty(), "message must name the step: ${e.message}")
        assertEquals(0, after.runs.get())
        assertEquals(emptyList(), rows(), "the rows_affected >= 0 CHECK keeps the bad record out of the ledger")
    }

    // ------------------------------------------------------------------ S8: the readiness gate (AC6)

    @Test
    fun `S8 a store that loses the record makes run fail naming the unapplied ONCE step`() {
        val step = FakeStep("lost-record")
        val store = DroppingStore(realStore, setOf("lost-record"))

        val e = assertFailsWith<DataStepException> { runBlocking { runner(listOf(step), store = store).run() } }

        assertEquals(1, step.runs.get(), "the body ran: the failure comes from the post-run gate, not from the body")
        assertTrue("lost-record" in e.message.orEmpty(), "message must name the unapplied step: ${e.message}")
    }

    @Test
    fun `S8 the gate names every unapplied ONCE step and none that has a row or is EVERY_BOOT`() {
        val lostA = FakeStep("alpha-lost")
        val lostB = FakeStep("bravo-lost", DataStepPhase.PIN)
        val kept = FakeStep("charlie-kept", DataStepPhase.CONFIG_IMPORT)
        val boot = FakeStep("delta-boot", DataStepPhase.CONFIG_CANONICALIZE, DataStepKind.EVERY_BOOT)
        val store = DroppingStore(realStore, setOf("alpha-lost", "bravo-lost"))

        val e = assertFailsWith<DataStepException> { runBlocking { runner(listOf(lostA, lostB, kept, boot), store = store).run() } }

        val message = e.message.orEmpty()
        assertTrue("alpha-lost" in message && "bravo-lost" in message, "every unapplied ONCE step must be named: $message")
        assertFalse("charlie-kept" in message, "a step with a row must not be named: $message")
        assertFalse("delta-boot" in message, "an EVERY_BOOT step must not be named: $message")
        assertEquals(listOf("charlie-kept"), rowNames())
    }

    @Test
    fun `S8 control - with a faithful store the same registration passes the gate`() {
        val steps = listOf(FakeStep("alpha-lost"), FakeStep("bravo-lost", DataStepPhase.PIN))
        val report = runBlocking { runner(steps).run() }
        assertEquals(listOf("alpha-lost", "bravo-lost"), report.applied)
        assertNotNull(rows().find { it["name"] == "bravo-lost" })
    }
}

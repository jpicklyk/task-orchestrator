package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8df44a22, R20) for the two store additions of the run tools, over the real SQLite
 * `runs` and `run_items` tables: `RunStore.list(rootIds, status, closedSince)` and `RunStore.findIdsByPrefix(prefix, limit)`.
 *
 * Oracles (never read from a run): task-scope planner additions, "Store and service additions": `list` takes an optional root set
 * (null means every root, a set keeps only runs whose root is in it), an optional status and an optional `closedSince` compared to
 * `closedAt` INCLUSIVELY; filters combine with AND; the `unmarked` filter is applied in Kotlin over rows whose `marks` text decodes
 * to the map, so `list` must return each run once with its decoded marks; `findIdsByPrefix(prefix, limit = 2)` returns at most
 * `limit` ids whose canonical hex form starts with the prefix (the tool maps 0 hits to `not_found` and 2 hits to `ambiguous_id`,
 * which is why the default limit is 2 and a third match never needs to be read). A run id is a UUID; its hex form is the 32
 * characters without dashes. Expected values are literals written from those clauses.
 *
 * Negative-assertion conditions: every exclusion is paired with a control run, in the same fixture, that differs only in the
 * attribute filtered on and IS returned (a root, a status, a closing instant one millisecond later, a mark key), so a filter that
 * returns everything or nothing fails one of the pair.
 *
 * Domain invariants satisfied by construction: roots are depth-0 items and members are children carrying that root; a closed [Run]
 * gets a terminal reason, a `closedAt` and a `closedBy` derived from its status, a paused one a pause reason, an open one none, and
 * every closing instant is after its opening instant; no item belongs to two not-closed runs.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteRunStoreListTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val store get() = sqlite.repositoryProvider().runStore()

    private fun at(text: String): Instant = Instant.parse(text)

    private val t0 = at("2026-10-10T08:00:00Z")

    private fun root(title: String): UUID =
        runBlocking {
            sqlite
                .repositoryProvider()
                .workItemRepository()
                .create(WorkItem(title = title, depth = 0))
                .id
        }

    private fun child(
        root: UUID,
        title: String
    ): UUID =
        runBlocking {
            sqlite
                .repositoryProvider()
                .workItemRepository()
                .create(WorkItem(title = title, parentId = root, depth = 1, rootId = root))
                .id
        }

    /** Creates a run whose state fields are derived from [status]; [members] are added in the same unit. */
    private fun create(
        rootId: UUID,
        status: RunStatus = RunStatus.OPEN,
        id: UUID = UUID.randomUUID(),
        closedAt: Instant = at("2026-10-10T11:00:00Z"),
        marks: Map<String, Instant> = emptyMap(),
        members: List<UUID> = emptyList()
    ): Run =
        runBlocking {
            lateinit var created: Run
            sqlite.unitOfWork().inUnit("test.createRun") {
                created =
                    store.create(
                        Run(
                            id = id,
                            rootId = rootId,
                            status = status,
                            pauseReason = if (status == RunStatus.PAUSED) RunPauseReason.MANUAL else null,
                            terminalReason = if (status == RunStatus.CLOSED) RunTerminalReason.COMPLETED else null,
                            openedBy = "api:t",
                            openedByKind = "service",
                            openedAt = t0,
                            closedBy = if (status == RunStatus.CLOSED) "api:t" else null,
                            closedAt = if (status == RunStatus.CLOSED) closedAt else null,
                            stallAfterSeconds = Run.DEFAULT_STALL_AFTER_SECONDS,
                            marks = marks
                        )
                    )
                if (members.isNotEmpty()) store.addMembers(id, members, t0)
            }
            created
        }

    private fun list(
        rootIds: Set<UUID>? = null,
        status: RunStatus? = null,
        closedSince: Instant? = null
    ): List<UUID> = runBlocking { store.list(rootIds, status, closedSince) }.map { it.id }

    private class Fixture(
        val r1: UUID,
        val r2: UUID,
        val r3: UUID,
        val openR1: Run,
        val pausedR1: Run,
        val closedR1: Run,
        val closedR2: Run,
        val openR2: Run,
        val closedR3Tie: Run
    ) {
        val all: Set<UUID> get() = setOf(openR1, pausedR1, closedR1, closedR2, openR2, closedR3Tie).map { it.id }.toSet()
    }

    /** R1: open, paused, closed at 11:00. R2: closed at 12:00, open. R3: closed at 11:00 (ties with R1's). Each run has two members. */
    private fun fixture(): Fixture {
        val r1 = root("R1")
        val r2 = root("R2")
        val r3 = root("R3")

        fun pair(r: UUID) = listOf(child(r, "m1"), child(r, "m2"))
        return Fixture(
            r1,
            r2,
            r3,
            create(r1, RunStatus.OPEN, members = pair(r1)),
            create(r1, RunStatus.PAUSED, members = pair(r1)),
            create(r1, RunStatus.CLOSED, closedAt = at("2026-10-10T11:00:00Z"), members = pair(r1)),
            create(r2, RunStatus.CLOSED, closedAt = at("2026-10-10T12:00:00Z"), members = pair(r2)),
            create(r2, RunStatus.OPEN, members = pair(r2)),
            create(r3, RunStatus.CLOSED, closedAt = at("2026-10-10T11:00:00Z"), members = pair(r3))
        )
    }

    // ------------------------------------------------------------------ list

    @Test
    fun `list with no filter returns every run exactly once, even though each has two member rows`() {
        val f = fixture()
        val ids = list()
        assertEquals(6, ids.size, "one row per run, never one per membership: $ids")
        assertEquals(f.all, ids.toSet())
    }

    @Test
    fun `list with a root set keeps only runs of those roots, a null set keeps all, and an empty set keeps none`() {
        val f = fixture()
        assertEquals(setOf(f.openR1.id, f.pausedR1.id, f.closedR1.id), list(rootIds = setOf(f.r1)).toSet())
        assertEquals(setOf(f.closedR2.id, f.openR2.id), list(rootIds = setOf(f.r2)).toSet())
        assertEquals(setOf(f.closedR3Tie.id), list(rootIds = setOf(f.r3)).toSet())
        assertEquals(
            setOf(f.openR1.id, f.pausedR1.id, f.closedR1.id, f.closedR2.id, f.openR2.id),
            list(rootIds = setOf(f.r1, f.r2)).toSet()
        )
        assertEquals(f.all, list(rootIds = null).toSet(), "null is every root")
        assertEquals(emptyList(), list(rootIds = emptySet()), "an empty root set admits nothing")
        assertEquals(emptyList(), list(rootIds = setOf(UUID.randomUUID())), "a root with no runs yields none")
    }

    @Test
    fun `list by status returns exactly the runs in that status`() {
        val f = fixture()
        assertEquals(setOf(f.openR1.id, f.openR2.id), list(status = RunStatus.OPEN).toSet())
        assertEquals(setOf(f.pausedR1.id), list(status = RunStatus.PAUSED).toSet())
        assertEquals(setOf(f.closedR1.id, f.closedR2.id, f.closedR3Tie.id), list(status = RunStatus.CLOSED).toSet())
    }

    @Test
    fun `list closedSince is inclusive at the closing instant and excludes runs that are not closed`() {
        val f = fixture()
        assertEquals(
            setOf(f.closedR1.id, f.closedR2.id, f.closedR3Tie.id),
            list(closedSince = at("2026-10-10T11:00:00Z")).toSet(),
            "a run closed exactly at the bound is included"
        )
        assertEquals(
            setOf(f.closedR2.id),
            list(closedSince = at("2026-10-10T11:00:00.001Z")).toSet(),
            "one millisecond later excludes the 11:00 closes and keeps the 12:00 one"
        )
        assertEquals(
            setOf(f.closedR2.id),
            list(closedSince = at("2026-10-10T12:00:00Z")).toSet(),
            "inclusive at the later bound too"
        )
        assertEquals(emptyList(), list(closedSince = at("2026-10-10T12:00:00.001Z")), "past every close, nothing remains")
        assertEquals(
            setOf(f.closedR1.id, f.closedR2.id, f.closedR3Tie.id,),
            list(closedSince = at("2026-10-10T10:59:59.999Z")).toSet(),
            "one millisecond before keeps them"
        )
        assertTrue(f.openR1.id !in list(closedSince = at("2026-01-01T00:00:00Z")), "an open run has no closedAt and never matches a bound")
        assertTrue(
            f.pausedR1.id !in list(closedSince = at("2026-01-01T00:00:00Z")),
            "a paused run has no closedAt and never matches a bound"
        )
    }

    @Test
    fun `list filters combine with AND`() {
        val f = fixture()
        assertEquals(setOf(f.closedR1.id), list(setOf(f.r1), RunStatus.CLOSED).toSet())
        assertEquals(setOf(f.closedR2.id), list(setOf(f.r2), RunStatus.CLOSED, at("2026-10-10T12:00:00Z")).toSet())
        assertEquals(emptyList(), list(setOf(f.r1), RunStatus.CLOSED, at("2026-10-10T11:00:00.001Z")))
        assertEquals(emptyList(), list(setOf(f.r2), RunStatus.PAUSED))
        assertEquals(setOf(f.closedR1.id, f.closedR3Tie.id), list(setOf(f.r1, f.r3), RunStatus.CLOSED, at("2026-10-10T11:00:00Z")).toSet())
    }

    @Test
    fun `list returns the decoded marks of each run so the unmarked filter can run over them`() {
        val r = root("R")
        val retro = at("2026-10-10T11:30:00Z")
        val audit = at("2026-10-10T11:45:30.250Z")
        val marked = create(r, RunStatus.CLOSED, marks = mapOf("retro" to retro, "a-b_c9" to audit))
        val plain = create(r, RunStatus.CLOSED)
        val byId = runBlocking { store.list(null, RunStatus.CLOSED, null) }.associateBy { it.id }
        assertEquals(mapOf("retro" to retro, "a-b_c9" to audit), byId.getValue(marked.id).marks)
        assertEquals(emptyMap(), byId.getValue(plain.id).marks, "control: a run with no marks decodes to an empty map")
        assertEquals(byId.getValue(marked.id).marks, runBlocking { store.getById(marked.id) }!!.marks, "list and getById agree")
    }

    @Test
    fun `a run with the maximum of 32 marks round-trips all of them`() {
        val r = root("R")
        val marks = (0 until 32).associate { "m%02d".format(it) to at("2026-10-10T11:30:00Z").plusSeconds(it.toLong()) }
        val run = create(r, RunStatus.CLOSED, marks = marks)
        assertEquals(marks, runBlocking { store.list(setOf(r), null, null) }.single { it.id == run.id }.marks)
    }

    @Test
    fun `a status change made through the store is what list filters on afterwards`() {
        val f = fixture()
        val before = runBlocking { store.getById(f.openR1.id) }!!
        runBlocking {
            sqlite.unitOfWork().inUnit("test.pause") {
                store.update(before.copy(status = RunStatus.PAUSED, pauseReason = RunPauseReason.MANUAL), before.version)
            }
        }
        assertEquals(setOf(f.pausedR1.id, f.openR1.id), list(status = RunStatus.PAUSED).toSet())
        assertEquals(setOf(f.openR2.id), list(status = RunStatus.OPEN).toSet())
    }

    // ------------------------------------------------------------------ findIdsByPrefix

    private fun hex(id: UUID) = id.toString().replace("-", "")

    private fun prefix(
        prefix: String,
        limit: Int? = null
    ): List<UUID> = runBlocking { if (limit == null) store.findIdsByPrefix(prefix) else store.findIdsByPrefix(prefix, limit) }

    private fun runWithId(
        text: String,
        root: UUID
    ) = create(root, id = UUID.fromString(text))

    @Test
    fun `findIdsByPrefix finds a run by an 8 character prefix and by the full 32 characters`() {
        val r = root("R")
        val a = runWithId("a0000001-0000-4000-8000-000000000001", r)
        val b = runWithId("b0000002-0000-4000-8000-000000000002", r)
        assertEquals(listOf(a.id), prefix("a0000001"))
        assertEquals(listOf(b.id), prefix("b0000002"))
        assertEquals(listOf(a.id), prefix(hex(a.id)), "the full 32-character form")
        assertEquals(listOf(a.id), prefix(hex(a.id).substring(0, 20)), "a prefix that crosses dash positions of the canonical form")
        assertEquals(emptyList(), prefix("c0000003"), "no run carries it")
        assertEquals(emptyList(), prefix("a0000002"), "near miss: shares the first seven characters only")
        assertEquals(emptyList(), prefix(hex(a.id).dropLast(1) + "2"), "near miss in the last character")
    }

    @Test
    fun `findIdsByPrefix returns at most limit ids, two by default, so a third match is never needed`() {
        val r = root("R")
        val ids =
            (1..3).map { runWithId("ab000000-0000-4000-8000-00000000000$it", r).id }
        val byDefault = prefix("ab000000")
        assertEquals(2, byDefault.size, "the default limit is 2: $byDefault")
        assertTrue(ids.containsAll(byDefault), "every returned id carries the prefix")
        assertEquals(1, prefix("ab000000", limit = 1).size)
        assertEquals(3, prefix("ab000000", limit = 3).size)
        assertEquals(ids.toSet(), prefix("ab000000", limit = 10).toSet(), "a limit above the match count returns all matches")
    }

    @Test
    fun `findIdsByPrefix separates ids that differ only in the last character and keeps a shared 31 character prefix ambiguous`() {
        val r = root("R")
        val a = runWithId("ab000000-0000-4000-8000-000000000001", r)
        val b = runWithId("ab000000-0000-4000-8000-000000000002", r)
        assertEquals(setOf(a.id, b.id), prefix(hex(a.id).dropLast(1)).toSet(), "31 shared characters match both")
        assertEquals(listOf(a.id), prefix(hex(a.id)))
        assertEquals(listOf(b.id), prefix(hex(b.id)))
    }

    @Test
    fun `findIdsByPrefix does not look at status, so closed and paused runs are found too`() {
        val r = root("R")
        val closed = create(r, RunStatus.CLOSED, id = UUID.fromString("c1000000-0000-4000-8000-000000000001"))
        val paused = create(r, RunStatus.PAUSED, id = UUID.fromString("d1000000-0000-4000-8000-000000000001"))
        assertEquals(listOf(closed.id), prefix("c1000000"))
        assertEquals(listOf(paused.id), prefix("d1000000"))
    }
}

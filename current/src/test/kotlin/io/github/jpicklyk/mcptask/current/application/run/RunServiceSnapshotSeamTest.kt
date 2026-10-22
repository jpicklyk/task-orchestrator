package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.config.RunsConfig
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 74bb6db9, R28) for the snapshot SEAM of [RunService]: the `rollupSnapshot` parameter that
 * `RunService.close` calls inside the close unit and whose result is stored under `rollup` in the `run.ended` data. The seam is
 * driven with hand-made [RunRollupSnapshot] values, so these tests state the seam contract without the real rollup behind it (the
 * real wiring is in `RunRollupSnapshotTest`).
 *
 * Oracles: task-scope AC8 (called by `RunService.close` inside the close unit, after `onClose` and before `run.ended` is recorded, for
 * EVERY terminal reason including budget_exhausted; its result is stored in the `run.ended` data under `rollup` and the key is omitted
 * when the result is null; the default is NONE so a service built without a seam closes as before; a throw rolls the close back, the
 * same contract as `onClose`) and D1 (the seam is a new defaulted constructor parameter). The expected `rollup` JSON is written out
 * from the maps the hand-made seams return.
 *
 * Negative-assertion conditions: "no key" is asserted for a seam that returns null AND for a service built without a seam, each next to
 * a seam that returns a map and stores it; the rollback assertions are paired with the same close succeeding once the seam stops
 * throwing.
 *
 * Forbidden-construct declaration: none. The throw case catches the failure of the close call itself with `runCatching`; the assertions
 * are on the stored state afterwards and are outside the handler.
 *
 * Domain invariants satisfied by construction: runs are opened through the service over a root created through the repository.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunServiceSnapshotSeamTest {
    private val clock = SettableClock(Instant.parse("2026-03-01T10:00:00Z"))

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private class RecordingHook(
        private val sink: MutableList<String>
    ) : RunCloseHook {
        override suspend fun onClose(
            run: Run,
            snapshot: RunEndedSnapshot
        ) {
            sink += "hook"
        }
    }

    private inner class Rig(
        snapshot: RunRollupSnapshot?,
        val order: MutableList<String> = mutableListOf()
    ) {
        val provider get() = sqlite.db.repositoryProvider()
        val uow = sqlite.db.unitOfWork(clock)
        private val claims = ClaimService(provider, uow)
        val store get() = provider.runStore()
        val service =
            if (snapshot == null) {
                RunService(provider, uow, claims, runsConfig = { RunsConfig() }, onClose = RecordingHook(order))
            } else {
                RunService(provider, uow, claims, runsConfig = { RunsConfig() }, onClose = RecordingHook(order), rollupSnapshot = snapshot)
            }

        suspend fun openRun(): Run {
            val root = provider.workItemRepository().create(WorkItem(title = "root", depth = 0))
            return service.open(RunOpenCommand(rootId = root.id)).ok()
        }

        suspend fun maxSeq(): Long = provider.eventStore().maxSeq()

        suspend fun rowsAfter(seq: Long) = provider.eventStore().readAfter(seq, null, 100_000)
    }

    private val allReasons =
        listOf(
            RunTerminalReason.COMPLETED,
            RunTerminalReason.CANCELLED,
            RunTerminalReason.FAILED,
            RunTerminalReason.ABANDONED,
            RunTerminalReason.BUDGET_EXHAUSTED
        )

    @Test
    fun `the seam is called once per close for every terminal reason and its map is stored under rollup in the run ended data`(): Unit =
        runBlocking {
            val calls = mutableListOf<UUID>()
            val rig =
                Rig(
                    RunRollupSnapshot { id ->
                        calls += id
                        mapOf("marker" to "m${calls.size}", "counts" to mapOf("a" to 1, "b" to 2), "flag" to true)
                    }
                )

            for ((index, reason) in allReasons.withIndex()) {
                val run = rig.openRun()
                val mark = rig.maxSeq()

                rig.service.close(run.id, reason).ok()

                assertEquals(index + 1, calls.size, "$reason: exactly one snapshot call per close")
                assertEquals(run.id, calls.last(), "$reason: the snapshot is asked for the closing run")
                val ended = rig.rowsAfter(mark).single { it.type == "run.ended" }
                assertEquals(
                    Json.parseToJsonElement("""{"marker":"m${index + 1}","counts":{"a":1,"b":2},"flag":true}"""),
                    ended.payload()["rollup"],
                    "$reason: the seam's map is stored verbatim under rollup"
                )
                assertEquals(
                    reason.name.lowercase(),
                    ended
                        .payload()
                        .getValue("reason")
                        .toString()
                        .trim('"'),
                    "$reason: the existing members are kept"
                )
            }
        }

    @Test
    fun `a seam that returns null and a service built without a seam both leave the rollup key out`(): Unit =
        runBlocking {
            for (rig in listOf(Rig(RunRollupSnapshot { null }), Rig(null))) {
                val run = rig.openRun()
                val mark = rig.maxSeq()

                rig.service.close(run.id, RunTerminalReason.CANCELLED).ok()

                val payload = rig.rowsAfter(mark).single { it.type == "run.ended" }.payload()
                assertFalse("rollup" in payload, "no rollup key when there is nothing to store: ${payload.keys}")
                assertTrue("reason" in payload, "control: the run.ended row itself is recorded")
            }
        }

    @Test
    fun `the seam runs after the close hook, sees the closed run and runs before the run ended row exists`(): Unit =
        runBlocking {
            val order = mutableListOf<String>()
            lateinit var rig: Rig
            var mark = 0L
            rig =
                Rig(
                    RunRollupSnapshot { id ->
                        val status = rig.store.getById(id)?.status
                        order += "snapshot:$status:" + rig.rowsAfter(mark).joinToString(",") { it.type }
                        mapOf("x" to 1)
                    },
                    order
                )
            val run = rig.openRun()
            mark = rig.maxSeq()

            rig.service.close(run.id, RunTerminalReason.CANCELLED).ok()

            assertEquals(
                listOf("hook", "snapshot:${RunStatus.CLOSED}:"),
                order,
                "the hook first; then the snapshot, reading the closed row inside the unit, before any run.ended row"
            )
            assertEquals(listOf("run.ended"), rig.rowsAfter(mark).map { it.type }, "control: the run.ended row is written afterwards")
        }

    @Test
    fun `a throwing seam rolls the close back and the same close succeeds once the seam stops throwing`(): Unit =
        runBlocking {
            var fail = true
            val rig =
                Rig(
                    RunRollupSnapshot {
                        if (fail) throw IllegalStateException("snapshot failed")
                        mapOf("x" to 1)
                    }
                )
            val run = rig.openRun()
            val mark = rig.maxSeq()

            runCatching { rig.service.close(run.id, RunTerminalReason.CANCELLED) }

            assertEquals(RunStatus.OPEN, rig.store.getById(run.id)?.status, "the close was rolled back: the run is still open")
            assertEquals(emptyList(), rig.rowsAfter(mark), "no cascade row and no run.ended survives the rollback")

            fail = false
            val result = rig.service.close(run.id, RunTerminalReason.CANCELLED).ok()

            assertEquals(RunStatus.CLOSED, result.run.status)
            assertNotNull(
                rig.rowsAfter(mark).single { it.type == "run.ended" }.payload()["rollup"],
                "control: the retry stores the snapshot"
            )
        }
}

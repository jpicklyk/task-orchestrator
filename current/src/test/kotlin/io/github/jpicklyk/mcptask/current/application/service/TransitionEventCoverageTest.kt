package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent P11 tests (item 919d379e) for S15: `item.transitioned` is recorded by AdvanceService once per applied
 * transition, by AdvanceService alone.
 *
 * Oracles: AC6 (item.transitioned recorded exactly once per applied transition, primary and each cascade, with origin
 * user | cascade, by AdvanceService). The role_transitions store this once decorated is retired (Q28b).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class TransitionEventCoverageTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    @Test
    fun `S15 the number of item transitioned rows equals the number of applied transitions across primaries and cascades`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val grand = d.item("grand", Role.QUEUE)
            val parent = d.item("parent", Role.QUEUE, parent = grand)
            val child = d.item("child", Role.QUEUE, parent = parent)
            val mark = d.rig.maxSeq()

            assertEquals(true, d.advance(child, "start").flag("applied"))
            assertEquals(true, d.advance(child, "complete").flag("applied"))

            val rows = d.rig.rowsAfter(mark).transitioned()
            assertEquals(5, rows.size, "start cascades one level (parent only), complete cascades two ancestors: ${rows.map { it.type }}")
            val expected =
                listOf(
                    Triple(child.id, "start", "queue>work"),
                    Triple(parent.id, "cascade", "queue>work"),
                    Triple(child.id, "complete", "work>terminal"),
                    Triple(parent.id, "cascade", "work>terminal"),
                    Triple(grand.id, "cascade", "queue>terminal"),
                ).sortedBy { it.toString() }
            val actual =
                rows
                    .map {
                        Triple(
                            it.entityId,
                            it.str("trigger"),
                            it.str("fromRole") + ">" + it.str("toRole")
                        )
                    }.sortedBy { it.toString() }
            assertEquals(expected, actual, "events carry the item, trigger and roles of each applied transition")
            val cascadeRows = rows.filter { it.str("origin") == "cascade" }
            assertEquals(3, cascadeRows.size)
            assertEquals(2, rows.count { it.str("origin") == "user" })
            assertEquals(setOf("cascade"), cascadeRows.map { it.str("trigger") }.toSet())
        }

    @Test
    fun `S15 every user trigger family records exactly one user-origin event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("lifecycle", Role.QUEUE)
            val mark = d.rig.maxSeq()
            val sequence = listOf("start", "block", "resume", "hold", "resume", "complete", "reopen", "cancel")

            sequence.forEach { trigger -> assertEquals(true, d.advance(item, trigger).flag("applied"), "$trigger must apply") }

            val rows =
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .sortedBy { it.seq }
            assertEquals(sequence, rows.map { it.str("trigger") }, "one row per applied transition, in order")
            assertEquals(setOf("user"), rows.map { it.str("origin") }.toSet())
        }

    @Test
    fun `S15 a rejected or invalid advance records no item transitioned row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val gated = d.item("gated", Role.QUEUE, type = "p11-gated")
            val done = d.item("done", Role.TERMINAL)
            val mark = d.rig.maxSeq()

            assertEquals(false, d.advance(gated, "start").flag("applied"))
            assertEquals(false, d.advance(done, "start").flag("applied"))

            assertEquals(emptyList(), d.rig.rowsAfter(mark).transitioned())
        }

    @Test
    fun `S15 a replayed keyed advance records no second event`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("keyed", Role.QUEUE)
            val key = UUID.randomUUID().toString()
            val params = d.advanceParams(item, "start", actor = "agent-1", requestId = key)
            val mark = d.rig.maxSeq()

            d.rig.call(AdvanceItemTool(), *params)
            d.rig.call(AdvanceItemTool(), *params)

            assertEquals(
                1,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size,
                "the replay executes nothing"
            )
            assertEquals(Role.WORK, d.reload(item).role)
        }
}

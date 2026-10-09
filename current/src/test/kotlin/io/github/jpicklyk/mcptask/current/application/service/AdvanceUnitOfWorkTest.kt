package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Independent P11 tests (item 919d379e) for the one-unit AdvanceService: scenarios S1, S2, S3, S4 and S6.
 *
 * Oracles: task-scope 1.1 (one `unitOfWork.write("AdvanceService.advance")`, cascades applied inside it, label and
 * row rules), plan 3.3 / 3.7 (one `item.transitioned` row per applied transition, origin user | cascade), and the
 * transition matrix of P3 as quoted in the test-plan (C6/C7 dependency gates, C8 lifecycle). No expected value is read
 * from the implementation.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceUnitOfWorkTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path): P11Driver = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    // ---------------------------------------------------------------------------------------------
    // S1 -- Q start -> W, schema-free: row, label, instant, event, exactly one write unit
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 a start advance opens exactly one write unit and stamps the unit instant, the row and the target-role label`(): Unit =
        runBlocking {
            val clock = SettableClock(Instant.parse("2026-05-05T10:00:00Z"))
            SqliteTestDatabase.open(clock = clock).use { sdb ->
                val provider = sdb.repositoryProvider()
                val counting = CountingUnitOfWork(sdb.unitOfWork(clock))
                val created = provider.workItemRepository().create(WorkItem(title = "S1", role = Role.QUEUE))
                clock.advanceSeconds(90)
                val unitInstant = clock.now()
                val service =
                    AdvanceService(
                        workItemRepository = provider.workItemRepository(),
                        roleTransitionRepository = provider.roleTransitionRepository(),
                        dependencyRepository = provider.dependencyRepository(),
                        noteRepository = provider.noteRepository(),
                        labelFor = { trigger, target -> "lbl:${trigger.wire}:${target.name}" },
                        schemaResolver = { null },
                        unitOfWork = counting,
                        clock = clock,
                    )

                val outcome =
                    service.advance(created, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)

                val result = assertIs<AdvanceOutcome.Success>(outcome, "start must apply: $outcome").result
                assertEquals(Role.WORK, result.newRole)
                assertEquals("lbl:start:WORK", result.statusLabel, "the label is looked up for (trigger, TARGET role)")
                assertEquals(1, counting.writes, "an unkeyed advance is exactly one write unit; ops=${counting.ops}")
                assertEquals(listOf("AdvanceService.advance"), counting.ops)
                val persisted = provider.workItemRepository().getById(created.id)!!
                assertEquals(Role.WORK, persisted.role)
                assertEquals("lbl:start:WORK", persisted.statusLabel)
                assertEquals(unitInstant, persisted.roleChangedAt, "roleChangedAt is the unit instant, not the creation instant")
                val rows = provider.roleTransitionRepository().findByItemId(created.id, limit = 50)
                assertEquals(1, rows.size)
                assertEquals("start", rows.single().trigger)
                assertEquals("queue", rows.single().fromRole)
                assertEquals("work", rows.single().toRole)
            }
        }

    @Test
    fun `S1 the advance_item tool call opens one write unit per transition and none for a rejected one`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val repo = db.repositoryProvider().workItemRepository()
            val ok = repo.create(WorkItem(title = "ok", role = Role.QUEUE))
            val invalid = repo.create(WorkItem(title = "already terminal", role = Role.TERMINAL))

            val data = AdvanceItemTool().execute(startParams(ok.id), ctx).dataOf()
            assertEquals(true, data.arr("results")[0].jsonObject.flag("applied"), "control: the advance applied: $data")
            assertEquals(1, counting.writes, "one transition is one write unit; ops=${counting.ops}")
            assertEquals(listOf("AdvanceService.advance"), counting.ops)

            val rejected = AdvanceItemTool().execute(startParams(invalid.id), ctx).dataOf()
            assertEquals(false, rejected.arr("results")[0].jsonObject.flag("applied"))
            assertEquals("invalid_transition", rejected.arr("results")[0].jsonObject.text("errorCode"))
            assertEquals(2, counting.writes, "the rejected advance also ran as one unit (it rolls back); ops=${counting.ops}")
        }

    @Test
    fun `S1 the transition records exactly one item transitioned event with origin user`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("S1 event", Role.QUEUE)
            val mark = d.rig.maxSeq()

            val r = d.advance(item, "start")

            assertEquals(true, r.flag("applied"), "$r")
            val rows = d.rig.rowsAfter(mark)
            assertEquals(listOf("item.transitioned"), rows.map { it.type }, "exactly one event row for one applied transition: $rows")
            val row = rows.single()
            assertEquals(item.id, row.entityId)
            assertEquals("user", row.str("origin"))
            assertEquals("start", row.str("trigger"))
            assertEquals("queue", row.str("fromRole"))
            assertEquals("work", row.str("toRole"))
        }

    // ---------------------------------------------------------------------------------------------
    // S2 -- last child complete cascades the parent (depth 3) in the same unit
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 completing the last child of a depth-3 chain cascades both ancestors to terminal in one unit`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val grand = d.item("grand", Role.WORK)
            val parent = d.item("parent", Role.WORK, parent = grand)
            val child = d.item("child", Role.WORK, parent = parent)
            val mark = d.rig.maxSeq()

            val r = d.advance(child, "complete")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals("terminal", r.text("newRole"))
            val cascades = r.arr("cascadeEvents").map { it.jsonObject }
            assertEquals(2, cascades.size, "one cascade event per ancestor: $r")
            assertEquals(listOf(parent.id.toString(), grand.id.toString()), cascades.map { it.text("itemId") })
            cascades.forEach {
                assertEquals(true, it.flag("applied"), "$it")
                assertEquals("work", it.text("previousRole"))
                assertEquals("terminal", it.text("targetRole"))
                assertEquals("done", it.text("statusLabel"), "cascade label default is done: $it")
            }
            assertEquals(Role.TERMINAL, d.role(parent))
            assertEquals(Role.TERMINAL, d.role(grand))
            assertEquals("cascade", d.transitions(parent).single().trigger)
            assertEquals("cascade", d.transitions(grand).single().trigger)
            assertEquals("complete", d.transitions(child).single().trigger)
            assertEquals("done", d.reload(parent).statusLabel)

            val rows = d.rig.rowsAfter(mark)
            val transitioned = rows.transitioned()
            assertEquals(3, transitioned.size, "one event per applied transition (primary + 2 cascades): ${rows.map { it.type }}")
            assertEquals("user", transitioned.single { it.entityId == child.id }.str("origin"))
            assertEquals("cascade", transitioned.single { it.entityId == parent.id }.str("origin"))
            assertEquals("cascade", transitioned.single { it.entityId == grand.id }.str("origin"))
            val seqs = rows.map { it.seq }
            assertEquals((seqs.first()..seqs.last()).toList(), seqs, "primary and cascades commit in ONE unit: contiguous seq")
        }

    @Test
    fun `S2 a parent with an unfinished sibling is not cascaded`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.WORK)
            val a = d.item("a", Role.WORK, parent = parent)
            d.item("b", Role.WORK, parent = parent)

            val r = d.advance(a, "complete")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals(0, r.arr("cascadeEvents").size, "control for the S2 cascade: an active sibling blocks it: $r")
            assertEquals(Role.WORK, d.role(parent))
        }

    // ---------------------------------------------------------------------------------------------
    // S3 -- start cascade; manual / permanent parents are NotApplicable (C8)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 starting a child cascades an auto parent from queue to work and records a cascade row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.QUEUE)
            val child = d.item("child", Role.QUEUE, parent = parent)
            val mark = d.rig.maxSeq()

            val r = d.advance(child, "start")

            assertEquals(true, r.flag("applied"), "$r")
            val cascade = r.arr("cascadeEvents").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"))
            assertEquals(true, cascade.flag("applied"))
            assertEquals("queue", cascade.text("previousRole"))
            assertEquals("work", cascade.text("targetRole"))
            assertEquals(Role.WORK, d.role(parent))
            assertEquals("cascade", d.transitions(parent).single().trigger)
            assertEquals(
                listOf("user", "cascade"),
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .sortedBy { it.seq }
                    .map { it.str("origin") },
            )
        }

    @Test
    fun `S3 a manual or permanent parent is not started by a child start and no cascade event appears`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (type in listOf("p11-manual", "p11-permanent")) {
                val parent = d.item("parent-$type", Role.QUEUE, type = type)
                val child = d.item("child-$type", Role.QUEUE, parent = parent)
                val mark = d.rig.maxSeq()

                val r = d.advance(child, "start")

                assertEquals(true, r.flag("applied"), "$type: the child itself still starts: $r")
                assertEquals(Role.WORK, d.role(child))
                assertEquals(0, r.arr("cascadeEvents").size, "$type: NotApplicable yields no cascade event at all: $r")
                assertEquals(Role.QUEUE, d.role(parent), "$type: lifecycle $type parents are never auto-started")
                assertEquals(0, d.transitions(parent).size)
                assertEquals(
                    1,
                    d.rig
                        .rowsAfter(mark)
                        .transitioned()
                        .size,
                    "$type: only the child's transition is recorded"
                )
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S4 -- reopen cascade T -> W, no note gate; manual parent excluded
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 reopening a terminal child reopens a terminal parent to work without evaluating its note gate`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            // p11-plain declares required notes (spec, impl); none is filled, so a gate on the parent would reject.
            val parent = d.item("parent", Role.TERMINAL, type = "p11-plain")
            val child = d.item("child", Role.TERMINAL, parent = parent)
            val mark = d.rig.maxSeq()

            val r = d.advance(child, "reopen")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals("queue", r.text("newRole"))
            val cascade = r.arr("cascadeEvents").single().jsonObject
            assertEquals(true, cascade.flag("applied"), "the reopen cascade has no note gate: $cascade")
            assertEquals("terminal", cascade.text("previousRole"))
            assertEquals("work", cascade.text("targetRole"))
            assertFalse(cascade.flag("gateBlocked") ?: false)
            assertEquals(Role.WORK, d.role(parent))
            assertEquals("cascade", d.transitions(parent).single().trigger)
            assertEquals(
                2,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size
            )
        }

    @Test
    fun `S4 a manual parent is not reopened by a child reopen`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.TERMINAL, type = "p11-manual")
            val child = d.item("child", Role.TERMINAL, parent = parent)

            val r = d.advance(child, "reopen")

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals(0, r.arr("cascadeEvents").size, "$r")
            assertEquals(Role.TERMINAL, d.role(parent))
        }

    // ---------------------------------------------------------------------------------------------
    // S6 -- keyed advance joins the element unit; replay executes nothing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a keyed advance opens no unit of its own and a replay returns the stored result without a second row`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val repo = db.repositoryProvider()
            val item = repo.workItemRepository().create(WorkItem(title = "keyed", role = Role.QUEUE))
            val key = UUID.randomUUID().toString()
            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", item.id.toString())
                                    put("trigger", "start")
                                    put(
                                        "actor",
                                        buildJsonObject {
                                            put("id", "agent-1")
                                            put("kind", "subagent")
                                        }
                                    )
                                },
                            )
                        },
                    )
                    put("requestId", JsonPrimitive(key))
                }

            val first =
                AdvanceItemTool()
                    .execute(params, ctx)
                    .dataOf()
                    .arr("results")[0]
                    .jsonObject

            assertEquals(true, first.flag("applied"), "$first")
            assertEquals(1, counting.writes, "the element's unit is the only unit; ops=${counting.ops}")
            assertFalse("AdvanceService.advance" in counting.ops, "the advance JOINS the keyed element unit: ${counting.ops}")
            assertEquals(1, repo.roleTransitionRepository().findByItemId(item.id, limit = 10).size)

            val replay =
                AdvanceItemTool()
                    .execute(params, ctx)
                    .dataOf()
                    .arr("results")[0]
                    .jsonObject

            assertEquals(true, replay.flag("replayed"), "an identical retry replays: $replay")
            assertEquals(1, repo.roleTransitionRepository().findByItemId(item.id, limit = 10).size, "replay executes nothing")
            assertEquals(Role.WORK, repo.workItemRepository().getById(item.id)!!.role)
            assertFalse("AdvanceService.advance" in counting.ops, "still no advance unit of its own: ${counting.ops}")
        }

    @Test
    fun `S6 an unkeyed repeat of a start is a second applied transition, never a replay`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val repo = db.repositoryProvider()
            val item = repo.workItemRepository().create(WorkItem(title = "unkeyed", role = Role.QUEUE))

            AdvanceItemTool().execute(startParams(item.id), ctx)
            val second =
                AdvanceItemTool()
                    .execute(startParams(item.id), ctx)
                    .dataOf()
                    .arr("results")[0]
                    .jsonObject

            assertEquals(null, second.flag("replayed"), "no requestId, no replay: $second")
            assertEquals(2, counting.ops.count { it == "AdvanceService.advance" }, "each unkeyed call is its own unit: ${counting.ops}")
            assertEquals(2, repo.roleTransitionRepository().findByItemId(item.id, limit = 10).size)
        }

    private fun startParams(id: UUID): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", id.toString())
                            put("trigger", "start")
                        },
                    )
                },
            )
        }

    private fun kotlinx.serialization.json.JsonElement.dataOf(): JsonObject = (this as JsonObject)["data"] as JsonObject
}

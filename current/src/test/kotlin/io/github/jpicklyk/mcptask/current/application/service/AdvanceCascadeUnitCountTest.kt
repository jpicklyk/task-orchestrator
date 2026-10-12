package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.actorJson
import io.github.jpicklyk.mcptask.current.test.advanceDirect
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Independent P11 tests (item 919d379e, round r2), gap 1: scenarios S1/S2/S3/S6 on an advance that CASCADES.
 *
 * Oracle: task-scope 1.1 ("ONE unitOfWork.write("AdvanceService.advance")", cascades applied inside it; a keyed advance
 * JOINS the keyed element's unit) and AC1 (an unkeyed advance opens exactly one write unit, a keyed advance none of its
 * own). A cascade that ran in its own outermost unit after the primary committed would raise the outermost write count
 * above one, which is what these tests pin. Each test first proves the cascade really happened (cascade events, roles),
 * so a count of one cannot be the result of a cascade that never ran.
 *
 * S1 to S3 drive the AdvanceService directly (Q32: the unit count is an outcome of the service, and the `advance` tool
 * would wrap it in its own element unit); S6 drives the keyed element through the production `advance` tool.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceCascadeUnitCountTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private suspend fun create(
        title: String,
        role: Role,
        parent: WorkItem? = null,
    ): WorkItem =
        db.repositoryProvider().workItemRepository().create(
            WorkItem(title = title, role = role, parentId = parent?.id, depth = (parent?.depth ?: -1) + 1),
        )

    private suspend fun roleOf(item: WorkItem): Role =
        db
            .repositoryProvider()
            .workItemRepository()
            .getById(item.id)!!
            .role!!

    @Test
    fun `S1 S2 a complete that cascades two ancestors to terminal is still exactly one write unit`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val grand = create("grand", Role.WORK)
            val parent = create("parent", Role.WORK, grand)
            val child = create("child", Role.WORK, parent)

            val r = ctx.advanceDirect(child, "complete").success()

            assertEquals(2, r.cascadeEvents.size, "control: both ancestors cascaded: $r")
            assertEquals(Role.TERMINAL, roleOf(parent))
            assertEquals(Role.TERMINAL, roleOf(grand))
            assertEquals(
                1,
                counting.writes,
                "primary and both cascades are ONE outermost write unit (a cascade in its own unit would make this 3); ops=${counting.ops}",
            )
            assertEquals(listOf("AdvanceService.advance"), counting.ops)
        }

    @Test
    fun `S1 S3 a start that start-cascades a queue parent is still exactly one write unit`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val parent = create("parent", Role.QUEUE)
            val child = create("child", Role.QUEUE, parent)

            val r = ctx.advanceDirect(child, "start").success()

            val cascade = r.cascadeEvents.single()
            assertEquals(true, cascade.applied, "control: the start cascade applied: $cascade")
            assertEquals(Role.WORK, roleOf(parent), "control: the parent was started by the cascade")
            assertEquals(Role.WORK, roleOf(child))
            assertEquals(1, counting.writes, "the start cascade shares the primary's unit; ops=${counting.ops}")
            assertEquals(listOf("AdvanceService.advance"), counting.ops)
        }

    @Test
    fun `S6 a keyed complete that cascades opens no unit of its own beyond the keyed element unit`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val parent = create("parent", Role.WORK)
            val child = create("child", Role.WORK, parent)

            val r =
                ctx
                    .advanceTool(
                        transitionOp(child.id, "complete", "idempotencyKey" to JsonPrimitive(UUID.randomUUID().toString())),
                        actor = actorJson("agent-cascade")
                    ).onlyResult()

            assertEquals(true, r.flag("ok"), "$r")
            assertEquals(1, r.arr("cascades").size, "control: the parent cascaded: $r")
            assertEquals(Role.TERMINAL, roleOf(parent))
            assertEquals(1, counting.writes, "the keyed element's unit is the only outermost unit; ops=${counting.ops}")
            assertEquals(listOf("advance"), counting.ops, "the element unit is the advance tool's")
            assertFalse("AdvanceService.advance" in counting.ops, "the advance and its cascade JOIN the keyed unit: ${counting.ops}")
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceCascadeUnitCountTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun params(
        id: UUID,
        trigger: String,
        requestId: String? = null,
    ): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", id.toString())
                            put("trigger", trigger)
                            if (requestId != null) {
                                put(
                                    "actor",
                                    buildJsonObject {
                                        put("id", "agent-cascade")
                                        put("kind", "subagent")
                                    },
                                )
                            }
                        },
                    )
                },
            )
            if (requestId != null) put("requestId", JsonPrimitive(requestId))
        }

    private fun JsonElement.entry(): JsonObject = (((this as JsonObject)["data"] as JsonObject).arr("results"))[0].jsonObject

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
            .role

    @Test
    fun `S1 S2 a complete that cascades two ancestors to terminal is still exactly one write unit`(): Unit =
        runBlocking {
            val counting = CountingUnitOfWork(db.unitOfWork())
            val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = counting)
            val grand = create("grand", Role.WORK)
            val parent = create("parent", Role.WORK, grand)
            val child = create("child", Role.WORK, parent)

            val r = AdvanceItemTool().execute(params(child.id, "complete"), ctx).entry()

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals(2, r.arr("cascadeEvents").size, "control: both ancestors cascaded: $r")
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

            val r = AdvanceItemTool().execute(params(child.id, "start"), ctx).entry()

            assertEquals(true, r.flag("applied"), "$r")
            val cascade = r.arr("cascadeEvents").single().jsonObject
            assertEquals(true, cascade.flag("applied"), "control: the start cascade applied: $cascade")
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

            val r = AdvanceItemTool().execute(params(child.id, "complete", requestId = UUID.randomUUID().toString()), ctx).entry()

            assertEquals(true, r.flag("applied"), "$r")
            assertEquals(1, r.arr("cascadeEvents").size, "control: the parent cascaded: $r")
            assertEquals(Role.TERMINAL, roleOf(parent))
            assertEquals(1, counting.writes, "the keyed element's unit is the only outermost unit; ops=${counting.ops}")
            assertFalse("AdvanceService.advance" in counting.ops, "the advance and its cascade JOIN the keyed unit: ${counting.ops}")
        }
}

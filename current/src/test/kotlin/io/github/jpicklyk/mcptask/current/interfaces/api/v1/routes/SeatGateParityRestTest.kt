package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advanceTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemsReadTool
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Independent test (items 79cd4f0c and 2cd17c00) for `missingBySeat` parity, re-pointed at the `advance` transition op: the seat
 * buckets of a gate failure must be the same whether read from the MCP context select, from `POST /ops/items_read` `select: context`, or from the `missing`
 * list of the `gate_blocked` element that `advance` op `transition` answers (`missing[].seat` grouped by seat).
 *
 * Oracles: the fixture schemas below (the seat-owned required notes are written in the config, so the expected buckets are
 * literals), envelope spec section 4 (`gate_blocked.missing[{key, role, seat?}]`) and config-format.md (seats and note `seat`).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SeatGateParityRestTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val token = ParityToken("seat-gate", setOf(ApiCapability.READ, ApiCapability.ADVANCE))

    private companion object {
        val ROOT_ID: UUID = UUID.fromString("a1c00000-0000-4000-8000-000000000000")
        val ITEM_ID: UUID = UUID.fromString("a1c00000-0000-4000-8000-000000000001")

        const val QUEUE_CONFIG = """
work_item_schemas:
  seat-parity:
    seats:
      - { name: planner, phase: queue, enters: true }
    notes:
      - key: diagnosis
        role: queue
        required: true
        seat: planner
      - key: task-scope
        role: queue
        required: true
        seat: planner
"""

        const val WORK_CONFIG = """
work_item_schemas:
  seat-parity-work:
    seats:
      - { name: implementer, phase: work, enters: true }
      - { name: reviewer2, phase: work }
    notes:
      - key: impl-notes
        role: work
        required: true
        seat: implementer
      - key: extra-notes
        role: work
        required: true
        seat: reviewer2
"""
    }

    private fun asMap(obj: JsonObject): Map<String, List<String>> = obj.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

    private fun parityOn(
        dir: Path,
        config: String,
        type: String,
        role: Role,
        expected: Map<String, List<String>>
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, config)
            rig.raw.workItemRepository().create(WorkItem(id = ROOT_ID, title = "Seat parity root", type = "project", depth = 0))
            rig.raw.workItemRepository().create(
                WorkItem(
                    id = ITEM_ID,
                    title = "Seat parity item",
                    type = type,
                    role = role,
                    parentId = ROOT_ID,
                    rootId = ROOT_ID,
                    depth = 1
                )
            )
            application { installParityRestApp(rig, parityAuthConfig(token), ToolRegistry(listOf(advanceTool(), ItemsReadTool.spec))) }

            val context =
                rig
                    .callOk(ItemContextProbe(), "itemId" to JsonPrimitive(ITEM_ID.toString()))
                    .getValue("data")
                    .jsonObject
                    .getValue("gateStatus")
                    .jsonObject
            assertFalse(
                context
                    .getValue("canAdvance")
                    .jsonPrimitive.content
                    .toBoolean(),
                "the gate is blocked"
            )
            val contextBuckets = asMap(context.getValue("missingBySeat").jsonObject)
            assertEquals(expected, contextBuckets, "the buckets named by the fixture schema")

            val gate = client.restContext(ITEM_ID, token.raw)
            assertEquals(HttpStatusCode.OK, gate.status)
            val gateBuckets =
                asMap(
                    gate
                        .bodyAsText()
                        .contextGate()
                        .getValue("missingBySeat")
                        .jsonObject
                )
            assertEquals(contextBuckets, gateBuckets, "the ops context select matches the MCP context select")

            val advance =
                client.post("/api/v1/ops/advance") {
                    header("Authorization", "Bearer ${token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"ops":[{"op":"transition","itemId":"$ITEM_ID","trigger":"start"}]}""")
                }
            val element =
                Json
                    .parseToJsonElement(advance.bodyAsText())
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("results")
                    .jsonArray[0]
                    .jsonObject
            val error = element.getValue("error").jsonObject
            assertEquals("gate_blocked", error.getValue("code").jsonPrimitive.content)
            val missing =
                error
                    .getValue("detail")
                    .jsonObject
                    .getValue("missing")
                    .jsonArray
                    .map { it.jsonObject }
            val advanceBuckets =
                missing.groupBy(
                    { it.getValue("seat").jsonPrimitive.content },
                    { it.getValue("key").jsonPrimitive.content }
                )
            assertEquals(contextBuckets, advanceBuckets, "advance transition missing[].seat matches get_context")
        }

    @Test
    fun `S12 missingBySeat is identical across the MCP and REST context selects and the advance transition for a queue item`(
        @TempDir dir: Path
    ) = parityOn(dir, QUEUE_CONFIG, "seat-parity", Role.QUEUE, mapOf("planner" to listOf("diagnosis", "task-scope")))

    @Test
    fun `N1 missingBySeat is identical across the three surfaces for a work item with two buckets`(
        @TempDir dir: Path
    ) = parityOn(
        dir,
        WORK_CONFIG,
        "seat-parity-work",
        Role.WORK,
        mapOf("implementer" to listOf("impl-notes"), "reviewer2" to listOf("extra-notes"))
    )
}

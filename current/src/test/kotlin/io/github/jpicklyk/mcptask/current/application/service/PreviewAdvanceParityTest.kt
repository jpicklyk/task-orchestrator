package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextStatusTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11MatrixState
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.cleanup
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.setup
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e) for S12 on the MCP surface: `get_context` `gateStatus.canAdvance` and
 * `get_next_status` must agree with what `advance_item(start)` actually does, over generated states covering every
 * gate (role, dependency, current-phase notes, exclusive lease).
 *
 * Oracles: AC5 (canAdvance == "advance(start) would be Allow" on the same DB state), task-scope 1.3 (`blockedBy`
 * names the first failing gate -- table, dependency, note, lease -- and is present only when canAdvance is false and the
 * item is not terminal; get_next_status reports Ready / Blocked with the dependency blockers, `gate_blocked` +
 * `missingNotes`, or `resource_unavailable` + `contendedResources`). The expected value of every state is derived in
 * [P11MatrixState.expectedBlockedBy] from the documented gate order, not from the implementation.
 * Claim ownership is excluded from the preview by definition, so no state carries a claim.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PreviewAdvanceParityTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    private suspend fun P11Driver.gate(item: io.github.jpicklyk.mcptask.current.domain.model.WorkItem): JsonObject {
        val data = rig.call(GetContextTool(), "itemId" to JsonPrimitive(item.id.toString()))["data"]!!.jsonObject
        return data["gateStatus"]!!.jsonObject
    }

    private suspend fun P11Driver.nextStatus(item: io.github.jpicklyk.mcptask.current.domain.model.WorkItem): JsonObject =
        rig.call(GetNextStatusTool(), "itemId" to JsonPrimitive(item.id.toString()))["data"]!!.jsonObject

    @Test
    fun `S12 get_context canAdvance and blockedBy equal the gate that advance start would hit, across every generated state`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val states = P11MatrixState.all()
            assertEquals(60, states.size, "fixture: 5 roles x 3 dependency x 2 note x 2 lease states")
            for (state in states) {
                val (item, holder) = d.setup(state)
                val label = state.label

                val gate = d.gate(item)
                val before = d.role(item)
                val outcome = d.advance(item, "start")

                assertEquals(state.allowed, gate.flag("canAdvance"), "$label: canAdvance must equal the expected gate result: $gate")
                assertEquals(state.allowed, outcome.flag("applied"), "$label: advance(start) must agree with the preview: $outcome")
                val expectedWire = state.expectedBlockedBy.takeIf { it != "terminal" }
                assertEquals(expectedWire, gate.text("blockedBy"), "$label: blockedBy names the first failing gate: $gate")
                if (!state.allowed) assertEquals(before, d.role(item), "$label: a rejected advance changes nothing")
                if (!state.allowed) {
                    val code = outcome.text("errorCode")
                    val expectedCode =
                        mapOf(
                            "terminal" to "invalid_transition",
                            "table" to "invalid_transition",
                            "dependency" to "dependency_blocked",
                            "note" to "gate_blocked",
                            "lease" to "resource_unavailable",
                        )[state.expectedBlockedBy]
                    assertEquals(expectedCode, code, "$label: the advance rejects with the code of the same gate: $outcome")
                }
                d.cleanup(item, holder)
            }
        }

    @Test
    fun `S12 get_next_status reports Ready or the same blocking gate that advance start would hit`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (state in P11MatrixState.all()) {
                val (item, holder) = d.setup(state)
                val label = state.label

                val next = d.nextStatus(item)
                val outcome = d.advance(item, "start")

                when (state.expectedBlockedBy) {
                    "terminal" -> assertEquals("Terminal", next.text("recommendation"), "$label: $next")
                    "table" -> assertEquals("Blocked", next.text("recommendation"), "$label: a blocked item is reported Blocked: $next")
                    null -> {
                        assertEquals("Ready", next.text("recommendation"), "$label: $next")
                        assertEquals("start", next.text("trigger"), "$label: $next")
                        assertEquals(true, outcome.flag("applied"), "$label: Ready agrees with advance: $outcome")
                    }
                    "dependency" -> {
                        assertEquals("Blocked", next.text("recommendation"), "$label: $next")
                        assertTrue(next.arr("blockers").isNotEmpty(), "$label: the dependency blockers are listed: $next")
                    }
                    "note" -> {
                        assertEquals("Blocked", next.text("recommendation"), "$label: $next")
                        assertEquals("gate_blocked", next.text("reason"), "$label: $next")
                        val phaseKey = mapOf(Role.QUEUE to "spec", Role.WORK to "impl", Role.REVIEW to "verdict")[state.role]
                        assertEquals(listOf(phaseKey), next.arr("missingNotes").map { it.jsonPrimitive.content }, "$label: $next")
                    }
                    "lease" -> {
                        assertEquals("Blocked", next.text("recommendation"), "$label: $next")
                        assertEquals("resource_unavailable", next.text("reason"), "$label: $next")
                        assertEquals(
                            listOf(P11_LEASE_KEY),
                            next.arr("contendedResources").map { it.jsonPrimitive.content },
                            "$label: $next"
                        )
                    }
                }
                if (state.expectedBlockedBy != null) assertFalse(outcome.flag("applied") ?: false, "$label: $outcome")
                d.cleanup(item, holder)
            }
        }

    @Test
    fun `S12 a claim held by someone else does not change the preview but does reject the advance`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("claimed", Role.QUEUE)
            d.claim(item, "agent-a")

            val gate = d.gate(item)
            val byOther = d.advance(item, "start", actor = "agent-b")
            val byHolder = d.advance(item, "start", actor = "agent-a")

            assertEquals(true, gate.flag("canAdvance"), "ownership is excluded from canAdvance: $gate")
            assertEquals("not_claim_holder", byOther.text("errorCode"), "$byOther")
            assertEquals(true, byHolder.flag("applied"), "$byHolder")
        }

    @Test
    fun `S12 probe a schema-free item and an item whose schema has no required notes are both allowed`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val free = d.item("schema-free", Role.QUEUE)
            val optional = d.item("optional-only", Role.QUEUE, type = "p11-optional")

            for (item in listOf(free, optional)) {
                val gate = d.gate(item)
                assertEquals(true, gate.flag("canAdvance"), "${item.title}: $gate")
                assertEquals(null, gate.text("blockedBy"), "${item.title}: blockedBy is absent when canAdvance is true: $gate")
                assertEquals("Ready", d.nextStatus(item).text("recommendation"), item.title)
                assertEquals(true, d.advance(item, "start").flag("applied"), item.title)
            }
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.model.run.Run
import io.github.jpicklyk.mcptask.current.model.run.RunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The 4.x legacy adapter of decision 0005 (item 8307148c): the run token and the orchestrator liveness keys take orchestrator
 * names, and the old `coordinator*` keys are returned beside them with the same values until 5.0 (T3 at the view level, T4, T5).
 */
class RunWireKeysTest {
    private val now: Instant = Instant.parse("2026-10-10T12:00:00Z")

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun run(seenAt: Instant?): Run =
        Run(
            rootId = UUID.randomUUID(),
            status = RunStatus.OPEN,
            openedBy = "api:opener",
            openedAt = now.minusSeconds(600),
            orchestratorSeenAt = seenAt,
            stallAfterSeconds = 120
        )

    @Test
    fun `T5 the deprecated aliases are exactly the three coordinator keys`() {
        assertEquals(
            mapOf(
                "orchestratorToken" to "coordinatorToken",
                "orchestratorSeenAt" to "coordinatorSeenAt",
                "orchestratorStalled" to "coordinatorStalled"
            ),
            RunWireKeys.DEPRECATED_ALIASES
        )
    }

    @Test
    fun `T5 the idempotency codec redacts both run token keys and the seat token`() {
        assertEquals(setOf("orchestratorToken", "coordinatorToken", "seatToken"), RunsWriteCodec.TOKEN_KEYS)
        val encoded =
            RunsWriteCodec.encode(obj("""{"runId":"r","orchestratorToken":"t","coordinatorToken":"t"}""")) as JsonObject
        assertEquals(obj("""{"runId":"r","tokenRedacted":true}"""), encoded)
    }

    @Test
    fun `putWithAlias writes the canonical key then its alias, and an unaliased key once`() {
        val built =
            buildJsonObject {
                with(RunWireKeys) {
                    putWithAlias("orchestratorStalled", JsonPrimitive(true))
                    putWithAlias("runId", JsonPrimitive("r"))
                }
            }
        assertEquals(listOf("orchestratorStalled", "coordinatorStalled", "runId"), built.keys.toList())
        assertEquals(JsonPrimitive(true), built["coordinatorStalled"])
    }

    @Test
    fun `T4 decode of a result stored with only the old keys returns both pairs`() {
        val stored = obj("""{"runId":"r","coordinatorSeenAt":"2026-10-10T12:00:00Z","coordinatorStalled":false,"tokenRedacted":true}""")
        val decoded = RunsWriteCodec.decode(stored)
        assertEquals(
            listOf("runId", "orchestratorSeenAt", "coordinatorSeenAt", "orchestratorStalled", "coordinatorStalled", "tokenRedacted"),
            decoded.keys.toList()
        )
        assertEquals(stored.getValue("coordinatorSeenAt"), decoded.getValue("orchestratorSeenAt"))
        assertEquals(stored.getValue("coordinatorStalled"), decoded.getValue("orchestratorStalled"))
    }

    @Test
    fun `T4 decode leaves a result that carries both pairs, or none, unchanged`() {
        val both =
            obj(
                """{"orchestratorSeenAt":"a","coordinatorSeenAt":"a","orchestratorStalled":true,"coordinatorStalled":true}"""
            )
        assertSame(both, RunsWriteCodec.decode(both))
        val none = obj("""{"decisionId":"d","status":"open"}""")
        assertSame(none, RunsWriteCodec.decode(none))
    }

    @Test
    fun `T3 the run view carries both stalled keys and both seen keys with equal values`() {
        val seen = now.minusSeconds(121)
        val view = RunView.of(run(seen), members = 1, seats = emptyList(), now = now)
        assertEquals(JsonPrimitive(seen.toString()), view["orchestratorSeenAt"])
        assertEquals(view["orchestratorSeenAt"], view["coordinatorSeenAt"])
        assertEquals(JsonPrimitive(true), view["orchestratorStalled"], "seen 121 s ago under a 120 s threshold")
        assertEquals(view["orchestratorStalled"], view["coordinatorStalled"])
    }

    @Test
    fun `T3 a run never seen has neither seen key, and both stalled keys`() {
        val view = RunView.of(run(null), members = 1, seats = emptyList(), now = now)
        assertFalse("orchestratorSeenAt" in view, "absent, never null: $view")
        assertFalse("coordinatorSeenAt" in view, "the alias is absent with it: $view")
        assertTrue("orchestratorStalled" in view && "coordinatorStalled" in view, view.toString())
        assertEquals(view["orchestratorStalled"], view["coordinatorStalled"])
    }
}

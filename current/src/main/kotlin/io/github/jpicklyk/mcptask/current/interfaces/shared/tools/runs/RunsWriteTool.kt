package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val RUNS_WRITE = "runs_write"

private object JsonObjectCodec : IdempotencyCodec<JsonObject> {
    override fun encode(value: JsonObject): JsonElement = value

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

/**
 * The runs_write tool: open, update, close, pause, resume and mark runs, and assign and end seats. Every op needs the
 * runs capability. mark is REST-only (it is on no MCP profile); pause and resume are director ops; update, assign and
 * end_seat are orchestration ops.
 */
fun runsWriteTool(): OpsToolSpec<JsonObject> =
    OpsToolSpec(
        name = RUNS_WRITE,
        description =
            "Open, update, close, pause, resume and mark runs; assign and end seats. Each op needs the runs capability and " +
                "a run whose root is in scope. close: coordinator, director or a human; update, assign: coordinator " +
                "token; end_seat: the seat's own token; pause and resume: director or human only; mark: REST only. " +
                "Result: the run (close adds snapshot, mark adds marked).",
        ops = listOf(runsOpenOp, runsUpdateOp, runsCloseOp, runsPauseOp, runsResumeOp, runsMarkOp, runsAssignOp, runsEndSeatOp),
        codec = JsonObjectCodec,
        element = { it }
    )

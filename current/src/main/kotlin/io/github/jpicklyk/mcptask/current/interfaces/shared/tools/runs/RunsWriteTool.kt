package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val RUNS_WRITE = "runs_write"

/**
 * The runs_write idempotency codec. A token is shown once: [encode] drops a `coordinatorToken` (open) or `seatToken`
 * (assign) from the result before it is stored and marks it `tokenRedacted: true`, so the idempotency store never
 * holds a credential and a keyed replay returns the result without the token. Recovery: re-assign the seat (it
 * supersedes the assignment and issues a fresh token) or, for the coordinator token, close the run and open a new one.
 * The first, live response is not encoded and carries the token.
 */
internal object RunsWriteCodec : IdempotencyCodec<JsonObject> {
    /** The result keys that carry a token. */
    val TOKEN_KEYS: Set<String> = setOf("coordinatorToken", "seatToken")

    const val TOKEN_REDACTED = "tokenRedacted"

    override fun encode(value: JsonObject): JsonElement {
        if (TOKEN_KEYS.none { it in value }) return value
        return JsonObject(value.filterKeys { it !in TOKEN_KEYS } + (TOKEN_REDACTED to JsonPrimitive(true)))
    }

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

/**
 * The runs_write tool: open, update, close, pause, resume and mark runs, assign and end seats, and raise, acknowledge,
 * answer and withdraw decisions. Every op needs the runs capability except ack and answer, which need decide. mark is
 * REST-only (it is on no MCP profile); pause, resume, ack, answer and withdraw are director ops; update, assign,
 * end_seat and raise are orchestration ops.
 */
fun runsWriteTool(): OpsToolSpec<JsonObject> =
    OpsToolSpec(
        name = RUNS_WRITE,
        description =
            "Open, update, close, pause, resume and mark runs; assign and end seats; raise, ack, answer and withdraw " +
                "decisions. Each op needs the runs capability (ack, answer: decide) and a root in scope. close: " +
                "coordinator, director or a human; update, assign: coordinator token; end_seat: the seat's own token; " +
                "pause and resume: director or human only; mark: REST only. Result: the run (close adds snapshot, mark " +
                "adds marked) or the decision.",
        ops =
            listOf(
                runsOpenOp,
                runsUpdateOp,
                runsCloseOp,
                runsPauseOp,
                runsResumeOp,
                runsMarkOp,
                runsAssignOp,
                runsEndSeatOp,
                runsRaiseOp,
                runsAckOp,
                runsAnswerOp,
                runsWithdrawOp
            ),
        codec = RunsWriteCodec,
        element = { it }
    )

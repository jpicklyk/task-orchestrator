package io.github.jpicklyk.mcptask.current.model.run

import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail

/** The `run_paused` refusal for a write against this paused run (an unset pause reason reads as manual). */
fun Run.pausedError(): DomainError {
    val reason = pauseReason?.wire ?: RunPauseReason.MANUAL.wire
    return DomainError(
        code = ErrorCode.RUN_PAUSED,
        message = "Run $id is paused ($reason)",
        detail = ErrorDetail.RunPaused(id, reason, pauseDecisionId),
        fixArgs = mapOf("runId" to id.toString(), "pauseReason" to reason)
    )
}

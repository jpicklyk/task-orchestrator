package io.github.jpicklyk.mcptask.current.telemetry

import java.util.UUID

/**
 * The one write the call-log writer makes against runs: it adds the token estimates of the rows it inserted to the run's
 * `observed_tokens` counter (R27). The run store implements it.
 */
fun interface ObservedRunTokens {
    /**
     * Adds [tokens] to `observed_tokens` of [runId] WITHOUT bumping the version (telemetry is not a change a coordinator's
     * `expectedVersion` should conflict with). A missing run is a no-op; a closed run still adds.
     */
    suspend fun addObservedTokens(
        runId: UUID,
        tokens: Long
    )
}

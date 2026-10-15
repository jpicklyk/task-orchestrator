package io.github.jpicklyk.mcptask.current.application.run

import java.util.UUID

/**
 * The run rollup snapshot a close stores in `run.ended` (plan R28). [RunService.close] calls it inside the close unit,
 * after the run row is stored closed and the close hook ran, and before `run.ended` is recorded, for EVERY terminal
 * reason. It answers the stored form of the rollup (the report codec's JSON object, full ids), or null to store none.
 * A throw rolls the close back, as the close hook's does. [NONE] stores nothing (a run service built without reports, in
 * tests); production wires the report-backed one once, in `ServerComposition`.
 */
fun interface RunRollupSnapshot {
    /** The stored form of the rollup of run [runId], read inside the caller's (close) unit; null for none. */
    suspend fun snapshot(runId: UUID): Map<String, Any?>?

    companion object {
        /** No snapshot: `run.ended` carries no `rollup`. */
        val NONE: RunRollupSnapshot = RunRollupSnapshot { null }
    }
}

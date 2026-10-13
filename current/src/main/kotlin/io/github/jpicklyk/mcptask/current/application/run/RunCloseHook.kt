package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.domain.model.Run

/**
 * Called once per run close, inside the close's unit, after the cascade and before `run.ended` is recorded. [run] is
 * the closed run as stored. A thrown exception rolls the whole close back. [NONE] does nothing (the default until a
 * later item supplies a hook).
 */
fun interface RunCloseHook {
    suspend fun onClose(
        run: Run,
        snapshot: RunEndedSnapshot
    )

    companion object {
        val NONE: RunCloseHook = RunCloseHook { _, _ -> }
    }
}

package io.github.jpicklyk.mcptask.current.application.upgrade

import io.github.jpicklyk.mcptask.current.application.port.WriteScope

/**
 * The fixed phases of the data-step runner (plan section 3.7). Declaration order IS run order. Later work items
 * register steps into these phases; they never add a phase.
 */
enum class DataStepPhase {
    CONFIG_CANONICALIZE,
    CONFIG_IMPORT,
    ITEM_BACKFILL,
    PIN
}

/** [ONCE] steps are recorded in `data_steps` and skipped once applied; [EVERY_BOOT] steps run on every boot and are never recorded. */
enum class DataStepKind {
    ONCE,
    EVERY_BOOT
}

/**
 * One idempotent upgrade step run by [DataStepRunner] after Flyway and before the server serves.
 *
 * [name] matches `^[a-z][a-z0-9-]{0,63}$` and is unique across all registered steps. [after] names steps of the SAME
 * [phase] that must run first. [run] works inside the step's own write unit and returns the rows it affected (>= 0).
 */
interface DataStep {
    val name: String
    val phase: DataStepPhase
    val kind: DataStepKind
    val after: Set<String> get() = emptySet()

    suspend fun run(scope: WriteScope): Int
}

/**
 * Thrown by [DataStepRunner.plan] for a bad step graph and by [DataStepRunner.run] for a step failure, a recording
 * failure or an unapplied once step after the run. Startup maps it to a failed start (readiness is never reported).
 */
class DataStepException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

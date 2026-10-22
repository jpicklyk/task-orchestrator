package io.github.jpicklyk.mcptask.current.application.upgrade

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DataStepStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import org.slf4j.LoggerFactory

/** What one [DataStepRunner.run] did: once steps applied now, once steps skipped as already applied, every-boot steps run. */
data class DataStepReport(
    val applied: List<String>,
    val skipped: List<String>,
    val ranEveryBoot: List<String>
)

/**
 * Runs the registered [DataStep]s after Flyway and before the server serves (plan section 3.7).
 *
 * Order: phases in [DataStepPhase] order; within a phase a topological order of `after`, ties broken by name, so the
 * order never depends on registration order. A once step and its `data_steps` row commit in ONE write unit whose
 * first act is the applied check; the writer transaction is IMMEDIATE, so two processes booting on one file
 * serialize and the second observes the row. A failure stops the run at that step. After the last step the runner
 * re-reads the ledger and refuses if any registered once step has no row.
 */
class DataStepRunner(
    private val steps: List<DataStep>,
    private val unitOfWork: UnitOfWork,
    private val store: DataStepStore,
    @Suppress("unused") private val clock: Clock,
    private val binaryVersion: String
) {
    private val logger = LoggerFactory.getLogger(DataStepRunner::class.java)

    /** The validated run order. Pure (no database); throws [DataStepException] naming the offending step. */
    fun plan(): List<DataStep> {
        val byName = HashMap<String, DataStep>()
        for (s in steps) {
            if (!NAME.matches(s.name)) {
                throw DataStepException("data step '${s.name}' has an invalid name (expected ${NAME.pattern})")
            }
            if (byName.put(s.name, s) != null) {
                throw DataStepException("data step '${s.name}' is registered more than once")
            }
        }
        for (s in steps) {
            for (dep in s.after) {
                val target = byName[dep] ?: throw DataStepException("data step '${s.name}' runs after unknown step '$dep'")
                if (target.phase != s.phase) {
                    throw DataStepException(
                        "data step '${s.name}' (${s.phase}) runs after '$dep' (${target.phase}): after is within a phase"
                    )
                }
            }
        }
        val ordered = ArrayList<DataStep>(steps.size)
        for (phase in DataStepPhase.entries) {
            val pending = steps.filter { it.phase == phase }.associateBy { it.name }.toMutableMap()
            val done = HashSet<String>()
            while (pending.isNotEmpty()) {
                val next =
                    pending.values
                        .filter { s -> s.after.all { it in done } }
                        .minByOrNull { it.name }
                        ?: throw DataStepException(
                            "data steps form a cycle in phase $phase: ${pending.keys.sorted().joinToString(", ")}"
                        )
                ordered.add(next)
                done.add(next.name)
                pending.remove(next.name)
            }
        }
        return ordered
    }

    suspend fun run(): DataStepReport {
        val order = plan()
        val applied = ArrayList<String>()
        val skipped = ArrayList<String>()
        val everyBoot = ArrayList<String>()
        for (step in order) {
            when (step.kind) {
                DataStepKind.ONCE -> if (runOnce(step)) applied.add(step.name) else skipped.add(step.name)
                DataStepKind.EVERY_BOOT -> {
                    runEveryBoot(step)
                    everyBoot.add(step.name)
                }
            }
        }
        val missing =
            try {
                val recorded = store.appliedNames()
                order.filter { it.kind == DataStepKind.ONCE && it.name !in recorded }.map { it.name }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                throw DataStepException("data step ledger could not be read: ${e.message}", e)
            }
        if (missing.isNotEmpty()) {
            throw DataStepException("data steps not applied after the run: ${missing.joinToString(", ")}")
        }
        return DataStepReport(applied, skipped, everyBoot)
    }

    /** Returns true when the step ran now, false when it was already applied. */
    private suspend fun runOnce(step: DataStep): Boolean {
        val result =
            try {
                unitOfWork.write("data-step:${step.name}") {
                    if (step.name in store.appliedNames()) {
                        Outcome.Ok(false)
                    } else {
                        val rows = step.run(this)
                        check(rows >= 0) { "returned negative rows affected: $rows" }
                        store.record(step.name, now, rows, binaryVersion)
                        logger.info("Data step {} applied ({} rows)", step.name, rows)
                        Outcome.Ok(true)
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                throw DataStepException("data step ${step.name} failed: ${e.message}", e)
            }
        return when (result) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> throw DataStepException("data step ${step.name} failed: ${result.error.message}")
        }
    }

    private suspend fun runEveryBoot(step: DataStep) {
        val result =
            try {
                unitOfWork.write("data-step:${step.name}") {
                    val rows = step.run(this)
                    logger.info("Data step {} ran ({} rows)", step.name, rows)
                    Outcome.Ok(rows)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                throw DataStepException("data step ${step.name} failed: ${e.message}", e)
            }
        if (result is Outcome.Err) throw DataStepException("data step ${step.name} failed: ${result.error.message}")
    }

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.RetentionPruner
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Advance latency while a retention pass runs (item 0da29ea1, plan "Bench: advance p95 at 1 and 8 agents during a 100k-row
 * pass"). Platform-gated like the other benches: runs only with TO_PERF=1 (the gate is the repo's bench convention, not a
 * guard on a behaviour under test).
 *
 * For each of 1 and 8 agents it seeds 100,000 ancient activity events (a raw JDBC batch), runs a baseline advance round with no
 * pass, seeds again and runs the same advance round while one `pruneOnce()` is deleting the 100k rows, and prints one
 * `PERF_RESULT` line per configuration with the p95 of each round and how many advances overlapped the pass. The only
 * assertions are that the pass deleted exactly the seeded rows and that every advance applied; the p95 numbers are for the
 * item's performance review to read, not asserted.
 */
@Tag("serial")
class RetentionPruneBench {
    @Test
    fun advanceLatencyDuringPrunePass() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        runConfiguration(agents = 2, label = "warmup", report = false)
        runConfiguration(agents = 1, label = "agents1", report = true)
        runConfiguration(agents = 8, label = "agents8", report = true)
    }

    private fun runConfiguration(
        agents: Int,
        label: String,
        report: Boolean
    ) {
        SqliteTestDatabase.open().use { db ->
            val context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val seeded: List<List<UUID>> = runBlocking { seed(context, agents) }
            val executor = Executors.newFixedThreadPool(agents + 1)
            try {
                val dispatcher = executor.asCoroutineDispatcher()
                val baseline = runBlocking { advanceRound(context, seeded.map { it.take(HALF) }, dispatcher, pruneActive = null) }

                RetentionRows.insertEventsBatch(
                    db.jdbcUrl,
                    EventStore.SEQ_FLOOR + 1_000_000,
                    PRUNE_ROWS,
                    DomainEvent.NOTE_UPSERTED,
                    Instant.now().minusSeconds(400L * 86_400)
                )
                // sseBufferSize + 1 recent rows above the ancient ones (the floor keeps seq >= maxSeq - buffer, i.e. buffer + 1 rows), so exactly PRUNE_ROWS are prunable
                RetentionRows.insertEventsBatch(
                    db.jdbcUrl,
                    EventStore.SEQ_FLOOR + 1_000_000 + PRUNE_ROWS,
                    RECENT_ROWS,
                    DomainEvent.NOTE_UPSERTED,
                    Instant.now()
                )
                val pruner =
                    RetentionPruner(
                        unitOfWork = db.unitOfWork(),
                        settings = RetentionSettings(callLogDays = 0, eventsDays = 1, auditEventsDays = 0, leaseHistoryDays = 0),
                        sseBufferSize = RECENT_ROWS - 1
                    )
                val active = AtomicBoolean(true)
                val during =
                    runBlocking {
                        val passJob =
                            async(dispatcher) {
                                try {
                                    pruner.pruneOnce()
                                } finally {
                                    active.set(false)
                                }
                            }
                        val round = advanceRound(context, seeded.map { it.drop(HALF) }, dispatcher, pruneActive = active)
                        val pass = passJob.await()
                        assertEquals(PRUNE_ROWS, pass.events, "the pass must delete exactly the 100k seeded ancient rows")
                        round
                    }
                assertEquals(0, baseline.failed + during.failed, "every advance must apply")

                if (report) {
                    println(
                        "PERF_RESULT config=retention-$label agents=$agents prune_rows=$PRUNE_ROWS " +
                            "baseline_p95_ms=${fmt(baseline.p95Ms())} baseline_n=${baseline.latenciesNs.size} " +
                            "during_pass_p95_ms=${fmt(during.p95Ms())} during_n=${during.latenciesNs.size} " +
                            "overlapped_advances=${during.overlapped}"
                    )
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private class Round(
        val latenciesNs: List<Long>,
        val failed: Int,
        val overlapped: Int
    ) {
        fun p95Ms(): Double {
            val sorted = latenciesNs.sorted()
            return sorted[minOf(sorted.size - 1, kotlin.math.ceil(0.95 * sorted.size).toInt() - 1)] / 1_000_000.0
        }
    }

    private suspend fun advanceRound(
        context: ToolExecutionContext,
        itemsPerAgent: List<List<UUID>>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        pruneActive: AtomicBoolean?
    ): Round {
        val latencies = Collections.synchronizedList(mutableListOf<Long>())
        val failed =
            java.util.concurrent.atomic
                .AtomicInteger()
        val overlapped =
            java.util.concurrent.atomic
                .AtomicInteger()
        kotlinx.coroutines.coroutineScope {
            itemsPerAgent
                .map { items ->
                    async(dispatcher) {
                        for (id in items) {
                            val during = pruneActive?.get() == true
                            val t0 = System.nanoTime()
                            val result = context.advanceTool(transitionOp(id, "start"))
                            latencies.add(System.nanoTime() - t0)
                            if (during) overlapped.incrementAndGet()
                            if ("\"ok\":true" !in result.toString().replace(" ", "")) failed.incrementAndGet()
                        }
                    }
                }.awaitAll()
        }
        return Round(latencies.toList(), failed.get(), overlapped.get())
    }

    /** One root per agent with 2 x [HALF] queue children: the first half for the baseline round, the second for the pass round. */
    private suspend fun seed(
        context: ToolExecutionContext,
        agents: Int
    ): List<List<UUID>> {
        val repo = context.workItemRepository()
        return (0 until agents).map { r ->
            val rootId = UUID.randomUUID()
            repo.create(WorkItem(id = rootId, rootId = rootId, title = "bench-root-$r", role = Role.QUEUE, depth = 0))
            (0 until 2 * HALF).map { c ->
                val child = repo.create(WorkItem(parentId = rootId, rootId = rootId, title = "bench-$r-$c", role = Role.QUEUE, depth = 1))
                child.id
            }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val PRUNE_ROWS = 100_000
        const val HALF = 50
        const val RECENT_ROWS = 1_001
    }
}

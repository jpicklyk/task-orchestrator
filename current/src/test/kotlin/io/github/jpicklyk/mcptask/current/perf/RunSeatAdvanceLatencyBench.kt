package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Seat-token advance latency benchmark (item f65fef01, R27 N3 measurement; instrumentation, no assertions). Platform-gated:
 * runs only with TO_PERF=1.
 *
 * Drives a seat-token `advance` `transition` start and `heartbeat` through the production dispatcher over the real
 * composition (the `SeatWorld` rig: one in-memory MCP client/server pair), on a run with no budget ("unlimited") and on a run
 * with a token budget far above anything used ("limited", so the check runs and never breaches), with 1 and 8 agents, and
 * prints one `PERF_RESULT` line per configuration and operation. Run it before and after the change under test; the
 * acceptance is p50 <= 1.05x and p95 <= 1.15x of the before figures.
 */
@Tag("serial")
class RunSeatAdvanceLatencyBench {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    @Test
    fun seatAdvanceLatency() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        runBlocking {
            val world = SeatWorld.create(db.db, dir, clock)
            try {
                // Warm-up (JIT, class loading); not reported.
                configuration(world, limited = false, agents = 2, perAgent = 20, label = "warmup", report = false)
                for (limited in listOf(false, true)) {
                    for (agents in listOf(1, 8)) {
                        val label = (if (limited) "limited" else "unlimited") + "-agents$agents"
                        configuration(world, limited, agents, PER_AGENT, label, report = true)
                    }
                }
            } finally {
                world.shutdown()
            }
        }
    }

    private suspend fun configuration(
        world: SeatWorld,
        limited: Boolean,
        agents: Int,
        perAgent: Int,
        label: String,
        report: Boolean
    ) {
        val items: List<WorkItem> =
            (0 until agents * perAgent).map { world.rig.seed("bench-$label-$it", parent = world.root, tags = "seated-task") }
        val budget = if (limited) ""","budget":{"tokens":1000000000}""" else ""
        val run = world.open(members = items, extra = budget)
        val tokens = items.map { item -> item to world.assignOk(run, item, "builder").token!! }
        for ((item, token) in tokens) check(world.claim(item, token).ok()) { "claim failed for ${item.id}" }

        val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val beats = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val executor = Executors.newFixedThreadPool(agents)
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            coroutineScope {
                (0 until agents)
                    .map { agent ->
                        async(dispatcher) {
                            for ((item, token) in tokens.subList(agent * perAgent, (agent + 1) * perAgent)) {
                                val t0 = System.nanoTime()
                                val started =
                                    world.ops(
                                        "advance",
                                        """{"op":"transition","itemId":"${item.id}","trigger":"start"}""",
                                        token = token
                                    )
                                starts.add(System.nanoTime() - t0)
                                check(started.ok()) { "start failed: $started" }
                                repeat(HEARTBEATS) {
                                    val h0 = System.nanoTime()
                                    val beat = world.heartbeat(item, token)
                                    beats.add(System.nanoTime() - h0)
                                    check(beat.ok()) { "heartbeat failed: $beat" }
                                }
                            }
                        }
                    }.awaitAll()
            }
        } finally {
            executor.shutdownNow()
        }
        if (report) {
            println(line(label, "start", agents, starts))
            println(line(label, "heartbeat", agents, beats))
        }
    }

    private fun line(
        label: String,
        op: String,
        agents: Int,
        samples: List<Long>
    ): String {
        val sorted = samples.sorted()

        fun pct(p: Double): Double = sorted[minOf(sorted.size - 1, ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0
        return "PERF_RESULT config=$label op=$op agents=$agents n=${sorted.size} " +
            "p50_ms=${"%.3f".format(pct(0.50))} p95_ms=${"%.3f".format(pct(0.95))} mean_ms=${"%.3f".format(sorted.average() / 1_000_000.0)}"
    }

    private companion object {
        const val PER_AGENT = 40
        const val HEARTBEATS = 3
    }
}

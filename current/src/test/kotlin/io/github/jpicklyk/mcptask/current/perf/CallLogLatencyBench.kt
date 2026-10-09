package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.CallLogStore
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Call-log overhead benchmark for item 8abb69e2 (P10), measurement plan of the frozen performance-baseline note
 * (section 4). Platform-gated: runs only with TO_PERF=1, like [AdvanceLatencyBench].
 *
 * 1. Advance latency on the adapter path: `advance_item(start)` through the in-memory MCP client/server pair with 1 and
 *    8 concurrent agents, 50 advances each on distinct items (8 roots x 50 queue children). Run A uses
 *    `CallLogSink.NONE`, run B a started [CallLogWriter] on the same database. Three interleaved runs per
 *    configuration; one `PERF_RESULT` line per run and one `PERF_RATIO` line with the median ratios.
 *    Accept bar (reported as `PERF_ACCEPT` lines, soft): B/A <= 1.10 at p50, <= 1.15 at p95 with 1 agent, <= 1.20 at
 *    p95 with 8 agents. Hard failures are the P5a limits: p50 1.2, p95 1.5 (1 agent) and 2.0 (8 agents).
 * 2. Writer throughput: 50_000 records from 8 coroutines into a real table. Accept: written == 50_000, dropped == 0
 *    within 30 s, every append <= 100 rows, at least 5_000 rows/s. (The queue is sized to 100_000 so the producers'
 *    unthrottled burst does not overflow the default 10_000; the oracle is "written == dropped-free", not the default
 *    capacity.)
 * 3. submit cost: 1_000_000 submits into a queue drained by a no-op store, p99 < 20 us.
 * 4. The direct-tool path is covered by the existing [AdvanceLatencyBench] (re-run before and after; expect <= 1.05x p50).
 */
@Tag("serial")
class CallLogLatencyBench {
    @Test
    fun callLogOverhead() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        runOnce(agents = 2, perAgent = 20, withWriter = false, label = "warmup", report = false)
        runOnce(agents = 2, perAgent = 20, withWriter = true, label = "warmup", report = false)
        for (agents in listOf(1, 8)) {
            val p50Ratios = mutableListOf<Double>()
            val p95Ratios = mutableListOf<Double>()
            repeat(RUNS) { run ->
                val a = runOnce(agents, ADVANCES, withWriter = false, label = "A-none-run$run", report = true)
                val b = runOnce(agents, ADVANCES, withWriter = true, label = "B-writer-run$run", report = true)
                p50Ratios += b.p50 / a.p50
                p95Ratios += b.p95 / a.p95
            }
            val p50 = p50Ratios.sorted()[RUNS / 2]
            val p95 = p95Ratios.sorted()[RUNS / 2]
            println("PERF_RATIO agents=$agents median_p50_ratio=${fmt(p50)} median_p95_ratio=${fmt(p95)}")
            val p95Accept = if (agents == 1) 1.15 else 1.20
            val p95Hard = if (agents == 1) 1.5 else 2.0
            println("PERF_ACCEPT agents=$agents p50_within_1_10=${p50 <= 1.10} p95_within_${fmt(p95Accept)}=${p95 <= p95Accept}")
            check(p50 <= 1.2) { "agents=$agents: median p50 ratio $p50 exceeds the P5a hard limit 1.2" }
            check(p95 <= p95Hard) { "agents=$agents: median p95 ratio $p95 exceeds the P5a hard limit $p95Hard" }
        }
    }

    @Test
    fun writerThroughput() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val batchSizes = CopyOnWriteArrayList<Int>()
            val real = SqliteCallLogStore(db.databaseManager)
            val counting =
                object : CallLogStore {
                    override suspend fun append(records: List<CallLogRecord>): Int {
                        batchSizes += records.size
                        return real.append(records)
                    }
                }
            val writer = CallLogWriter(db.unitOfWork(), counting, capacity = 100_000)
            writer.start()
            val total = 50_000
            val startNs = System.nanoTime()
            runBlocking {
                (0 until 8)
                    .map { agent ->
                        async(Dispatchers.Default) {
                            repeat(total / 8) { n ->
                                writer.submit(
                                    sampleCallLogRecord(
                                        "w" + agent + (n + 100_000).toString().takeLast(5) + "a"
                                    )
                                )
                            }
                        }
                    }.awaitAll()
                withTimeout(30_000) { while (writer.written + writer.dropped + writer.failed < total) delay(10) }
                writer.stop()
            }
            val seconds = (System.nanoTime() - startNs) / 1e9
            val rate = fmt(total / seconds)
            println(
                "PERF_RESULT config=writer-throughput rows=$total written=${writer.written} dropped=${writer.dropped} " +
                    "failed=${writer.failed} rows_per_s=$rate max_batch=${batchSizes.maxOrNull()}"
            )
            check(writer.written == total.toLong()) { "written=${writer.written}" }
            check(writer.dropped == 0L) { "dropped=${writer.dropped}" }
            check(batchSizes.all { it <= 100 }) { "an append exceeded 100 rows: ${batchSizes.maxOrNull()}" }
            check(total / seconds >= 5_000) { "throughput ${total / seconds} rows/s is below the 5000 rows/s expectation" }
            check(CallLogRows.count(db.jdbcUrl) == total) { "rows in table: ${CallLogRows.count(db.jdbcUrl)}" }
        }
    }

    @Test
    fun submitCost() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val drained = AtomicLong()
            val noop =
                object : CallLogStore {
                    override suspend fun append(records: List<CallLogRecord>): Int {
                        drained.addAndGet(records.size.toLong())
                        return records.size
                    }
                }
            val writer = CallLogWriter(db.unitOfWork(), noop, capacity = 100_000, flushInterval = 5.milliseconds)
            writer.start()
            val sink: CallLogSink = writer
            val record = sampleCallLogRecord("submit01")
            repeat(50_000) { sink.submit(record) }
            val samples = LongArray(1_000_000)
            for (i in samples.indices) {
                val t0 = System.nanoTime()
                sink.submit(record)
                samples[i] = System.nanoTime() - t0
            }
            runBlocking { writer.stop() }
            samples.sort()
            val p99us = samples[(samples.size * 0.99).toInt()] / 1_000.0
            println(
                "PERF_RESULT config=submit-cost n=${samples.size} p50_us=${fmt(
                    samples[samples.size / 2] / 1_000.0
                )} p99_us=${fmt(p99us)} dropped=${writer.dropped}"
            )
            check(p99us < 20.0) { "submit p99 ${p99us}us exceeds 20us" }
        }
    }

    private class Stats(
        val p50: Double,
        val p95: Double
    )

    private fun runOnce(
        agents: Int,
        perAgent: Int,
        withWriter: Boolean,
        label: String,
        report: Boolean
    ): Stats {
        SqliteTestDatabase.open().use { db ->
            val context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val seeded: List<List<UUID>> = runBlocking { seed(context) }
            val writer =
                if (withWriter) {
                    CallLogWriter(
                        db.unitOfWork(),
                        SqliteCallLogStore(db.databaseManager)
                    ).also { it.start() }
                } else {
                    null
                }
            val adapter = McpToolAdapter(callLog = writer ?: CallLogSink.NONE)
            val latenciesNs = java.util.Collections.synchronizedList(mutableListOf<Long>())
            var failed = 0
            runBlocking {
                val server =
                    Server(serverInfo = Implementation(name = "p10-bench", version = "1.0.0"), options = inMemoryTestServerOptions())
                adapter.registerToolsWithServer(server, buildMcpTools(), context)
                val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
                val client =
                    Client(
                        clientInfo = Implementation(name = "p10-bench-client", version = "1.0.0"),
                        options = ClientOptions(capabilities = ClientCapabilities())
                    )
                server.createSession(serverTransport)
                client.connect(clientTransport)
                try {
                    (0 until agents)
                        .map { agent ->
                            async(Dispatchers.Default) {
                                var localFailed = 0
                                for (itemId in seeded[agent].take(perAgent)) {
                                    val request =
                                        buildCallToolRequest {
                                            name = "advance_item"
                                            arguments(
                                                JsonObject(
                                                    mapOf(
                                                        "transitions" to
                                                            JsonArray(
                                                                listOf(
                                                                    JsonObject(
                                                                        mapOf(
                                                                            "itemId" to JsonPrimitive(itemId.toString()),
                                                                            "trigger" to JsonPrimitive("start")
                                                                        )
                                                                    )
                                                                )
                                                            )
                                                    )
                                                )
                                            )
                                        }
                                    val t0 = System.nanoTime()
                                    val result = client.callTool(request)
                                    latenciesNs.add(System.nanoTime() - t0)
                                    if (result.isError == true) localFailed++
                                }
                                localFailed
                            }
                        }.awaitAll()
                        .forEach { failed += it }
                } finally {
                    writer?.stop()
                    closeInMemoryPair(client, server)
                }
            }
            val sorted = latenciesNs.sorted()

            fun pct(p: Double): Double = sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0
            val stats = Stats(pct(0.50), pct(0.95))
            if (report) {
                println(
                    "PERF_RESULT config=$label agents=$agents n=${sorted.size} p50_ms=${fmt(stats.p50)} p95_ms=${fmt(pct(0.95))} " +
                        "p99_ms=${fmt(pct(0.99))} failed=$failed written=${writer?.written ?: "na"} dropped=${writer?.dropped ?: "na"}"
                )
            }
            return stats
        }
    }

    private suspend fun seed(context: ToolExecutionContext): List<List<UUID>> {
        val repo = context.workItemRepository()
        return (0 until ROOTS).map { r ->
            val rootId = UUID.randomUUID()
            check(repo.create(WorkItem(id = rootId, rootId = rootId, title = "bench-root-$r", role = Role.QUEUE, depth = 0)) != null) {
                "seed root $r failed"
            }
            (0 until ADVANCES).map { c ->
                val created = repo.create(WorkItem(parentId = rootId, rootId = rootId, title = "bench-$r-$c", role = Role.QUEUE, depth = 1))
                check(created != null) { "seed child $r/$c failed" }
                created.id
            }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val ROOTS = 8
        const val ADVANCES = 50
        const val RUNS = 3
    }
}

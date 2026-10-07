package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Advance latency benchmark (P5a performance-baseline). Platform-gated: runs only with TO_PERF=1.
 *
 * Seeds a file-backed SQLite fixture with 8 roots x 50 queue children (no notes-gated schema), then
 * drives `advance_item(start)` through [AdvanceItemTool.execute] (the MCP code path) with 1 and 8
 * concurrent agents, 50 advances each on distinct items, and prints one `PERF_RESULT` line per
 * configuration. Uses only APIs present both before and after the unit-of-work/pool change, so the
 * same file compiles on either base. Pool statistics are read reflectively and report `na` where the
 * server has no Hikari pools.
 */
class AdvanceLatencyBench {
    @Test
    fun advanceLatency() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        // Warm-up (JIT, class loading, template DB) on a throwaway database; not reported.
        runConfiguration(agents = 2, perAgent = 20, label = "warmup", report = false)
        runConfiguration(agents = 1, perAgent = ADVANCES, label = "agents1", report = true)
        runConfiguration(agents = 8, perAgent = ADVANCES, label = "agents8", report = true)
    }

    private fun runConfiguration(
        agents: Int,
        perAgent: Int,
        label: String,
        report: Boolean
    ) {
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            val context = ToolExecutionContext(provider)
            val tool = AdvanceItemTool()
            val seeded: List<List<UUID>> = runBlocking { seed(context) }

            val sampler = PoolSampler(db.databaseManager)
            sampler.start()
            val failed = AtomicInteger()
            val unavailable = AtomicInteger()
            val latenciesNs = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val executor = Executors.newFixedThreadPool(agents)
            val wallNs = AtomicLong()
            try {
                val dispatcher = executor.asCoroutineDispatcher()
                val start = System.nanoTime()
                runBlocking {
                    (0 until agents)
                        .map { agent ->
                            async(dispatcher) {
                                for (itemId in seeded[agent].take(perAgent)) {
                                    val params = advanceParams(itemId)
                                    val t0 = System.nanoTime()
                                    val result = tool.execute(params, context)
                                    val dt = System.nanoTime() - t0
                                    latenciesNs.add(dt)
                                    val outcome = classify(result)
                                    if (outcome != Outcome.APPLIED) failed.incrementAndGet()
                                    if (outcome == Outcome.UNAVAILABLE) unavailable.incrementAndGet()
                                }
                            }
                        }.awaitAll()
                }
                wallNs.set(System.nanoTime() - start)
            } finally {
                executor.shutdownNow()
                sampler.stop()
            }

            if (report) {
                val sorted = latenciesNs.sorted()
                fun pct(p: Double): Double = sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0
                val mean = sorted.average() / 1_000_000.0
                println(
                    "PERF_RESULT config=$label agents=$agents n=${sorted.size} " +
                        "p50_ms=${fmt(pct(0.50))} p95_ms=${fmt(pct(0.95))} p99_ms=${fmt(pct(0.99))} " +
                        "mean_ms=${fmt(mean)} wall_ms=${fmt(wallNs.get() / 1_000_000.0)} " +
                        "failed=${failed.get()} unavailable=${unavailable.get()} " +
                        "writer_active_peak=${sampler.peak("writerPool", "active")} " +
                        "writer_awaiting_peak=${sampler.peak("writerPool", "awaiting")} " +
                        "reader_active_peak=${sampler.peak("readerPool", "active")} " +
                        "reader_awaiting_peak=${sampler.peak("readerPool", "awaiting")}"
                )
            }
        }
    }

    /** 8 roots x 50 queue children; returns the child ids grouped by root. */
    private suspend fun seed(context: ToolExecutionContext): List<List<UUID>> {
        val repo = context.workItemRepository()
        return (0 until ROOTS).map { r ->
            val rootId = UUID.randomUUID()
            val root =
                WorkItem(id = rootId, rootId = rootId, title = "bench-root-$r", role = Role.QUEUE, depth = 0)
            check(repo.create(root) is Result.Success) { "seed root $r failed" }
            (0 until ADVANCES).map { c ->
                val child =
                    WorkItem(
                        parentId = rootId,
                        rootId = rootId,
                        title = "bench-$r-$c",
                        role = Role.QUEUE,
                        depth = 1
                    )
                val created = repo.create(child)
                check(created is Result.Success) { "seed child $r/$c failed: $created" }
                created.data.id
            }
        }
    }

    private fun advanceParams(itemId: UUID): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemId.toString()))
                            put("trigger", JsonPrimitive("start"))
                        }
                    )
                }
            )
        }

    private enum class Outcome { APPLIED, FAILED, UNAVAILABLE }

    private fun classify(result: JsonElement): Outcome {
        val obj = result as? JsonObject ?: return Outcome.FAILED
        val ok = obj["success"]?.jsonPrimitive?.boolean == true
        val first =
            runCatching { obj["data"]?.jsonObject?.get("results")?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
        val applied = first?.get("applied")?.jsonPrimitive?.boolean == true
        if (ok && applied) return Outcome.APPLIED
        val text = result.toString().lowercase()
        return if ("unavailable" in text || "busy" in text || "database is locked" in text) Outcome.UNAVAILABLE else Outcome.FAILED
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    /** Polls Hikari pool MXBeans (if the manager has pools) at 1 ms; reports `na` otherwise. */
    private class PoolSampler(
        private val manager: Any
    ) {
        private val peaks = ConcurrentHashMap<String, Int>()
        private val observable = ConcurrentHashMap.newKeySet<String>()

        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            running = true
            thread =
                Thread {
                    while (running) {
                        sampleOnce()
                        try {
                            Thread.sleep(1)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }
        }

        fun stop() {
            running = false
            thread?.join(1000)
        }

        fun peak(
            pool: String,
            stat: String
        ): String = if ("$pool.$stat" in observable) peaks["$pool.$stat"].toString() else "na"

        private fun sampleOnce() {
            for (pool in listOf("writerPool", "readerPool")) {
                try {
                    val field = manager.javaClass.getDeclaredField(pool)
                    field.isAccessible = true
                    val ds = field.get(manager) ?: continue
                    val mxBeanType = Class.forName("com.zaxxer.hikari.HikariPoolMXBean")
                    val bean = ds.javaClass.getMethod("getHikariPoolMXBean").invoke(ds) ?: continue
                    val active = mxBeanType.getMethod("getActiveConnections").invoke(bean) as Int
                    val awaiting = mxBeanType.getMethod("getThreadsAwaitingConnection").invoke(bean) as Int
                    record("$pool.active", active)
                    record("$pool.awaiting", awaiting)
                } catch (_: Throwable) {
                    // No such field / class on this base: not observable.
                }
            }
        }

        private fun record(
            key: String,
            value: Int
        ) {
            observable.add(key)
            peaks.merge(key, value) { a, b -> maxOf(a, b) }
        }
    }

    private companion object {
        const val ROOTS = 8
        const val ADVANCES = 50
    }
}

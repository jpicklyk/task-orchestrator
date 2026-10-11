package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.support.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Search latency A/B for the Q3 follow-up N1 (one read transaction per search). Platform-gated: runs only with
 * TO_PERF=1, like [AdvanceLatencyBench].
 *
 * Seed: 10k items over 3 roots, 100k notes (10 per item), a common term in about 20% of the notes, a rare term in
 * about 10 rows. Cases: item AUTO unscoped (common, rare) and note AUTO unscoped (common). Configuration A is
 * `SearchService(engine, UnscopedUnitOfWork)` (each index statement is its own read, the pre-change behaviour);
 * configuration B is `SearchService(engine, SqliteUnitOfWork)` (one read for the whole search). The two are
 * interleaved per case, 5 warm-up and 50 timed runs each, and one `PERF_RESULT` line per case and configuration is
 * printed. Soft accept: B p95 <= A p95 for every case (`PERF_ACCEPT`).
 */
@Tag("serial")
class SearchLatencyBench {
    @Test
    fun searchLatencyAb() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            runBlocking { seed(db) }
            val clock = Clock { Instant.now() }
            val a = SearchService(provider.searchIndex(), UnscopedUnitOfWork(provider, clock))
            val b = SearchService(provider.searchIndex(), SqliteUnitOfWork(db.databaseManager, provider, clock))
            val cases =
                listOf(
                    Triple("item-common", Corpus.ITEM, COMMON),
                    Triple("item-rare", Corpus.ITEM, RARE),
                    Triple("note-common", Corpus.NOTE, COMMON),
                )
            for ((name, corpus, term) in cases) {
                val request =
                    SearchRequest(
                        query = term,
                        corpus = corpus,
                        matchMode = SearchMatchMode.AUTO,
                        access = AccessScope.unrestricted(),
                    )
                val samplesA = mutableListOf<Long>()
                val samplesB = mutableListOf<Long>()
                runBlocking {
                    repeat(WARMUP + RUNS) { i ->
                        val ta = time { a.search(request) }
                        val tb = time { b.search(request) }
                        if (i >= WARMUP) {
                            samplesA += ta
                            samplesB += tb
                        }
                    }
                }
                val pa = report("A-per-statement", name, samplesA)
                val pb = report("B-one-read", name, samplesB)
                println("PERF_ACCEPT case=$name b_p95_le_a_p95=${pb <= pa}")
            }
        }
    }

    private suspend fun time(block: suspend () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    /** Prints p50/p95 and returns p95 in ms. */
    private fun report(
        config: String,
        case: String,
        samples: List<Long>
    ): Double {
        val sorted = samples.sorted()

        fun pct(p: Double) = sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0
        println("PERF_RESULT config=$config case=$case n=${sorted.size} p50_ms=${fmt(pct(0.50))} p95_ms=${fmt(pct(0.95))}")
        return pct(0.95)
    }

    private suspend fun seed(db: SqliteTestDatabase) {
        val provider = db.repositoryProvider()
        val items = provider.workItemRepository()
        val notes = provider.noteRepository()
        val roots =
            (0 until ROOTS).map { r ->
                val id = UUID.randomUUID()
                check(items.create(WorkItem(id = id, rootId = id, title = "bench-root-$r", role = Role.QUEUE, depth = 0)) != null)
                id
            }
        var noteCount = 0
        var rareLeft = RARE_ROWS
        for (i in 0 until ITEMS) {
            val root = roots[i % ROOTS]
            val common = i % 5 == 0
            val rare = rareLeft > 0 && i % 1000 == 7
            if (rare) rareLeft--
            val title = "bench item $i" + (if (common) " $COMMON" else "") + (if (rare) " $RARE" else "")
            val item =
                items.create(
                    WorkItem(parentId = root, rootId = root, title = title, summary = "filler summary $i", role = Role.QUEUE, depth = 1)
                )
            checkNotNull(item) { "seed item $i failed" }
            repeat(NOTES_PER_ITEM) { n ->
                val body = "filler body $i $n" + if ((i + n) % 5 == 0) " $COMMON" else ""
                checkNotNull(notes.upsert(Note(itemId = item.id, key = "k$n", role = "work", body = body)))
                noteCount++
            }
        }
        println("PERF_SEED items=$ITEMS notes=$noteCount roots=$ROOTS")
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val ROOTS = 3
        const val ITEMS = 10_000
        const val NOTES_PER_ITEM = 10
        const val RARE_ROWS = 10
        const val COMMON = "commonword"
        const val RARE = "rarewordzx"
        const val WARMUP = 5
        const val RUNS = 50
    }
}

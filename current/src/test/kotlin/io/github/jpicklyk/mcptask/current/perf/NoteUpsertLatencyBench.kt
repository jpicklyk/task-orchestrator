package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Note upsert latency (item 23faed48, AC8 and performance-baseline section 4). Platform-gated: runs only with TO_PERF=1,
 * like [SearchLatencyBench]; without it the test is skipped, which is the only skip in this file.
 *
 * Seed: 1 root, 1000 child items with 3 plain notes each, 20 plan documents (`plan-0` .. `plan-19`). Cases, each on its own
 * note key over 205 distinct items (5 warm-up + 200 timed), so no timed run sees another run of the same case:
 *  - a: ~1.5 KB plain body replaced by another plain body (previous and new bodies contain no double bracket; the fast path),
 *  - b: the plain body replaced by the same text plus 5 links to existing documents and 1 dangling link,
 *  - c: the linked body replaced by the plain text (the links are removed),
 *  - d: the linked body replaced by an identical linked body (replay).
 * One `PERF_RESULT config=<PERF_CONFIG or branch> case=<c> n=200 p50_ms=.. p95_ms=..` line per case is printed. Only
 * NoteCommandService, DocumentService and the stores are used, so the file compiles at the base commit as well (there the
 * linked cases simply carry no link work), which gives the before/after for case a.
 *
 * Soft accept (performance-baseline): a.p95 within the larger of +5% and +0.2 ms of the base run's a.p95; b, c and d p95 at most
 * a.p95 + 2 ms (`PERF_ACCEPT` lines). A miss is a review finding, not a failed assertion.
 */
@Tag("serial")
class NoteUpsertLatencyBench {
    @Test
    fun noteUpsertLatency() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            val notes = ToolExecutionContext(provider, unitOfWork = db.unitOfWork()).noteCommandService
            val docs = DocumentService(provider, db.unitOfWork())
            val config = System.getenv("PERF_CONFIG") ?: "branch"
            runBlocking {
                val items = seed(db, docs)
                println("PERF_SEED items=${items.size} notes_per_item=$NOTES_PER_ITEM docs=$DOCS config=$config")
                val total = WARMUP + RUNS
                val p95 = mutableMapOf<String, Double>()

                // a: plain -> plain
                preseed(notes, items, "bench-a", PLAIN_V1, total)
                p95["a"] = measure(config, "a", notes, items, "bench-a", PLAIN_V2, total)

                // b: plain -> linked
                preseed(notes, items, "bench-b", PLAIN_V1, total)
                p95["b"] = measure(config, "b", notes, items, "bench-b", LINKED, total)

                // c: linked -> plain
                preseed(notes, items, "bench-c", LINKED, total)
                p95["c"] = measure(config, "c", notes, items, "bench-c", PLAIN_V2, total)

                // d: linked -> identical linked (replay)
                preseed(notes, items, "bench-d", LINKED, total)
                p95["d"] = measure(config, "d", notes, items, "bench-d", LINKED, total)

                val a = p95.getValue("a")
                for (case in listOf("b", "c", "d")) {
                    println("PERF_ACCEPT config=$config case=$case p95_le_a_plus_2ms=${p95.getValue(case) <= a + 2.0}")
                }
            }
        }
    }

    private suspend fun preseed(
        notes: NoteCommandService,
        items: List<UUID>,
        key: String,
        body: String,
        count: Int
    ) {
        for (i in 0 until count) upsert(notes, items[i], key, body)
    }

    private suspend fun measure(
        config: String,
        case: String,
        notes: NoteCommandService,
        items: List<UUID>,
        key: String,
        body: String,
        count: Int
    ): Double {
        val samples = mutableListOf<Long>()
        for (i in 0 until count) {
            val t0 = System.nanoTime()
            upsert(notes, items[i], key, body)
            val dt = System.nanoTime() - t0
            if (i >= WARMUP) samples += dt
        }
        return report(config, case, samples)
    }

    private suspend fun upsert(
        notes: NoteCommandService,
        itemId: UUID,
        key: String,
        body: String
    ) {
        val outcome = notes.upsert(NoteUpsertCommand(itemId, key, "work", body, null, null))
        check(outcome is Outcome.Ok) { "bench upsert failed: $outcome" }
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

    private suspend fun seed(
        db: SqliteTestDatabase,
        docs: DocumentService
    ): List<UUID> {
        val provider = db.repositoryProvider()
        val repo = provider.workItemRepository()
        val noteRepo = provider.noteRepository()
        val root = UUID.randomUUID()
        checkNotNull(repo.create(WorkItem(id = root, rootId = root, title = "bench-root", role = Role.QUEUE, depth = 0)))
        for (d in 0 until DOCS) {
            val outcome = docs.write(root, DocumentKind.PLAN, "plan-$d", "# plan $d\n\nfiller body for plan $d\n")
            check(outcome is Outcome.Ok) { "seed document $d failed: $outcome" }
        }
        val ids = mutableListOf<UUID>()
        for (i in 0 until ITEMS) {
            val item =
                checkNotNull(
                    repo.create(
                        WorkItem(
                            parentId = root,
                            rootId = root,
                            title = "bench item $i",
                            summary = "filler summary $i",
                            role = Role.QUEUE,
                            depth = 1
                        )
                    )
                ) { "seed item $i failed" }
            ids += item.id
            repeat(NOTES_PER_ITEM) { n ->
                checkNotNull(noteRepo.upsert(Note(itemId = item.id, key = "k$n", role = "work", body = "filler body $i $n")))
            }
        }
        return ids
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val ITEMS = 1000
        const val NOTES_PER_ITEM = 3
        const val DOCS = 20
        const val WARMUP = 5
        const val RUNS = 200
        val FILLER: String = "filler text for the latency body. ".repeat(44)
        val PLAIN_V1: String = "$FILLER v1"
        val PLAIN_V2: String = "$FILLER v2"
        val LINKED: String = "$FILLER [[plan-0]] [[plan:plan-1#intro]] [[plan-2]] [[plan-3#part]] [[plan-4]] [[missing-doc]]"
    }
}

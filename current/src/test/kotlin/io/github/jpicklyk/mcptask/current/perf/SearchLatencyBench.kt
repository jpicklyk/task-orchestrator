package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.service.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
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
 *
 * Q26 adds 600 documents over the same roots (200 plan at 2 KB, 200 rule at 8 KB, 200 page at 32 KB; the common term
 * in about 20% of the bodies, the rare term in about 5%) and the document cases C1 to C5 (see [documentCases]),
 * timed on configuration B only. C4 seeds 200 further 64 KB documents that all contain the term.
 */
@Tag("serial")
class SearchLatencyBench {
    @Test
    fun searchLatencyAb() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            val roots = runBlocking { seed(db) }
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
                        corpora = setOf(corpus),
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
            documentCases(db, b, roots)
        }
    }

    /**
     * C1 item+note explicit (the Q19 default), C2 kinds absent (the new default), C3 document only (common and rare),
     * C4 the 64 KB worst case, C5 C2 under a 2-root principal scope. One `PERF_RESULT config=Q26 case=...` line each.
     */
    private fun documentCases(
        db: SqliteTestDatabase,
        service: SearchService,
        roots: List<UUID>,
    ) {
        val all = AccessScope.unrestricted()
        val scoped = AccessScope.principal(roots.take(2).toSet(), emptySet())
        val itemNote = setOf(Corpus.ITEM, Corpus.NOTE)
        val cases =
            listOf(
                Triple("C1-item-note-common", itemNote, COMMON) to all,
                Triple("C2-default-common", null, COMMON) to all,
                Triple("C3-document-common", setOf(Corpus.DOCUMENT), COMMON) to all,
                Triple("C3-document-rare", setOf(Corpus.DOCUMENT), RARE) to all,
                Triple("C5-default-common-2root-scope", null, COMMON) to scoped,
            )
        for ((spec, access) in cases) {
            val (name, corpora, term) = spec
            timeCase(service, name, SearchRequest(query = term, corpora = corpora, matchMode = SearchMatchMode.AUTO, access = access))
        }
        runBlocking { seedWorstCaseDocuments(db, roots.first()) }
        timeCase(
            service,
            "C4-document-worst-case-64kb",
            SearchRequest(query = WORST, corpora = setOf(Corpus.DOCUMENT), matchMode = SearchMatchMode.AUTO, access = all),
        )
    }

    private fun timeCase(
        service: SearchService,
        name: String,
        request: SearchRequest,
    ) {
        val samples = mutableListOf<Long>()
        runBlocking {
            repeat(WARMUP + RUNS) { i ->
                val t = time { service.search(request) }
                if (i >= WARMUP) samples += t
            }
        }
        report("Q26", name, samples)
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

    private suspend fun seed(db: SqliteTestDatabase): List<UUID> {
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
        seedDocuments(db, roots)
        return roots
    }

    private suspend fun seedDocuments(
        db: SqliteTestDatabase,
        roots: List<UUID>,
    ) {
        val store = db.repositoryProvider().documentStore()
        val sizes = listOf(DocumentKind.PLAN to 2_000, DocumentKind.RULE to 8_000, DocumentKind.PAGE to 32_000)
        var count = 0
        for ((kind, size) in sizes) {
            repeat(DOCS_PER_KIND) { n ->
                val term =
                    when {
                        n % 5 == 0 -> " $COMMON"
                        n % 20 == 1 -> " $RARE"
                        else -> ""
                    }
                store.insert(document(roots[n % ROOTS], kind, "bench-${kind.name.lowercase()}-$n", "bench document $n$term", size))
                count++
            }
        }
        println("PERF_SEED documents=$count")
    }

    private suspend fun seedWorstCaseDocuments(
        db: SqliteTestDatabase,
        root: UUID,
    ) {
        val store = db.repositoryProvider().documentStore()
        repeat(DOCS_PER_KIND) { n ->
            store.insert(document(root, DocumentKind.PAGE, "bench-worst-$n", "worst $n $WORST", WORST_BYTES))
        }
        println("PERF_SEED worstCaseDocuments=$DOCS_PER_KIND bytes=$WORST_BYTES")
    }

    private fun document(
        root: UUID,
        kind: DocumentKind,
        slug: String,
        head: String,
        bytes: Int,
    ): Document {
        val now = Instant.now()
        val filler = "lorem ipsum dolor sit amet consectetur "
        val body = head + " " + filler.repeat(bytes / filler.length + 1).take(bytes)
        return Document(
            id = UUID.randomUUID(),
            rootItemId = root,
            kind = kind,
            slug = slug,
            title = head,
            body = body,
            contentHash = "bench",
            version = 1,
            archivedAt = null,
            createdAt = now,
            modifiedAt = now,
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private companion object {
        const val ROOTS = 3
        const val ITEMS = 10_000
        const val NOTES_PER_ITEM = 10
        const val RARE_ROWS = 10
        const val COMMON = "commonword"
        const val RARE = "rarewordzx"
        const val WORST = "worstwordqv"
        const val WORST_BYTES = 64 * 1024
        const val DOCS_PER_KIND = 200
        const val WARMUP = 5
        const val RUNS = 50
    }
}

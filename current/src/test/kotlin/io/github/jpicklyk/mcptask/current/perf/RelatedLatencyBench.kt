package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedService
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Latency of `RelatedService.related` (Q30, performance-baseline note of 630a826a). Platform-gated: runs only with
 * TO_PERF=1, like [SearchLatencyBench].
 *
 * Seed (the shape of [SearchLatencyBench] plus the relation data `related` reads): 10k items over 3 roots, 100k notes
 * (10 per item), 600 documents, a tag distribution with one tag on about 80% of the items, one on about 1% and the rest
 * spread thin, about 2k dependency edges and about 5k links.
 *
 * Cases: R1 a rich ref (edges, links, three tags, notes), R2 a ref carrying only the common tag (the worst case of the TAGS
 * signal, so its time is the TAGS share), R3 a ref with only rare terms, R4 R1 with `acrossRoots`, R5 R1 under a restricted
 * principal scope. 5 warm-up and 50 timed runs per case; one `PERF_RESULT` line with p50 and p95 each, and one
 * `PERF_ACCEPT` line against the plan's soft acceptance (p95 <= 150 ms for R1, R3 and R5; p95 <= 300 ms for R2). R4 is
 * reported without a threshold.
 *
 * The per-signal timing the plan asks for is read off the isolated refs: R2 carries only the TAGS signal and R3 only the
 * TEXT signal, so their lines are those signals' shares; the edge and link shares are R1 minus those.
 *
 * Forbidden-construct declaration: `assumeTrue(TO_PERF)` is the opt-in platform gate shared by every bench in this package
 * (a measurement that must not run in the default suite), not a guard on a behavioural condition.
 */
@Tag("serial")
class RelatedLatencyBench {
    @Test
    fun relatedLatency() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        SqliteTestDatabase.open().use { db ->
            val seeded = runBlocking { seed(db) }
            val service = RelatedService(index = db.repositoryProvider().searchIndex(), unitOfWork = db.unitOfWork())
            val unrestricted = AccessScope.unrestricted()
            val scoped = AccessScope.principal(setOf(seeded.roots[0]), emptySet())
            val cases =
                listOf(
                    Case("R1-rich", seeded.rich, false, unrestricted, ACCEPT_MS),
                    Case("R2-common-tag-only", seeded.commonTagOnly, false, unrestricted, ACCEPT_COMMON_TAG_MS),
                    Case("R3-rare-terms-only", seeded.rareTermsOnly, false, unrestricted, ACCEPT_MS),
                    Case("R4-rich-across-roots", seeded.rich, true, unrestricted, null),
                    Case("R5-rich-restricted-principal", seeded.rich, false, scoped, ACCEPT_MS),
                )
            for (case in cases) {
                val request =
                    RelatedRequest(
                        ref = EntityRef(RefKind.ITEM, case.ref),
                        limit = LIMIT,
                        offset = 0,
                        acrossRoots = case.acrossRoots,
                        access = case.access,
                    )
                val samples = mutableListOf<Long>()
                runBlocking {
                    repeat(WARMUP + RUNS) { i ->
                        val t = time { service.related(request) }
                        if (i >= WARMUP) samples += t
                    }
                }
                val p95 = report(case.name, samples)
                if (case.acceptMs != null) println("PERF_ACCEPT case=${case.name} p95_le_${case.acceptMs}ms=${p95 <= case.acceptMs}")
            }
        }
    }

    private class Case(
        val name: String,
        val ref: UUID,
        val acrossRoots: Boolean,
        val access: AccessScope,
        val acceptMs: Long?,
    )

    private class Seeded(
        val roots: List<UUID>,
        val rich: UUID,
        val commonTagOnly: UUID,
        val rareTermsOnly: UUID,
    )

    private suspend fun time(block: suspend () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    /** Prints p50 and p95 in ms and returns p95. */
    private fun report(
        case: String,
        samples: List<Long>,
    ): Double {
        val sorted = samples.sorted()

        fun pct(p: Double) = sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0
        println("PERF_RESULT config=Q30 case=$case n=${sorted.size} p50_ms=${fmt(pct(0.50))} p95_ms=${fmt(pct(0.95))}")
        return pct(0.95)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)

    private suspend fun seed(db: SqliteTestDatabase): Seeded {
        val provider = db.repositoryProvider()
        val items = provider.workItemRepository()
        val notes = provider.noteRepository()
        val deps = provider.dependencyRepository()
        val links = provider.linkStore()
        val documents = provider.documentStore()
        val roots =
            (0 until ROOTS).map { r ->
                val id = UUID.randomUUID()
                check(items.create(WorkItem(id = id, rootId = id, title = "bench-root-$r", role = Role.QUEUE, depth = 0)) != null)
                id
            }
        val ids = ArrayList<UUID>(ITEMS)
        val noteIds = ArrayList<UUID>(ITEMS)
        for (i in 0 until ITEMS) {
            val root = roots[i % ROOTS]
            val tags =
                buildList {
                    if (i % 5 != 0) add(COMMON_TAG)
                    if (i % 100 == 3) add(RARE_TAG)
                    add("spread-${i % 500}")
                }.joinToString(",")
            val title = "bench item $i" + if (i % 5 == 0) " $COMMON" else ""
            val item =
                checkNotNull(
                    items.create(
                        WorkItem(
                            parentId = root,
                            rootId = root,
                            title = title,
                            summary = "filler summary $i",
                            role = Role.QUEUE,
                            depth = 1,
                            tags = tags
                        ),
                    ),
                ) { "seed item $i failed" }
            ids += item.id
            repeat(NOTES_PER_ITEM) { n ->
                val body = "filler body $i $n" + if ((i + n) % 5 == 0) " $COMMON" else ""
                val note = checkNotNull(notes.upsert(Note(itemId = item.id, key = "k$n", role = "work", body = body)))
                if (n == 0) noteIds += note.id
            }
        }
        val docIds = ArrayList<UUID>(DOCS)
        repeat(DOCS) { n ->
            val doc =
                document(
                    roots[n % ROOTS],
                    DocumentKind.PLAN,
                    "bench-doc-$n",
                    "bench document $n" +
                        if (n % 5 ==
                            0
                        ) {
                            " $COMMON"
                        } else {
                            ""
                        }
                )
            documents.insert(doc)
            docIds += doc.id
        }
        for (e in 0 until EDGES) {
            val from = ids[(e * 7) % ITEMS]
            val to = ids[(e * 13 + 1) % ITEMS]
            if (from != to) deps.create(Dependency(fromItemId = from, toItemId = to, type = DependencyType.RELATES_TO))
        }
        for (l in 0 until LINKS) {
            val from = EntityRef(RefKind.NOTE, noteIds[(l * 11) % noteIds.size])
            val to = EntityRef(RefKind.DOCUMENT, docIds[l % docIds.size])
            links.insert(
                Link(
                    UUID.randomUUID(),
                    roots[l % ROOTS],
                    from,
                    to,
                    "plan:bench-doc-${l % docIds.size}",
                    LinkRelation.REFERENCES,
                    null,
                    Instant.now()
                )
            )
        }
        val rich = ids[RICH_INDEX]
        repeat(RICH_EXTRA) { k ->
            val other = ids[(RICH_INDEX + 17 * (k + 1)) % ITEMS]
            deps.create(
                Dependency(
                    fromItemId = rich,
                    toItemId = other,
                    type =
                        if (k % 2 ==
                            0
                        ) {
                            DependencyType.RELATES_TO
                        } else {
                            DependencyType.BLOCKS
                        }
                )
            )
            links.insert(
                Link(
                    UUID.randomUUID(),
                    roots[RICH_INDEX % ROOTS],
                    EntityRef(RefKind.NOTE, noteIds[RICH_INDEX]),
                    EntityRef(RefKind.DOCUMENT, docIds[k]),
                    "plan:bench-doc-$k",
                    LinkRelation.REFERENCES,
                    null,
                    Instant.now()
                ),
            )
        }
        val commonTagOnly =
            checkNotNull(
                items.create(
                    WorkItem(
                        parentId = roots[0],
                        rootId = roots[0],
                        title = "zzcommononly",
                        role = Role.QUEUE,
                        depth = 1,
                        tags = COMMON_TAG
                    )
                ),
            ).id
        val rareTermsOnly =
            checkNotNull(
                items.create(
                    WorkItem(parentId = roots[0], rootId = roots[0], title = "zzrarewordonly qxrareterm", role = Role.QUEUE, depth = 1)
                ),
            ).id
        println("PERF_SEED items=$ITEMS notes=${ITEMS * NOTES_PER_ITEM} documents=$DOCS edges=$EDGES links=$LINKS roots=$ROOTS")
        return Seeded(roots, rich, commonTagOnly, rareTermsOnly)
    }

    private fun document(
        root: UUID,
        kind: DocumentKind,
        slug: String,
        head: String,
    ): Document {
        val now = Instant.now()
        return Document(
            id = UUID.randomUUID(),
            rootItemId = root,
            kind = kind,
            slug = slug,
            title = head,
            body = "$head lorem ipsum dolor sit amet consectetur",
            contentHash = "bench",
            version = 1,
            archivedAt = null,
            createdAt = now,
            modifiedAt = now,
        )
    }

    private companion object {
        const val ROOTS = 3
        const val ITEMS = 10_000
        const val NOTES_PER_ITEM = 10
        const val DOCS = 600
        const val EDGES = 2_000
        const val LINKS = 5_000
        const val COMMON = "commonword"
        const val COMMON_TAG = "commontag"
        const val RARE_TAG = "raretag"
        const val LIMIT = 10
        const val RICH_INDEX = 3
        const val RICH_EXTRA = 10
        const val WARMUP = 5
        const val RUNS = 50
        const val ACCEPT_MS = 150L
        const val ACCEPT_COMMON_TAG_MS = 300L
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.migrated
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.uuidBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures the startup FTS integrity-check with six FTS5 tables (plan 4.1: add `FTS_INTEGRITY` only above ~2 s) and pins
 * the document tables' share of it. Oracle: `performance-baseline` section 4 -- planner-measured 11-44 ms for the document
 * pair against 2679 ms for all six at 100k notes; the 500 ms bound is a deliberately generous CI margin, and the totals and
 * ratios are LOGGED, not asserted.
 *
 * `@Tag("serial")`: the build runs wall-clock tests alone in `serialTest` (target one with `:current:serialTest --tests`).
 * Scale: default 10k notes x 600 B + 1k items + 50 documents x 8 KiB; env `PERF_SCALE=100k` seeds 100k / 10k / 200. The scale
 * is a parameter, never a skip.
 */
@Tag("serial")
class FtsIntegrityTimingTest {
    @TempDir
    lateinit var dir: Path

    private data class Scale(
        val notes: Int,
        val items: Int,
        val documents: Int
    )

    private fun scale(): Scale =
        when (System.getenv("PERF_SCALE")?.trim()?.lowercase()) {
            "100k" -> Scale(notes = 100_000, items = 10_000, documents = 200)
            else -> Scale(notes = 10_000, items = 1_000, documents = 50)
        }

    private val words: List<String> =
        Random(42).let { rnd -> List(2_000) { String(CharArray(3 + rnd.nextInt(8)) { 'a' + rnd.nextInt(26) }) } }

    private fun text(
        rnd: Random,
        chars: Int
    ): String {
        val sb = StringBuilder(chars + 12)
        while (sb.length < chars) sb.append(words[rnd.nextInt(words.size)]).append(' ')
        return sb.toString()
    }

    private fun seed(
        c: Connection,
        s: Scale
    ) {
        val rnd = Random(7)
        val ts = "2026-01-01 00:00:00.000"
        c.autoCommit = false
        val items = List(s.items) { UUID.randomUUID() }
        c
            .prepareStatement(
                "INSERT INTO work_items (id, parent_id, title, depth, root_id, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, NULL, ?, 0, ?, '$ts', '$ts', '$ts')"
            ).use { ps ->
                items.forEach {
                    ps.setBytes(1, uuidBytes(it))
                    ps.setString(2, text(rnd, 40))
                    ps.setBytes(3, uuidBytes(it))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        c.commit()
        c
            .prepareStatement(
                "INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at) VALUES (?, ?, ?, 'queue', ?, '$ts', '$ts')"
            ).use { ps ->
                repeat(s.notes) { i ->
                    ps.setBytes(1, uuidBytes(UUID.randomUUID()))
                    ps.setBytes(2, uuidBytes(items[i % items.size]))
                    ps.setString(3, "note-$i")
                    ps.setString(4, text(rnd, 600))
                    ps.addBatch()
                    if ((i + 1) % 5_000 == 0) {
                        ps.executeBatch()
                        c.commit()
                    }
                }
                ps.executeBatch()
            }
        c.commit()
        c
            .prepareStatement(
                "INSERT INTO documents (id, root_item_id, kind, slug, title, body, content_hash, version, created_at, modified_at) " +
                    "VALUES (?, ?, 'plan', ?, ?, ?, 'h', 1, '$ts', '$ts')"
            ).use { ps ->
                repeat(s.documents) { i ->
                    ps.setBytes(1, uuidBytes(UUID.randomUUID()))
                    ps.setBytes(2, uuidBytes(items[0]))
                    ps.setString(3, "doc-$i")
                    ps.setString(4, text(rnd, 30))
                    ps.setString(5, text(rnd, 8 * 1024))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        c.commit()
        c.autoCommit = true
    }

    @Test
    fun `S21 six-table integrity-check is timed per table and the document tables stay far below the bound`() {
        val s = scale()
        val url = migrated(dir)
        withConn(url) { seed(it, s) }

        assertEquals(s.items, scalarInt(url, "SELECT count(*) FROM work_items"))
        assertEquals(s.notes, scalarInt(url, "SELECT count(*) FROM notes"))
        assertEquals(s.documents, scalarInt(url, "SELECT count(*) FROM documents"))

        val perTableMs = linkedMapOf<String, Long>()
        var checkFtsMs = 0L
        withConn(url) { c ->
            for (fts in StartupIntegrity.FTS_TABLES) {
                val t0 = System.nanoTime()
                c.createStatement().use { it.execute("INSERT INTO $fts($fts, rank) VALUES('integrity-check', 1)") }
                perTableMs[fts] = (System.nanoTime() - t0) / 1_000_000
            }
            val t0 = System.nanoTime()
            StartupIntegrity.checkFts(c)
            checkFtsMs = (System.nanoTime() - t0) / 1_000_000
        }

        val documentsMs = perTableMs.getValue("documents_fts_trigram") + perTableMs.getValue("documents_fts_text")
        val totalMs = perTableMs.values.sum()
        println("FTS integrity-check at $s: per table $perTableMs; six-table sum ${totalMs}ms; StartupIntegrity.checkFts ${checkFtsMs}ms")
        println("documents pair ${documentsMs}ms = ${if (totalMs > 0) documentsMs * 100 / totalMs else 0}% of the six-table sum")

        assertEquals(6, perTableMs.size, "all six FTS tables are checked")
        assertTrue(documentsMs < 500, "the two document FTS tables took ${documentsMs}ms (measured 11-44ms by the planner): $perTableMs")

        // checkFts on a healthy database must leave the indexes exactly as they were: one docsize row per document.
        assertEquals(s.documents, scalarInt(url, "SELECT count(*) FROM documents_fts_text_docsize"))
        assertEquals(s.documents, scalarInt(url, "SELECT count(*) FROM documents_fts_trigram_docsize"))
        assertEquals(s.notes, scalarInt(url, "SELECT count(*) FROM notes_fts_text_docsize"))
        assertEquals(s.items, scalarInt(url, "SELECT count(*) FROM work_items_fts_text_docsize"))
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `67ba3514` ([Q5]) -- `ItemStore.findByIdPrefix`, which `task-scope` item 3
 * turns from a `LOWER(HEX(id)) LIKE` scan into a primary-key range over the BLOB id. Scenario S7 of the frozen
 * `test-plan` note (NEW-SURFACE for the range handling, risk R1: BLOB range vs hex prefix, byte order, odd nibble,
 * all-f carry) and the store half of S2/S4.
 *
 * Oracle: the hex-prefix definition -- a prefix selects exactly the ids whose lower-case, dash-free hex starts with it,
 * in any input case -- and `task-scope` item 3 ("`ORDER BY id`", "same case-insensitive semantics", an all-`f` prefix has
 * no upper bound). The expected sets are computed independently here by `String.startsWith` over the seeded ids; the
 * expected order is the unsigned byte order of the id, which is the lexicographic order of the canonical lower-case
 * string, NOT `UUID.compareTo` (that one is signed).
 *
 * The seeded ids are engineered around the byte bounds: leading bytes 00/01/0f/10/7f/80/8f/f0/fe/ff crossed with second
 * bytes 00/01/0f/10/ff, and extra all-ff-tailed ids at the carry edges, so a wrong low/high bound, a signed comparison,
 * a missing carry on an all-f prefix, or an off-by-one nibble pad changes a result.
 *
 * NOT COVERED here (declared in `test-manifest`): perf-baseline M1 (`EXPLAIN QUERY PLAN` shows a PK search for the
 * statement `findByIdPrefix` runs). The author may not read the production SQL, and an EXPLAIN over a hand-written
 * statement would test the author's SQL, not the store's; that check is left to the perf review.
 *
 * BLINDNESS: authored from the frozen notes and the orchestrator-supplied declarations. No `src/main` file, implementer
 * diff or implementer note was opened.
 */
class ItemIdPrefixRangeTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        repository = SQLiteWorkItemRepository(sqliteDb.databaseManager)
    }

    private val tailLow = "0000000040008000000000000000" // 28 hex
    private val tailHigh = "ffffffffffffffffffffffffffff" // 28 hex

    private fun uuidOf(
        head: String,
        tail: String = tailLow,
    ): UUID {
        require(head.length == 4 && tail.length == 28)
        val h = head + tail
        return UUID.fromString(
            "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}",
        )
    }

    private fun hex(id: UUID): String = id.toString().replace("-", "")

    private fun seedAll(ids: Collection<UUID>) {
        runBlocking { ids.forEach { repository.create(WorkItem(id = it, title = "t-${it.toString().take(8)}")) } }
    }

    private fun expected(
        ids: Collection<UUID>,
        prefix: String,
    ): List<UUID> = ids.filter { hex(it).startsWith(prefix.lowercase()) }.sortedBy { it.toString() }

    private fun found(
        prefix: String,
        limit: Int = 1000,
    ): List<UUID> = runBlocking { repository.findByIdPrefix(prefix, limit) }.map { it.id }

    private fun boundaryIds(): List<UUID> {
        val first = listOf("00", "01", "0f", "10", "7f", "80", "8f", "f0", "fe", "ff")
        val second = listOf("00", "01", "0f", "10", "ff")
        val lows = first.flatMap { a -> second.map { b -> uuidOf(a + b) } }
        val highs = listOf("0000", "7fff", "8000", "fffe", "ffff", "ff00", "00ff").map { uuidOf(it, tailHigh) }
        return (lows + highs).distinct()
    }

    // ============================================================================ S7

    @Test
    fun `S7 findByIdPrefix equals the startsWith oracle, in unsigned order, for every prefix length across the byte bounds`(): Unit =
        runBlocking {
            val ids = boundaryIds()
            seedAll(ids)

            val prefixes =
                (
                    ids.flatMap { id -> (1..8).map { hex(id).substring(0, it) } } +
                        listOf("f", "ff", "fff", "ffff", "fffff", "0", "00", "000", "0000", "00000")
                ).distinct()
            assertTrue(prefixes.size > 100, "fixture sanity: ${prefixes.size} prefixes")

            for (prefix in prefixes) {
                assertEquals(expected(ids, prefix), found(prefix), "prefix '$prefix'")
            }
        }

    @Test
    fun `S7 an upper-case prefix returns the same rows as the lower-case one`(): Unit =
        runBlocking {
            val ids = boundaryIds()
            seedAll(ids)
            for (prefix in listOf("ff", "ffff", "fffe", "f0", "8f0", "7fff", "0f01", "fe10")) {
                assertEquals(expected(ids, prefix), found(prefix.uppercase()), "upper '$prefix'")
                assertEquals(found(prefix), found(prefix.uppercase()), "lower vs upper '$prefix'")
            }
        }

    @Test
    fun `S7 the all-f prefix at every length carries past the top byte and stays bounded`(): Unit =
        runBlocking {
            val ids = boundaryIds()
            seedAll(ids)
            for (n in 1..8) {
                val prefix = "f".repeat(n)
                val got = found(prefix)
                assertEquals(expected(ids, prefix), got, "prefix '$prefix'")
                assertTrue(got.all { hex(it).startsWith(prefix) }, "no row outside '$prefix': $got")
            }
            // The largest possible id is selected by the all-f prefix and by nothing smaller than itself.
            val top = uuidOf("ffff", tailHigh)
            assertTrue(top in found("ffff"))
            assertEquals(listOf(top), found(hex(top)))
        }

    @Test
    fun `S7 the neighbours across a nibble bound are excluded, both below and above`(): Unit =
        runBlocking {
            val below = uuidOf("7fff", tailHigh)
            val inside = uuidOf("8000")
            val insideTop = uuidOf("8fff", tailHigh)
            val above = uuidOf("9000")
            seedAll(listOf(below, inside, insideTop, above))

            // '8' selects 8000.. through 8fff.. -- not 7fff.. (just below) and not 9000.. (just above).
            assertEquals(listOf(inside, insideTop), found("8"))
            assertEquals(listOf(inside), found("80"))
            assertEquals(listOf(insideTop), found("8ff"))
            assertEquals(listOf(below), found("7ff"))
            assertEquals(listOf(above), found("9"))
        }

    @Test
    fun `S7 the order is unsigned byte order, so a set top bit in the low half sorts after a clear one`(): Unit =
        runBlocking {
            // Same first 8 bytes; the 9th byte decides. UUID.compareTo is signed in the low half and would put 80.. and ff..
            // before 01.. -- the unsigned byte order of the BLOB key does not.
            val ids = listOf("ff", "80", "7f", "01").map { uuidOf("abcd", "000000004000" + it + "00000000000000") }
            seedAll(ids)

            val expectedOrder = ids.sortedBy { it.toString() }
            assertEquals(listOf("01", "7f", "80", "ff"), expectedOrder.map { hex(it).substring(16, 18) }, "fixture sanity")
            assertEquals(expectedOrder, found("abcd"))
        }
    // ============================================================================ limit and matching basics

    @Test
    fun `limit keeps the smallest ids first and never exceeds the limit`(): Unit =
        runBlocking {
            val ids = (1..5).map { uuidOf("ab%02x".format(it)) }
            seedAll(ids)

            assertEquals(ids.take(1), found("ab", limit = 1))
            assertEquals(ids.take(2), found("ab", limit = 2))
            assertEquals(ids, found("ab", limit = 5))
            assertEquals(ids, found("ab", limit = 50))
        }

    @Test
    fun `a full 32-hex prefix selects exactly its own item and carries the item fields`(): Unit =
        runBlocking {
            val id = uuidOf("1234")
            val other = uuidOf("1235")
            runBlocking {
                repository.create(WorkItem(id = id, title = "mine"))
                repository.create(WorkItem(id = other, title = "other"))
            }
            val rows = repository.findByIdPrefix(hex(id), 10)
            assertEquals(listOf(id), rows.map { it.id })
            assertEquals("mine", rows.single().title)
            assertEquals(listOf(id), found(hex(id).uppercase()))
        }

    @Test
    fun `a prefix that matches nothing returns an empty list`(): Unit =
        runBlocking {
            seedAll(listOf(uuidOf("1234"), uuidOf("9999")))
            assertEquals(emptyList(), found("5555"))
            assertEquals(emptyList(), found("ffffffff"))
            assertEquals(emptyList(), found("00000000"))
        }

    @Test
    fun `an empty store returns an empty list for any prefix`(): Unit =
        runBlocking {
            assertEquals(emptyList(), found("abcd1234"))
            assertEquals(emptyList(), found("ffffffff"))
            assertEquals(emptyList(), found("f"))
        }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.insertItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.uuidBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.withConn
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): the data-and-schema migration V22
 * "Normalize dependency direction". Scenarios S13, S14, S15 and S16 of the frozen test-plan.
 *
 * Oracles (frozen migration-assessment section 3 and plan 3.4, never the migration file):
 * - a threshold's rank is queue=0 < work=1 < review=2 < terminal=3, and a NULL threshold ranks as terminal;
 * - a twin pair is a BLOCKS row from b to a plus an IS_BLOCKED_BY row from a to b; the surviving row is the BLOCKS row
 *   (its id and created_at), carrying the stricter threshold; on equal rank the BLOCKS row's own value is kept; a
 *   stricter twin's raw value is copied, so a NULL stays NULL;
 * - every other IS_BLOCKED_BY row is rewritten in place: same id, same unblock_at, same created_at, ends swapped, type
 *   BLOCKS;
 * - BLOCKS and RELATES_TO rows are untouched, and mutual blocks (a BLOCKS b plus b BLOCKS a) are kept, both rows;
 * - the type CHECK becomes BLOCKS|RELATES_TO, the three indexes survive, foreign keys cascade, foreign_key_check stays
 *   clean and the migration runs with foreign_keys off;
 * - StartupIntegrity.reportMutualBlocks WARNs with the pair count and both ids and modifies nothing.
 *
 * Harness: [UpgradeHarness.copyAt] at 21 (the version before V22), raw JDBC seeding with [SchemaTestSupport], then
 * [UpgradeHarness.migrate]; the V18 test is the pattern. The seed `SeedV22` (harness-level coverage of a lone,
 * mutual and RELATES_TO row) is separate; twins are tested here because the harness has no expected-deletion contract.
 *
 * EXISTING-SURFACE: only the pre-existing harness is used, so deleting V22 yields behavioral red (IS_BLOCKED_BY rows
 * survive and the CHECK still allows them), not a compile failure.
 */
class V22NormalizeDependencyDirectionMigrationTest {
    @TempDir
    lateinit var dir: File

    private val createdAt = "2026-02-03 04:05:06.789"

    private data class Row(
        val id: UUID,
        val from: UUID,
        val to: UUID,
        val type: String,
        val unblockAt: String?,
        val createdAt: String
    )

    private fun hex(id: UUID): String = uuidBytes(id).joinToString("") { "%02X".format(it) }

    private fun fromHex(hex: String): UUID {
        val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val buf = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long)
    }

    private fun newDbAt21(name: String): String = UpgradeHarness.copyAt(21, File(dir, name))

    private fun seedItem(
        url: String,
        title: String
    ): UUID = UUID.randomUUID().also { insertItem(url, it, title = title) }

    private fun seedDep(
        url: String,
        from: UUID,
        to: UUID,
        type: String,
        unblockAt: String?,
        id: UUID = UUID.randomUUID(),
        created: String = createdAt
    ): UUID {
        withConn(url) { c ->
            c
                .prepareStatement(
                    "INSERT INTO dependencies (id, from_item_id, to_item_id, type, unblock_at, created_at) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setBytes(1, uuidBytes(id))
                    ps.setBytes(2, uuidBytes(from))
                    ps.setBytes(3, uuidBytes(to))
                    ps.setString(4, type)
                    ps.setString(5, unblockAt)
                    ps.setString(6, created)
                    ps.executeUpdate()
                }
        }
        return id
    }

    private fun rows(url: String): List<Row> =
        SchemaTestSupport.query(
            url,
            "SELECT hex(id), hex(from_item_id), hex(to_item_id), type, unblock_at, created_at FROM dependencies ORDER BY rowid"
        ) {
            Row(
                fromHex(it.getString(1)),
                fromHex(it.getString(2)),
                fromHex(it.getString(3)),
                it.getString(4),
                it.getString(5),
                it.getString(6)
            )
        }

    private fun migrate(url: String) {
        val probe = UpgradeHarness.migrate(url)
        assertTrue(probe.observed.isNotEmpty(), "the foreign-key probe must have fired")
        assertEquals(emptyList(), probe.violations(), "V22 must run with foreign_keys off")
        val applied =
            SchemaTestSupport.scalarInt(
                url,
                "SELECT count(*) FROM ${FlywayDatabaseSchemaManager.HISTORY_TABLE} WHERE version = '22' AND success = 1"
            )
        assertEquals(1, applied, "Flyway must have applied migration 22")
        val fkViolations = SchemaTestSupport.query(url, "PRAGMA foreign_key_check") { it.getString(1) }
        assertEquals(emptyList(), fkViolations, "PRAGMA foreign_key_check must be clean")
    }

    // ------------------------------------------------------------------
    // S13: twins collapse to the BLOCKS row carrying the stricter threshold
    // ------------------------------------------------------------------

    private data class TwinCase(
        val blocksUnblock: String?,
        val ibbUnblock: String?,
        val expected: String?
    )

    @Test
    fun `S13 a twin pair collapses to the BLOCKS row with the stricter threshold`() {
        val url = newDbAt21("twins.db")
        val cases =
            listOf(
                TwinCase(blocksUnblock = "work", ibbUnblock = null, expected = null), // NULL ranks as terminal: stricter, raw NULL copied
                TwinCase(blocksUnblock = null, ibbUnblock = "terminal", expected = null), // equal rank: the BLOCKS value is kept
                TwinCase(blocksUnblock = "review", ibbUnblock = "queue", expected = "review"), // BLOCKS stricter
                TwinCase(blocksUnblock = "queue", ibbUnblock = "review", expected = "review"), // IS_BLOCKED_BY stricter
                TwinCase(blocksUnblock = "terminal", ibbUnblock = null, expected = "terminal"), // equal rank: the BLOCKS value is kept
                TwinCase(blocksUnblock = "work", ibbUnblock = "work", expected = "work")
            )
        val seeded =
            cases.mapIndexed { index, case ->
                val a = seedItem(url, "a$index")
                val b = seedItem(url, "b$index")
                val blocksCreated = "2026-02-03 04:05:0$index.100"
                // alternate the insertion order so the result cannot depend on which row came first
                val (blocksId, ibbId) =
                    if (index % 2 == 0) {
                        val x = seedDep(url, b, a, "BLOCKS", case.blocksUnblock, created = blocksCreated)
                        x to seedDep(url, a, b, "IS_BLOCKED_BY", case.ibbUnblock, created = "2026-02-03 05:00:00.000")
                    } else {
                        val y = seedDep(url, a, b, "IS_BLOCKED_BY", case.ibbUnblock, created = "2026-02-03 05:00:00.000")
                        seedDep(url, b, a, "BLOCKS", case.blocksUnblock, created = blocksCreated) to y
                    }
                Triple(Triple(a, b, case), blocksId, ibbId) to blocksCreated
            }
        assertEquals(cases.size * 2, rows(url).size)

        migrate(url)

        val after = rows(url)
        assertEquals(cases.size, after.size, "each twin pair leaves exactly one row: $after")
        assertTrue(after.none { it.type == "IS_BLOCKED_BY" })
        seeded.forEach { (triple, blocksCreated) ->
            val (ab, blocksId, ibbId) = triple
            val (a, b, case) = ab
            val survivor = after.single { it.id == blocksId }
            assertEquals(
                Row(blocksId, b, a, "BLOCKS", case.expected, blocksCreated),
                survivor,
                "twin (${case.blocksUnblock}, ${case.ibbUnblock})"
            )
            assertTrue(after.none { it.id == ibbId }, "the IS_BLOCKED_BY twin row is gone")
        }
    }

    // ------------------------------------------------------------------
    // S14: a lone IS_BLOCKED_BY row is rewritten in place
    // ------------------------------------------------------------------

    @Test
    fun `S14 a lone IS_BLOCKED_BY row keeps its id threshold and created_at with its ends swapped`() {
        val url = newDbAt21("lone.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val id = seedDep(url, a, b, "IS_BLOCKED_BY", "review", created = "2026-02-03 04:05:06.789")

        migrate(url)

        assertEquals(listOf(Row(id, b, a, "BLOCKS", "review", "2026-02-03 04:05:06.789")), rows(url))
    }

    @Test
    fun `S14 a lone IS_BLOCKED_BY row without a threshold keeps it NULL`() {
        val url = newDbAt21("lone-null.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val id = seedDep(url, a, b, "IS_BLOCKED_BY", null)

        migrate(url)

        assertEquals(listOf(Row(id, b, a, "BLOCKS", null, createdAt)), rows(url))
    }

    @Test
    fun `S14 BLOCKS and RELATES_TO rows and a lone IS_BLOCKED_BY row on other pairs are handled independently`() {
        val url = newDbAt21("bystanders.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val c = seedItem(url, "c")
        val d = seedItem(url, "d")
        val blocks = seedDep(url, a, b, "BLOCKS", "work")
        val relates = seedDep(url, b, c, "RELATES_TO", null)
        val relatesReverse = seedDep(url, c, b, "RELATES_TO", null)
        val ibb = seedDep(url, c, d, "IS_BLOCKED_BY", "queue")

        migrate(url)

        val byId = rows(url).associateBy { it.id }
        assertEquals(4, byId.size)
        assertEquals(Row(blocks, a, b, "BLOCKS", "work", createdAt), byId[blocks])
        assertEquals(Row(relates, b, c, "RELATES_TO", null, createdAt), byId[relates], "RELATES_TO is never swapped")
        assertEquals(Row(relatesReverse, c, b, "RELATES_TO", null, createdAt), byId[relatesReverse])
        assertEquals(Row(ibb, d, c, "BLOCKS", "queue", createdAt), byId[ibb])
    }

    @Test
    fun `S14 probe an IS_BLOCKED_BY row whose swapped pair holds only a RELATES_TO row does not collide`() {
        val url = newDbAt21("relates-collision.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val relates = seedDep(url, b, a, "RELATES_TO", null)
        val ibb = seedDep(url, a, b, "IS_BLOCKED_BY", "work")

        migrate(url)

        val byId = rows(url).associateBy { it.id }
        assertEquals(2, byId.size, "different types are different keys: both rows survive")
        assertEquals(Row(relates, b, a, "RELATES_TO", null, createdAt), byId[relates])
        assertEquals(Row(ibb, b, a, "BLOCKS", "work", createdAt), byId[ibb])
    }

    // ------------------------------------------------------------------
    // S15: mutual blocks are kept and reported, never repaired
    // ------------------------------------------------------------------

    @Test
    fun `S15 a BLOCKS plus an IS_BLOCKED_BY on the same pair become mutual BLOCKS rows and are reported`() {
        val url = newDbAt21("mutual.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val blocks = seedDep(url, a, b, "BLOCKS", "work") // a blocks b
        val ibb = seedDep(url, a, b, "IS_BLOCKED_BY", "review") // b blocks a, once normalized

        migrate(url)

        val byId = rows(url).associateBy { it.id }
        assertEquals(2, byId.size, "mutual blocks are kept, not deduplicated")
        assertEquals(Row(blocks, a, b, "BLOCKS", "work", createdAt), byId[blocks])
        assertEquals(Row(ibb, b, a, "BLOCKS", "review", createdAt), byId[ibb])

        val warns = captureLogs { withConn(url) { StartupIntegrity.reportMutualBlocks(it) } }.at(Level.WARN)
        val joined = warns.joinToString("\n").lowercase()
        assertTrue(warns.isNotEmpty(), "the mutual pair must be reported")
        assertTrue(
            joined.contains(a.toString().substring(0, 8)) && joined.contains(b.toString().substring(0, 8)),
            "WARN must name both ids: $warns"
        )
        assertTrue(Regex("\\b1\\b").containsMatchIn(joined), "WARN must carry the count of 1 pair: $warns")
        assertEquals(2, rows(url).size, "reporting never modifies rows")
    }

    @Test
    fun `S15 reportMutualBlocks stays quiet when there is no mutual pair after the rewrite`() {
        val url = newDbAt21("not-mutual.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        val c = seedItem(url, "c")
        seedDep(url, a, b, "BLOCKS", null)
        seedDep(url, c, b, "IS_BLOCKED_BY", null) // b blocks c
        migrate(url)

        val warns = captureLogs { withConn(url) { StartupIntegrity.reportMutualBlocks(it) } }.at(Level.WARN)

        assertTrue(warns.none { it.contains(a.toString().substring(0, 8)) && it.contains(b.toString().substring(0, 8)) }, "no pair: $warns")
        assertEquals(2, rows(url).size)
    }

    @Test
    fun `S15 a BLOCKS pair that was already mutual before the migration is kept and reported`() {
        val url = newDbAt21("already-mutual.db")
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        seedDep(url, a, b, "BLOCKS", null)
        seedDep(url, b, a, "BLOCKS", null)

        migrate(url)

        assertEquals(2, rows(url).size)
        val warns = captureLogs { withConn(url) { StartupIntegrity.reportMutualBlocks(it) } }.at(Level.WARN)
        assertTrue(warns.isNotEmpty(), "the existing mutual pair is reported")
    }

    // ------------------------------------------------------------------
    // S16: the type CHECK is tightened and the table keeps its constraints
    // ------------------------------------------------------------------

    private fun migratedFresh(name: String): Pair<String, Pair<UUID, UUID>> {
        val url = newDbAt21(name)
        val a = seedItem(url, "a")
        val b = seedItem(url, "b")
        migrate(url)
        return url to (a to b)
    }

    @Test
    fun `S16 a raw IS_BLOCKED_BY insert fails the tightened CHECK while BLOCKS and RELATES_TO still insert`() {
        val (url, pair) = migratedFresh("check.db")
        val (a, b) = pair

        assertFailsWith<SQLException>("an IS_BLOCKED_BY row must violate the type CHECK") {
            seedDep(url, a, b, "IS_BLOCKED_BY", null)
        }
        assertEquals(0, rows(url).size, "the failed insert left nothing behind")

        seedDep(url, a, b, "BLOCKS", null)
        seedDep(url, a, b, "RELATES_TO", null)
        assertEquals(listOf("BLOCKS", "RELATES_TO"), rows(url).map { it.type }.sorted())
    }

    @Test
    fun `S16 the stored table definition names BLOCKS and RELATES_TO and no longer names IS_BLOCKED_BY`() {
        val (url, _) = migratedFresh("sql.db")

        val sql =
            SchemaTestSupport
                .query(url, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'dependencies'") {
                    it.getString(1)
                }.single()

        assertTrue(sql.contains("BLOCKS") && sql.contains("RELATES_TO"), sql)
        assertFalse(sql.contains("IS_BLOCKED_BY"), "the CHECK no longer allows the alias: $sql")
    }

    @Test
    fun `S16 the three indexes survive and the unique key still rejects a repeated edge`() {
        val (url, pair) = migratedFresh("indexes.db")
        val (a, b) = pair

        val indexes = SchemaTestSupport.objectNames(url, "index")
        for (name in listOf("idx_deps_unique", "idx_deps_from", "idx_deps_to")) assertTrue(name in indexes, "$name missing: $indexes")

        seedDep(url, a, b, "BLOCKS", null)
        assertFailsWith<SQLException>("(from, to, type) stays unique") { seedDep(url, a, b, "BLOCKS", "work") }
        seedDep(url, b, a, "BLOCKS", null) // the reverse direction is a different key
        assertEquals(2, rows(url).size)
    }

    @Test
    fun `S16 deleting a work item still cascades to its dependency rows at both ends`() {
        val (url, pair) = migratedFresh("cascade.db")
        val (a, b) = pair
        val c = seedItem(url, "c")
        seedDep(url, a, b, "BLOCKS", null)
        seedDep(url, c, a, "BLOCKS", null)
        seedDep(url, b, c, "RELATES_TO", null)

        withConn(url) { conn ->
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use {
                it.setBytes(1, uuidBytes(a))
                it.executeUpdate()
            }
        }

        val left = rows(url)
        assertEquals(
            listOf(Triple(b, c, "RELATES_TO")),
            left.map { Triple(it.from, it.to, it.type) },
            "both rows touching a were cascaded away"
        )
    }

    @Test
    fun `S16 probe migrating an empty dependencies table succeeds and leaves it empty`() {
        val url = newDbAt21("empty.db")

        migrate(url)

        assertEquals(0, rows(url).size)
        assertFailsWith<SQLException> { seedDep(url, UUID.randomUUID(), UUID.randomUUID(), "IS_BLOCKED_BY", null) }
    }
}

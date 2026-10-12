package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.RoleTransitionBackfillCallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V29 fix round 2 (M2): V29 drops role_transitions in the same migration, so it must not skip a row silently.
 * - A row whose transitioned_at does not parse is backfilled at the item's created_at (else the epoch) with
 *   `timestampRepaired: true`, ordered at that fallback time (parseable rows first on a tie, then id).
 * - [RoleTransitionBackfillCallback] reports the row counts around V29 and WARNs naming why rows were not copied.
 */
class V29BackfillAccountingTest {
    @TempDir
    lateinit var dir: File

    private class EventRow(
        val seq: Long,
        val id: UUID,
        val occurredAt: String,
        val data: JsonObject
    )

    private fun events(url: String): List<EventRow> =
        sqlRows(url, "SELECT seq, id, occurred_at, data FROM events WHERE type = 'item.transitioned' ORDER BY seq") { rs ->
            EventRow(
                rs.getLong(1),
                uuidOf(rs.getBytes(2))!!,
                rs.getString(3),
                Json.parseToJsonElement(rs.getString(4)) as JsonObject
            )
        }

    private fun transition(
        conn: Connection,
        name: String,
        item: String,
        at: String
    ) = BaselineDataset.insert(
        conn,
        "role_transitions",
        "id" to BaselineDataset.id("transition:$name"),
        "item_id" to BaselineDataset.id(item),
        "from_role" to "queue",
        "to_role" to "work",
        "trigger" to "start",
        "transitioned_at" to at
    )

    private fun hexOf(name: String) = BaselineDataset.id(name).joinToString("") { "%02x".format(it) }

    @Test
    fun `an unparseable transitioned_at is backfilled at the item created_at or the epoch and marked timestampRepaired`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "repair.sqlite"))
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.extraItem(conn, "repair-item", "work", "in-progress")
            transition(conn, "early", "repair-item", "2026-03-01 09:00:00.000")
            transition(conn, "garbled", "repair-item", "not-a-timestamp")
            transition(conn, "later", "repair-item", "2026-03-02 00:00:00.000")
            transition(conn, "orphan-a", "repair-orphan-item", "garbage")
            transition(conn, "orphan-b", "repair-orphan-item", "")
        }

        UpgradeHarness.migrate(url)

        val rows = events(url)
        val orphans = listOf("orphan-a", "orphan-b").sortedBy { hexOf("transition:$it") }
        assertEquals(
            (orphans + listOf("early", "garbled", "later")).map { BaselineDataset.uuid("transition:$it") },
            rows.map { it.id },
            "every row is backfilled; repaired rows sit at their fallback time, ties broken by id"
        )
        assertEquals((1L..5L).toList(), rows.map { it.seq })
        val byId = rows.associateBy { it.id }

        val garbled = byId.getValue(BaselineDataset.uuid("transition:garbled"))
        assertEquals(UtcTimestamp.format(UtcTimestamp.parse(BaselineDataset.TS)), garbled.occurredAt, "the item's created_at")
        assertEquals(JsonPrimitive(true), garbled.data["timestampRepaired"])
        assertEquals(JsonPrimitive(true), garbled.data["backfilled"])
        assertEquals("timestampRepaired", garbled.data.keys.last(), "the marker is appended after the documented keys")

        for (name in orphans) {
            val row = byId.getValue(BaselineDataset.uuid("transition:$name"))
            assertEquals("1970-01-01 00:00:00.000", row.occurredAt, "$name: an orphan falls back to the epoch")
            assertEquals(JsonPrimitive(true), row.data["timestampRepaired"], name)
        }
        for (name in listOf("early", "later")) {
            assertFalse("timestampRepaired" in byId.getValue(BaselineDataset.uuid("transition:$name")).data, name)
        }
    }

    /** One pre-V20 row, one garbled row, one future-dated row with no twin, one pre-V20 row whose id is already an event. */
    private fun seedAccounting(url: String) {
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.extraItem(conn, "count-item", "work", "in-progress")
            transition(conn, "count-old", "count-item", "2026-03-01 09:00:00.000")
            transition(conn, "count-garbled", "count-item", "not-a-timestamp")
            transition(conn, "count-future", "count-item", UtcTimestamp.format(Instant.now().plus(1, ChronoUnit.HOURS)))
            transition(conn, "count-dup", "count-item", "2026-03-01 09:00:01.000")
            BaselineDataset.insert(
                conn,
                "events",
                "id" to BaselineDataset.id("transition:count-dup"),
                "occurred_at" to "2026-03-01 09:00:01.000",
                "root_id" to BaselineDataset.id("root"),
                "entity_kind" to "item",
                "entity_id" to BaselineDataset.id("count-item"),
                "type" to "item.transitioned",
                "data" to "{\"trigger\":\"start\"}"
            )
        }
    }

    @Test
    fun `the callback counts every role_transitions row and the reasons a row was not copied`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "counts.sqlite"))
        seedAccounting(url)
        val callback = RoleTransitionBackfillCallback()

        FlywayDatabaseSchemaManager(url)
            .flywayConfiguration()
            .callbacks(callback)
            .load()
            .migrate()

        val report = assertNotNull(callback.lastReport)
        assertEquals(
            RoleTransitionBackfillCallback.Report(
                rows = 4,
                backfilled = 2,
                timestampRepaired = 1,
                atOrAfterV20 = 1,
                futureDated = 1,
                alreadyInEvents = 1
            ),
            report
        )
        assertEquals(2, report.notBackfilled)
    }

    @Test
    fun `the production Flyway configuration logs the counts at INFO and WARNs naming why rows were not copied`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "logs.sqlite"))
        seedAccounting(url)
        val logger = LoggerFactory.getLogger(RoleTransitionBackfillCallback::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val savedLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.INFO
        try {
            FlywayDatabaseSchemaManager(url).flywayConfiguration().load().migrate()
        } finally {
            logger.detachAppender(appender)
            logger.level = savedLevel
        }

        val info = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
        val warn = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(info.any { "rows=4, backfilled=2 (timestampRepaired=1)" in it }, "INFO count line: $info")
        assertEquals(1, warn.size, "one WARN: $warn")
        assertTrue("2 of 4 row(s) were NOT copied" in warn.single(), warn.single())
        assertTrue("1 dated at or after the V20 install" in warn.single(), warn.single())
        assertTrue("1 future-dated" in warn.single(), warn.single())
        assertTrue("1 already present in events by id" in warn.single(), warn.single())
    }

    @Test
    fun `a fully backfilled table logs the counts and no WARN`() {
        val url = UpgradeHarness.copyAt(28, File(dir, "clean.sqlite"))
        DriverManager.getConnection(url).use { conn ->
            BaselineDataset.extraItem(conn, "clean-item", "work", "in-progress")
            transition(conn, "clean-a", "clean-item", "2026-03-01 09:00:00.000")
        }
        val callback = RoleTransitionBackfillCallback()

        FlywayDatabaseSchemaManager(url)
            .flywayConfiguration()
            .callbacks(callback)
            .load()
            .migrate()

        val report = assertNotNull(callback.lastReport)
        assertEquals(1, report.rows)
        assertEquals(1, report.backfilled)
        assertEquals(0, report.notBackfilled)
    }
}

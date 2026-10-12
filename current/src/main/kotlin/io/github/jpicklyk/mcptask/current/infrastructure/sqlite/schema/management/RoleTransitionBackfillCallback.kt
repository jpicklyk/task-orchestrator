package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException

/**
 * Makes the V29 backfill observable. V29 copies the pre-events `role_transitions` rows into `events` and drops the
 * table in the same migration, so a row it does not copy is gone for good. This Flyway callback counts the table
 * before V29 runs and the backfilled events after it, on Flyway's own connection, and logs:
 * - INFO: the number of `role_transitions` rows, how many were backfilled, and how many of those had their
 *   timestamp repaired (unparseable `transitioned_at`, see the V29 header);
 * - WARN, when any row was not copied: how many, and why (dated at or after the V20 install, so assumed to already
 *   have its twin `item.transitioned` event, with the future-dated subset named; or already present in `events` by id).
 *
 * It only reads and never fails the migration. One instance per Flyway configuration: it holds the pre-V29 counts
 * until the after-migrate event. [lastReport] keeps the outcome for inspection.
 */
class RoleTransitionBackfillCallback : Callback {
    private val logger = LoggerFactory.getLogger(RoleTransitionBackfillCallback::class.java)

    /** Counts taken before V29 ran; null when V29 is not running or the table was absent. */
    private var before: Before? = null

    /** The report of the last V29 run seen by this instance, or null. */
    @Volatile
    var lastReport: Report? = null
        private set

    /** The pre-V29 counts. */
    data class Before(
        val rows: Long,
        val atOrAfterV20: Long,
        val futureDated: Long,
        val alreadyInEvents: Long,
        val backfilledEventsBefore: Long,
        val repairedEventsBefore: Long
    )

    /** The outcome reported after V29: what was found and what was copied. */
    data class Report(
        val rows: Long,
        val backfilled: Long,
        val timestampRepaired: Long,
        val atOrAfterV20: Long,
        val futureDated: Long,
        val alreadyInEvents: Long
    ) {
        val notBackfilled: Long get() = rows - backfilled
    }

    override fun supports(
        event: Event?,
        context: Context?
    ): Boolean =
        (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE) &&
            context?.migrationInfo?.version?.version == V29

    override fun canHandleInTransaction(
        event: Event?,
        context: Context?
    ): Boolean = true

    override fun handle(
        event: Event?,
        context: Context?
    ) {
        val conn = context?.connection ?: return
        try {
            when (event) {
                Event.BEFORE_EACH_MIGRATE -> before = countBefore(conn)
                Event.AFTER_EACH_MIGRATE -> before?.let { b -> log(report(conn, b).also { lastReport = it }) }
                else -> Unit
            }
        } catch (e: SQLException) {
            // Reporting must never fail the migration; say so loudly instead.
            logger.warn("V29 role_transitions backfill: could not count rows (${e.message}); the backfill itself is unaffected")
        } finally {
            if (event == Event.AFTER_EACH_MIGRATE) before = null
        }
    }

    override fun getCallbackName(): String = "roleTransitionBackfillReport"

    private fun countBefore(conn: Connection): Before? {
        if (scalar(conn, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'role_transitions'") == 0L) {
            return null
        }
        return Before(
            rows = scalar(conn, "SELECT COUNT(*) FROM role_transitions"),
            atOrAfterV20 = scalar(conn, "SELECT COUNT(*) FROM role_transitions WHERE $PARSED IS NOT NULL AND $PARSED >= $V20_BOUNDARY"),
            futureDated =
                scalar(
                    conn,
                    "SELECT COUNT(*) FROM role_transitions WHERE $PARSED IS NOT NULL AND $PARSED >= $V20_BOUNDARY " +
                        "AND $PARSED > strftime('%Y-%m-%d %H:%M:%f', 'now')"
                ),
            alreadyInEvents =
                scalar(
                    conn,
                    "SELECT COUNT(*) FROM role_transitions rt WHERE ($PARSED IS NULL OR $PARSED < $V20_BOUNDARY) " +
                        "AND EXISTS (SELECT 1 FROM events e WHERE e.id = rt.id)"
                ),
            backfilledEventsBefore = scalar(conn, "SELECT COUNT(*) FROM events WHERE $BACKFILLED"),
            repairedEventsBefore = scalar(conn, "SELECT COUNT(*) FROM events WHERE $BACKFILLED AND $REPAIRED")
        )
    }

    private fun report(
        conn: Connection,
        b: Before
    ): Report =
        Report(
            rows = b.rows,
            backfilled = scalar(conn, "SELECT COUNT(*) FROM events WHERE $BACKFILLED") - b.backfilledEventsBefore,
            timestampRepaired = scalar(conn, "SELECT COUNT(*) FROM events WHERE $BACKFILLED AND $REPAIRED") - b.repairedEventsBefore,
            atOrAfterV20 = b.atOrAfterV20,
            futureDated = b.futureDated,
            alreadyInEvents = b.alreadyInEvents
        )

    private fun log(r: Report) {
        logger.info(
            "V29 role_transitions backfill: rows=${r.rows}, backfilled=${r.backfilled} " +
                "(timestampRepaired=${r.timestampRepaired}); the table is now dropped"
        )
        if (r.notBackfilled > 0) {
            logger.warn(
                "V29 role_transitions backfill: ${r.notBackfilled} of ${r.rows} row(s) were NOT copied to events and were " +
                    "dropped with the table: ${r.atOrAfterV20} dated at or after the V20 install (assumed to already have " +
                    "their twin item.transitioned event), of which ${r.futureDated} future-dated (clock skew); " +
                    "${r.alreadyInEvents} already present in events by id"
            )
        }
    }

    private fun scalar(
        conn: Connection,
        sql: String
    ): Long =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    private companion object {
        const val V29 = "29"
        const val PARSED = "strftime('%Y-%m-%d %H:%M:%f', transitioned_at)"

        /** Same boundary as V29: the V20 installation time, or far future on a hand-baselined database. */
        const val V20_BOUNDARY =
            "COALESCE((SELECT strftime('%Y-%m-%d %H:%M:%f', MIN(installed_on)) FROM flyway_schema_history " +
                "WHERE version = '20'), '9999-12-31 00:00:00.000')"
        const val BACKFILLED = "json_extract(data, '\$.backfilled') = 1"
        const val REPAIRED = "json_extract(data, '\$.timestampRepaired') = 1"
    }
}

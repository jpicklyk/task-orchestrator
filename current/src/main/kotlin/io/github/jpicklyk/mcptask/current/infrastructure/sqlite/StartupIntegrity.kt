package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException

/** Thrown when a startup integrity check finds the database unusable (missing objects, unrepairable FTS). */
class StartupIntegrityException(
    message: String
) : IllegalStateException(message)

/**
 * Startup integrity checks run after Flyway migrate/validate, under the migration file lock.
 *
 * - [verifyInventory]: the expected FTS5 virtual tables and triggers must all exist. A missing
 *   object is a hard failure and nothing is re-created (re-creating would reintroduce a
 *   hand-written copy of the DDL).
 * - [checkFts]: per FTS5 table `integrity-check` with rank=1 (the default form passes a desynced
 *   external-content index); on failure `rebuild` then re-check; a second failure is a hard
 *   failure. SQLITE_BUSY means WARN and skip.
 * - [reportPlacementDrift] / [reportMutualBlocks]: WARN-only reports; never fail, never write.
 */
object StartupIntegrity {
    private val logger = LoggerFactory.getLogger(StartupIntegrity::class.java)

    val FTS_TABLES: List<String> =
        listOf("work_items_fts_trigram", "work_items_fts_text", "notes_fts_trigram", "notes_fts_text")

    /** The 12 FTS sync triggers (V7, with the V8 update-trigger replacements). */
    val FTS_TRIGGERS: List<String> =
        FTS_TABLES.flatMap { t -> listOf("${t}_ai", "${t}_ad", "${t}_au") }

    /** The 2 parent-cycle guard triggers (V7). */
    val CYCLE_TRIGGERS: List<String> = listOf("work_items_cycle_check", "work_items_cycle_check_update")

    private const val SAMPLE_LIMIT = 10
    private const val SQLITE_BUSY = 5

    /** Runs every check in order. Throws [StartupIntegrityException] on a hard failure. */
    fun run(connection: Connection) {
        verifyInventory(connection)
        checkFts(connection)
        reportPlacementDrift(connection)
        reportMutualBlocks(connection)
    }

    fun verifyInventory(connection: Connection) {
        val present = mutableSetOf<String>()
        connection.createStatement().use { st ->
            st.executeQuery("SELECT type, name FROM sqlite_master WHERE type IN ('table', 'trigger')").use { rs ->
                while (rs.next()) present += "${rs.getString(1)}:${rs.getString(2)}"
            }
        }
        val missing =
            FTS_TABLES.filter { "table:$it" !in present }.map { "FTS table $it" } +
                (FTS_TRIGGERS + CYCLE_TRIGGERS).filter { "trigger:$it" !in present }.map { "trigger $it" }
        if (missing.isNotEmpty()) {
            throw StartupIntegrityException(
                "Database is missing required schema objects: ${missing.joinToString(", ")}. " +
                    "Nothing was re-created. Restore from a backup or rebuild the database from migrations."
            )
        }
    }

    fun checkFts(connection: Connection) {
        for (table in FTS_TABLES) {
            val start = System.nanoTime()
            val outcome = checkOne(connection, table)
            val ms = (System.nanoTime() - start) / 1_000_000
            logger.info("FTS integrity check for $table: $outcome (${ms}ms)")
        }
    }

    private fun checkOne(
        connection: Connection,
        table: String
    ): String {
        try {
            exec(connection, "INSERT INTO $table($table, rank) VALUES('integrity-check', 1)")
            return "ok"
        } catch (e: SQLException) {
            if (isBusy(e)) {
                logger.warn("FTS integrity check for $table skipped: database busy (${e.message})")
                return "skipped-busy"
            }
            logger.warn("FTS integrity check failed for $table (${e.message}); rebuilding")
        }
        try {
            exec(connection, "INSERT INTO $table($table) VALUES('rebuild')")
            exec(connection, "INSERT INTO $table($table, rank) VALUES('integrity-check', 1)")
        } catch (e: SQLException) {
            if (isBusy(e)) {
                logger.warn("FTS rebuild/re-check for $table skipped: database busy (${e.message})")
                return "skipped-busy"
            }
            throw StartupIntegrityException(
                "FTS5 table $table failed integrity-check even after rebuild: ${e.message}"
            )
        }
        return "rebuilt"
    }

    private fun exec(
        connection: Connection,
        sql: String
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun isBusy(e: SQLException): Boolean = (e.errorCode and 0xFF) == SQLITE_BUSY || e.message?.contains("SQLITE_BUSY") == true

    /** WARN-only: parent/child depth and root_id drift, depth-0 rows with a foreign root_id, and orphans. */
    fun reportPlacementDrift(connection: Connection) {
        report(
            connection,
            "work items whose depth is not parent.depth + 1",
            "SELECT lower(hex(c.id)) AS ident FROM work_items c JOIN work_items p ON c.parent_id = p.id " +
                "WHERE c.depth != p.depth + 1"
        )
        report(
            connection,
            "work items whose root_id differs from the parent root_id",
            "SELECT lower(hex(c.id)) AS ident FROM work_items c JOIN work_items p ON c.parent_id = p.id " +
                "WHERE c.root_id IS NOT p.root_id"
        )
        report(
            connection,
            "depth-0 work items whose root_id is not their own id",
            "SELECT lower(hex(id)) AS ident FROM work_items WHERE parent_id IS NULL AND root_id IS NOT id"
        )
        report(
            connection,
            "orphaned work items (parent_id references a missing item)",
            "SELECT lower(hex(c.id)) AS ident FROM work_items c LEFT JOIN work_items p ON c.parent_id = p.id " +
                "WHERE c.parent_id IS NOT NULL AND p.id IS NULL"
        )
    }

    /** WARN-only: pairs of items that block each other (a BLOCKS b and b BLOCKS a). */
    fun reportMutualBlocks(connection: Connection) {
        report(
            connection,
            "mutual blocking dependency pairs (a blocks b and b blocks a)",
            "SELECT lower(hex(a.from_item_id)) || ' and ' || lower(hex(a.to_item_id)) AS ident " +
                "FROM dependencies a JOIN dependencies b " +
                "ON a.type = 'BLOCKS' AND b.type = 'BLOCKS' " +
                "AND a.from_item_id = b.to_item_id AND a.to_item_id = b.from_item_id " +
                "WHERE a.from_item_id < a.to_item_id"
        )
    }

    private fun report(
        connection: Connection,
        label: String,
        identQuery: String
    ) {
        try {
            var count = 0
            connection.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM ($identQuery)").use { rs -> if (rs.next()) count = rs.getInt(1) }
            }
            if (count == 0) return
            val ids = mutableListOf<String>()
            connection.createStatement().use { st ->
                st.executeQuery("$identQuery LIMIT $SAMPLE_LIMIT").use { rs -> while (rs.next()) ids += rs.getString(1) }
            }
            logger.warn("Data integrity report: $count $label. First ${ids.size}: ${ids.joinToString(", ")}")
        } catch (e: Exception) {
            logger.warn("Could not run integrity report ($label): ${e.message}")
        }
    }
}

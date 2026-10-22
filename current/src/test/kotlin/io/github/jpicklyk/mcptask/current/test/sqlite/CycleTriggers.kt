package io.github.jpicklyk.mcptask.current.test.sqlite

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Drops the V7 `work_items_cycle_check*` triggers so a test can write a corrupt (cyclic) `parent_id`
 * directly, simulating pre-guard data. On the real SQLite schema those triggers abort cycle writes,
 * so cyclic fixtures call [drop] first.
 */
object CycleTriggers {
    private val NAMES = listOf("work_items_cycle_check", "work_items_cycle_check_update")

    /** Drops both cycle triggers on [db] (idempotent). */
    fun drop(db: Database) {
        transaction(db = db) {
            NAMES.forEach { exec("DROP TRIGGER IF EXISTS $it") }
        }
    }

    /** Drops both cycle triggers on a raw JDBC connection (idempotent). */
    fun drop(conn: java.sql.Connection) {
        conn.createStatement().use { st -> NAMES.forEach { st.execute("DROP TRIGGER IF EXISTS $it") } }
    }
}

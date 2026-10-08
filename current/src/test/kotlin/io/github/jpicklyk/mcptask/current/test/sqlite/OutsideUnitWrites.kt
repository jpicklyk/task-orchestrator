package io.github.jpicklyk.mcptask.current.test.sqlite

import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import kotlin.test.assertEquals

/** Snapshot of `units.outsideUnitWrites` (store writes attempted outside a unit of work), by op. */
fun DatabaseManager.outsideUnitWriteCounts(): Map<String, Long> = units.outsideUnitWrites

/**
 * Runs [block] (the system-under-test call) and asserts it made NO store write outside a unit of work: the
 * outside-unit write counter of [databaseManager] does not move across it. Seeding before the call may still
 * write outside a unit (the fixture policy is IMPLICIT). Returns the block's result.
 */
inline fun <T> assertNoOutsideUnitWrites(
    databaseManager: DatabaseManager,
    block: () -> T
): T {
    val before = databaseManager.outsideUnitWriteCounts()
    val result = block()
    val after = databaseManager.outsideUnitWriteCounts()
    val moved = after.filter { (op, n) -> n != (before[op] ?: 0L) }.mapValues { (op, n) -> n - (before[op] ?: 0L) }
    assertEquals(emptyMap(), moved, "the call made store writes outside a unit of work (op -> count)")
    return result
}

/** [assertNoOutsideUnitWrites] over this database. */
inline fun <T> SqliteTestDatabase.assertNoOutsideUnitWrites(block: () -> T): T = assertNoOutsideUnitWrites(databaseManager, block)

/** [assertNoOutsideUnitWrites] over the extension's current database. */
inline fun <T> SqliteTestDatabaseExtension.assertNoOutsideUnitWrites(block: () -> T): T = assertNoOutsideUnitWrites(databaseManager, block)

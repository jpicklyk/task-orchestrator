package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The row mapper is total for unreadable timestamp text: one row whose stored timestamp cannot be parsed (and any
 * integer value, which is deliberately not read as epoch millis) is returned with a diagnostic naming the column,
 * and does not fail the whole list.
 */
class WorkItemRowsBadTimestampTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val items get() = sqliteDb.repositoryProvider().itemStore()
    private val jdbc get() = sqliteDb.jdbcUrl

    @Test
    fun `a row with unparseable text timestamps is returned with diagnostics and does not fail the list`(): Unit =
        runBlocking {
            val good = items.create(WorkItem(title = "Good"))
            val bad = items.create(WorkItem(title = "Bad"))
            P7Raw.exec(jdbc, "UPDATE work_items SET created_at = ? WHERE id = ?", "not-a-timestamp", bad.id)
            P7Raw.exec(jdbc, "UPDATE work_items SET claimed_at = ? WHERE id = ?", "garbage", bad.id)

            val listed = items.findByIds(setOf(good.id, bad.id))
            assertEquals(setOf(good.id, bad.id), listed.map { it.id }.toSet())

            val badRow = listed.single { it.id == bad.id }
            val diagnostics = assertNotNull(badRow.diagnostics)
            assertTrue(diagnostics.any { "created_at" in it }, "diagnostics must name created_at: $diagnostics")
            assertTrue(diagnostics.any { "claimed_at" in it }, "diagnostics must name claimed_at: $diagnostics")
            assertEquals(Instant.EPOCH, badRow.createdAt)
            assertNull(badRow.claimedAt)
            assertNull(listed.single { it.id == good.id }.diagnostics)
        }

    @Test
    fun `an integer stored in a timestamp column is invalid with a diagnostic, not epoch millis`(): Unit =
        runBlocking {
            val bad = items.create(WorkItem(title = "IntegerStamp"))
            P7Raw.exec(jdbc, "UPDATE work_items SET modified_at = 1700000000000 WHERE id = ?", bad.id)

            val row = assertNotNull(items.getById(bad.id))
            assertTrue(assertNotNull(row.diagnostics).any { "modified_at" in it })
            assertEquals(Instant.EPOCH, row.modifiedAt)
        }
}

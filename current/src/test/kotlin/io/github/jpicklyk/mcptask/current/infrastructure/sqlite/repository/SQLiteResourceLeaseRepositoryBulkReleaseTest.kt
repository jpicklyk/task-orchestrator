package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** F-016: `LeaseStore.releaseAllForItems`. */
class SQLiteResourceLeaseRepositoryBulkReleaseTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val database get() = sqliteDb.database
    private val repositoryProvider get() = sqliteDb.repositoryProvider()

    private val leases get() = repositoryProvider.resourceLeaseRepository()

    private suspend fun holders(n: Int): List<UUID> =
        (0 until n).map {
            ((repositoryProvider.workItemRepository().create(WorkItem(title = "h$it", depth = 0)))!!).id
        }

    private fun openIntervals(holder: UUID): Int =
        transaction(db = database) {
            exec(
                "SELECT COUNT(*) FROM resource_lease_history WHERE holder_item_id = ? AND released_at IS NULL",
                args = listOf(UUIDColumnType() to holder),
            ) { rs -> if (rs.next()) rs.getInt(1) else -1 } ?: -1
        }

    private fun reasons(holder: UUID): List<String?> =
        transaction(db = database) {
            exec(
                "SELECT release_reason FROM resource_lease_history WHERE holder_item_id = ?",
                args = listOf(UUIDColumnType() to holder),
            ) { rs ->
                val out = mutableListOf<String?>()
                while (rs.next()) out += rs.getString(1)
                out
            } ?: emptyList()
        }

    private fun expireLease(
        key: String,
        holder: UUID,
    ) {
        transaction(db = database) {
            val keyType = VarCharColumnType(255)
            val uuidType = UUIDColumnType()
            exec(
                "UPDATE resource_leases SET expires_at = datetime('now', '-10 seconds') WHERE resource_key = ? AND holder_item_id = ?",
                args = listOf(keyType to key, uuidType to holder),
            )
            exec(
                "UPDATE resource_lease_history SET expires_at = datetime('now', '-10 seconds') " +
                    "WHERE resource_key = ? AND holder_item_id = ? AND released_at IS NULL",
                args = listOf(keyType to key, uuidType to holder),
            )
        }
    }

    /**
     * Runs [block] inside one transaction whose own connection has SQLITE_LIMIT_VARIABLE_NUMBER
     * lowered to [limit] (the limit dies with that connection). The nested lease-repository
     * transaction joins this outer one, so a statement binding more than [limit] variables fails.
     */
    private suspend fun <T> withVariableLimit(
        limit: Int,
        block: suspend () -> T,
    ): T {
        var out: T? = null
        sqliteDb.unitOfWork().inUnit {
            val conn = TransactionManager.current().connection.connection as Connection
            val sqlite = conn.unwrap(SQLiteConnection::class.java)
            sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, limit)
            out = block()
        }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun releaseExactly(n: Int): Unit =
        runBlocking {
            val hs = holders(n)
            hs.forEachIndexed { i, h -> assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "a", listOf("res-$i" to 900))) }

            // Limit == chunk size: only chunked statements (<= SQL_IN_CHUNK_SIZE variables) can succeed.
            val result = withVariableLimit(SQL_IN_CHUNK_SIZE) { leases.releaseAllForItems(hs.toSet()) }

            assertEquals(LeaseReleaseResult.Success(n), result)
            assertTrue(hs.all { openIntervals(it) == 0 })
            assertTrue(hs.all { leases.findActiveForItem(it).isEmpty() })
        }

    @Test
    fun `T3 chunk boundary one below the chunk size`() = releaseExactly(SQL_IN_CHUNK_SIZE - 1)

    @Test
    fun `T3 chunk boundary exactly the chunk size`() = releaseExactly(SQL_IN_CHUNK_SIZE)

    @Test
    fun `T3 chunk boundary one above the chunk size`() = releaseExactly(SQL_IN_CHUNK_SIZE + 1)

    @Test
    fun `T3 empty set is a no-op returning zero`(): Unit =
        runBlocking {
            val result = leases.releaseAllForItems(emptySet())
            assertEquals(LeaseReleaseResult.Success(0), result)
        }

    @Test
    fun `T3 releases leases and closes history for a holder set larger than the chunk size`(): Unit =
        runBlocking {
            val n = SQL_IN_CHUNK_SIZE + 100
            val hs = holders(n)
            val bystander = holders(1).single()
            hs.forEachIndexed { i, h -> assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "a", listOf("res-$i" to 900))) }
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(bystander, "a", listOf("res-bystander" to 900)))
            // One expired hold: must close as expired exactly as releaseAllForItem would.
            expireLease("res-0", hs[0])

            // Lower the variable limit below n so an unchunked IN list would fail.
            val result = withVariableLimit(SQL_IN_CHUNK_SIZE + 50) { leases.releaseAllForItems(hs.toSet()) }

            assertEquals(LeaseReleaseResult.Success(n), result)
            assertTrue(hs.all { openIntervals(it) == 0 }, "every open interval of every holder must be closed")
            assertTrue(hs.all { leases.findActiveForItem(it).isEmpty() })
            assertEquals(listOf<String?>("expired"), reasons(hs[0]))
            assertEquals(listOf<String?>("released"), reasons(hs[1]))
            // Bystander untouched.
            assertEquals(1, leases.findActiveForItem(bystander).size)
            assertEquals(1, openIntervals(bystander))
        }
}

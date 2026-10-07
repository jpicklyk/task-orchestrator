package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** F-016: `ResourceLeaseRepository.releaseAllForItems`. */
class SQLiteResourceLeaseRepositoryBulkReleaseTest : SQLiteRepositoryTestBase() {
    private val leases get() = repositoryProvider.resourceLeaseRepository()

    private suspend fun holders(n: Int): List<UUID> =
        (0 until n).map {
            ((repositoryProvider.workItemRepository().create(WorkItem(title = "h$it", depth = 0))) as Result.Success).data.id
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

            val result = leases.releaseAllForItems(hs.toSet())

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

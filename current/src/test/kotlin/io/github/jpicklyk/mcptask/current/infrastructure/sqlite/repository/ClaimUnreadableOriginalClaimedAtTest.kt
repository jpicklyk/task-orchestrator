package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** A same-agent re-claim of a row whose stored original_claimed_at is unreadable falls back to the unit instant. */
class ClaimUnreadableOriginalClaimedAtTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    @Test
    fun `re-claim by the same agent tolerates unparseable original_claimed_at`(): Unit =
        runBlocking {
            val repo = sqliteDb.repositoryProvider().workItemRepository()
            val item = repo.create(WorkItem(title = "Reclaim"))
            assertIs<ClaimResult.Success>(repo.claim(item.id, "agent-a", ttlSeconds = 60))
            P7Raw.exec(sqliteDb.jdbcUrl, "UPDATE work_items SET original_claimed_at = ? WHERE id = ?", "garbage", item.id)

            val again = assertIs<ClaimResult.Success>(repo.claim(item.id, "agent-a", ttlSeconds = 60))
            assertNotNull(again.item.originalClaimedAt)
        }
}

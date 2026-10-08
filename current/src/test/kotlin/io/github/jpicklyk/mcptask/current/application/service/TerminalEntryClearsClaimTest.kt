package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A claim that lands between the transition's snapshot read and its update must not survive an entry into
 * TERMINAL: claims no longer bump `version`, so nothing else would catch the interleaving.
 */
class TerminalEntryClearsClaimTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    @Test
    fun `a claim placed after the snapshot is cleared when the item enters terminal`(): Unit =
        runBlocking {
            val provider = sqliteDb.repositoryProvider()
            val repository = provider.workItemRepository()
            val snapshot = repository.create(WorkItem(title = "Racy", role = Role.WORK))
            assertNull(snapshot.claimedBy)

            // The interleaving: a claim lands after the snapshot was read, before applyTransition writes.
            assertIs<ClaimResult.Success>(repository.claim(snapshot.id, "late-claimer", ttlSeconds = 900))

            val result =
                RoleTransitionHandler().applyTransition(
                    item = snapshot,
                    targetRole = Role.TERMINAL,
                    trigger = "complete",
                    summary = null,
                    statusLabel = null,
                    workItemRepository = repository,
                    roleTransitionRepository = provider.roleTransitionRepository(),
                    unitOfWork = sqliteDb.unitOfWork()
                )
            assertTrue(result.success, "transition failed: ${result.error}")

            val after = assertNotNull(repository.getById(snapshot.id))
            assertNull(after.claimedBy, "a terminal item must not stay claimed")
            assertNull(after.claimExpiresAt)
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testClaimService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A claim that lands after the caller read its copy of the item must not survive an entry into TERMINAL:
 * claims no longer bump `version`, so the advance clears the claim columns unconditionally on TERMINAL entry
 * (and re-reads the row inside its unit, so the stale caller copy is never what gets written).
 */
class TerminalEntryClearsClaimTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    @Test
    fun `a claim placed after the caller's read is cleared when the item enters terminal`(): Unit =
        runBlocking {
            val provider = sqliteDb.repositoryProvider()
            val repository = provider.workItemRepository()
            val snapshot = repository.create(WorkItem(title = "Racy", role = Role.WORK))
            assertNull(snapshot.claimedBy)

            // The interleaving: a claim lands after the caller's read, before the advance runs.
            assertIs<ClaimResult.Success>(repository.claim(snapshot.id, "late-claimer", ttlSeconds = 900))

            val service =
                AdvanceService(
                    workItemRepository = repository,
                    dependencyRepository = provider.dependencyRepository(),
                    noteRepository = provider.noteRepository(),
                    schemaResolver = { null },
                    unitOfWork = sqliteDb.unitOfWork(),
                    claimService = testClaimService(repository, null, sqliteDb.unitOfWork())
                )
            // Ownership is always enforced: the complete is made as the late claimer, whose claim it clears.
            val outcome =
                service.advance(
                    snapshot,
                    "complete",
                    null,
                    ActorClaim(id = "late-claimer", kind = ActorKind.EXTERNAL),
                    null,
                )
            assertIs<AdvanceOutcome.Success>(outcome, "transition failed: $outcome")

            val after = assertNotNull(repository.getById(snapshot.id))
            assertNull(after.claimedBy, "a terminal item must not stay claimed")
            assertNull(after.claimExpiresAt)
        }
}

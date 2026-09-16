package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.BaseRepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Bug `3785f37a`, S12 (integration, H2): the terminal claim-clear performed inside
 * [AdvanceService.advance] / [io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler.applyTransition]
 * must actually survive the round trip through [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.update]
 * — a plain in-memory mock (as used by `AdvanceServiceTerminalClaimClearTest`) cannot prove that;
 * only a real persisted `update()` + re-fetch can. Uses [BaseRepositoryTest]'s real H2-backed
 * repositories (not mocks), which is why this lives in the repository test package rather than
 * alongside the mocked `AdvanceService` unit tests.
 *
 * EXISTING-SURFACE: no new production signature; a narrowest revert of the
 * `RoleTransitionHandler.applyTransition` copy-block change alone turns these tests red.
 *
 * Oracles: O1 (`api-reference.md:1727`, terminal items cannot be claimed) and O3 (`reopen`
 * TERMINAL->QUEUE) from the frozen test-plan.
 */
class SQLiteTerminalClaimClearTest : BaseRepositoryTest() {
    private fun advanceService(): AdvanceService =
        AdvanceService(
            workItemRepository = repositoryProvider.workItemRepository(),
            roleTransitionRepository = repositoryProvider.roleTransitionRepository(),
            dependencyRepository = repositoryProvider.dependencyRepository(),
            noteRepository = repositoryProvider.noteRepository(),
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { null },
        )

    /** Persists a WORK-role item already claimed by [claimedBy], via `create()` (H2-safe). */
    private suspend fun createClaimedItem(
        claimedBy: String,
        role: Role = Role.WORK
    ): WorkItem {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val result =
            repositoryProvider.workItemRepository().create(
                WorkItem(
                    title = "Claimed persisted item",
                    role = role,
                    depth = 0,
                    claimedBy = claimedBy,
                    claimedAt = now,
                    claimExpiresAt = now.plusSeconds(900),
                    originalClaimedAt = now,
                ),
            )
        return (result as Result.Success).data
    }

    @Test
    fun `S12 re-reading after a terminal advance shows all four claim columns null`(): Unit =
        runBlocking {
            val item = createClaimedItem(claimedBy = "agent-persisted")

            val outcome =
                advanceService().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)

            // The proof this file exists for: re-fetch from the DB, not the in-memory result —
            // confirms the clear was actually written by update(), not merely present on the
            // in-process return value.
            val reFetched = repositoryProvider.workItemRepository().getById(item.id)
            val persisted = (reFetched as Result.Success).data
            assertNull(persisted.claimedBy, "claimedBy must be null on the re-fetched row")
            assertNull(persisted.claimedAt, "claimedAt must be null on the re-fetched row")
            assertNull(persisted.claimExpiresAt, "claimExpiresAt must be null on the re-fetched row")
            assertNull(persisted.originalClaimedAt, "originalClaimedAt must be null on the re-fetched row")
        }

    /**
     * Ordering probe (test-plan §6): the previousRole == TERMINAL clause must also survive
     * persistence for a legacy terminal-and-still-claimed row, symmetric with the primary S12
     * case above. Confirms `reopen` does not merely null the fields on the transient result but
     * commits the clear to the same row a subsequent `getById` reads back.
     */
    @Test
    fun `reopen on a legacy claimed-terminal row persists the clear before the item is re-fetched`(): Unit =
        runBlocking {
            val item = createClaimedItem(claimedBy = "agent-legacy", role = Role.TERMINAL)
            assertNotNull(item.claimedBy, "fixture must model a pre-fix terminal-and-claimed row")

            val outcome =
                advanceService().advance(item, "reopen", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.QUEUE, success.result.newRole)

            val reFetched = repositoryProvider.workItemRepository().getById(item.id)
            val persisted = (reFetched as Result.Success).data
            assertNull(persisted.claimedBy)
            assertNull(persisted.claimedAt)
            assertNull(persisted.claimExpiresAt)
            assertNull(persisted.originalClaimedAt)
        }
}

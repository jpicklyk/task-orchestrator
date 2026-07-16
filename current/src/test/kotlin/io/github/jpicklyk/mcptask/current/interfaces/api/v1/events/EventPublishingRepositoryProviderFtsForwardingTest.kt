package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.SearchResult
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Regression tests for bug 56aa72f0 — FTS search (`query_items`/`query_notes` operation=search,
 * and the `/search` / `/notes/search` REST routes) silently returned empty whenever the REST API
 * was enabled.
 *
 * Root cause: `ftsSearch()` lived only on the concrete `SQLiteWorkItemRepository` /
 * `SQLiteNoteRepository` classes, not on the `WorkItemRepository` / `NoteRepository` interfaces.
 * Callers gated dispatch behind an `is SQLite*Repository` check. When the REST API is enabled,
 * `ServerComposition` wraps repositories in `EventPublishing*Repository` decorators (`... by
 * inner`), which are NOT instances of the concrete SQLite types — so the `is` check always failed
 * and FTS never ran, even though the underlying database and FTS5 index were healthy.
 *
 * The fix promotes `ftsSearch` onto the `WorkItemRepository`/`NoteRepository` interfaces; the
 * `by inner` decorators then auto-forward the call to the wrapped repository with no code change
 * needed in [EventPublishingRepositoryProvider] itself. These tests pin that forwarding contract
 * using fake inner repositories that return a distinctive sentinel [SearchResult] — proving the
 * decorator returns the EXACT object the inner repository produced, not a silent fallback empty
 * result (which is what the old `is`-check gate used to produce).
 */
class EventPublishingRepositoryProviderFtsForwardingTest {
    @Test
    fun `EventPublishingWorkItemRepository forwards ftsSearch to the inner repository`(): Unit =
        runBlocking {
            val sentinel = SearchResult(hits = emptyList(), totalHits = 42, nextOffset = null)
            val innerWorkItemRepo = mockk<WorkItemRepository>()
            coEvery {
                innerWorkItemRepo.ftsSearch(
                    sanitizedFtsQuery = "needle",
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns sentinel

            val delegate = mockk<RepositoryProvider>()
            every { delegate.workItemRepository() } returns innerWorkItemRepo

            val provider = EventPublishingRepositoryProvider(delegate, ApiEventBus())
            // provider.workItemRepository() returns the EventPublishingWorkItemRepository decorator,
            // NOT the mocked inner repo directly — this is the exact shape production code sees
            // when the REST API is enabled.
            val result = provider.workItemRepository().ftsSearch(sanitizedFtsQuery = "needle")

            assertSame(
                sentinel,
                result,
                "Decorator must forward ftsSearch to the inner WorkItemRepository unchanged",
            )
        }

    @Test
    fun `EventPublishingNoteRepository forwards ftsSearch to the inner repository`(): Unit =
        runBlocking {
            val sentinel = SearchResult(hits = emptyList(), totalHits = 7, nextOffset = null)
            val innerNoteRepo = mockk<NoteRepository>()
            coEvery {
                innerNoteRepo.ftsSearch(
                    sanitizedFtsQuery = "needle",
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns sentinel

            val delegate = mockk<RepositoryProvider>()
            every { delegate.noteRepository() } returns innerNoteRepo

            val provider = EventPublishingRepositoryProvider(delegate, ApiEventBus())
            val result = provider.noteRepository().ftsSearch(sanitizedFtsQuery = "needle")

            assertSame(
                sentinel,
                result,
                "Decorator must forward ftsSearch to the inner NoteRepository unchanged",
            )
        }
}

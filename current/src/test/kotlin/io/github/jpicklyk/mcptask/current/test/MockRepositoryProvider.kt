package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/**
 * Creates a fully-mocked RepositoryProvider with individual repository mocks accessible.
 * Eliminates the 10+ lines of MockK boilerplate repeated across tool tests.
 */
class MockRepositoryProvider {
    val workItemRepo: WorkItemRepository = mockk()
    val noteRepo: NoteRepository = mockk()
    val depRepo: DependencyRepository = mockk()
    val roleTransitionRepo: RoleTransitionRepository = mockk()
    val workTreeExecutor: WorkTreeExecutor = mockk()
    val provider: RepositoryProvider = mockk()

    init {
        every { provider.workItemRepository() } returns workItemRepo
        every { provider.noteRepository() } returns noteRepo
        every { provider.dependencyRepository() } returns depRepo
        every { provider.roleTransitionRepository() } returns roleTransitionRepo
        every { provider.database() } returns null
        every { provider.workTreeExecutor() } returns workTreeExecutor
        // Default: noteRepo returns empty lists for any query
        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())
    }

    /** Build a ToolExecutionContext with optional schema and status label services. */
    fun context(
        noteSchemaService: NoteSchemaService = NoOpNoteSchemaService,
        statusLabelService: StatusLabelService = NoOpStatusLabelService
    ): ToolExecutionContext =
        ToolExecutionContext(provider, noteSchemaService, statusLabelService)
}

package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.mockk.*

/**
 * Creates a fully-mocked RepositoryProvider with individual repository mocks accessible.
 * Eliminates the 10+ lines of MockK boilerplate repeated across tool tests.
 */
class MockRepositoryProvider {
    val workItemRepo: WorkItemRepository = mockk()
    val noteRepo: NoteStore = mockk()
    val depRepo: DependencyStore = mockk()
    val roleTransitionRepo: TransitionStore = mockk()
    val projectConfigRepo: ProjectConfigStore = mockk()
    val planDocumentRepo: PlanDocumentStore = mockk()
    val resourceLeaseRepo: LeaseStore = mockk()
    val idempotencyRepo = InMemoryIdempotencyStore()
    val eventStore = InMemoryEventStore()
    val provider: RepositoryProvider = mockk()

    init {
        every { provider.workItemRepository() } returns workItemRepo
        every { provider.noteRepository() } returns noteRepo
        every { provider.dependencyRepository() } returns depRepo
        every { provider.roleTransitionRepository() } returns roleTransitionRepo
        every { provider.projectConfigRepository() } returns projectConfigRepo
        every { provider.planDocumentRepository() } returns planDocumentRepo
        every { provider.resourceLeaseRepository() } returns resourceLeaseRepo
        every { provider.idempotencyStore() } returns idempotencyRepo
        every { provider.eventStore() } returns eventStore
        // The narrow work-item stores are the same mock as the composite (the interface defaults do the same).
        every { provider.itemStore() } returns workItemRepo
        every { provider.hierarchyStore() } returns workItemRepo
        every { provider.claimStore() } returns workItemRepo
        every { provider.searchIndex() } returns workItemRepo
        // Default: noteRepo returns empty lists for any query
        coEvery { noteRepo.findByItemId(any()) } returns emptyList()
        coEvery { noteRepo.findByItemId(any(), any()) } returns emptyList()
        // P11: the advance and its previews read dependency rows through findByItemId, blocker roles through
        // findByIds and lease contention through findActiveByKeys. Default them onto the suite's own
        // per-direction / getById stubs (a test's later stub for the same call still wins).
        AdvanceMockStores.stubDependencyUnion(depRepo)
        coEvery { workItemRepo.findByIds(any()) } coAnswers {
            firstArg<Set<java.util.UUID>>().mapNotNull { id -> runCatching { workItemRepo.getById(id) }.getOrNull() }
        }
        coEvery { resourceLeaseRepo.findActiveByKeys(any()) } returns emptyList()
        // P14: ClaimService reads the item before a claim write, the agent's other claims, the roots of its event
        // rows, and the lapsed lease rows. Defaults: nothing held, nothing lapsed, items are their own root.
        coEvery { workItemRepo.getById(any()) } returns null
        coEvery { workItemRepo.findHeldBy(any()) } returns emptyList()
        coEvery { workItemRepo.findLapsedClaims() } returns emptyList()
        coEvery { workItemRepo.findAncestorChains(any()) } returns emptyMap()
        coEvery { resourceLeaseRepo.findLapsed(any(), any()) } returns emptyList()
        coEvery { resourceLeaseRepo.deleteLapsed(any()) } returns 0
        coEvery { resourceLeaseRepo.findActiveForItem(any()) } returns emptyList()
        coEvery { resourceLeaseRepo.findAllActive() } returns emptyList()
    }

    /** Build a ToolExecutionContext with optional schema, status label, and actor verifier services. */
    fun context(
        noteSchemaService: NoteSchemaService = NoOpNoteSchemaService,
        statusLabelService: StatusLabelService = NoOpStatusLabelService,
        actorVerifier: ActorVerifier = NoOpActorVerifier
    ): ToolExecutionContext = ToolExecutionContext(provider, noteSchemaService, statusLabelService, actorVerifier = actorVerifier)
}

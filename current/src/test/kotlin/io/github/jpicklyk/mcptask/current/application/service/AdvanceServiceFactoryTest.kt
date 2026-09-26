package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ServiceBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenarios S1 and S2 map to this file per the test-plan's file list. Oracle for S1:
 * `task-scope`'s Part A `AdvanceServiceFactory.forItem` body, `statusLabelService =
 * configResolver.rootBoundStatusLabels(item.rootId, trigger)` — a rooted item must see the
 * PER-ROOT status label for the trigger, and a rootless item must fall through to the global
 * label, exactly as [io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolverTest]
 * already characterizes for `resolveStatusLabels`/Q11. Oracle for S2: the same body assigns
 * `resourceLeasesEnforced = resourceLeasesEnforced()` — a direct call inside `forItem`, so a
 * counting lambda must be invoked once per `forItem` call, not once at construction.
 *
 * NEW-SURFACE (both scenarios): [AdvanceServiceFactory] is introduced by this feature. No source
 * revert can yield behavioral red; per the frozen plan, S1's substitute is "forItem passes
 * rootId=null" (an orchestrator-run mutation) and S2's is "read once in init" (likewise).
 */
class AdvanceServiceFactoryTest {
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var noteRepo: NoteRepository

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        roleTransitionRepo = mockk()
        depRepo = mockk()
        noteRepo = mockk()

        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()
    }

    private fun makeItem(
        role: Role = Role.QUEUE,
        rootId: UUID? = null,
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "AdvanceServiceFactory item",
            role = role,
            rootId = rootId,
            depth = 0,
        )

    private fun writeGlobalYaml(content: String): java.nio.file.Path {
        val path = Files.createTempFile("advance-service-factory-global", ".yaml")
        Files.writeString(path, content)
        return path
    }

    // ──────────────────────────────────────────────
    // S1 — statusLabelService binds to item.rootId
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - forItem binds the resolved status label to the item's rootId, per-root over global`(): Unit =
        runBlocking {
            val globalPath = writeGlobalYaml("status_labels:\n  start: \"g-s\"\n")
            val globalLookup = ServiceBackedGlobalLookup(YamlWorkItemSchemaService(globalPath), YamlStatusLabelService(globalPath))

            val prRoot = UUID.randomUUID()
            val prDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), statusLabels = mapOf("start" to "pr-s"))
            val perRoot =
                object : PerRootConfigSource {
                    override suspend fun layer(rootId: UUID): ConfigLayer? =
                        if (rootId == prRoot) ConfigLayer(prDoc, "pr-fp", ConfigSource.PER_ROOT) else null
                }
            val resolver = EffectiveConfigResolver(globalLookup, perRoot)
            val factory =
                AdvanceServiceFactory(
                    workItemRepository = workItemRepo,
                    roleTransitionRepository = roleTransitionRepo,
                    dependencyRepository = depRepo,
                    noteRepository = noteRepo,
                    resourceLeaseRepository = null,
                    configResolver = resolver,
                    resourceLeasesEnforced = { false },
                )

            val prItem = makeItem(role = Role.QUEUE, rootId = prRoot)
            val prService = factory.forItem(prItem, "start")
            val prOutcome =
                prService.advance(prItem, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)
            val prSuccess = assertIs<AdvanceOutcome.Success>(prOutcome)
            assertEquals("pr-s", prSuccess.result.statusLabel, "a rooted item must see the per-root status label, not the global one")

            val rootlessItem = makeItem(role = Role.QUEUE, rootId = null)
            val rootlessService = factory.forItem(rootlessItem, "start")
            val rootlessOutcome =
                rootlessService.advance(
                    rootlessItem,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                )
            val rootlessSuccess = assertIs<AdvanceOutcome.Success>(rootlessOutcome)
            assertEquals("g-s", rootlessSuccess.result.statusLabel, "a rootless item must fall through to the global status label")
        }

    // ──────────────────────────────────────────────
    // S2 — resourceLeasesEnforced is read fresh per forItem call
    // ──────────────────────────────────────────────

    @Test
    fun `S2 - resourceLeasesEnforced is invoked once per forItem call, not memoized at construction`(): Unit =
        runBlocking {
            var calls = 0
            val globalLookup = ServiceBackedGlobalLookup(NoOpNoteSchemaService, NoOpStatusLabelService)
            val resolver = EffectiveConfigResolver(globalLookup, null)
            val factory =
                AdvanceServiceFactory(
                    workItemRepository = workItemRepo,
                    roleTransitionRepository = roleTransitionRepo,
                    dependencyRepository = depRepo,
                    noteRepository = noteRepo,
                    resourceLeaseRepository = null,
                    configResolver = resolver,
                    resourceLeasesEnforced = {
                        calls++
                        false
                    },
                )
            val item = makeItem(role = Role.QUEUE, rootId = null)

            factory.forItem(item, "start")
            factory.forItem(item, "start")

            assertEquals(2, calls, "resourceLeasesEnforced must be read fresh on every forItem call, not once at construction")
        }
}

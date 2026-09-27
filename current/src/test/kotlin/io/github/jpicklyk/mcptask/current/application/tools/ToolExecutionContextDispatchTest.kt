package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolExecutionContext.resolveDispatchProfile] (resolved-schema overload) /
 * [ToolExecutionContext.resolveDispatchProfiles] — the dispatch-trait resolution layer (B1,
 * dispatch trait dimension). Uses the 3-arg `resolveDispatchProfile(item, role, resolvedSchema)`
 * overload throughout: it is the overload the tools (AdvanceItemTool, GetContextTool,
 * QueryItemsTool) actually use (per the pinned contract, P7), and it isolates the dispatch
 * RESOLUTION algorithm under test here from the separate schema LOOKUP algorithm already covered
 * by [ToolExecutionContextResolveSchemaTest]. Mirrors the fixture and mocking conventions of
 * [ToolExecutionContextResourceMergeTest], the resources-dimension sibling, and of
 * [ToolExecutionContextResolveSchemaTest] for the P8 no-cross-call regression tests.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and P4/P5/P7/P8 in the item's `task-scope` note — never from reading
 * ToolExecutionContext's source.
 *
 * Arbitration (case 2, orchestrator arbitration on commit f3fec87): the original version of this
 * file exercised the 2-arg convenience overload `resolveDispatchProfile(item, role)` throughout.
 * That overload internally resolves the schema first (via `resolveSchema`, which calls
 * `getSchemaForType` / `getSchemaForTags` / `getTraitNotes`, and — when `item.rootId` is set and a
 * `PerRootConfigService` is wired — `PerRootConfigService.getSnapshot`), so it reached calls the
 * strict `noteSchemaService` mock here didn't stub, producing `MockKException` in 11 of 12 tests.
 * The 12th (S10 "no traits") failed the same way for a distinct reason: P7's "empty trait list ->
 * null without fetching a per-root snapshot" guarantee belongs to the 3-arg overload, not the 2-arg
 * one, which fetches a snapshot via `resolveSchema` regardless. Construction fix: switch every test
 * to the 3-arg overload with a directly-constructed `WorkItemSchema` (or `null` for schema-free
 * scenarios) — no assertion or oracle changed.
 */
class ToolExecutionContextDispatchTest {
    private lateinit var noteSchemaService: NoteSchemaService
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        noteSchemaService = mockk()
        every { noteSchemaService.getTraitDispatch(any()) } returns emptyMap()

        val repoProvider = mockk<RepositoryProvider>(relaxed = true)
        context = ToolExecutionContext(repoProvider, noteSchemaService)
    }

    private fun makeItem(
        properties: String? = null,
        rootId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = "feature-task",
            properties = properties,
            rootId = rootId,
            depth = 0
        )

    /** A resolved-schema fixture for the 3-arg overload — resolveSchema/getSchemaFor* are never called. */
    private fun schema(defaultTraits: List<String> = emptyList()): WorkItemSchema =
        WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = defaultTraits)

    // ──────────────────────────────────────────────
    // S5 — defaultTraits resolve dispatch; a schema-free item still honors its properties traits
    // (P5, parity with resources)
    // ──────────────────────────────────────────────

    @Test
    fun `S5 resolveDispatchProfile resolves a WORK profile from a type's defaultTraits`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("delegated")))

            assertEquals(DispatchProfile(agent = "task-orchestrator:implementer"), profile)
        }

    @Test
    fun `S5 resolveDispatchProfile resolves the same profile for a schema-free item via properties traits`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            val item = makeItem(properties = """{"traits": ["delegated"]}""")
            val profile = context.resolveDispatchProfile(item, Role.WORK, resolvedSchema = null)

            assertEquals(DispatchProfile(agent = "task-orchestrator:implementer"), profile)
        }

    // ──────────────────────────────────────────────
    // S6 — an item-level trait's profile wins over a defaultTraits trait's profile for the same
    // role (item traits precede defaultTraits, the reverse of note merging)
    // ──────────────────────────────────────────────

    @Test
    fun `S6 an item-level trait's profile wins over a defaultTraits trait's profile for the same role`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "team-default"))
            every { noteSchemaService.getTraitDispatch("x") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "a"))

            val item = makeItem(properties = """{"traits": ["x"]}""")
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("delegated")))

            assertEquals("a", profile?.agent)
        }

    // ──────────────────────────────────────────────
    // S7 — first trait-with-a-profile-for-the-role wins; a trait lacking an entry for the role is
    // skipped over, falling through to the next trait in order.
    // ──────────────────────────────────────────────

    @Test
    fun `S7 the first defaultTraits trait with a WORK profile wins over a later one`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("trait-a") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "a"))
            every { noteSchemaService.getTraitDispatch("trait-b") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "b"))

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("trait-a", "trait-b")))

            assertEquals("a", profile?.agent)
        }

    @Test
    fun `S7 a trait with no entry for the requested role is skipped, falling through to the next trait`() =
        runBlocking {
            // trait-a declares only REVIEW -- no WORK entry at all.
            every { noteSchemaService.getTraitDispatch("trait-a") } returns
                mapOf(Role.REVIEW to DispatchProfile(agent = "a-reviewer"))
            every { noteSchemaService.getTraitDispatch("trait-b") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "b"))

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("trait-a", "trait-b")))

            assertEquals("b", profile?.agent)
        }

    // ──────────────────────────────────────────────
    // S8 — the winning trait's whole profile is used; a later trait's fields never merge in.
    // ──────────────────────────────────────────────

    @Test
    fun `S8 the winning trait's whole profile is used, never merged field-by-field with a later trait`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("t1") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "a"))
            every { noteSchemaService.getTraitDispatch("t2") } returns
                mapOf(Role.WORK to DispatchProfile(effort = "high"))

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("t1", "t2")))

            assertEquals(DispatchProfile(agent = "a"), profile)
            assertNull(profile?.effort, "t2's effort must not merge into t1's winning profile")
        }

    // ──────────────────────────────────────────────
    // S9 — per-root layering: a per-root trait dispatch map is that trait's COMPLETE definition
    // for the root (trait-level shadow, like resources) — no fall-through to global per role.
    // ──────────────────────────────────────────────

    @Test
    fun `S9 a per-root trait dispatch map wins outright over the global definition for that trait`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            val perRootProfile = DispatchProfile(agent = "p")
            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitDispatch = mapOf("delegated" to mapOf(Role.WORK to perRootProfile)),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(rootId = rootId)
            val profile = ctx.resolveDispatchProfile(item, Role.WORK, schema(listOf("delegated")))

            assertEquals(perRootProfile, profile)
            verify(exactly = 0) { noteSchemaService.getTraitDispatch("delegated") }
        }

    @Test
    fun `S9 a trait absent from the per-root snapshot's traitDispatch falls through to the global definition`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitDispatch = emptyMap(),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(rootId = rootId)
            val profile = ctx.resolveDispatchProfile(item, Role.WORK, schema(listOf("delegated")))

            assertEquals("task-orchestrator:implementer", profile?.agent)
        }

    @Test
    fun `S9 a per-root trait map lacking the requested role does not fall through to the global entry for that role`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            // Global defines WORK -- must NOT be used once the per-root layer supplies this trait.
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitDispatch = mapOf("delegated" to mapOf(Role.REVIEW to DispatchProfile(agent = "reviewer-only"))),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(rootId = rootId)
            val profile = ctx.resolveDispatchProfile(item, Role.WORK, schema(listOf("delegated")))

            assertNull(profile, "a per-root REVIEW-only map must not fall through to the global WORK entry")
        }

    // ──────────────────────────────────────────────
    // S10 — no dispatch trait at all resolves null; an empty trait list short-circuits before any
    // per-root snapshot fetch (P7); a role the trait doesn't declare resolves null; an unknown
    // trait name is skipped silently.
    // ──────────────────────────────────────────────

    @Test
    fun `S10 an item with no traits at all resolves null without fetching a per-root snapshot`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            // Empty on both sides: no item-level traits (no properties) and no defaultTraits.
            val item = makeItem(rootId = rootId)
            val profile = ctx.resolveDispatchProfile(item, Role.WORK, schema(emptyList()))

            assertNull(profile)
            coVerify(exactly = 0) { perRoot.layer(any()) }
        }

    @Test
    fun `S10 a trait with a dispatch entry resolves null for a role it does not declare (QUEUE)`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(
                    Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                    Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high")
                )

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.QUEUE, schema(listOf("delegated")))

            assertNull(profile, "the pinned contract declares queue/work/review only -- no QUEUE entry ever resolves")
        }

    @Test
    fun `S10 an unknown trait name is skipped silently, resolution continues to the next trait`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("no-such-trait") } returns emptyMap()
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            val item = makeItem()
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("no-such-trait", "delegated")))

            assertEquals("task-orchestrator:implementer", profile?.agent)
        }

    // ──────────────────────────────────────────────
    // Probe: a trait present both as an item trait AND in defaultTraits resolves at its
    // item-trait position — order = (itemTraits + defaultTraits).distinct(), item-first.
    // ──────────────────────────────────────────────

    @Test
    fun `probe -- a trait present both as an item trait and in defaultTraits resolves at its item-trait position`() =
        runBlocking {
            every { noteSchemaService.getTraitDispatch("d") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "d-agent"))
            every { noteSchemaService.getTraitDispatch("t") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "t-agent"))

            val item = makeItem(properties = """{"traits": ["t"]}""")
            val profile = context.resolveDispatchProfile(item, Role.WORK, schema(listOf("d", "t")))

            assertEquals("t-agent", profile?.agent, "order = [t, d, t].distinct() = [t, d] -- t wins")
        }

    // ──────────────────────────────────────────────
    // P8 — resolveSchema / resolveResourceRequirements must never touch getTraitDispatch. Unaffected
    // by the arbitration fix above: these call resolveSchema/resolveResourceRequirements directly,
    // never resolveDispatchProfile. Mirrors the exact fixtures of ToolExecutionContextResolveSchemaTest's
    // "merges trait notes after base schema notes" test and ToolExecutionContextResourceMergeTest's
    // "no traits at all" test, each already run against a strict mockk() that doesn't stub
    // getTraitDispatch.
    // ──────────────────────────────────────────────

    @Test
    fun `P8 resolveSchema never calls getTraitDispatch`() =
        runBlocking {
            val strictService = mockk<NoteSchemaService>()
            every { strictService.getSchemaForType(any()) } returns null
            every { strictService.getDefaultTraits(any()) } returns emptyList()
            every { strictService.getTraitNotes(any()) } returns null

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(NoteSchemaEntry(key = "work-note", role = Role.WORK, required = true, description = "Work note")),
                    defaultTraits = listOf("needs-security-review")
                )
            every { strictService.getSchemaForType("feature-task") } returns baseSchema
            every { strictService.getTraitNotes("needs-security-review") } returns
                listOf(NoteSchemaEntry(key = "security-assessment", role = Role.WORK, required = true, description = "Trait note"))
            every { strictService.getDefaultTraits("feature-task") } returns listOf("needs-security-review")

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, strictService)
            val item = makeItem()

            val result = ctx.resolveSchema(item)

            assertEquals(2, result!!.notes.size, "sanity: the merge itself must still succeed")
            verify(exactly = 0) { strictService.getTraitDispatch(any()) }
        }

    @Test
    fun `P8 resolveResourceRequirements never calls getTraitDispatch`() =
        runBlocking {
            val strictService = mockk<NoteSchemaService>()
            every { strictService.getSchemaForType(any()) } returns null
            every { strictService.getSchemaForTags(any()) } returns null
            every { strictService.getTraitResources(any()) } returns emptyList()
            every { strictService.getResourceRegistry() } returns emptyMap()
            every { strictService.getSchemaForType("feature-task") } returns
                WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = emptyList())

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, strictService)
            val item = makeItem()

            val result = ctx.resolveResourceRequirements(item)

            assertTrue(result.isEmpty(), "sanity: the no-traits resource path must still succeed")
            verify(exactly = 0) { strictService.getTraitDispatch(any()) }
        }
}

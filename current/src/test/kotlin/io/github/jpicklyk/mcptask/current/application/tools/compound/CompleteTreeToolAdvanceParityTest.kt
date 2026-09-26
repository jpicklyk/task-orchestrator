package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.test.TestStatusLabelService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Independent test-author coverage for item 3e455253 (complete_tree via AdvanceService).
 *
 * Oracles: `current/docs/api-reference.md` `advance_item` + `complete_tree` sections, and
 * `AdvanceService` KDoc (steps 1-7), per the frozen `test-plan` note on this item. Scenario ids
 * (S1, S2, S5-S7, S9-S11 + probes) match that note; do not renumber.
 *
 * Per the test-author blindness rule this file was written against public signatures and
 * declarations only (AdvanceService, AdvanceFailure, AdvanceResult, RoleTransitionHandler,
 * ToolExecutionContext, ActorParsing, existing test harnesses) plus CompleteTreeTool's own public
 * `description`/`parameterSchema` contract text — never the implementer's changed function bodies.
 */
class CompleteTreeToolAdvanceParityTest {
    private lateinit var tool: CompleteTreeTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    @BeforeEach
    fun setUp() {
        tool = CompleteTreeTool()
        workItemRepo = mockk()
        depRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()

        repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.resourceLeaseRepository() } returns mockk(relaxed = true)
        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }

        context = ToolExecutionContext(repoProvider)
    }

    private fun contextWithPerRootLabels(
        labelService: StatusLabelService,
        perRoot: PerRootConfigService
    ): ToolExecutionContext = ToolExecutionContext(repoProvider, statusLabelService = labelService, perRootConfigService = perRoot)

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Item",
        role: Role = Role.QUEUE,
        tags: String? = null,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0,
        rootId: UUID? = null,
        claimedBy: String? = null,
        claimExpiresAt: Instant? = null
    ): WorkItem {
        // claimedAt must never be after claimExpiresAt (WorkItem.validate() claim invariant) — when
        // the caller supplies an expiry (including one already in the past, for expired-claim
        // scenarios), anchor claimedAt safely before it rather than always using "now".
        val claimedAt =
            when {
                claimedBy == null -> null
                claimExpiresAt != null -> claimExpiresAt.minusSeconds(900)
                else -> Instant.now()
            }
        return WorkItem(
            id = id,
            title = title,
            role = role,
            tags = tags,
            parentId = parentId,
            depth = depth,
            rootId = rootId,
            claimedBy = claimedBy,
            claimedAt = claimedAt,
            claimExpiresAt = claimExpiresAt,
            originalClaimedAt = claimedAt
        )
    }

    private fun gatedContext(requiredKey: String = "acceptance-criteria"): ToolExecutionContext {
        val schemaEntries =
            listOf(
                NoteSchemaEntry(key = requiredKey, role = Role.QUEUE, required = true, description = "AC")
            )
        val noteSchemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                    if ("feature-task" in tags) schemaEntries else null
            }
        val gatedRepoProvider = mockk<RepositoryProvider>()
        every { gatedRepoProvider.workItemRepository() } returns workItemRepo
        every { gatedRepoProvider.dependencyRepository() } returns depRepo
        every { gatedRepoProvider.noteRepository() } returns noteRepo
        every { gatedRepoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { gatedRepoProvider.resourceLeaseRepository() } returns mockk(relaxed = true)
        return ToolExecutionContext(gatedRepoProvider, noteSchemaService)
    }

    private fun buildItemIdsParams(
        itemIds: List<UUID>,
        trigger: String = "complete",
        actorId: String? = null,
        actorKind: String = "subagent",
        requestId: String? = null
    ): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it.toString())) } })
            put("trigger", JsonPrimitive(trigger))
            if (actorId != null) {
                put(
                    "actor",
                    buildJsonObject {
                        put("id", JsonPrimitive(actorId))
                        put("kind", JsonPrimitive(actorKind))
                    }
                )
            }
            if (requestId != null) put("requestId", JsonPrimitive(requestId))
        }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response, got: $obj")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray = extractData(result)["results"]!!.jsonArray

    private fun extractSummary(result: JsonElement): JsonObject = extractData(result)["summary"]!!.jsonObject

    private fun resultFor(
        result: JsonElement,
        itemId: UUID
    ): JsonObject =
        extractResults(result)
            .map { it.jsonObject }
            .first { it["itemId"]!!.jsonPrimitive.content == itemId.toString() }

    // ──────────────────────────────────────────────
    // S1 — actor attribution is recorded on the persisted audit row
    // (today: RoleTransition.actorClaim is null — this is the bug being fixed)
    // ──────────────────────────────────────────────

    @Test
    fun `S1 actor claim passed to complete_tree is persisted on the RoleTransition audit row`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Unclaimed Item", role = Role.QUEUE)

            val transitionSlot = slot<RoleTransition>()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId), actorId = "agent-alpha")
            val result = tool.execute(params, context)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "Expected item to complete: $r")

            assertTrue(transitionSlot.isCaptured, "roleTransitionRepo.create was never called")
            assertEquals(
                "agent-alpha",
                transitionSlot.captured.actorClaim?.id,
                "Audit row must carry the actor claim passed to complete_tree, not null"
            )
        }

    // ──────────────────────────────────────────────
    // S2 — per-root status_labels layering (same precedence as advance_item, PER TRIGGER)
    // ──────────────────────────────────────────────

    @Test
    fun `S2 per-root status_labels override the global label on complete`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = mapOf("complete" to "shipped"),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )
            val globalLabels = TestStatusLabelService(mapOf("complete" to "done"))
            val customContext = contextWithPerRootLabels(globalLabels, perRoot)

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE, rootId = rootId)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId))
            val result = tool.execute(params, customContext)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(
                "shipped",
                r["statusLabel"]!!.jsonPrimitive.content,
                "Per-root status_labels must win over the global label, per-trigger, exactly as advance_item does"
            )
        }

    // ──────────────────────────────────────────────
    // S5 — ownership rejection reported the way advance_item reports it; in-set dependents skipped
    // ──────────────────────────────────────────────

    @Test
    fun `S5 child claimed by another actor is not completed and its in-set dependent is skipped`(): Unit =
        runBlocking {
            val idB = UUID.randomUUID()
            val idC = UUID.randomUUID()
            val itemB =
                makeItem(
                    id = idB,
                    title = "Claimed",
                    role = Role.QUEUE,
                    claimedBy = "agent-alpha",
                    claimExpiresAt = Instant.now().plusSeconds(600)
                )
            val itemC = makeItem(id = idC, title = "Dependent", role = Role.QUEUE)
            val depBtoC = Dependency(fromItemId = idB, toItemId = idC, type = DependencyType.BLOCKS)

            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.getById(idC) } returns Result.Success(itemC)
            every { depRepo.findByToItemId(idB) } returns emptyList()
            every { depRepo.findByFromItemId(idB) } returns emptyList()
            every { depRepo.findByToItemId(idC) } returns listOf(depBtoC)
            every { depRepo.findByFromItemId(idC) } returns emptyList()
            // Deliberately NOT stubbing workItemRepo.update / roleTransitionRepo.create — a
            // regression that applied the transition anyway would fail this test with a strict
            // MockK "no answer found" error.

            val params = buildItemIdsParams(listOf(idB, idC), actorId = "agent-beta")
            val result = tool.execute(params, context)

            val rB = resultFor(result, idB)
            assertFalse(rB["applied"]!!.jsonPrimitive.boolean)
            assertEquals("not_claim_holder", rB["errorCode"]!!.jsonPrimitive.content)
            assertEquals("permanent", rB["errorKind"]!!.jsonPrimitive.content)
            assertEquals(idB.toString(), rB["contendedItemId"]!!.jsonPrimitive.content)

            val rC = resultFor(result, idC)
            assertFalse(rC["applied"]!!.jsonPrimitive.boolean)
            assertTrue(rC["skipped"]!!.jsonPrimitive.boolean)
            assertEquals("dependency gate failed", rC["skippedReason"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // S6 — an EXPIRED claim imposes no ownership restriction (any actor may complete)
    // ──────────────────────────────────────────────

    @Test
    fun `S6 expired claim allows a different actor to complete the item`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item =
                makeItem(
                    id = itemId,
                    title = "Expired claim",
                    role = Role.QUEUE,
                    claimedBy = "agent-alpha",
                    claimExpiresAt = Instant.now().minusSeconds(600)
                )

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId), actorId = "agent-beta")
            val result = tool.execute(params, context)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "Expired claim must not block a different actor: $r")
        }

    // ──────────────────────────────────────────────
    // S7 — gate-failure reporting format matches the documented `"missing: <key>"` shape;
    // in-set dependent is skipped with the documented reason string.
    // ──────────────────────────────────────────────

    @Test
    fun `S7 gate failure reports exact missing-key format and skips in-set dependent`(): Unit =
        runBlocking {
            val idB = UUID.randomUUID()
            val idC = UUID.randomUUID()
            val itemB = makeItem(id = idB, title = "Gated", role = Role.QUEUE, tags = "feature-task")
            val itemC = makeItem(id = idC, title = "Dependent", role = Role.QUEUE)
            val depBtoC = Dependency(fromItemId = idB, toItemId = idC, type = DependencyType.BLOCKS)

            val gated = gatedContext("acceptance-criteria")

            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.getById(idC) } returns Result.Success(itemC)
            coEvery { noteRepo.findByItemId(idB) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(idB, any()) } returns Result.Success(emptyList())
            every { depRepo.findByToItemId(idB) } returns emptyList()
            every { depRepo.findByFromItemId(idB) } returns emptyList()
            every { depRepo.findByToItemId(idC) } returns listOf(depBtoC)
            every { depRepo.findByFromItemId(idC) } returns emptyList()

            val params = buildItemIdsParams(listOf(idB, idC))
            val result = tool.execute(params, gated)

            val rB = resultFor(result, idB)
            assertFalse(rB["applied"]!!.jsonPrimitive.boolean)
            val gateErrors = rB["gateErrors"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("missing: acceptance-criteria"),
                gateErrors,
                "Per api-reference.md's complete_tree example, gateErrors entries are exactly \"missing: <key>\""
            )

            val rC = resultFor(result, idC)
            assertTrue(rC["skipped"]!!.jsonPrimitive.boolean)
            assertEquals("dependency gate failed", rC["skippedReason"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // S9 — cancel bypasses the note gate but NOT the ownership check (two independent calls,
    // since complete_tree's actor is a single top-level claim shared by every item in one call).
    // ──────────────────────────────────────────────

    @Test
    fun `S9a cancel trigger bypasses the note gate when the actor matches the claim holder`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item =
                makeItem(
                    id = itemId,
                    title = "Claimed + gated",
                    role = Role.QUEUE,
                    tags = "feature-task",
                    claimedBy = "agent-alpha",
                    claimExpiresAt = Instant.now().plusSeconds(600)
                )
            val gated = gatedContext("acceptance-criteria")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()
            // Deliberately NOT stubbing noteRepo.findByItemId — cancel must never gate-check.

            val params = buildItemIdsParams(listOf(itemId), trigger = "cancel", actorId = "agent-alpha")
            val result = tool.execute(params, gated)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "cancel must bypass the gate: $r")
            assertNull(r["gateErrors"])
        }

    @Test
    fun `S9b cancel trigger still enforces ownership for a non-claim-holder actor`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item =
                makeItem(
                    id = itemId,
                    title = "Claimed + gated",
                    role = Role.QUEUE,
                    tags = "feature-task",
                    claimedBy = "agent-alpha",
                    claimExpiresAt = Instant.now().plusSeconds(600)
                )
            val gated = gatedContext("acceptance-criteria")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()
            // Deliberately NOT stubbing update/create/noteRepo — ownership is checked BEFORE the
            // gate (per AdvanceService step order), so none of these should ever be reached.

            val params = buildItemIdsParams(listOf(itemId), trigger = "cancel", actorId = "agent-beta")
            val result = tool.execute(params, gated)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean, "cancel must NOT bypass ownership: $r")
            assertEquals("not_claim_holder", r["errorCode"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // S10 — already-terminal items are skipped, never gate-checked, produce no audit row, and do
    // NOT propagate a skip to their dependents (regression coverage through the AdvanceService
    // refactor for a behavior the pre-refactor tool already had).
    // ──────────────────────────────────────────────

    @Test
    fun `S10 already terminal item is skipped with no audit row and its dependent still completes`(): Unit =
        runBlocking {
            val idA = UUID.randomUUID()
            val idB = UUID.randomUUID()
            val itemA = makeItem(id = idA, title = "Already terminal", role = Role.TERMINAL, tags = "feature-task")
            val itemB = makeItem(id = idB, title = "Dependent", role = Role.QUEUE)
            val depAtoB = Dependency(fromItemId = idA, toItemId = idB, type = DependencyType.BLOCKS)
            val gated = gatedContext("acceptance-criteria")

            coEvery { workItemRepo.getById(idA) } returns Result.Success(itemA)
            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(idA) } returns emptyList()
            every { depRepo.findByFromItemId(idA) } returns emptyList()
            every { depRepo.findByToItemId(idB) } returns listOf(depAtoB)
            every { depRepo.findByFromItemId(idB) } returns emptyList()
            // Deliberately NOT stubbing noteRepo.findByItemId(idA) — a terminal item must never be
            // gate-checked even though its schema requires a note it never got.

            val params = buildItemIdsParams(listOf(idA, idB), actorId = "agent-alpha")
            val result = tool.execute(params, gated)

            val rA = resultFor(result, idA)
            assertTrue(rA["skipped"]!!.jsonPrimitive.boolean)
            assertEquals("already terminal", rA["skippedReason"]!!.jsonPrimitive.content)
            assertNull(rA["gateErrors"])

            val rB = resultFor(result, idB)
            assertTrue(rB["applied"]!!.jsonPrimitive.boolean, "dependent of a terminal blocker must complete, not skip")

            coVerify(exactly = 0) { roleTransitionRepo.create(match { it.itemId == idA }) }
        }

    // ──────────────────────────────────────────────
    // S11 — no actor provided: legacy shape, actorClaim persists as null
    // ──────────────────────────────────────────────

    @Test
    fun `S11 no actor provided completes normally and persists a null actorClaim`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "No actor", role = Role.QUEUE)

            val transitionSlot = slot<RoleTransition>()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId)) // no actorId supplied
            val result = tool.execute(params, context)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)
            assertNull(transitionSlot.captured.actorClaim, "legacy shape: absent actor persists as null, not a default")
        }

    // ──────────────────────────────────────────────
    // PROBE — absent vs. explicit JSON null actor must be equivalent (both parse to "Absent")
    // ──────────────────────────────────────────────

    @Test
    fun `probe explicit JSON null actor behaves identically to an absent actor field`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Null actor", role = Role.QUEUE)

            val transitionSlot = slot<RoleTransition>()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params =
                buildJsonObject {
                    put("itemIds", buildJsonArray { add(JsonPrimitive(itemId.toString())) })
                    put("trigger", JsonPrimitive("complete"))
                    put("actor", JsonNull)
                }
            val result = tool.execute(params, context)

            val r = resultFor(result, itemId)
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)
            assertNull(transitionSlot.captured.actorClaim, "explicit JSON null actor must parse as Absent, same as omitting the field")
        }

    // ──────────────────────────────────────────────
    // PROBE — replay: same (actor, requestId) twice within the idempotency window returns the
    // cached response and creates exactly one audit row, not two.
    // ──────────────────────────────────────────────

    @Test
    fun `probe replaying the same actor and requestId is idempotent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Idempotent", role = Role.QUEUE)
            val requestId = UUID.randomUUID().toString()

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId), actorId = "agent-alpha", requestId = requestId)

            val firstResult = tool.execute(params, context)
            val secondResult = tool.execute(params, context)

            val r1 = resultFor(firstResult, itemId)
            val r2 = resultFor(secondResult, itemId)
            assertTrue(r1["applied"]!!.jsonPrimitive.boolean)
            assertEquals(r1, r2, "a replayed (actor, requestId) call must return the cached response byte-for-byte")

            coVerify(exactly = 1) { roleTransitionRepo.create(any()) }
        }
}

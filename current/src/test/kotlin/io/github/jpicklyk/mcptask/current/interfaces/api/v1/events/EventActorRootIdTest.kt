package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

/**
 * Independent test authorship for item f0e193b7 (ApiEvent gains `actor` and `rootId`).
 *
 * Oracles (frozen, planning phase): the item's `task-scope` note (acceptance criteria 1-7, the
 * "Where actor comes from" resolution order, the "rootId" section, the wrap-site list) and the
 * parent feature's `feature-summary` note. No oracle value is read from the implementation.
 *
 *  [AC1] each domain event carries rootId = the depth-0 ancestor; scope.left/entered old/new root.
 *  [AC2] `withEventActor(c)` around a write -> actor {c.id, c.kind lowercase, c.parent};
 *        note.upserted uses the Note's own actorClaim even when the context differs; no actor
 *        anywhere -> field absent.
 *  [AC3] deferred (in-transaction) events keep the actor/rootId captured at enqueue; rollback
 *        publishes nothing.
 *  [AC4] advance_item with two transitions by different actors -> each item.advanced carries its
 *        own actor.
 *  [AC6] unresolved event (published at 0 subscribers) replayed to an unrestricted client: no
 *        rootId; actor still present.
 *  [AC7] `proof` never appears in any frame.
 *  [WRAP] the wrap-site list: each named MCP tool puts its parsed actor around its write.
 *
 * Harness: the decorated provider over the real (SQLite in-memory) repositories, and real MCP
 * tools executed through a ToolExecutionContext built on that decorated provider (same wiring
 * CurrentMcpServer uses when the API is enabled). An UNRESTRICTED subscriber is connected before
 * every write so roots are resolved; the zero-subscriber scenarios deliberately connect none.
 */
class EventActorRootIdTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = db.repositoryProvider()

    private val agentA = ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT, parent = "orch-1")
    private val agentB = ActorClaim(id = "agent-b", kind = ActorKind.ORCHESTRATOR)

    private fun dtoA() = ActorClaimDto(id = "agent-a", kind = "subagent", parent = "orch-1")

    private fun dtoB() = ActorClaimDto(id = "agent-b", kind = "orchestrator", parent = null)

    private fun dtoPlainA() = ActorClaimDto(id = "agent-a", kind = "subagent", parent = null)

    private fun decorated(bus: ApiEventBus) = EventPublishingRepositoryProvider(repositoryProvider, bus)

    private fun toolContext(bus: ApiEventBus): ToolExecutionContext {
        // P11: item.transitioned is recorded by AdvanceService through its unit's event sink, so the unit must
        // share the bus-feeding recorder with the decorator, exactly as ServerComposition wires it.
        val (provider, unitOfWork) = eventWiredUnit(db.databaseManager, repositoryProvider, bus)
        return ToolExecutionContext(
            repositoryProvider = provider,
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            unitOfWork = unitOfWork,
        )
    }

    private fun List<ApiEvent>.one(
        type: String,
        itemId: UUID,
    ): ApiEvent {
        val matches = filter { it.event == type && it.itemId == itemId.toString() }
        assertEquals(1, matches.size, "expected exactly one $type for $itemId, got: $this")
        return matches[0]
    }

    private fun note(
        itemId: UUID,
        key: String,
        author: ActorClaim? = null,
    ): Note =
        Note(
            itemId = itemId,
            key = key,
            role = "work",
            body = "body-$key",
            actorClaim = author,
            verification = author?.let { VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop") },
        )

    private fun actorJson(
        id: String,
        kind: String = "subagent",
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
        }

    private fun assertToolSuccess(result: Any?): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "tool call must succeed, got: $obj")
        return obj
    }

    private suspend fun EventPublishingRepositoryProvider.newRoot(title: String): WorkItem =
        workItemRepository().create(WorkItem(title = title, depth = 0))!!

    private suspend fun EventPublishingRepositoryProvider.newChild(
        title: String,
        parent: WorkItem,
    ): WorkItem = workItemRepository().create(WorkItem(title = title, parentId = parent.id, depth = 1))!!

    // -------------------------------------------------------------------------
    // S1 -- rootId per event type [AC1]
    // -------------------------------------------------------------------------

    @Test
    fun `S1a item created updated advanced deleted carry the depth-0 ancestor as rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s1a", emptySet(), lastEventId = null)

            val root = provider.newRoot("R-s1a")
            val child = provider.newChild("C-s1a", root)
            val renamed = provider.workItemRepository().update(child.copy(title = "C-s1a-renamed"))!!
            // P11: an advance is AdvanceService's one unit (role change, transition row and the item.transitioned
            // event it records itself); that event projects as item.advanced.
            val (wired, unitOfWork) = eventWiredUnit(db.databaseManager, repositoryProvider, bus)
            val outcome =
                AdvanceService(
                    workItemRepository = wired.workItemRepository(),
                    roleTransitionRepository = wired.roleTransitionRepository(),
                    dependencyRepository = wired.dependencyRepository(),
                    noteRepository = wired.noteRepository(),
                    schemaResolver = { null },
                    unitOfWork = unitOfWork,
                ).advance(renamed, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)
            assertTrue(outcome is AdvanceOutcome.Success, "advance failed: $outcome")
            provider.workItemRepository().delete(child.id)

            val events = bus.drainDelivered("s1a", flow)
            val expectedRoot = root.id.toString()
            assertEquals(expectedRoot, events.one(ApiEventType.ITEM_CREATED, child.id).rootId)
            assertEquals(expectedRoot, events.one(ApiEventType.ITEM_UPDATED, child.id).rootId)
            assertEquals(expectedRoot, events.one(ApiEventType.ITEM_ADVANCED, child.id).rootId)
            assertEquals(expectedRoot, events.one(ApiEventType.ITEM_DELETED, child.id).rootId)
        }

    @Test
    fun `S1b a depth-0 item own events carry its own id as rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s1b", emptySet(), lastEventId = null)

            val root = provider.newRoot("R-s1b")

            val events = bus.drainDelivered("s1b", flow)
            assertEquals(root.id.toString(), events.one(ApiEventType.ITEM_CREATED, root.id).rootId)
        }

    @Test
    fun `S1c note upserted and note deleted carry the item root as rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s1c", emptySet(), lastEventId = null)

            val root = provider.newRoot("R-s1c")
            val child = provider.newChild("C-s1c", root)
            val saved = provider.noteRepository().upsert(note(child.id, "k-s1c"))!!
            provider.noteRepository().delete(saved.id)

            val events = bus.drainDelivered("s1c", flow)
            assertEquals(root.id.toString(), events.one(ApiEventType.NOTE_UPSERTED, child.id).rootId)
            assertEquals(root.id.toString(), events.one(ApiEventType.NOTE_DELETED, child.id).rootId)
        }

    @Test
    fun `S1d dependency added and removed carry the origin item root as rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s1d", emptySet(), lastEventId = null)

            val root = provider.newRoot("R-s1d")
            val a = provider.newChild("A-s1d", root)
            val b = provider.newChild("B-s1d", root)
            val dep = provider.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
            provider.dependencyRepository().delete(dep.id)

            val events = bus.drainDelivered("s1d", flow)
            assertEquals(root.id.toString(), events.one(ApiEventType.DEPENDENCY_ADDED, a.id).rootId)
            assertEquals(root.id.toString(), events.one(ApiEventType.DEPENDENCY_REMOVED, a.id).rootId)
        }

    // -------------------------------------------------------------------------
    // S2 -- scope.left / scope.entered rootId [AC1]
    // -------------------------------------------------------------------------

    @Test
    fun `S2 reparent emits scope left with the OLD root and scope entered with the NEW root as rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s2", emptySet(), lastEventId = null)

            val root1 = provider.newRoot("R1-s2")
            val root2 = provider.newRoot("R2-s2")
            val child = provider.newChild("C-s2", root1)
            provider.workItemRepository().update(child.copy(parentId = root2.id))

            val events = bus.drainDelivered("s2", flow)
            assertEquals(root1.id.toString(), events.one(ApiEventType.SCOPE_LEFT, child.id).rootId, "scope.left carries the OLD root")
            assertEquals(root2.id.toString(), events.one(ApiEventType.SCOPE_ENTERED, child.id).rootId, "scope.entered carries the NEW root")
        }

    // -------------------------------------------------------------------------
    // S3 -- actor from the coroutine context [AC2]
    // -------------------------------------------------------------------------

    @Test
    fun `S3a a write inside withEventActor carries that actor with lowercase kind and parent`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3a", emptySet(), lastEventId = null)

            val item = withEventActor(agentA) { provider.newRoot("X-s3a") }

            val events = bus.drainDelivered("s3a", flow)
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, item.id).actor)
        }

    @Test
    fun `S3b a write with no actor anywhere has no actor and the context does not leak past the block`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3b", emptySet(), lastEventId = null)

            val inside = withEventActor(agentA) { provider.newRoot("In-s3b") }
            val outside = provider.newRoot("Out-s3b")
            val nullClaim = withEventActor(null) { provider.newRoot("Null-s3b") }

            val events = bus.drainDelivered("s3b", flow)
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, inside.id).actor, "fixture: inside block must be attributed")
            assertNull(events.one(ApiEventType.ITEM_CREATED, outside.id).actor, "a write after the block must not inherit the actor")
            assertNull(events.one(ApiEventType.ITEM_CREATED, nullClaim.id).actor, "withEventActor(null) means no actor")
        }

    @Test
    fun `S3c two consecutive writes under different actors are each attributed to their own actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3c", emptySet(), lastEventId = null)

            val first = withEventActor(agentA) { provider.newRoot("1-s3c") }
            val second = withEventActor(agentB) { provider.newRoot("2-s3c") }

            val events = bus.drainDelivered("s3c", flow)
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, first.id).actor)
            assertEquals(dtoB(), events.one(ApiEventType.ITEM_CREATED, second.id).actor)
        }

    @Test
    fun `S3d nested withEventActor attributes the inner write to the inner actor and restores the outer afterwards`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3d", emptySet(), lastEventId = null)

            lateinit var innerItem: WorkItem
            lateinit var afterItem: WorkItem
            withEventActor(agentA) {
                withEventActor(agentB) {
                    innerItem = provider.newRoot("inner-s3d")
                }
                afterItem = provider.newRoot("after-s3d")
            }

            val events = bus.drainDelivered("s3d", flow)
            assertEquals(dtoB(), events.one(ApiEventType.ITEM_CREATED, innerItem.id).actor)
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, afterItem.id).actor)
        }

    @Test
    fun `S3e the actor survives a context switch to another dispatcher inside the block`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3e", emptySet(), lastEventId = null)

            val item =
                withEventActor(agentA) {
                    withContext(Dispatchers.IO) { provider.newRoot("io-s3e") }
                }

            val events = bus.drainDelivered("s3e", flow)
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, item.id).actor)
        }

    @Test
    fun `S3f the proof of an actor claim is never exposed on the event`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s3f", emptySet(), lastEventId = null)

            val withProof = ActorClaim(id = "agent-p", kind = ActorKind.SUBAGENT, proof = "SECRET-PROOF-TOKEN")
            val item = withEventActor(withProof) { provider.newRoot("p-s3f") }

            val events = bus.drainDelivered("s3f", flow)
            val ev = events.one(ApiEventType.ITEM_CREATED, item.id)
            assertEquals(ActorClaimDto(id = "agent-p", kind = "subagent", parent = null), ev.actor)
            val encoded = Json { explicitNulls = false }.encodeToString(ApiEvent.serializer(), ev)
            assertFalse(encoded.contains("SECRET-PROOF-TOKEN"), "proof value leaked: $encoded")
            assertFalse(encoded.contains("proof"), "a proof key leaked: $encoded")
            assertFalse(encoded.contains("verification"), "a verification key leaked: $encoded")
        }

    // -------------------------------------------------------------------------
    // S4 -- Note.actorClaim wins over the context actor [AC2]
    // -------------------------------------------------------------------------

    @Test
    fun `S4a note upserted uses the Note own actorClaim even when the context actor differs`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s4a", emptySet(), lastEventId = null)

            val item = provider.newRoot("X-s4a")
            withEventActor(agentA) { provider.noteRepository().upsert(note(item.id, "k-s4a", author = agentB)) }

            val events = bus.drainDelivered("s4a", flow)
            assertEquals(dtoB(), events.one(ApiEventType.NOTE_UPSERTED, item.id).actor)
        }

    @Test
    fun `S4b note upserted with no actorClaim falls back to the context actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s4b", emptySet(), lastEventId = null)

            val item = provider.newRoot("X-s4b")
            withEventActor(agentA) { provider.noteRepository().upsert(note(item.id, "k-s4b", author = null)) }

            val events = bus.drainDelivered("s4b", flow)
            assertEquals(dtoA(), events.one(ApiEventType.NOTE_UPSERTED, item.id).actor)
        }

    @Test
    fun `S4c note upserted with neither actorClaim nor context actor has no actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s4c", emptySet(), lastEventId = null)

            val item = provider.newRoot("X-s4c")
            provider.noteRepository().upsert(note(item.id, "k-s4c", author = null))

            val events = bus.drainDelivered("s4c", flow)
            assertNull(events.one(ApiEventType.NOTE_UPSERTED, item.id).actor)
        }

    @Test
    fun `S4d work tree notes use their own actorClaim and work tree items use the context actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s4d", emptySet(), lastEventId = null)

            val rootId = UUID.randomUUID()
            val c1Id = UUID.randomUUID()
            val c2Id = UUID.randomUUID()
            val root = WorkItem(id = rootId, title = "Tree Root s4d", depth = 0)
            val c1 = WorkItem(id = c1Id, parentId = rootId, depth = 1, title = "C1 s4d")
            val c2 = WorkItem(id = c2Id, parentId = rootId, depth = 1, title = "C2 s4d")
            val input =
                WorkTreeInput(
                    items = listOf(root, c1, c2),
                    refToItem = mapOf("R" to root, "C1" to c1, "C2" to c2),
                    deps = listOf(TreeDepSpec(fromRef = "C1", toRef = "C2", type = DependencyType.BLOCKS, unblockAt = null)),
                    notes = listOf(note(c1Id, "tree-note-s4d", author = agentB)),
                )

            withEventActor(agentA) { provider.workTreeExecutor().execute(input) }

            val events = bus.drainDelivered("s4d", flow)
            assertEquals(5, events.size, "3 item.created + 1 dependency.added + 1 note.upserted, got: $events")
            assertEquals(dtoB(), events.one(ApiEventType.NOTE_UPSERTED, c1Id).actor, "the note own actorClaim wins")
            assertEquals(dtoA(), events.one(ApiEventType.ITEM_CREATED, c1Id).actor)
            assertEquals(dtoA(), events.one(ApiEventType.DEPENDENCY_ADDED, c1Id).actor)
            assertTrue(
                events.all { it.rootId == rootId.toString() },
                "every work-tree event is in the tree root, got rootIds: ${events.map { it.rootId }}",
            )
        }

    // -------------------------------------------------------------------------
    // S5 -- deferred events keep what was captured at enqueue; rollback publishes nothing [AC3]
    // -------------------------------------------------------------------------

    @Test
    fun `S5a an event enqueued inside a transaction keeps the actor and rootId captured at enqueue`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s5a", emptySet(), lastEventId = null)

            val root = provider.newRoot("R-s5a")
            lateinit var child: WorkItem
            // The actor scope closes BEFORE the transaction commits, so a flush-time context read
            // would see no actor: the assertion below can only pass if capture happened at enqueue.
            db.unitOfWork().inUnit {
                child = withEventActor(agentA) { provider.newChild("C-s5a", root) }
            }

            val events = bus.drainDelivered("s5a", flow)
            val ev = events.one(ApiEventType.ITEM_CREATED, child.id)
            assertEquals(dtoA(), ev.actor)
            assertEquals(root.id.toString(), ev.rootId)
        }

    @Test
    fun `S5b a rolled back transaction publishes nothing even with an actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s5b", emptySet(), lastEventId = null)

            var threw = false
            try {
                db.unitOfWork().inUnit {
                    withEventActor(agentA) { provider.newRoot("X-s5b") }
                    throw IllegalStateException("forced rollback s5b")
                }
            } catch (e: IllegalStateException) {
                threw = true
            }

            assertTrue(threw, "fixture: the forced exception must propagate")
            val events = bus.drainDelivered("s5b", flow)
            assertTrue(events.isEmpty(), "rollback must publish nothing, got: $events")
            assertTrue(bus.projectedEvents().isEmpty(), "rollback must buffer nothing")
        }

    // -------------------------------------------------------------------------
    // S6 -- advance_item with two transitions under different actors [AC4]
    // -------------------------------------------------------------------------

    private fun transition(
        itemId: UUID,
        actor: JsonObject?,
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId.toString())
            put("trigger", "start")
            actor?.let { put("actor", it) }
        }

    @Test
    fun `S6 advance_item with two transitions attributes each item advanced to its own actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s6", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)

            val x = provider.newRoot("X-s6")
            val y = provider.newRoot("Y-s6")

            val result =
                AdvanceItemTool().execute(
                    buildJsonObject {
                        put(
                            "transitions",
                            buildJsonArray {
                                add(transition(x.id, actorJson("agent-a")))
                                add(transition(y.id, actorJson("agent-b", kind = "orchestrator")))
                            },
                        )
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val events = bus.drainDelivered("s6", flow)
            assertEquals(dtoPlainA(), events.one(ApiEventType.ITEM_ADVANCED, x.id).actor)
            assertEquals(dtoB(), events.one(ApiEventType.ITEM_ADVANCED, y.id).actor)
        }

    @Test
    fun `S6b a transition without an actor is not attributed to the previous transition actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s6b", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)

            val x = provider.newRoot("X-s6b")
            val y = provider.newRoot("Y-s6b")

            val result =
                AdvanceItemTool().execute(
                    buildJsonObject {
                        put(
                            "transitions",
                            buildJsonArray {
                                add(transition(x.id, actorJson("agent-a")))
                                add(transition(y.id, null))
                            },
                        )
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val events = bus.drainDelivered("s6b", flow)
            assertEquals(
                dtoPlainA(),
                events.one(ApiEventType.ITEM_ADVANCED, x.id).actor,
                "fixture: the first transition must be attributed",
            )
            assertNull(events.one(ApiEventType.ITEM_ADVANCED, y.id).actor, "an actor-less transition must have no actor")
        }

    // -------------------------------------------------------------------------
    // S7 -- zero-subscriber (unresolved) events: rootId absent, actor present, on replay [AC6]
    // -------------------------------------------------------------------------

    @Test
    fun `S7 an event published at zero subscribers replays with no rootId but with its actor`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)

            val root = provider.newRoot("R-s7")
            val w = bus.projectedEvents().last().id
            assertEquals(0, bus.subscriberCount(), "fixture: nothing may be subscribed during the write")
            val child = withEventActor(agentA) { provider.newChild("C-s7", root) }

            val flow = bus.subscribe("s7", emptySet(), lastEventId = w)
            val events = bus.drainDelivered("s7", flow)

            val ev = events.one(ApiEventType.ITEM_CREATED, child.id)
            // P8 expected-value change: the zero-subscriber limitation is gone; every row carries its root.
            assertEquals(root.id.toString(), ev.rootId, "a zero-subscriber write carries its resolved rootId since P8")
            assertEquals(dtoA(), ev.actor, "the actor is present on a zero-subscriber write")
        }

    @Test
    fun `S7b the same write with a subscriber connected carries the resolved rootId`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val root = provider.newRoot("R-s7b")
            val flow = bus.subscribe("s7b", emptySet(), lastEventId = null)

            val child = withEventActor(agentA) { provider.newChild("C-s7b", root) }

            val ev = bus.drainDelivered("s7b", flow).one(ApiEventType.ITEM_CREATED, child.id)
            assertEquals(root.id.toString(), ev.rootId, "control for S7: with a subscriber the root resolves")
            assertEquals(dtoA(), ev.actor)
        }

    // -------------------------------------------------------------------------
    // S11 -- MCP tool wrap sites [WRAP]
    // -------------------------------------------------------------------------

    private fun idOfFirstItem(r: JsonObject): UUID =
        UUID.fromString(
            (r["data"] as JsonObject)["items"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive.content,
        )

    private fun itemCreateParams(
        parent: WorkItem,
        title: String,
        actor: JsonObject?,
    ): JsonObject =
        buildJsonObject {
            put("operation", "create")
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", title)
                            put("parentId", parent.id.toString())
                        },
                    )
                },
            )
            actor?.let { put("actor", it) }
        }

    @Test
    fun `S11a manage_items create with a top-level actor attributes item created and a call without an actor has none`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s11a", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)
            val root = provider.newRoot("R-s11a")

            val withActor = assertToolSuccess(ManageItemsTool().execute(itemCreateParams(root, "With-s11a", actorJson("agent-a")), ctx))
            val without = assertToolSuccess(ManageItemsTool().execute(itemCreateParams(root, "Without-s11a", null), ctx))

            val events = bus.drainDelivered("s11a", flow)
            val a = events.one(ApiEventType.ITEM_CREATED, idOfFirstItem(withActor))
            assertEquals(dtoPlainA(), a.actor)
            assertEquals(root.id.toString(), a.rootId)
            assertNull(events.one(ApiEventType.ITEM_CREATED, idOfFirstItem(without)).actor, "no actor supplied -> field absent")
        }

    @Test
    fun `S11b manage_dependencies create with a top-level actor attributes dependency added`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s11b", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)
            val root = provider.newRoot("R-s11b")
            val a = provider.newChild("A-s11b", root)
            val b = provider.newChild("B-s11b", root)

            val result =
                ManageDependenciesTool().execute(
                    buildJsonObject {
                        put("operation", "create")
                        put(
                            "dependencies",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("fromItemId", a.id.toString())
                                        put("toItemId", b.id.toString())
                                        put("type", "BLOCKS")
                                    },
                                )
                            },
                        )
                        put("actor", actorJson("agent-a"))
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val ev = bus.drainDelivered("s11b", flow).one(ApiEventType.DEPENDENCY_ADDED, a.id)
            assertEquals(dtoPlainA(), ev.actor)
            assertEquals(root.id.toString(), ev.rootId)
        }

    @Test
    fun `S11c manage_notes delete with a top-level actor attributes note deleted`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s11c", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)
            val item = provider.newRoot("X-s11c")
            val saved = provider.noteRepository().upsert(note(item.id, "k-s11c"))!!

            val result =
                ManageNotesTool().execute(
                    buildJsonObject {
                        put("operation", "delete")
                        put("ids", JsonArray(listOf(JsonPrimitive(saved.id.toString()))))
                        put("actor", actorJson("agent-a"))
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val ev = bus.drainDelivered("s11c", flow).one(ApiEventType.NOTE_DELETED, item.id)
            assertEquals(dtoPlainA(), ev.actor)
        }

    @Test
    fun `S11d manage_notes upsert with a top-level actor attributes note upserted`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s11d", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)
            val item = provider.newRoot("X-s11d")

            val result =
                ManageNotesTool().execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", item.id.toString())
                                        put("key", "k-s11d")
                                        put("role", "work")
                                        put("body", "b")
                                    },
                                )
                            },
                        )
                        put("actor", actorJson("agent-a"))
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val ev = bus.drainDelivered("s11d", flow).one(ApiEventType.NOTE_UPSERTED, item.id)
            assertEquals(dtoPlainA(), ev.actor)
        }

    @Test
    fun `S11e claim_item with a top-level actor attributes the claim item updated`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = decorated(bus)
            val flow = bus.subscribe("s11e", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)
            val item = provider.newRoot("X-s11e")

            val result =
                ClaimItemTool().execute(
                    buildJsonObject {
                        put("claims", buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) })
                        put("actor", actorJson("agent-a"))
                        put("requestId", UUID.randomUUID().toString())
                    },
                    ctx,
                )
            assertToolSuccess(result)

            val ev = bus.drainDelivered("s11e", flow).one(ApiEventType.ITEM_UPDATED, item.id)
            assertEquals(dtoPlainA(), ev.actor)
        }

    @Test
    fun `S11f create_work_tree with a top-level actor attributes the tree item created events`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val flow = bus.subscribe("s11f", emptySet(), lastEventId = null)
            val ctx = toolContext(bus)

            val result =
                CreateWorkTreeTool().execute(
                    buildJsonObject {
                        put("root", buildJsonObject { put("title", "Tree-s11f") })
                        put(
                            "children",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("ref", "c1")
                                        put("title", "Child-s11f")
                                    },
                                )
                            },
                        )
                        put("actor", actorJson("agent-a"))
                    },
                    ctx,
                )
            val data = assertToolSuccess(result)["data"] as JsonObject
            val rootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val childId = UUID.fromString((data["children"] as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content)

            val events = bus.drainDelivered("s11f", flow)
            assertEquals(dtoPlainA(), events.one(ApiEventType.ITEM_CREATED, rootId).actor)
            assertEquals(dtoPlainA(), events.one(ApiEventType.ITEM_CREATED, childId).actor)
            assertEquals(rootId.toString(), events.one(ApiEventType.ITEM_CREATED, childId).rootId)
        }

    // -------------------------------------------------------------------------
    // S13 -- the recorded row carries actor/rootId through to the projected event
    // -------------------------------------------------------------------------

    @Test
    fun `S13 a recorded event's actor and rootId are carried onto the projected event`(): Unit =
        runBlocking {
            val store = repositoryProvider.eventStore()
            val bus = ApiEventBus(source = store)
            val recorder = EventRecorder(store, listener = DeferredEventPublisher(bus))
            val itemId = UUID.randomUUID()
            val rootId = UUID.randomUUID()

            withEventActor(agentA) { recorder.record(DomainEvent.ItemUpdated(itemId, rootId, listOf("title"))) }

            val snapshot = bus.projectedEvents()
            assertEquals(1, snapshot.size)
            assertEquals(dtoA(), snapshot[0].actor)
            assertEquals(rootId.toString(), snapshot[0].rootId)
        }

    @Test
    fun `S13b a recorded event with no actor projects a null actor and its own root`(): Unit =
        runBlocking {
            val store = repositoryProvider.eventStore()
            val bus = ApiEventBus(source = store)
            val recorder = EventRecorder(store, listener = DeferredEventPublisher(bus))
            val itemId = UUID.randomUUID()

            recorder.record(DomainEvent.ItemUpdated(itemId, itemId, listOf("title")))

            val snapshot = bus.projectedEvents()
            assertEquals(1, snapshot.size)
            assertNull(snapshot[0].actor)
            // P8: every row carries a root (a root item uses its own id), so rootId is never absent.
            assertEquals(itemId.toString(), snapshot[0].rootId)
        }
}

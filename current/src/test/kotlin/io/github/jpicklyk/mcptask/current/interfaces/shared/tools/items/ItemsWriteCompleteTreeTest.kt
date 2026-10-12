package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `items_write` `complete_tree` (item c820b3d0, test-author seat): scenarios S6, S10 to S15, S18 and the complete_tree
 * halves of S12/S13 of the frozen test-plan, all NEW-SURFACE, through the real dispatcher in the orchestration profile (the op is
 * hidden from the knowledge profile) and, for scope, a real MCP client/server pair.
 *
 * Oracles: task-scope B3 (element and entry shapes), B4 (order, skip rules, scope order), AC5 to AC7, decisions D3 to D6; plan
 * flag (d) (an already-terminal skip carries no `error`). The cascade fixture follows the settled 3.x rule recorded in the base
 * tests (a parent cascades to terminal when it is in WORK and its last child terminalizes; a queue parent does not).
 *
 * Every absence assertion is paired with a control through the same call shape that does write.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteCompleteTreeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val c2 = UUID.fromString("a1000003-0000-4000-8000-0000000000c2")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val d2 = UUID.fromString("b2000002-0000-4000-8000-0000000000d2")
    private val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")
    private val farFuture: Instant = Instant.parse("2099-01-01T00:00:00Z")

    private val advanceCaps = setOf(ApiCapability.READ, ApiCapability.ADVANCE)
    private val rootScoped = ParityToken("ct-root", advanceCaps, rootIds = setOf(r1))
    private val tagScoped = ParityToken("ct-tag", advanceCaps, tagsInclude = setOf("alpha"))

    private suspend fun IwKit.complete(
        vararg fields: Pair<String, JsonElement>,
        actor: JsonObject? = IW_ACTOR
    ): Dispatched = callIn(ToolProfile.ORCHESTRATION, twComplete(*fields), actor = actor)

    private suspend fun IwKit.dep(
        from: UUID,
        to: UUID,
        type: DependencyType = DependencyType.BLOCKS
    ) {
        rig.raw.dependencyRepository().create(Dependency(fromItemId = from, toItemId = to, type = type))
    }

    private suspend fun IwKit.role(id: UUID): Role = assertNotNull(item(id), "item $id").role!!

    /** An item held by [by] with a live claim (expiry in 2099), seeded directly like the advance tests do. */
    private suspend fun IwKit.claimed(
        title: String,
        parent: WorkItem? = null,
        by: String = "other-agent-77"
    ): WorkItem {
        val claimedAt = farFuture.minusSeconds(60)
        val item =
            WorkItem(
                title = title,
                role = Role.QUEUE,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id },
                claimedBy = by,
                claimedAt = claimedAt,
                claimExpiresAt = farFuture,
                originalClaimedAt = claimedAt
            )
        rig.raw.workItemRepository().create(item)
        return item
    }

    private fun JsonObject.entries(): List<JsonObject> = treeItems()

    private fun JsonObject.entry(id: UUID): JsonObject = entries().single { it.str("itemId") == id.toString() }

    private fun JsonObject.order(): List<String> = entries().map { it.str("itemId") }

    private fun JsonObject.counts(): List<String> =
        getValue("summary").jsonObject.let { s -> listOf("total", "applied", "skipped", "failed").map { s.str(it) } }

    private fun JsonObject.field(): String =
        (
            getValue("detail")
                .jsonObject
                .getValue("fields")
                .jsonArray
                .first() as JsonObject
        ).str("field")

    private fun assertField(
        err: JsonObject,
        pointer: String
    ) {
        assertEquals("invalid_request", err.str("code"), "error: $err")
        assertTrue(err.field().endsWith(pointer), "field '${err.field()}' must end with '$pointer' in $err")
    }

    private fun assertUntouched(
        k: IwKit,
        before: Map<String, List<Map<String, Any?>>>
    ) {
        assertEquals(before, k.snapshot(), "no row changed")
    }

    // ------------------------------------------------------------------ S6 order and shape

    @Test
    fun `S6 rootId mode applies the blocker before the blocked item and the root last, with the documented element shape`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val b = k.make("B", root)
            val a = k.make("A", root)
            k.dep(a.id, b.id)
            val r = k.complete("rootId" to jstr(root.id.toString()))
            assertEquals(200, r.envelope.httpStatus)
            assertEquals("""{"ok":1,"failed":0}""", r.envelope.body.summary())
            val ok = r.envelope.body.okResult()
            assertEquals(setOf("ok", "id", "trigger", "items", "summary"), ok.keys, "B3: exactly these members")
            assertEquals(root.id.toString(), ok.str("id"))
            assertEquals("complete", ok.str("trigger"))
            assertEquals(listOf(a.id, b.id, root.id).map { it.toString() }, ok.order(), "blocker A, then B, then the root last")
            for (e in listOf(ok.entry(a.id), ok.entry(b.id))) {
                assertEquals("applied", e.str("outcome"), "entry: $e")
                assertEquals("queue", e.str("previousRole"))
                assertEquals("terminal", e.str("newRole"))
                assertFalse("error" in e || "reason" in e, "an applied entry carries neither error nor reason: $e")
            }
            // Arbitration (oracle correction): the frozen S6 said "all applied", but the settled terminal cascade (workflow-guide.md,
            // pre-existing: "if all siblings are also terminal, the parent is automatically advanced to TERMINAL", no role condition)
            // terminalizes the root when its last child completes, so the root's own entry is the benign cascade skip (see S15).
            val rootEntry = ok.entry(root.id)
            assertEquals(Pair("skipped", "already_terminal"), Pair(rootEntry.str("outcome"), rootEntry.str("reason")), "entry: $rootEntry")
            assertEquals(listOf("3", "2", "1", "0"), ok.counts())
            assertEquals(listOf(Role.TERMINAL, Role.TERMINAL, Role.TERMINAL), listOf(a.id, b.id, root.id).map { k.role(it) })
        }

    @Test
    fun `S6 itemIds mode orders listed items by dependency not by request order, and independent items keep request order`(): Unit =
        runBlocking {
            val k = kit()
            val b = k.make("B")
            val a = k.make("A")
            k.dep(a.id, b.id)
            val x = k.make("X")
            val y = k.make("Y")
            val ok =
                k
                    .complete("itemIds" to twIds(b.id, a.id, y.id, x.id))
                    .envelope.body
                    .okResult()
            assertNull(ok["id"], "no rootId was given, so the element carries no id")
            val order = ok.order()
            assertTrue(order.indexOf(a.id.toString()) < order.indexOf(b.id.toString()), "A blocks B so A is first: $order")
            assertTrue(
                order.indexOf(y.id.toString()) < order.indexOf(x.id.toString()),
                "no edge between Y and X so request order holds: $order"
            )
            assertEquals(4, order.size)
            assertEquals(listOf("4", "4", "0", "0"), ok.counts())
        }

    @Test
    fun `S6 a relates_to edge between listed items does not order them, a blocks edge does`(): Unit =
        runBlocking {
            val k = kit()
            val p = k.make("P")
            val q = k.make("Q")
            k.dep(q.id, p.id, DependencyType.RELATES_TO)
            val soft =
                k
                    .complete("itemIds" to twIds(p.id, q.id))
                    .envelope.body
                    .okResult()
            assertEquals(listOf(p.id, q.id).map { it.toString() }, soft.order(), "relates_to is not blocking: request order")
            val s = k.make("S")
            val t = k.make("T")
            k.dep(t.id, s.id)
            val hard =
                k
                    .complete("itemIds" to twIds(s.id, t.id))
                    .envelope.body
                    .okResult()
            assertEquals(listOf(t.id, s.id).map { it.toString() }, hard.order(), "control: a blocks edge reorders the same request shape")
        }

    @Test
    fun `S6 includeRoot false leaves the root out of the answer and a prefix or upper-case rootId resolves`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val a = k.make("A", root)
            val ok =
                k
                    .complete(
                        "rootId" to
                            jstr(
                                root.id
                                    .toString()
                                    .take(8)
                                    .uppercase()
                            ),
                        "includeRoot" to JsonPrimitive(false)
                    ).envelope.body
                    .okResult()
            assertEquals(listOf(a.id.toString()), ok.order())
            assertEquals(Role.TERMINAL, k.role(a.id))
            assertEquals(Role.TERMINAL, k.role(root.id), "terminalized by the last child cascade, not by an entry of its own")
            val again =
                k
                    .complete("rootId" to jstr(root.id.toString()))
                    .envelope.body
                    .okResult()
            assertEquals(
                listOf(a.id.toString() to "skipped", root.id.toString() to "skipped"),
                again.entries().map { it.str("itemId") to it.str("outcome") },
                "control: includeRoot defaults to true, so the root now has an entry (already terminal)"
            )
        }

    // ------------------------------------------------------------------ S10 flag (d)

    @Test
    fun `S10 an already-terminal item is skipped already_terminal with no error member and does not propagate`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val done = k.rig.seed("Done", root, Role.TERMINAL)
            val next = k.make("Next", root)
            k.dep(done.id, next.id)
            val ok =
                k
                    .complete("itemIds" to twIds(done.id, next.id))
                    .envelope.body
                    .okResult()
            val skipped = ok.entry(done.id)
            assertEquals(setOf("itemId", "outcome", "reason"), skipped.keys, "flag (d): no error member on the benign skip")
            assertEquals("skipped", skipped.str("outcome"))
            assertEquals("already_terminal", skipped.str("reason"))
            assertEquals("applied", ok.entry(next.id).str("outcome"), "a terminal blocker does not skip its dependent")
            assertEquals(listOf("2", "1", "1", "0"), ok.counts())
            assertFalse(ok.entries().any { "error" in it }, "no entry anywhere carries an error")
            assertEquals(Role.TERMINAL, k.role(done.id))
            assertEquals(Role.TERMINAL, k.role(next.id))
        }

    @Test
    fun `S10 a gated item that is already terminal is skipped, not failed with gate_blocked`(): Unit =
        runBlocking {
            val k = kit()
            val gatedDone = k.rig.seed("GatedDone", role = Role.TERMINAL, tags = "feature-task")
            val ok =
                k
                    .complete("itemIds" to twIds(gatedDone.id))
                    .envelope.body
                    .okResult()
            assertEquals("skipped", ok.entry(gatedDone.id).str("outcome"))
            val open = k.make("GatedOpen", type = "feature-task")
            val control =
                k
                    .complete("itemIds" to twIds(open.id))
                    .envelope.body
                    .okResult()
            assertEquals(
                "failed",
                control.entry(open.id).str("outcome"),
                "control: the same item type that is not terminal is gate-checked"
            )
        }

    // ------------------------------------------------------------------ S11 failures are data

    @Test
    fun `S11 a gate-failed item is failed gate_blocked, its dependents are skipped upstream_failed transitively, nothing moves`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("A", type = "feature-task")
            val b = k.make("B")
            val c = k.make("C")
            k.dep(a.id, b.id)
            k.dep(b.id, c.id)
            val before = k.snapshot()
            val r = k.complete("itemIds" to twIds(a.id, b.id, c.id))
            assertEquals(200, r.envelope.httpStatus, "per-item failures are data, the element is ok")
            assertEquals("""{"ok":1,"failed":0}""", r.envelope.body.summary())
            val ok = r.envelope.body.okResult()
            val failed = ok.entry(a.id)
            assertEquals("failed", failed.str("outcome"))
            val error = failed.getValue("error").jsonObject
            assertEquals("gate_blocked", error.str("code"))
            assertTrue(error.keys.containsAll(setOf("code", "kind", "message")), "error: $error")
            val skippedB = ok.entry(b.id)
            assertEquals("skipped", skippedB.str("outcome"))
            assertEquals("upstream_failed", skippedB.str("reason"))
            assertEquals(listOf(a.id.toString()), skippedB.getValue("blockedBy").jsonArray.map { it.jsonPrimitive.content })
            assertFalse("error" in skippedB, "a propagated skip carries no error")
            val skippedC = ok.entry(c.id)
            assertEquals("skipped", skippedC.str("outcome"))
            assertEquals("upstream_failed", skippedC.str("reason"), "the skip propagates transitively")
            assertEquals(listOf("3", "0", "2", "1"), ok.counts())
            // A per-item rejection records a transition.rejected audit event (existing advance pipeline), so compare state tables only.
            assertEquals(before - "events", k.snapshot() - "events", "no item, note or dependency row changed")
        }

    @Test
    fun `S11 the failure entry carries the same catalog error code and kind the advance transition op returns for that item`(): Unit =
        runBlocking {
            val k = kit()
            val viaTree = k.make("ViaTree", type = "feature-task")
            val viaAdvance = k.make("ViaAdvance", type = "feature-task")
            val entryError =
                k
                    .complete(
                        "itemIds" to twIds(viaTree.id)
                    ).envelope.body
                    .okResult()
                    .entry(viaTree.id)
                    .getValue("error")
                    .jsonObject
            val advanceSpec = checkNotNull(ToolRegistry(buildToolSpecs())["advance"]) { "advance is not registered" }
            val args =
                buildJsonObject {
                    put("actor", IW_ACTOR)
                    put(
                        "ops",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("op", "transition")
                                    put("itemId", viaAdvance.id.toString())
                                    put("trigger", "complete")
                                }
                            )
                        )
                    )
                }
            val transitionError =
                buildToolDispatcher(k.rig.ctx)
                    .dispatch(advanceSpec, ToolProfile.ORCHESTRATION, args, "k7f3q9ab", "mcp")
                    .envelope.body
                    .errorAt()
            assertEquals(transitionError.str("code"), entryError.str("code"))
            assertEquals(transitionError["kind"], entryError["kind"])
        }

    @Test
    fun `S11 a gate failure never rolls back the applied siblings of the same call`(): Unit =
        runBlocking {
            val k = kit()
            val gated = k.make("Gated", type = "feature-task")
            val free = k.make("Free")
            val ok =
                k
                    .complete("itemIds" to twIds(gated.id, free.id))
                    .envelope.body
                    .okResult()
            assertEquals(listOf("2", "1", "0", "1"), ok.counts())
            assertEquals(Role.TERMINAL, k.role(free.id), "the applied sibling stays committed")
            assertEquals(Role.QUEUE, k.role(gated.id))
        }

    @Test
    fun `S11 all applied transitions of one element commit or roll back together with the batch`(): Unit =
        runBlocking {
            val k = kit()
            val one = k.make("One")
            val two = k.make("Two")
            val before = k.snapshot()
            val atomic =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twComplete("itemIds" to twIds(one.id, two.id)),
                    deleteOp(ghost),
                    atomic = true,
                    actor = IW_ACTOR
                )
            assertEquals(
                "not_found",
                atomic.envelope.body
                    .wholeError()
                    .str("code")
            )
            assertEquals(
                "1",
                atomic.envelope.body
                    .wholeError()
                    .str("index")
            )
            assertUntouched(k, before)
            assertEquals(
                listOf(Role.QUEUE, Role.QUEUE),
                listOf(one.id, two.id).map { k.role(it) },
                "the atomic batch rolled both transitions back"
            )
            val loose =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twComplete("itemIds" to twIds(one.id, two.id)),
                    deleteOp(ghost),
                    actor = IW_ACTOR
                )
            loose.envelope.body.okResult(0)
            assertEquals(
                listOf(Role.TERMINAL, Role.TERMINAL),
                listOf(one.id, two.id).map {
                    k.role(it)
                },
                "control: without atomic the transitions commit"
            )
        }

    // ------------------------------------------------------------------ S14 cancel and ownership

    @Test
    fun `S14 cancel bypasses the note gate while complete does not, and the element echoes the trigger`(): Unit =
        runBlocking {
            val k = kit()
            val toCancel = k.make("ToCancel", type = "feature-task")
            val toComplete = k.make("ToComplete", type = "feature-task")
            val cancelled =
                k
                    .complete("itemIds" to twIds(toCancel.id), "trigger" to jstr("cancel"))
                    .envelope.body
                    .okResult()
            assertEquals("cancel", cancelled.str("trigger"))
            val applied = cancelled.entry(toCancel.id)
            assertEquals("applied", applied.str("outcome"))
            assertEquals("terminal", applied.str("newRole"))
            assertEquals(Role.TERMINAL, k.role(toCancel.id))
            val completed =
                k
                    .complete("itemIds" to twIds(toComplete.id))
                    .envelope.body
                    .okResult()
            assertEquals(
                "gate_blocked",
                completed
                    .entry(toComplete.id)
                    .getValue("error")
                    .jsonObject
                    .str("code"),
                "control: complete is gated"
            )
            assertEquals(Role.QUEUE, k.role(toComplete.id))
        }

    @Test
    fun `S14 cancel does not bypass ownership - an item claimed by another agent is failed not_claim_holder and keeps its claim`(): Unit =
        runBlocking {
            val k = kit()
            val held = k.claimed("Held")
            val mine = k.make("Mine")
            val ok =
                k
                    .complete("itemIds" to twIds(held.id, mine.id), "trigger" to jstr("cancel"))
                    .envelope.body
                    .okResult()
            val failed = ok.entry(held.id)
            assertEquals("failed", failed.str("outcome"))
            assertEquals("not_claim_holder", failed.getValue("error").jsonObject.str("code"))
            assertEquals(Role.QUEUE, k.role(held.id))
            assertEquals("other-agent-77", assertNotNull(k.item(held.id)).claimedBy, "the foreign claim is untouched")
            assertEquals("applied", ok.entry(mine.id).str("outcome"), "control: the unclaimed item is cancelled in the same call")
            assertEquals(listOf("2", "1", "0", "1"), ok.counts())
        }

    @Test
    fun `S14 a failure from ownership skips its dependents like any other failure`(): Unit =
        runBlocking {
            val k = kit()
            val held = k.claimed("Held")
            val after = k.make("After")
            k.dep(held.id, after.id)
            val ok =
                k
                    .complete("itemIds" to twIds(held.id, after.id))
                    .envelope.body
                    .okResult()
            assertEquals("failed", ok.entry(held.id).str("outcome"))
            assertEquals("upstream_failed", ok.entry(after.id).str("reason"))
            assertEquals(Role.QUEUE, k.role(after.id))
        }

    // ------------------------------------------------------------------ S15 cascade

    @Test
    fun `S15 the last child's cascade terminalizes the root, whose entry is skipped already_terminal, one root transition row`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.rig.seed("Root", role = Role.WORK)
            val child = k.rig.seed("Only child", root, Role.WORK)
            val (ok, rows) =
                k.rig.written {
                    k
                        .complete("rootId" to jstr(root.id.toString()))
                        .envelope.body
                        .okResult()
                }
            assertEquals(listOf(child.id, root.id).map { it.toString() }, ok.order(), "descendants first, the root last")
            val applied = ok.entry(child.id)
            assertEquals("applied", applied.str("outcome"))
            assertTrue(
                root.id.toString() in applied.getValue("cascades").toString(),
                "the child's entry reports the root cascade: $applied"
            )
            val rootEntry = ok.entry(root.id)
            assertEquals(setOf("itemId", "outcome", "reason"), rootEntry.keys)
            assertEquals("skipped", rootEntry.str("outcome"))
            assertEquals("already_terminal", rootEntry.str("reason"))
            assertEquals(listOf("2", "1", "1", "0"), ok.counts())
            assertEquals(Role.TERMINAL, k.role(root.id))
            val rootRows = rows.filter { it.type == "item.transitioned" && it.entityId == root.id }
            assertEquals(1, rootRows.size, "exactly one transition row for the root: the cascade's")
            assertEquals("agent-1", rootRows.single().principalId, "the cascade row carries the lifted actor")
        }

    @Test
    fun `S15 a queue-role root is cascaded too - the terminal cascade has no role condition`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("QueueRoot")
            val child = k.make("Child", root)
            val ok =
                k
                    .complete("rootId" to jstr(root.id.toString()))
                    .envelope.body
                    .okResult()
            assertEquals("applied", ok.entry(child.id).str("outcome"))
            assertEquals("skipped", ok.entry(root.id).str("outcome"))
            assertEquals("already_terminal", ok.entry(root.id).str("reason"))
            assertEquals(listOf("2", "1", "1", "0"), ok.counts())
        }

    // ------------------------------------------------------------------ shape probes

    @Test
    fun `probe rootId with itemIds, neither, an empty itemIds list and an unknown trigger are invalid_request and write nothing`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val a = k.make("A", root)
            val before = k.snapshot()
            assertField(
                k
                    .complete("rootId" to jstr(root.id.toString()), "itemIds" to twIds(a.id))
                    .envelope.body
                    .errorAt(),
                "/rootId"
            )
            assertEquals(
                "invalid_request",
                k
                    .complete()
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertField(
                k
                    .complete("itemIds" to JsonArray(emptyList()))
                    .envelope.body
                    .errorAt(),
                "/itemIds"
            )
            assertField(
                k
                    .complete("itemIds" to twIds(a.id), "trigger" to jstr("finish"))
                    .envelope.body
                    .errorAt(),
                "/trigger"
            )
            assertUntouched(k, before)
            k
                .complete("itemIds" to twIds(a.id))
                .envelope.body
                .okResult()
            assertEquals(Role.TERMINAL, k.role(a.id), "control: a valid single-mode request completes")
        }

    @Test
    fun `probe an unknown id in itemIds is not_found and no listed item moves, an ambiguous prefix is ambiguous_id`(): Unit =
        runBlocking {
            val k = kit()
            val real = k.make("Real")
            val one = k.make("One", id = UUID.fromString("abcdef12-0000-4000-8000-000000000001"))
            k.make("Two", id = UUID.fromString("abcdef12-0000-4000-8000-000000000002"))
            val before = k.snapshot()
            val err =
                k
                    .complete("itemIds" to twIds(real.id, ghost))
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", err.str("code"))
            val amb =
                k
                    .complete("itemIds" to JsonArray(listOf(jstr("abcdef12"))))
                    .envelope.body
                    .errorAt()
            assertEquals("ambiguous_id", amb.str("code"))
            val unknownRoot =
                k
                    .complete("rootId" to jstr(ghost.toString()))
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", unknownRoot.str("code"))
            assertUntouched(k, before)
            k
                .complete("itemIds" to twIds(real.id, one.id))
                .envelope.body
                .okResult()
            assertEquals(listOf(Role.TERMINAL, Role.TERMINAL), listOf(real.id, one.id).map { k.role(it) }, "control: valid ids complete")
        }

    @Test
    fun `probe a repeated idempotencyKey replays the stored element instead of re-running the tree`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("A")
            val b = k.make("B")
            val keyed = twComplete("itemIds" to twIds(a.id, b.id), "idempotencyKey" to jstr("ct-key-1"))
            val first =
                k
                    .callIn(ToolProfile.ORCHESTRATION, keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()
            assertNull(first["replayed"])
            val again =
                k
                    .callIn(ToolProfile.ORCHESTRATION, keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()
            assertEquals("true", again.str("replayed"))
            assertEquals(first.entries(), again.entries(), "the replay answers the stored entries, not already_terminal skips")
            assertEquals(listOf("applied", "applied"), again.entries().map { it.str("outcome") })
        }

    // ------------------------------------------------------------------ S18 events

    @Test
    fun `S18 applied transitions carry the lifted actor, a failed or not-found element emits no transition row`(): Unit =
        runBlocking {
            val k = kit()
            val a = k.make("A")
            val b = k.make("B")
            val gated = k.make("Gated", type = "feature-task")
            val (_, failedRows) =
                k.rig.written {
                    k
                        .complete("itemIds" to twIds(gated.id))
                        .envelope.body
                        .okResult()
                    k
                        .complete("itemIds" to twIds(ghost))
                        .envelope.body
                        .errorAt()
                }
            assertEquals(
                emptyList(),
                failedRows.filter {
                    it.type == "item.transitioned"
                },
                "failed entries and a failed element write no transition row"
            )
            val (_, rows) =
                k.rig.written {
                    k
                        .complete("itemIds" to twIds(a.id, b.id))
                        .envelope.body
                        .okResult()
                }
            val transitions = rows.filter { it.type == "item.transitioned" }
            assertEquals(setOf(a.id, b.id), transitions.map { it.entityId }.toSet())
            for (row in transitions) {
                assertEquals("agent-1", row.principalId)
                assertEquals("subagent", row.principalKind)
            }
        }

    // ------------------------------------------------------------------ scope

    /** R1 (alpha) > C1 (alpha,shared), C2 (alpha); R2 (beta) > D2 (beta). */
    private suspend fun seeded(): IwKit {
        val k = kit()
        val root1 = k.make("R1", tags = "alpha", id = r1)
        k.make("C1", root1, tags = "alpha,shared", id = c1)
        k.make("C2", root1, tags = "alpha", id = c2)
        val root2 = k.make("R2", tags = "beta", id = r2)
        k.make("D2", root2, tags = "beta", id = d2)
        return k
    }

    @Test
    fun `S12 root scope - a rootId outside the listed root is forbidden root for every spelling and nothing moves`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (ref in listOf(r2.toString(), r2.toString().take(8), r2.toString().uppercase(), d2.toString().take(8), ghost.toString())) {
                assertForbidden(k.callScoped(rootScoped, twComplete("rootId" to jstr(ref))).errorAt(), "root", r1.toString())
            }
            assertUntouched(k, before)
            k.callScoped(rootScoped, twComplete("rootId" to jstr(r1.toString().take(8)))).okResult()
            assertEquals(
                listOf(Role.TERMINAL, Role.TERMINAL, Role.TERMINAL),
                listOf(r1, c1, c2).map {
                    k.role(it)
                },
                "control: the in-scope root completes"
            )
            assertEquals(Role.QUEUE, k.role(r2), "the foreign tree was never touched")
        }

    @Test
    fun `S12 root scope - an itemIds list with one foreign entry is forbidden as a whole and the in-scope entries do not move`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(rootScoped, twComplete("itemIds" to twIds(c1, r2))).errorAt(), "root", r1.toString())
            assertForbidden(
                k
                    .callScoped(
                        rootScoped,
                        twComplete("itemIds" to JsonArray(listOf(jstr(c1.toString()), jstr(d2.toString().take(8)))))
                    ).errorAt(),
                "root",
                r1.toString()
            )
            assertForbidden(k.callScoped(rootScoped, twComplete("itemIds" to twIds(c1, ghost))).errorAt(), "root", r1.toString())
            assertUntouched(k, before)
            assertEquals(Role.QUEUE, k.role(c1))
            k.callScoped(rootScoped, twComplete("itemIds" to twIds(c1, c2))).okResult()
            assertEquals(listOf(Role.TERMINAL, Role.TERMINAL), listOf(c1, c2).map { k.role(it) }, "control: all-in-scope ids complete")
        }

    @Test
    fun `S12 tag scope - a tree with a descendant outside the scope is forbidden as a whole, an all-admitted tree completes`(): Unit =
        runBlocking {
            val k = seeded()
            val stranger = k.make("Stranger", k.item(r1), tags = "beta")
            val clean = k.make("R3", tags = "alpha")
            val cleanChild = k.make("Y", clean, tags = "alpha")
            val before = k.snapshot()
            assertForbidden(k.callScoped(tagScoped, twComplete("rootId" to jstr(r1.toString()))).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tagScoped, twComplete("itemIds" to twIds(c1, d2))).errorAt(), "tag", "alpha")
            assertUntouched(k, before)
            assertEquals(Role.QUEUE, k.role(c1))
            assertEquals(Role.QUEUE, k.role(stranger.id))
            k.callScoped(tagScoped, twComplete("rootId" to jstr(clean.id.toString()))).okResult()
            assertEquals(
                listOf(Role.TERMINAL, Role.TERMINAL),
                listOf(cleanChild.id, clean.id).map { k.role(it) },
                "control: every descendant admitted"
            )
        }

    // ------------------------------------------------------------------ S13 capability, both directions

    @Test
    fun `S13 complete_tree needs advance - a write-items token is forbidden capability advance, and create_tree needs write-items`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val writer = ParityToken("ct-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS))
            assertForbidden(k.callScoped(writer, twComplete("itemIds" to twIds(c1))).errorAt(), "capability", "advance")
            val advancer = ParityToken("ct-advancer", advanceCaps)
            assertForbidden(k.callScoped(advancer, twTree(twRoot("T"))).errorAt(), "capability", "write-items")
            assertUntouched(k, before)
            k.callScoped(advancer, twComplete("itemIds" to twIds(c1))).okResult()
            assertEquals(Role.TERMINAL, k.role(c1), "control: an advance token completes")
            k.callScoped(writer, twTree(twRoot("T"))).okResult()
            assertEquals(6, k.all().size, "control: a write-items token creates")
        }
}

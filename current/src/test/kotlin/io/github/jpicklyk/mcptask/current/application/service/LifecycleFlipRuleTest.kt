package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.IW_ACTOR
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.IwKit
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.detail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.blocksDep
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.relatesDep
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server document of every flip-rule test: `test-item` and `shifty` are ordinary (auto) types, `page` and `page-b` are
 * lifecycle-free. `shifty` is the type a per-root push turns lifecycle-free or back, so the same type can be pinned both ways.
 */
private const val SERVER_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  shifty:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n" +
        "  page-b:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n" +
        "traits:\n" +
        "  t-one:\n" +
        "    notes: []\n"

private const val ROOT_SHIFTY_NONE =
    "work_item_schemas:\n" +
        "  shifty:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

private const val ROOT_SHIFTY_AUTO =
    "work_item_schemas:\n" +
        "  shifty:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

private val ADVANCE_REGISTRY = ToolRegistry(buildToolSpecs())

/**
 * Independent tests (test-author seat, item 9e5707b3 / Q31b) for lifecycle-free creation and the flip rule (U13b):
 * scenarios S2, S3, S4, S6 and S7 of the frozen test-plan, the BLOCKS half of S9 and the flip probes.
 *
 * Oracles (frozen task-scope Build 3, 4 and 5 and plan 3.12 / U13b, none read from the implementation): a `lifecycle: none`
 * type creates an item with a NULL role (V26 CHECK: role NULL iff lifecycle 'none'); only a type change and `repin` may
 * change the lifecycle class; a crossing is `(item.role == null) != (new pin lifecycle == none)`; into none is allowed only
 * from QUEUE or TERMINAL and leaves role, previousRole, statusLabel and the claim cleared with `roleChangedAt` now; out of none
 * the item becomes QUEUE with `roleChangedAt` now, subject to the create closed-parent rule; every other crossing is
 * `invalid_transition` with detail `{itemId, fromRole (role wire or "none"), trigger (the pin reason wire), allowed: []}` and
 * nothing written; a flip into none is also refused while the item has a BLOCKS edge in either direction; a flip records
 * `item.updated` (changedFields includes `role`) and `item.pinned` and no transition row; reopen, trait change and a cross-root
 * move never flip (a crossing pin there is the same rejection with their own trigger).
 *
 * Every rejection assertion compares the whole stored row before and after and is paired with a control that writes through
 * the same call shape. Fixtures are built by construction: parents are created before children, roots before the pushes that
 * give a type its lifecycle, and a BLOCKED row always carries a previousRole.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class LifecycleFlipRuleTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private class Fx(
        val rig: EventLogRig,
        val clock: SettableClock
    ) {
        val k = IwKit(rig)

        suspend fun make(
            title: String,
            parent: WorkItem? = null,
            type: String = "test-item"
        ): WorkItem =
            rig.ctx.itemCommandService
                .create(ItemCreateCommand(parentId = parent?.id, title = title, type = type))
                .orFail()

        suspend fun get(id: UUID): WorkItem = checkNotNull(rig.raw.workItemRepository().getById(id)) { "item $id" }

        suspend fun push(
            root: WorkItem,
            yaml: String
        ) {
            assertIs<ConfigPushResult.Success>(checkNotNull(rig.ctx.configService).push(root.id, yaml), "push of '${yaml.take(40)}'")
        }

        /** Sets role, previous role and label straight in the store (never through the code under test). */
        suspend fun setState(
            item: WorkItem,
            role: Role,
            statusLabel: String? = null
        ) {
            val previous = if (role == Role.BLOCKED) Role.WORK else null
            rig.raw.workItemRepository().update(get(item.id).copy(role = role, previousRole = previous, statusLabel = statusLabel))
        }

        suspend fun update(
            id: UUID,
            vararg fields: Pair<String, JsonElement>
        ): JsonObject = k.call(updateOp(id, *fields), actor = IW_ACTOR).envelope.body

        suspend fun changeType(
            id: UUID,
            type: String
        ): JsonObject = update(id, "type" to JsonPrimitive(type))

        suspend fun repin(id: UUID): JsonObject =
            k
                .call(
                    buildJsonObject {
                        put("op", "repin")
                        put("id", id.toString())
                    },
                    actor = IW_ACTOR
                ).envelope.body

        /** One `advance` transition element through the real dispatcher in the orchestration profile; the envelope body. */
        suspend fun advance(
            id: UUID,
            trigger: String
        ): JsonObject {
            val spec = checkNotNull(ADVANCE_REGISTRY["advance"]) { "advance is not registered" }
            val element =
                buildJsonObject {
                    put("op", "transition")
                    put("itemId", id.toString())
                    put("trigger", trigger)
                }
            val args =
                buildJsonObject {
                    put("actor", IW_ACTOR)
                    put("ops", JsonArray(listOf(element)))
                }
            return buildToolDispatcher(rig.ctx).dispatch(spec, ToolProfile.ORCHESTRATION, args, "k7f3q9ab", "mcp").envelope.body
        }

        fun column(
            id: UUID,
            name: String
        ): String? = P7Raw.query(rig.db.jdbcUrl, "SELECT $name FROM work_items WHERE id = ?", id) { it.getString(1) }.single()

        /** Every `item.transitioned` event ever recorded for the item (role transitions live only in the event store). */
        suspend fun transitionRows(id: UUID): List<EventRecord> = rig.rows().filter { it.type == "item.transitioned" && it.entityId == id }
    }

    private fun fx(
        dir: Path,
        clock: SettableClock = SettableClock()
    ) = Fx(EventLogRig.build(db.db, dir, SERVER_YAML, clock), clock)

    private fun List<EventRecord>.ofType(type: String) = filter { it.type == type }

    private fun EventRecord.changed(): Set<String> =
        payload()
            .getValue("changedFields")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    private fun List<EventRecord>.noWriteEvents() =
        none {
            it.type == "item.updated" ||
                it.type == "item.pinned" ||
                it.type == "item.transitioned"
        }

    private fun assertRejected(
        err: JsonObject,
        id: UUID,
        fromRole: String,
        trigger: String
    ) {
        assertEquals("invalid_transition", err.str("code"), "error: $err")
        val d = err.detail()
        assertEquals(id.toString(), d.str("itemId"), "error: $err")
        assertEquals(fromRole, d.str("fromRole"), "error: $err")
        assertEquals(trigger, d.str("trigger"), "error: $err")
        assertEquals(0, d.getValue("allowed").jsonArray.size, "allowed is empty: $err")
    }

    // ---------------------------------------------------------------------------------------------
    // S2 - creating an item whose type is lifecycle-free
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 a none-typed item is created without a role and a sibling of an auto type is queue`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)

            val (body, rows) =
                fx.rig.written {
                    fx.k
                        .call(createOp("Page", null, "page"), createOp("Plain", null, "test-item"), actor = IW_ACTOR)
                        .envelope.body
                }

            val pageId = UUID.fromString(body.okResult(0).str("id"))
            val plainId = UUID.fromString(body.okResult(1).str("id"))
            val page = fx.get(pageId)
            assertNull(page.role, "a lifecycle-free item has no role")
            assertNull(page.previousRole)
            assertEquals(LifecycleMode.NONE, page.pin?.lifecycle, "the pin reads back as none")
            assertEquals("page", page.pin?.schemaKey)
            assertEquals(Role.QUEUE, fx.get(plainId).role, "control: the sibling of an auto type is queue")
            assertNull(fx.column(pageId, "role"), "the stored role column is NULL")
            assertEquals("none", fx.column(pageId, "lifecycle"), "the stored lifecycle column is 'none'")
            assertEquals("queue", fx.column(plainId, "role"))
            assertEquals("auto", fx.column(plainId, "lifecycle"))
            assertEquals(2, rows.ofType("item.created").size, "both creates were recorded: ${rows.map { it.type }}")
            assertTrue(fx.transitionRows(pageId).isEmpty(), "creating a page records no item.transitioned event")
        }

    @Test
    fun `S2 a none-typed child under a queue parent is created without a role and the parent is untouched`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val parent = fx.make("Parent")
            val before = fx.get(parent.id)

            val child = fx.make("Child page", parent, "page")

            assertNull(fx.get(child.id).role)
            assertEquals(parent.id, fx.get(child.id).parentId)
            assertEquals(before.role, fx.get(parent.id).role, "the parent keeps its role")
            val sibling = fx.make("Child plain", parent, "test-item")
            assertEquals(Role.QUEUE, fx.get(sibling.id).role, "control: an auto child under the same parent is queue")
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - flipping into none
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 a QUEUE item changed to a none type loses role, label and claim and records one update and one pin row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val clock = SettableClock()
            val fx = fx(dir, clock)
            val item = fx.make("I")
            fx.setState(item, Role.QUEUE, statusLabel = "wip")
            fx.rig.raw
                .workItemRepository()
                .claim(item.id, "agent-1", ttlSeconds = 900)
            val before = fx.get(item.id)
            assertEquals("wip", before.statusLabel, "fixture: the label is set")
            assertEquals("agent-1", before.claimedBy, "fixture: the claim is held")
            clock.advanceSeconds(3600)

            val (body, rows) = fx.rig.written { fx.changeType(item.id, "page") }

            body.okResult()
            val after = fx.get(item.id)
            assertNull(after.role)
            assertNull(after.previousRole)
            assertNull(after.statusLabel)
            assertNull(after.claimedBy, "the claim is cleared explicitly")
            assertNull(after.claimExpiresAt)
            assertEquals(clock.now(), after.roleChangedAt, "roleChangedAt is now")
            assertTrue(after.version > before.version, "the flip is an edit")
            assertEquals(LifecycleMode.NONE, after.pin?.lifecycle)
            assertEquals("page", after.pin?.schemaKey)
            assertEquals("none", fx.column(item.id, "lifecycle"))
            assertNull(fx.column(item.id, "role"))
            val updated = rows.ofType("item.updated").single()
            assertTrue("role" in updated.changed(), "changedFields names role: ${updated.changed()}")
            assertTrue("type" in updated.changed(), "changedFields names type: ${updated.changed()}")
            assertEquals("type_change", rows.ofType("item.pinned").single().str("reason"))
            assertTrue(rows.ofType("item.transitioned").isEmpty(), "a flip is not a transition: ${rows.map { it.type }}")
            assertTrue(fx.transitionRows(item.id).isEmpty(), "a flip records no item.transitioned event")
        }

    @Test
    fun `S3 a TERMINAL item whose type a push turned none loses its role on repin`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val clock = SettableClock()
            val fx = fx(dir, clock)
            val root = fx.make("R")
            val item = fx.make("I", root, "shifty")
            fx.setState(item, Role.TERMINAL, statusLabel = "done")
            fx.push(root, ROOT_SHIFTY_NONE)
            clock.advanceSeconds(60)
            val before = fx.get(item.id)
            assertEquals(Role.TERMINAL, before.role, "fixture: terminal")
            assertEquals(LifecycleMode.AUTO, before.pin?.lifecycle, "fixture: still pinned to the old auto schema")

            val (body, rows) = fx.rig.written { fx.repin(item.id) }

            assertEquals(
                "true",
                body
                    .okResult()
                    .getValue("repinned")
                    .jsonPrimitive.content
            )
            val after = fx.get(item.id)
            assertNull(after.role)
            assertNull(after.previousRole)
            assertNull(after.statusLabel)
            assertNull(after.claimedBy)
            assertEquals(clock.now(), after.roleChangedAt)
            assertEquals(LifecycleMode.NONE, after.pin?.lifecycle)
            assertEquals("none", fx.column(item.id, "lifecycle"))
            assertEquals("repin", rows.ofType("item.pinned").single().str("reason"))
            assertTrue("role" in rows.ofType("item.updated").single().changed(), "a repin flip records item.updated with role")
            assertTrue(fx.transitionRows(item.id).isEmpty(), "a flip records no item.transitioned event")
        }

    @Test
    fun `S3 a title-only patch of a none item keeps it role-free and records no pin row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val page = fx.make("Page", null, "page")
            val before = fx.get(page.id)

            val (body, rows) = fx.rig.written { fx.update(page.id, "title" to JsonPrimitive("Renamed")) }

            body.okResult()
            val after = fx.get(page.id)
            assertEquals("Renamed", after.title, "control: the patch wrote")
            assertNull(after.role, "a patch that changes no type leaves the item role-free")
            assertEquals(before.pin, after.pin)
            assertTrue(rows.ofType("item.pinned").isEmpty(), "a title-only patch is not a pin point")
            assertFalse("role" in rows.ofType("item.updated").single().changed())
        }

    @Test
    fun `S3 probe - a none item changed to another none type stays role-free and re-pins to the new key`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val page = fx.make("Page", null, "page")

            val body = fx.changeType(page.id, "page-b")

            body.okResult()
            val after = fx.get(page.id)
            assertNull(after.role, "none to none is not a crossing")
            assertEquals("page-b", after.pin?.schemaKey)
            assertEquals(LifecycleMode.NONE, after.pin?.lifecycle)
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - flipping out of none
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a none item changed to an auto type becomes queue with roleChangedAt now and an auto pin`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val clock = SettableClock()
            val fx = fx(dir, clock)
            val page = fx.make("Page", null, "page")
            clock.advanceSeconds(3600)

            val (body, rows) = fx.rig.written { fx.changeType(page.id, "test-item") }

            body.okResult()
            val after = fx.get(page.id)
            assertEquals(Role.QUEUE, after.role)
            assertNull(after.previousRole)
            assertEquals(clock.now(), after.roleChangedAt)
            assertEquals(LifecycleMode.AUTO, after.pin?.lifecycle)
            assertEquals("auto", fx.column(page.id, "lifecycle"))
            assertEquals("queue", fx.column(page.id, "role"))
            assertTrue("role" in rows.ofType("item.updated").single().changed())
            assertEquals("type_change", rows.ofType("item.pinned").single().str("reason"))
            assertTrue(fx.transitionRows(page.id).isEmpty(), "leaving none records no item.transitioned event")
        }

    @Test
    fun `S4 a none item whose type a push turned auto becomes queue on repin`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val clock = SettableClock()
            val fx = fx(dir, clock)
            val root = fx.make("R")
            fx.push(root, ROOT_SHIFTY_NONE)
            val item = fx.make("I", root, "shifty")
            assertNull(fx.get(item.id).role, "fixture: created lifecycle-free under the pushed document")
            fx.push(root, ROOT_SHIFTY_AUTO)
            clock.advanceSeconds(60)

            val body = fx.repin(item.id)

            assertEquals(
                "true",
                body
                    .okResult()
                    .getValue("repinned")
                    .jsonPrimitive.content
            )
            val after = fx.get(item.id)
            assertEquals(Role.QUEUE, after.role)
            assertNull(after.previousRole)
            assertEquals(clock.now(), after.roleChangedAt)
            assertEquals(LifecycleMode.AUTO, after.pin?.lifecycle)
        }

    @Test
    fun `S4 probe - a repeated repin after a flip changes nothing and records no second event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val item = fx.make("I", root, "shifty")
            fx.push(root, ROOT_SHIFTY_NONE)
            assertEquals(
                "true",
                fx
                    .repin(item.id)
                    .okResult()
                    .getValue("repinned")
                    .jsonPrimitive.content
            )
            val settled = fx.get(item.id)
            assertNull(settled.role, "fixture: the first repin flipped the item")

            val (body, rows) = fx.rig.written { fx.repin(item.id) }

            assertEquals(
                "false",
                body
                    .okResult()
                    .getValue("repinned")
                    .jsonPrimitive.content
            )
            assertEquals(settled, fx.get(item.id), "the second repin wrote nothing")
            assertTrue(rows.isEmpty(), "no event on the replay: ${rows.map { it.type }}")
        }

    @Test
    fun `S6 a none item under a TERMINAL auto parent cannot become an auto item, and under a QUEUE parent it can`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val closed = fx.make("Closed parent")
            val open = fx.make("Open parent")
            val refused = fx.make("Page under closed", closed, "page")
            val allowed = fx.make("Page under open", open, "page")
            fx.setState(closed, Role.TERMINAL)
            val before = fx.get(refused.id)

            val (denied, rows) = fx.rig.written { fx.changeType(refused.id, "test-item") }

            assertEquals("invalid_transition", denied.errorAt().str("code"), "the closed-parent rule applies: $denied")
            assertEquals(before, fx.get(refused.id), "nothing was written")
            assertTrue(rows.noWriteEvents(), "no write event: ${rows.map { it.type }}")
            fx.changeType(allowed.id, "test-item").okResult()
            assertEquals(Role.QUEUE, fx.get(allowed.id).role, "control: the same call under an open parent flips to queue")
        }

    // ---------------------------------------------------------------------------------------------
    // S6 - every crossing the flip rule refuses
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 probe - a type change into none is allowed from QUEUE and TERMINAL only, for every role`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val allowed = setOf(Role.QUEUE, Role.TERMINAL)
            for (role in Role.entries) {
                val item = fx.make("Item $role")
                fx.setState(item, role)
                val before = fx.get(item.id)

                val (body, rows) = fx.rig.written { fx.changeType(item.id, "page") }

                if (role in allowed) {
                    body.okResult()
                    assertNull(fx.get(item.id).role, "$role flips into none")
                } else {
                    assertRejected(body.errorAt(), item.id, role.name.lowercase(), "type_change")
                    assertEquals(before, fx.get(item.id), "$role: version, role and pin are unchanged")
                    assertTrue(rows.noWriteEvents(), "$role: ${rows.map { it.type }}")
                }
            }
        }

    @Test
    fun `S6 probe - a repin into none is allowed from QUEUE and TERMINAL only, for every role`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val items = Role.entries.associateWith { fx.make("Item $it", root, "shifty") }
            for ((role, item) in items) fx.setState(item, role)
            fx.push(root, ROOT_SHIFTY_NONE)
            val allowed = setOf(Role.QUEUE, Role.TERMINAL)
            for ((role, item) in items) {
                val before = fx.get(item.id)

                val (body, rows) = fx.rig.written { fx.repin(item.id) }

                if (role in allowed) {
                    assertEquals(
                        "true",
                        body
                            .okResult()
                            .getValue("repinned")
                            .jsonPrimitive.content,
                        "$role"
                    )
                    assertNull(fx.get(item.id).role, "$role flips into none")
                } else {
                    assertRejected(body.errorAt(), item.id, role.name.lowercase(), "repin")
                    assertEquals(before, fx.get(item.id), "$role: version, role and pin are unchanged")
                    assertTrue(rows.noWriteEvents(), "$role: ${rows.map { it.type }}")
                }
            }
        }

    @Test
    fun `S6 a QUEUE item with a BLOCKS edge in either direction cannot flip into none, with RELATES_TO or no edge it can`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val other = fx.make("Other")
            val blocker = fx.make("Item that blocks")
            val blocked = fx.make("Item that is blocked")
            val soft = fx.make("Item with a soft edge")
            val bare = fx.make("Item without edges")
            fx.rig.raw
                .dependencyRepository()
                .create(blocksDep(blocker.id, other.id))
            fx.rig.raw
                .dependencyRepository()
                .create(blocksDep(other.id, blocked.id))
            fx.rig.raw
                .dependencyRepository()
                .create(relatesDep(soft.id, other.id))

            for (item in listOf(blocker, blocked)) {
                val before = fx.get(item.id)
                val (body, rows) = fx.rig.written { fx.changeType(item.id, "page") }
                assertRejected(body.errorAt(), item.id, "queue", "type_change")
                assertEquals(before, fx.get(item.id), "${item.title}: nothing was written")
                assertTrue(rows.noWriteEvents(), "${item.title}: ${rows.map { it.type }}")
            }
            fx.changeType(soft.id, "page").okResult()
            fx.changeType(bare.id, "page").okResult()
            assertNull(fx.get(soft.id).role, "control: a RELATES_TO edge does not block the flip")
            assertNull(fx.get(bare.id).role, "control: no edge, flips")
        }

    @Test
    fun `S6 a TERMINAL item with a BLOCKS edge cannot flip into none on repin either`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val other = fx.make("Other", root)
            val edged = fx.make("Edged", root, "shifty")
            val free = fx.make("Free", root, "shifty")
            fx.setState(edged, Role.TERMINAL)
            fx.setState(free, Role.TERMINAL)
            fx.rig.raw
                .dependencyRepository()
                .create(blocksDep(edged.id, other.id))
            fx.push(root, ROOT_SHIFTY_NONE)
            val before = fx.get(edged.id)

            val denied = fx.repin(edged.id)

            assertRejected(denied.errorAt(), edged.id, "terminal", "repin")
            assertEquals(before, fx.get(edged.id))
            assertEquals(
                "true",
                fx
                    .repin(free.id)
                    .okResult()
                    .getValue("repinned")
                    .jsonPrimitive.content
            )
            assertNull(fx.get(free.id).role, "control: the same repin without the edge flips")
        }

    // ---------------------------------------------------------------------------------------------
    // S7 - pin points that never flip
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 after a push makes the type none a reopen is refused with trigger reopen and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val doomed = fx.make("Doomed", root, "shifty")
            val control = fx.make("Control", root, "test-item")
            fx.setState(doomed, Role.TERMINAL)
            fx.setState(control, Role.TERMINAL)
            fx.push(root, ROOT_SHIFTY_NONE)
            val before = fx.get(doomed.id)

            val (denied, rows) = fx.rig.written { fx.advance(doomed.id, "reopen") }

            assertRejected(denied.errorAt(), doomed.id, "terminal", "reopen")
            assertEquals(before, fx.get(doomed.id), "still terminal, same pin, same version")
            assertTrue(rows.noWriteEvents(), "nothing recorded: ${rows.map { it.type }}")
            fx.advance(control.id, "reopen").okResult()
            assertEquals(Role.QUEUE, fx.get(control.id).role, "control: the same reopen of an unaffected type works")
        }

    @Test
    fun `S7 after a push makes the type none a trait change is refused with trigger trait_change and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val doomed = fx.make("Doomed", root, "shifty")
            val control = fx.make("Control", root, "test-item")
            fx.push(root, ROOT_SHIFTY_NONE)
            val before = fx.get(doomed.id)

            val (body, rows) = fx.rig.written { fx.update(doomed.id, "traits" to strings("t-one")) }

            assertRejected(body.errorAt(), doomed.id, "queue", "trait_change")
            assertEquals(before, fx.get(doomed.id))
            assertTrue(rows.noWriteEvents(), "nothing recorded: ${rows.map { it.type }}")
            fx.update(control.id, "traits" to strings("t-one")).okResult()
            assertEquals(listOf("t-one"), fx.get(control.id).traits, "control: the same trait change on an unaffected type works")
        }

    @Test
    fun `S7 a cross-root move into a root that declares the type none is refused for the item and for a descendant`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val r1 = fx.make("R1")
            val r2 = fx.make("R2")
            fx.push(r2, ROOT_SHIFTY_NONE)
            val item = fx.make("Item", r1, "shifty")
            val container = fx.make("Container", r1, "test-item")
            val inner = fx.make("Inner", container, "shifty")
            val harmless = fx.make("Harmless", r1, "test-item")
            val beforeItem = fx.get(item.id)
            val beforeContainer = fx.get(container.id)
            val beforeInner = fx.get(inner.id)
            val moveTo = "parentId" to JsonPrimitive(r2.id.toString())

            val (itemBody, itemRows) = fx.rig.written { fx.update(item.id, moveTo) }
            val (treeBody, treeRows) = fx.rig.written { fx.update(container.id, moveTo) }

            assertRejected(itemBody.errorAt(), item.id, "queue", "reparent")
            assertRejected(treeBody.errorAt(), inner.id, "queue", "reparent")
            assertEquals(beforeItem, fx.get(item.id), "the item was not moved")
            assertEquals(beforeContainer, fx.get(container.id), "the container was not moved")
            assertEquals(beforeInner, fx.get(inner.id), "the descendant was not re-pinned")
            assertTrue(itemRows.noWriteEvents(), "nothing recorded: ${itemRows.map { it.type }}")
            assertTrue(treeRows.noWriteEvents(), "nothing recorded: ${treeRows.map { it.type }}")
            fx.update(harmless.id, moveTo).okResult()
            assertEquals(r2.id, fx.get(harmless.id).parentId, "control: the same move of an unaffected item works")
        }

    @Test
    fun `S7 a pushed none type does not change an existing item by itself`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = fx(dir)
            val root = fx.make("R")
            val item = fx.make("I", root, "shifty")
            val before = fx.get(item.id)

            fx.push(root, ROOT_SHIFTY_NONE)

            assertEquals(before, fx.get(item.id), "a push alone never touches a stored row")
            assertNotNull(fx.get(item.id).role)
        }
}

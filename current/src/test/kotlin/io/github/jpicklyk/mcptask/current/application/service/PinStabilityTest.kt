package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SchemaPin
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.missingKeys
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.text
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVER_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n" +
        "  feature-task:\n" +
        "    lifecycle: manual\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "traits:\n" +
        "  t-one:\n    notes: []\n" +
        "  t-two:\n    notes: []\n"

/** Per-root document, revision one: `root-only` is declared per-root only and requires `rspec`. */
private const val ROOT_V1 =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: permanent\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "  root-only:\n" +
        "    lifecycle: manual\n" +
        "    notes:\n" +
        "      - key: rspec\n        role: queue\n        required: true\n" +
        "  cascade-type:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

/** Revision two: `root-only` additionally requires `extra` (a new queue-phase gate). */
private const val ROOT_V2 =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: permanent\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "  root-only:\n" +
        "    lifecycle: manual\n" +
        "    notes:\n" +
        "      - key: rspec\n        role: queue\n        required: true\n" +
        "      - key: extra\n        role: queue\n        required: true\n" +
        "  cascade-type:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

private const val OTHER_ROOT_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n"

/**
 * Independent tests (test-author seat, item 934b25e4) for the schema pin: scenarios S1, S3, S5, S6, S9, S10 and S12 of the
 * frozen test-plan plus probes, driven through the production composition ([EventLogRig]) over a real SQLite file.
 *
 * Oracles (frozen task-scope AC1, AC3, AC7, decisions D2 D4 D5 D6 D7, plan s3.8; none read from the implementation):
 * - a pin names the server and root HEAD version ids at pin time (NULL for a scope with no head or a reset head), the base
 *   schema key and layer its type resolves to (per-root exact type, server exact type, per-root `default`, server
 *   `default`; none for a schema-free type) and that schema's lifecycle (the literals written in the YAML fixtures above);
 * - exactly one `item.pinned {from, to, reason}` event is recorded at each pin point: create, a type change
 *   (`type_change`), a move under another root (`reparent`, pinned against the NEW root), a traits change
 *   (`trait_change`), a transition out of terminal (`reopen`, also for the cascade); precedence type_change > reparent >
 *   trait_change; a title-only patch or a same-root move records none and leaves the six columns alone;
 * - a pinned item resolves its schema from the pinned documents: a later push, rollback or reset never changes its gates
 *   until a pin point fires; an item with no pin resolves at the heads; a pinned key the pinned document lacks is
 *   schema-free; a read fault during create fails closed (`unavailable`) and writes nothing.
 *
 * Every absence assertion is paired with a control through the same call shape that does write (or, for the gate, an item
 * created after the push that DOES see the new requirement).
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PinStabilityTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun rigOf(
        dir: Path,
        clock: SettableClock = SettableClock(),
        serverYaml: String = SERVER_YAML
    ) = EventLogRig.build(db.db, dir, serverYaml, clock)

    private suspend fun EventLogRig.createItem(
        title: String,
        parent: WorkItem? = null,
        type: String = "test-item"
    ): WorkItem = ctx.itemCommandService.create(ItemCreateCommand(parentId = parent?.id, title = title, type = type)).orFail()

    private suspend fun EventLogRig.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(ctx.configService).push(root.id, yaml), "push of '${yaml.take(30)}'")
    }

    /** Pushes a content-identical but fingerprint-distinct revision, so the root head moves without changing any schema. */
    private suspend fun EventLogRig.bump(
        root: WorkItem,
        base: String,
        rev: Int
    ) = push(root, "$base# rev $rev\n")

    private suspend fun EventLogRig.rootHead(root: WorkItem) = raw.configStore().head(ConfigScope.ROOT, root.id)

    private suspend fun EventLogRig.serverHead() = raw.configStore().head(ConfigScope.SERVER, null)

    private suspend fun EventLogRig.stored(item: WorkItem): WorkItem = checkNotNull(raw.workItemRepository().getById(item.id))

    private suspend fun EventLogRig.pinOf(item: WorkItem): SchemaPin? = stored(item).pin

    private fun List<EventRecord>.pins() = filter { it.type == "item.pinned" }

    private fun EventRecord.side(name: String): JsonObject? = payload()[name]?.takeIf { it !is JsonNull }?.jsonObject

    private fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun columns(
        jdbcUrl: String,
        id: UUID
    ): Map<String, String?> =
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn
                .prepareStatement(
                    "SELECT pinned_schema_key, pinned_schema_layer, lifecycle, pinned_at, length(pinned_at), " +
                        "pinned_server_cfg IS NOT NULL, pinned_root_cfg IS NOT NULL FROM work_items WHERE id = ?"
                ).use { ps ->
                    ps.setBytes(1, uuidBytes(id))
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "row $id not found" }
                        mapOf(
                            "key" to rs.getString(1),
                            "layer" to rs.getString(2),
                            "lifecycle" to rs.getString(3),
                            "pinned_at" to rs.getString(4),
                            "len" to rs.getString(5),
                            "hasServer" to rs.getString(6),
                            "hasRoot" to rs.getString(7)
                        )
                    }
                }
        }

    private fun assertPin(
        label: String,
        pin: SchemaPin?,
        server: UUID?,
        root: UUID?,
        key: String?,
        layer: PinLayer?,
        lifecycle: LifecycleMode?
    ) {
        val p = assertNotNull(pin, "$label: the item is pinned")
        assertEquals(server, p.serverVersionId, "$label: server version id")
        assertEquals(root, p.rootVersionId, "$label: root version id")
        assertEquals(key, p.schemaKey, "$label: base schema key")
        assertEquals(layer, p.schemaLayer, "$label: base schema layer")
        assertEquals(lifecycle, p.lifecycle, "$label: lifecycle")
    }

    private fun assertEventRef(
        label: String,
        ref: JsonObject?,
        server: UUID?,
        root: UUID?,
        key: String?,
        layer: String?
    ) {
        val r = assertNotNull(ref, "$label: the ref is present")
        assertEquals(server?.toString(), r.text("server"), "$label: server")
        assertEquals(root?.toString(), r.text("root"), "$label: root")
        assertEquals(key, r.text("key"), "$label: key")
        assertEquals(layer, r.text("layer"), "$label: layer")
    }

    // ---------------------------------------------------------------------------------------------
    // S1 - the pin written at create
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 create pins both heads, the base schema and its lifecycle, and records exactly one create event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val clock = SettableClock()
            val rig = rigOf(dir, clock)
            val server = assertNotNull(rig.serverHead(), "fixture: the mounted server file is the server head")
            val root = rig.createItem("R")
            assertPin(
                "root before any per-root config",
                rig.pinOf(root),
                server.versionId,
                null,
                "test-item",
                PinLayer.SERVER,
                LifecycleMode.MANUAL
            )
            rig.push(root, ROOT_V1)
            val head = assertNotNull(rig.rootHead(root))

            val (child, rows) = rig.written { rig.createItem("C", root, "feature-task") }

            val pin = rig.pinOf(child)
            assertPin(
                "S1 per-root exact type",
                pin,
                server.versionId,
                head.versionId,
                "feature-task",
                PinLayer.ROOT,
                LifecycleMode.PERMANENT
            )
            assertEquals(clock.now(), pin!!.pinnedAt, "pinned_at is the unit instant")
            assertEquals(listOf("item.created", "item.pinned"), rows.map { it.type }, "one created row and one pin row")
            val event = rows.pins().single()
            assertEquals(child.id, event.entityId)
            assertEquals(root.id, event.rootId)
            assertEquals("create", event.str("reason"))
            assertNull(event.side("from"), "a create has no previous pin")
            assertEventRef("S1 create event", event.side("to"), server.versionId, head.versionId, "feature-task", "root")

            val cols = columns(db.jdbcUrl, child.id)
            assertEquals("feature-task", cols["key"])
            assertEquals("root", cols["layer"])
            assertEquals("permanent", cols["lifecycle"])
            assertEquals("2026-03-01 10:00:00.000", cols["pinned_at"], "UTC text in the 23-character column shape")
            assertEquals("23", cols["len"])

            // A type only the server declares still pins BOTH heads; the layer is the one that declared the schema.
            val serverTyped = rig.createItem("S", root, "test-item")
            assertPin(
                "S1 server-declared type",
                rig.pinOf(serverTyped),
                server.versionId,
                head.versionId,
                "test-item",
                PinLayer.SERVER,
                LifecycleMode.MANUAL
            )
        }

    @Test
    fun `S1 a per-root default does not shadow a server schema of the exact type for a NEW pin`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val server = assertNotNull(rig.serverHead())
            val root = rig.createItem("R")
            rig.push(root, "work_item_schemas:\n  default:\n    lifecycle: permanent\n    notes: []\n")
            val head = assertNotNull(rig.rootHead(root))

            val child = rig.createItem("C", root, "test-item")

            // New pins use the 4.0 order (per-root type, server type, per-root default, server default); only the
            // upgrade backfill keeps the 3.x order, where the per-root default would have won.
            assertPin(
                "exact server type beats the per-root default",
                rig.pinOf(child),
                server.versionId,
                head.versionId,
                "test-item",
                PinLayer.SERVER,
                LifecycleMode.MANUAL
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - forItem: a pinned item keeps resolving its pinned documents
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 a later push adds a required note, the pinned item still starts, a new item is gated by it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val x = rig.createItem("X", root, "root-only")
            rig.push(root, ROOT_V2)
            val y = rig.createItem("Y", root, "root-only")
            d.note(x, "rspec", "queue")
            d.note(y, "rspec", "queue")

            val rx = d.advance(x, "start")
            val ry = d.advance(y, "start")

            assertEquals(true, rx.flag("ok"), "X is pinned to revision one, which does not require 'extra': $rx")
            assertEquals(Role.WORK, d.role(x))
            assertEquals(false, ry.flag("ok"), "control: Y is pinned to revision two and is gated by 'extra': $ry")
            assertEquals("gate_blocked", ry.errorCode(), "$ry")
            assertEquals(listOf("extra"), ry.missingKeys(), "$ry")
            assertEquals(Role.QUEUE, d.role(y))
        }

    @Test
    fun `S3 a rollback after the pin does not change what the pinned item resolves`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            rig.push(root, ROOT_V2)
            val x = rig.createItem("X", root, "root-only")
            d.note(x, "rspec", "queue")
            val gated = d.advance(x, "start")
            assertEquals("gate_blocked", gated.errorCode(), "fixture: pinned to revision two it needs 'extra': $gated")

            assertIs<ConfigRollbackResult.Success>(checkNotNull(rig.ctx.configService).rollback(root.id, 1))
            val after = d.advance(x, "start")

            assertEquals(false, after.flag("ok"), "the head is now revision one's content, the pin still names revision two: $after")
            assertEquals(listOf("extra"), after.missingKeys(), "$after")
        }

    @Test
    fun `probe - a row with no pin resolves at the heads`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val pinned = rig.createItem("Pinned", root, "root-only")
            val unpinned = d.item("Unpinned", Role.QUEUE, type = "root-only", parent = root)
            assertNull(rig.pinOf(unpinned), "fixture: a raw-inserted row carries no pin")
            rig.push(root, ROOT_V2)
            d.note(pinned, "rspec", "queue")
            d.note(unpinned, "rspec", "queue")

            val rp = d.advance(pinned, "start")
            val ru = d.advance(unpinned, "start")

            assertEquals(true, rp.flag("ok"), "control: the pinned item keeps revision one: $rp")
            assertEquals(false, ru.flag("ok"), "the unpinned row resolves at the head, which now requires 'extra': $ru")
            assertEquals("gate_blocked", ru.errorCode(), "$ru")
        }

    // ---------------------------------------------------------------------------------------------
    // S5 - reopen
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 reopen of a terminal item pinned to an old version re-pins to the heads after the transition row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val server = assertNotNull(rig.serverHead())
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val head1 = assertNotNull(rig.rootHead(root))
            val x = rig.createItem("X", root, "root-only")
            rig.push(root, ROOT_V2)
            val head2 = assertNotNull(rig.rootHead(root))
            assertNotEquals(head1.versionId, head2.versionId)
            d.setRole(x, Role.TERMINAL)
            assertEquals(head1.versionId, rig.pinOf(x)!!.rootVersionId, "fixture: still pinned to revision one")

            val (result, rows) = rig.written { d.advance(x, "reopen") }

            assertEquals(true, result.flag("ok"), "$result")
            assertEquals(Role.QUEUE, d.role(x))
            assertPin("S5 after reopen", rig.pinOf(x), server.versionId, head2.versionId, "root-only", PinLayer.ROOT, LifecycleMode.MANUAL)
            val event = rows.pins().single()
            assertEquals("reopen", event.str("reason"))
            assertEquals(x.id, event.entityId)
            assertEventRef("S5 from", event.side("from"), server.versionId, head1.versionId, "root-only", "root")
            assertEventRef("S5 to", event.side("to"), server.versionId, head2.versionId, "root-only", "root")
            val order = rows.map { it.type }
            assertTrue(
                order.indexOf("item.transitioned") in 0 until order.indexOf("item.pinned"),
                "the pin row follows the transition row: $order"
            )

            // The new pin governs what happens next: revision two requires 'extra' for the queue phase.
            d.note(x, "rspec", "queue")
            val start = d.advance(x, "start")
            assertEquals("gate_blocked", start.errorCode(), "the reopened item is gated by the new pin: $start")
            assertEquals(listOf("extra"), start.missingKeys(), "$start")
        }

    @Test
    fun `S5 the reopen cascade re-pins the reopened parent too`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val parent = rig.createItem("P", root, "cascade-type")
            val child = rig.createItem("C", parent, "cascade-type")
            rig.bump(root, ROOT_V1, 2)
            val head2 = assertNotNull(rig.rootHead(root))
            d.setRole(child, Role.TERMINAL)
            d.setRole(parent, Role.TERMINAL)

            val (result, rows) = rig.written { d.advance(child, "reopen") }

            assertEquals(true, result.flag("ok"), "$result")
            assertEquals(1, result.arr("cascades").size, "control: the parent is reopened by the cascade: $result")
            assertEquals(Role.WORK, d.role(parent), "control: the cascade reopened the parent (terminal to work)")
            assertEquals(head2.versionId, rig.pinOf(child)!!.rootVersionId)
            assertEquals(head2.versionId, rig.pinOf(parent)!!.rootVersionId, "a cascade out of terminal is a pin point as well")
            val pins = rows.pins()
            assertEquals(setOf(child.id, parent.id), pins.map { it.entityId }.toSet())
            assertEquals(2, pins.size, "one pin row per reopened item")
            assertTrue(pins.all { it.str("reason") == "reopen" }, "$pins")
        }

    // ---------------------------------------------------------------------------------------------
    // S6 - the other pin points, and the writes that must not pin
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a title-only patch leaves the six columns alone, and a type change re-pins with type_change`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val server = assertNotNull(rig.serverHead())
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val head1 = assertNotNull(rig.rootHead(root))
            val c = rig.createItem("C", root, "test-item")
            val pin0 = assertNotNull(rig.pinOf(c))
            rig.bump(root, ROOT_V1, 2)
            val head2 = assertNotNull(rig.rootHead(root))

            val (_, titleRows) = rig.written { rig.itemsWrite(updateOp(c.id, "title" to JsonPrimitive("C renamed"))).okResult() }

            assertTrue(titleRows.any { it.type == "item.updated" }, "control: the title patch did write: ${titleRows.map { it.type }}")
            assertTrue(titleRows.pins().isEmpty(), "a title-only patch records no pin row")
            assertEquals(pin0, rig.pinOf(c), "a title-only patch leaves the pin as it was, although the head moved")

            val (_, typeRows) = rig.written { rig.itemsWrite(updateOp(c.id, "type" to JsonPrimitive("feature-task"))).okResult() }

            assertPin(
                "S6 type change",
                rig.pinOf(c),
                server.versionId,
                head2.versionId,
                "feature-task",
                PinLayer.ROOT,
                LifecycleMode.PERMANENT
            )
            val event = typeRows.pins().single()
            assertEquals("type_change", event.str("reason"))
            assertEventRef("S6 type from", event.side("from"), server.versionId, head1.versionId, "test-item", "server")
            assertEventRef("S6 type to", event.side("to"), server.versionId, head2.versionId, "feature-task", "root")
        }

    @Test
    fun `S6 a traits change re-pins with trait_change, while an unchanged, kept or already-empty traits list does not`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val c = rig.createItem("C", root, "test-item")
            val pinBefore = assertNotNull(rig.pinOf(c))

            // Empty vs absent vs null: an empty list on an item with no traits changes nothing; null keeps; absent keeps.
            val (_, emptyRows) =
                rig.written {
                    rig
                        .itemsWrite(
                            updateOp(
                                c.id,
                                "traits" to JsonArray(emptyList()),
                                "title" to JsonPrimitive("t1")
                            )
                        ).okResult()
                }
            val (_, nullRows) =
                rig.written {
                    rig
                        .itemsWrite(
                            updateOp(c.id, "traits" to JsonNull, "title" to JsonPrimitive("t2"))
                        ).okResult()
                }
            assertTrue(
                emptyRows.any { it.type == "item.updated" } && nullRows.any { it.type == "item.updated" },
                "control: both calls wrote"
            )
            assertTrue(emptyRows.pins().isEmpty() && nullRows.pins().isEmpty(), "an unchanged traits list is not a pin point")
            assertEquals(pinBefore, rig.pinOf(c))

            rig.bump(root, ROOT_V1, 2)
            val head2 = assertNotNull(rig.rootHead(root))
            val (_, changeRows) = rig.written { rig.itemsWrite(updateOp(c.id, "traits" to strings("t-one"))).okResult() }

            val event = changeRows.pins().single()
            assertEquals("trait_change", event.str("reason"))
            assertEquals(head2.versionId, rig.pinOf(c)!!.rootVersionId, "the pin moved to the head")
            assertEventRef(
                "S6 trait to",
                event.side("to"),
                assertNotNull(rig.serverHead()).versionId,
                head2.versionId,
                "test-item",
                "server"
            )

            // The same list again is not a change (the head moved on, so a wrongly fired pin would be visible).
            rig.bump(root, ROOT_V1, 3)
            val pinAfterChange = assertNotNull(rig.pinOf(c))
            val (_, sameRows) = rig.written { rig.itemsWrite(updateOp(c.id, "traits" to strings("t-one"))).okResult() }
            assertTrue(sameRows.pins().isEmpty(), "re-sending the same traits records no pin row")
            assertEquals(pinAfterChange, rig.pinOf(c))

            // Clearing is a change.
            val (_, clearRows) = rig.written { rig.itemsWrite(updateOp(c.id, "traits" to JsonArray(emptyList()))).okResult() }
            assertEquals("trait_change", clearRows.pins().single().str("reason"))
        }

    @Test
    fun `S6 one patch that changes type and traits records a single pin row with reason type_change`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val c = rig.createItem("C", root, "test-item")
            rig.bump(root, ROOT_V1, 2)

            val (_, rows) =
                rig.written {
                    rig.itemsWrite(updateOp(c.id, "type" to JsonPrimitive("feature-task"), "traits" to strings("t-two"))).okResult()
                }

            val event = rows.pins().single()
            assertEquals("type_change", event.str("reason"), "type_change outranks trait_change")
            assertEquals("feature-task", rig.pinOf(c)!!.schemaKey)
        }

    @Test
    fun `S6 a move under another root re-pins against the NEW root head, a move within the root does not`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val server = assertNotNull(rig.serverHead())
            val r1 = rig.createItem("R1")
            val r2 = rig.createItem("R2")
            rig.push(r1, ROOT_V1)
            rig.push(r2, OTHER_ROOT_YAML)
            val head1 = assertNotNull(rig.rootHead(r1))
            val head2 = assertNotNull(rig.rootHead(r2))
            val c = rig.createItem("C", r1, "test-item")
            assertEquals(head1.versionId, rig.pinOf(c)!!.rootVersionId, "fixture")

            val (_, moveRows) = rig.written { rig.itemsWrite(updateOp(c.id, "parentId" to JsonPrimitive(r2.id.toString()))).okResult() }

            assertPin(
                "S6 cross-root move",
                rig.pinOf(c),
                server.versionId,
                head2.versionId,
                "test-item",
                PinLayer.ROOT,
                LifecycleMode.PERMANENT
            )
            val event = moveRows.pins().single()
            assertEquals("reparent", event.str("reason"))
            assertEventRef("S6 reparent from", event.side("from"), server.versionId, head1.versionId, "test-item", "server")
            assertEventRef("S6 reparent to", event.side("to"), server.versionId, head2.versionId, "test-item", "root")

            // Same-root move: the root head moves first, so a pin that fired anyway would be visible.
            val d = rig.createItem("D", r2, "test-item")
            rig.bump(r2, OTHER_ROOT_YAML, 2)
            val pinBefore = assertNotNull(rig.pinOf(c))
            val (_, sameRows) = rig.written { rig.itemsWrite(updateOp(c.id, "parentId" to JsonPrimitive(d.id.toString()))).okResult() }
            assertTrue(sameRows.any { it.type == "item.reparented" }, "control: the move did write: ${sameRows.map { it.type }}")
            assertEquals(d.id, rig.stored(c).parentId, "control: C now sits under D")
            assertTrue(sameRows.pins().isEmpty(), "a move within the same root is not a pin point")
            assertEquals(pinBefore, rig.pinOf(c))
        }

    @Test
    fun `S6 moving an item to the root pins against its own (config-less) root, and reparent outranks trait_change`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val server = assertNotNull(rig.serverHead())
            val r1 = rig.createItem("R1")
            rig.push(r1, ROOT_V1)
            val head1 = assertNotNull(rig.rootHead(r1))
            val c = rig.createItem("C", r1, "test-item")

            val (_, toRootRows) = rig.written { rig.itemsWrite(updateOp(c.id, "clear" to strings("parentId"))).okResult() }

            assertEquals(c.id, rig.stored(c).rootId, "control: C is now its own root")
            assertPin("S6 moved to root", rig.pinOf(c), server.versionId, null, "test-item", PinLayer.SERVER, LifecycleMode.MANUAL)
            assertEquals("reparent", toRootRows.pins().single().str("reason"))

            val (_, backRows) =
                rig.written {
                    rig.itemsWrite(updateOp(c.id, "parentId" to JsonPrimitive(r1.id.toString()), "traits" to strings("t-two"))).okResult()
                }

            assertEquals("reparent", backRows.pins().single().str("reason"), "reparent outranks trait_change")
            assertEquals(head1.versionId, rig.pinOf(c)!!.rootVersionId)
        }

    // ---------------------------------------------------------------------------------------------
    // S9 - a read fault fails closed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 a per-root read fault during create fails closed as unavailable and writes no row, no pin and no event`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, P11_BASE_YAML)
            val d = P11Driver(rig)
            val f = d.configFaultFixture()
            val (control, controlRows) = rig.written { rig.itemsWrite(createOp("control child", f.root.id)) }
            control.okResult()
            assertEquals(listOf("item.created", "item.pinned"), controlRows.map { it.type }, "control: a healthy create pins")

            d.breakConfigReads()
            val items = rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM work_items")
            val seq = rig.maxSeq()
            val result = rig.itemsWrite(createOp("faulted child", f.root.id))

            assertEquals("unavailable", result.errorAt(0)["code"]!!.jsonPrimitive.content, "$result")
            assertEquals(items, rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM work_items"), "no row written")
            assertEquals(seq, rig.maxSeq(), "no event written (no item.created, no item.pinned)")
        }

    // ---------------------------------------------------------------------------------------------
    // S10 / S12 - the views and a reset head
    // ---------------------------------------------------------------------------------------------

    private fun JsonObject.field(name: String): JsonObject? = this[name]?.takeIf { it !is JsonNull }?.jsonObject

    private fun JsonObject.noteKeys(): List<String> =
        getValue("notes").jsonArray.map {
            it.jsonObject
                .getValue("key")
                .jsonPrimitive.content
        }

    private fun JsonObject.versionOf(name: String): Int? = this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

    @Test
    fun `S10 get, scoped overview and schema carry schemaVersion and stale, compact views never do`(
        @TempDir dir: Path
    ): Unit =
        irApp(db.db, dir, yaml = SERVER_YAML) { rig ->
            val server = assertNotNull(rig.serverHead())
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val head1 = assertNotNull(rig.rootHead(root))
            val x = rig.createItem("X", root, "root-only")
            val fp1 = rig.raw.configStore().computeFingerprint(ROOT_V1)

            val fresh = select("""{"select":"get","itemId":"${x.id}"}""")
            assertEquals(200, fresh.status, fresh.raw)
            val sv = assertNotNull(fresh.data.field("schemaVersion"), "a pinned item renders its pin: ${fresh.raw}")
            assertEquals(server.version, sv.versionOf("server"))
            assertEquals(head1.version, sv.versionOf("root"))
            assertEquals("root-only", sv.getValue("key").jsonPrimitive.content)
            assertEquals("root", sv.getValue("layer").jsonPrimitive.content)
            assertFalse("stale" in fresh.data, "the pin names the heads, so the item is not stale: ${fresh.raw}")

            val schemaFresh = select("""{"select":"schema","itemId":"${x.id}"}""")
            assertEquals(listOf("rspec"), schemaFresh.data.noteKeys(), schemaFresh.raw)
            assertEquals(
                fp1,
                schemaFresh.data
                    .getValue("configFingerprint")
                    .jsonPrimitive.content
            )
            assertEquals(head1.version, schemaFresh.data.field("schemaVersion")!!.versionOf("root"))
            assertFalse("stale" in schemaFresh.data, schemaFresh.raw)

            // Push revision two: the pinned item is now stale but keeps resolving revision one.
            rig.push(root, ROOT_V2)
            val fp2 = rig.raw.configStore().computeFingerprint(ROOT_V2)
            val y = rig.createItem("Y", root, "root-only")
            val stale = select("""{"select":"get","itemId":"${x.id}"}""")
            assertEquals(
                true,
                stale.data["stale"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean(),
                stale.raw
            )
            assertEquals(head1.version, stale.data.field("schemaVersion")!!.versionOf("root"), "the pin still names revision one")
            val schemaStale = select("""{"select":"schema","itemId":"${x.id}"}""")
            assertEquals(listOf("rspec"), schemaStale.data.noteKeys(), "the pinned schema is still revision one's")
            assertEquals(
                fp1,
                schemaStale.data
                    .getValue("configFingerprint")
                    .jsonPrimitive.content,
                "the fingerprint is the pinned layer's"
            )
            assertEquals("true", schemaStale.data["stale"]?.jsonPrimitive?.content)

            val control = select("""{"select":"get","itemId":"${y.id}"}""")
            assertFalse("stale" in control.data, "control: an item pinned after the push is not stale: ${control.raw}")
            val schemaY = select("""{"select":"schema","itemId":"${y.id}"}""")
            assertEquals(listOf("rspec", "extra"), schemaY.data.noteKeys())
            assertEquals(
                fp2,
                schemaY.data
                    .getValue("configFingerprint")
                    .jsonPrimitive.content
            )

            // The scoped overview renders its subject's pin: the root was pinned before it had a per-root head, so it has no root
            // member at all (a null is omitted) and is stale against the head that now exists.
            val overview = select("""{"select":"overview","itemId":"${root.id}"}""")
            val subject = assertNotNull(overview.data.field("item"), overview.raw)
            val rootSv = assertNotNull(subject.field("schemaVersion"), "the scoped overview item carries the pin: ${overview.raw}")
            assertEquals(server.version, rootSv.versionOf("server"))
            assertFalse("root" in rootSv, "a null root pin is omitted: $rootSv")
            assertEquals("test-item", rootSv.getValue("key").jsonPrimitive.content)
            assertEquals("server", rootSv.getValue("layer").jsonPrimitive.content)
            assertEquals("true", subject["stale"]?.jsonPrimitive?.content, "null (pinned) vs a root head that now exists is stale")
            // The overview's children are compact views: neither pin member appears.
            for (entry in overview.items) {
                assertFalse("schemaVersion" in entry || "stale" in entry, "compact entries never carry a pin: $entry")
            }
            assertTrue(overview.items.isNotEmpty(), "vacuity control: the overview has children to check")

            val list = select("""{"select":"list"}""")
            assertTrue(list.items.size >= 3, "vacuity control: the list returns the three items")
            for (entry in list.items) {
                assertFalse("schemaVersion" in entry || "stale" in entry, "list entries never carry a pin: $entry")
            }

            // An item that was never pinned renders neither member.
            val raw = rig.raw.workItemRepository().create(WorkItem(title = "raw", type = "test-item"))
            val rawView = select("""{"select":"get","itemId":"${raw.id}"}""")
            assertFalse("schemaVersion" in rawView.data || "stale" in rawView.data, "an unpinned row renders no pin: ${rawView.raw}")
            val rawSchema = select("""{"select":"schema","itemId":"${raw.id}"}""")
            assertFalse("schemaVersion" in rawSchema.data || "stale" in rawSchema.data, rawSchema.raw)
        }

    @Test
    fun `S12 after a root config reset the pinned item still resolves its pinned document and is stale, and a new item has no root pin`(
        @TempDir dir: Path
    ): Unit =
        irApp(db.db, dir, yaml = SERVER_YAML) { rig ->
            val d = P11Driver(rig)
            val server = assertNotNull(rig.serverHead())
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val head1 = assertNotNull(rig.rootHead(root))
            val x = rig.createItem("X", root, "root-only")

            assertEquals(
                true,
                checkNotNull(rig.ctx.configService).reset(root.id).getOrNull(),
                "the reset is accepted"
            )

            val view = select("""{"select":"get","itemId":"${x.id}"}""")
            assertEquals(
                "true",
                view.data["stale"]?.jsonPrimitive?.content,
                "a reset head counts as no head, so the pin is stale: ${view.raw}"
            )
            assertEquals(
                head1.version,
                view.data.field("schemaVersion")!!.versionOf("root"),
                "the pin still names the version it was taken at"
            )
            val schema = select("""{"select":"schema","itemId":"${x.id}"}""")
            assertEquals(listOf("rspec"), schema.data.noteKeys(), "the pinned document is still served after the reset: ${schema.raw}")
            val blocked = d.advance(x, "start")
            assertEquals("gate_blocked", blocked.errorCode(), "the pinned document's gate still applies after the reset: $blocked")
            assertEquals(listOf("rspec"), blocked.missingKeys(), "$blocked")
            d.note(x, "rspec", "queue")
            assertEquals(true, d.advance(x, "start").flag("ok"), "control: with the note filled the same call starts")

            val fresh = rig.createItem("Fresh", root, "feature-task")
            assertPin("S12 after reset", rig.pinOf(fresh), server.versionId, null, "feature-task", PinLayer.SERVER, LifecycleMode.MANUAL)
            val freshView = select("""{"select":"get","itemId":"${fresh.id}"}""")
            assertFalse("stale" in freshView.data, "control: null pinned, null head is not stale: ${freshView.raw}")
            assertFalse("root" in freshView.data.field("schemaVersion")!!, freshView.raw)
        }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe - a pinned schema key the pinned document does not declare resolves schema-free`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val d = P11Driver(rig)
            val root = rig.createItem("R")
            rig.push(root, ROOT_V1)
            val x = rig.createItem("X", root, "root-only")
            val control = rig.createItem("Control", root, "root-only")
            assertEquals("gate_blocked", d.advance(control, "start").errorCode(), "control: with the key intact the gate blocks")

            DriverManager.getConnection(db.jdbcUrl).use { conn ->
                conn.prepareStatement("UPDATE work_items SET pinned_schema_key = 'no-such-key' WHERE id = ?").use {
                    it.setBytes(1, uuidBytes(x.id))
                    assertEquals(1, it.executeUpdate())
                }
            }
            val result = d.advance(x, "start")

            assertEquals(
                true,
                result.flag("ok"),
                "an unknown pinned key is schema-free, so no gate applies and nothing crashes: $result"
            )
            assertEquals(Role.WORK, d.role(x))
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A `repin` element for [id] (a full UUID or any prefix the caller formats itself). */
private fun repinOp(
    id: String,
    vararg extra: Pair<String, JsonElement>
): JsonObject =
    buildJsonObject {
        put("op", "repin")
        put("id", id)
        extra.forEach { (k, v) -> put(k, v) }
    }

private fun repinOp(
    id: UUID,
    vararg extra: Pair<String, JsonElement>
): JsonObject = repinOp(id.toString(), *extra)

/** A per-root document that declares `feature-task` itself (the server's declares it with a required `spec` note). */
private const val ROOT_FEATURE_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n"

/**
 * Independent tests (test-author seat, item 934b25e4) for the `items_write` op `repin`: scenario S4 of the frozen test-plan,
 * its scope rules and probes, through the real dispatcher over the production composition.
 *
 * Oracles (frozen task-scope AC5 and the Surfaces paragraph, plan s3.8; none read from the implementation): `repin`
 * recomputes the item's pin against the CURRENT heads and the base schema its type resolves to now; a recomputed pin
 * equal to the stored one writes nothing and records nothing (`repinned: false`, `version` unchanged); otherwise the item's
 * `version` goes up by one, one `item.pinned` event with reason `repin` is recorded and the element answers
 * `{ok, id, version, repinned: true, schemaVersion {server, root, key, layer}}`; `expectedVersion` guards it like `update`
 * (`version_conflict {kind, id, expected, actual}`, nothing written); the scope rules are the update rules (`forbidden
 * {scope, required}` before any write); a repeated `idempotencyKey` replays the stored result; a per-root read fault fails
 * closed as `unavailable` and leaves the pin as it was.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RepinItemOpTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit() = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))

    private suspend fun IwKit.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(rig.ctx.configService).push(root.id, yaml), "push of '${yaml.take(30)}'")
    }

    private suspend fun IwKit.serverVersion(): Int = assertNotNull(rig.raw.configStore().head(ConfigScope.SERVER, null)).version

    private suspend fun IwKit.rootHead(root: WorkItem) = assertNotNull(rig.raw.configStore().head(ConfigScope.ROOT, root.id))

    // ------------------------------------------------------------------ S4 the move

    @Test
    fun `S4 repin moves the pin to the heads and the current base schema, bumps the version and records one repin event`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            val before = assertNotNull(k.item(item.id))
            val pinBefore = assertNotNull(before.pin)
            assertEquals("feature-task", pinBefore.schemaKey)
            assertEquals(PinLayer.SERVER, pinBefore.schemaLayer, "fixture: with no per-root config the server declares feature-task")
            assertNull(pinBefore.rootVersionId, "fixture: the root had no config head when the item was pinned")
            k.push(root, ROOT_FEATURE_YAML)
            val head = k.rootHead(root)

            val (body, rows) = k.rig.written { k.call(repinOp(item.id)).envelope.body }

            val ok = body.okResult()
            assertEquals(setOf("ok", "id", "version", "repinned", "schemaVersion"), ok.keys, "the repin element's exact members")
            assertEquals(item.id.toString(), ok.getValue("id").jsonPrimitive.content)
            assertEquals("true", ok.getValue("repinned").jsonPrimitive.content)
            assertEquals(before.version + 1, ok.getValue("version").jsonPrimitive.long, "a changed pin bumps the version by one")
            val sv = ok.getValue("schemaVersion").jsonObject
            assertEquals(k.serverVersion().toString(), sv.str("server"))
            assertEquals(head.version.toString(), sv.str("root"))
            assertEquals("feature-task", sv.str("key"))
            assertEquals("root", sv.str("layer"), "the per-root document now declares the type, so the base schema moves layers")

            val after = assertNotNull(k.item(item.id))
            assertEquals(before.version + 1, after.version)
            val pin = assertNotNull(after.pin)
            assertEquals(head.versionId, pin.rootVersionId)
            assertEquals(pinBefore.serverVersionId, pin.serverVersionId, "the server head did not move")
            assertEquals(PinLayer.ROOT, pin.schemaLayer)
            assertEquals(LifecycleMode.PERMANENT, pin.lifecycle, "the lifecycle follows the new base schema")
            val event = rows.filter { it.type == "item.pinned" }.single()
            assertEquals("repin", event.str("reason"))
            assertEquals(item.id, event.entityId)
            assertEquals(
                "server",
                event
                    .payload()
                    .getValue("from")
                    .jsonObject
                    .str("layer")
            )
            assertEquals(
                "root",
                event
                    .payload()
                    .getValue("to")
                    .jsonObject
                    .str("layer")
            )
        }

    @Test
    fun `S4 a second repin changes nothing, records nothing and answers repinned false`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            k.push(root, ROOT_FEATURE_YAML)
            k
                .call(repinOp(item.id))
                .envelope.body
                .okResult()
            val settled = assertNotNull(k.item(item.id))
            val snapshot = k.snapshot()
            val mark = k.rig.maxSeq()

            val ok =
                k
                    .call(repinOp(item.id))
                    .envelope.body
                    .okResult()

            assertEquals("false", ok.getValue("repinned").jsonPrimitive.content)
            assertEquals(settled.version, ok.getValue("version").jsonPrimitive.long, "an identical pin leaves the version alone")
            assertEquals(snapshot, k.snapshot(), "no row of work_items, notes, dependencies or events changed")
            assertEquals(mark, k.rig.maxSeq(), "no event row was recorded")
            assertEquals(settled.pin, assertNotNull(k.item(item.id)).pin)
        }

    @Test
    fun `S4 a repin after a later push moves again, and a repin of a never-pinned row pins it`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            k.push(root, ROOT_FEATURE_YAML)
            k
                .call(repinOp(item.id))
                .envelope.body
                .okResult()
            val first = assertNotNull(k.item(item.id))
            k.push(root, "$ROOT_FEATURE_YAML# second revision\n")
            val head2 = k.rootHead(root)

            val ok =
                k
                    .call(repinOp(item.id))
                    .envelope.body
                    .okResult()

            assertEquals("true", ok.getValue("repinned").jsonPrimitive.content, "the head moved, so the pin moves")
            val second = assertNotNull(k.item(item.id))
            assertEquals(first.version + 1, second.version)
            assertEquals(head2.versionId, second.pin!!.rootVersionId)

            val raw = k.rig.make("never pinned", root)
            assertNull(assertNotNull(k.item(raw.id)).pin, "fixture: a raw-inserted row carries no pin")
            val rawOk =
                k
                    .call(repinOp(raw.id))
                    .envelope.body
                    .okResult()
            assertEquals("true", rawOk.getValue("repinned").jsonPrimitive.content, "no pin versus a computed pin is a change")
            assertEquals(k.rootHead(root).versionId, k.item(raw.id)!!.pin!!.rootVersionId, "the row is pinned to the root head now")
            assertEquals(raw.version + 1, k.item(raw.id)!!.version)
        }

    // ------------------------------------------------------------------ expectedVersion and ids

    @Test
    fun `S4 a stale expectedVersion is version_conflict and writes nothing, the matching one repins`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            k.push(root, ROOT_FEATURE_YAML)
            val current = assertNotNull(k.item(item.id))
            val snapshot = k.snapshot()

            val err =
                k
                    .call(repinOp(item.id, "expectedVersion" to JsonPrimitive(current.version - 1)))
                    .envelope.body
                    .errorAt()

            assertEquals("version_conflict", err.str("code"))
            assertEquals("item", err.detail().str("kind"))
            assertEquals(item.id.toString(), err.detail().str("id"))
            assertEquals((current.version - 1).toString(), err.detail().str("expected"))
            assertEquals(current.version.toString(), err.detail().str("actual"))
            assertEquals(snapshot, k.snapshot(), "nothing was written")

            val ok =
                k
                    .call(repinOp(item.id, "expectedVersion" to JsonPrimitive(current.version)))
                    .envelope.body
                    .okResult()
            assertEquals("true", ok.getValue("repinned").jsonPrimitive.content, "control: the matching expectedVersion repins")
        }

    @Test
    fun `S4 an unknown id is not_found and an 8-hex prefix of a known id works`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            k.push(root, ROOT_FEATURE_YAML)

            val missing =
                k
                    .call(repinOp(UUID.fromString("c3000000-0000-4000-8000-0000000000ee")))
                    .envelope.body
                    .errorAt()
            val viaPrefix =
                k
                    .call(repinOp(item.id.toString().take(8)))
                    .envelope.body
                    .okResult()

            assertEquals("not_found", missing.str("code"))
            assertEquals(item.id.toString(), viaPrefix.getValue("id").jsonPrimitive.content)
            assertEquals("true", viaPrefix.getValue("repinned").jsonPrimitive.content)
        }

    @Test
    fun `probe - a repeated idempotencyKey replays the stored repin result without a second write`(): Unit =
        runBlocking {
            val k = kit()
            val root = k.make("R")
            val item = k.make("I", root, type = "feature-task")
            k.push(root, ROOT_FEATURE_YAML)
            val keyed = repinOp(item.id, "idempotencyKey" to JsonPrimitive("repin-key-1"))

            val first =
                k
                    .call(keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()
            val afterFirst = k.snapshot()
            val seq = k.rig.maxSeq()
            val replay =
                k
                    .call(keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()

            assertNull(first["replayed"], "the first execution is not a replay")
            assertEquals("true", first.getValue("repinned").jsonPrimitive.content)
            assertEquals("true", replay.getValue("replayed").jsonPrimitive.content)
            assertEquals(
                "true",
                replay.getValue("repinned").jsonPrimitive.content,
                "the replay returns the stored result, not a fresh no-op"
            )
            assertEquals(first.getValue("version"), replay.getValue("version"))
            assertEquals(afterFirst, k.snapshot())
            assertEquals(seq, k.rig.maxSeq(), "the replay recorded no event")
        }

    // ------------------------------------------------------------------ scope

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
    private val rootScoped = ParityToken("rp-root", caps, rootIds = setOf(r1))
    private val readOnly = ParityToken("rp-read", setOf(ApiCapability.READ))

    @Test
    fun `S4 repin follows the update scope rules - an out-of-scope item and a read-only token are forbidden, in scope it writes`(): Unit =
        runBlocking {
            val k = kit()
            val root1 = k.make("R1", id = r1)
            k.make("C1", root1, id = c1, type = "feature-task")
            k.make("R2", id = r2)
            k.push(root1, ROOT_FEATURE_YAML)
            val before = k.snapshot()

            val outside = k.callScoped(rootScoped, repinOp(r2)).errorAt()
            val readonly = k.callScoped(readOnly, repinOp(c1)).errorAt()

            assertForbidden(outside, "root", r1.toString())
            assertForbidden(readonly, "capability", "write-items")
            assertEquals(before, k.snapshot(), "every forbidden element precedes any write")

            val inScope = k.callScoped(rootScoped, repinOp(c1)).okResult()
            assertEquals("true", inScope.getValue("repinned").jsonPrimitive.content, "control: the same call shape inside the scope writes")
            assertNotEquals(before, k.snapshot())
        }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun `S9 a per-root read fault makes repin fail closed as unavailable and leaves the item as it was`(): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, P11_BASE_YAML)
            val d = P11Driver(rig)
            val f = d.configFaultFixture()
            val svc = rig.ctx.itemCommandService

            suspend fun create(title: String): WorkItem =
                svc.create(ItemCreateCommand(parentId = f.root.id, title = title, type = "test-item")).orFail()

            val faulted = create("faulted")
            val control = create("control")
            assertNotNull(
                rig.raw
                    .workItemRepository()
                    .getById(faulted.id)!!
                    .pin,
                "fixture: the item is pinned before the fault"
            )
            // Move the head so a healthy repin changes the pin (a fault must not be mistaken for a no-op).
            rig.raw.configStore().upsert(f.root.id, "work_item_schemas:\n  p11-gated:\n    notes: []\n")
            val healthy = rig.itemsWrite(repinOp(control.id)).okResult()
            assertEquals(
                "true",
                healthy.getValue("repinned").jsonPrimitive.content,
                "control: with readable config the repin changes the pin"
            )
            val before = assertNotNull(rig.raw.workItemRepository().getById(faulted.id))

            d.breakConfigReads()
            val seq = rig.maxSeq()
            val result = rig.itemsWrite(repinOp(faulted.id))

            assertEquals("unavailable", result.errorAt(0).str("code"), "$result")
            assertEquals(seq, rig.maxSeq(), "the faulted repin recorded no event")
            val after = assertNotNull(rig.raw.workItemRepository().getById(faulted.id))
            assertEquals(before.pin, after.pin, "the pin is never moved to a guessed version")
            assertEquals(before.version, after.version)
        }
}

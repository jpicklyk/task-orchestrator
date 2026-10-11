package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private const val SERVER_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  feature-task:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n"

private const val ROOT_V1 =
    "work_item_schemas:\n" +
        "  root-only:\n" +
        "    notes:\n" +
        "      - key: rspec\n        role: queue\n        required: true\n"

private const val ROOT_V2 =
    "work_item_schemas:\n" +
        "  root-only:\n" +
        "    notes:\n" +
        "      - key: rspec\n        role: queue\n        required: true\n" +
        "      - key: extra\n        role: queue\n        required: true\n" +
        "      - key: nice\n        role: queue\n        required: false\n" +
        "  feature-task:\n" +
        "    notes:\n" +
        "      - key: spec\n        role: queue\n        required: true\n" +
        "      - key: zextra\n        role: queue\n        required: true\n"

/**
 * Independent tests (test-author seat, item 934b25e4) for `schema_pinned_conflict`, scenario S7 of the frozen test-plan,
 * through the production `notes_write` tool over the production composition.
 *
 * Oracles (frozen task-scope "schema_pinned_conflict" paragraph, AC8 and the documented error shape; none read from the
 * implementation): when the item is pinned, the pinned schema has NO entry for the written key, and the schema the item would
 * resolve to at the current config heads declares that key with `required = true`, the write fails `schema_pinned_conflict`
 * (permanent) with `detail {itemId, pinnedVersion, currentVersion}`, each version written `server:<n|none>/root:<n|none>`
 * (n = the version number of that scope's pinned / head version); it is never raised for an unpinned item, when the pin
 * knows the key, or when the head declares the key as optional; nothing is written on a conflict; a repin clears it.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class SchemaPinnedConflictTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun rig() = EventLogRig.build(db.db, dir, SERVER_YAML)

    private suspend fun EventLogRig.createItem(
        title: String,
        parent: WorkItem? = null,
        type: String
    ): WorkItem = ctx.itemCommandService.create(ItemCreateCommand(parentId = parent?.id, title = title, type = type)).orFail()

    private suspend fun EventLogRig.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(ctx.configService).push(root.id, yaml), "push of '${yaml.take(30)}'")
    }

    private suspend fun EventLogRig.serverVersion(): Int = assertNotNull(raw.configStore().head(ConfigScope.SERVER, null)).version

    private suspend fun EventLogRig.rootVersion(root: WorkItem): Int =
        assertNotNull(raw.configStore().head(ConfigScope.ROOT, root.id)).version

    private suspend fun EventLogRig.upsert(
        item: WorkItem,
        key: String,
        role: String = "queue"
    ): JsonObject = ctx.upsertNoteVia(item.id, key, role, "body of $key")

    private fun JsonObject.detail(): JsonObject = getValue("detail").jsonObject

    private fun noteCount() = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM notes")

    private fun repin(id: UUID) =
        buildJsonObject {
            put("op", "repin")
            put("id", id.toString())
        }

    @Test
    fun `S7 a head-required key the pin lacks is schema_pinned_conflict with both version strings, and nothing is written`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.createItem("R", type = "test-item")
            rig.push(root, ROOT_V1)
            val x = rig.createItem("X", root, "root-only")
            rig.push(root, ROOT_V2)
            val serverN = rig.serverVersion()
            val rootN = rig.rootVersion(root)
            val notes = noteCount()
            val seq = rig.maxSeq()

            val error = rig.upsert(x, "extra").errorAt()

            assertEquals("schema_pinned_conflict", error.getValue("code").jsonPrimitive.content)
            assertEquals("permanent", error.getValue("kind").jsonPrimitive.content)
            assertEquals(
                x.id.toString(),
                error
                    .detail()
                    .getValue("itemId")
                    .jsonPrimitive.content
            )
            assertEquals(
                "server:$serverN/root:1",
                error
                    .detail()
                    .getValue("pinnedVersion")
                    .jsonPrimitive.content
            )
            assertEquals(
                "server:$serverN/root:$rootN",
                error
                    .detail()
                    .getValue("currentVersion")
                    .jsonPrimitive.content
            )
            assertEquals(2, rootN, "fixture: the head is revision two")
            assertEquals(notes, noteCount(), "no note row was written")
            assertEquals(seq, rig.maxSeq(), "no event row was written")
        }

    @Test
    fun `S7 a pinned scope with no config head reads as none in the version strings`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.createItem("R", type = "test-item")
            val z = rig.createItem("Z", root, "feature-task")
            rig.push(root, ROOT_V2)
            val serverN = rig.serverVersion()

            val error = rig.upsert(z, "zextra").errorAt()

            assertEquals("schema_pinned_conflict", error.getValue("code").jsonPrimitive.content)
            assertEquals(
                "server:$serverN/root:none",
                error
                    .detail()
                    .getValue("pinnedVersion")
                    .jsonPrimitive.content
            )
            assertEquals(
                "server:$serverN/root:1",
                error
                    .detail()
                    .getValue("currentVersion")
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `S7 no conflict when the head declares the key optional, when the pin knows the key, or when no schema declares it`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.createItem("R", type = "test-item")
            rig.push(root, ROOT_V1)
            val x = rig.createItem("X", root, "root-only")
            rig.push(root, ROOT_V2)
            val conflict = rig.upsert(x, "extra").errorAt()
            assertEquals(
                "schema_pinned_conflict",
                conflict.getValue("code").jsonPrimitive.content,
                "control: the required key does conflict"
            )

            rig.upsert(x, "nice").okResult()
            rig.upsert(x, "rspec").okResult()
            rig.upsert(x, "freeform", role = "work").okResult()

            val stored =
                rig.raw
                    .noteRepository()
                    .findByItemId(x.id)
                    .map { it.key }
                    .toSet()
            assertEquals(setOf("nice", "rspec", "freeform"), stored, "the three non-conflicting writes landed, the conflicting one did not")
        }

    @Test
    fun `S7 an unpinned row resolves at the head, so the key is accepted with its role and never conflicts`(): Unit =
        runBlocking {
            val rig = rig()
            val d = P11Driver(rig)
            val root = rig.createItem("R", type = "test-item")
            rig.push(root, ROOT_V1)
            val pinned = rig.createItem("Pinned", root, "root-only")
            val unpinned = d.item("Unpinned", Role.QUEUE, type = "root-only", parent = root)
            assertEquals(
                null,
                rig.raw
                    .workItemRepository()
                    .getById(unpinned.id)!!
                    .pin,
                "fixture: no pin"
            )
            rig.push(root, ROOT_V2)
            assertEquals(
                "schema_pinned_conflict",
                rig
                    .upsert(pinned, "extra")
                    .errorAt()
                    .getValue("code")
                    .jsonPrimitive.content,
                "control: the pinned twin conflicts on the same key"
            )

            rig.upsert(unpinned, "extra").okResult()
            val wrongRole = rig.upsert(unpinned, "extra", role = "work").errorAt()

            assertEquals(
                "schema_violation",
                wrongRole.getValue("code").jsonPrimitive.content,
                "the head's role rule applies to the unpinned row"
            )
        }

    @Test
    fun `S7 a repin clears the conflict`(): Unit =
        runBlocking {
            val rig = rig()
            val root = rig.createItem("R", type = "test-item")
            rig.push(root, ROOT_V1)
            val x = rig.createItem("X", root, "root-only")
            rig.push(root, ROOT_V2)
            assertEquals(
                "schema_pinned_conflict",
                rig
                    .upsert(x, "extra")
                    .errorAt()
                    .getValue("code")
                    .jsonPrimitive.content,
                "control"
            )

            rig.itemsWrite(repin(x.id)).okResult()
            rig.upsert(x, "extra").okResult()

            assertEquals(
                true,
                rig.raw
                    .noteRepository()
                    .findByItemId(x.id)
                    .any { it.key == "extra" }
            )
        }
}

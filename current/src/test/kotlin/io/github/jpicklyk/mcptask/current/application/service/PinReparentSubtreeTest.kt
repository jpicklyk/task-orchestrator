package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.domain.model.SchemaPin
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNull

private const val SERVER_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n" +
        "  feature-task:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n"

private const val R1_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

private const val R2_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n"

/**
 * Fix round 1 of item 934b25e4 (review finding B1): a move to another root re-pins the moved item AND every descendant
 * against the new root's heads, so no row keeps a pin naming the old root's config versions, and deleting the old root
 * (whose `config_versions` rows cascade away) succeeds. The delete path also re-points any surviving pin that still
 * names a doomed version (placement that disagrees with the tree), so the pin foreign key never fails a delete.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PinReparentSubtreeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private suspend fun EventLogRig.createItem(
        title: String,
        parent: WorkItem? = null,
        type: String = "test-item"
    ): WorkItem = ctx.itemCommandService.create(ItemCreateCommand(parentId = parent?.id, title = title, type = type)).orFail()

    private suspend fun EventLogRig.push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(ctx.configService).push(root.id, yaml))
    }

    private suspend fun EventLogRig.rootHeadId(root: WorkItem): UUID =
        assertNotNull(raw.configStore().head(ConfigScope.ROOT, root.id)).versionId

    private suspend fun EventLogRig.serverHeadId(): UUID = assertNotNull(raw.configStore().head(ConfigScope.SERVER, null)).versionId

    private suspend fun EventLogRig.pinOf(item: WorkItem): SchemaPin? = raw.workItemRepository().getById(item.id)?.pin

    private fun hex(id: UUID): String = id.toString().replace("-", "")

    private fun configVersionsOf(root: WorkItem): Int =
        rawCount(db.jdbcUrl, "SELECT count(*) FROM config_versions WHERE root_id = X'${hex(root.id)}'")

    private fun List<EventRecord>.pins() = filter { it.type == "item.pinned" }

    private fun assertPin(
        label: String,
        pin: SchemaPin?,
        server: UUID,
        root: UUID?,
        key: String,
        layer: PinLayer,
        lifecycle: LifecycleMode
    ) {
        val p = assertNotNull(pin, "$label: pinned")
        assertEquals(server, p.serverVersionId, "$label: server version")
        assertEquals(root, p.rootVersionId, "$label: root version")
        assertEquals(key, p.schemaKey, "$label: key")
        assertEquals(layer, p.schemaLayer, "$label: layer")
        assertEquals(lifecycle, p.lifecycle, "$label: lifecycle")
    }

    @Test
    fun `a cross-root move re-pins every level of the moved subtree, and the old root then deletes cleanly`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, SERVER_YAML)
            val server = rig.serverHeadId()
            val r1 = rig.createItem("R1")
            val r2 = rig.createItem("R2")
            rig.push(r1, R1_YAML)
            rig.push(r2, R2_YAML)
            val head1 = rig.rootHeadId(r1)
            val head2 = rig.rootHeadId(r2)
            val a = rig.createItem("A", r1)
            val b = rig.createItem("B", a, "feature-task")
            val c = rig.createItem("C", b)
            for (item in listOf(a, b, c)) assertEquals(head1, rig.pinOf(item)?.rootVersionId, "fixture: ${item.title} pinned to R1")

            val (_, moveRows) = rig.written { rig.itemsWrite(updateOp(a.id, "parentId" to JsonPrimitive(r2.id.toString()))).okResult() }

            assertPin("A (moved)", rig.pinOf(a), server, head2, "test-item", PinLayer.ROOT, LifecycleMode.PERMANENT)
            assertPin("B (child)", rig.pinOf(b), server, head2, "feature-task", PinLayer.SERVER, LifecycleMode.MANUAL)
            assertPin("C (grandchild)", rig.pinOf(c), server, head2, "test-item", PinLayer.ROOT, LifecycleMode.PERMANENT)
            val pinRows = moveRows.pins()
            assertEquals(setOf(a.id, b.id, c.id), pinRows.map { it.entityId }.toSet(), "one pin row per moved item")
            assertEquals(3, pinRows.size)
            for (row in pinRows) {
                assertEquals("reparent", row.str("reason"))
                assertEquals(r2.id, row.rootId, "the row is recorded under the new root")
                assertEquals(
                    head1.toString(),
                    row
                        .payload()["from"]!!
                        .jsonObject["root"]!!
                        .jsonPrimitive.content
                )
                assertEquals(
                    head2.toString(),
                    row
                        .payload()["to"]!!
                        .jsonObject["root"]!!
                        .jsonPrimitive.content
                )
            }
            assertEquals(
                0,
                rawCount(db.jdbcUrl, "SELECT count(*) FROM work_items WHERE pinned_root_cfg = X'${hex(head1)}'"),
                "no row names R1's head any more"
            )

            assertEquals(1, configVersionsOf(r1), "control: R1 owns its pushed version before the delete")
            rig.itemsWrite(deleteOp(r1.id, recursive = true)).okResult()

            assertNull(rig.raw.workItemRepository().getById(r1.id), "R1 is gone")
            assertEquals(0, configVersionsOf(r1), "R1's config versions cascaded away")
            for (item in listOf(a, b, c)) assertEquals(head2, rig.pinOf(item)?.rootVersionId, "${item.title} survives, pinned to R2")
        }

    @Test
    fun `a delete re-points a surviving pin that names a doomed config version instead of failing on the pin key`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, SERVER_YAML)
            val server = rig.serverHeadId()
            val r1 = rig.createItem("R1")
            val r2 = rig.createItem("R2")
            rig.push(r1, R1_YAML)
            rig.push(r2, R2_YAML)
            val head1 = rig.rootHeadId(r1)
            val head2 = rig.rootHeadId(r2)
            val x = rig.createItem("X", r2)
            // Out-of-band: X sits under R2 but its pin names R1's version (placement data that disagrees with the tree).
            rawExec(db.jdbcUrl, "UPDATE work_items SET pinned_root_cfg = X'${hex(head1)}' WHERE id = X'${hex(x.id)}'")
            assertEquals(head1, rig.pinOf(x)?.rootVersionId, "fixture")
            val versionBefore = assertNotNull(rig.raw.workItemRepository().getById(x.id)).version

            val (_, deleteRows) = rig.written { rig.itemsWrite(deleteOp(r1.id)).okResult() }

            assertEquals(0, configVersionsOf(r1), "R1's config versions cascaded away")
            assertPin("X (re-pointed)", rig.pinOf(x), server, head2, "test-item", PinLayer.ROOT, LifecycleMode.PERMANENT)
            assertEquals(
                versionBefore,
                rig.raw
                    .workItemRepository()
                    .getById(x.id)
                    ?.version,
                "a re-point is not an edit"
            )
            val row = deleteRows.pins().single()
            assertEquals(x.id, row.entityId)
            assertEquals("repin", row.str("reason"))
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.PIN_ROOT_DEFAULT_YAML
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.PIN_ROOT_EXACT_YAML
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.PIN_SERVER_YAML
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.bootProductionSteps
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.migratedGolden
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.resolveGoldenBases
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.sqlRows
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVER_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n" +
        "  feature-task:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n" +
        "  plain:\n" +
        "    notes: []\n"

private const val ROOT_AUTO_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

private const val ROOT_MANUAL_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n"

/**
 * Independent tests (test-author seat, item 934b25e4) for the lifecycle-column invariant, scenario S8 of the frozen
 * test-plan (AC6), plus the "only SchemaPinService constructs a SchemaPin" guard.
 *
 * Oracles (frozen task-scope AC6 and decision D10, plan s3.8; none read from the implementation): for every pinned row the
 * `lifecycle` column equals the `lifecycle` the row's resolved base schema declares (`auto`, `manual`, `permanent`; an
 * omitted `lifecycle` key is the documented default `auto`), and is NULL when the pin is schema-free; the value `none` is
 * never written by this item; the invariant holds for rows pinned at create, re-pinned by a type change or a `repin`, and
 * for rows the upgrade backfill pinned. The expected values are the literals written in the YAML fixtures above.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class LifecycleColumnInvariantTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

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

    private fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun column(item: WorkItem): String? =
        sqlRows(db.jdbcUrl, "SELECT lifecycle FROM work_items WHERE id = ?", { it.setBytes(1, uuidBytes(item.id)) }) {
            it.getString(1)
        }.single()

    /** The lifecycle the production resolver reads from the base schema `forItem` resolves, as the column's text form. */
    private suspend fun EventLogRig.resolvedLifecycle(item: WorkItem): String? {
        val stored = checkNotNull(raw.workItemRepository().getById(item.id))
        val entry = inUnit { ctx.configResolver.forItem(stored).baseSchemaEntry(stored.type) }
        return entry
            ?.schema
            ?.lifecycleMode
            ?.name
            ?.lowercase()
    }

    private suspend fun EventLogRig.assertInvariant(
        label: String,
        item: WorkItem,
        expected: String?
    ) {
        assertEquals(expected, column(item), "$label: the lifecycle column")
        assertEquals(expected, resolvedLifecycle(item), "$label: forItem's base schema lifecycle")
        assertEquals(column(item), resolvedLifecycle(item), "$label: column equals the resolved lifecycle")
    }

    @Test
    fun `S8 created rows carry the lifecycle of the base schema they resolve, from either layer, with auto as the default`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, SERVER_YAML)
            val r1 = rig.createItem("R1", type = "test-item")
            val r2 = rig.createItem("R2", type = "test-item")
            rig.push(r1, ROOT_AUTO_YAML)

            rig.assertInvariant("root of manual server type", r1, "manual")
            rig.assertInvariant("per-root override wins", rig.createItem("A", r1, "feature-task"), "auto")
            rig.assertInvariant("server type without a per-root declaration", rig.createItem("B", r2, "feature-task"), "permanent")
            rig.assertInvariant("server type declared without a lifecycle key", rig.createItem("C", r2, "plain"), "auto")
            rig.assertInvariant(
                "server type under a root with a per-root document that omits it",
                rig.createItem("D", r1, "test-item"),
                "manual"
            )

            assertEquals(
                0,
                sqlRows(db.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE lifecycle = 'none'") { it.getInt(1) }.single(),
                "never none"
            )
        }

    @Test
    fun `S8 a schema-free row has a NULL lifecycle column and resolves no base schema`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, "work_item_schemas: {}\n")
            val item = rig.createItem("Free", type = "anything")

            rig.assertInvariant("schema-free", item, null)
            val pin = assertNotNull(rig.raw.workItemRepository().getById(item.id)).pin
            assertNull(pin?.lifecycle)
            assertNull(pin?.schemaKey, "a schema-free pin names no schema key")
            assertNull(pin?.schemaLayer, "a schema-free pin names no schema layer")
            assertNotNull(pin?.serverVersionId, "the row is pinned all the same, to the server head")
        }

    @Test
    fun `S8 a type change and a repin keep the column equal to the resolved lifecycle`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir, SERVER_YAML)
            val r1 = rig.createItem("R1", type = "test-item")
            val r2 = rig.createItem("R2", type = "test-item")
            rig.push(r1, ROOT_AUTO_YAML)
            val moved = rig.createItem("M", r2, "plain")
            rig.assertInvariant("before the type change", moved, "auto")

            rig.itemsWrite(updateOp(moved.id, "type" to JsonPrimitive("feature-task"))).okResult()

            rig.assertInvariant("after the type change (server permanent)", moved, "permanent")

            val under = rig.createItem("U", r1, "feature-task")
            rig.assertInvariant("before the push", under, "auto")
            rig.push(r1, ROOT_MANUAL_YAML)
            assertEquals("auto", column(under), "control: a push alone does not touch the column")
            rig
                .itemsWrite(
                    buildJsonObject {
                        put("op", "repin")
                        put("id", under.id.toString())
                    }
                ).okResult()

            rig.assertInvariant("after repin", under, "manual")
        }

    @Test
    fun `S8 rows pinned by the upgrade backfill carry the lifecycle of the base schema forItem resolves`(
        @TempDir dir: File
    ) {
        var nonNull = 0
        for (
        (name, perRoot, server) in
        listOf(
            Triple("default", PIN_ROOT_DEFAULT_YAML, PIN_SERVER_YAML),
            Triple("exact", PIN_ROOT_EXACT_YAML, PIN_SERVER_YAML),
            Triple("server-only", null, PIN_SERVER_YAML),
            Triple("none", null, null)
        )
        ) {
            val url = migratedGolden(dir, "life-$name", perRoot)
            val (runDir, _, _) = bootProductionSteps(url, dir, "life-$name", server)

            for ((id, base) in resolveGoldenBases(url, runDir)) {
                assertEquals(
                    base.baseLifecycle?.name?.lowercase(),
                    base.pinLifecycle,
                    "$name: row $id column vs forItem base schema lifecycle"
                )
                if (base.pinLifecycle != null) nonNull++
            }
        }
        assertTrue(nonNull > 0, "vacuity control: some backfilled rows carry a lifecycle")
    }

    // ---------------------------------------------------------------------------------------------
    // Only SchemaPinService constructs a SchemaPin
    // ---------------------------------------------------------------------------------------------

    private fun mainSourceRoot(): Path =
        listOf(Paths.get("src/main/kotlin"), Paths.get("current/src/main/kotlin"))
            .firstOrNull { Files.isDirectory(it) }
            ?: error("production source root not found from ${Paths.get("").toAbsolutePath()}")

    @Test
    fun `S8 no production file other than the pin service, the model itself and the row mapper constructs a SchemaPin`() {
        val root = mainSourceRoot()
        val files = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue(files.size > 100, "vacuity control: the scan covers the production tree (${files.size} files)")
        val constructor = Regex("""(?<![A-Za-z0-9_.])SchemaPin\(""")
        // The model declares the class; the row mapper rebuilds the value it reads back from the six columns (reading is not pinning).
        val allowed = setOf("SchemaPin.kt", "SchemaPinService.kt", "WorkItemRows.kt")

        val hits =
            files.flatMap { file ->
                Files.readAllLines(file, Charsets.UTF_8).withIndex().mapNotNull { (i, line) ->
                    if (constructor.containsMatchIn(line)) file.fileName.toString() to "${root.relativize(file)}:${i + 1}" else null
                }
            }

        assertTrue(
            hits.any { it.first == "SchemaPinService.kt" },
            "vacuity control: the pin service is where the scan finds a construction: $hits"
        )
        assertEquals(emptyList(), hits.filter { it.first !in allowed }.map { it.second }, "SchemaPin constructed outside the pin service")
    }
}

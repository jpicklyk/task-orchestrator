package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.createTreeOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `test-item` is an ordinary type; `page` is lifecycle-free. */
private const val RANK_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

/**
 * Independent tests (test-author seat, item 7ceedf39) for the `rank` member of `items_write` create, update and create_tree
 * children, `clear: ["rank"]`, the full item view and the `item.updated` row: scenarios S8 to S12 and S14 of the frozen
 * test-plan, plus the probes that apply to a nullable integer wire field.
 *
 * Oracles: task-scope D1 (any 32-bit integer is valid, 0 and negatives included; null is unranked; a non-Int JSON value is
 * refused by the decoder as `invalid_request` on the member with nothing written), D2 (`rank` right after `complexity` on
 * create, update and `create_tree` children, NOT on the `create_tree` root; `clear` takes `rank`; null in a patch keeps, `clear`
 * nulls; clear together with a value, or a repeated clear, is `invalid_request`), D3 (the full item view shows `rank` right
 * after `complexity` only when set; the compact views never carry it), D6 (`item.updated` changedFields names `rank`) and
 * decision 0013 (rank orders `next`). Expected values are literals written from those clauses.
 *
 * Arbitration: the plan says "invalid_request on the rank member" without the envelope shape. A decoder refusal of an ops
 * element member surfaces as the element's error inside a `partial_failure` batch envelope (the envelope specification, as other
 * write tests read it); [refusal] reads the element error, or the whole-call error when no batch results exist, and every assertion is on the code `invalid_request`, a mention of `rank` in the refusal and the store being untouched. This is
 * resolved from the envelope specification (the same envelope other write tests read), not from the implementation.
 *
 * Forbidden-construct declaration: the "10" probe accepts either outcome because the plan records its outcome without
 * asserting it; it still asserts that the store agrees with whichever answer came back.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemsWriteRankTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun num(n: Int): JsonElement = JsonPrimitive(n)

    private fun next(extra: String = "") = """{"select":"next","limit":200$extra}"""

    /** The refusal of a call: the first element's error of a batch envelope (a per-element `partial_failure` call), else the whole-call error. */
    private fun refusal(body: JsonObject): JsonObject =
        if (body["data"]?.jsonObject?.get("results") != null) body.errorAt() else body.getValue("error").jsonObject

    private suspend fun EventLogRig.stored(id: UUID): WorkItem = checkNotNull(raw.workItemRepository().getById(id)) { "no item $id" }

    private suspend fun EventLogRig.count(): Int = IwKit(this).all().size

    private suspend fun IrClient.getItem(id: UUID): JsonObject {
        val r = select("""{"select":"get","itemId":"$id"}""")
        assertEquals(200, r.status, r.raw)
        return r.data
    }

    // ------------------------------------------------------------------ S8 create (AC3)

    @Test
    fun `S8 create with rank 10 stores it and get shows it right after complexity and before depth`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val body =
                rig.itemsWrite(createOp("Ranked", null, TEST_ITEM_TYPE, "complexity" to num(3), "rank" to num(10)))
            val id = body.okId()

            assertEquals(10, rig.stored(id).rank)
            val view = getItem(id)
            assertEquals("10", view.getValue("rank").jsonPrimitive.content, "$view")
            val keys = view.keys.toList()
            assertEquals(keys.indexOf("complexity") + 1, keys.indexOf("rank"), "rank is written right after complexity: $keys")
            assertEquals(keys.indexOf("rank") + 1, keys.indexOf("depth"), "depth follows rank: $keys")
        }

    @Test
    fun `S8 create without rank stores none and get has no rank member`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Unranked", null, TEST_ITEM_TYPE, "complexity" to num(3))).okId()

            assertNull(rig.stored(id).rank)
            assertFalse("rank" in getItem(id), "an unranked item has no rank member")
        }

    @Test
    fun `probe create with rank 0 and a negative rank stores them as given and shows them`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val zero = rig.itemsWrite(createOp("Zero", null, TEST_ITEM_TYPE, "rank" to num(0))).okId()
            val minus = rig.itemsWrite(createOp("Minus", null, TEST_ITEM_TYPE, "rank" to num(-3))).okId()
            val max = rig.itemsWrite(createOp("Max", null, TEST_ITEM_TYPE, "rank" to num(Int.MAX_VALUE))).okId()

            assertEquals(0, rig.stored(zero).rank, "0 is a rank, not unranked")
            assertEquals("0", getItem(zero).getValue("rank").jsonPrimitive.content)
            assertEquals(-3, rig.stored(minus).rank)
            assertEquals("-3", getItem(minus).getValue("rank").jsonPrimitive.content)
            assertEquals(Int.MAX_VALUE, rig.stored(max).rank, "the largest 32-bit integer is valid")
        }

    @Test
    fun `probe create with an explicit null rank is unranked`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("NullRank", null, TEST_ITEM_TYPE, "rank" to JsonNull)).okId()

            assertNull(rig.stored(id).rank)
            assertFalse("rank" in getItem(id))
        }

    @Test
    fun `S8 next entries never carry rank even for a ranked item`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            rig.itemsWrite(createOp("Ranked", null, TEST_ITEM_TYPE, "rank" to num(10)))

            val r = select(next())

            assertEquals(listOf("Ranked"), r.items.map { it.getValue("title").jsonPrimitive.content })
            for (entry in r.items) assertFalse("rank" in entry, "compact next entry has no rank: $entry")
        }

    // ------------------------------------------------------------------ S9 create refuses non-Int (AC3)

    private fun rankRefusedOnCreate(
        raw: JsonElement,
        label: String
    ) {
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val control = rig.itemsWrite(createOp("Control", null, TEST_ITEM_TYPE, "rank" to num(10)))
            control.okId()
            assertEquals(1, rig.count(), "control: an Int rank writes")

            val body = rig.itemsWrite(createOp("Bad $label", null, TEST_ITEM_TYPE, "rank" to raw))

            val error = refusal(body)
            assertEquals("invalid_request", error.getValue("code").jsonPrimitive.content, "$label: $body")
            assertTrue("rank" in error.toString(), "$label: the refusal names the rank member: $error")
            assertEquals(1, rig.count(), "$label: nothing was written")
        }
    }

    @Test
    fun `S9 create with a string rank is refused and writes nothing`() = rankRefusedOnCreate(JsonPrimitive("ten"), "string")

    @Test
    fun `S9 create with a boolean rank is refused and writes nothing`() = rankRefusedOnCreate(JsonPrimitive(true), "boolean")

    @Test
    fun `S9 create with a fractional rank is refused and writes nothing`() = rankRefusedOnCreate(JsonPrimitive(1.5), "fraction")

    @Test
    fun `S9 create with a rank one past the 32-bit maximum is refused and writes nothing`() =
        rankRefusedOnCreate(JsonPrimitive(2147483648L), "int-max-plus-one")

    @Test
    fun `probe create with a quoted number rank records its outcome and the store agrees with the answer`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val body = rig.itemsWrite(createOp("Quoted", null, TEST_ITEM_TYPE, "rank" to JsonPrimitive("10")))

            val accepted = body["error"] == null && body["data"]?.jsonObject?.get("results")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("ok")?.jsonPrimitive?.content == "true"
            if (accepted) {
                assertEquals(10, rig.stored(body.okId()).rank, "accepted: the stored rank is the number")
            } else {
                assertEquals("invalid_request", refusal(body).getValue("code").jsonPrimitive.content, "$body")
                assertEquals(0, rig.count(), "refused: nothing was written")
            }
        }

    // ------------------------------------------------------------------ S10 update and clear (AC4)

    @Test
    fun `S10 update sets rank and bumps version, a title-only update keeps it, clear rank removes it`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE)).okId()
            val v0 = rig.stored(id).version

            val set = rig.itemsWrite(updateOp(id, "rank" to num(30))).okResult()
            assertEquals(30, rig.stored(id).rank)
            assertEquals(v0 + 1, set.getValue("version").jsonPrimitive.long, "a rank-only update bumps the version once")
            assertEquals(v0 + 1, rig.stored(id).version)
            assertEquals("30", getItem(id).getValue("rank").jsonPrimitive.content)

            rig.itemsWrite(updateOp(id, "title" to JsonPrimitive("Renamed"))).okResult()
            assertEquals(30, rig.stored(id).rank, "a title-only update keeps the rank")
            assertEquals("30", getItem(id).getValue("rank").jsonPrimitive.content)

            rig.itemsWrite(updateOp(id, "clear" to strings("rank"))).okResult()
            assertNull(rig.stored(id).rank, "clear rank removes it")
            assertFalse("rank" in getItem(id), "the member is gone")
        }

    @Test
    fun `probe an explicit null rank in an update keeps the stored rank`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(30))).okId()

            rig.itemsWrite(updateOp(id, "rank" to JsonNull, "title" to JsonPrimitive("Renamed"))).okResult()

            assertEquals(30, rig.stored(id).rank, "null is keep, only clear nulls")
            assertEquals("Renamed", rig.stored(id).title)
        }

    @Test
    fun `probe update can move a rank to 0 and to a negative value`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(30))).okId()

            rig.itemsWrite(updateOp(id, "rank" to num(0))).okResult()
            assertEquals(0, rig.stored(id).rank)
            rig.itemsWrite(updateOp(id, "rank" to num(-7))).okResult()
            assertEquals(-7, rig.stored(id).rank)
        }

    @Test
    fun `probe update with a non-Int rank is refused and leaves the item unchanged`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(30))).okId()
            val before = rig.stored(id)

            val body = rig.itemsWrite(updateOp(id, "rank" to JsonPrimitive("ten")))

            assertEquals("invalid_request", refusal(body).getValue("code").jsonPrimitive.content, "$body")
            assertEquals(before, rig.stored(id), "the item is unchanged")
        }

    @Test
    fun `probe a replayed rank update under one idempotencyKey makes one version`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE)).okId()
            val v0 = rig.stored(id).version
            val update = updateOp(id, "rank" to num(5), "idempotencyKey" to JsonPrimitive("rank-key-1"))

            val first = rig.itemsWrite(update, actor = IW_ACTOR).okResult()
            val again = rig.itemsWrite(update, actor = IW_ACTOR).okResult()

            assertNull(first["replayed"], "the first execution is not a replay")
            assertEquals("true", again.getValue("replayed").jsonPrimitive.content)
            assertEquals(5, rig.stored(id).rank)
            assertEquals(v0 + 1, rig.stored(id).version, "one version bump for two sends")
        }

    // ------------------------------------------------------------------ S11 clear conflicts (AC4)

    @Test
    fun `S11 clear rank together with a rank value is invalid_request and the item is unchanged`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(30))).okId()
            val before = rig.stored(id)

            val body = rig.itemsWrite(updateOp(id, "clear" to strings("rank"), "rank" to num(5)))

            val error = refusal(body)
            assertEquals("invalid_request", error.getValue("code").jsonPrimitive.content, "$body")
            assertTrue("rank" in error.toString(), "the refusal names rank: $error")
            assertEquals(before, rig.stored(id), "neither the clear nor the new value was applied")
        }

    @Test
    fun `S11 clear listing rank twice is invalid_request and the item is unchanged`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(30))).okId()
            val before = rig.stored(id)

            val body = rig.itemsWrite(updateOp(id, "clear" to strings("rank", "rank")))

            val error = refusal(body)
            assertEquals("invalid_request", error.getValue("code").jsonPrimitive.content, "$body")
            assertTrue("rank" in error.toString(), "the refusal names rank: $error")
            assertEquals(before, rig.stored(id), "the repeated clear applied nothing")
        }

    // ------------------------------------------------------------------ S12 create_tree children (AC5)

    private fun child(
        ref: String,
        rank: Int?
    ): JsonObject =
        JsonObject(
            buildMap {
                put("ref", JsonPrimitive(ref))
                put("title", JsonPrimitive("Child $ref"))
                put("type", JsonPrimitive(TEST_ITEM_TYPE))
                if (rank != null) put("rank", num(rank))
            }
        )

    @Test
    fun `S12 create_tree children take rank and next under the root lists the lower rank first`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val body =
                rig.itemsWrite(
                    createTreeOp(
                        "root" to JsonObject(mapOf("title" to JsonPrimitive("Tree root"), "type" to JsonPrimitive(TEST_ITEM_TYPE))),
                        "children" to JsonArray(listOf(child("a", 20), child("b", 10)))
                    )
                )
            body.okResult()
            val all = IwKit(rig).all()
            val root = all.single { it.title == "Tree root" }

            assertEquals(20, all.single { it.title == "Child a" }.rank)
            assertEquals(10, all.single { it.title == "Child b" }.rank)
            assertNull(root.rank, "the root carries no rank")
            val r = select(next(""","parentId":"${root.id}""""))
            assertEquals(listOf("Child b", "Child a"), r.items.map { it.getValue("title").jsonPrimitive.content })
        }

    @Test
    fun `S12 orchestration schema of items_write has rank on children and none on the root`() {
        val spec = assertNotNull(ToolRegistry(buildToolSpecs())["items_write"])
        val schema = assertNotNull(ToolSchemaRenderer.inputSchema(spec, ToolProfile.ORCHESTRATION), "the orchestration input schema")

        val children = assertNotNull(propertySchema(schema, "children"), "children is in the schema: $schema")
        val childProps = propertyNames(childItems(children))
        val rootProps = propertyNames(assertNotNull(propertySchema(schema, "root"), "root is in the schema: $schema"))

        assertTrue("rank" in childProps, "children[] has rank: $childProps")
        assertTrue("complexity" in childProps, "control: children[] has complexity, so the read of the schema is sound: $childProps")
        assertFalse("rank" in rootProps, "the root spec has no rank: $rootProps")
        assertTrue("complexity" in rootProps, "control: the root spec has complexity: $rootProps")
    }

    /** The schema of property [name] in the first `properties` map that has it, searching the whole schema tree. */
    private fun propertySchema(
        node: JsonElement,
        name: String
    ): JsonElement? =
        when (node) {
            is JsonObject -> {
                val props = node["properties"] as? JsonObject
                props?.get(name) ?: node.values.firstNotNullOfOrNull { propertySchema(it, name) }
            }
            is JsonArray -> node.firstNotNullOfOrNull { propertySchema(it, name) }
            else -> null
        }

    private fun childItems(children: JsonElement): JsonElement = (children as? JsonObject)?.get("items") ?: children

    private fun propertyNames(node: JsonElement): Set<String> =
        buildSet {
            fun walk(n: JsonElement) {
                when (n) {
                    is JsonObject -> {
                        (n["properties"] as? JsonObject)?.keys?.let { addAll(it) }
                        n.values.forEach { walk(it) }
                    }
                    is JsonArray -> n.forEach { walk(it) }
                    else -> Unit
                }
            }
            walk(node)
        }

    // ------------------------------------------------------------------ S14 item.updated changedFields (AC10)

    @Test
    fun `S14 a rank-only update bumps the version and its item updated row lists exactly rank`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "complexity" to num(2))).okId()
            val v0 = rig.stored(id).version

            val (_, rows) = rig.written { rig.itemsWrite(updateOp(id, "rank" to num(9))).okResult() }

            val updated = rows.filter { it.type == "item.updated" }
            assertEquals(1, updated.size, "one item.updated row: ${rows.map { it.type }}")
            assertEquals(
                listOf("rank"),
                updated.single().payload().getValue("changedFields").jsonArray.map { it.jsonPrimitive.content },
                "exactly rank changed"
            )
            assertEquals(v0 + 1, rig.stored(id).version)
        }

    @Test
    fun `S14 control a title-only update lists title and not rank, and clearing rank lists rank`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val id = rig.itemsWrite(createOp("Item", null, TEST_ITEM_TYPE, "rank" to num(9))).okId()

            val (_, titleRows) = rig.written { rig.itemsWrite(updateOp(id, "title" to JsonPrimitive("New"))).okResult() }
            val (_, clearRows) = rig.written { rig.itemsWrite(updateOp(id, "clear" to strings("rank"))).okResult() }

            val titleFields =
                titleRows.single { it.type == "item.updated" }.payload().getValue("changedFields").jsonArray.map { it.jsonPrimitive.content }
            val clearFields =
                clearRows.single { it.type == "item.updated" }.payload().getValue("changedFields").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("title"), titleFields)
            assertEquals(listOf("rank"), clearFields, "clearing a rank is a rank change")
        }

    // ------------------------------------------------------------------ probe lifecycle-free

    @Test
    fun `probe a lifecycle-free item takes a rank and never appears in next`(): Unit =
        irApp(db.db, dir, yaml = RANK_YAML) { rig ->
            val page = rig.itemsWrite(createOp("Page", null, "page", "rank" to num(1))).okId()
            rig.itemsWrite(createOp("Work", null, TEST_ITEM_TYPE)).okResult()

            val r = select(next())

            assertEquals(1, rig.stored(page).rank, "the lifecycle-free item stores its rank")
            assertEquals("1", getItem(page).getValue("rank").jsonPrimitive.content)
            assertEquals(listOf("Work"), r.items.map { it.getValue("title").jsonPrimitive.content }, "control: the lifecycle item is listed")
        }
}

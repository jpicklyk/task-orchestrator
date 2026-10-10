package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests for the Q2b `type` rule on the manage_items surface (item 2c67eeca): scenario S7 of the frozen
 * test-plan plus probes. Runs the tool against the real production composition over a real SQLite file.
 *
 * Oracles (frozen task-scope 1c and AC1-AC3, none read from the implementation): a type violation is an ELEMENT failure,
 * not a thrown validation error: `{index, error, errorCode: "schema_violation", errorKind: "permanent", availableTypes}` for
 * create, the same keyed by `id` for update, `availableTypes` present only when the effective set is non-empty, sibling
 * elements still run, `success` stays true. A blank or explicitly-null `type` on update reaches the service as a cleared
 * type and is rejected; a patch that leaves `type` out is never type-checked. The replay rule for `requestId` (a retry
 * re-runs elements that did not succeed) is stated in `current/docs/api-reference.md`, manage_items parameter table.
 *
 * The harness calls `execute` directly (no `validateParams`), like the existing manage_items tests, so the structural
 * `required` of the parameter schema (a later item) is not in play: only the service rule is.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ManageItemsTypeTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val typesYaml = "work_item_schemas:\n  beta-type:\n    notes: []\n  alpha-type:\n    notes: []\n"
    private val declared = listOf("alpha-type", "beta-type")

    private fun typedRig(dir: Path) = EventLogRig.build(db.db, dir, typesYaml)

    private fun schemaFreeRig(dir: Path) = EventLogRig.build(db.db, dir)

    private fun element(
        title: String,
        type: JsonElement? = null,
    ): JsonObject =
        buildJsonObject {
            put("title", title)
            if (type != null) put("type", type)
        }

    private fun createParams(vararg items: JsonObject): Array<Pair<String, JsonElement>> =
        arrayOf("operation" to str("create"), "items" to JsonArray(items.toList()))

    private fun updateParams(vararg items: JsonObject): Array<Pair<String, JsonElement>> =
        arrayOf("operation" to str("update"), "items" to JsonArray(items.toList()))

    private fun updateElement(
        itemId: String,
        vararg fields: Pair<String, JsonElement>,
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            fields.forEach { (k, v) -> put(k, v) }
        }

    private fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    private fun JsonObject.failures(): List<JsonObject> = data()["failures"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.types(): List<String> = this["availableTypes"]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun EventLogRig.itemCount(): Int = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items")

    private suspend fun EventLogRig.createTyped(
        title: String,
        type: String,
    ): String {
        val r = callOk(ManageItemsTool(), *createParams(element(title, JsonPrimitive(type))))
        return r
            .data()["items"]!!
            .jsonArray[0]
            .jsonObject
            .text("id")
    }

    private suspend fun EventLogRig.stored(id: String): WorkItem = raw.workItemRepository().getById(UUID.fromString(id))!!

    // ---------------------------------------------------------------------------------------------
    // S7 - create: element failures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 create batch with an untyped and a typed element fails the first with the typed element failure and creates the second`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val result = r.call(ManageItemsTool(), *createParams(element("untyped"), element("typed", JsonPrimitive("alpha-type"))))

        assertTrue(result["success"]!!.jsonPrimitive.boolean, "$result")
        assertEquals(1, result.data()["created"]!!.jsonPrimitive.int, "$result")
        assertEquals(1, result.data()["failed"]!!.jsonPrimitive.int, "$result")
        val failure = result.failures().single()
        assertEquals(0, failure["index"]!!.jsonPrimitive.int, "$failure")
        assertEquals("schema_violation", failure.text("errorCode"), "$failure")
        assertEquals("permanent", failure.text("errorKind"), "$failure")
        assertEquals(declared, failure.types(), "$failure")
        assertTrue(failure.text("error").contains("type is required"), "$failure")
        val created =
            result
                .data()["items"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("typed", created.text("title"))
        assertEquals(1, r.itemCount(), "exactly the typed sibling was written")
        assertEquals("alpha-type", r.stored(created.text("id")).type)
    }

    @Test
    fun `S7 the failing element keeps its own index among siblings`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val result =
            r.call(
                ManageItemsTool(),
                *createParams(
                    element("first", JsonPrimitive("alpha-type")),
                    element("second"),
                    element("third", JsonPrimitive("beta-type")),
                ),
            )

        assertEquals(2, result.data()["created"]!!.jsonPrimitive.int, "$result")
        val failure = result.failures().single()
        assertEquals(1, failure["index"]!!.jsonPrimitive.int, "$failure")
        assertEquals(2, r.itemCount())
    }

    @Test
    fun `S7 absent, empty, blank and tab-only types are each a type-required element failure`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val result =
            r.call(
                ManageItemsTool(),
                *createParams(
                    element("absent"),
                    element("empty", JsonPrimitive("")),
                    element("blank", JsonPrimitive("   ")),
                    element("tab", JsonPrimitive("\t")),
                ),
            )

        assertEquals(0, result.data()["created"]!!.jsonPrimitive.int, "$result")
        assertEquals(4, result.data()["failed"]!!.jsonPrimitive.int, "$result")
        val failures = result.failures()
        assertEquals(listOf(0, 1, 2, 3), failures.map { it["index"]!!.jsonPrimitive.int })
        failures.forEach {
            assertEquals("schema_violation", it.text("errorCode"), "$it")
            assertEquals("permanent", it.text("errorKind"), "$it")
            assertEquals(declared, it.types(), "$it")
        }
        assertEquals(0, r.itemCount())
    }

    @Test
    fun `S7 an explicit JSON null type on create is a type-required element failure`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val result = r.call(ManageItemsTool(), *createParams(element("null type", JsonNull)))

        assertEquals(0, result.data()["created"]!!.jsonPrimitive.int, "$result")
        val failure = result.failures().single()
        assertEquals("schema_violation", failure.text("errorCode"), "$failure")
        assertEquals(declared, failure.types(), "$failure")
        assertEquals(0, r.itemCount())
    }

    @Test
    fun `S7 an undeclared type is an element failure naming the type and listing the declared ones`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val result = r.call(ManageItemsTool(), *createParams(element("bad", JsonPrimitive("nope"))))

        val failure = result.failures().single()
        assertEquals("schema_violation", failure.text("errorCode"), "$failure")
        assertEquals("permanent", failure.text("errorKind"), "$failure")
        assertTrue(failure.text("error").contains("type 'nope' is not declared"), "$failure")
        assertEquals(declared, failure.types(), "$failure")
        assertEquals(0, r.itemCount())
    }

    @Test
    fun `S7 in a schema-free server the failure carries no availableTypes and any typed element is created`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = schemaFreeRig(dir)

        val result = r.call(ManageItemsTool(), *createParams(element("untyped"), element("typed", JsonPrimitive("anything"))))

        assertEquals(1, result.data()["created"]!!.jsonPrimitive.int, "$result")
        val failure = result.failures().single()
        assertEquals("schema_violation", failure.text("errorCode"), "$failure")
        assertFalse(failure.containsKey("availableTypes"), "nothing is declared, so the key is omitted entirely: $failure")
        assertEquals(
            "anything",
            r
                .stored(
                    result
                        .data()["items"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .text("id")
                ).type
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S7 - update: element failures keyed by id
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 update clearing the type with null, empty or blank fails the element by id and leaves the stored type`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val id = r.createTyped("item", "alpha-type")

        for (blank in listOf<JsonElement>(JsonNull, JsonPrimitive(""), JsonPrimitive("  "))) {
            val result = r.call(ManageItemsTool(), *updateParams(updateElement(id, "type" to blank)))

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "$blank: $result")
            assertEquals(0, result.data()["updated"]!!.jsonPrimitive.int, "$blank: $result")
            val failure = result.failures().single()
            assertEquals(id, failure.text("id"), "$blank: $failure")
            assertEquals("schema_violation", failure.text("errorCode"), "$blank: $failure")
            assertEquals("permanent", failure.text("errorKind"), "$blank: $failure")
            assertEquals(declared, failure.types(), "$blank: $failure")
            assertEquals("alpha-type", r.stored(id).type, "$blank: the stored type is untouched")
        }
    }

    @Test
    fun `S7 update to an undeclared type fails by id and a declared type is applied`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val id = r.createTyped("item", "alpha-type")

        val bad = r.call(ManageItemsTool(), *updateParams(updateElement(id, "type" to JsonPrimitive("nope"))))
        val failure = bad.failures().single()
        assertEquals(id, failure.text("id"), "$failure")
        assertEquals("schema_violation", failure.text("errorCode"), "$failure")
        assertTrue(failure.text("error").contains("type 'nope' is not declared"), "$failure")
        assertEquals("alpha-type", r.stored(id).type)

        val good = r.call(ManageItemsTool(), *updateParams(updateElement(id, "type" to JsonPrimitive("beta-type"))))
        assertEquals(1, good.data()["updated"]!!.jsonPrimitive.int, "$good")
        assertEquals("beta-type", r.stored(id).type)
    }

    @Test
    fun `S7 an update that leaves type out is not type-checked, even for a residue item, and siblings still run`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val residue = r.raw.workItemRepository().create(WorkItem(title = "residue", type = "untyped"))
        val typed = r.createTyped("typed", "alpha-type")

        val result =
            r.call(
                ManageItemsTool(),
                *updateParams(
                    updateElement(residue.id.toString(), "title" to JsonPrimitive("residue renamed")),
                    updateElement(typed, "type" to JsonNull),
                ),
            )

        assertEquals(1, result.data()["updated"]!!.jsonPrimitive.int, "$result")
        assertEquals(1, result.data()["failed"]!!.jsonPrimitive.int, "$result")
        assertEquals(typed, result.failures().single().text("id"))
        assertEquals("residue renamed", r.stored(residue.id.toString()).title)
        assertEquals("untyped", r.stored(residue.id.toString()).type)
        assertEquals("alpha-type", r.stored(typed).type)
    }

    // ---------------------------------------------------------------------------------------------
    // Probe - replay of a rejected element
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe - replaying a create request whose element was rejected re-runs it and rejects it again`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val actor =
            buildJsonObject {
                put("id", "agent-type-replay")
                put("kind", "subagent")
            }
        val requestId = UUID.randomUUID().toString()
        val params = arrayOf<Pair<String, JsonElement>>(*createParams(element("untyped")), "actor" to actor, "requestId" to str(requestId))

        val first = r.call(ManageItemsTool(), *params)
        val second = r.call(ManageItemsTool(), *params)

        for (result in listOf(first, second)) {
            assertEquals(0, result.data()["created"]!!.jsonPrimitive.int, "$result")
            assertEquals("schema_violation", result.failures().single().text("errorCode"), "$result")
        }
        assertEquals(0, r.itemCount(), "a replayed rejection never turns into a created row")
    }
}

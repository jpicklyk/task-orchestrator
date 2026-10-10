package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests for the Q2b `type` rule on create_work_tree (item 2c67eeca): scenario S9 of the frozen test-plan plus
 * probes. Runs the tool against the real production composition over a real SQLite file.
 *
 * Oracles (frozen task-scope 1c, AC1-AC2, none read from the implementation): a type violation anywhere in the tree fails
 * the whole call as a `VALIDATION_ERROR` whose message is `<ref>: <the service message>` where `<ref>` is `root` or the
 * child's ref, and the tree rolls back (zero items written). Attach mode (`root.id` names an existing item) never
 * re-validates the existing root. The harness calls `execute` directly, as the existing create_work_tree tests do.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class CreateWorkTreeTypeTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val typesYaml = "work_item_schemas:\n  beta-type:\n    notes: []\n  alpha-type:\n    notes: []\n"

    private fun typedRig(dir: Path) = EventLogRig.build(db.db, dir, typesYaml)

    private fun schemaFreeRig(dir: Path) = EventLogRig.build(db.db, dir)

    private fun spec(
        title: String,
        type: String? = null,
        vararg extra: Pair<String, JsonElement>,
    ): JsonObject =
        buildJsonObject {
            put("title", title)
            if (type != null) put("type", type)
            extra.forEach { (k, v) -> put(k, v) }
        }

    private fun child(
        ref: String,
        type: String? = null,
    ): JsonObject = spec("Child $ref", type, "ref" to kotlinx.serialization.json.JsonPrimitive(ref))

    private suspend fun EventLogRig.tree(
        root: JsonObject,
        vararg children: JsonObject,
    ): JsonObject =
        call(
            CreateWorkTreeTool(),
            "root" to root,
            "children" to JsonArray(children.toList()),
        )

    private suspend fun EventLogRig.itemCount(): Int = rawCount(db.jdbcUrl, "SELECT COUNT(*) FROM work_items")

    private fun JsonObject.errorCode(): String = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun JsonObject.errorMessage(): String = this["error"]!!.jsonObject["message"]!!.jsonPrimitive.content

    private fun assertRejected(
        result: JsonObject,
        refPrefix: String,
        messagePart: String,
    ) {
        assertFalse(result["success"]!!.jsonPrimitive.boolean, "$result")
        assertEquals("VALIDATION_ERROR", result.errorCode(), "$result")
        assertTrue(
            result.errorMessage().contains("$refPrefix: $messagePart"),
            "expected '$refPrefix: $messagePart' in: ${result.errorMessage()}"
        )
    }

    @Test
    fun `S9 a root without a type fails the whole tree naming the root and writes no rows`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = schemaFreeRig(dir)

        val result = r.tree(spec("Root"), child("c1", "anything"))

        assertRejected(result, "root", "type is required")
        assertEquals(0, r.itemCount())
    }

    @Test
    fun `S9 a child without a type fails the whole tree naming the child and rolls back the root and the typed sibling`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = schemaFreeRig(dir)

        val result = r.tree(spec("Root", "anything"), child("c1", "anything"), child("c2"))

        assertRejected(result, "c2", "type is required")
        assertEquals(0, r.itemCount(), "the valid root and the valid first child rolled back with the failing one")
    }

    @Test
    fun `S9 blank and whitespace-only child types are missing types`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = schemaFreeRig(dir)

        for (blank in listOf("", "   ")) {
            val result = r.tree(spec("Root", "anything"), child("c1", blank))

            assertRejected(result, "c1", "type is required")
            assertEquals(0, r.itemCount(), "type='$blank'")
        }
    }

    @Test
    fun `S9 with types declared an undeclared child type fails the tree naming the child and the type, a fully declared tree succeeds`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)

        val bad = r.tree(spec("Root", "alpha-type"), child("c1", "beta-type"), child("c2", "nope"))
        assertRejected(bad, "c2", "type 'nope' is not declared")
        assertTrue(
            bad.errorMessage().contains("alpha-type") && bad.errorMessage().contains("beta-type"),
            "the declared types are listed: ${bad.errorMessage()}"
        )
        assertEquals(0, r.itemCount(), "the whole tree rolled back")

        val good = r.tree(spec("Root", "alpha-type"), child("c1", "beta-type"))
        assertTrue(good["success"]!!.jsonPrimitive.boolean, "control: a fully declared tree is created: $good")
        assertEquals(2, r.itemCount())
        val types =
            good["data"]!!
                .jsonObject
                .let { data ->
                    listOf(data["root"]!!.jsonObject["id"]!!.jsonPrimitive.content) +
                        data["children"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                }.map {
                    r.raw
                        .workItemRepository()
                        .getById(UUID.fromString(it))!!
                        .type
                }
        assertEquals(listOf("alpha-type", "beta-type"), types)
    }

    @Test
    fun `S9 attach mode does not re-validate an existing root whose type is undeclared`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val existing: WorkItem = r.raw.workItemRepository().create(WorkItem(title = "existing", type = "untyped"))

        val result =
            r.call(
                CreateWorkTreeTool(),
                "root" to spec("existing", null, "id" to kotlinx.serialization.json.JsonPrimitive(existing.id.toString())),
                "children" to JsonArray(listOf(child("c1", "alpha-type"))),
            )

        assertTrue(result["success"]!!.jsonPrimitive.boolean, "attach must succeed, the existing root is not type-checked: $result")
        assertEquals(2, r.itemCount())
        assertEquals(
            "untyped",
            r.raw
                .workItemRepository()
                .getById(existing.id)!!
                .type,
            "the attached root keeps its type"
        )
    }

    @Test
    fun `S9 attach mode still requires a type on the new children`(
        @TempDir dir: Path,
    ) = runBlocking {
        val r = typedRig(dir)
        val existing: WorkItem = r.raw.workItemRepository().create(WorkItem(title = "existing", type = "untyped"))

        val result =
            r.call(
                CreateWorkTreeTool(),
                "root" to spec("existing", null, "id" to kotlinx.serialization.json.JsonPrimitive(existing.id.toString())),
                "children" to JsonArray(listOf(child("c1"))),
            )

        assertRejected(result, "c1", "type is required")
        assertEquals(1, r.itemCount(), "only the pre-existing root remains")
    }
}

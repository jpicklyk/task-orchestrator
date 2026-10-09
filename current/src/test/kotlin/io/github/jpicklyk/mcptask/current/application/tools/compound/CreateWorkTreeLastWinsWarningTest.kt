package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for the review follow-up (item 6adda27b): for a duplicate `(itemRef, key)` the maxLength warning must
 * follow the last-wins note, not the first one that happened to warn.
 */
class CreateWorkTreeLastWinsWarningTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val tool = CreateWorkTreeTool()

    private fun context(): ToolExecutionContext {
        val schemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                    if (tags.contains("lim-type")) listOf(NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10)) else null

                override fun getNoteLimitsMode(): String = "warn"
            }
        return ToolExecutionContext(db.repositoryProvider(), schemaService, unitOfWork = db.unitOfWork())
    }

    private fun note(body: String) =
        buildJsonObject {
            put("itemRef", JsonPrimitive("c1"))
            put("key", JsonPrimitive("lim"))
            put("role", JsonPrimitive("work"))
            put("body", JsonPrimitive(body))
        }

    private suspend fun limEntry(bodies: List<String>): JsonObject {
        val params =
            buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                put(
                    "children",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("ref", JsonPrimitive("c1"))
                                put("title", JsonPrimitive("C1"))
                                put("tags", JsonPrimitive("lim-type"))
                            }
                        )
                    )
                )
                put("notes", JsonArray(bodies.map { note(it) }))
            }
        tool.validateParams(params)
        val result = tool.execute(params, context()) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
        return result["data"]!!
            .jsonObject["notes"]!!
            .jsonArray
            .map { it.jsonObject }
            .single { it["key"]!!.jsonPrimitive.content == "lim" }
    }

    @Test
    fun `an earlier warning is dropped when the last-wins duplicate is within the limit`(): Unit =
        runBlocking {
            val entry = limEntry(listOf("x".repeat(11), "short"))

            assertNull(entry["warning"], "the stored (last) note is within maxLength: $entry")
        }

    @Test
    fun `a warning is kept when the last-wins duplicate exceeds the limit`(): Unit =
        runBlocking {
            val entry = limEntry(listOf("short", "x".repeat(11)))

            assertNotNull(entry["warning"], "the stored (last) note exceeds maxLength: $entry")
        }
}

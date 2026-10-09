package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureWriteTestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independently authored (item 6adda27b, seat test-author) cross-surface parity scenario S20 of the frozen
 * test-plan: the same logical write - key `k`, role `Work`, body `x\r\ny` - sent through manage_notes (MCP),
 * `PUT /items/{id}/notes/{key}` (REST) and create_work_tree must leave an identical (role, body) pair in the
 * store. Oracle: task-scope "one owner, every path" (policy steps 1 and 3: lowercase role, CRLF -> LF) and plan
 * section 3.6; the expected pair is stated literally, not read back from any one surface.
 *
 * Probes: the same parity for a lone-CR body, and for an update of an existing note on every surface.
 */
class NoteWritePolicyParityTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private data class Stored(
        val role: String,
        val body: String
    )

    private fun context() = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())

    private suspend fun newItem(title: String): UUID =
        db
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = title))
            .id

    private suspend fun storedPair(
        itemId: UUID,
        key: String
    ): Stored {
        val note = assertNotNull(db.repositoryProvider().noteRepository().findByItemIdAndKey(itemId, key), "note $key on $itemId")
        return Stored(note.role, note.body)
    }

    private suspend fun viaMcp(
        itemId: UUID,
        key: String,
        role: String,
        body: String
    ) {
        val tool = ManageNotesTool()
        val params =
            JsonObject(
                mapOf(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("key", JsonPrimitive(key))
                                    put("role", JsonPrimitive(role))
                                    put("body", JsonPrimitive(body))
                                }
                            )
                        )
                )
            )
        tool.validateParams(params)
        val result = tool.execute(params, context()) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "MCP: $result")
    }

    private suspend fun viaWorkTree(
        key: String,
        role: String,
        body: String
    ): UUID {
        val tool = CreateWorkTreeTool()
        val params =
            buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("parity tree $key")) })
                put(
                    "notes",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("itemRef", JsonPrimitive("root"))
                                put("key", JsonPrimitive(key))
                                put("role", JsonPrimitive(role))
                                put("body", JsonPrimitive(body))
                            }
                        )
                    )
                )
            }
        tool.validateParams(params)
        val result = tool.execute(params, context()) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "work tree: $result")
        return UUID.fromString(
            result["data"]!!
                .jsonObject["root"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content
        )
    }

    private fun restBody(
        role: String,
        body: String
    ) = buildJsonObject {
        put("role", role)
        put("body", body)
    }.toString()

    @Test
    fun `S20 key k role Work body x CRLF y is stored identically by MCP REST and create_work_tree`(): Unit =
        testApplication {
            val mcpItem = runBlocking { newItem("parity mcp") }
            val restItem = runBlocking { newItem("parity rest") }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            runBlocking { viaMcp(mcpItem, "k", "Work", "x\r\ny") }
            val response =
                client.put("/api/v1/items/$restItem/notes/k") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(restBody("Work", "x\r\ny"))
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val treeRoot = runBlocking { viaWorkTree("k", "Work", "x\r\ny") }

            val mcp = runBlocking { storedPair(mcpItem, "k") }
            val rest = runBlocking { storedPair(restItem, "k") }
            val tree = runBlocking { storedPair(treeRoot, "k") }
            assertEquals(Stored("work", "x\ny"), mcp, "MCP")
            assertEquals(mcp, rest, "REST must match MCP")
            assertEquals(mcp, tree, "create_work_tree must match MCP")
        }

    @Test
    fun `S20 probe a lone CR and WORK casing are stored identically on all three surfaces`(): Unit =
        testApplication {
            val mcpItem = runBlocking { newItem("parity2 mcp") }
            val restItem = runBlocking { newItem("parity2 rest") }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            runBlocking { viaMcp(mcpItem, "k", "WORK", "p\rq\r\nr") }
            val response =
                client.put("/api/v1/items/$restItem/notes/k") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(restBody("WORK", "p\rq\r\nr"))
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val treeRoot = runBlocking { viaWorkTree("k", "WORK", "p\rq\r\nr") }

            val expected = Stored("work", "p\rq\nr")
            assertEquals(expected, runBlocking { storedPair(mcpItem, "k") }, "MCP")
            assertEquals(expected, runBlocking { storedPair(restItem, "k") }, "REST")
            assertEquals(expected, runBlocking { storedPair(treeRoot, "k") }, "create_work_tree")
        }
}

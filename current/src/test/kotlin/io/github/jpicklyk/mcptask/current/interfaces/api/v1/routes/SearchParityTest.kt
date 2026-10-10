package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S6 (AC7): the MCP search tools and the REST search routes return identical ordered ids for the same query on the
 * same database, unscoped, page 0, `limit` 50. Both surfaces are expected to answer from one search core, so any
 * divergence in match mode, ranking, tie order or page size between them is a defect.
 *
 * Oracle: plan section 10 / AC7 (identical ordered ids at limit 50) and `api-rest.md` section 16 ("Returns up to 50
 * hits"). The fixture is mixed so the order is not trivial: exact word in the title, word only in the summary, word
 * glued into a longer token (trigram only), and a plural (matches both analyzers), 15 of each, 60 matches in total, so
 * the 50-hit page cuts the list.
 *
 * Besides the ids, the per-position `field`, `title` and `noteKey` and the `score` are compared, because the two
 * surfaces render the same hit.
 * Forbidden-construct declaration: none.
 */
class SearchParityTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val provider get() = db.repositoryProvider()
    private val context by lazy { ToolExecutionContext(provider, unitOfWork = db.unitOfWork()) }

    private data class Row(
        val itemId: String,
        val field: String,
        val title: String?,
        val noteKey: String?,
        val score: Double,
    )

    private fun rowsOf(hits: List<JsonElement>): List<Row> =
        hits.map {
            val o = it.jsonObject
            Row(
                itemId = o["itemId"]!!.jsonPrimitive.content,
                field = o["field"]!!.jsonPrimitive.content,
                title = o["title"]?.jsonPrimitive?.contentOrNull,
                noteKey = o["noteKey"]?.jsonPrimitive?.contentOrNull,
                score = o["score"]!!.jsonPrimitive.double,
            )
        }

    private suspend fun mcpRows(
        tool: io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition,
        query: String,
    ): List<Row> {
        val result =
            tool.execute(
                JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive(query),
                        "limit" to JsonPrimitive(50),
                    )
                ),
                context,
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "${tool.name} search must succeed: $result")
        return rowsOf(((result["data"] as JsonObject)["hits"]!!).jsonArray)
    }

    private suspend fun ApplicationTestBuilder.restRows(path: String): List<Row> {
        val response = client.get("/api/v1$path") { header("Authorization", "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return rowsOf(Json.parseToJsonElement(response.bodyAsText()).jsonArray)
    }

    private suspend fun seedItems() {
        val repo = provider.workItemRepository()
        repeat(15) { repo.create(WorkItem(title = "paritymark exact $it")) }
        repeat(15) { repo.create(WorkItem(title = "summary carrier $it", summary = "some words then paritymark appears here $it")) }
        repeat(15) { repo.create(WorkItem(title = "zzparitymarkzz glued $it")) }
        repeat(15) { repo.create(WorkItem(title = "paritymarks plural $it")) }
    }

    @Test
    fun `S6 query_items and GET search return identical ordered hits for the same query`() {
        runBlocking { seedItems() }
        val mcp = runBlocking { mcpRows(QueryItemsTool(), "paritymark") }

        testApplication {
            application { configureTestApp { searchRoutes(provider) } }

            val rest = restRows("/search?q=paritymark")

            assertEquals(50, mcp.size, "the MCP page is cut at limit 50 from 60 matches")
            assertEquals(mcp.map { it.itemId }, rest.map { it.itemId }, "identical ordered item ids on both surfaces")
            assertEquals(mcp.map { it.field }, rest.map { it.field }, "identical field per position")
            assertEquals(mcp.map { it.title }, rest.map { it.title }, "identical title per position")
            assertEquals(mcp.map { it.noteKey }, rest.map { it.noteKey }, "item hits carry no note key on either surface")
            mcp.zip(rest).forEach { (m, r) -> assertEquals(m.score, r.score, 1e-9, "score of ${m.itemId}") }
            assertEquals(mcp.size, mcp.map { it.itemId }.toSet().size, "no repeated hit")
        }
    }

    @Test
    fun `S6 query_notes and GET notes search return identical ordered hits for the same query`() {
        runBlocking {
            val repo = provider.workItemRepository()
            val notes = provider.noteRepository()
            val bodies =
                (0 until 15).map { "paritynote exact word $it" } +
                    (0 until 15).map { "lots of filler words before the paritynote marker appears $it" } +
                    (0 until 15).map { "zzparitynotezz glued $it" } +
                    (0 until 15).map { "paritynotes plural $it" }
            bodies.forEachIndexed { i, body ->
                val host = requireNotNull(repo.create(WorkItem(title = "note host $i")))
                requireNotNull(notes.upsert(Note(itemId = host.id, key = "spec", role = "queue", body = body)))
            }
        }
        val mcp = runBlocking { mcpRows(QueryNotesTool(), "paritynote") }

        testApplication {
            application { configureTestApp { noteRoutes(provider) } }

            val rest = restRows("/notes/search?q=paritynote")

            assertEquals(50, mcp.size, "the MCP page is cut at limit 50 from 60 matches")
            assertEquals(mcp.map { it.itemId }, rest.map { it.itemId }, "identical ordered owning item ids on both surfaces")
            assertEquals(mcp.map { it.noteKey }, rest.map { it.noteKey }, "identical note key per position")
            assertEquals(mcp.map { it.title }, rest.map { it.title }, "identical owning item title per position")
            assertEquals(mcp.map { it.field }, rest.map { it.field })
            mcp.zip(rest).forEach { (m, r) -> assertEquals(m.score, r.score, 1e-9, "score of ${m.itemId}") }
        }
    }

    @Test
    fun `S6 both surfaces agree on a query that matches nothing`() {
        runBlocking { seedItems() }
        val mcp = runBlocking { mcpRows(QueryItemsTool(), "nothingmatchesthis") }

        testApplication {
            application { configureTestApp { searchRoutes(provider) } }

            val rest = restRows("/search?q=nothingmatchesthis")

            assertEquals(emptyList(), mcp)
            assertEquals(emptyList(), rest)
        }
    }
}

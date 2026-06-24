package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parity tests: the SAME item + trigger driven through the MCP `advance_item` tool path and the
 * REST `POST /items/{id}/advance` route must yield IDENTICAL gate decisions, cascade sets, and
 * unblock sets. This is the regression guard against the two paths diverging again — the whole
 * reason [io.github.jpicklyk.mcptask.current.application.service.AdvanceService] exists.
 *
 * Both paths run over real H2-backed repositories. Equivalent (mirrored) subtrees are constructed
 * so the MCP tool can act on one and the REST route on the other within the same DB.
 */
class AdvanceParityTest {
    /** Schema with a single REQUIRED queue-phase note — drives gate parity. */
    private class GateSchemaService : WorkItemSchemaService {
        private val schema =
            WorkItemSchema(
                type = "gate-type",
                notes = listOf(NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec")),
            )

        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if ("gate-type" in tags) schema.notes else null

        override fun getSchemaForType(type: String?): WorkItemSchema? = if (type == "gate-type") schema else null
    }

    /** No schema — exercises cascade/unblock parity without gate involvement. */
    private object NoSchemaService : NoteSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
    }

    private fun mcpTool(
        repo: DefaultRepositoryProvider,
        schemaService: NoteSchemaService,
    ): Pair<AdvanceItemTool, ToolExecutionContext> = AdvanceItemTool() to ToolExecutionContext(repo, schemaService)

    private fun advanceParams(
        itemId: UUID,
        trigger: String,
    ): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", itemId.toString())
                            put("trigger", trigger)
                        },
                    )
                },
            )
        }

    private fun mcpResult(result: kotlinx.serialization.json.JsonElement): JsonObject =
        (result as JsonObject)["data"]!!.jsonObject["results"]!!.jsonArray[0].jsonObject

    // ──────────────────────────────────────────────
    // Gate decision parity (failure path)
    // ──────────────────────────────────────────────

    @Test
    fun `gate decision matches - both paths block a start with a missing required note`(): Unit =
        runBlocking {
            val repo = buildH2RepositoryProvider()
            val schemaService = GateSchemaService()

            // Two mirrored items, both gate-incomplete (no 'spec' note).
            val mcpItem =
                repo.workItemRepository().create(WorkItem(title = "MCP", type = "gate-type", role = Role.QUEUE, depth = 0)).getOrNull()!!
            val restItem =
                repo.workItemRepository().create(WorkItem(title = "REST", type = "gate-type", role = Role.QUEUE, depth = 0)).getOrNull()!!

            // MCP path.
            val (tool, ctx) = mcpTool(repo, schemaService)
            val mcpResp = mcpResult(tool.execute(advanceParams(mcpItem.id, "start"), ctx))

            // REST path.
            var restBody = ""
            testApplication {
                application { configureWriteTestApp(repo, schemaService = schemaService) }
                val r =
                    client.post("/api/v1/items/${restItem.id}/advance") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"trigger":"start"}""")
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, r.status, "REST must reject the gate-incomplete start")
                restBody = r.bodyAsText()
            }

            // MCP must also block (applied=false) with the same missing key set.
            assertFalse(mcpResp["applied"]!!.jsonPrimitive.content.toBoolean(), "MCP must block the gate-incomplete start")
            val mcpMissing = mcpResp["missingNotes"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("spec"), mcpMissing)

            // REST must name the same missing key.
            val restJson = Json.parseToJsonElement(restBody).jsonObject
            val restMissing =
                restJson["details"]!!
                    .jsonObject["missingNotes"]!!
                    .jsonArray
                    .map { it.jsonObject["key"]!!.jsonPrimitive.content }
                    .toSet()
            assertEquals(mcpMissing, restMissing, "MCP and REST must report the same missing-note set")

            // Neither item advanced.
            assertEquals(Role.QUEUE, (repo.workItemRepository().getById(mcpItem.id) as Result.Success).data.role)
            assertEquals(Role.QUEUE, (repo.workItemRepository().getById(restItem.id) as Result.Success).data.role)
        }

    // ──────────────────────────────────────────────
    // Cascade + unblock parity (success path)
    // ──────────────────────────────────────────────

    @Test
    fun `cascade and unblock sets match across MCP and REST paths`(): Unit =
        runBlocking {
            val repo = buildH2RepositoryProvider()

            // Build two mirrored subtrees:
            //   parent (WORK) → child (WORK, last child)   — completing child cascades parent→terminal
            //   child BLOCKS downstream (QUEUE)            — completing child unblocks downstream
            suspend fun buildTree(label: String): Triple<UUID, UUID, UUID> {
                val parent = repo.workItemRepository().create(WorkItem(title = "$label-parent", role = Role.WORK, depth = 0)).getOrNull()!!
                val child =
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "$label-child", role = Role.WORK, parentId = parent.id, depth = 1))
                        .getOrNull()!!
                val downstream =
                    repo.workItemRepository().create(WorkItem(title = "$label-downstream", role = Role.QUEUE, depth = 0)).getOrNull()!!
                repo.dependencyRepository().create(
                    Dependency(fromItemId = child.id, toItemId = downstream.id, type = DependencyType.BLOCKS),
                )
                return Triple(parent.id, child.id, downstream.id)
            }

            val (mcpParent, mcpChild, mcpDownstream) = buildTree("mcp")
            val (restParent, restChild, restDownstream) = buildTree("rest")

            // MCP path — complete the child.
            val (tool, ctx) = mcpTool(repo, NoSchemaService)
            val mcpResp = mcpResult(tool.execute(advanceParams(mcpChild, "complete"), ctx))

            val mcpCascadeTargets =
                mcpResp["cascadeEvents"]!!
                    .jsonArray
                    .map {
                        it.jsonObject["previousRole"]!!.jsonPrimitive.content to it.jsonObject["targetRole"]!!.jsonPrimitive.content
                    }.toSet()
            val mcpUnblocked = mcpResp["unblockedItems"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()

            // REST path — complete the mirrored child.
            var restBody = ""
            testApplication {
                application { configureWriteTestApp(repo) }
                val r =
                    client.post("/api/v1/items/$restChild/advance") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"trigger":"complete"}""")
                    }
                assertEquals(HttpStatusCode.OK, r.status)
                restBody = r.bodyAsText()
            }
            val restJson = Json.parseToJsonElement(restBody).jsonObject
            val restCascadeTargets =
                restJson["cascadeEvents"]!!
                    .jsonArray
                    .map {
                        it.jsonObject["previousRole"]!!.jsonPrimitive.content to it.jsonObject["targetRole"]!!.jsonPrimitive.content
                    }.toSet()
            val restUnblocked = restJson["unblockedItems"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()

            // Cascade ROLE-transition shape must match (work→terminal for the parent).
            assertEquals(setOf("work" to "terminal"), mcpCascadeTargets, "MCP cascade set unexpected")
            assertEquals(mcpCascadeTargets, restCascadeTargets, "MCP and REST cascade sets must match")

            // One downstream unblocked on each path.
            assertEquals(setOf("mcp-downstream"), mcpUnblocked)
            assertEquals(setOf("rest-downstream"), restUnblocked)
            assertEquals(mcpUnblocked.size, restUnblocked.size, "MCP and REST must unblock the same count")

            // Both parents cascaded to terminal, both children terminal.
            assertEquals(Role.TERMINAL, (repo.workItemRepository().getById(mcpParent) as Result.Success).data.role)
            assertEquals(Role.TERMINAL, (repo.workItemRepository().getById(restParent) as Result.Success).data.role)
            assertEquals(Role.TERMINAL, (repo.workItemRepository().getById(mcpChild) as Result.Success).data.role)
            assertEquals(Role.TERMINAL, (repo.workItemRepository().getById(restChild) as Result.Success).data.role)

            // Sanity: downstream items exist and were the unblock targets.
            assertTrue(repo.workItemRepository().getById(mcpDownstream) is Result.Success)
            assertTrue(repo.workItemRepository().getById(restDownstream) is Result.Success)
        }
}

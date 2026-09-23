package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for `query_items(operation="schema")`'s top-level `dispatch`/`resources` fields (B1,
 * dispatch trait dimension, S12 + S10 portions). Mirrors [QueryItemsToolTest]'s real-H2 harness
 * conventions — that file already covers the base `notes`/`configFingerprint`/`configSource`
 * schema-op contract; this file is scoped to the two new fields only.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and P9 in the item's `task-scope` note — never from reading QueryItemsTool's source.
 */
class QueryItemsToolSchemaDispatchTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var tool: QueryItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        tool = QueryItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /** A NoteSchemaService exposing one type schema plus a dispatch/resources map per trait name. */
    private fun schemaServiceWith(
        schema: WorkItemSchema,
        dispatchByTrait: Map<String, Map<Role, DispatchProfile>> = emptyMap(),
        resourcesByTrait: Map<String, List<ResourceRequirement>> = emptyMap()
    ): NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

            override fun getSchemaForType(type: String?): WorkItemSchema? = if (type == schema.type) schema else null

            override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = dispatchByTrait[traitName] ?: emptyMap()

            override fun getTraitResources(traitName: String): List<ResourceRequirement> = resourcesByTrait[traitName] ?: emptyList()
        }

    // ──────────────────────────────────────────────
    // S12 — schema operation by type surfaces dispatch {work,review} and trait resources
    // ──────────────────────────────────────────────

    @Test
    fun `S12 schema operation by type surfaces dispatch for work and review, and trait resources`(): Unit =
        runBlocking {
            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            val schemaService =
                schemaServiceWith(
                    schema,
                    dispatchByTrait =
                        mapOf(
                            "delegated" to
                                mapOf(
                                    Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                                    Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high")
                                )
                        ),
                    resourcesByTrait = mapOf("delegated" to listOf(ResourceRequirement(key = "db")))
                )
            val schemaContext = ToolExecutionContext(repositoryProvider, schemaService)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("schema"), "type" to JsonPrimitive("feature-task")),
                    schemaContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject

            val dispatch = data["dispatch"]!!.jsonObject
            assertEquals(setOf("work", "review"), dispatch.keys, "no queue key -- the pinned contract has no queue phase")
            assertEquals("task-orchestrator:implementer", dispatch["work"]!!.jsonObject["agent"]!!.jsonPrimitive.content)
            val review = dispatch["review"]!!.jsonObject
            assertEquals("task-orchestrator:reviewer", review["agent"]!!.jsonPrimitive.content)
            assertEquals("high", review["effort"]!!.jsonPrimitive.content)

            val resources = data["resources"]!!.jsonArray
            assertEquals(1, resources.size)
            val resource = resources[0].jsonObject
            assertEquals("db", resource["key"]!!.jsonPrimitive.content)
            assertEquals("exclusive", resource["mode"]!!.jsonPrimitive.content)
            assertFalse(resource.containsKey("ttlSeconds"), "ttlSeconds omitted when not configured")
        }

    @Test
    fun `S12 schema operation by itemId surfaces dispatch and resources resolved from the item's type`(): Unit =
        runBlocking {
            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            val schemaService =
                schemaServiceWith(
                    schema,
                    dispatchByTrait =
                        mapOf(
                            "delegated" to
                                mapOf(
                                    Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                                    Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high")
                                )
                        ),
                    resourcesByTrait = mapOf("delegated" to listOf(ResourceRequirement(key = "db")))
                )
            val schemaContext = ToolExecutionContext(repositoryProvider, schemaService)

            val item = WorkItem(id = UUID.randomUUID(), title = "Tagged item", type = "feature-task", depth = 0)
            val created = schemaContext.workItemRepository().create(item)
            assertTrue(created is Result.Success)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("schema"), "itemId" to JsonPrimitive(item.id.toString())),
                    schemaContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject

            val dispatch = data["dispatch"]!!.jsonObject
            assertEquals(setOf("work", "review"), dispatch.keys)
            assertEquals("task-orchestrator:implementer", dispatch["work"]!!.jsonObject["agent"]!!.jsonPrimitive.content)

            val resources = data["resources"]!!.jsonArray
            assertEquals(1, resources.size)
            assertEquals("db", resources[0].jsonObject["key"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // S10 — no dispatch-bearing trait -> no dispatch/resources keys; unknown trait skipped
    // ──────────────────────────────────────────────

    @Test
    fun `S10 schema operation omits dispatch and resources when the type has no dispatch-bearing trait`(): Unit =
        runBlocking {
            val schema = WorkItemSchema(type = "plain-task", notes = emptyList(), defaultTraits = emptyList())
            val schemaService = schemaServiceWith(schema)
            val schemaContext = ToolExecutionContext(repositoryProvider, schemaService)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("schema"), "type" to JsonPrimitive("plain-task")),
                    schemaContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertFalse(data.containsKey("dispatch"), "dispatch key must be absent, not an empty object, when nothing resolves")
            assertFalse(data.containsKey("resources"), "resources key must be absent, not an empty array, when nothing resolves")
        }

    @Test
    fun `S10 schema operation skips an unknown trait name silently, resolution continues to the next trait`(): Unit =
        runBlocking {
            val schema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("no-such-trait", "delegated")
                )
            val schemaService =
                schemaServiceWith(
                    schema,
                    dispatchByTrait =
                        mapOf("delegated" to mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer")))
                )
            val schemaContext = ToolExecutionContext(repositoryProvider, schemaService)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("schema"), "type" to JsonPrimitive("feature-task")),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(
                "task-orchestrator:implementer",
                data["dispatch"]!!
                    .jsonObject["work"]!!
                    .jsonObject["agent"]!!
                    .jsonPrimitive.content
            )
        }
}

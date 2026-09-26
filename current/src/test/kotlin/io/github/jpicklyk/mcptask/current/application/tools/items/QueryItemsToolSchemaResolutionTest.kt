package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
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
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `8879f554`
 * (scenario S12, EXISTING-SURFACE). `query_items(operation="schema", type=..., rootId=...)`
 * already resolves per-root vs. global (item `ce346f52`'s T2, see
 * [QueryItemsToolTest]'s per-root schema-op tests); C4 changes only WHICH mode governs that
 * resolution. Oracle: `task-scope` behaviour table, LAYERED row -- type lookup order
 * `PR[t] -> G[t] -> PR[default] -> G[default]`. Mirrors [QueryItemsToolTest]'s harness (mockk-free
 * fake [PerRootConfigSource], real H2-backed `repositoryProvider`, per
 * [QueryItemsToolSchemaDispatchTest]'s conventions).
 */
class QueryItemsToolSchemaResolutionTest {
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

    private val globalContainerSchema =
        WorkItemSchema(type = "container", notes = listOf(NoteSchemaEntry(key = "container-note", role = Role.QUEUE)))

    private val globalSchemaService: NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

            override fun getSchemaForType(type: String?): WorkItemSchema? = if (type == "container") globalContainerSchema else null

            override fun getConfigFingerprint(): String? = "global-fp"
        }

    private class FakePerRootConfigSource(
        private val layer: ConfigLayer?
    ) : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = layer
    }

    @Test
    fun `S12 - schema op on a layered root (P default, no container) resolves to the global exact schema`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRootDoc =
                ConfigDocument(
                    workItemSchemas =
                        mapOf(
                            "default" to
                                WorkItemSchema(
                                    type = "default",
                                    notes = listOf(NoteSchemaEntry(key = "per-root-default-note", role = Role.QUEUE))
                                )
                        ),
                    traits = emptyMap(),
                    schemaResolution = SchemaResolutionMode.LAYERED,
                )
            val perRoot = FakePerRootConfigSource(ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT))

            val schemaContext = ToolExecutionContext(repositoryProvider, globalSchemaService, perRootConfigService = perRoot)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("schema"),
                        "type" to JsonPrimitive("container"),
                        "rootId" to JsonPrimitive(rootId.toString())
                    ),
                    schemaContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals("container", data["type"]!!.jsonPrimitive.content)
            assertEquals("global-fp", data["configFingerprint"]!!.jsonPrimitive.content)
            assertEquals("global", data["configSource"]!!.jsonPrimitive.content)
            val notes = data["notes"]!!.jsonArray
            assertEquals(1, notes.size)
            assertEquals("container-note", notes[0].jsonObject["key"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S12 - the same fixture under an absent schema_resolution key (legacy) instead resolves to the per-root default`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRootDoc =
                ConfigDocument(
                    workItemSchemas =
                        mapOf(
                            "default" to
                                WorkItemSchema(
                                    type = "default",
                                    notes = listOf(NoteSchemaEntry(key = "per-root-default-note", role = Role.QUEUE))
                                )
                        ),
                    traits = emptyMap(),
                    schemaResolution = null,
                )
            val perRoot = FakePerRootConfigSource(ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT))

            val schemaContext = ToolExecutionContext(repositoryProvider, globalSchemaService, perRootConfigService = perRoot)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("schema"),
                        "type" to JsonPrimitive("container"),
                        "rootId" to JsonPrimitive(rootId.toString())
                    ),
                    schemaContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals("default", data["type"]!!.jsonPrimitive.content)
            assertEquals("pr-fp", data["configFingerprint"]!!.jsonPrimitive.content)
            assertEquals("per-root", data["configSource"]!!.jsonPrimitive.content)
        }
}

package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f75eb22e / Q8, scenario S16) for the wire shape of the 3.x
 * `manage_project_config` tool after it started delegating to the versioned [ConfigService].
 *
 * Oracle: task-scope section 1.9 and requirement R6: `push` and `get` keep their 3.x key sets, minus the
 * `ignoredSections` key that 4.0 removed (`rootId, fingerprint, updatedAt, warning?, schemaWarnings?` and
 * `rootId, configYaml, fingerprint, updatedAt, relation?`), `updatedAt` is the head version's creation time, a
 * byte-identical re-push is naturally idempotent, and nothing about versions is added to the operation.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ManageProjectConfigToolWireTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val tool = ManageProjectConfigTool(YamlConfigDocumentParser)

    private val yaml =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private val versionWords = setOf("version", "versions", "appended", "source", "headVersion", "etag", "canonical", "history", "rollback")

    private fun context(withService: Boolean): ToolExecutionContext {
        val provider = sqlite.repositoryProvider()
        val uow = sqlite.unitOfWork()
        return if (withService) {
            ToolExecutionContext(provider, unitOfWork = uow, configService = ConfigService(provider, YamlConfigDocumentParser, uow))
        } else {
            ToolExecutionContext(provider, unitOfWork = uow)
        }
    }

    private suspend fun newRoot(type: String? = "project"): UUID =
        sqlite
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = "Root", type = type, depth = 0))
            .id

    private fun call(
        ctx: ToolExecutionContext,
        vararg pairs: Pair<String, JsonElement>
    ): JsonObject = runBlocking { tool.execute(JsonObject(mapOf(*pairs)), ctx) }.jsonObject

    private fun push(
        ctx: ToolExecutionContext,
        root: UUID,
        text: String
    ) = call(ctx, "operation" to JsonPrimitive("push"), "rootId" to JsonPrimitive(root.toString()), "configYaml" to JsonPrimitive(text))

    private fun get(
        ctx: ToolExecutionContext,
        root: UUID,
        fingerprint: String? = null
    ): JsonObject {
        val args = mutableMapOf<String, JsonElement>("operation" to JsonPrimitive("get"), "rootId" to JsonPrimitive(root.toString()))
        if (fingerprint != null) args["fingerprint"] = JsonPrimitive(fingerprint)
        return runBlocking { tool.execute(JsonObject(args), ctx) }.jsonObject
    }

    private fun JsonObject.ok() = this["success"]!!.jsonPrimitive.boolean

    private fun JsonObject.data() = this["data"]!!.jsonObject

    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 push returns exactly the 3x key set, with an explicit service and with the tool's own fallback`() {
        for (withService in listOf(true, false)) {
            runBlocking {
                val ctx = context(withService)
                val root = newRoot()

                val result = push(ctx, root, yaml)

                assertTrue(result.ok(), "withService=$withService: $result")
                assertEquals(setOf("rootId", "fingerprint", "updatedAt"), result.data().keys, "withService=$withService")
                assertEquals(root.toString(), result.data()["rootId"]!!.jsonPrimitive.content)
                assertEquals(
                    sqlite.repositoryProvider().configStore().computeFingerprint(yaml),
                    result.data()["fingerprint"]!!.jsonPrimitive.content
                )
            }
        }
    }

    @Test
    fun `S16 push with advisory warnings and a non-project root keeps only the 3x optional keys`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot(type = "feature-task")
            val unownedNote =
                "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                    "    notes:\n      - key: spec\n        role: work\n        required: true\n"

            val result = push(ctx, root, unownedNote)

            assertTrue(result.ok(), "$result")
            val keys = result.data().keys
            assertEquals(setOf("rootId", "fingerprint", "updatedAt", "warning", "schemaWarnings"), keys)
            assertTrue(keys.none { it in versionWords }, "no version vocabulary leaks into the response: $keys")
        }
    }

    @Test
    fun `S16 get returns exactly the 3x key set and adds relation only when a fingerprint is passed`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot()
            val pushed = push(ctx, root, yaml)

            val plain = get(ctx, root)
            assertTrue(plain.ok(), "$plain")
            assertEquals(setOf("rootId", "configYaml", "fingerprint", "updatedAt"), plain.data().keys)
            assertEquals(yaml, plain.data()["configYaml"]!!.jsonPrimitive.content)

            val withRelation = get(ctx, root, pushed.data()["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(setOf("rootId", "configYaml", "fingerprint", "updatedAt", "relation"), withRelation.data().keys)
            assertEquals("current", withRelation.data()["relation"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `S16 updatedAt is the creation time of the head version`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot()

            val pushed = push(ctx, root, yaml)
            val head = assertNotNull(sqlite.repositoryProvider().configStore().head(ConfigScope.ROOT, root))
            val headRow = assertNotNull(sqlite.repositoryProvider().configStore().version(head.versionId))

            assertEquals(headRow.createdAt, Instant.parse(pushed.data()["updatedAt"]!!.jsonPrimitive.content))
            assertEquals(headRow.createdAt, Instant.parse(get(ctx, root).data()["updatedAt"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `S16 a byte-identical re-push is idempotent - same fingerprint and updatedAt, one stored version`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot()
            val first = push(ctx, root, yaml)

            val again = push(ctx, root, yaml)

            assertTrue(again.ok(), "$again")
            assertEquals(first.data()["fingerprint"], again.data()["fingerprint"])
            assertEquals(first.data()["updatedAt"], again.data()["updatedAt"])
            assertEquals(
                1,
                sqlite
                    .repositoryProvider()
                    .configStore()
                    .history(ConfigScope.ROOT, root, 50)
                    .size,
                "fixture condition: a second row would make this 2"
            )
        }
    }

    @Test
    fun `S16 a changed push moves the fingerprint and get follows the head`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot()
            val first = push(ctx, root, yaml)
            val changed = "$yaml# changed\n"

            val second = push(ctx, root, changed)

            assertNotEquals(first.data()["fingerprint"]!!.jsonPrimitive.content, second.data()["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(changed, get(ctx, root).data()["configYaml"]!!.jsonPrimitive.content)
            assertEquals(
                2,
                sqlite
                    .repositoryProvider()
                    .configStore()
                    .history(ConfigScope.ROOT, root, 50)
                    .size
            )
        }
    }

    @Test
    fun `S16 get of a reset or never-configured root is NOT_FOUND`() {
        runBlocking {
            val ctx = context(withService = true)
            val root = newRoot()
            val bare = newRoot()
            push(ctx, root, yaml)
            val service = ConfigService(sqlite.repositoryProvider(), YamlConfigDocumentParser, sqlite.unitOfWork())
            assertEquals(true, service.reset(root).getOrNull())

            for (id in listOf(root, bare)) {
                val result = get(ctx, id)
                assertFalse(result.ok(), "root $id: $result")
                assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `S16 the operation gained no version parameters and its description does not mention rollback`() {
        val properties = tool.parameterSchema.properties!!.keys

        assertTrue(
            properties.none {
                it in setOf("version", "toVersion", "expectedVersion", "before", "limit", "history", "rollback")
            },
            "unexpected parameters: $properties"
        )
        assertTrue(
            "rootId" in properties && "operation" in properties && "configYaml" in properties && "fingerprint" in properties,
            "3.x parameters stay: $properties"
        )
        assertFalse(tool.description.contains("rollback", ignoreCase = true), "R6: no prose about versions or rollback is added")
    }
}

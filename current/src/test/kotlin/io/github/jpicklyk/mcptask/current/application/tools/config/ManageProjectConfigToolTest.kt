package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [ManageProjectConfigTool] against a real H2-backed [SQLiteProjectConfigRepository] and
 * [SQLiteWorkItemRepository] (mirroring [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContextPerRootIntegrationTest]'s
 * DB-backed style) rather than mocking the repository — push validation depends on real WorkItem
 * rows (depth, type) and the parse-before-store contract depends on nothing being written when
 * `configYaml` is invalid, both of which are easiest to prove against a real table.
 */
class ManageProjectConfigToolTest {
    private lateinit var tool: ManageProjectConfigTool
    private lateinit var databaseManager: DatabaseManager
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var context: ToolExecutionContext
    private lateinit var rootId: UUID

    private val validYaml =
        """
        work_item_schemas:
          feature-task:
            notes:
              - key: spec
                role: queue
                required: true
        """.trimIndent()

    @BeforeEach
    fun setUp() =
        runBlocking {
            tool = ManageProjectConfigTool()

            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            context = ToolExecutionContext(repositoryProvider)

            val root =
                (workItemRepository.create(WorkItem(title = "Project Root", type = "project")) as Result.Success).data
            rootId = root.id
        }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    private fun dataOf(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun errorOf(result: JsonElement): JsonObject = (result as JsonObject)["error"] as JsonObject

    private fun push(
        rootItemId: String,
        configYaml: String,
        force: Boolean? = null
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    *buildList {
                        add("operation" to JsonPrimitive("push"))
                        add("rootItemId" to JsonPrimitive(rootItemId))
                        add("configYaml" to JsonPrimitive(configYaml))
                        if (force != null) add("force" to JsonPrimitive(force))
                    }.toTypedArray()
                ),
                context
            )
        }

    private fun get(rootItemId: String): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    "operation" to JsonPrimitive("get"),
                    "rootItemId" to JsonPrimitive(rootItemId)
                ),
                context
            )
        }

    // ──────────────────────────────────────────────
    // push
    // ──────────────────────────────────────────────

    @Test
    fun `push happy path returns fingerprint`() {
        val result = push(rootId.toString(), validYaml)

        assertTrue(isSuccess(result))
        val data = dataOf(result)
        assertEquals(rootId.toString(), data["rootItemId"]!!.jsonPrimitive.content)
        assertFalse(data["fingerprint"]!!.jsonPrimitive.content.isBlank())
        assertNotNull(data["updatedAt"])
        assertNull(data["warning"], "root is type='project' — no warning expected")
    }

    @Test
    fun `push surfaces ignoredSections when the doc has an unhonored top-level key`() {
        // Plain concatenation (not a nested trimIndent template) — interpolating an already-
        // trimIndent'd multi-line constant into another trimIndent block would get its indentation
        // re-mangled by the outer trim's common-margin computation (see ProjectConfigPushServiceTest).
        val yamlWithActorAuth = "$validYaml\nactor_authentication:\n  mode: jwks\n"

        val result = push(rootId.toString(), yamlWithActorAuth)

        assertTrue(isSuccess(result))
        val ignoredSections = dataOf(result)["ignoredSections"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("actor_authentication"), ignoredSections)
    }

    @Test
    fun `push omits ignoredSections when the doc only uses honored keys`() {
        val result = push(rootId.toString(), validYaml)

        assertTrue(isSuccess(result))
        assertNull(dataOf(result)["ignoredSections"], "ignoredSections must be omitted entirely when empty, not an empty array")
    }

    @Test
    fun `push with hex-prefix rootItemId resolves the item`() {
        val prefix = rootId.toString().replace("-", "").take(8)

        val result = push(prefix, validYaml)

        assertTrue(isSuccess(result))
        assertEquals(rootId.toString(), dataOf(result)["rootItemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push to nonexistent root returns error`() {
        val result = push(UUID.randomUUID().toString(), validYaml)

        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push to non-depth-0 item returns error`() {
        runBlocking {
            val parent = (workItemRepository.create(WorkItem(title = "Parent")) as Result.Success).data
            val child =
                (
                    workItemRepository.create(
                        WorkItem(title = "Child", parentId = parent.id, depth = 1)
                    ) as Result.Success
                ).data

            val result = push(child.id.toString(), validYaml)

            assertFalse(isSuccess(result))
            assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
            assertTrue(errorOf(result)["message"]!!.jsonPrimitive.content.contains("depth-0"))
        }
    }

    @Test
    fun `push to non-project-typed root succeeds with a warning field`() {
        runBlocking {
            val untyped = (workItemRepository.create(WorkItem(title = "Untyped Root")) as Result.Success).data

            val result = push(untyped.id.toString(), validYaml)

            assertTrue(isSuccess(result))
            val warning = dataOf(result)["warning"]?.jsonPrimitive?.content
            assertNotNull(warning, "expected a non-fatal warning for a non-'project'-typed root")
            assertTrue(warning.contains("project"))
        }
    }

    @Test
    fun `push unparseable YAML returns error containing parse detail and stores no row`() {
        val badYaml = "work_item_schemas: [this is not, a valid: map"

        val result = push(rootId.toString(), badYaml)

        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("failed to parse"))

        // No row must have been stored — a broken config must never silently exist server-side.
        val getResult = get(rootId.toString())
        assertFalse(isSuccess(getResult))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(getResult)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push with a SnakeYAML type-tag payload is rejected as a parse error and stores nothing`() {
        // A `!!`-tag payload attempting arbitrary Java object instantiation — the class of
        // deserialization-RCE gadget SnakeYAML's unsafe default Constructor would build (CWE-502).
        // With SafeConstructor wired in, this must be rejected at the tag-resolution stage, before
        // any object is ever instantiated — proven here by asserting nothing gets stored, exactly
        // like the "unparseable YAML" case above.
        val maliciousYaml = "!!java.net.URL [\"http://169.254.169.254/latest/meta-data/\"]"

        val result = push(rootId.toString(), maliciousYaml)

        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)

        val getResult = get(rootId.toString())
        assertFalse(isSuccess(getResult))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(getResult)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push with oversized configYaml is rejected before any parse attempt`() {
        val oversized = "x".repeat(ManageProjectConfigTool.MAX_CONFIG_YAML_BYTES + 1)

        val result = push(rootId.toString(), oversized)

        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue(message.contains(ManageProjectConfigTool.MAX_CONFIG_YAML_BYTES.toString()))
        assertTrue(message.contains((ManageProjectConfigTool.MAX_CONFIG_YAML_BYTES + 1).toString()))

        val getResult = get(rootId.toString())
        assertFalse(isSuccess(getResult))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(getResult)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `idempotent re-push of identical content returns the same fingerprint`() {
        val first = push(rootId.toString(), validYaml)
        val second = push(rootId.toString(), validYaml)

        assertTrue(isSuccess(first))
        assertTrue(isSuccess(second))
        assertEquals(
            dataOf(first)["fingerprint"]!!.jsonPrimitive.content,
            dataOf(second)["fingerprint"]!!.jsonPrimitive.content
        )
    }

    // ──────────────────────────────────────────────
    // rootId mismatch guard
    // ──────────────────────────────────────────────

    @Test
    fun `push with embedded project rootId matching the target succeeds`() {
        val yaml = "project:\n  rootId: $rootId\n$validYaml"

        val result = push(rootId.toString(), yaml)

        assertTrue(isSuccess(result))
    }

    @Test
    fun `push with embedded project rootId differing from the target is rejected naming both ids`() {
        val otherRootId = UUID.randomUUID()
        val yaml = "project:\n  rootId: $otherRootId\n$validYaml"

        val result = push(rootId.toString(), yaml)

        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue(message.contains(rootId.toString()), "message should name the target rootId: $message")
        assertTrue(message.contains(otherRootId.toString()), "message should name the embedded rootId: $message")

        // Rejected mismatch must not have stored anything.
        val getResult = get(rootId.toString())
        assertFalse(isSuccess(getResult))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(getResult)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push with a malformed non-UUID embedded project rootId proceeds unchanged`() {
        val yaml = "project:\n  rootId: not-a-uuid\n$validYaml"

        val result = push(rootId.toString(), yaml)

        assertTrue(isSuccess(result))
    }

    @Test
    fun `push with force true bypasses a mismatched embedded project rootId`() {
        val otherRootId = UUID.randomUUID()
        val yaml = "project:\n  rootId: $otherRootId\n$validYaml"

        val result = push(rootId.toString(), yaml, force = true)

        assertTrue(isSuccess(result))
        val getResult = get(rootId.toString())
        assertTrue(isSuccess(getResult))
    }

    // ──────────────────────────────────────────────
    // get
    // ──────────────────────────────────────────────

    @Test
    fun `get returns stored config`() {
        push(rootId.toString(), validYaml)

        val result = get(rootId.toString())

        assertTrue(isSuccess(result))
        val data = dataOf(result)
        assertEquals(rootId.toString(), data["rootItemId"]!!.jsonPrimitive.content)
        assertEquals(validYaml, data["configYaml"]!!.jsonPrimitive.content)
        assertFalse(data["fingerprint"]!!.jsonPrimitive.content.isBlank())
        assertNotNull(data["updatedAt"])
    }

    @Test
    fun `get with no row returns NOT_FOUND`() {
        val result = get(rootId.toString())

        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    private fun getWithFingerprint(
        rootItemId: String,
        fingerprint: String
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    "operation" to JsonPrimitive("get"),
                    "rootItemId" to JsonPrimitive(rootItemId),
                    "fingerprint" to JsonPrimitive(fingerprint)
                ),
                context
            )
        }

    @Test
    fun `get without a fingerprint param omits relation from the response`() {
        push(rootId.toString(), validYaml)

        val result = get(rootId.toString())

        assertTrue(isSuccess(result))
        assertNull(dataOf(result)["relation"])
    }

    @Test
    fun `get with a fingerprint matching current returns relation current`() {
        val pushResult = push(rootId.toString(), validYaml)
        val currentFingerprint = dataOf(pushResult)["fingerprint"]!!.jsonPrimitive.content

        val result = getWithFingerprint(rootId.toString(), currentFingerprint)

        assertTrue(isSuccess(result))
        assertEquals("current", dataOf(result)["relation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get with a superseded fingerprint returns relation superseded`() {
        val validYaml2 =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: other
                    role: queue
                    required: true
            """.trimIndent()
        val firstPush = push(rootId.toString(), validYaml)
        val firstFingerprint = dataOf(firstPush)["fingerprint"]!!.jsonPrimitive.content
        push(rootId.toString(), validYaml2)

        val result = getWithFingerprint(rootId.toString(), firstFingerprint)

        assertTrue(isSuccess(result))
        assertEquals("superseded", dataOf(result)["relation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get with an unrelated fingerprint returns relation unknown`() {
        push(rootId.toString(), validYaml)

        val result = getWithFingerprint(rootId.toString(), "0".repeat(64))

        assertTrue(isSuccess(result))
        assertEquals("unknown", dataOf(result)["relation"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // fast-forward (known-old) push guard
    // ──────────────────────────────────────────────

    @Test
    fun `push with a superseded fingerprint is rejected as CONFLICT_ERROR and does not overwrite`() {
        val validYaml2 =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: other
                    role: queue
                    required: true
            """.trimIndent()
        push(rootId.toString(), validYaml)
        push(rootId.toString(), validYaml2)

        val result = push(rootId.toString(), validYaml)

        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.CONFLICT_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)

        val getResult = get(rootId.toString())
        assertEquals(validYaml2, dataOf(getResult)["configYaml"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push with force true bypasses a superseded fingerprint`() {
        val validYaml2 =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: other
                    role: queue
                    required: true
            """.trimIndent()
        push(rootId.toString(), validYaml)
        push(rootId.toString(), validYaml2)

        val result = push(rootId.toString(), validYaml, force = true)

        assertTrue(isSuccess(result))
        val getResult = get(rootId.toString())
        assertEquals(validYaml, dataOf(getResult)["configYaml"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // validateParams
    // ──────────────────────────────────────────────

    @Test
    fun `validateParams rejects push without configYaml`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("push"),
                    "rootItemId" to JsonPrimitive(rootId.toString())
                )
            )
        }
    }

    @Test
    fun `validateParams rejects an unknown operation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("delete"),
                    "rootItemId" to JsonPrimitive(rootId.toString())
                )
            )
        }
    }

    // ──────────────────────────────────────────────
    // End-to-end: pushed config is visible through ToolExecutionContext.resolveSchema
    // ──────────────────────────────────────────────

    @Test
    fun `pushed config resolves through ToolExecutionContext for an item under that root`() {
        runBlocking {
            val pushResult = push(rootId.toString(), validYaml)
            assertTrue(isSuccess(pushResult))

            val perRootConfigService = PerRootConfigService(projectConfigRepository)
            val resolvingContext =
                ToolExecutionContext(
                    mockk<RepositoryProvider>(relaxed = true).also {
                        every { it.workItemRepository() } returns workItemRepository
                        every { it.projectConfigRepository() } returns projectConfigRepository
                    },
                    perRootConfigService = perRootConfigService
                )

            val childItem =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Child under configured root",
                    type = "feature-task",
                    rootId = rootId,
                    depth = 0
                )

            val resolved = resolvingContext.resolveSchema(childItem)

            assertNotNull(resolved, "expected the per-root pushed schema to resolve, not schema-free mode")
            assertEquals(1, resolved.notes.size)
            assertEquals("spec", resolved.notes[0].key)
        }
    }
}

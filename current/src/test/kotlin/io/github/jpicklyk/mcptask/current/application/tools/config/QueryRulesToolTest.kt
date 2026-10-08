package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored (blind test author) against the frozen `task-scope` / `test-plan` /
 * `task-scope-addendum` notes on item `840e700a` (A3) -- the MCP half of S1, S2, S3, S4, S6, S8,
 * S10, plus probes P2-P4, P6-P8 (`task-scope-addendum` par F). REST-side scenarios (S1 REST leg,
 * S4 REST leg, S7, probe P1) live in `RuleRoutesTest.kt`; the config_unavailable scenario (S9)
 * lives in `QueryRulesConfigUnavailableTest.kt`; the pointer-only-schema scenario (S5) lives in
 * `RulePointerOnlyGoldenTest.kt`; the size-budget/D1 scenarios (S11, S12) live in
 * `RuleBudgetStashTest.kt` -- per this item's File ownership table.
 *
 * Harness (contract "Public-API rule" / "Harness rule"): every MCP call runs `QueryRulesTool`
 * (and `ManagePlanDocumentsTool` for fixture stashing where the oracle explicitly names that
 * surface) against the `ToolExecutionContext` from a REAL `ServerComposition.build()` over a SQLite
 * DB -- never a hand-built TEC.
 */
class QueryRulesToolTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // --- Shared composition wiring (own copy; no shared harness file across owned test files) ---

    private fun buildDatabaseManager(): DatabaseManager = db.databaseManager

    private fun materializeGlobalConfig(
        tempDir: Path,
        content: String,
    ) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), content.toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(
        tempDir: Path,
        globalConfig: String = "work_item_schemas: {}\n",
    ): CompositionResult {
        materializeGlobalConfig(tempDir, globalConfig)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = buildDatabaseManager(),
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun queryRules(
        context: ToolExecutionContext,
        vararg pairs: Pair<String, JsonElement>,
    ): JsonObject = runBlocking { QueryRulesTool().execute(params(*pairs), context) } as JsonObject

    /** Fixture setup via the actual MCP surface named by the oracle (S1) -- not a repository shortcut. */
    private fun stashViaTool(
        context: ToolExecutionContext,
        rootId: UUID,
        slug: String,
        body: String,
    ): JsonObject {
        val result =
            runBlocking {
                ManagePlanDocumentsTool().execute(
                    params(
                        "operation" to JsonPrimitive("stash"),
                        "rootId" to JsonPrimitive(rootId.toString()),
                        "slug" to JsonPrimitive(slug),
                        "body" to JsonPrimitive(body),
                    ),
                    context,
                )
            } as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "fixture stash failed: $result")
        return result
    }

    /** Fixture setup that bypasses the tool surface, for scenarios where stash mechanics aren't the oracle under test. */
    private fun stashDirect(
        repo: RepositoryProvider,
        rootId: UUID,
        slug: String,
        body: String,
    ) {
        runBlocking { repo.planDocumentRepository().stash(rootId, slug, body) }
    }

    private fun isSuccess(result: JsonObject): Boolean = result["success"]!!.jsonPrimitive.boolean

    private fun dataOf(result: JsonObject): JsonObject = result["data"] as JsonObject

    private fun errorOf(result: JsonObject): JsonObject = result["error"] as JsonObject

    companion object {
        /**
         * Per `task-scope-addendum` par B: a CRLF pair, U+2014 (em-dash), U+00E9 (e-acute), U+1F680
         * (rocket, surrogate pair), a TAB, two trailing spaces, and no final LF.
         */
        const val FIXTURE_B =
            "First line ends here.\r\nSecond line: em-dash —, e-acute é, rocket 🚀, and a\ttab.  "
        val FIXTURE_B2 = "${FIXTURE_B}x"
    }

    // -----------------------------------------------------------------------
    // S1 (MCP leg) -- served body byte-identical to what was stashed
    // -----------------------------------------------------------------------

    @Test
    fun `S1 get(rootId,key) serves the stashed body UTF-8 byte-identical, incl CRLF, trailing spaces, no final LF, multibyte chars`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S1 Root", depth = 0))!! }
        stashViaTool(composition.toolContext, root.id, "rule/protocol.entry-seat", FIXTURE_B)

        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("protocol.entry-seat"),
            )
        assertTrue(isSuccess(result), "expected success: $result")
        val data = dataOf(result)
        val servedBody = data["body"]!!.jsonPrimitive.content
        assertEquals(FIXTURE_B, servedBody, "served body must equal the stashed fixture exactly (task-scope Acceptance)")
        assertContentEquals(
            FIXTURE_B.toByteArray(Charsets.UTF_8),
            servedBody.toByteArray(Charsets.UTF_8),
            "served body must be UTF-8 byte-identical to the stashed fixture",
        )
        assertEquals(root.id.toString(), data["rootId"]!!.jsonPrimitive.content)
        assertEquals("protocol.entry-seat", data["key"]!!.jsonPrimitive.content)
        assertEquals("rule/protocol.entry-seat", data["slug"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // S2 -- rulesVersion == contentHash == SHA-256(UTF-8 body), independently computed
    // -----------------------------------------------------------------------

    @Test
    fun `S2 rulesVersion equals contentHash equals an independently computed lowercase-hex SHA-256 of the UTF-8 body, and tracks re-stash`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S2 Root", depth = 0))!! }
        val stashResult = stashViaTool(composition.toolContext, root.id, "rule/versioned", FIXTURE_B)
        val contentHash = dataOf(stashResult)["contentHash"]!!.jsonPrimitive.content

        val expectedHash = sha256Hex(FIXTURE_B.toByteArray(Charsets.UTF_8))
        assertEquals(expectedHash, contentHash, "sanity: PlanDocumentService.contentHash must equal an independently computed SHA-256")

        val getResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("versioned"),
            )
        assertEquals(contentHash, dataOf(getResult)["rulesVersion"]!!.jsonPrimitive.content, "rulesVersion must equal contentHash")

        // Re-stash with B2 -> rulesVersion changes to match the new hash.
        val stash2 = stashViaTool(composition.toolContext, root.id, "rule/versioned", FIXTURE_B2)
        val hash2 = dataOf(stash2)["contentHash"]!!.jsonPrimitive.content
        assertEquals(sha256Hex(FIXTURE_B2.toByteArray(Charsets.UTF_8)), hash2)
        val get2 =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("versioned"),
            )
        val version2 = dataOf(get2)["rulesVersion"]!!.jsonPrimitive.content
        assertEquals(hash2, version2)
        assertFalse(version2 == contentHash, "rulesVersion must change after the body changes")

        // Re-stash with the original B -> rulesVersion reverts to the original hash.
        val stash3 = stashViaTool(composition.toolContext, root.id, "rule/versioned", FIXTURE_B)
        val hash3 = dataOf(stash3)["contentHash"]!!.jsonPrimitive.content
        assertEquals(contentHash, hash3, "re-stashing the original body must restore the original content hash")
        val get3 =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("versioned"),
            )
        assertEquals(
            contentHash,
            dataOf(get3)["rulesVersion"]!!.jsonPrimitive.content,
            "rulesVersion (not just contentHash) must also revert on re-stash of the original body",
        )
    }

    // -----------------------------------------------------------------------
    // S3 -- item-mode skill-pointer resolution through the trait-merged (effective) schema
    // -----------------------------------------------------------------------

    @Test
    fun `S3 item mode resolves noteKey to its schema entry skill and serves the SAME rule the rootId+key mode serves, honoring trait merge`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              ft-trait:
                default_traits: [needs-test-author]
                notes:
                  - key: task-scope
                    role: queue
                    required: true
                    skill: spec-quality
            traits:
              needs-test-author:
                notes:
                  - key: test-plan
                    role: queue
                    required: true
                    skill: test-author
            """.trimIndent()
        val composition = buildComposition(tempDir, globalConfig)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S3 Root", depth = 0))!! }
        val item =
            runBlocking {
                repo
                    .workItemRepository()
                    .create(
                        WorkItem(
                            title = "S3 feature-task",
                            type = "ft-trait",
                            role = Role.QUEUE,
                            parentId = root.id,
                            rootId = root.id,
                            depth = 1,
                        ),
                    )!!
            }
        stashViaTool(composition.toolContext, root.id, "rule/test-author", "Test-author rule body.")
        stashViaTool(composition.toolContext, root.id, "rule/spec-quality", "Spec-quality rule body.")

        // test-plan is the TRAIT-merged note (from needs-test-author) -- resolving it proves the
        // effective (trait-merged) schema is used, not just the base type's own notes.
        val traitResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("test-plan"),
            )
        assertTrue(isSuccess(traitResult), "expected success: $traitResult")
        val traitData = dataOf(traitResult)
        val directTraitResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("test-author"),
            )
        val directTraitData = dataOf(directTraitResult)
        assertEquals(directTraitData["body"], traitData["body"], "item-mode body must equal the rootId+key body for the resolved rule key")
        assertEquals(directTraitData["rulesVersion"], traitData["rulesVersion"])
        val resolvedFrom = traitData["resolvedFrom"]!!.jsonObject
        assertEquals(item.id.toString(), resolvedFrom["itemId"]!!.jsonPrimitive.content)
        assertEquals("test-plan", resolvedFrom["noteKey"]!!.jsonPrimitive.content)
        assertEquals("test-author", resolvedFrom["skill"]!!.jsonPrimitive.content)

        // task-scope is the BASE type's own note -> resolves to key spec-quality.
        val baseResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("task-scope"),
            )
        assertTrue(isSuccess(baseResult), "expected success: $baseResult")
        val baseResolvedFrom = dataOf(baseResult)["resolvedFrom"]!!.jsonObject
        assertEquals("spec-quality", baseResolvedFrom["skill"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // S4 (MCP leg) -- list(rootId) returns only valid rule/ slugs, sorted by key, no body
    // -----------------------------------------------------------------------

    @Test
    fun `S4 list(rootId) returns only valid rule slugs sorted by key with no body, excluding non-rule plans and invalid rule keys`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S4 Root", depth = 0))!! }

        stashDirect(repo, root.id, "rule/test-author", "test-author body")
        stashDirect(repo, root.id, "rule/protocol.entry-seat", "entry-seat body")
        // Excluded: wrong prefix, bare prefix with no key, invalid key grammar, and a non-rule plan.
        stashDirect(repo, root.id, "rules/x", "wrong prefix body")
        stashDirect(repo, root.id, "rule/", "bare prefix body")
        stashDirect(repo, root.id, "rule/Bad Key", "invalid key grammar body")
        stashDirect(repo, root.id, "plan-a", "ordinary non-rule plan body")

        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("list"),
                "rootId" to JsonPrimitive(root.id.toString()),
            )
        assertTrue(isSuccess(result), "expected success: $result")
        val data = dataOf(result)
        assertEquals(root.id.toString(), data["rootId"]!!.jsonPrimitive.content)
        val rules = data["rules"]!!.jsonArray
        val keys = rules.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("protocol.entry-seat", "test-author"),
            keys,
            "list must contain only the two valid rule/ slugs, sorted by key ascending",
        )
        rules.forEach { entry ->
            assertFalse((entry.jsonObject).containsKey("body"), "list entries must never include body")
        }
    }

    // -----------------------------------------------------------------------
    // S6 -- failure envelope: unknown key, unknown root, non-depth-0 root
    // -----------------------------------------------------------------------

    @Test
    fun `S6 unknown key at a known root returns RESOURCE_NOT_FOUND naming the key`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(WorkItem(title = "S6 Root", depth = 0))!!
            }
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("no-such-rule"),
            )
        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("no-such-rule"), "message must name the key: $error")
    }

    @Test
    fun `S6 unknown root returns RESOURCE_NOT_FOUND naming the root`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val unknownRoot = UUID.randomUUID()
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(unknownRoot.toString()),
                "key" to JsonPrimitive("some-key"),
            )
        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Root WorkItem not found"), "message: $error")
    }

    @Test
    fun `S6 a non-depth-0 rootId returns VALIDATION_ERROR`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val (_, child) =
            runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "S6 Root", depth = 0))!!
                val c =
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "S6 Child", parentId = r.id, rootId = r.id, depth = 1))!!
                r to c
            }
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(child.id.toString()),
                "key" to JsonPrimitive("some-key"),
            )
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // Review follow-up: list-mode root errors (unknown root / non-depth-0) -- S6 previously only
    // exercised the get() leg of these two root-level errors, never list().
    // -----------------------------------------------------------------------

    @Test
    fun `review follow-up - list(rootId) for an unknown root returns RESOURCE_NOT_FOUND naming the root`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val unknownRoot = UUID.randomUUID()
        val result =
            queryRules(composition.toolContext, "operation" to JsonPrimitive("list"), "rootId" to JsonPrimitive(unknownRoot.toString()))
        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Root WorkItem not found"), "message: $error")
    }

    @Test
    fun `review follow-up - list(rootId) for a non-depth-0 root returns VALIDATION_ERROR`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val child =
            runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "List VALIDATION_ERROR Root", depth = 0))!!
                repo
                    .workItemRepository()
                    .create(WorkItem(title = "List VALIDATION_ERROR Child", parentId = r.id, rootId = r.id, depth = 1))!!
            }
        val result =
            queryRules(composition.toolContext, "operation" to JsonPrimitive("list"), "rootId" to JsonPrimitive(child.id.toString()))
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // Review follow-up: item-mode skill-grammar VALIDATION_ERROR and null item.rootId
    // -----------------------------------------------------------------------

    @Test
    fun `review follow-up - item mode where the resolved skill fails the key grammar returns VALIDATION_ERROR naming the skill`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              bad-skill-type:
                notes:
                  - key: some-note
                    role: queue
                    required: false
                    skill: "Bad Skill With Spaces"
            """.trimIndent()
        val composition = buildComposition(tempDir, globalConfig)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "Skill Grammar Root", depth = 0))!! }
        val item =
            runBlocking {
                repo
                    .workItemRepository()
                    .create(
                        WorkItem(
                            title = "Skill Grammar Item",
                            type = "bad-skill-type",
                            role = Role.QUEUE,
                            parentId = root.id,
                            rootId = root.id,
                            depth = 1,
                        ),
                    )!!
            }
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("some-note"),
            )
        assertFalse(isSuccess(result))
        val error = errorOf(result)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
        assertTrue(
            error["message"]!!.jsonPrimitive.content.contains("Bad Skill With Spaces"),
            "message must name the malformed skill: $error",
        )
    }

    @Test
    fun `review follow-up - item mode on an item with a null rootId returns VALIDATION_ERROR`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        // A depth-0 root item has no parent and no rootId of its own -- item.rootId is null by construction.
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(WorkItem(title = "Null rootId Root", depth = 0))!!
            }
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(root.id.toString()),
                "noteKey" to JsonPrimitive("anything"),
            )
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // S8 -- validateParams: key grammar and get/list XOR shape
    // -----------------------------------------------------------------------

    @Test
    fun `S8 validateParams rejects malformed keys uppercase, slash, leading dot, blank, and over-length`() {
        val badKeys = listOf("A", "a/b", ".x", "", "a".repeat(101))
        badKeys.forEach { badKey ->
            assertFailsWith<ToolValidationException>("expected rejection for key '$badKey'") {
                QueryRulesTool().validateParams(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "rootId" to JsonPrimitive(UUID.randomUUID().toString()),
                        "key" to JsonPrimitive(badKey),
                    ),
                )
            }
        }
    }

    @Test
    fun `S8 probe P3 - a 100-char key passes validateParams, a 101-char key is rejected`() {
        val key100 = "a".repeat(100)
        val key101 = "a".repeat(101)
        // Must not throw.
        QueryRulesTool().validateParams(
            params(
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(UUID.randomUUID().toString()),
                "key" to JsonPrimitive(key100),
            ),
        )
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params(
                    "operation" to JsonPrimitive("get"),
                    "rootId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "key" to JsonPrimitive(key101),
                ),
            )
        }
    }

    @Test
    fun `S8 validateParams rejects get with neither (rootId+key) nor (itemId+noteKey)`() {
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(params("operation" to JsonPrimitive("get")))
        }
    }

    @Test
    fun `S8 validateParams rejects get with both (rootId+key) and (itemId+noteKey) supplied`() {
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params(
                    "operation" to JsonPrimitive("get"),
                    "rootId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "key" to JsonPrimitive("some-key"),
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "noteKey" to JsonPrimitive("task-scope"),
                ),
            )
        }
    }

    @Test
    fun `S8 validateParams rejects get with only rootId or only key, and only itemId or only noteKey`() {
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("get"), "rootId" to JsonPrimitive(UUID.randomUUID().toString())),
            )
        }
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("get"), "key" to JsonPrimitive("some-key")),
            )
        }
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("get"), "itemId" to JsonPrimitive(UUID.randomUUID().toString())),
            )
        }
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("get"), "noteKey" to JsonPrimitive("task-scope")),
            )
        }
    }

    @Test
    fun `S8 validateParams rejects list with a key, itemId, or noteKey present`() {
        val rootId = JsonPrimitive(UUID.randomUUID().toString())
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("list"), "rootId" to rootId, "key" to JsonPrimitive("x")),
            )
        }
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("list"), "rootId" to rootId, "itemId" to JsonPrimitive(UUID.randomUUID().toString())),
            )
        }
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(
                params("operation" to JsonPrimitive("list"), "rootId" to rootId, "noteKey" to JsonPrimitive("x")),
            )
        }
    }

    @Test
    fun `S8 validateParams rejects list without rootId`() {
        assertFailsWith<ToolValidationException> {
            QueryRulesTool().validateParams(params("operation" to JsonPrimitive("list")))
        }
    }

    // -----------------------------------------------------------------------
    // S10 -- item mode: noteKey absent from effective schema, entry with no skill, skill with no doc
    // -----------------------------------------------------------------------

    @Test
    fun `S10 item mode error triage - noteKey not in schema, entry has no skill, and skill names a rule that was never stashed`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              s10-type:
                notes:
                  - key: task-scope
                    role: queue
                    required: true
                  - key: orphan-note
                    role: queue
                    required: false
                    skill: orphan-rule
            """.trimIndent()
        val composition = buildComposition(tempDir, globalConfig)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S10 Root", depth = 0))!! }
        val item =
            runBlocking {
                repo
                    .workItemRepository()
                    .create(
                        WorkItem(
                            title = "S10 item",
                            type = "s10-type",
                            role = Role.QUEUE,
                            parentId = root.id,
                            rootId = root.id,
                            depth = 1,
                        ),
                    )!!
            }

        // (a) noteKey absent from the effective schema entirely.
        val missingNoteResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("does-not-exist"),
            )
        assertFalse(isSuccess(missingNoteResult))
        val missingNoteError = errorOf(missingNoteResult)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, missingNoteError["code"]!!.jsonPrimitive.content)
        assertTrue(missingNoteError["message"]!!.jsonPrimitive.content.contains("does-not-exist"), "message: $missingNoteError")

        // (b) noteKey resolves to a schema entry, but that entry has no `skill` pointer.
        val noSkillResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("task-scope"),
            )
        assertFalse(isSuccess(noSkillResult))
        val noSkillError = errorOf(noSkillResult)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, noSkillError["code"]!!.jsonPrimitive.content)
        assertTrue(noSkillError["message"]!!.jsonPrimitive.content.contains("task-scope"), "message must name the noteKey: $noSkillError")

        // (c) noteKey resolves to a skill, but no rule document exists for that key.
        val orphanSkillResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("orphan-note"),
            )
        assertFalse(isSuccess(orphanSkillResult))
        val orphanSkillError = errorOf(orphanSkillResult)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, orphanSkillError["code"]!!.jsonPrimitive.content)
        assertTrue(
            orphanSkillError["message"]!!.jsonPrimitive.content.contains("orphan-rule"),
            "message must name the resolved rule KEY, not the noteKey: $orphanSkillError",
        )
    }

    @Test
    fun `probe P6 - item mode on a schema-free item returns RESOURCE_NOT_FOUND naming the noteKey`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "P6 Root", depth = 0))!! }
        val item =
            runBlocking {
                repo
                    .workItemRepository()
                    .create(
                        WorkItem(
                            title = "P6 schema-free item",
                            type = "p6-unmapped-type",
                            role = Role.QUEUE,
                            parentId = root.id,
                            rootId = root.id,
                            depth = 1,
                        ),
                    )!!
            }
        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "itemId" to JsonPrimitive(item.id.toString()),
                "noteKey" to JsonPrimitive("anything"),
            )
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // Probe P2 -- hex-prefix rootId on get/list (MCP accepts a 4+ hex prefix per the frozen API)
    // -----------------------------------------------------------------------

    @Test
    fun `probe P2 - a hex prefix of rootId resolves the same root as the full UUID for get and list`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "P2 Root", depth = 0))!! }
        stashDirect(repo, root.id, "rule/hexprefix", "hex prefix body")
        val hexPrefix =
            root.id
                .toString()
                .replace("-", "")
                .substring(0, 8)

        val getResult =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(hexPrefix),
                "key" to JsonPrimitive("hexprefix"),
            )
        assertTrue(isSuccess(getResult), "hex-prefix rootId must resolve on get: $getResult")
        assertEquals("hex prefix body", dataOf(getResult)["body"]!!.jsonPrimitive.content)

        val listResult =
            queryRules(composition.toolContext, "operation" to JsonPrimitive("list"), "rootId" to JsonPrimitive(hexPrefix))
        assertTrue(isSuccess(listResult), "hex-prefix rootId must resolve on list: $listResult")
        assertEquals(listOf("hexprefix"), dataOf(listResult)["rules"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content })
    }

    // -----------------------------------------------------------------------
    // Probe P4 -- an ADOPTED rule doc is still served
    // -----------------------------------------------------------------------

    @Test
    fun `probe P4 - an ADOPTED rule document is still served identically to before adoption`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "P4 Root", depth = 0))!! }
        stashViaTool(composition.toolContext, root.id, "rule/adoptable", "Adoptable rule body.")
        val before =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("adoptable"),
            )
        val adopter = runBlocking { repo.workItemRepository().create(WorkItem(title = "Adopter"))!! }
        runBlocking { repo.planDocumentRepository().markAdopted(root.id, "rule/adoptable", adopter.id) }

        val after =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("adoptable"),
            )
        assertTrue(isSuccess(after), "expected success after adoption: $after")
        assertEquals(dataOf(before)["body"], dataOf(after)["body"], "adoption status must not affect what get() serves")
        assertEquals(dataOf(before)["rulesVersion"], dataOf(after)["rulesVersion"])
    }

    // -----------------------------------------------------------------------
    // Probe P7 -- two roots, same key, no cross-root leak
    // -----------------------------------------------------------------------

    @Test
    fun `probe P7 - two roots with the same key each serve their own body, no cross-root leak`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root1 = runBlocking { repo.workItemRepository().create(WorkItem(title = "P7 Root 1", depth = 0))!! }
        val root2 = runBlocking { repo.workItemRepository().create(WorkItem(title = "P7 Root 2", depth = 0))!! }
        stashDirect(repo, root1.id, "rule/shared-key", "Root 1's body")
        stashDirect(repo, root2.id, "rule/shared-key", "Root 2's body")

        val result1 =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root1.id.toString()),
                "key" to JsonPrimitive("shared-key"),
            )
        val result2 =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root2.id.toString()),
                "key" to JsonPrimitive("shared-key"),
            )
        assertEquals("Root 1's body", dataOf(result1)["body"]!!.jsonPrimitive.content)
        assertEquals("Root 2's body", dataOf(result2)["body"]!!.jsonPrimitive.content)
        assertFalse(
            dataOf(result1)["rulesVersion"] == dataOf(result2)["rulesVersion"],
            "different bodies at different roots must not collide on rulesVersion",
        )
    }

    // -----------------------------------------------------------------------
    // Probe P8 -- empty body served as "", rulesVersion = SHA-256 of the empty string
    // -----------------------------------------------------------------------

    @Test
    fun `probe P8 - an empty rule body is served as an empty string with rulesVersion equal to SHA-256 of the empty string`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "P8 Root", depth = 0))!! }
        stashDirect(repo, root.id, "rule/empty", "")

        val result =
            queryRules(
                composition.toolContext,
                "operation" to JsonPrimitive("get"),
                "rootId" to JsonPrimitive(root.id.toString()),
                "key" to JsonPrimitive("empty"),
            )
        assertTrue(isSuccess(result), "expected success: $result")
        val data = dataOf(result)
        assertEquals("", data["body"]!!.jsonPrimitive.content)
        assertEquals(sha256Hex(ByteArray(0)), data["rulesVersion"]!!.jsonPrimitive.content)
    }
}

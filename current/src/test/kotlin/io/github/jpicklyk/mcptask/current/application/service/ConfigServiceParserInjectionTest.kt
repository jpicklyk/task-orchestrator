package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteConfigStore
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenarios S10 and S11 map to this file per the test-plan's file list. Oracle for S10:
 * `task-scope`'s Part B mapping in `parseAndValidateYaml`/`push` — a [ConfigDocumentParser.Outcome.Failed]
 * maps to `ConfigPushResult.ParseError(detail)` carrying the SAME detail string, and a
 * [ConfigDocumentParser.Outcome.Parsed] maps to `ConfigPushResult.Success` carrying the
 * parsed document's `warnings` as `schemaWarnings` and the raw root map's keys as `ignoredSections`
 * — confirmed verbatim in the declarations' `parseAndValidateYaml`/`push` excerpt. Oracle for S11:
 * the same Failed->ParseError mapping, surfaced through the `config` tool's push op
 * (a `config_invalid` element error), with an INJECTED fake parser rather than real YAML.
 *
 * NEW-SURFACE (both scenarios): the [ConfigDocumentParser] injection point on [ConfigService]
 * is introduced by this feature. No source revert can yield behavioral
 * red; per the frozen plan, the substitute is "keep param, inline old parse" (an orchestrator-run
 * mutation).
 */
class ConfigServiceParserInjectionTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var databaseManager: DatabaseManager
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SqliteConfigStore
    private lateinit var repositoryProvider: RepositoryProvider
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            databaseManager = db.databaseManager

            workItemRepository = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
            projectConfigRepository = db.repositoryProvider().configStore() as SqliteConfigStore

            val provider = mockk<RepositoryProvider>(relaxed = true)
            every { provider.workItemRepository() } returns workItemRepository
            every { provider.configStore() } returns projectConfigRepository
            repositoryProvider = provider

            val root = workItemRepository.create(WorkItem(title = "PCPS Root", type = "project"))
            rootId = root.id
        }

    private class FakeConfigDocumentParser(
        private val outcome: ConfigDocumentParser.Outcome
    ) : ConfigDocumentParser {
        override fun parse(
            yaml: String,
            warnOnMissingSchemas: Boolean,
            mode: ParseMode,
        ): ConfigDocumentParser.Outcome = outcome
    }

    // ──────────────────────────────────────────────
    // S10 — ConfigService(p, fakeParser)
    // ──────────────────────────────────────────────

    @Test
    fun `S10 - a Failed outcome maps to ParseError carrying the same detail`(): Unit =
        runBlocking {
            val service =
                ConfigService(
                    repositoryProvider,
                    FakeConfigDocumentParser(ConfigDocumentParser.Outcome.Failed("boom")),
                    db.unitOfWork(),
                    validator = ConfigValidator(),
                )

            val result = service.push(rootId, "irrelevant: yaml")

            val parseError = assertIs<ConfigPushResult.ParseError>(result)
            assertEquals("boom", parseError.detail)
        }

    @Test
    fun `S10 - a Parsed outcome maps to Success carrying the document warnings as schemaWarnings`(): Unit =
        runBlocking {
            val doc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    warnings = listOf("w1"),
                    warningViolations = listOf(ConfigViolation("/x", "w1")),
                )
            val outcome = ConfigDocumentParser.Outcome.Parsed(doc, rawRoot = mapOf("x" to 1))
            val service =
                ConfigService(repositoryProvider, FakeConfigDocumentParser(outcome), db.unitOfWork(), validator = ConfigValidator())

            val result = service.push(rootId, "irrelevant: yaml")

            val success = assertIs<ConfigPushResult.Success>(result)
            assertEquals(listOf("w1"), success.schemaWarnings)
        }

    // ──────────────────────────────────────────────
    // S11 — `config` push over a service with a fake Failed("boom") parser
    // ──────────────────────────────────────────────

    @Test
    fun `S11 - config push surfaces the injected parser's Failed detail in the config_invalid error`(): Unit =
        runBlocking {
            val service =
                ConfigService(
                    repositoryProvider,
                    FakeConfigDocumentParser(ConfigDocumentParser.Outcome.Failed("boom")),
                    db.unitOfWork(),
                    validator = ConfigValidator(),
                )
            val context = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork(), configService = service)

            val body =
                context.callTool(
                    "config",
                    buildJsonObject {
                        put(
                            "ops",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("op", "push")
                                        put("rootId", rootId.toString())
                                        put("configYaml", "irrelevant: yaml")
                                    }
                                )
                            }
                        )
                    }
                )

            val error = body.errorAt(0)
            assertEquals("config_invalid", error["code"]!!.jsonPrimitive.content)
            val reasons = error["detail"]!!.jsonObject["errors"]!!.jsonArray.map { it.jsonObject["reason"]!!.jsonPrimitive.content }
            assertTrue(reasons.any { it.contains("boom") }, "expected the injected parser's detail in the errors: $reasons")
        }
}

package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the optional `credentialRefs` audit field on `advance_item` transitions (T1).
 *
 * Covers: bare-string coercion to a one-element list, array-of-strings acceptance, the >8-entries
 * rejection, the charset/pattern rejection, and the absent-field no-behavior-change path — plus
 * verifying the resolved list actually reaches [RoleTransitionRepository.create] via
 * [RoleTransitionHandler]/[AdvanceService].
 *
 * Mirrors the mock setup in [AdvanceItemToolTest].
 */
class AdvanceItemToolCredentialRefTest {
    private lateinit var tool: AdvanceItemTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    @BeforeEach
    fun setUp() {
        tool = AdvanceItemTool()
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()

        repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        val defaultNoteRepo = mockk<NoteRepository>()
        coEvery { defaultNoteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        coEvery { defaultNoteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())
        every { repoProvider.noteRepository() } returns defaultNoteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }

        context = ToolExecutionContext(repoProvider)
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE
    ): WorkItem = WorkItem(id = id, title = "Test Item", role = role)

    private fun stubHappyPath(itemId: UUID) {
        val item = makeItem(id = itemId, role = Role.QUEUE)
        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()
    }

    private fun extractData(result: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonObject {
        val obj = result.jsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response")
        return obj["data"]!!.jsonObject
    }

    // ──────────────────────────────────────────────
    // Bare-string coercion
    // ──────────────────────────────────────────────

    @Test
    fun `bare string credentialRefs coerces to a one-element list and reaches the repository`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            stubHappyPath(itemId)
            val transitionSlot = slot<RoleTransition>()
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("trigger", JsonPrimitive("start"))
                                    put("credentialRefs", JsonPrimitive("vault/prod-db-password"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context)
            val data = extractData(result)
            val r = (data["results"] as JsonArray)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)

            assertEquals(listOf("vault/prod-db-password"), transitionSlot.captured.consumedCredentials)
        }

    // ──────────────────────────────────────────────
    // Array of strings accepted
    // ──────────────────────────────────────────────

    @Test
    fun `array credentialRefs is accepted and reaches the repository in order`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            stubHappyPath(itemId)
            val transitionSlot = slot<RoleTransition>()
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("trigger", JsonPrimitive("start"))
                                    put(
                                        "credentialRefs",
                                        buildJsonArray {
                                            add(JsonPrimitive("vault/prod-db-password"))
                                            add(JsonPrimitive("github-pat-ci"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context)
            val data = extractData(result)
            val r = (data["results"] as JsonArray)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)

            assertEquals(
                listOf("vault/prod-db-password", "github-pat-ci"),
                transitionSlot.captured.consumedCredentials
            )
        }

    // ──────────────────────────────────────────────
    // Absent field -> no behavior change
    // ──────────────────────────────────────────────

    @Test
    fun `absent credentialRefs results in empty consumedCredentials`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            stubHappyPath(itemId)
            val transitionSlot = slot<RoleTransition>()
            coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId.toString()))
                                    put("trigger", JsonPrimitive("start"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context)
            val data = extractData(result)
            val r = (data["results"] as JsonArray)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)

            assertTrue(transitionSlot.captured.consumedCredentials.isEmpty())
        }

    // ──────────────────────────────────────────────
    // Validation failures (validateParams rejects before anything is persisted)
    // ──────────────────────────────────────────────

    @Test
    fun `more than 8 credentialRefs entries is rejected`() {
        val itemId = UUID.randomUUID()
        val params =
            buildJsonObject {
                put(
                    "transitions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId.toString()))
                                put("trigger", JsonPrimitive("start"))
                                put(
                                    "credentialRefs",
                                    buildJsonArray {
                                        repeat(9) { i -> add(JsonPrimitive("cred-$i")) }
                                    }
                                )
                            }
                        )
                    }
                )
            }

        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(params) }
        assertTrue(ex.message!!.contains("credentialRefs"), "Expected error to mention credentialRefs: ${ex.message}")
    }

    @Test
    fun `a credentialRefs entry with an uppercase character is rejected`() {
        val itemId = UUID.randomUUID()
        val params =
            buildJsonObject {
                put(
                    "transitions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId.toString()))
                                put("trigger", JsonPrimitive("start"))
                                put("credentialRefs", JsonPrimitive("Vault:Prod-DB-Password"))
                            }
                        )
                    }
                )
            }

        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(params) }
        assertTrue(
            ex.message!!.contains("credentialRefs[0]"),
            "Expected error to name the offending index: ${ex.message}"
        )
    }

    @Test
    fun `a credentialRefs entry shaped like a raw token is rejected`() {
        val itemId = UUID.randomUUID()
        val params =
            buildJsonObject {
                put(
                    "transitions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId.toString()))
                                put("trigger", JsonPrimitive("start"))
                                // Raw-looking secret material (uppercase/mixed-case token) must be
                                // rejected by the pattern — callers must pass an opaque label instead.
                                put("credentialRefs", JsonPrimitive("ghp_ABCDEF1234567890"))
                            }
                        )
                    }
                )
            }

        assertFailsWith<ToolValidationException> { tool.validateParams(params) }
    }

    @Test
    fun `a non-string non-array credentialRefs value is rejected`() {
        val itemId = UUID.randomUUID()
        val params =
            buildJsonObject {
                put(
                    "transitions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId.toString()))
                                put("trigger", JsonPrimitive("start"))
                                put("credentialRefs", JsonPrimitive(42))
                            }
                        )
                    }
                )
            }

        val ex = assertFailsWith<ToolValidationException> { tool.validateParams(params) }
        assertTrue(
            ex.message!!.contains("string or an array of strings"),
            "Expected a shape error: ${ex.message}"
        )
    }
}

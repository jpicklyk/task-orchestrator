package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored (item 6adda27b, seat test-author) create_work_tree note-policy scenarios against the
 * frozen queue-phase `task-scope` ("create_work_tree: validateParams accepts any role case; prepare per note
 * before the executor transaction; any rejection (cap, schema-role, maxLength reject) fails the WHOLE call with
 * VALIDATION_ERROR, zero items, doc not adopted; warn adds `warning` to that note's entry in the notes
 * response array") and `test-plan`: S4, S6, S9, S10, S15, S16 (work-tree half), S21 plus the CRLF / casing probes.
 *
 * Call order mirrors the production adapter: `validateParams` first, then `execute`. Response and atomicity
 * conventions (data.root.id, data.notes[] entries with itemRef/key/role, `error.code` VALIDATION_ERROR,
 * "nothing persisted" = absent from findByFilters, plan document stays PENDING) are the ones already used by
 * CreateWorkTreeExecuteCharacterizationTest / CreateWorkTreeToolIntegrationTest.
 *
 * Fixture: schema key `lim` (role work, maxLength 10) for items tagged `lim-type`; global `note_limits` mode
 * comes from the schema service object.
 */
class CreateWorkTreeNotePolicyTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val tool = CreateWorkTreeTool()
    private val planBody = "# Overview\nO.\n# Task 1\nTask 1 detail text."

    private fun context(globalMode: String): ToolExecutionContext {
        val schemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                    if (tags.contains("lim-type")) listOf(NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10)) else null

                override fun getNoteLimitsMode(): String = globalMode
            }
        return ToolExecutionContext(db.repositoryProvider(), schemaService, unitOfWork = db.unitOfWork())
    }

    private suspend fun run(
        ctx: ToolExecutionContext,
        params: JsonObject
    ): JsonObject {
        tool.validateParams(params)
        return tool.execute(params, ctx) as JsonObject
    }

    private fun child(
        ref: String,
        title: String,
        tags: String? = null
    ) = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("title", JsonPrimitive(title))
        if (tags != null) put("tags", JsonPrimitive(tags))
    }

    private fun explicitNote(
        itemRef: String,
        key: String,
        role: String,
        body: String
    ) = buildJsonObject {
        put("itemRef", JsonPrimitive(itemRef))
        put("key", JsonPrimitive(key))
        put("role", JsonPrimitive(role))
        put("body", JsonPrimitive(body))
    }

    private fun treeParams(
        rootTitle: String,
        children: List<JsonObject>,
        notes: List<JsonObject>
    ) = buildJsonObject {
        put("root", buildJsonObject { put("title", JsonPrimitive(rootTitle)) })
        put("children", JsonArray(children))
        put("notes", JsonArray(notes))
    }

    private fun JsonObject.data() = this["data"]!!.jsonObject

    private fun JsonObject.error() = this["error"]!!.jsonObject

    private suspend fun titlesInDb(): List<String> =
        db.repositoryProvider().workItemRepository().findByFilters(limit = 500).items.map {
            it.title
        }

    private fun childId(result: JsonObject): UUID =
        UUID.fromString(
            result
                .data()["children"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive.content
        )

    private fun rootId(result: JsonObject): UUID =
        UUID.fromString(
            result
                .data()["root"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content
        )

    private suspend fun stored(
        itemId: UUID,
        key: String
    ) = db.repositoryProvider().noteRepository().findByItemIdAndKey(itemId, key)

    private suspend fun projectRootWithPlan(): UUID {
        val repos = db.repositoryProvider()
        val projectRoot = repos.workItemRepository().create(WorkItem(title = "Project", type = "project"))
        repos.planDocumentRepository().stash(projectRoot.id, "my-plan", planBody)
        return projectRoot.id
    }

    // ---------------------------------------------------------------------------------------------
    // S4 normalization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 an off-schema explicit note with role Review and CRLF is stored as review and LF`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val params = treeParams("S4 Root", listOf(child("c1", "S4 C1")), listOf(explicitNote("c1", "free", "Review", "a\r\nb")))

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val note = assertNotNull(stored(childId(result), "free"))
            assertEquals("review", note.role)
            assertEquals("a\nb", note.body)
            val entry =
                result
                    .data()["notes"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("review", entry["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S4 probe casings and a lone CR on the root item`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val params =
                treeParams(
                    "S4 Probe Root",
                    emptyList(),
                    listOf(
                        explicitNote("root", "k1", "WORK", "a\rb"),
                        explicitNote("root", "k2", "wORK", "x\ny"),
                        explicitNote("root", "k3", "QUEUE", "")
                    )
                )

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val root = rootId(result)
            assertEquals("work" to "a\rb", stored(root, "k1")!!.let { it.role to it.body })
            assertEquals("work" to "x\ny", stored(root, "k2")!!.let { it.role to it.body })
            assertEquals("queue" to "", stored(root, "k3")!!.let { it.role to it.body })
        }

    @Test
    fun `S4 a schema key given as WORK satisfies the schema role`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val params =
                treeParams(
                    "S4 Schema Root",
                    listOf(child("c1", "S4 Schema C1", tags = "lim-type")),
                    listOf(explicitNote("c1", "lim", "WORK", "ok"))
                )

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("work", stored(childId(result), "lim")!!.role)
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S21 warn
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a lim body of 11 chars in warn mode succeeds and only that notes entry carries a warning`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val params =
                treeParams(
                    "S6 Root",
                    listOf(child("c1", "S6 C1", tags = "lim-type")),
                    listOf(explicitNote("c1", "lim", "work", "x".repeat(11)), explicitNote("c1", "other", "queue", "x".repeat(11)))
                )

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val entries = result.data()["notes"]!!.jsonArray.map { it.jsonObject }
            val limEntry = entries.single { it["key"]!!.jsonPrimitive.content == "lim" }
            val otherEntry = entries.single { it["key"]!!.jsonPrimitive.content == "other" }
            val warning = assertNotNull(limEntry["warning"], "lim entry must carry a warning: $limEntry").jsonPrimitive.content
            assertTrue("lim" in warning && "10" in warning, "warning: $warning")
            assertNull(otherEntry["warning"], "an off-schema note has no limit and no warning: $otherEntry")
            assertEquals("x".repeat(11), stored(childId(result), "lim")!!.body)
        }

    @Test
    fun `S21 a lim body of 12 raw chars with two CRLF is 10 normalized chars and gets no warning`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val params =
                treeParams(
                    "S21 Root",
                    listOf(child("c1", "S21 C1", tags = "lim-type")),
                    listOf(explicitNote("c1", "lim", "work", "ab\r\ncdef\r\ngh"))
                )

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertNull(
                result
                    .data()["notes"]!!
                    .jsonArray
                    .single()
                    .jsonObject["warning"]
            )
            assertEquals("ab\ncdef\ngh", stored(childId(result), "lim")!!.body)
        }

    // ---------------------------------------------------------------------------------------------
    // S9 / S10 maxLength reject fails the whole call
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 a lim body of 11 chars in reject mode fails the whole call with VALIDATION_ERROR and persists no item`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val params =
                treeParams(
                    "S9 Root",
                    listOf(child("c1", "S9 C1", tags = "lim-type"), child("c2", "S9 C2")),
                    listOf(explicitNote("c2", "fine", "work", "ok"), explicitNote("c1", "lim", "work", "x".repeat(11)))
                )

            val result = run(ctx, params)

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("VALIDATION_ERROR", result.error()["code"]!!.jsonPrimitive.content)
            val titles = titlesInDb()
            assertTrue(titles.none { it.startsWith("S9 ") }, "root and children must not be persisted: $titles")
        }

    @Test
    fun `S9 control - the same call with a 10 char lim body succeeds under reject`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val params =
                treeParams(
                    "S9c Root",
                    listOf(child("c1", "S9c C1", tags = "lim-type")),
                    listOf(explicitNote("c1", "lim", "work", "x".repeat(10)))
                )

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
        }

    @Test
    fun `S10 a noteAnchors slice longer than maxLength in reject mode fails the whole call and the plan document stays PENDING`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val projectRoot = projectRootWithPlan()
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S10 Root")) })
                    put("parentId", JsonPrimitive(projectRoot.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("S10 C1"))
                                    put("tags", JsonPrimitive("lim-type"))
                                    put(
                                        "noteAnchors",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("noteKey", JsonPrimitive("lim"))
                                                    put("role", JsonPrimitive("work"))
                                                    put("anchor", JsonPrimitive("task-1"))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = run(ctx, params)

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("VALIDATION_ERROR", result.error()["code"]!!.jsonPrimitive.content)
            val titles = titlesInDb()
            assertTrue(titles.none { it.startsWith("S10 ") }, "nothing may be persisted: $titles")
            val doc = db.repositoryProvider().planDocumentRepository().get(projectRoot, "my-plan")
            assertEquals(PlanDocumentStatus.PENDING, assertNotNull(doc).status)
        }

    @Test
    fun `S10 control - the same anchor in warn mode succeeds with a warning and adopts the document`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val projectRoot = projectRootWithPlan()
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S10c Root")) })
                    put("parentId", JsonPrimitive(projectRoot.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("S10c C1"))
                                    put("tags", JsonPrimitive("lim-type"))
                                    put(
                                        "noteAnchors",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("noteKey", JsonPrimitive("lim"))
                                                    put("role", JsonPrimitive("work"))
                                                    put("anchor", JsonPrimitive("task-1"))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertNotNull(
                result
                    .data()["notes"]!!
                    .jsonArray
                    .single()
                    .jsonObject["warning"]
            )
            val doc = db.repositoryProvider().planDocumentRepository().get(projectRoot, "my-plan")
            assertEquals(PlanDocumentStatus.ADOPTED, assertNotNull(doc).status)
        }

    // ---------------------------------------------------------------------------------------------
    // S15 byte cap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 an explicit note body of 65537 bytes fails the whole call with VALIDATION_ERROR and zero items`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val params = treeParams("S15 Root", listOf(child("c1", "S15 C1")), listOf(explicitNote("c1", "big", "work", "a".repeat(65537))))

            val result = run(ctx, params)

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("VALIDATION_ERROR", result.error()["code"]!!.jsonPrimitive.content)
            val titles = titlesInDb()
            assertTrue(titles.none { it.startsWith("S15 ") }, "nothing may be persisted: $titles")
        }

    @Test
    fun `S15 probe 65536 bytes passes and a multibyte body of 65537 bytes fails`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val euro = "\u20AC"

            val ok =
                run(
                    ctx,
                    treeParams(
                        "S15a Root",
                        listOf(child("c1", "S15a C1")),
                        listOf(
                            explicitNote(
                                "c1",
                                "fits",
                                "work",
                                euro.repeat(21845) + "a"
                            )
                        )
                    )
                )
            assertTrue(ok["success"]!!.jsonPrimitive.boolean, "65536 bytes: $ok")

            val over =
                run(
                    ctx,
                    treeParams(
                        "S15b Root",
                        listOf(child("c1", "S15b C1")),
                        listOf(
                            explicitNote(
                                "c1",
                                "over",
                                "work",
                                euro.repeat(21845) + "ab"
                            )
                        )
                    )
                )
            assertFalse(over["success"]!!.jsonPrimitive.boolean, "65537 bytes: $over")
            assertEquals("VALIDATION_ERROR", over.error()["code"]!!.jsonPrimitive.content, "65537 bytes: $over")
            assertTrue(titlesInDb().none { it.startsWith("S15b ") })
        }

    // ---------------------------------------------------------------------------------------------
    // S16 invalid roles
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // noteAnchors slices get the same write policy as inline notes (oracle: inline siblings S4, S15, S9/S11)
    // ---------------------------------------------------------------------------------------------

    private suspend fun anchorParams(
        title: String,
        planText: String,
        tags: String?,
        noteKey: String,
        role: String
    ): Pair<JsonObject, UUID> {
        val repos = db.repositoryProvider()
        val projectRoot = repos.workItemRepository().create(WorkItem(title = "Project $title", type = "project"))
        repos.planDocumentRepository().stash(projectRoot.id, "anchor-plan", planText)
        val params =
            buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive(title)) })
                put("parentId", JsonPrimitive(projectRoot.id.toString()))
                put("docRef", buildJsonObject { put("slug", JsonPrimitive("anchor-plan")) })
                put(
                    "children",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("ref", JsonPrimitive("c1"))
                                put("title", JsonPrimitive("$title C1"))
                                if (tags != null) put("tags", JsonPrimitive(tags))
                                put(
                                    "noteAnchors",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("noteKey", JsonPrimitive(noteKey))
                                                put("role", JsonPrimitive(role))
                                                put("anchor", JsonPrimitive("task-1"))
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        return params to projectRoot.id
    }

    private suspend fun planStatus(projectRoot: UUID) =
        assertNotNull(db.repositoryProvider().planDocumentRepository().get(projectRoot, "anchor-plan")).status

    @Test
    fun `anchored slice with role WORK is stored with role work like an inline WORK note`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val (params, projectRoot) = anchorParams("AnchorCase", planBody, null, "free", "WORK")

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("work", assertNotNull(stored(childId(result), "free")).role)
            val entry =
                result
                    .data()["notes"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("work", entry["role"]!!.jsonPrimitive.content)
            assertEquals(PlanDocumentStatus.ADOPTED, planStatus(projectRoot))
        }

    @Test
    fun `anchored slice from a CRLF document is stored with LF only like an inline CRLF note`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val crlfPlan = "# Overview\r\nO.\r\n# Task 1\r\nline one\r\nline two\r\n"
            val (params, _) = anchorParams("AnchorCrlf", crlfPlan, null, "free", "work")

            val result = run(ctx, params)

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val body = assertNotNull(stored(childId(result), "free")).body
            assertFalse(body.contains('\r'), "stored body must contain no CR: ${body.replace("\r", "\\r")}")
            assertTrue(body.contains("line one\nline two"), "stored body: $body")
        }

    @Test
    fun `anchored slice over the 64 KiB cap fails the whole call and the plan document stays PENDING`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val bigPlan = "# Overview\nO.\n# Task 1\n" + "a".repeat(65537)
            val (params, projectRoot) = anchorParams("AnchorBig", bigPlan, null, "big", "work")

            val result = run(ctx, params)

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("VALIDATION_ERROR", result.error()["code"]!!.jsonPrimitive.content)
            assertTrue(titlesInDb().none { it.startsWith("AnchorBig") }, "nothing may be persisted")
            assertEquals(PlanDocumentStatus.PENDING, planStatus(projectRoot))
        }

    @Test
    fun `anchored slice whose role disagrees with the schema role fails the whole call and the plan document stays PENDING`(): Unit =
        runBlocking {
            val ctx = context("warn")
            val (params, projectRoot) = anchorParams("AnchorRole", planBody, "lim-type", "lim", "queue")

            val result = run(ctx, params)

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals("VALIDATION_ERROR", result.error()["code"]!!.jsonPrimitive.content)
            assertTrue(titlesInDb().none { it.startsWith("AnchorRole") }, "nothing may be persisted")
            assertEquals(PlanDocumentStatus.PENDING, planStatus(projectRoot))
        }

    @Test
    fun `S16 roles done and padded work are rejected by validateParams as an invalid role and persist no item`(): Unit =
        runBlocking {
            for ((i, bad) in listOf("done", " work", "work ", "").withIndex()) {
                val title = "S16-$i Root"
                val params = treeParams(title, listOf(child("c1", "S16-$i C1")), listOf(explicitNote("c1", "k", bad, "b")))

                val ex = assertFailsWith<ToolValidationException>("role '$bad'") { tool.validateParams(params) }

                assertTrue(ex.message!!.contains("role"), "role '$bad': ${ex.message}")
                assertTrue(titlesInDb().none { it == title }, "role '$bad': nothing may be persisted")
            }
        }
}

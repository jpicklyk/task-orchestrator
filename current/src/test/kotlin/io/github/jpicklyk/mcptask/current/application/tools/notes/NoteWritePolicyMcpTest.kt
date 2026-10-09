package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored (item 6adda27b, seat test-author) MCP-surface scenarios for the note write policy,
 * against the frozen queue-phase `task-scope` and `test-plan`: S1, S2, S11, S13, S16 (MCP half), S18, S21
 * plus the casing / padding / lone-CR / multibyte-cap / key-case / duplicate-key / replay probes.
 *
 * Call order mirrors the production adapter (`validateParams` first, then `execute`), so a validateParams
 * that still pre-rejects a non-lowercase role fails here. Per-note failures keep the 3.x entry shape in
 * `data.failures` (`index`, `error`, plus `code` for the structured codes), as ManageNotesToolTest already
 * exercises for NOTE_BODY_TOO_LONG.
 *
 * Fixture: schema key `lim` (role work, maxLength 10) for items tagged `lim-type`; `note_limits` mode comes
 * from the global service (default warn) and, where stated, from a per-root row in the project config table.
 */
class NoteWritePolicyMcpTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val tool = ManageNotesTool()

    private fun schema(globalMode: String): NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.contains("lim-type")) listOf(NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10)) else null

            override fun getNoteLimitsMode(): String = globalMode
        }

    private fun context(
        globalMode: String = "warn",
        perRoot: PerRootConfigSource? = null
    ) = ToolExecutionContext(db.repositoryProvider(), schema(globalMode), perRootConfigService = perRoot, unitOfWork = db.unitOfWork())

    private suspend fun item(
        tags: String? = "lim-type",
        rootId: UUID? = null
    ): UUID =
        db
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = "host", tags = tags, rootId = rootId))
            .id

    private fun noteObj(
        itemId: UUID,
        key: String,
        role: String,
        body: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("key", JsonPrimitive(key))
            put("role", JsonPrimitive(role))
            put("body", JsonPrimitive(body))
        }

    private fun upsertParams(
        vararg notes: JsonObject,
        extra: Map<String, JsonElement> = emptyMap()
    ): JsonObject = JsonObject(mapOf("operation" to JsonPrimitive("upsert"), "notes" to JsonArray(notes.toList())) + extra)

    private suspend fun run(
        ctx: ToolExecutionContext,
        params: JsonObject
    ): JsonObject {
        tool.validateParams(params)
        return tool.execute(params, ctx) as JsonObject
    }

    private fun JsonObject.data() = this["data"]!!.jsonObject

    private suspend fun stored(
        itemId: UUID,
        key: String
    ) = db.repositoryProvider().noteRepository().findByItemIdAndKey(itemId, key)

    // ---------------------------------------------------------------------------------------------
    // S1 / S2 normalization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 role WORK is stored as work and a role=work query finds it`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)

            val result = run(ctx, upsertParams(noteObj(itemId, "k", "WORK", "body")))

            assertEquals(1, result.data()["upserted"]!!.jsonPrimitive.int, "result: $result")
            assertEquals(
                "work",
                result
                    .data()["notes"]!!
                    .jsonArray[0]
                    .jsonObject["role"]!!
                    .jsonPrimitive.content
            )
            assertEquals("work", stored(itemId, "k")!!.role)

            val listed =
                QueryNotesTool().execute(
                    JsonObject(
                        mapOf(
                            "operation" to JsonPrimitive("list"),
                            "itemId" to JsonPrimitive(itemId.toString()),
                            "role" to JsonPrimitive("work")
                        )
                    ),
                    ctx
                ) as JsonObject
            assertEquals(1, listed.data()["total"]!!.jsonPrimitive.int, "role=work must find the note written as WORK")
        }

    @Test
    fun `S1 probe every casing of the three roles is normalized`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)
            val cases = listOf("WORK" to "work", "Work" to "work", "wORK" to "work", "QUEUE" to "queue", "Review" to "review")

            for ((i, c) in cases.withIndex()) {
                run(ctx, upsertParams(noteObj(itemId, "k$i", c.first, "b")))
                assertEquals(c.second, stored(itemId, "k$i")!!.role, "given ${c.first}")
            }
        }

    @Test
    fun `S2 inline CRLF is stored as LF and a lone CR survives`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)

            run(
                ctx,
                upsertParams(
                    noteObj(itemId, "crlf", "work", "a\r\nb\r\n"),
                    noteObj(itemId, "lone", "work", "a\rb"),
                    noteObj(itemId, "lf", "work", "a\nb")
                )
            )

            assertEquals("a\nb\n", stored(itemId, "crlf")!!.body)
            assertEquals("a\rb", stored(itemId, "lone")!!.body)
            assertEquals("a\nb", stored(itemId, "lf")!!.body)
        }

    // ---------------------------------------------------------------------------------------------
    // S11 schema role
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 a schema key with the wrong role fails that note alone and an off-schema sibling is stored`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item()

            val result = run(ctx, upsertParams(noteObj(itemId, "lim", "queue", "ok"), noteObj(itemId, "other", "queue", "ok")))

            val data = result.data()
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int, "result: $result")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0].jsonObject
            assertEquals(0, failure["index"]!!.jsonPrimitive.int)
            val message = failure["error"]!!.jsonPrimitive.content
            assertTrue("lim" in message && "work" in message, "the failure names the key and the expected role: $message")
            assertNull(stored(itemId, "lim"), "the violating note must not be stored")
            assertEquals("queue", stored(itemId, "other")!!.role)
        }

    @Test
    fun `S11 control - the schema key with its own role is accepted, including when given as WORK`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item()

            val result = run(ctx, upsertParams(noteObj(itemId, "lim", "WORK", "ok")))

            assertEquals(1, result.data()["upserted"]!!.jsonPrimitive.int, "result: $result")
            assertEquals("work", stored(itemId, "lim")!!.role)
        }

    @Test
    fun `S11 probe key LIM is off-schema so it takes any role and has no length limit`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val itemId = item()

            val result = run(ctx, upsertParams(noteObj(itemId, "LIM", "queue", "x".repeat(50))))

            assertEquals(1, result.data()["upserted"]!!.jsonPrimitive.int, "result: $result")
            assertEquals("queue", stored(itemId, "LIM")!!.role)
        }

    // ---------------------------------------------------------------------------------------------
    // S13 byte cap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 an inline body of 65537 bytes fails with NOTE_BODY_TOO_LARGE and 65536 is stored`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)

            val tooBig = run(ctx, upsertParams(noteObj(itemId, "big", "work", "a".repeat(65537))))
            assertEquals(0, tooBig.data()["upserted"]!!.jsonPrimitive.int, "result: $tooBig")
            assertEquals(1, tooBig.data()["failed"]!!.jsonPrimitive.int)
            assertEquals(
                "NOTE_BODY_TOO_LARGE",
                tooBig
                    .data()["failures"]!!
                    .jsonArray[0]
                    .jsonObject["code"]!!
                    .jsonPrimitive.content
            )
            assertNull(stored(itemId, "big"))

            val atCap = run(ctx, upsertParams(noteObj(itemId, "fits", "work", "a".repeat(65536))))
            assertEquals(1, atCap.data()["upserted"]!!.jsonPrimitive.int, "result: $atCap")
            assertEquals(65536, stored(itemId, "fits")!!.body.length)
        }

    @Test
    fun `S13 probe the cap counts UTF-8 bytes - 65536 multibyte bytes pass and 65537 fail`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)
            val euro = "\u20AC"

            val atCap = run(ctx, upsertParams(noteObj(itemId, "at", "work", euro.repeat(21845) + "a")))
            assertEquals(1, atCap.data()["upserted"]!!.jsonPrimitive.int, "65536 bytes: $atCap")

            val over = run(ctx, upsertParams(noteObj(itemId, "over", "work", euro.repeat(21845) + "ab")))
            assertEquals(1, over.data()["failed"]!!.jsonPrimitive.int, "65537 bytes: $over")
            assertEquals(
                "NOTE_BODY_TOO_LARGE",
                over
                    .data()["failures"]!!
                    .jsonArray[0]
                    .jsonObject["code"]!!
                    .jsonPrimitive.content
            )
            assertNull(stored(itemId, "over"))
        }

    // ---------------------------------------------------------------------------------------------
    // S16 invalid roles (MCP half)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 roles done and padded work are rejected per note and nothing is stored`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)

            for ((i, bad) in listOf("done", " work", "work ", "").withIndex()) {
                val result = run(ctx, upsertParams(noteObj(itemId, "bad$i", bad, "b")))
                assertEquals(0, result.data()["upserted"]!!.jsonPrimitive.int, "role '$bad': $result")
                assertEquals(1, result.data()["failed"]!!.jsonPrimitive.int, "role '$bad'")
                assertNull(stored(itemId, "bad$i"), "role '$bad' must not be stored")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S21 / maxLength after normalization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S21 a lim body of 12 raw chars with two CRLF is 10 normalized chars and gets no warning even under reject`(): Unit =
        runBlocking {
            val ctx = context("reject")
            val itemId = item()

            val result = run(ctx, upsertParams(noteObj(itemId, "lim", "work", "ab\r\ncdef\r\ngh")))

            assertEquals(1, result.data()["upserted"]!!.jsonPrimitive.int, "result: $result")
            assertFalse(
                result
                    .data()["notes"]!!
                    .jsonArray[0]
                    .jsonObject
                    .containsKey("warning"),
                "no warning at exactly maxLength"
            )
            assertEquals("ab\ncdef\ngh", stored(itemId, "lim")!!.body)
        }

    // ---------------------------------------------------------------------------------------------
    // probes: duplicate key in one batch, replay of the same payload
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe the same key twice in one batch stores the later body once`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)

            run(ctx, upsertParams(noteObj(itemId, "dup", "WORK", "first"), noteObj(itemId, "dup", "Work", "second")))

            assertEquals("second", stored(itemId, "dup")!!.body)
            assertEquals(
                1,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemId(itemId)
                    .size
            )
            assertEquals("work", stored(itemId, "dup")!!.role)
        }

    @Test
    fun `probe re-sending the same payload twice is idempotent in the store`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = item(tags = null)
            val params = upsertParams(noteObj(itemId, "same", "WORK", "x\r\ny"))

            run(ctx, params)
            val second = run(ctx, params)

            assertEquals(1, second.data()["upserted"]!!.jsonPrimitive.int, "result: $second")
            assertEquals(
                1,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemId(itemId)
                    .size
            )
            assertEquals("x\ny", stored(itemId, "same")!!.body)
            assertEquals("work", stored(itemId, "same")!!.role)
        }

    // ---------------------------------------------------------------------------------------------
    // S18 idempotency never records a config-dependent rejection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S18 a reject-mode rejection under requestId is not recorded and the retry executes after config flips to warn`(): Unit =
        runBlocking {
            val repos = db.repositoryProvider()
            val root = repos.workItemRepository().create(WorkItem(title = "root"))
            val itemId = item(rootId = root.id)
            val perRoot = PerRootConfigService(repos.projectConfigRepository())
            val ctx = context(globalMode = "reject", perRoot = perRoot)
            val requestId = UUID.randomUUID().toString()
            val params =
                upsertParams(
                    noteObj(itemId, "lim", "work", "x".repeat(11)),
                    extra =
                        mapOf(
                            "requestId" to JsonPrimitive(requestId),
                            "actor" to
                                buildJsonObject {
                                    put("id", JsonPrimitive("s18-agent"))
                                    put("kind", JsonPrimitive("subagent"))
                                }
                        )
                )

            val rejected = run(ctx, params)
            assertEquals(1, rejected.data()["failed"]!!.jsonPrimitive.int, "reject mode must refuse: $rejected")
            assertEquals(
                "NOTE_BODY_TOO_LONG",
                rejected
                    .data()["failures"]!!
                    .jsonArray[0]
                    .jsonObject["code"]!!
                    .jsonPrimitive.content
            )
            assertNull(stored(itemId, "lim"))

            repos.projectConfigRepository().upsert(root.id, "note_limits:\n  mode: warn\n")

            val retried = run(ctx, params)
            assertTrue(retried["success"]!!.jsonPrimitive.boolean, "result: $retried")
            assertEquals(1, retried.data()["upserted"]!!.jsonPrimitive.int, "the retry must execute, not replay the rejection: $retried")
            assertNotNull(retried.data()["notes"]!!.jsonArray[0].jsonObject["warning"], "warn mode accepts with a warning")
            assertFalse(retried.toString().contains("\"replayed\":true"), "the retry is a fresh execution: $retried")
            assertEquals("x".repeat(11), stored(itemId, "lim")!!.body)
        }
}

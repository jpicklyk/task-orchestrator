package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.envelopeOk
import io.github.jpicklyk.mcptask.current.test.getNoteVia
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0b2d5199) for the `notes_write` tool (ops `upsert` and `delete`): scenarios S6 to
 * S14, S16 (write half), S17 (write half) and the ported write policy of S21 of the frozen test-plan (all NEW-SURFACE).
 *
 * Oracles: task-scope 1.2 (per-element results `{id, version, created?, warning?}` and `{id}`; exactly one of `body` /
 * `bodyFromFile`, `body: ""` allowed; `delete` needs itemId + key and a missing note is `not_found{kind: note}`; request
 * classes never declare `actor`), 1.3 (a forbidden element writes nothing and records no event), 1.4 (create gives version 1,
 * every successful upsert of an existing note adds 1, identical body included; `expectedVersion`: null unconditional, 0 must
 * create, n current must equal n, else `version_conflict{kind: note, id, expected, actual}` with id = the note UUID when it
 * exists else `<itemUuid>/<key>`; delete honours it the same way), 1.5 (read errors are `invalid_request`, cap 65536 bytes
 * with `payload_too_large{max, actual}`, CRLF becomes LF, a lone CR is kept), decisions D2 to D4, D7 (`warning` is an object
 * `{key, max, actual}`), D8 (no item context in results), D12 (`note.upserted` / `note.deleted` events, no `version` in the
 * upsert payload), the unchanged 3.x write policy (role lower-cased and one of queue|work|review, a schema-declared key must
 * carry the schema role, `maxLength` warns or rejects per `note_limits.mode`) and envelope sections 2 to 4 (batch results,
 * `partial_failure`, `atomic`, `error.index`, idempotency with `replayed`, details).
 *
 * Harness: the REAL production composition ([EventLogRig], its temp directory is AGENT_CONFIG_DIR) so scope, units, events,
 * idempotency and the body source are the production ones. Every "not stored / no event" assertion is paired with a control
 * that DOES store through the same call shape.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NotesWriteToolTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var agentDir: Path
    private lateinit var secret: Path
    private var built: EventLogRig? = null

    private val rig: EventLogRig get() = built ?: useRig()

    @BeforeEach
    fun setUp(
        @TempDir dir: Path
    ) {
        agentDir = dir.resolve("agent")
        Files.createDirectories(agentDir)
        secret = dir.resolve("secret.txt")
        Files.write(secret, "OUTSIDE-SECRET".toByteArray(Charsets.UTF_8))
    }

    private fun useRig(config: String = "work_item_schemas: {}\n"): EventLogRig =
        EventLogRig.build(db.db, agentDir, config).also {
            built =
                it
        }

    // ------------------------------------------------------------------ helpers

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.num(name: String): Long = getValue(name).jsonPrimitive.long

    private fun JsonObject.data(): JsonObject = obj("data")

    private fun JsonObject.code(): String = obj("error").str("code")

    private fun JsonObject.results(): List<JsonObject> = data().arr("results").map { it.jsonObject }

    private fun JsonObject.elementError(index: Int): JsonObject = results()[index].obj("error")

    private fun JsonObject.elementOk(index: Int): Boolean = results()[index].str("ok").toBoolean()

    private fun op(
        name: String,
        item: UUID,
        key: String,
        vararg fields: Pair<String, JsonElement>
    ): JsonObject =
        buildJsonObject {
            put("op", name)
            put("itemId", item.toString())
            put("key", key)
            for ((field, value) in fields) put(field, value)
        }

    private fun upsertOp(
        item: UUID,
        key: String,
        role: String = "work",
        body: String? = "x",
        fields: Map<String, JsonElement> = emptyMap()
    ): JsonObject {
        val all =
            buildList<Pair<String, JsonElement>> {
                add("role" to JsonPrimitive(role))
                if (body != null) add("body" to JsonPrimitive(body))
                addAll(fields.toList())
            }
        return op("upsert", item, key, *all.toTypedArray())
    }

    private fun fileOp(
        item: UUID,
        key: String,
        path: String,
        role: String = "work"
    ): JsonObject = upsertOp(item, key, role, null, mapOf("bodyFromFile" to JsonPrimitive(path)))

    private fun deleteOp(
        item: UUID,
        key: String,
        vararg fields: Pair<String, JsonElement>
    ): JsonObject = op("delete", item, key, *fields)

    private suspend fun write(
        vararg ops: JsonObject,
        atomic: Boolean? = null,
        actor: JsonObject? = null,
        access: CallAccess = CallAccess.UNRESTRICTED
    ): JsonObject =
        rig.ctx.callTool(
            "notes_write",
            buildJsonObject {
                actor?.let { put("actor", it) }
                atomic?.let { put("atomic", it) }
                put("ops", JsonArray(ops.toList()))
            },
            access
        )

    private fun actor(id: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    private suspend fun stored(
        item: UUID,
        key: String
    ): Note? = rig.raw.noteRepository().findByItemIdAndKey(item, key)

    private suspend fun noteCount(item: UUID): Int =
        rig.raw
            .noteRepository()
            .findByItemId(item)
            .size

    private fun access(token: ParityToken): CallAccess = token.principal().toCallAccess(redactNoteAttribution = true)

    // ------------------------------------------------------------------ S6 version on create / upsert

    @Test
    fun `S6 a first write creates version 1 with created true, a rewrite with the identical body bumps to 2 without created`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val first = write(upsertOp(item.id, "spec", body = "same body"))
            assertTrue(envelopeOk(first), "$first")
            val created = first.results().single()
            assertEquals(1L, created.num("version"))
            assertEquals("true", created.str("created"))
            assertEquals(1L, first.data().obj("summary").num("ok"))

            val second = write(upsertOp(item.id, "spec", body = "same body"))
            assertTrue(envelopeOk(second), "$second")
            val updated = second.results().single()
            assertEquals(2L, updated.num("version"), "an identical body still bumps the version (D5)")
            assertFalse("created" in updated, "an update carries no created flag: $updated")
            assertEquals(created.str("id"), updated.str("id"), "the note id is stable across updates")

            val third = write(upsertOp(item.id, "spec", body = "different"))
            assertEquals(3L, third.results().single().num("version"))
            val row = assertNotNull(stored(item.id, "spec"))
            assertEquals(3L, row.version)
            assertEquals("different", row.body)
        }

    @Test
    fun `S6 probe the same key twice in one batch yields versions 1 then 2 in order`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "dup", body = "one"), upsertOp(item.id, "dup", body = "two"))

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals(listOf(1L, 2L), answer.results().map { it.num("version") })
            assertEquals(2L, assertNotNull(stored(item.id, "dup")).version)
            assertEquals("two", assertNotNull(stored(item.id, "dup")).body)
        }

    @Test
    fun `S6 a result element carries only id, version, created, warning and replayed - no item context`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "spec"))

            val element = answer.results().single()
            assertEquals(setOf("ok", "id", "version", "created"), element.keys, "D8: no itemContext or phase guidance in the element")
            assertEquals(setOf("results", "summary"), answer.data().keys)
        }

    // ------------------------------------------------------------------ S7 expectedVersion

    @Test
    fun `S7 a stale expectedVersion on upsert is version_conflict with expected, actual and the note id, and the note is kept`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val id = write(upsertOp(item.id, "spec", body = "original")).results().single().str("id")

            val answer = write(upsertOp(item.id, "spec", body = "overwrite", fields = mapOf("expectedVersion" to JsonPrimitive(5))))

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("partial_failure", answer.code())
            assertFalse(answer.elementOk(0))
            val error = answer.elementError(0)
            assertEquals("version_conflict", error.str("code"))
            val detail = error.obj("detail")
            assertEquals("note", detail.str("kind"))
            assertEquals(id, detail.str("id"))
            assertEquals(5L, detail.num("expected"))
            assertEquals(1L, detail.num("actual"))
            val row = assertNotNull(stored(item.id, "spec"))
            assertEquals("original", row.body, "the conflicting write changed nothing")
            assertEquals(1L, row.version)
        }

    @Test
    fun `S7 expectedVersion 0 on an existing note conflicts, and on a missing note creates it`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(upsertOp(item.id, "exists", body = "v1"))

            val mustCreate = write(upsertOp(item.id, "exists", body = "v2", fields = mapOf("expectedVersion" to JsonPrimitive(0))))
            assertEquals("version_conflict", mustCreate.elementError(0).str("code"))
            assertEquals(0L, mustCreate.elementError(0).obj("detail").num("expected"))
            assertEquals(1L, mustCreate.elementError(0).obj("detail").num("actual"))
            assertEquals("v1", assertNotNull(stored(item.id, "exists")).body)

            val creates = write(upsertOp(item.id, "fresh", body = "new", fields = mapOf("expectedVersion" to JsonPrimitive(0))))
            assertTrue(envelopeOk(creates), "expectedVersion 0 on a missing note creates it: $creates")
            assertEquals(1L, creates.results().single().num("version"))
        }

    @Test
    fun `S7 expectedVersion n on a missing note conflicts with actual 0 and the itemUuid slash key id, and stores nothing`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "ghost", body = "x", fields = mapOf("expectedVersion" to JsonPrimitive(3))))

            val error = answer.elementError(0)
            assertEquals("version_conflict", error.str("code"))
            val detail = error.obj("detail")
            assertEquals("note", detail.str("kind"))
            assertEquals("${item.id}/ghost", detail.str("id"))
            assertEquals(3L, detail.num("expected"))
            assertEquals(0L, detail.num("actual"))
            assertNull(stored(item.id, "ghost"), "nothing was created")
        }

    @Test
    fun `S7 the right expectedVersion writes and advances the version, again and again`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(upsertOp(item.id, "spec", body = "v1"))

            val one = write(upsertOp(item.id, "spec", body = "v2", fields = mapOf("expectedVersion" to JsonPrimitive(1))))
            assertTrue(envelopeOk(one), "$one")
            assertEquals(2L, one.results().single().num("version"))
            val two = write(upsertOp(item.id, "spec", body = "v3", fields = mapOf("expectedVersion" to JsonPrimitive(2))))
            assertEquals(3L, two.results().single().num("version"))
            val stale = write(upsertOp(item.id, "spec", body = "v-stale", fields = mapOf("expectedVersion" to JsonPrimitive(2))))
            assertEquals("version_conflict", stale.elementError(0).str("code"))
            assertEquals(3L, stale.elementError(0).obj("detail").num("actual"))
            assertEquals("v3", assertNotNull(stored(item.id, "spec")).body)
        }

    @Test
    fun `S7 delete honours expectedVersion - stale conflicts and keeps the note, the right version deletes it`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val id = write(upsertOp(item.id, "spec", body = "keep me")).results().single().str("id")
            write(upsertOp(item.id, "spec", body = "keep me v2"))

            val stale = write(deleteOp(item.id, "spec", "expectedVersion" to JsonPrimitive(1)))
            assertEquals("partial_failure", stale.code())
            val error = stale.elementError(0)
            assertEquals("version_conflict", error.str("code"))
            assertEquals("note", error.obj("detail").str("kind"))
            assertEquals(id, error.obj("detail").str("id"))
            assertEquals(1L, error.obj("detail").num("expected"))
            assertEquals(2L, error.obj("detail").num("actual"))
            assertNotNull(stored(item.id, "spec"), "the stale delete kept the note")

            val zero = write(deleteOp(item.id, "spec", "expectedVersion" to JsonPrimitive(0)))
            assertEquals("version_conflict", zero.elementError(0).str("code"), "0 means must-create, so an existing note conflicts")
            assertNotNull(stored(item.id, "spec"))

            val right = write(deleteOp(item.id, "spec", "expectedVersion" to JsonPrimitive(2)))
            assertTrue(envelopeOk(right), "$right")
            assertEquals(id, right.results().single().str("id"))
            assertNull(stored(item.id, "spec"))
        }

    // ------------------------------------------------------------------ delete

    @Test
    fun `delete removes the note, answers its id and records a note deleted event`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val id = write(upsertOp(item.id, "gone", body = "bye")).results().single().str("id")

            val (answer, rows) = rig.written { write(deleteOp(item.id, "gone")) }

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals(setOf("ok", "id"), answer.results().single().keys)
            assertEquals(id, answer.results().single().str("id"))
            assertNull(stored(item.id, "gone"))
            assertEquals(listOf("note.deleted"), rows.map { it.type })
            assertFalse(envelopeOk(rig.ctx.getNoteVia(item.id, "gone")), "a read of the deleted note is not_found")
        }

    @Test
    fun `delete of a missing note is not_found with kind note, never ok`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(upsertOp(item.id, "other"))

            val answer = write(deleteOp(item.id, "missing"))

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("partial_failure", answer.code())
            val error = answer.elementError(0)
            assertEquals("not_found", error.str("code"))
            assertEquals("note", error.obj("detail").str("kind"))
            assertNotNull(stored(item.id, "other"), "a failed delete touched nothing else")
        }

    @Test
    fun `delete takes itemId and key only - the 3x ids form is rejected and deletes nothing`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val id = write(upsertOp(item.id, "spec")).results().single().str("id")

            val withIds = write(deleteOp(item.id, "spec", "ids" to JsonArray(listOf(JsonPrimitive(id)))))
            assertEquals("unknown_parameter", withIds.elementError(0).str("code"), "$withIds")
            assertNotNull(stored(item.id, "spec"), "nothing was deleted")

            val withoutKey =
                write(
                    buildJsonObject {
                        put("op", "delete")
                        put("itemId", item.id.toString())
                    }
                )
            assertFalse(withoutKey.elementOk(0), "delete by item alone (the bulk form) is gone: $withoutKey")
            assertNotNull(stored(item.id, "spec"), "nothing was deleted")

            val control = write(deleteOp(item.id, "spec"))
            assertTrue(envelopeOk(control), "control: the supported shape deletes: $control")
            assertNull(stored(item.id, "spec"))
        }

    // ------------------------------------------------------------------ S8 batches

    @Test
    fun `S8 a valid element then an invalid role is partial_failure - the first is stored, the second reports invalid_request`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "good", role = "work"), upsertOp(item.id, "bad", role = "bogus"))

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("partial_failure", answer.code())
            assertTrue(answer.elementOk(0))
            assertFalse(answer.elementOk(1))
            assertEquals("invalid_request", answer.elementError(1).str("code"))
            assertEquals(1L, answer.data().obj("summary").num("ok"))
            assertEquals(1L, answer.data().obj("summary").num("failed"))
            assertNotNull(stored(item.id, "good"))
            assertNull(stored(item.id, "bad"))
        }

    @Test
    fun `S8 with atomic true the same batch stores nothing, records no event and reports the failing index`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val before = rig.maxSeq()

            val answer = write(upsertOp(item.id, "good"), upsertOp(item.id, "bad", role = "bogus"), atomic = true)

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("invalid_request", answer.code())
            assertEquals(1L, answer.obj("error").num("index"))
            assertFalse("data" in answer, "an atomic failure carries no per-element data: $answer")
            assertNull(stored(item.id, "good"), "element 0 was rolled back with the batch")
            assertEquals(0, noteCount(item.id))
            assertEquals(before, rig.maxSeq(), "the rolled-back batch left no event rows")

            val control = write(upsertOp(item.id, "good"), upsertOp(item.id, "also-good"), atomic = true)
            assertTrue(envelopeOk(control), "control: an all-valid atomic batch commits: $control")
            assertEquals(2, noteCount(item.id))
        }

    // ------------------------------------------------------------------ S9 idempotency

    @Test
    fun `S9 replaying the same idempotencyKey returns the stored result without re-executing`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val keyed = upsertOp(item.id, "spec", body = "once", fields = mapOf("idempotencyKey" to JsonPrimitive("key-1")))

            val first = write(keyed, actor = actor("agent-1"))
            assertTrue(envelopeOk(first), "$first")
            assertEquals(1L, first.results().single().num("version"))
            assertFalse("replayed" in first.results().single(), "the first execution is not a replay")
            val mark = rig.maxSeq()

            val second = write(keyed, actor = actor("agent-1"))
            assertTrue(envelopeOk(second), "$second")
            assertEquals("true", second.results().single().str("replayed"))
            assertEquals(1L, second.results().single().num("version"), "the stored result is returned, the version is not bumped")
            assertEquals(first.results().single().str("id"), second.results().single().str("id"))
            assertEquals(1L, assertNotNull(stored(item.id, "spec")).version, "the replay did not write")
            assertEquals(mark, rig.maxSeq(), "the replay recorded no event")

            val withoutKey = write(upsertOp(item.id, "spec", body = "once"), actor = actor("agent-1"))
            assertEquals(2L, withoutKey.results().single().num("version"), "control: the same write without a key does execute")
        }

    @Test
    fun `S9 the same key with a different payload is idempotency_mismatch and a different actor may reuse the key`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(
                upsertOp(item.id, "spec", body = "one", fields = mapOf("idempotencyKey" to JsonPrimitive("key-m"))),
                actor = actor("agent-1")
            )

            val mismatch =
                write(
                    upsertOp(item.id, "spec", body = "two", fields = mapOf("idempotencyKey" to JsonPrimitive("key-m"))),
                    actor = actor("agent-1")
                )
            assertEquals("idempotency_mismatch", mismatch.elementError(0).str("code"))
            assertEquals("key-m", mismatch.elementError(0).obj("detail").str("idempotencyKey"))
            assertEquals("one", assertNotNull(stored(item.id, "spec")).body)

            val otherActor =
                write(
                    upsertOp(item.id, "spec", body = "two", fields = mapOf("idempotencyKey" to JsonPrimitive("key-m"))),
                    actor = actor("agent-2")
                )
            assertTrue(envelopeOk(otherActor), "another principal may reuse the key: $otherActor")
            assertEquals("two", assertNotNull(stored(item.id, "spec")).body)
        }

    @Test
    fun `S9 probe a replay after the file content changed returns the stored result and does not re-read the file`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val file = agentDir.resolve("replay.txt")
            Files.write(file, "first text".toByteArray(Charsets.UTF_8))
            val keyed =
                fileOp(item.id, "spec", "replay.txt").let { base ->
                    JsonObject(base + ("idempotencyKey" to JsonPrimitive("key-f")))
                }

            val first = write(keyed, actor = actor("agent-1"))
            assertTrue(envelopeOk(first), "$first")
            Files.write(file, "second text".toByteArray(Charsets.UTF_8))
            val replay = write(keyed, actor = actor("agent-1"))

            assertEquals("true", replay.results().single().str("replayed"))
            assertEquals("first text", assertNotNull(stored(item.id, "spec")).body)
            assertEquals(1L, assertNotNull(stored(item.id, "spec")).version)

            val fresh = write(fileOp(item.id, "spec", "replay.txt"), actor = actor("agent-1"))
            assertEquals("second text", assertNotNull(stored(item.id, "spec")).body, "control: without the key the new file text is read")
            assertEquals(2L, fresh.results().single().num("version"))
        }

    // ------------------------------------------------------------------ S10 body / bodyFromFile

    @Test
    fun `S10 body together with bodyFromFile, and neither, are invalid_request and store nothing`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            Files.write(agentDir.resolve("f.txt"), "from file".toByteArray(Charsets.UTF_8))

            val both = write(upsertOp(item.id, "both", body = "inline", fields = mapOf("bodyFromFile" to JsonPrimitive("f.txt"))))
            assertEquals("invalid_request", both.elementError(0).str("code"), "$both")
            val neither = write(upsertOp(item.id, "neither", body = null))
            assertEquals("invalid_request", neither.elementError(0).str("code"), "$neither")
            val explicitNull = write(upsertOp(item.id, "nullbody", body = null, fields = mapOf("body" to JsonNull)))
            assertEquals("invalid_request", explicitNull.elementError(0).str("code"), "null is not a body: $explicitNull")

            assertEquals(0, noteCount(item.id))
            assertTrue(envelopeOk(write(fileOp(item.id, "control", "f.txt"))), "control: the file form alone is accepted")
            assertEquals("from file", assertNotNull(stored(item.id, "control")).body)
        }

    @Test
    fun `S10 an empty inline body is a body, stored as an empty string`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "blank", body = ""))

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals("", assertNotNull(stored(item.id, "blank")).body)
            val read = rig.ctx.getNoteVia(item.id, "blank")
            assertEquals("", read.data().str("body"))
        }

    @Test
    fun `an actor member inside an element is unknown_parameter - the principal is top-level only`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val answer = write(upsertOp(item.id, "spec", fields = mapOf("actor" to actor("sneaky"))))

            assertEquals("unknown_parameter", answer.elementError(0).str("code"), "$answer")
            assertNull(stored(item.id, "spec"))
            assertTrue(envelopeOk(write(upsertOp(item.id, "spec"))), "control: the element without the member stores")
        }

    // ------------------------------------------------------------------ S11 CRLF

    @Test
    fun `S11 a CRLF file is stored with LF line ends and a lone CR is kept`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            Files.write(agentDir.resolve("crlf.txt"), "a\r\nb\r\nc\rd\r\n".toByteArray(Charsets.UTF_8))

            val answer = write(fileOp(item.id, "crlf", "crlf.txt"))

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals("a\nb\nc\rd\n", assertNotNull(stored(item.id, "crlf")).body)
        }

    @Test
    fun `S11 an inline CRLF body is normalized the same way`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            write(upsertOp(item.id, "inline", body = "x\r\ny\rz"))

            assertEquals("x\ny\rz", assertNotNull(stored(item.id, "inline")).body)
        }

    // ------------------------------------------------------------------ S12 tool-level containment

    @Test
    fun `S12 unreadable bodyFromFile paths fail the element as invalid_request with no leak and store nothing`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            Files.write(agentDir.resolve("ok.txt"), "fine".toByteArray(Charsets.UTF_8))
            Files.createDirectories(agentDir.resolve("a-directory"))
            Files.write(agentDir.resolve("bad-utf8.bin"), byteArrayOf(0x61, 0xC3.toByte(), 0x28))
            val absolute = agentDir.resolve("ok.txt").toString()
            val cases =
                mapOf(
                    "escape" to "../secret.txt",
                    "nested escape" to "a-directory/../../secret.txt",
                    "absolute" to absolute,
                    "missing" to "nope.txt",
                    "directory" to "a-directory",
                    "nul" to "ok.txt\u0000",
                    "bad utf-8" to "bad-utf8.bin"
                )

            for ((label, path) in cases) {
                val answer = write(fileOp(item.id, "c-${label.replace(' ', '-')}", path))
                assertFalse(envelopeOk(answer), "$label: $answer")
                assertEquals("partial_failure", answer.code(), label)
                val error = answer.elementError(0)
                assertEquals("invalid_request", error.str("code"), "$label: $answer")
                val text = error.toString()
                assertFalse("OUTSIDE-SECRET" in text, "$label: no file text in the error: $text")
                val rootForms =
                    listOf(agentDir.toString(), agentDir.toString().replace('\\', '/'), agentDir.toString().replace("\\", "\\\\"))
                if (label != "absolute") {
                    // an absolute path typed by the caller is echoed back as the caller's own input, not a server-side leak
                    assertFalse(rootForms.any { it in text }, "$label: the root is not named: $text")
                }
                assertTrue("bodyFromFile" in text, "$label: the error points at the field: $text")
            }
            assertEquals(0, noteCount(item.id), "none of the rejected elements stored a note")

            val control = write(fileOp(item.id, "control", "ok.txt"))
            assertTrue(envelopeOk(control), "control: a readable relative path stores: $control")
            assertEquals("fine", assertNotNull(stored(item.id, "control")).body)
        }

    // ------------------------------------------------------------------ S13 caps

    @Test
    fun `S13 a file of exactly 65536 bytes is stored, one more byte is payload_too_large with max and actual`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            Files.write(agentDir.resolve("at.txt"), "a".repeat(65536).toByteArray(Charsets.UTF_8))
            Files.write(agentDir.resolve("over.txt"), "a".repeat(65537).toByteArray(Charsets.UTF_8))

            val atCap = write(fileOp(item.id, "at", "at.txt"))
            assertTrue(envelopeOk(atCap), "$atCap")
            assertEquals(65536, assertNotNull(stored(item.id, "at")).body.length)

            val over = write(fileOp(item.id, "over", "over.txt"))
            assertFalse(envelopeOk(over), "$over")
            val error = over.elementError(0)
            assertEquals("payload_too_large", error.str("code"))
            assertEquals(65536L, error.obj("detail").num("max"))
            assertEquals(65537L, error.obj("detail").num("actual"))
            assertNull(stored(item.id, "over"))
        }

    @Test
    fun `S13 an inline body is capped at 65536 bytes counted in bytes`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val atCap = write(upsertOp(item.id, "at", body = "a".repeat(65536)))
            assertTrue(envelopeOk(atCap), "$atCap")
            val over = write(upsertOp(item.id, "over", body = "a".repeat(65537)))
            assertEquals("payload_too_large", over.elementError(0).str("code"))
            assertEquals(65537L, over.elementError(0).obj("detail").num("actual"))
            val wide = write(upsertOp(item.id, "wide", body = "é".repeat(32769)))
            assertEquals("payload_too_large", wide.elementError(0).str("code"), "32769 two-byte characters are 65538 bytes")
            assertEquals(65538L, wide.elementError(0).obj("detail").num("actual"))
            assertNull(stored(item.id, "over"))
            assertNull(stored(item.id, "wide"))
        }

    // ------------------------------------------------------------------ S14 default context

    @Test
    fun `S14 a context built without a body source refuses bodyFromFile even for a file that exists in the working directory`(): Unit =
        runBlocking {
            val bare = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val item = db.repositoryProvider().workItemRepository().create(WorkItem(title = "bare owner"))
            val cwdFile =
                assertNotNull(
                    File(System.getProperty("user.dir"))
                        .listFiles()
                        ?.firstOrNull { it.isFile && it.length() in 1..60_000 },
                    "control: the test working directory holds a small regular file"
                )

            val answer = bare.upsertNoteVia(item.id, "cwd", body = null, extra = mapOf("bodyFromFile" to JsonPrimitive(cwdFile.name)))

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("invalid_request", answer.elementError(0).str("code"), "$answer")
            assertEquals(
                0,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemId(item.id)
                    .size
            )

            val inline = bare.upsertNoteVia(item.id, "inline", body = "still fine")
            assertTrue(envelopeOk(inline), "control: inline bodies need no body source: $inline")
        }

    @Test
    fun `S14 the composed server reads bodyFromFile relative to AGENT_CONFIG_DIR`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            Files.write(agentDir.resolve("composed.txt"), "composed text".toByteArray(Charsets.UTF_8))

            val answer = write(fileOp(item.id, "composed", "composed.txt"))

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals("composed text", assertNotNull(stored(item.id, "composed")).body)
        }

    // ------------------------------------------------------------------ S16 scope (write half)

    @Test
    fun `S16 a root-scoped token writes inside its tree and is forbidden outside it - nothing written, no event`(): Unit =
        runBlocking {
            val r1 = rig.seed("alpha root")
            val c1 = rig.seed("alpha child", parent = r1)
            val r2 = rig.seed("beta root")
            rig.ctx.upsertNoteVia(r2.id, "pre", body = "PRE-EXISTING")
            val scoped = access(ParityToken("nw-root", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES), rootIds = setOf(r1.id)))

            val (allowed, allowedRows) = rig.written { write(upsertOp(c1.id, "spec", body = "in scope"), access = scoped) }
            assertTrue(envelopeOk(allowed), "control: an in-scope upsert succeeds: $allowed")
            assertTrue(allowedRows.any { it.type == "note.upserted" }, "control: it records an event: ${allowedRows.map { it.type }}")

            val mark = rig.maxSeq()
            val denied = write(upsertOp(r2.id, "spec", body = "HIJACK"), access = scoped)
            val deniedDelete = write(deleteOp(r2.id, "pre"), access = scoped)
            for (answer in listOf(denied, deniedDelete)) {
                assertFalse(envelopeOk(answer), "$answer")
                assertEquals("partial_failure", answer.code())
                val error = answer.elementError(0)
                assertEquals("forbidden", error.str("code"))
                assertEquals("root", error.obj("detail").str("scope"))
                assertEquals(r1.id.toString(), error.obj("detail").str("required"))
            }
            assertNull(stored(r2.id, "spec"), "nothing was written outside the scope")
            assertEquals("PRE-EXISTING", assertNotNull(stored(r2.id, "pre")).body, "nothing was deleted outside the scope")
            assertEquals(mark, rig.maxSeq(), "forbidden elements record no event")
        }

    @Test
    fun `S16 a batch with an in-scope and an out-of-scope element is partial_failure, the first is written`(): Unit =
        runBlocking {
            val r1 = rig.seed("alpha root")
            val r2 = rig.seed("beta root")
            val scoped = access(ParityToken("nw-mixed", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES), rootIds = setOf(r1.id)))

            val answer = write(upsertOp(r1.id, "in", body = "ok"), upsertOp(r2.id, "out", body = "no"), access = scoped)

            assertEquals("partial_failure", answer.code())
            assertTrue(answer.elementOk(0))
            assertEquals("forbidden", answer.elementError(1).str("code"))
            assertNotNull(stored(r1.id, "in"))
            assertNull(stored(r2.id, "out"))

            val atomic = write(upsertOp(r1.id, "in2", body = "ok"), upsertOp(r2.id, "out2", body = "no"), atomic = true, access = scoped)
            assertEquals("forbidden", atomic.code())
            assertEquals(1L, atomic.obj("error").num("index"))
            assertNull(stored(r1.id, "in2"), "the in-scope element was rolled back with the atomic batch")
            assertNull(stored(r2.id, "out2"))
        }

    @Test
    fun `S16 a tag-scoped token is forbidden for an untagged item`(): Unit =
        runBlocking {
            val alpha = rig.seed("tagged alpha", tags = "alpha")
            val beta = rig.seed("tagged beta", tags = "beta")
            val scoped = access(ParityToken("nw-tag", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES), tagsInclude = setOf("alpha")))

            assertTrue(envelopeOk(write(upsertOp(alpha.id, "spec"), access = scoped)), "control: the tagged item accepts the write")
            val denied = write(upsertOp(beta.id, "spec"), access = scoped)

            assertEquals("forbidden", denied.elementError(0).str("code"))
            assertEquals("tag", denied.elementError(0).obj("detail").str("scope"))
            assertEquals("alpha", denied.elementError(0).obj("detail").str("required"))
            assertNull(stored(beta.id, "spec"))
        }

    // ------------------------------------------------------------------ S17 capability (write half)

    @Test
    fun `S17 a read-only token is forbidden on both write ops - never executed, nothing stored, no event`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            rig.ctx.upsertNoteVia(item.id, "pre", body = "KEEP")
            val readOnly = access(ParityToken("nw-readonly", setOf(ApiCapability.READ)))
            val mark = rig.maxSeq()

            val upsert = write(upsertOp(item.id, "spec", body = "no"), access = readOnly)
            val delete = write(deleteOp(item.id, "pre"), access = readOnly)

            for (answer in listOf(upsert, delete)) {
                assertFalse(envelopeOk(answer), "$answer")
                val error = answer.elementError(0)
                assertEquals("forbidden", error.str("code"))
                assertEquals("capability", error.obj("detail").str("scope"))
                assertEquals("write-notes", error.obj("detail").str("required"))
            }
            assertNull(stored(item.id, "spec"))
            assertEquals("KEEP", assertNotNull(stored(item.id, "pre")).body)
            assertEquals(mark, rig.maxSeq())

            val writer = access(ParityToken("nw-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES)))
            assertTrue(envelopeOk(write(upsertOp(item.id, "spec", body = "yes"), access = writer)), "control: a write token stores")
        }

    // ------------------------------------------------------------------ S21 ported write policy and events

    @Test
    fun `S21 the role is lower-cased on write, an unknown role is invalid_request`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            assertTrue(envelopeOk(write(upsertOp(item.id, "upper", role = "WORK"), upsertOp(item.id, "mixed", role = "Review"))))
            assertEquals("work", assertNotNull(stored(item.id, "upper")).role)
            assertEquals("review", assertNotNull(stored(item.id, "mixed")).role)

            val bad = write(upsertOp(item.id, "bad", role = "terminal"))
            assertEquals("invalid_request", bad.elementError(0).str("code"))
            assertNull(stored(item.id, "bad"))
        }

    private val limConfig =
        "note_limits:\n  mode: %MODE%\n" +
            "work_item_schemas:\n  lim-type:\n    notes:\n      - key: lim\n        role: work\n        maxLength: 10\n"

    @Test
    fun `S21 a schema-declared key must carry the schema role, undeclared keys and cases are free`(): Unit =
        runBlocking {
            useRig(limConfig.replace("%MODE%", "warn"))
            val item = rig.seed("typed", tags = "lim-type")

            val wrong = write(upsertOp(item.id, "lim", role = "queue", body = "short"))
            assertEquals("schema_violation", wrong.elementError(0).str("code"), "$wrong")
            assertNull(stored(item.id, "lim"))

            assertTrue(envelopeOk(write(upsertOp(item.id, "lim", role = "work", body = "short"))), "control: the schema role is accepted")
            assertTrue(envelopeOk(write(upsertOp(item.id, "free", role = "queue", body = "anything"))), "an undeclared key takes any role")
            assertTrue(envelopeOk(write(upsertOp(item.id, "Lim", role = "queue", body = "case"))), "so Lim is unconstrained")
        }

    @Test
    fun `S21 maxLength in warn mode stores the note and answers a warning object with key, max and actual`(): Unit =
        runBlocking {
            useRig(limConfig.replace("%MODE%", "warn"))
            val item = rig.seed("typed", tags = "lim-type")

            val over = write(upsertOp(item.id, "lim", role = "work", body = "x".repeat(11)))
            assertTrue(envelopeOk(over), "$over")
            val warning = over.results().single().obj("warning")
            assertEquals("lim", warning.str("key"))
            assertEquals(10L, warning.num("max"))
            assertEquals(11L, warning.num("actual"))
            assertEquals(11, assertNotNull(stored(item.id, "lim")).body.length, "warn mode still stores")

            val within = write(upsertOp(item.id, "lim", role = "work", body = "x".repeat(10)))
            assertFalse("warning" in within.results().single(), "exactly maxLength is within the limit: $within")
        }

    @Test
    fun `S21 maxLength in reject mode fails the element as note_too_long and keeps the previous note`(): Unit =
        runBlocking {
            useRig(limConfig.replace("%MODE%", "reject"))
            val item = rig.seed("typed", tags = "lim-type")
            assertTrue(envelopeOk(write(upsertOp(item.id, "lim", role = "work", body = "ten chars!"))), "control: 10 characters fit")

            val over = write(upsertOp(item.id, "lim", role = "work", body = "x".repeat(11)))

            assertFalse(envelopeOk(over), "$over")
            val error = over.elementError(0)
            assertEquals("note_too_long", error.str("code"))
            assertEquals("lim", error.obj("detail").str("key"))
            assertEquals(10L, error.obj("detail").num("max"))
            assertEquals(11L, error.obj("detail").num("actual"))
            val row = assertNotNull(stored(item.id, "lim"))
            assertEquals("ten chars!", row.body)
            assertEquals(1L, row.version)
        }

    @Test
    fun `D12 an upsert records one note upserted event without a version member, a delete one note deleted event`(): Unit =
        runBlocking {
            val item = rig.seed("owner")

            val (_, upsertRows) = rig.written { write(upsertOp(item.id, "spec", body = "evented")) }
            val (_, deleteRows) = rig.written { write(deleteOp(item.id, "spec")) }

            assertEquals(listOf("note.upserted"), upsertRows.map { it.type })
            assertFalse(
                upsertRows.single().payload().containsKey("version"),
                "D12: the payload is unchanged, no version: ${upsertRows.single().data}"
            )
            assertEquals(listOf("note.deleted"), deleteRows.map { it.type })
        }
}

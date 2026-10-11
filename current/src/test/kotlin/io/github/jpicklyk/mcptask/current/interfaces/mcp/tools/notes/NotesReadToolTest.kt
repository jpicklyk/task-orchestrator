package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.envelopeOk
import io.github.jpicklyk.mcptask.current.test.getNoteVia
import io.github.jpicklyk.mcptask.current.test.listNotesVia
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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 0b2d5199) for the `notes_read` tool (selects `get` and `list`): scenarios S1 to
 * S5, S16 (read half), S17 (read half), S18 (tool half) and S20 of the frozen test-plan (all NEW-SURFACE).
 *
 * Oracles: task-scope 1.1 (`get` view `{id, key, role, version, body, modifiedAt, actor?, verification?}` with nulls omitted,
 * `ifVersion` equal to the current version answers `{unchanged: true, version}`, `ifVersion` < 1 is `invalid_request`, a
 * missing note is `not_found{kind: note, id: "<itemUuid>/<key>"}`; `list` answers summaries `{id (8 hex), key, role, version,
 * bodyLength, modifiedAt}` and NEVER a body, ordered `key ASC, id ASC`, a `ListPage` with `total`/`truncated`/`next`, a bad
 * cursor is `invalid_cursor`), 1.3 (item ids through the id resolver, scope check before reading, `forbidden{scope,
 * required}`), 1.6 (attribution visible only when the call may see it, proof evidence never emitted), 1.8 (`query_notes`
 * keeps only search), envelope sections 2 to 4 (shapes, codes, details) and the dispatcher KDoc (a field of another select
 * is `unknown_parameter`; a select lacking the capability fails the whole call `forbidden`).
 *
 * Harness: the REAL production composition ([EventLogRig]) so scope, events and the id resolver are the production ones;
 * notes are created through the production write path (`notes_write` / `NoteCommandService`) except where a scenario needs
 * attribution or proof evidence the write path does not accept (those rows are seeded through the undecorated repository).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NotesReadToolTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var rig: EventLogRig

    @BeforeEach
    fun setUp(
        @TempDir dir: Path
    ) {
        rig = EventLogRig.build(db.db, dir)
    }

    // ------------------------------------------------------------------ helpers

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.num(name: String): Long = getValue(name).jsonPrimitive.long

    private fun JsonObject.data(): JsonObject = obj("data")

    private fun JsonObject.code(): String = obj("error").str("code")

    private fun JsonObject.items(): List<JsonObject> = data().arr("items").map { it.jsonObject }

    private fun JsonObject.page(): JsonObject = data().obj("page")

    private fun JsonObject.keys(): List<String> = items().map { it.str("key") }

    private fun assertNoNulls(
        element: JsonElement,
        path: String = "$"
    ) {
        when (element) {
            is JsonNull -> fail("null member at $path")
            is JsonObject -> element.forEach { (k, v) -> assertNoNulls(v, "$path.$k") }
            is JsonArray -> element.forEachIndexed { i, v -> assertNoNulls(v, "$path[$i]") }
            else -> Unit
        }
    }

    private suspend fun read(
        select: String,
        itemId: String,
        vararg fields: Pair<String, JsonElement>,
        access: CallAccess = CallAccess.UNRESTRICTED
    ): JsonObject =
        rig.ctx.callTool(
            "notes_read",
            buildJsonObject {
                put("select", select)
                put("itemId", itemId)
                for ((name, value) in fields) put(name, value)
            },
            access
        )

    private suspend fun write(
        item: UUID,
        key: String,
        body: String,
        role: String = "work"
    ): String {
        val answer = rig.ctx.upsertNoteVia(item, key, role = role, body = body)
        assertTrue(envelopeOk(answer), "fixture write through notes_write must succeed: $answer")
        return answer
            .data()
            .arr("results")[0]
            .jsonObject
            .str("id")
    }

    // ------------------------------------------------------------------ S1 get

    @Test
    fun `S1 get returns the written note with body, role, version 1 and no null members`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val noteId = write(item.id, "spec", "hello body", role = "queue")

            val answer = rig.ctx.getNoteVia(item.id, "spec")

            assertTrue(envelopeOk(answer), "$answer")
            val note = answer.data()
            assertEquals(noteId, note.str("id"))
            assertEquals("spec", note.str("key"))
            assertEquals("queue", note.str("role"))
            assertEquals(1L, note.num("version"))
            assertEquals("hello body", note.str("body"))
            assertTrue(note.str("modifiedAt").isNotBlank(), "modifiedAt is present: $note")
            assertNoNulls(answer)
            assertFalse("actor" in note, "a note written without a principal carries no actor: $note")
            assertFalse("verification" in note, "and no verification: $note")
        }

    @Test
    fun `S1 get after a second write returns the new body and version 2, and an 8-hex item prefix resolves`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "first")
            write(item.id, "spec", "second")

            val answer = read("get", item.id.toString().take(8), "key" to JsonPrimitive("spec"))

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals("second", answer.data().str("body"))
            assertEquals(2L, answer.data().num("version"))
        }

    @Test
    fun `S1 an empty body is returned as an empty string, not omitted`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "blank", "")

            val answer = rig.ctx.getNoteVia(item.id, "blank")

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals("", answer.data().str("body"))
        }

    // ------------------------------------------------------------------ S2 list

    @Test
    fun `S2 list returns summaries in key order with bodyLength and never a body`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val idC = write(item.id, "c", "SECRET-BODY-C", role = "review")
            val idA = write(item.id, "a", "SECRET-A", role = "queue")
            val idB = write(item.id, "b", "SECRET-B-LONGER", role = "work")

            val answer = rig.ctx.listNotesVia(item.id)

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals(listOf("a", "b", "c"), answer.keys())
            val byKey = answer.items().associateBy { it.str("key") }
            assertEquals(idA.take(8), byKey.getValue("a").str("id"))
            assertEquals(idB.take(8), byKey.getValue("b").str("id"))
            assertEquals(idC.take(8), byKey.getValue("c").str("id"))
            assertEquals(8, byKey.getValue("a").str("id").length)
            assertEquals(listOf("queue", "work", "review"), listOf("a", "b", "c").map { byKey.getValue(it).str("role") })
            assertEquals(listOf(8L, 15L, 13L), listOf("a", "b", "c").map { byKey.getValue(it).num("bodyLength") })
            for (entry in answer.items()) {
                assertEquals(1L, entry.num("version"))
                assertTrue(entry.str("modifiedAt").isNotBlank(), "modifiedAt: $entry")
                assertFalse("body" in entry, "a list entry never carries a body: $entry")
            }
            assertFalse("SECRET" in answer.toString(), "no note text anywhere in a list answer: $answer")
            assertEquals(3L, answer.page().num("total"))
            assertEquals("false", answer.page().str("truncated"))
            assertFalse("next" in answer.page(), "no cursor when nothing is left: ${answer.page()}")
            assertNoNulls(answer)
        }

    @Test
    fun `S2 an item without notes lists an empty page and is not an error`(): Unit =
        runBlocking {
            val item = rig.seed("empty owner")

            val answer = rig.ctx.listNotesVia(item.id)

            assertTrue(envelopeOk(answer), "$answer")
            assertEquals(emptyList(), answer.items())
            assertEquals(0L, answer.page().num("total"))
        }

    @Test
    fun `S2 the role and keys filters narrow the list and total counts the matches`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "a", "1", role = "queue")
            write(item.id, "b", "2", role = "work")
            write(item.id, "c", "3", role = "queue")

            val queueOnly = read("list", item.id.toString(), "role" to JsonPrimitive("queue"))
            assertEquals(listOf("a", "c"), queueOnly.keys())
            assertEquals(2L, queueOnly.page().num("total"))

            val picked = read("list", item.id.toString(), "keys" to JsonArray(listOf("c", "a", "nope").map { JsonPrimitive(it) }))
            assertEquals(listOf("a", "c"), picked.keys(), "requested order does not matter, unknown keys are ignored")
            assertEquals(2L, picked.page().num("total"))
        }

    @Test
    fun `S2 the same key on two items is listed per item`(): Unit =
        runBlocking {
            val one = rig.seed("one")
            val two = rig.seed("two")
            write(one.id, "shared", "from one")
            write(two.id, "shared", "from two")
            write(two.id, "extra", "more")

            assertEquals(listOf("shared"), rig.ctx.listNotesVia(one.id).keys())
            assertEquals(listOf("extra", "shared"), rig.ctx.listNotesVia(two.id).keys())
        }

    // ------------------------------------------------------------------ S3 pagination

    @Test
    fun `S3 limit 2 of 3 notes is truncated with a cursor and the cursor yields the rest`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            for (key in listOf("b", "c", "a")) write(item.id, key, "body-$key")

            val first = read("list", item.id.toString(), "limit" to JsonPrimitive(2))
            assertTrue(envelopeOk(first), "$first")
            assertEquals(listOf("a", "b"), first.keys())
            assertEquals(3L, first.page().num("total"))
            assertEquals("true", first.page().str("truncated"))
            val next = first.page().str("next")
            assertTrue(next.isNotBlank(), "a truncated page carries a cursor: ${first.page()}")

            val second = read("list", item.id.toString(), "limit" to JsonPrimitive(2), "cursor" to JsonPrimitive(next))
            assertTrue(envelopeOk(second), "$second")
            assertEquals(listOf("c"), second.keys())
            assertEquals(3L, second.page().num("total"), "total is the full count before the cap on every page: ${second.page()}")
        }

    @Test
    fun `S3 walking one note per page visits every key exactly once in order`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val keys = listOf("k1", "k2", "k3", "k4", "k5")
            for (key in keys.reversed()) write(item.id, key, "b-$key")

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val fields =
                    buildList<Pair<String, JsonElement>> {
                        add("limit" to JsonPrimitive(1))
                        cursor?.let { add("cursor" to JsonPrimitive(it)) }
                    }
                val answer = read("list", item.id.toString(), *fields.toTypedArray())
                assertTrue(envelopeOk(answer), "$answer")
                seen += answer.keys()
                cursor = answer.page()["next"]?.jsonPrimitive?.content
                pages++
            } while (cursor != null && answer.keys().isNotEmpty() && pages < 10)

            assertEquals(keys, seen, "every key exactly once, in key order")
        }

    @Test
    fun `S3 a garbage cursor is invalid_cursor`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "a", "x")

            val answer = read("list", item.id.toString(), "cursor" to JsonPrimitive("%%not-a-cursor%%"))

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("invalid_cursor", answer.code())
        }

    // ------------------------------------------------------------------ S4 not found, ids

    @Test
    fun `S4 get of a missing key is not_found naming the note as itemUuid slash key`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "present", "x")

            val answer = rig.ctx.getNoteVia(item.id, "missing")

            assertFalse(envelopeOk(answer), "$answer")
            assertEquals("not_found", answer.code())
            val detail = answer.obj("error").obj("detail")
            assertEquals("note", detail.str("kind"))
            assertEquals("${item.id}/missing", detail.str("id"))
        }

    @Test
    fun `S4 get and list of an unknown item are not_found with kind item`(): Unit =
        runBlocking {
            val unknown = UUID.randomUUID()

            val get = rig.ctx.getNoteVia(unknown, "k")
            val list = rig.ctx.listNotesVia(unknown)

            for (answer in listOf(get, list)) {
                assertFalse(envelopeOk(answer), "$answer")
                assertEquals("not_found", answer.code())
                assertEquals("item", answer.obj("error").obj("detail").str("kind"))
            }
        }

    @Test
    fun `S4 an ambiguous 8-hex item prefix is ambiguous_id naming both candidates`(): Unit =
        runBlocking {
            val a = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
            val b = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002")
            for ((id, title) in listOf(a to "first", b to "second")) {
                rig.raw.workItemRepository().create(WorkItem(id = id, title = title, depth = 0))
            }
            write(a, "k", "x")

            val ambiguous = read("get", "aaaaaaaa", "key" to JsonPrimitive("k"))
            assertFalse(envelopeOk(ambiguous), "$ambiguous")
            assertEquals("ambiguous_id", ambiguous.code())
            val candidates = ambiguous.toString()
            assertTrue(a.toString() in candidates && b.toString() in candidates, "both candidates are listed: $candidates")

            val exact = read("get", a.toString(), "key" to JsonPrimitive("k"))
            assertTrue(envelopeOk(exact), "control: the full id resolves: $exact")
        }

    // ------------------------------------------------------------------ S5 ifVersion

    @Test
    fun `S5 ifVersion equal to the current version answers unchanged with the version and no body`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "one")
            write(item.id, "spec", "two")

            val same = read("get", item.id.toString(), "key" to JsonPrimitive("spec"), "ifVersion" to JsonPrimitive(2))

            assertTrue(envelopeOk(same), "$same")
            assertEquals("true", same.data().str("unchanged"))
            assertEquals(2L, same.data().num("version"))
            assertFalse("body" in same.data(), "an unchanged answer carries no body: ${same.data()}")
        }

    @Test
    fun `S5 a stale or different ifVersion returns the full note`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "one")
            write(item.id, "spec", "two")

            for (ifVersion in listOf(1L, 3L, Long.MAX_VALUE)) {
                val answer = read("get", item.id.toString(), "key" to JsonPrimitive("spec"), "ifVersion" to JsonPrimitive(ifVersion))
                assertTrue(envelopeOk(answer), "ifVersion=$ifVersion: $answer")
                assertFalse("unchanged" in answer.data(), "ifVersion=$ifVersion is not the current version: ${answer.data()}")
                assertEquals("two", answer.data().str("body"), "ifVersion=$ifVersion")
                assertEquals(2L, answer.data().num("version"))
            }
        }

    @Test
    fun `S5 an ifVersion below 1 is invalid_request`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "one")

            for (ifVersion in listOf(0L, -1L)) {
                val answer = read("get", item.id.toString(), "key" to JsonPrimitive("spec"), "ifVersion" to JsonPrimitive(ifVersion))
                assertFalse(envelopeOk(answer), "ifVersion=$ifVersion: $answer")
                assertEquals("invalid_request", answer.code(), "ifVersion=$ifVersion")
            }
        }

    // ------------------------------------------------------------------ request shape

    @Test
    fun `a field of another select and an unknown field are unknown_parameter`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "one")

            val listWithKey = read("list", item.id.toString(), "key" to JsonPrimitive("spec"))
            assertEquals("unknown_parameter", listWithKey.code(), "$listWithKey")
            val getWithLimit = read("get", item.id.toString(), "key" to JsonPrimitive("spec"), "limit" to JsonPrimitive(1))
            assertEquals("unknown_parameter", getWithLimit.code(), "$getWithLimit")
            val bogus = read("get", item.id.toString(), "key" to JsonPrimitive("spec"), "bogus" to JsonPrimitive(1))
            assertEquals("unknown_parameter", bogus.code(), "$bogus")
        }

    // ------------------------------------------------------------------ S16 scope (read half)

    private val reader = ParityToken("nr-reader", setOf(ApiCapability.READ))

    private fun access(
        token: ParityToken,
        redactAttribution: Boolean = true
    ): CallAccess = token.principal().toCallAccess(redactNoteAttribution = redactAttribution)

    @Test
    fun `S16 a root-scoped token reads notes of its tree and is forbidden outside it`(): Unit =
        runBlocking {
            val r1 = rig.seed("alpha root")
            val c1 = rig.seed("alpha child", parent = r1)
            val r2 = rig.seed("beta root")
            write(c1.id, "spec", "ALPHA-BODY")
            write(r2.id, "spec", "BETA-BODY")
            val scoped = access(ParityToken("nr-root", setOf(ApiCapability.READ), rootIds = setOf(r1.id)))

            val inGet = rig.ctx.getNoteVia(c1.id, "spec", scoped)
            assertTrue(envelopeOk(inGet), "control: an in-scope get succeeds: $inGet")
            assertEquals("ALPHA-BODY", inGet.data().str("body"))
            val inList = rig.ctx.listNotesVia(c1.id, scoped)
            assertTrue(envelopeOk(inList), "control: an in-scope list succeeds: $inList")
            assertEquals(listOf("spec"), inList.keys())

            for (answer in listOf(rig.ctx.getNoteVia(r2.id, "spec", scoped), rig.ctx.listNotesVia(r2.id, scoped))) {
                assertFalse(envelopeOk(answer), "$answer")
                assertEquals("forbidden", answer.code())
                val detail = answer.obj("error").obj("detail")
                assertEquals("root", detail.str("scope"))
                assertEquals(r1.id.toString(), detail.str("required"))
                assertFalse("BETA-BODY" in answer.toString(), "an out-of-scope note text never leaks: $answer")
            }
        }

    @Test
    fun `S16 a tag-scoped token reads notes of tagged items and is forbidden for others`(): Unit =
        runBlocking {
            val alpha = rig.seed("tagged alpha", tags = "alpha")
            val beta = rig.seed("tagged beta", tags = "beta")
            write(alpha.id, "spec", "ALPHA-BODY")
            write(beta.id, "spec", "BETA-BODY")
            val scoped = access(ParityToken("nr-tag", setOf(ApiCapability.READ), tagsInclude = setOf("alpha")))

            val allowed = rig.ctx.getNoteVia(alpha.id, "spec", scoped)
            assertTrue(envelopeOk(allowed), "control: $allowed")

            val denied = rig.ctx.getNoteVia(beta.id, "spec", scoped)
            assertFalse(envelopeOk(denied), "$denied")
            assertEquals("forbidden", denied.code())
            assertEquals("tag", denied.obj("error").obj("detail").str("scope"))
            assertEquals("alpha", denied.obj("error").obj("detail").str("required"))
            assertFalse("BETA-BODY" in denied.toString(), "$denied")
        }

    // ------------------------------------------------------------------ S17 capability (read half)

    @Test
    fun `S17 a token without the read capability is forbidden for both selects, a read token is not`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            write(item.id, "spec", "CAPABILITY-BODY")
            val writeOnly = access(ParityToken("nr-writeonly", setOf(ApiCapability.WRITE_NOTES)))

            for (answer in listOf(rig.ctx.getNoteVia(item.id, "spec", writeOnly), rig.ctx.listNotesVia(item.id, writeOnly))) {
                assertFalse(envelopeOk(answer), "$answer")
                assertEquals("forbidden", answer.code())
                assertEquals("capability", answer.obj("error").obj("detail").str("scope"))
                assertFalse("CAPABILITY-BODY" in answer.toString(), "$answer")
            }
            assertTrue(envelopeOk(rig.ctx.getNoteVia(item.id, "spec", access(reader))), "control: a read token succeeds")
        }

    // ------------------------------------------------------------------ S18 attribution (tool half)

    private val sha = "deadbeef".repeat(8)

    private suspend fun seedEvidenceNote(item: WorkItem) {
        val claims =
            ProofClaims(
                iss = "https://issuer-s18.example",
                sub = "agent-evidence",
                aud = listOf("aud-s18"),
                jti = "jti-s18",
                iat = 1000L,
                exp = 2000L,
                kid = "kid-s18",
                alg = "RS256"
            )
        rig.raw.noteRepository().upsert(
            Note(
                itemId = item.id,
                key = "evidence",
                role = "work",
                body = "Evidence body",
                actorClaim = ActorClaim(id = "agent-evidence", kind = ActorKind.ORCHESTRATOR, parent = "workflow:s18"),
                verification =
                    VerificationResult(
                        status = VerificationStatus.VERIFIED,
                        verifier = "jwks",
                        proofSha256 = sha,
                        proofClaims = claims
                    )
            )
        )
    }

    private fun assertNoProofEvidence(answer: JsonObject) {
        val text = answer.toString()
        for (evidence in listOf(sha, "jti-s18", "kid-s18", "RS256", "issuer-s18", "aud-s18", "\"sha256\"", "\"proof\"", "proofClaims")) {
            assertFalse(evidence in text, "proof evidence ($evidence) is never emitted: $text")
        }
    }

    @Test
    fun `S18 an unrestricted caller sees actor and verification, and never any proof evidence`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            seedEvidenceNote(item)

            val get = rig.ctx.getNoteVia(item.id, "evidence")
            assertTrue(envelopeOk(get), "$get")
            val actor = get.data().obj("actor")
            assertEquals("agent-evidence", actor.str("id"))
            assertEquals("orchestrator", actor.str("kind"))
            assertEquals("workflow:s18", actor.str("parent"))
            assertEquals("verified", get.data().obj("verification").str("status"))
            assertNoProofEvidence(get)

            val list = rig.ctx.listNotesVia(item.id)
            assertTrue(envelopeOk(list), "$list")
            assertEquals(
                "agent-evidence",
                list
                    .items()
                    .single()
                    .obj("actor")
                    .str("id")
            )
            assertEquals(
                "verified",
                list
                    .items()
                    .single()
                    .obj("verification")
                    .str("status")
            )
            assertNoProofEvidence(list)
        }

    @Test
    fun `S18 a non-admin caller under the default redaction sees neither actor nor verification`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            seedEvidenceNote(item)
            val nonAdmin = access(reader, redactAttribution = true)

            val get = rig.ctx.getNoteVia(item.id, "evidence", nonAdmin)
            val list = rig.ctx.listNotesVia(item.id, nonAdmin)

            for (answer in listOf(get, list)) {
                assertTrue(envelopeOk(answer), "$answer")
                val text = answer.toString()
                assertFalse("\"actor\"" in text, "redacted: no actor: $text")
                assertFalse("\"verification\"" in text, "redacted: no verification: $text")
                assertFalse("agent-evidence" in text, "redacted: the actor id never appears: $text")
                assertNoProofEvidence(answer)
            }
            assertEquals("Evidence body", get.data().str("body"), "control: the note itself is still returned")
            assertEquals("evidence", list.items().single().str("key"), "control: the summary is still returned")
        }

    @Test
    fun `S18 an admin caller sees attribution under redaction, and a non-admin sees it when redaction is off`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            seedEvidenceNote(item)
            val admin = access(ParityToken("nr-admin", setOf(ApiCapability.ADMIN)), redactAttribution = true)
            val unredacted = access(reader, redactAttribution = false)

            for (caller in listOf(admin, unredacted)) {
                val get = rig.ctx.getNoteVia(item.id, "evidence", caller)
                assertTrue(envelopeOk(get), "$get")
                assertEquals("agent-evidence", get.data().obj("actor").str("id"))
                assertEquals("verified", get.data().obj("verification").str("status"))
                assertNoProofEvidence(get)
                val list = rig.ctx.listNotesVia(item.id, caller)
                assertEquals(
                    "agent-evidence",
                    list
                        .items()
                        .single()
                        .obj("actor")
                        .str("id")
                )
                assertNoProofEvidence(list)
            }
        }

    @Test
    fun `S18 a note written through the tool by a named actor round-trips its actor id to an unrestricted reader only`(): Unit =
        runBlocking {
            val item = rig.seed("owner")
            val written =
                rig.ctx.callTool(
                    "notes_write",
                    buildJsonObject {
                        put(
                            "actor",
                            buildJsonObject {
                                put("id", "writer-agent")
                                put("kind", "subagent")
                            }
                        )
                        put(
                            "ops",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("op", "upsert")
                                        put("itemId", item.id.toString())
                                        put("key", "attributed")
                                        put("role", "work")
                                        put("body", "b")
                                    }
                                )
                            )
                        )
                    }
                )
            assertTrue(envelopeOk(written), "$written")

            val unrestricted = rig.ctx.getNoteVia(item.id, "attributed")
            assertEquals("writer-agent", unrestricted.data().obj("actor").str("id"))
            val redacted = rig.ctx.getNoteVia(item.id, "attributed", access(reader))
            assertFalse("writer-agent" in redacted.toString(), "$redacted")
        }

    // ------------------------------------------------------------------ S20 surface

    @Test
    fun `S20 the registry holds notes_read and notes_write, manage_notes is gone and query_notes keeps only search`() {
        val registry = ToolRegistry(buildToolSpecs())
        assertEquals(listOf("notes_read", "notes_write"), registry.tools.map { it.name }.filter { it.startsWith("notes") }.sorted())
        for (name in listOf("notes_read", "notes_write")) {
            val spec = assertNotNull(registry[name], name)
            assertEquals(name, McpOperationAdapter.toolFor(spec, ToolProfile.KNOWLEDGE)?.name, "$name is exposed in the knowledge profile")
        }

        val legacy = buildMcpTools()
        assertTrue(legacy.isNotEmpty(), "control: the 3.x tool list is not empty")
        assertFalse(legacy.any { it.name == "manage_notes" }, "manage_notes is deleted")
        val queryNotes = legacy.single { it.name == "query_notes" }
        val operations =
            queryNotes.parameterSchema.properties!!["operation"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("search"), operations)
    }
}

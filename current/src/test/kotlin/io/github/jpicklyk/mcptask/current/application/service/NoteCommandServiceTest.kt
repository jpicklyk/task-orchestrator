package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored test of `NoteCommandService` (item 6adda27b, seat test-author) against the
 * frozen queue-phase `task-scope` ("Policy ... in this order", "Proposed public API") and `test-plan`
 * notes. Scenario ids (S-numbers) are the test-plan's. No implementation source, diff or commit was
 * opened; every expected value below is derived from the oracle cited in the test-plan:
 *
 *  - TS1 role: locale-invariant `lowercase()`, no trim, must be queue|work|review else INVALID_REQUEST.
 *  - TS2 byte cap: raw resolved body UTF-8 bytes <= 65536, absolute (any mode), else PAYLOAD_TOO_LARGE.
 *  - TS3 CRLF: replace "\r\n" with "\n" (a lone CR is kept).
 *  - TS4 schema-role: a schema-declared key must carry the schema role, else SCHEMA_VIOLATION; off-schema
 *    keys and schema-free items are unconstrained; keys are case-sensitive.
 *  - TS5 maxLength: normalized body String.length > maxLength -> layered note_limits mode: reject ->
 *    NOTE_TOO_LONG, warn -> accept plus warning(key, maxLength, actualLength).
 *  - The five rules apply in that order (first failing rule wins).
 *
 * Fixtures: schema key `lim` (role work, maxLength 10) is declared for items tagged `lim-type`.
 */
class NoteCommandServiceTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val limTag = "lim-type"

    private fun schema(
        globalMode: String = "warn",
        entries: List<NoteSchemaEntry> = listOf(NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10))
    ): NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if (tags.contains(limTag)) entries else null

            override fun getNoteLimitsMode(): String = globalMode
        }

    private fun context(
        schema: NoteSchemaService = schema(),
        perRoot: PerRootConfigSource? = null
    ) = ToolExecutionContext(db.repositoryProvider(), schema, perRootConfigService = perRoot, unitOfWork = db.unitOfWork())

    private fun service(
        schema: NoteSchemaService = schema(),
        perRoot: PerRootConfigSource? = null
    ): NoteCommandService = context(schema, perRoot).noteCommandService

    private fun limItem(rootId: UUID? = null) = WorkItem(title = "lim item", tags = limTag, rootId = rootId)

    private fun plainItem() = WorkItem(title = "plain item")

    private fun <T> Outcome<T>.ok(): T {
        if (this is Outcome.Ok<T>) return value
        fail("expected Ok but was $this")
    }

    private fun Outcome<*>.err(): DomainError = (this as? Outcome.Err)?.error ?: fail("expected Err but was $this")

    private suspend fun persist(item: WorkItem): WorkItem = db.repositoryProvider().workItemRepository().create(item)

    // ---------------------------------------------------------------------------------------------
    // S22 (NEW-SURFACE) prepare is validate-only: no store access at all
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S22 prepare on an unsaved item touches no repository and returns normalized role and LF body`(): Unit =
        runBlocking {
            val strict = mockk<RepositoryProvider>() // no stubs: ANY call on it throws
            val ctx = context()
            val svc = NoteCommandService(strict, ctx.configResolver, ctx.unitOfWork)

            val prepared = svc.prepare(plainItem(), "k", "Work", "a\r\nb").ok()

            assertEquals("work", prepared.role)
            assertEquals("a\nb", prepared.body)
            assertNull(prepared.warning)
        }

    @Test
    fun `S22 prepare on an unsaved schema item still reports the maxLength warning without any store call`(): Unit =
        runBlocking {
            val strict = mockk<RepositoryProvider>()
            val ctx = context()
            val svc = NoteCommandService(strict, ctx.configResolver, ctx.unitOfWork)

            val prepared = svc.prepare(limItem(), "lim", "work", "x".repeat(11)).ok()

            assertEquals(NoteLengthWarning("lim", 10, 11), prepared.warning)
        }

    // ---------------------------------------------------------------------------------------------
    // TS1 role
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 role is lowercased for any casing of queue work review`(): Unit =
        runBlocking {
            val svc = service()
            for ((given, expected) in listOf(
                "WORK" to "work",
                "Work" to "work",
                "wORK" to "work",
                "QUEUE" to "queue",
                "Queue" to "queue",
                "REVIEW" to "review",
                "review" to "review"
            )) {
                assertEquals(expected, svc.prepare(plainItem(), "k", given, "b").ok().role, "role '$given'")
            }
        }

    @Test
    fun `S16 roles outside queue work review are INVALID_REQUEST`(): Unit =
        runBlocking {
            val svc = service()
            for (bad in listOf("done", "terminal", "blocked", "", "wor k", "worker")) {
                assertEquals(ErrorCode.INVALID_REQUEST, svc.prepare(plainItem(), "k", bad, "b").err().code, "role '$bad'")
            }
        }

    @Test
    fun `S16 probe a role with leading or trailing whitespace is not trimmed and is rejected`(): Unit =
        runBlocking {
            val svc = service()
            for (padded in listOf(" work", "work ", " work ", "\twork", "work\n")) {
                assertEquals(
                    ErrorCode.INVALID_REQUEST,
                    svc.prepare(plainItem(), "k", padded, "b").err().code,
                    "padded role '${padded.replace("\n", "\\n").replace("\t", "\\t")}'"
                )
            }
        }

    // ---------------------------------------------------------------------------------------------
    // TS2 byte cap (raw bytes, absolute)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 the cap constant is 65536`() {
        assertEquals(65536, NoteCommandService.MAX_NOTE_BODY_BYTES)
    }

    @Test
    fun `S13 an ASCII body of exactly 65536 bytes is accepted and 65537 is PAYLOAD_TOO_LARGE with byte detail`(): Unit =
        runBlocking {
            val svc = service()

            assertEquals(
                65536,
                svc
                    .prepare(plainItem(), "k", "work", "a".repeat(65536))
                    .ok()
                    .body.length
            )

            val error = svc.prepare(plainItem(), "k", "work", "a".repeat(65537)).err()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.code)
            val detail = error.detail
            assertTrue(detail is ErrorDetail.PayloadTooLarge, "detail: $detail")
            assertEquals(65536L, detail.max)
            assertEquals(65537L, detail.actual)
        }

    // Q34-S8 (oracle: D3 PAYLOAD_TOO_LARGE fix template "Reduce the payload to at most {max} bytes."; the 65536-byte cap
    // is the S13 constant above). The unit must appear exactly once: not dropped, not doubled.
    @Test
    fun `Q34-S8 the oversize body error fix states the 65536 byte cap with the unit exactly once`(): Unit =
        runBlocking {
            val error = service().prepare(plainItem(), "k", "work", "a".repeat(65537)).err()

            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.code)
            val fix = assertNotNull(error.fix)
            assertTrue(fix.contains("65536 bytes"), "fix: $fix")
            assertFalse(fix.contains("bytes bytes"), "fix: $fix")
            assertEquals("Reduce the payload to at most 65536 bytes.", fix)
        }

    @Test
    fun `S14 the cap counts UTF-8 bytes not characters`(): Unit =
        runBlocking {
            val svc = service()
            val euro = "\u20AC" // 3 bytes in UTF-8
            val atCap = euro.repeat(21845) + "a" // 65535 + 1 = 65536 bytes, 21846 chars
            val overCap = euro.repeat(21845) + "ab" // 65537 bytes, 21847 chars

            assertEquals(atCap, svc.prepare(plainItem(), "k", "work", atCap).ok().body)

            val error = svc.prepare(plainItem(), "k", "work", overCap).err()
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.code)
            assertEquals(65537L, (error.detail as ErrorDetail.PayloadTooLarge).actual)

            val fewCharsManyBytes = euro.repeat(21846) // 65538 bytes but only 21846 chars (< 65536)
            assertTrue(fewCharsManyBytes.length < 65536)
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, svc.prepare(plainItem(), "k", "work", fewCharsManyBytes).err().code)
        }

    @Test
    fun `S13 the cap is measured on the raw body before CRLF normalization`(): Unit =
        runBlocking {
            val svc = service()
            val rawAtCap = "\r\n".repeat(32768) // 65536 raw bytes, 32768 normalized
            val rawOverCap = "\r\n".repeat(32768) + "a" // 65537 raw bytes, 32769 normalized

            assertEquals("\n".repeat(32768), svc.prepare(plainItem(), "k", "work", rawAtCap).ok().body)
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, svc.prepare(plainItem(), "k", "work", rawOverCap).err().code)
        }

    @Test
    fun `S13 the byte cap rejects even when note_limits mode is warn and the key is off-schema`(): Unit =
        runBlocking {
            val svc = service(schema(globalMode = "warn"))

            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, svc.prepare(limItem(), "other", "work", "a".repeat(65537)).err().code)
        }

    // ---------------------------------------------------------------------------------------------
    // TS3 CRLF
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 CRLF becomes LF and LF-only text is unchanged`(): Unit =
        runBlocking {
            val svc = service()

            assertEquals("a\nb\n", svc.prepare(plainItem(), "k", "work", "a\r\nb\r\n").ok().body)
            assertEquals("a\nb\n", svc.prepare(plainItem(), "k", "work", "a\nb\n").ok().body)
        }

    @Test
    fun `S2 probe a lone CR is kept and the replacement is a single pass`(): Unit =
        runBlocking {
            val svc = service()

            assertEquals("a\rb", svc.prepare(plainItem(), "k", "work", "a\rb").ok().body)
            // "\r" + "\r\n" -> "\r" + "\n": only the CRLF pair is replaced, the extra CR is kept.
            assertEquals("a\r\nb", svc.prepare(plainItem(), "k", "work", "a\r\r\nb").ok().body)
        }

    @Test
    fun `S2 probe empty body is accepted as an empty body`(): Unit =
        runBlocking {
            val prepared = service().prepare(plainItem(), "k", "work", "").ok()

            assertEquals("", prepared.body)
            assertNull(prepared.warning)
        }

    // ---------------------------------------------------------------------------------------------
    // TS4 schema role
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 a schema-declared key with the wrong role is SCHEMA_VIOLATION`(): Unit =
        runBlocking {
            val svc = service()

            for (wrong in listOf("queue", "review")) {
                assertEquals(ErrorCode.SCHEMA_VIOLATION, svc.prepare(limItem(), "lim", wrong, "ok").err().code, "role $wrong")
            }
            assertEquals("work", svc.prepare(limItem(), "lim", "work", "ok").ok().role)
        }

    @Test
    fun `S1 the schema role is compared after role normalization so WORK satisfies a work schema key`(): Unit =
        runBlocking {
            val prepared = service().prepare(limItem(), "lim", "WORK", "ok").ok()

            assertEquals("work", prepared.role)
        }

    @Test
    fun `S11 off-schema keys on a schema item and any key on a schema-free item are unconstrained`(): Unit =
        runBlocking {
            val svc = service()

            assertEquals("queue", svc.prepare(limItem(), "other", "queue", "b").ok().role)
            assertEquals("review", svc.prepare(plainItem(), "lim", "review", "b").ok().role)
        }

    @Test
    fun `S11 probe schema keys are case-sensitive so LIM is off-schema and takes any role`(): Unit =
        runBlocking {
            val prepared = service().prepare(limItem(), "LIM", "queue", "x".repeat(50)).ok()

            assertEquals("queue", prepared.role)
            assertNull(prepared.warning, "an off-schema key has no maxLength")
        }

    // ---------------------------------------------------------------------------------------------
    // TS5 maxLength
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 warn mode accepts an over-length body with a warning naming key limit and actual`(): Unit =
        runBlocking {
            val prepared = service(schema(globalMode = "warn")).prepare(limItem(), "lim", "work", "x".repeat(11)).ok()

            assertEquals("x".repeat(11), prepared.body)
            assertEquals(NoteLengthWarning(key = "lim", maxLength = 10, actualLength = 11), prepared.warning)
            val message = prepared.warning!!.message()
            assertTrue("lim" in message && "10" in message && "11" in message, "message: $message")
        }

    @Test
    fun `S5 probe a body exactly at maxLength carries no warning and 11 does`(): Unit =
        runBlocking {
            val svc = service(schema(globalMode = "warn"))

            assertNull(svc.prepare(limItem(), "lim", "work", "x".repeat(10)).ok().warning)
            assertNotNull(svc.prepare(limItem(), "lim", "work", "x".repeat(11)).ok().warning)
        }

    @Test
    fun `S7 reject mode returns NOTE_TOO_LONG with key max and actual and 10 chars still passes`(): Unit =
        runBlocking {
            val svc = service(schema(globalMode = "reject"))

            val error = svc.prepare(limItem(), "lim", "work", "x".repeat(11)).err()
            assertEquals(ErrorCode.NOTE_TOO_LONG, error.code)
            val detail = error.detail
            assertTrue(detail is ErrorDetail.NoteTooLong, "detail: $detail")
            assertEquals("lim", detail.key)
            assertEquals(10, detail.max)
            assertEquals(11, detail.actual)

            assertNull(svc.prepare(limItem(), "lim", "work", "x".repeat(10)).ok().warning)
        }

    @Test
    fun `S21 maxLength is measured after CRLF normalization`(): Unit =
        runBlocking {
            val svc = service(schema(globalMode = "reject"))
            val raw = "ab\r\ncdef\r\ngh" // 12 raw chars, 10 once each CRLF is one LF

            assertEquals(12, raw.length)
            val prepared = svc.prepare(limItem(), "lim", "work", raw).ok()

            assertEquals("ab\ncdef\ngh", prepared.body)
            assertEquals(10, prepared.body.length)
            assertNull(prepared.warning)
        }

    @Test
    fun `S5 a key without a maxLength never warns or rejects regardless of length`(): Unit =
        runBlocking {
            val noLimit = schema(globalMode = "reject", entries = listOf(NoteSchemaEntry(key = "free", role = Role.WORK)))
            val prepared = service(noLimit).prepare(limItem(), "free", "work", "x".repeat(5000)).ok()

            assertNull(prepared.warning)
        }

    @Test
    fun `S8 per-root note_limits layering - per-root reject over global warn and per-root explicit warn over global reject`(): Unit =
        runBlocking {
            val repos = db.repositoryProvider()
            val strictRoot = persist(WorkItem(title = "strict root"))
            val laxRoot = persist(WorkItem(title = "lax root"))
            val bareRoot = persist(WorkItem(title = "bare root"))
            repos.projectConfigRepository().upsert(strictRoot.id, "note_limits:\n  mode: reject\n")
            repos.projectConfigRepository().upsert(laxRoot.id, "note_limits:\n  mode: warn\n")
            val perRoot = PerRootConfigService(repos.projectConfigRepository())
            val over = "x".repeat(11)

            val globalWarn = service(schema(globalMode = "warn"), perRoot)
            assertEquals(
                ErrorCode.NOTE_TOO_LONG,
                globalWarn.prepare(limItem(strictRoot.id), "lim", "work", over).err().code,
                "per-root reject beats global warn"
            )
            assertNotNull(
                globalWarn.prepare(limItem(bareRoot.id), "lim", "work", over).ok().warning,
                "no per-root value falls back to the global warn"
            )

            val globalReject = service(schema(globalMode = "reject"), perRoot)
            assertNotNull(
                globalReject.prepare(limItem(laxRoot.id), "lim", "work", over).ok().warning,
                "an explicit per-root warn beats global reject"
            )
            assertEquals(
                ErrorCode.NOTE_TOO_LONG,
                globalReject.prepare(limItem(bareRoot.id), "lim", "work", over).err().code,
                "no per-root value falls back to the global reject"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // ordering of the five rules
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `order - an invalid role wins over an over-cap body`(): Unit =
        runBlocking {
            val error = service().prepare(plainItem(), "k", "done", "a".repeat(65537)).err()

            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
        }

    @Test
    fun `order - the byte cap wins over a schema-role violation`(): Unit =
        runBlocking {
            val error = service().prepare(limItem(), "lim", "queue", "a".repeat(65537)).err()

            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.code)
        }

    @Test
    fun `order - a schema-role violation wins over a reject-mode maxLength overflow`(): Unit =
        runBlocking {
            val error = service(schema(globalMode = "reject")).prepare(limItem(), "lim", "queue", "x".repeat(11)).err()

            assertEquals(ErrorCode.SCHEMA_VIOLATION, error.code)
        }

    // ---------------------------------------------------------------------------------------------
    // config unavailable propagates as an exception, not an Outcome
    // ---------------------------------------------------------------------------------------------

    private object ThrowingPerRoot : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? =
            throw PerRootConfigUnavailableException(rootId, "per-root config unreadable")
    }

    @Test
    fun `S17 prepare lets PerRootConfigUnavailableException propagate for an item with a root`(): Unit =
        runBlocking {
            val root = persist(WorkItem(title = "failing root"))
            val svc = service(perRoot = ThrowingPerRoot)

            assertFailsWith<PerRootConfigUnavailableException> {
                svc.prepare(limItem(root.id), "lim", "work", "ok")
            }
        }

    @Test
    fun `S17 upsert lets PerRootConfigUnavailableException propagate and stores nothing`(): Unit =
        runBlocking {
            val root = persist(WorkItem(title = "failing root"))
            val item = persist(limItem(root.id))
            val svc = service(perRoot = ThrowingPerRoot)

            assertFailsWith<PerRootConfigUnavailableException> {
                svc.upsert(NoteUpsertCommand(item.id, "lim", "work", "ok", actorClaim = null, verification = null))
            }
            assertNull(db.repositoryProvider().noteRepository().findByItemIdAndKey(item.id, "lim"))
        }

    @Test
    fun `S17 control - an item without a rootId never consults the per-root source`(): Unit =
        runBlocking {
            val svc = service(perRoot = ThrowingPerRoot)

            assertEquals("work", svc.prepare(plainItem(), "k", "work", "ok").ok().role)
        }

    // ---------------------------------------------------------------------------------------------
    // upsert
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `upsert on a missing item is NOT_FOUND`(): Unit =
        runBlocking {
            val error = service().upsert(NoteUpsertCommand(UUID.randomUUID(), "k", "work", "b", null, null)).err()

            assertEquals(ErrorCode.NOT_FOUND, error.code)
        }

    @Test
    fun `S1 upsert stores the normalized role and LF body and reports created then updated`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            val claim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT)

            val first = svc.upsert(NoteUpsertCommand(item.id, "k", "Work", "x\r\ny", claim, null)).ok()
            assertTrue(first.created)
            assertEquals("work", first.note.role)
            assertEquals("x\ny", first.note.body)
            assertNull(first.warning)

            val stored = db.repositoryProvider().noteRepository().findByItemIdAndKey(item.id, "k")
            assertNotNull(stored)
            assertEquals("work", stored.role)
            assertEquals("x\ny", stored.body)
            assertEquals("agent-1", stored.actorClaim?.id)

            val second = svc.upsert(NoteUpsertCommand(item.id, "k", "WORK", "z", claim, null)).ok()
            assertTrue(!second.created, "re-upsert of the same (item, key) is an update")
            assertEquals(first.note.id, second.note.id)
            assertEquals(
                1,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemId(item.id)
                    .size
            )
            assertEquals(
                "z",
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemIdAndKey(item.id, "k")!!
                    .body
            )
        }

    @Test
    fun `S5 upsert in warn mode stores the over-length note and returns the warning`(): Unit =
        runBlocking {
            val item = persist(limItem())

            val result =
                service(
                    schema(globalMode = "warn")
                ).upsert(NoteUpsertCommand(item.id, "lim", "work", "x".repeat(11), null, null)).ok()

            assertEquals(NoteLengthWarning("lim", 10, 11), result.warning)
            assertEquals(
                "x".repeat(11),
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemIdAndKey(item.id, "lim")!!
                    .body
            )
        }

    @Test
    fun `S7 a rejected upsert stores nothing and leaves an existing note untouched`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val repos = db.repositoryProvider()
            repos.noteRepository().upsert(Note(itemId = item.id, key = "lim", role = "work", body = "orig"))

            val tooLong =
                service(
                    schema(globalMode = "reject")
                ).upsert(NoteUpsertCommand(item.id, "lim", "work", "x".repeat(11), null, null))
            val wrongRole = service().upsert(NoteUpsertCommand(item.id, "lim", "queue", "new", null, null))
            val overCap = service().upsert(NoteUpsertCommand(item.id, "lim", "work", "a".repeat(65537), null, null))

            assertEquals(ErrorCode.NOTE_TOO_LONG, tooLong.err().code)
            assertEquals(ErrorCode.SCHEMA_VIOLATION, wrongRole.err().code)
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, overCap.err().code)
            assertEquals("orig", repos.noteRepository().findByItemIdAndKey(item.id, "lim")!!.body)
        }

    // ---------------------------------------------------------------------------------------------
    // deletes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `deleteById removes an existing note and reports false for an unknown id`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val repos = db.repositoryProvider()
            val note = repos.noteRepository().upsert(Note(itemId = item.id, key = "gone", role = "work", body = "b"))
            val svc = service()

            assertEquals(true, svc.deleteById(note.id).ok())
            assertNull(repos.noteRepository().findByItemIdAndKey(item.id, "gone"))
            assertEquals(false, svc.deleteById(UUID.randomUUID()).ok())
        }

    @Test
    fun `deleteByKey returns the deleted note and null when the key is absent`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val repos = db.repositoryProvider()
            repos.noteRepository().upsert(Note(itemId = item.id, key = "a", role = "work", body = "A"))
            repos.noteRepository().upsert(Note(itemId = item.id, key = "b", role = "work", body = "B"))
            val svc = service()

            val deleted = svc.deleteByKey(item.id, "a").ok()
            assertNotNull(deleted)
            assertEquals("a", deleted.key)
            assertNull(repos.noteRepository().findByItemIdAndKey(item.id, "a"))
            assertNotNull(repos.noteRepository().findByItemIdAndKey(item.id, "b"), "only the named key is deleted")
            assertNull(svc.deleteByKey(item.id, "a").ok(), "second delete finds nothing")
            assertNull(svc.deleteByKey(item.id, "A").ok(), "keys are case-sensitive")
        }

    @Test
    fun `deleteAllForItem returns the count and removes only that item's notes`(): Unit =
        runBlocking {
            val one = persist(WorkItem(title = "one"))
            val other = persist(WorkItem(title = "other"))
            val repos = db.repositoryProvider()
            repos.noteRepository().upsert(Note(itemId = one.id, key = "a", role = "work", body = "A"))
            repos.noteRepository().upsert(Note(itemId = one.id, key = "b", role = "queue", body = "B"))
            repos.noteRepository().upsert(Note(itemId = other.id, key = "a", role = "work", body = "A"))
            val svc = service()

            assertEquals(2, svc.deleteAllForItem(one.id).ok())
            assertTrue(repos.noteRepository().findByItemId(one.id).isEmpty())
            assertEquals(1, repos.noteRepository().findByItemId(other.id).size)
            assertEquals(0, svc.deleteAllForItem(one.id).ok())
        }
}

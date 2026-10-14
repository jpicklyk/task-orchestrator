package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    ): ConfigDocument = TestConfigResolver.document(mapOf(limTag to entries)).copy(noteLimitsMode = globalMode)

    private fun context(
        schema: ConfigDocument = schema(),
        perRoot: PerRootConfigSource? = null
    ) = ToolExecutionContext(
        db.repositoryProvider(),
        perRootConfigService = perRoot,
        configResolver = TestConfigResolver.resolver(schema, perRoot),
        unitOfWork = db.unitOfWork()
    )

    private fun service(
        schema: ConfigDocument = schema(),
        perRoot: PerRootConfigSource? = null
    ): NoteCommandService = context(schema, perRoot).noteCommandService

    private fun limItem(rootId: UUID? = null) = WorkItem(title = "lim item", tags = limTag, type = limTag, rootId = rootId)

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
            repos.configStore().upsert(strictRoot.id, "note_limits:\n  mode: reject\n")
            repos.configStore().upsert(laxRoot.id, "note_limits:\n  mode: warn\n")
            val perRoot = PerRootConfigService(repos.configStore())
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
                svc.upsert(NoteUpsertCommand(item.id, "lim", "work", "ok", actorClaim = null, verification = null, caller = null))
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
            val error = service().upsert(NoteUpsertCommand(UUID.randomUUID(), "k", "work", "b", null, null, caller = null)).err()

            assertEquals(ErrorCode.NOT_FOUND, error.code)
        }

    @Test
    fun `S1 upsert stores the normalized role and LF body and reports created then updated`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            val claim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT)

            // R21: a note stamped with an actor is owned by it, so the rewrite below must come from the same principal
            // (the caller identity is the claim id) or it would be note_owned_by_other.
            val owner = ClaimHolder("agent-1")
            val first = svc.upsert(NoteUpsertCommand(item.id, "k", "Work", "x\r\ny", claim, null, caller = owner)).ok()
            assertTrue(first.created)
            assertEquals("work", first.note.role)
            assertEquals("x\ny", first.note.body)
            assertNull(first.warning)

            val stored = db.repositoryProvider().noteRepository().findByItemIdAndKey(item.id, "k")
            assertNotNull(stored)
            assertEquals("work", stored.role)
            assertEquals("x\ny", stored.body)
            assertEquals("agent-1", stored.actorClaim?.id)

            val second = svc.upsert(NoteUpsertCommand(item.id, "k", "WORK", "z", claim, null, caller = owner)).ok()
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
                ).upsert(NoteUpsertCommand(item.id, "lim", "work", "x".repeat(11), null, null, caller = null)).ok()

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
                ).upsert(NoteUpsertCommand(item.id, "lim", "work", "x".repeat(11), null, null, caller = null))
            val wrongRole = service().upsert(NoteUpsertCommand(item.id, "lim", "queue", "new", null, null, caller = null))
            val overCap = service().upsert(NoteUpsertCommand(item.id, "lim", "work", "a".repeat(65537), null, null, caller = null))

            assertEquals(ErrorCode.NOTE_TOO_LONG, tooLong.err().code)
            assertEquals(ErrorCode.SCHEMA_VIOLATION, wrongRole.err().code)
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, overCap.err().code)
            assertEquals("orig", repos.noteRepository().findByItemIdAndKey(item.id, "lim")!!.body)
        }

    // ---------------------------------------------------------------------------------------------
    // deletes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `delete returns the deleted note and is NOT_FOUND when the key is absent`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val repos = db.repositoryProvider()
            repos.noteRepository().upsert(Note(itemId = item.id, key = "a", role = "work", body = "A"))
            repos.noteRepository().upsert(Note(itemId = item.id, key = "b", role = "work", body = "B"))
            val svc = service()

            val deleted = svc.delete(item.id, "a", caller = null).ok()
            assertEquals("a", deleted.key)
            assertNull(repos.noteRepository().findByItemIdAndKey(item.id, "a"))
            assertNotNull(repos.noteRepository().findByItemIdAndKey(item.id, "b"), "only the named key is deleted")

            val again = svc.delete(item.id, "a", caller = null).err()
            assertEquals(ErrorCode.NOT_FOUND, again.code)
            assertEquals(ErrorDetail.NotFound(EntityKind.NOTE, "${item.id}/a"), again.detail)
            assertEquals(ErrorCode.NOT_FOUND, svc.delete(item.id, "A", caller = null).err().code, "keys are case-sensitive")
            val noItem = svc.delete(UUID(0, 1), "a", caller = null).err()
            assertEquals(ErrorDetail.NotFound(EntityKind.ITEM, UUID(0, 1).toString()), noItem.detail)
        }

    @Test
    fun `delete honours expectedVersion and keeps the note on a conflict`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val repos = db.repositoryProvider()
            repos.noteRepository().upsert(Note(itemId = item.id, key = "a", role = "work", body = "A"))
            val svc = service()

            val conflict = svc.delete(item.id, "a", expectedVersion = 5, caller = null).err()
            assertEquals(ErrorCode.VERSION_CONFLICT, conflict.code)
            assertNotNull(repos.noteRepository().findByItemIdAndKey(item.id, "a"), "a stale delete leaves the note")

            assertEquals("a", svc.delete(item.id, "a", expectedVersion = 1, caller = null).ok().key)
            assertNull(repos.noteRepository().findByItemIdAndKey(item.id, "a"))
        }

    // ---------------------------------------------------------------------------------------------
    // note version (V28): 1 on create, +1 per upsert, expectedVersion as a compare-and-set
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `version is 1 on create and goes up by one on every upsert including an identical body`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()

            fun cmd(
                body: String,
                expected: Long? = null
            ) = NoteUpsertCommand(item.id, "k", "work", body, null, null, expectedVersion = expected, caller = null)

            assertEquals(
                1L,
                svc
                    .upsert(cmd("one"))
                    .ok()
                    .note.version
            )
            assertEquals(
                2L,
                svc
                    .upsert(cmd("one"))
                    .ok()
                    .note.version
            )
            assertEquals(
                3L,
                svc
                    .upsert(cmd("two"))
                    .ok()
                    .note.version
            )
            assertEquals(
                3L,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemIdAndKey(item.id, "k")!!
                    .version
            )
        }

    @Test
    fun `expectedVersion is null unconditional, 0 must create, n must match`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()

            fun cmd(expected: Long?) = NoteUpsertCommand(item.id, "k", "work", "b", null, null, expectedVersion = expected, caller = null)

            // 0 on a missing note creates; n on a missing note conflicts with actual 0
            val missing = svc.upsert(cmd(3)).err()
            assertEquals(ErrorCode.VERSION_CONFLICT, missing.code)
            assertEquals(ErrorDetail.VersionConflict(EntityKind.NOTE, "${item.id}/k", 3, 0), missing.detail)
            assertEquals(
                1L,
                svc
                    .upsert(cmd(0))
                    .ok()
                    .note.version
            )

            // 0 on an existing note conflicts; the right n wins and bumps; a stale n conflicts and writes nothing
            val existing = svc.upsert(cmd(0)).err()
            assertEquals(
                ErrorDetail.VersionConflict(
                    EntityKind.NOTE,
                    db
                        .repositoryProvider()
                        .noteRepository()
                        .findByItemIdAndKey(item.id, "k")!!
                        .id
                        .toString(),
                    0,
                    1
                ),
                existing.detail
            )
            assertEquals(
                2L,
                svc
                    .upsert(cmd(1))
                    .ok()
                    .note.version
            )
            assertEquals(ErrorCode.VERSION_CONFLICT, svc.upsert(cmd(1)).err().code)
            assertEquals(
                2L,
                db
                    .repositoryProvider()
                    .noteRepository()
                    .findByItemIdAndKey(item.id, "k")!!
                    .version
            )
        }

    // ---------------------------------------------------------------------------------------------
    // R21 note ownership (item 190f1bdd, test-author seat). Oracle: frozen task-scope "What to build" 2-3,
    // D1-D8 and AC2-AC4, AC7; scenario ids are the test-plan's (S2-S13). The caller identity is
    // ClaimHolder.principalId (D2) and the owner is the stored actor claim id (D1).
    // ---------------------------------------------------------------------------------------------

    private val ownerA = "owner-zq7x"
    private val callerB = "caller-w4k9"
    private val adminId = "admin-p2m6"

    private fun ownershipSchema(): ConfigDocument =
        schema(
            entries =
                listOf(
                    NoteSchemaEntry(key = "lim", role = Role.WORK, maxLength = 10),
                    NoteSchemaEntry(key = "sh", role = Role.WORK, shared = true),
                    NoteSchemaEntry(key = "own", role = Role.WORK, shared = false)
                )
        )

    private fun write(
        item: WorkItem,
        key: String,
        who: String?,
        body: String = "body",
        role: String = "work",
        takeover: Boolean = false,
        takeoverAllowed: Boolean = false,
        assignment: UUID? = null,
        expectedVersion: Long? = null
    ) = NoteUpsertCommand(
        itemId = item.id,
        key = key,
        role = role,
        body = body,
        actorClaim = who?.let { ActorClaim(id = it, kind = ActorKind.SUBAGENT) },
        verification = null,
        caller = who?.let { ClaimHolder(it, assignment) },
        expectedVersion = expectedVersion,
        takeover = takeover,
        takeoverAllowed = takeoverAllowed
    )

    private suspend fun stored(
        item: WorkItem,
        key: String
    ): Note? = db.repositoryProvider().noteRepository().findByItemIdAndKey(item.id, key)

    private fun <T> Outcome<T>.assertOwnedByOther(
        item: WorkItem,
        key: String,
        label: String
    ) {
        val error = err()
        assertEquals(ErrorCode.NOTE_OWNED_BY_OTHER, error.code, "$label: ${error.message}")
        assertEquals(ErrorDetail.NoteOwnedByOther(item.id, key, null), error.detail, label)
        assertFalse(ownerA in error.message, "$label: the owner id is in no message: ${error.message}")
        assertFalse(ownerA in error.detail.toString(), "$label: the owner id is in no detail")
        assertFalse(error.fix?.contains(ownerA) == true, "$label: the owner id is in no fix: ${error.fix}")
        assertTrue(error.fixArgs.values.none { ownerA in it }, "$label: the owner id is in no fix argument: ${error.fixArgs}")
    }

    @Test
    fun `R21 S2 the first writer rewrites and deletes its own note`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())

            assertTrue(svc.upsert(write(item, "own", ownerA)).ok().created)
            assertEquals(
                2L,
                svc
                    .upsert(write(item, "own", ownerA, body = "v2"))
                    .ok()
                    .note.version
            )
            assertEquals("own", svc.delete(item.id, "own", null, ClaimHolder(ownerA)).ok().key)
            assertNull(stored(item, "own"))
        }

    @Test
    fun `R21 S3 another principal cannot upsert or delete a non-shared note - nothing changes and the owner id leaks nowhere`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())
            svc.upsert(write(item, "own", ownerA, body = "original")).ok()

            svc.upsert(write(item, "own", callerB, body = "hijack")).assertOwnedByOther(item, "own", "upsert")
            svc.delete(item.id, "own", null, ClaimHolder(callerB)).assertOwnedByOther(item, "own", "delete")

            val row = assertNotNull(stored(item, "own"), "the refused delete kept the note")
            assertEquals("original", row.body)
            assertEquals(1L, row.version, "the refused upsert did not bump the version")
            assertEquals(ownerA, row.actorClaim?.id, "the refused upsert did not re-stamp the actor")
            // control: the owner's identical call is accepted, so the refusals above are the ownership rule
            assertEquals(
                2L,
                svc
                    .upsert(write(item, "own", ownerA, body = "by owner"))
                    .ok()
                    .note.version
            )
        }

    @Test
    fun `R21 S3 an off-schema key and a schema-free item are non-shared too`(): Unit =
        runBlocking {
            val schemaItem = persist(limItem())
            val plain = persist(plainItem())
            val svc = service(ownershipSchema())
            svc.upsert(write(schemaItem, "ghost-key", ownerA)).ok()
            svc.upsert(write(plain, "any", ownerA)).ok()

            svc.upsert(write(schemaItem, "ghost-key", callerB)).assertOwnedByOther(schemaItem, "ghost-key", "off-schema key")
            svc.upsert(write(plain, "any", callerB)).assertOwnedByOther(plain, "any", "schema-free item")
        }

    @Test
    fun `R21 S3 a null caller on an owned note is refused, on an unowned or missing note allowed`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            svc.upsert(write(item, "owned", ownerA)).ok()
            db
                .repositoryProvider()
                .noteRepository()
                .upsert(Note(itemId = item.id, key = "legacy", role = "work", body = "orig"))

            svc
                .upsert(NoteUpsertCommand(item.id, "owned", "work", "x", null, null, caller = null))
                .assertOwnedByOther(item, "owned", "null caller, upsert")
            svc.delete(item.id, "owned", null, null).assertOwnedByOther(item, "owned", "null caller, delete")
            assertNotNull(stored(item, "owned"))

            assertTrue(
                svc
                    .upsert(NoteUpsertCommand(item.id, "legacy", "work", "x", null, null, caller = null))
                    .ok()
                    .note.version == 2L
            )
            assertTrue(svc.upsert(NoteUpsertCommand(item.id, "fresh", "work", "x", null, null, caller = null)).ok().created)
        }

    @Test
    fun `R21 S3 probe an owner id that differs by case or whitespace is a different principal`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            svc.upsert(write(item, "k", "Agent-One")).ok()

            for (spelling in listOf("agent-one", "AGENT-ONE", "Agent-One ", " Agent-One")) {
                val error = svc.upsert(write(item, "k", spelling)).err()
                assertEquals(ErrorCode.NOTE_OWNED_BY_OTHER, error.code, "spelling [$spelling]")
            }
            assertEquals("Agent-One", stored(item, "k")?.actorClaim?.id)
            assertEquals(
                2L,
                svc
                    .upsert(write(item, "k", "Agent-One"))
                    .ok()
                    .note.version,
                "control: the exact spelling"
            )
        }

    @Test
    fun `R21 S4 a shared key is last-writer-wins - a second principal rewrites it and becomes its actor, and may delete it`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())
            svc.upsert(write(item, "sh", ownerA, body = "first")).ok()

            val second = svc.upsert(write(item, "sh", callerB, body = "second")).ok()

            assertEquals(2L, second.note.version)
            assertEquals(callerB, stored(item, "sh")?.actorClaim?.id, "on a shared key the actor is the last writer")
            assertEquals("second", stored(item, "sh")?.body)
            assertEquals("sh", svc.delete(item.id, "sh", null, ClaimHolder(ownerA)).ok().key, "the former owner may delete it now too")
            assertNull(stored(item, "sh"))
        }

    @Test
    fun `R21 S5 a NULL-actor note is unowned - the first actor-stamped writer owns it afterwards`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            val notes = db.repositoryProvider().noteRepository()
            notes.upsert(Note(itemId = item.id, key = "legacy", role = "work", body = "orig"))
            notes.upsert(Note(itemId = item.id, key = "legacy-del", role = "work", body = "orig"))

            svc.upsert(write(item, "legacy", callerB, body = "claimed")).ok()
            assertEquals(callerB, stored(item, "legacy")?.actorClaim?.id)
            svc.upsert(write(item, "legacy", "third-party")).assertOwnedByOther(item, "legacy", "after B stamped it")
            assertEquals("legacy-del", svc.delete(item.id, "legacy-del", null, ClaimHolder("anyone")).ok().key)
            assertNull(stored(item, "legacy-del"))
        }

    @Test
    fun `R21 S6 an ADMIN takeover writes another principal's note, stamps the admin as actor, and moves ownership`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            svc.upsert(write(item, "k", ownerA, body = "original")).ok()

            val taken = svc.upsert(write(item, "k", adminId, body = "taken", takeover = true, takeoverAllowed = true)).ok()

            assertEquals(2L, taken.note.version)
            val row = assertNotNull(stored(item, "k"))
            assertEquals("taken", row.body)
            assertEquals(adminId, row.actorClaim?.id)
            // ownership moved: the former owner is now the non-owner
            svc.upsert(write(item, "k", ownerA, body = "back")).assertOwnedByOther(item, "k", "former owner after the takeover")
            assertEquals("taken", stored(item, "k")?.body)
        }

    @Test
    fun `R21 S7 takeover without the capability is forbidden capability admin and writes nothing`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            svc.upsert(write(item, "foreign", ownerA, body = "original")).ok()
            svc.upsert(write(item, "mine", callerB, body = "mine")).ok()

            for (key in listOf("foreign", "mine", "missing")) {
                val error = svc.upsert(write(item, key, callerB, body = "TAKEN", takeover = true, takeoverAllowed = false)).err()
                assertEquals(ErrorCode.FORBIDDEN, error.code, key)
                assertEquals(ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, "admin"), error.detail, key)
            }
            assertEquals("original", stored(item, "foreign")?.body)
            assertEquals(ownerA, stored(item, "foreign")?.actorClaim?.id)
            assertEquals("mine", stored(item, "mine")?.body)
            assertNull(stored(item, "missing"))
            // control: with the capability the same own-note write goes through
            assertEquals(
                2L,
                svc
                    .upsert(write(item, "mine", callerB, body = "ok", takeover = true, takeoverAllowed = true))
                    .ok()
                    .note.version
            )
        }

    @Test
    fun `R21 S8 takeover under a seat assignment is invalid_request at slash takeover and writes nothing`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            svc.upsert(write(item, "k", ownerA, body = "original")).ok()

            val error =
                svc
                    .upsert(
                        write(item, "k", adminId, body = "TAKEN", takeover = true, takeoverAllowed = true, assignment = UUID.randomUUID())
                    ).err()

            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            assertEquals("/takeover", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field)
            assertEquals("original", stored(item, "k")?.body)
            assertEquals(ownerA, stored(item, "k")?.actorClaim?.id)
            // control: the same call without an assignment takes the note over
            assertEquals(
                2L,
                svc
                    .upsert(write(item, "k", adminId, body = "TAKEN", takeover = true, takeoverAllowed = true))
                    .ok()
                    .note.version
            )
        }

    @Test
    fun `R21 S9 an ADMIN takeover where no transfer is needed is an ordinary write`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())
            svc.upsert(write(item, "own", adminId)).ok()
            svc.upsert(write(item, "sh", ownerA)).ok()

            for (key in listOf("own", "sh", "fresh")) {
                val result = svc.upsert(write(item, key, adminId, takeover = true, takeoverAllowed = true)).ok()
                assertEquals(adminId, stored(item, key)?.actorClaim?.id, key)
                assertTrue(result.note.version >= 1L, key)
            }
        }

    @Test
    fun `R21 S11 order - ownership precedes the version compare, body policy and the claim fence precede ownership`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())
            svc.upsert(write(item, "own", ownerA)).ok()

            svc.upsert(write(item, "own", callerB, expectedVersion = 99)).assertOwnedByOther(item, "own", "stale expectedVersion")
            assertEquals(
                ErrorCode.PAYLOAD_TOO_LARGE,
                svc.upsert(write(item, "own", callerB, body = "a".repeat(65537))).err().code,
                "a body over the cap is a body policy error and wins"
            )
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                svc.upsert(write(item, "own", callerB, role = "bogus")).err().code,
                "an invalid role is a body policy error and wins"
            )
            // control: the owner with the same stale version gets the version conflict, so the order is observable
            assertEquals(ErrorCode.VERSION_CONFLICT, svc.upsert(write(item, "own", ownerA, expectedVersion = 99)).err().code)

            db.repositoryProvider().workItemRepository().claim(item.id, "claimer-c", ttlSeconds = 900)
            assertEquals(
                ErrorCode.NOT_CLAIM_HOLDER,
                svc.upsert(write(item, "own", callerB)).err().code,
                "a non-holder non-owner on a claimed item is fenced first"
            )
        }

    @Test
    fun `R21 S13 delete resolves the schema - an unreadable per-root config propagates and the note is kept`(): Unit =
        runBlocking {
            val root = persist(WorkItem(title = "failing root"))
            val item = persist(limItem(root.id))
            val plain = persist(plainItem())
            val notes = db.repositoryProvider().noteRepository()
            notes.upsert(Note(itemId = item.id, key = "lim", role = "work", body = "keep me"))
            notes.upsert(Note(itemId = plain.id, key = "k", role = "work", body = "control"))
            val svc = service(perRoot = ThrowingPerRoot)

            assertFailsWith<PerRootConfigUnavailableException> { svc.delete(item.id, "lim", null, null) }
            assertEquals("keep me", stored(item, "lim")?.body, "nothing was deleted")
            // control: an item without a root never consults the per-root source, so the same call shape deletes
            assertEquals("k", svc.delete(plain.id, "k", null, null).ok().key)
        }

    @Test
    fun `R21 probe a per-root shared true overrides a global non-shared declaration for the items of that root only`(): Unit =
        runBlocking {
            val repos = db.repositoryProvider()
            val sharedRoot = persist(WorkItem(title = "shared root"))
            val bareRoot = persist(WorkItem(title = "bare root"))
            repos.configStore().upsert(
                sharedRoot.id,
                "work_item_schemas:\n  $limTag:\n    notes:\n      - key: pr\n        role: work\n        shared: true\n"
            )
            val perRoot = PerRootConfigService(repos.configStore())
            val svc = service(schema(entries = listOf(NoteSchemaEntry(key = "pr", role = Role.WORK))), perRoot)
            val sharedItem = persist(limItem(sharedRoot.id))
            val bareItem = persist(limItem(bareRoot.id))
            svc.upsert(write(sharedItem, "pr", ownerA)).ok()
            svc.upsert(write(bareItem, "pr", ownerA)).ok()

            assertEquals(
                callerB,
                svc
                    .upsert(write(sharedItem, "pr", callerB))
                    .ok()
                    .note.actorClaim
                    ?.id,
                "per-root shared: true wins"
            )
            svc
                .upsert(
                    write(bareItem, "pr", callerB)
                ).assertOwnedByOther(bareItem, "pr", "no per-root declaration, the global non-shared applies")
        }

    // ---------------------------------------------------------------------------------------------
    // R21 decision Dprime (legacy notes): C1-C8 of decision.md Ruling 7. Oracle: Rulings 3 and 4. A legacy note is a
    // pre-4.0 row: a 3.x actor claim and a NULL owner_id. The owner is the principal of the first 4.0 write, never the actor.
    // ---------------------------------------------------------------------------------------------

    private val legacyActor = "planner:eadaaa4e"

    private suspend fun seedLegacy(
        item: WorkItem,
        key: String = "legacy"
    ) {
        db.repositoryProvider().noteRepository().upsert(
            Note(
                itemId = item.id,
                key = key,
                role = "work",
                body = "orig",
                actorClaim = ActorClaim(id = legacyActor, kind = ActorKind.SUBAGENT),
                ownerId = null
            )
        )
        val seeded = assertNotNull(stored(item, key), "fixture: the legacy note exists")
        assertNull(seeded.ownerId, "fixture: a legacy note has no owner")
        assertEquals(legacyActor, seeded.actorClaim?.id, "fixture: a legacy note keeps its 3.x actor")
    }

    private suspend fun <T> eventsOf(block: suspend () -> T): Pair<T, List<EventRecord>> {
        val events = db.repositoryProvider().eventStore()
        val mark = events.maxSeq()
        val result = block()
        return result to events.readAfter(mark, null, 100_000)
    }

    private fun transferPayload(
        item: WorkItem,
        key: String,
        from: String?,
        to: String,
        legacy: String? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", item.id.toString())
            put("key", key)
            if (from == null) put("from", JsonNull) else put("from", from)
            put("to", to)
            if (from == null) {
                if (legacy == null) put("legacyActor", JsonNull) else put("legacyActor", legacy)
            }
        }

    @Test
    fun `R21 C1 the first 4 0 upsert of a legacy note adopts it - owner and actor are the caller, version 2, adoption event first`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)

            val (result, events) = eventsOf { svc.upsert(write(item, "legacy", "api:t", body = "adopted")) }

            assertEquals(2L, result.ok().note.version)
            val row = assertNotNull(stored(item, "legacy"))
            assertEquals("api:t", row.ownerId)
            assertEquals("api:t", row.actorClaim?.id)
            assertEquals(2L, row.version)
            assertEquals("adopted", row.body)
            assertEquals(listOf("note.ownership_transferred", "note.upserted"), events.map { it.type })
            assertEquals(transferPayload(item, "legacy", from = null, to = "api:t", legacy = legacyActor), events.first().payload())
        }

    @Test
    fun `R21 C2 after an adoption another principal is note_owned_by_other - row unchanged, no events`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)
            svc.upsert(write(item, "legacy", "api:t", body = "adopted")).ok()

            val (result, events) = eventsOf { svc.upsert(write(item, "legacy", "api:u", body = "hijack")) }

            result.assertOwnedByOther(item, "legacy", "second principal after adoption")
            val row = assertNotNull(stored(item, "legacy"))
            assertEquals(2L, row.version)
            assertEquals("api:t", row.ownerId)
            assertEquals("api:t", row.actorClaim?.id)
            assertEquals("adopted", row.body)
            assertEquals(emptyList(), events.map { it.type }, "a refusal records no event")
        }

    @Test
    fun `R21 C3 the local principal adopts a legacy note without takeover - transfer payload from null to local`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)

            val (result, events) = eventsOf { svc.upsert(write(item, "legacy", "local", takeover = false, takeoverAllowed = false)) }

            result.ok()
            assertEquals("local", stored(item, "legacy")?.ownerId)
            assertEquals(listOf("note.ownership_transferred", "note.upserted"), events.map { it.type })
            assertEquals(transferPayload(item, "legacy", from = null, to = "local", legacy = legacyActor), events.first().payload())
        }

    @Test
    fun `R21 C4 an admin takeover of an adopted note moves the owner - payload has from and to and no legacyActor key`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)
            svc.upsert(write(item, "legacy", "api:t")).ok()

            val (result, events) =
                eventsOf { svc.upsert(write(item, "legacy", "api:admin", takeover = true, takeoverAllowed = true)) }

            result.ok()
            assertEquals("api:admin", stored(item, "legacy")?.ownerId)
            assertEquals(listOf("note.ownership_transferred", "note.upserted"), events.map { it.type })
            assertEquals(transferPayload(item, "legacy", from = "api:t", to = "api:admin"), events.first().payload())
            assertFalse("legacyActor" in events.first().payload(), "a takeover payload carries no legacyActor key")
        }

    @Test
    fun `R21 C5 a shared key keeps its creator as owner across a second writer and records no ownership event`(): Unit =
        runBlocking {
            val item = persist(limItem())
            val svc = service(ownershipSchema())

            val (results, events) =
                eventsOf {
                    listOf(svc.upsert(write(item, "sh", "api:a")), svc.upsert(write(item, "sh", "api:b")))
                }

            results.forEach { it.ok() }
            val row = assertNotNull(stored(item, "sh"))
            assertEquals("api:a", row.ownerId, "a shared-key write does not move ownership")
            assertEquals("api:b", row.actorClaim?.id, "attribution is last-writer-wins")
            assertEquals(listOf("note.upserted", "note.upserted"), events.map { it.type }, "no note.ownership_transferred at all")
        }

    @Test
    fun `R21 C6 a new note is owned by its creator and records only upserted`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()

            val (result, events) = eventsOf { svc.upsert(write(item, "fresh", "api:t")) }

            assertTrue(result.ok().created)
            assertEquals("api:t", stored(item, "fresh")?.ownerId)
            assertEquals(listOf("note.upserted"), events.map { it.type })
        }

    @Test
    fun `R21 C7 a delete of a legacy note by any caller removes it and records only deleted - a delete never takes ownership`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)

            val (result, events) = eventsOf { svc.delete(item.id, "legacy", null, ClaimHolder("api:u")) }

            assertEquals("legacy", result.ok().key)
            assertNull(stored(item, "legacy"))
            assertEquals(listOf("note.deleted"), events.map { it.type })
        }

    @Test
    fun `R21 C8 a legacy note on an item claimed by another is fenced - not_claim_holder, owner still NULL, no events`(): Unit =
        runBlocking {
            val item = persist(plainItem())
            val svc = service()
            seedLegacy(item)
            db.repositoryProvider().workItemRepository().claim(item.id, "api:h", ttlSeconds = 900)

            val (result, events) = eventsOf { svc.upsert(write(item, "legacy", "api:u")) }

            assertEquals(ErrorCode.NOT_CLAIM_HOLDER, result.err().code)
            val row = assertNotNull(stored(item, "legacy"))
            assertNull(row.ownerId)
            assertEquals(legacyActor, row.actorClaim?.id)
            assertEquals(1L, row.version)
            assertEquals(emptyList(), events.map { it.type })
            // control: the claim holder passes the fence on the same note and adopts it, so the fence above is what refused api:u
            svc.upsert(write(item, "legacy", "api:h")).ok()
            assertEquals("api:h", stored(item, "legacy")?.ownerId)
        }
}

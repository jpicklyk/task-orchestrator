package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `67ba3514` ([Q5]) -- [IdResolver] and [IdResolution.toOutcome].
 * Scenarios S1-S7 of the frozen `test-plan` note (all NEW-SURFACE). Q32 deleted S8 with the 3.x entry point it covered.
 *
 * Oracles:
 *  - S1: IdRef KDoc (a canonical 36-character UUID, any case) and `task-scope` item 1 (full UUID -> Resolved with
 *    NO store read; existence stays the handler's job).
 *  - S2: IdRef KDoc ("a dash-free hex prefix of 8..32 characters", any case).
 *  - S3: envelope `not_found` row: `not_found{kind: item, id: prefix}`.
 *  - S4: envelope `ambiguous_id`: candidates are ids only, ascending, at most 10 (`task-scope` item 1).
 *  - S5: IdRef.FORMAT_REASON and `invalid_request{fields[{field, reason, raw}]}` (`task-scope` item 1).
 *  - S6: envelope line 171 -- a store fault is rethrown, never reported as not_found; the cancellation-safety ratchet --
 *    a CancellationException is never wrapped.
 *  - S7: the hex-prefix definition: a prefix selects exactly the ids whose lowercase dash-free hex starts with it.
 *
 * BLINDNESS: authored from the frozen `task-scope`/`test-plan` notes and the orchestrator-supplied declarations block.
 * No `src/main` file, implementer diff or implementer note was opened. The harness is the pre-existing
 * `SqliteTestDatabase` + `SQLiteWorkItemRepository` (an ItemStore) and a strict MockK `ItemStore` (any unstubbed call
 * throws, which makes "zero store calls" observable: a call would surface as Failed, not Resolved).
 */
class IdResolverTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var repository: SQLiteWorkItemRepository
    private lateinit var resolver: IdResolver

    @BeforeEach
    fun setUp() {
        repository = SQLiteWorkItemRepository(sqliteDb.databaseManager)
        resolver = IdResolver(repository)
    }

    private fun seed(
        id: String,
        title: String = "item-$id",
    ): UUID {
        val uuid = UUID.fromString(id)
        runBlocking { repository.create(WorkItem(id = uuid, title = title)) }
        return uuid
    }

    private fun hex(id: UUID): String = id.toString().replace("-", "")

    private fun errorOf(outcome: Outcome<UUID>) = assertIs<Outcome.Err>(outcome, "expected Err but was $outcome").error

    // ============================================================================ S1 -- full UUID

    @Test
    fun `S1 a canonical UUID resolves with zero store calls, in either case and through every entry`(): Unit =
        runBlocking {
            val store = mockk<ItemStore>()
            val strict = IdResolver(store)
            val id = UUID.fromString("3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffff")

            assertEquals(IdResolution.Resolved(id), strict.resolve(id.toString()))
            assertEquals(IdResolution.Resolved(id), strict.resolve(id.toString().uppercase()))
            assertEquals(IdResolution.Resolved(id), strict.resolve(IdRef(id.toString())))
            confirmVerified(store)
        }

    @Test
    fun `S1 a canonical UUID that is not in the store is still Resolved (existence is the handler's job)`(): Unit =
        runBlocking {
            val nil = UUID(0L, 0L)
            assertEquals(IdResolution.Resolved(nil), resolver.resolve(nil.toString()))
            val absent = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")
            assertEquals(IdResolution.Resolved(absent), resolver.resolve(absent.toString()))
        }

    @Test
    fun `S1 Resolved maps to Ok with the same id`(): Unit =
        runBlocking {
            val id = UUID.fromString("3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffff")
            assertEquals(Outcome.Ok(id), IdResolution.Resolved(id).toOutcome("id"))
        }

    // ============================================================================ S2 -- unique prefix

    @Test
    fun `S2 an 8-hex prefix of exactly one item resolves to it, in any case`(): Unit =
        runBlocking {
            val a = seed("3f2a9c10-1111-4111-8111-111111111111")
            seed("9e0b77aa-2222-4222-8222-222222222222")
            seed("3f2a9c11-3333-4333-8333-333333333333")

            assertEquals(IdResolution.Resolved(a), resolver.resolve("3f2a9c10"))
            assertEquals(IdResolution.Resolved(a), resolver.resolve("3F2A9C10"))
            assertEquals(IdResolution.Resolved(a), resolver.resolve("3f2A9c10"))
            assertEquals(IdResolution.Resolved(a), resolver.resolve(IdRef("3f2a9c10")))
        }

    @Test
    fun `S2 every prefix length from 8 to 32 hex characters resolves a unique item`(): Unit =
        runBlocking {
            val a = seed("3f2a9c10-1111-4111-8111-111111111111")
            seed("9e0b77aa-2222-4222-8222-222222222222")
            val full = hex(a)
            for (length in 8..32) {
                assertEquals(IdResolution.Resolved(a), resolver.resolve(full.substring(0, length)), "length $length")
                assertEquals(IdResolution.Resolved(a), resolver.resolve(full.substring(0, length).uppercase()), "length $length upper")
            }
        }

    @Test
    fun `S2 resolve by IdRef and by raw string agree`(): Unit =
        runBlocking {
            seed("3f2a9c10-1111-4111-8111-111111111111")
            seed("abcd1234-0000-4000-8000-000000000001")
            seed("abcd1234-7777-4777-8777-777777777777")
            assertEquals(resolver.resolve("abcd1234"), resolver.resolve(IdRef("abcd1234")))
            assertEquals(resolver.resolve("deadbeef"), resolver.resolve(IdRef("deadbeef")))
        }

    // ============================================================================ S3 -- no match

    @Test
    fun `S3 a prefix that matches nothing is NotFound and maps to not_found with the prefix as id`(): Unit =
        runBlocking {
            seed("3f2a9c10-1111-4111-8111-111111111111")

            val result = resolver.resolve("deadbeef")
            assertEquals(IdResolution.NotFound("deadbeef"), result)

            val error = errorOf(result.toOutcome("itemId"))
            assertEquals(ErrorCode.NOT_FOUND, error.code)
            assertEquals(ErrorDetail.NotFound(EntityKind.ITEM, "deadbeef"), error.detail)
        }

    @Test
    fun `S3 NotFound on an empty store, and for an upper-case prefix, is still NotFound`(): Unit =
        runBlocking {
            assertEquals(IdResolution.NotFound("deadbeef"), resolver.resolve("deadbeef"))
            assertIs<IdResolution.NotFound>(resolver.resolve("DEADBEEF"))
        }

    // ============================================================================ S4 -- ambiguous

    @Test
    fun `S4 a prefix matching three items is Ambiguous with all three ids ascending and an ids-only outcome`(): Unit =
        runBlocking {
            val low = seed("abcd1234-0000-4000-8000-000000000001", "low")
            val mid = seed("abcd1234-7777-4777-8777-777777777777", "mid")
            val high = seed("abcd1234-ffff-4fff-8fff-ffffffffffff", "high")
            seed("abcd1233-ffff-4fff-8fff-ffffffffffff", "decoy-below")
            seed("abcd1235-0000-4000-8000-000000000000", "decoy-above")

            val result = resolver.resolve("abcd1234")
            val ambiguous = assertIs<IdResolution.Ambiguous>(result)
            assertEquals("abcd1234", ambiguous.prefix)
            assertEquals(listOf(low, mid, high), ambiguous.candidates)

            val error = errorOf(result.toOutcome("id"))
            assertEquals(ErrorCode.AMBIGUOUS_ID, error.code)
            assertEquals(ErrorDetail.AmbiguousId("abcd1234", listOf(low, mid, high)), error.detail)
        }

    @Test
    fun `S4 exactly two matches is already Ambiguous`(): Unit =
        runBlocking {
            val a = seed("abcd1234-0000-4000-8000-000000000001")
            val b = seed("abcd1234-0000-4000-8000-000000000002")
            val ambiguous = assertIs<IdResolution.Ambiguous>(resolver.resolve("abcd1234"))
            assertEquals(listOf(a, b), ambiguous.candidates)
        }

    @Test
    fun `S4 candidates are capped at 10 and stay ascending`(): Unit =
        runBlocking {
            val ids = (0 until 12).map { seed("c0ffee00-%04x-4000-8000-000000000000".format(it)) }
            val ambiguous = assertIs<IdResolution.Ambiguous>(resolver.resolve("c0ffee00"))
            assertEquals(IdResolver.MAX_CANDIDATES, 10)
            assertEquals(10, ambiguous.candidates.size, "12 matches must be capped at 10 candidates")
            assertEquals(ambiguous.candidates.sortedBy { it.toString() }, ambiguous.candidates, "ascending")
            assertTrue(ids.containsAll(ambiguous.candidates), "candidates must be seeded ids")
            assertEquals(ambiguous.candidates.distinct(), ambiguous.candidates, "no duplicates")
            val error = errorOf(IdResolution.Ambiguous("c0ffee00", ambiguous.candidates).toOutcome("id"))
            assertEquals(ErrorCode.AMBIGUOUS_ID, error.code)
        }

    @Test
    fun `S4 a longer prefix disambiguates what a shorter one cannot`(): Unit =
        runBlocking {
            val a = seed("abcd1234-0000-4000-8000-000000000001")
            seed("abcd1234-7777-4777-8777-777777777777")
            assertIs<IdResolution.Ambiguous>(resolver.resolve("abcd1234"))
            assertEquals(IdResolution.Resolved(a), resolver.resolve("abcd12340"))
            assertEquals(IdResolution.Resolved(a), resolver.resolve("abcd1234-0000-4000-8000-000000000001"))
        }

    // ============================================================================ S5 -- invalid

    private val invalidInputs: List<String> =
        listOf(
            "abcdef0", // 7 hex: below the 8 minimum
            "abc",
            "a",
            "",
            " ",
            "0".repeat(33), // 33 hex: above the 32 maximum
            "3f2a9c10aaaa4bbb8cccddddeeeeffff0", // 33 hex of a real-looking id
            "ghijklmn", // 8 non-hex
            "zzzzzzzz",
            "3f2a-9c10", // dashed partial
            "3f2a9c10-", // trailing dash
            "3f2a9c10-aaaa-4bbb", // dashed partial UUID
            " 3f2a9c10", // whitespace padded
            "3f2a9c10 ",
            "0x3f2a9c10", // 0x prefix
            "{3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffff}", // braces
            "urn:uuid:3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffff",
            "3f2a9c10-aaaa-4bbb-8ccc-ddddeeeefff", // 35 characters
            "3f2a9c10aaaa-4bbb-8ccc-ddddeeeeffff", // dash misplaced
            "3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffgg", // 36 characters, non-hex
        )

    @Test
    fun `S5 malformed ids are Invalid with the format reason, the raw text, and zero store calls`(): Unit =
        runBlocking {
            val store = mockk<ItemStore>()
            val strict = IdResolver(store)
            for (raw in invalidInputs) {
                assertNull(IdRef.parse(raw), "fixture sanity: '$raw' must not parse as an IdRef")
                val result = strict.resolve(raw)
                val invalid = assertIs<IdResolution.Invalid>(result, "'$raw' -> $result")
                assertEquals(raw, invalid.raw, "raw must be reported unnormalized")
                assertEquals(IdRef.FORMAT_REASON, invalid.reason, "'$raw'")
            }
            confirmVerified(store)
        }

    @Test
    fun `S5 Invalid maps to invalid_request naming the field with the reason and the received text`(): Unit =
        runBlocking {
            for (raw in listOf("abcdef0", "ghijklmn", "", " 3f2a9c10")) {
                val outcome = resolver.resolve(raw).toOutcome("parentId")
                val error = errorOf(outcome)
                assertEquals(ErrorCode.INVALID_REQUEST, error.code, "'$raw'")
                val detail = assertIs<ErrorDetail.InvalidRequest>(error.detail)
                assertEquals(listOf(FieldViolation("parentId", IdRef.FORMAT_REASON, raw)), detail.fields, "'$raw'")
            }
        }

    @Test
    fun `S5 the 4x minimum is 8 hex - a 4 to 7 character prefix is Invalid even when it would match`(): Unit =
        runBlocking {
            seed("3f2a9c10-1111-4111-8111-111111111111")
            for (raw in listOf("3f2a", "3f2a9", "3f2a9c", "3f2a9c1")) {
                assertIs<IdResolution.Invalid>(resolver.resolve(raw), "'$raw'")
            }
        }

    @Test
    fun `S5 the field name in the violation follows the argument`(): Unit =
        runBlocking {
            val a = errorOf(resolver.resolve("zz").toOutcome("a")).detail as ErrorDetail.InvalidRequest
            val b = errorOf(resolver.resolve("zz").toOutcome("items[2].id")).detail as ErrorDetail.InvalidRequest
            assertEquals("a", a.fields.single().field)
            assertEquals("items[2].id", b.fields.single().field)
        }

    // ============================================================================ S6 -- store fault

    @Test
    fun `S6 a store fault is Failed carrying the cause, and toOutcome rethrows it instead of not_found`(): Unit =
        runBlocking {
            val boom = SQLException("disk I/O error")
            val store = mockk<ItemStore>()
            coEvery { store.findByIdPrefix(any(), any()) } throws boom
            val failing = IdResolver(store)

            val result = failing.resolve("abcd1234")
            val failed = assertIs<IdResolution.Failed>(result, "a store fault must not be NotFound/Invalid: $result")
            assertSame(boom, failed.cause)

            val rethrown = assertFailsWith<SQLException> { result.toOutcome("id") }
            assertSame(boom, rethrown)
        }

    @Test
    fun `S6 any non-cancellation exception is Failed, through resolve and IdRef`(): Unit =
        runBlocking {
            val boom = IllegalStateException("Database connection failed")
            val store = mockk<ItemStore>()
            coEvery { store.findByIdPrefix(any(), any()) } throws boom
            val failing = IdResolver(store)

            assertSame(boom, assertIs<IdResolution.Failed>(failing.resolve("abcd1234")).cause)
            assertSame(boom, assertIs<IdResolution.Failed>(failing.resolve(IdRef("abcd1234"))).cause)
            assertFailsWith<IllegalStateException> { failing.resolve("abcd1234").toOutcome("id") }
        }

    @Test
    fun `S6 a CancellationException propagates unwrapped from every entry`(): Unit =
        runBlocking {
            val store = mockk<ItemStore>()
            coEvery { store.findByIdPrefix(any(), any()) } throws CancellationException("cancelled")
            val cancelling = IdResolver(store)

            assertFailsWith<CancellationException> { cancelling.resolve("abcd1234") }
            assertFailsWith<CancellationException> { cancelling.resolve(IdRef("abcd1234")) }
        }

    @Test
    fun `S6 a fault only matters on the prefix path - a full UUID and a malformed id never reach the store`(): Unit =
        runBlocking {
            val store = mockk<ItemStore>()
            coEvery { store.findByIdPrefix(any(), any()) } throws SQLException("boom")
            val failing = IdResolver(store)
            val id = UUID.fromString("3f2a9c10-aaaa-4bbb-8ccc-ddddeeeeffff")

            assertEquals(IdResolution.Resolved(id), failing.resolve(id.toString()))
            assertIs<IdResolution.Invalid>(failing.resolve("zzzz"))
            coVerify(exactly = 0) { store.findByIdPrefix(any(), any()) }
        }

    // ============================================================================ S7 -- byte-bound edges

    @Test
    fun `S7 an odd-length prefix selects exactly the ids on its nibble, not the neighbours across the byte bound`(): Unit =
        runBlocking {
            seed("abcd1234-3fff-4fff-8fff-ffffffffffff", "below-2")
            seed("abcd1234-4fff-4fff-8fff-ffffffffffff", "below")
            val inLow = seed("abcd1234-5000-4000-8000-000000000000", "in-low")
            val inHigh = seed("abcd1234-5fff-4fff-8fff-ffffffffffff", "in-high")
            seed("abcd1234-6000-4000-8000-000000000000", "above")
            seed("abcd1234-7000-4000-8000-000000000000", "above-2")

            // 9 hex characters: the last nibble '5' selects 5000.. through 5fff..
            val ambiguous = assertIs<IdResolution.Ambiguous>(resolver.resolve("abcd12345"))
            assertEquals(listOf(inLow, inHigh), ambiguous.candidates)
            // 11 hex characters narrow it to one on each side.
            assertEquals(IdResolution.Resolved(inLow), resolver.resolve("abcd1234500"))
            assertEquals(IdResolution.Resolved(inHigh), resolver.resolve("abcd12345ff"))
        }

    @Test
    fun `S7 an all-f prefix has no upper neighbour and still selects exactly its ids`(): Unit =
        runBlocking {
            val top = seed("ffffffff-ffff-4fff-bfff-ffffffffffff", "max")
            val topLow = seed("ffffffff-0000-4000-8000-000000000000", "top-low")
            seed("fffffffe-ffff-4fff-bfff-ffffffffffff", "just-below")
            seed("00000000-0000-4000-8000-000000000000", "min")

            val ambiguous = assertIs<IdResolution.Ambiguous>(resolver.resolve("ffffffff"))
            assertEquals(listOf(topLow, top), ambiguous.candidates)
            assertEquals(IdResolution.Resolved(top), resolver.resolve("ffffffffffff"))
            assertEquals(IdResolution.Resolved(top), resolver.resolve("FFFFFFFFFFFF4FFFBFFFFFFFFFFFFFFF"))
        }

    @Test
    fun `S7 an odd all-f prefix selects only the ids whose next nibble is f`(): Unit =
        runBlocking {
            val hit = seed("ffffffff-f000-4000-8000-000000000000", "hit")
            seed("ffffffff-e000-4000-8000-000000000000", "miss")
            seed("fffffffe-f000-4000-8000-000000000000", "miss-2")

            assertEquals(IdResolution.Resolved(hit), resolver.resolve("fffffffff"))
        }

    @Test
    fun `S7 an all-zero prefix selects only the zero-prefixed ids`(): Unit =
        runBlocking {
            val zero = seed("00000000-0000-4000-8000-000000000001", "zero")
            seed("00000001-0000-4000-8000-000000000000", "one")
            assertEquals(IdResolution.Resolved(zero), resolver.resolve("00000000"))
            assertEquals(IdResolution.NotFound("00000002"), resolver.resolve("00000002"))
        }
}

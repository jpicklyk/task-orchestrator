package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.WasteCount
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.signalJson
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MCP_SESSIONS = "mcp_sessions"

// Ids with hex letters, so that an upper-case spelling really differs from the lower-case one.
private const val X1 = "a0000000-0000-4000-8000-000000000001"
private const val X2 = "a0000000-0000-4000-8000-000000000002"
private const val H1 = "d0000000-0000-4000-8000-00000000000a"
private const val H2 = "d0000000-0000-4000-8000-00000000000b"
private const val H3 = "d0000000-0000-4000-8000-00000000000c"

/**
 * Independent tests (test-author seat, item 03f3520d, R12) for [ReportService] `waste` over the real SQLite database and the
 * real [io.github.jpicklyk.mcptask.current.application.port.ReportQueries] port: scenarios S1 to S9, S11 to S14 of the frozen
 * test-plan (all NEW-SURFACE); S10, S16 and S18 live in the parity scenarios and the classification test, S15 in
 * `ReportWasteQueryCountTest`, S17 in `McpLifecycleFreeFlipCallLogTest`.
 *
 * Oracles (never read from a run). Each is a point of the frozen task-scope (TS-n), and every expected number below is
 * counted by hand from the seeded rows (the arithmetic is in the comment next to the test): TS-2 (half-open window
 * [since, until), until defaults to now, since to until minus 7 days, at most 7 days else invalid_request on /since, rows of
 * both join sides inside the window), TS-3 (visibility as usage: a tag scope is forbidden{tag} before any read; a root scope
 * sees only rows whose root_id is in scope, rows without a root never; rootId must be depth 0 and narrows further), TS-4 (READ /
 * WRITE / EXCLUDED tool classes; a session row has surface mcp AND a session id), TS-5 (the definition of each signal; value =
 * numerator / denominator rounded half-up to 4 decimals, absent when the denominator is 0; basis mcp_sessions exactly on the
 * session signals; every signal always present).
 *
 * Layouts used by the fixtures come from public evidence, not from the implementation: `target_versions` is an object
 * {id: version}, `target_ids` an array of id strings, `hit_signals` an array of {id, kind, signals} (api-reference "related" at
 * the base commit and `CallLogFieldsTest`), `request_shape` an object whose `fields` member is an array of names
 * (`CallLogFieldsRootKeysTest`).
 *
 * Negative / absence assertions: each names the seeded row that WOULD change the count if the rule under test were absent (an
 * error row, a REST row, a row of another root, a row at the excluded boundary, a replayed copy) and pairs it with a control from
 * the same fixture that does count.
 *
 * Oracle interpretation flagged for arbitration (see the manifest): the two probes named `no operation` read "operation NOT IN
 * (related, search)" as set membership, so a row whose operation is NULL is NOT a member and still counts.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions, no disjunctive assertions.
 *
 * Domain invariants satisfied by construction: [ReportKit.rec] derives outcome from the error code and the token estimate from
 * the response bytes; the copy() call only sets the capture columns, and every dependent set (a related row's hit signals, a next
 * row's result and eligible counts) is written by the one builder below; roots are depth 0 with no parent.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ReportWasteTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2
    private var seq = 0

    /** A moment `sec` seconds after a base two days before the env clock (always inside the default window). */
    private fun t(sec: Long): Instant = now.minus(Duration.ofDays(2)).plusSeconds(sec)

    private fun ago(d: Duration): Instant = now.minus(d)

    private fun vers(vararg p: Pair<String, Int>): String = p.joinToString(",", "{", "}") { "\"${it.first}\":${it.second}" }

    private fun idArray(vararg ids: String): String = ids.joinToString(",", "[", "]") { "\"$it\"" }

    private fun hitsJson(vararg h: Pair<String, List<String>>): String =
        h.joinToString(",", "[", "]") { (id, signals) ->
            "{\"id\":\"$id\",\"kind\":\"item\",\"signals\":${signals.joinToString(",", "[", "]") { "\"$it\"" }}}"
        }

    private fun row(
        at: Instant,
        tool: String,
        op: String? = null,
        session: String? = null,
        surface: String = CallLogRecord.SURFACE_MCP,
        error: String? = null,
        versions: String? = null,
        ids: String? = null,
        shape: String? = null,
        result: Int? = null,
        eligible: Int? = null,
        hits: String? = null,
        lf: Int? = null,
        replayed: Boolean = false,
        root: UUID? = null
    ): CallLogRecord =
        ReportKit
            .rec(++seq, at, tool, op, surface = surface, error = error, replayed = replayed, rootId = root)
            .copy(
                sessionId = session,
                targetIds = ids,
                targetVersions = versions,
                requestShape = shape,
                resultCount = result,
                eligibleCount = eligible,
                hitSignals = hits,
                lifecycleFreeRejections = lf
            )

    private suspend fun ReportFixture.waste(
        request: ReportWasteRequest = ReportWasteRequest(),
        ctx: CallContext = ctx()
    ): Outcome<WasteReport> = service().waste(ctx, request)

    private suspend fun ReportFixture.wasteOk(
        request: ReportWasteRequest = ReportWasteRequest(),
        ctx: CallContext = ctx()
    ): WasteReport =
        when (val outcome = waste(request, ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok waste report for $request but got ${outcome.error}")
        }

    private fun err(outcome: Outcome<WasteReport>) = assertIs<Outcome.Err>(outcome).error

    private fun req(
        since: String? = null,
        until: String? = null,
        rootId: String? = null
    ) = ReportWasteRequest(rootId?.let { IdRef(it) }, since, until)

    /** [value] is the expected value as decimal text, or null when it must be absent; compared numerically, never as text. */
    private fun assertSignal(
        label: String,
        signal: WasteSignal,
        numerator: Long,
        denominator: Long,
        value: String?,
        basis: String?
    ) {
        assertEquals(numerator, signal.numerator, "$label numerator")
        assertEquals(denominator, signal.denominator, "$label denominator")
        if (value == null) {
            assertNull(signal.value, "$label value must be absent")
        } else {
            val actual = assertNotNull(signal.value, "$label value must be present")
            assertEquals(0, BigDecimal(value).compareTo(actual), "$label value expected $value but was $actual")
        }
        assertEquals(basis, signal.basis, "$label basis")
    }

    // ------------------------------------------------------------------ S1 shape of an empty window

    @Test
    fun `S1 an empty window answers every signal with zero counts, no value and the documented basis`(): Unit =
        runBlocking {
            val report = fx.wasteOk()

            assertSignal("redundantReads", report.redundantReads, 0, 0, null, MCP_SESSIONS)
            assertSignal("overFetch", report.overFetch, 0, 0, null, null)
            assertSignal("readsWithoutLaterWrite", report.readsWithoutLaterWrite, 0, 0, null, MCP_SESSIONS)
            assertSignal("emptyNext", report.emptyNext, 0, 0, null, null)
            assertSignal("lifecycleFreeRejections", report.lifecycleFreeRejections, 0, 0, null, null)
            assertEquals(setOf("edges", "links", "tags", "text"), report.relatedFollowThrough.keys, "all four follow-through signals")
            assertEquals(4, report.relatedFollowThrough.size)
            for (name in listOf("edges", "links", "tags", "text")) {
                assertSignal("followThrough.$name", report.relatedFollowThrough.getValue(name), 0, 0, null, MCP_SESSIONS)
            }
            assertEquals(now.minus(Duration.ofDays(7)), report.window.since)
            assertEquals(now, report.window.until)
            assertEquals("raw", report.window.basis)
            assertNull(report.window.retainedSince)
            assertFalse(report.window.truncatedByRetention)
        }

    @Test
    fun `S1 rows of the excluded tool classes and of unknown tools enter no signal`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "report", "usage", session = "A", versions = vers(X1 to 1), shape = "{\"fields\":[\"ancestors\"]}"),
                row(t(20), "config", "get", session = "A", versions = vers(X1 to 1)),
                row(t(30), "sample_tool", "get", session = "A", versions = vers(X1 to 1), lf = 3),
                row(t(40), "sample_tool", "next", session = "A", result = 0, eligible = 4),
                row(t(50), "report", "waste", session = "A", lf = 2)
            )

            val report = f.wasteOk()

            assertSignal("redundantReads", report.redundantReads, 0, 0, null, MCP_SESSIONS)
            assertSignal("overFetch", report.overFetch, 0, 0, null, null)
            assertSignal("readsWithoutLaterWrite", report.readsWithoutLaterWrite, 0, 0, null, MCP_SESSIONS)
            assertSignal("emptyNext", report.emptyNext, 0, 0, null, null)
            assertSignal("lifecycleFreeRejections", report.lifecycleFreeRejections, 0, 0, null, null)
            assertTrue(report.relatedFollowThrough.values.all { it.denominator == 0L })
        }

    // ------------------------------------------------------------------ S2 redundantReads

    @Test
    fun `S2 redundantReads is the share of key and version observations a session already made`(): Unit =
        runBlocking {
            val f = fx
            // A: X1 v1 twice, X2 v1; B: X1 v1; a related row of A naming X1 is not a body read.
            // observations = 4, distinct (session, key, version) = (A,X1,1) (A,X2,1) (B,X1,1) = 3, numerator = 1.
            f.seed(
                row(t(10), "items_read", "get", session = "A", versions = vers(X1 to 1)),
                row(t(20), "items_read", "get", session = "A", versions = vers(X1 to 1)),
                row(t(30), "items_read", "get", session = "A", versions = vers(X2 to 1)),
                row(t(40), "items_read", "get", session = "B", versions = vers(X1 to 1)),
                row(t(50), "items_read", "related", session = "A", versions = vers(X1 to 1), hits = hitsJson(X1 to listOf("edges")))
            )

            val report = f.wasteOk()

            assertSignal("redundantReads", report.redundantReads, 1, 4, "0.25", MCP_SESSIONS)
        }

    @Test
    fun `S2 probe the same key at another version is a new observation, not a repeat`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_read", "get", session = "A", versions = vers(X1 to 1)),
                row(t(20), "items_read", "get", session = "A", versions = vers(X1 to 2))
            )

            assertSignal("redundantReads", f.wasteOk().redundantReads, 0, 2, "0", MCP_SESSIONS)
        }

    @Test
    fun `S2 probe a row with several keys counts each key, and every read tool counts`(): Unit =
        runBlocking {
            val f = fx
            // notes_read {X1 v1, X2 v1}, docs_read {X1 v1}, deps_read {X2 v1}: 4 observations, distinct (A,X1,1) (A,X2,1) = 2.
            f.seed(
                row(t(10), "notes_read", "get", session = "A", versions = vers(X1 to 1, X2 to 1)),
                row(t(20), "docs_read", "get", session = "A", versions = vers(X1 to 1)),
                row(t(30), "deps_read", "get", session = "A", versions = vers(X2 to 1))
            )

            assertSignal("redundantReads", f.wasteOk().redundantReads, 2, 4, "0.5", MCP_SESSIONS)
        }

    @Test
    fun `S2 probe error, search, write, REST, session-less and null-version rows are not observations`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_read", "get", session = "A", versions = vers(X1 to 1)),
                // each of the following would add an observation of (A, X1, 1) or (NULL, X1, 1) if it were counted
                row(t(20), "items_read", "get", session = "A", error = "not_found", versions = vers(X1 to 1)),
                row(t(30), "items_read", "search", session = "A", versions = vers(X1 to 1)),
                row(t(40), "items_write", "update", session = "A", versions = vers(X1 to 1)),
                row(t(50), "items_read", "get", session = "A", surface = CallLogRecord.SURFACE_REST, versions = vers(X1 to 1)),
                row(t(60), "items_read", "get", session = null, versions = vers(X1 to 1)),
                row(t(70), "items_read", "get", session = "A", versions = null)
            )

            assertSignal("redundantReads", f.wasteOk().redundantReads, 0, 1, "0", MCP_SESSIONS)
        }

    @Test
    fun `S2 probe a read with no operation is still an observation`(): Unit =
        runBlocking {
            val f = fx
            // the operation of these two rows is NULL: not related and not search, so both are observations of (A, X1, 1)
            f.seed(
                row(t(10), "notes_read", null, session = "A", versions = vers(X1 to 1)),
                row(t(20), "docs_read", null, session = "A", versions = vers(X1 to 1))
            )

            assertSignal("redundantReads", f.wasteOk().redundantReads, 1, 2, "0.5", MCP_SESSIONS)
        }

    // ------------------------------------------------------------------ S3 overFetch

    @Test
    fun `S3 overFetch is the share of ok item reads that opted into fields`(): Unit =
        runBlocking {
            val f = fx
            // 3 ok get/list rows, one with fields: 1/3. The error row and the related row also carry fields and are excluded.
            f.seed(
                row(t(10), "items_read", "get", shape = "{\"fields\":[\"ancestors\"]}"),
                row(t(20), "items_read", "list"),
                row(t(30), "items_read", "get", shape = "{\"includeChildren\":true}"),
                row(t(40), "items_read", "get", error = "not_found", shape = "{\"fields\":[\"ancestors\"]}"),
                row(t(50), "items_read", "related", shape = "{\"fields\":[\"ancestors\"]}")
            )

            assertSignal("overFetch", f.wasteOk().overFetch, 1, 3, "0.3333", null)
        }

    @Test
    fun `S3 probe exactly get, list, schema, context, next and blocked are fetch operations`(): Unit =
        runBlocking {
            val f = fx
            val fields = "{\"fields\":[\"ancestors\"]}"
            val rows =
                mutableListOf(
                    row(t(1), "items_read", "get"),
                    row(t(2), "items_read", "list"),
                    row(t(3), "items_read", "schema", shape = fields),
                    row(t(4), "items_read", "context"),
                    row(t(5), "items_read", "next"),
                    row(t(6), "items_read", "blocked", shape = fields)
                )
            // every one of these carries fields; none is a fetch operation or an items_read row, so none may be counted
            rows += row(t(7), "items_read", "search", shape = fields)
            rows += row(t(8), "items_read", "overview", shape = fields)
            rows += row(t(9), "items_read", "history", shape = fields)
            rows += row(t(10), "items_read", "related", shape = fields)
            rows += row(t(11), "items_read", null, shape = fields)
            rows += row(t(12), "notes_read", "get", shape = fields)
            f.seed(*rows.toTypedArray())

            assertSignal("overFetch", f.wasteOk().overFetch, 2, 6, "0.3333", null)
        }

    // ------------------------------------------------------------------ S4 readsWithoutLaterWrite

    @Test
    fun `S4 readsWithoutLaterWrite counts the session reads no write of the same session follows`(): Unit =
        runBlocking {
            val f = fx
            // A: read t10, write t20, read t30. B: read t10. C: write t5, read t10. D: a write at t100 in another session.
            // ok session reads = A(2) + B(1) + C(1) = 4; only A's first read has a strictly later write: numerator 3.
            f.seed(
                row(t(10), "items_read", "get", session = "A"),
                row(t(20), "items_write", "update", session = "A"),
                row(t(30), "items_read", "get", session = "A"),
                row(t(10), "items_read", "get", session = "B"),
                row(t(5), "items_write", "update", session = "C"),
                row(t(10), "items_read", "get", session = "C"),
                row(t(100), "items_write", "update", session = "D")
            )

            assertSignal("readsWithoutLaterWrite", f.wasteOk().readsWithoutLaterWrite, 3, 4, "0.75", MCP_SESSIONS)
        }

    @Test
    fun `S4 probe every write tool rescues a read, even a failed write, and no other class does`(): Unit =
        runBlocking {
            val f = fx
            val rows = mutableListOf<CallLogRecord>()
            for (tool in listOf("items_write", "notes_write", "deps_write", "docs_write", "advance")) {
                rows += row(t(10), "items_read", "get", session = "w-$tool")
                rows += row(t(20), tool, null, session = "w-$tool", error = "forbidden")
            }
            // controls: a later report, config or unknown-tool row leaves its read unrescued
            for (tool in listOf("report", "config", "sample_tool")) {
                rows += row(t(10), "items_read", "get", session = "n-$tool")
                rows += row(t(20), tool, null, session = "n-$tool")
            }
            // a later READ row does not rescue either: both reads of this session stay unrescued
            rows += row(t(10), "items_read", "get", session = "n-read")
            rows += row(t(20), "notes_read", "get", session = "n-read")
            f.seed(*rows.toTypedArray())

            // reads: 5 (rescued) + 3 + 2 = 10, unrescued 3 + 2 = 5
            assertSignal("readsWithoutLaterWrite", f.wasteOk().readsWithoutLaterWrite, 5, 10, "0.5", MCP_SESSIONS)
        }

    @Test
    fun `S4 probe all read tools and operations count as reads, an error read does not`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_read", "related", session = "A"),
                row(t(11), "notes_read", "get", session = "A", error = "not_found"),
                row(t(12), "docs_read", "search", session = "A"),
                row(t(13), "deps_read", "get", session = "A")
            )

            assertSignal("readsWithoutLaterWrite", f.wasteOk().readsWithoutLaterWrite, 3, 3, "1", MCP_SESSIONS)
        }

    // ------------------------------------------------------------------ S5 emptyNext

    @Test
    fun `S5 emptyNext is the share of next calls that returned nothing although something was eligible`(): Unit =
        runBlocking {
            val f = fx
            // (result, eligible) = (0,3) empty, (0,0) nothing eligible, (2,5) answered: 1/3.
            f.seed(
                row(t(10), "items_read", "next", result = 0, eligible = 3),
                row(t(20), "items_read", "next", result = 0, eligible = 0),
                row(t(30), "items_read", "next", result = 2, eligible = 5)
            )

            assertSignal("emptyNext", f.wasteOk().emptyNext, 1, 3, "0.3333", null)
        }

    @Test
    fun `S5 probe NULL counts never match, REST counts, and error, other-operation and other-tool rows are excluded`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_read", "next", result = 0, eligible = 3),
                row(t(20), "items_read", "next", result = null, eligible = 3),
                row(t(30), "items_read", "next", result = 0, eligible = null),
                row(t(40), "items_read", "next", surface = CallLogRecord.SURFACE_REST, result = 0, eligible = 3),
                // excluded rows, each would be an empty next if it were counted
                row(t(50), "items_read", "next", error = "forbidden", result = 0, eligible = 3),
                row(t(60), "items_read", "get", result = 0, eligible = 3),
                row(t(70), "notes_read", "next", result = 0, eligible = 3)
            )

            // den = rows 1..4; num = rows 1 and 4
            assertSignal("emptyNext", f.wasteOk().emptyNext, 2, 4, "0.5", null)
        }

    // ------------------------------------------------------------------ S6 relatedFollowThrough

    @Test
    fun `S6 follow-through counts the units a later call in the same session targeted`(): Unit =
        runBlocking {
            val f = fx
            // r at t100 in A found H1 by edges and text, H2 by tags. Later in A: a get naming H1 (counts for edges and text),
            // a related and a search row naming H2 (excluded operations). Distractors for H2: a row of session B, and a row of A
            // BEFORE r. Units: edges 1, text 1, tags 1; links 0.
            f.seed(
                row(t(90), "items_read", "get", session = "A", versions = vers(H2 to 1)),
                row(t(100), "items_read", "related", session = "A", hits = hitsJson(H1 to listOf("edges", "text"), H2 to listOf("tags"))),
                row(t(110), "items_read", "get", session = "A", versions = vers(H1 to 3)),
                row(t(120), "items_read", "related", session = "A", versions = vers(H2 to 1)),
                row(t(130), "items_read", "search", session = "A", versions = vers(H2 to 1)),
                row(t(110), "items_read", "get", session = "B", versions = vers(H2 to 1))
            )

            val ft = f.wasteOk().relatedFollowThrough

            assertSignal("edges", ft.getValue("edges"), 1, 1, "1", MCP_SESSIONS)
            assertSignal("text", ft.getValue("text"), 1, 1, "1", MCP_SESSIONS)
            assertSignal("tags", ft.getValue("tags"), 0, 1, "0", MCP_SESSIONS)
            assertSignal("links", ft.getValue("links"), 0, 0, null, MCP_SESSIONS)
        }

    @Test
    fun `S6 a later call of any tool and any outcome counts, matched on the exact id in target_ids or target_versions`(): Unit =
        runBlocking {
            val f = fx
            val allThree = hitsJson(H1 to listOf("links"), H2 to listOf("links"), H3 to listOf("links"))
            f.seed(
                row(t(100), "items_read", "related", session = "A", hits = allThree),
                // H1: a FAILED notes_write that targeted it; H2: an ok items_write that targeted it
                row(t(110), "notes_write", "update", session = "A", error = "forbidden", ids = idArray(H1)),
                row(t(120), "items_write", "update", session = "A", ids = idArray(H2, X1)),
                // H3 near misses: an upper-case spelling, an id with one more character, a prefix, in target_ids and as a version key
                row(t(130), "items_read", "get", session = "A", ids = idArray(H3.uppercase()), versions = vers(H3.uppercase() to 1)),
                row(t(140), "items_read", "get", session = "A", ids = idArray(H3 + "0", H3.take(8)), versions = vers(H3 + "0" to 1))
            )

            val ft = f.wasteOk().relatedFollowThrough

            assertSignal("links", ft.getValue("links"), 2, 3, "0.6667", MCP_SESSIONS)
        }

    @Test
    fun `S6 probe a later call with no operation is still a follow-through candidate`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(100), "items_read", "related", session = "A", hits = hitsJson(H1 to listOf("tags"))),
                row(t(110), "advance", null, session = "A", ids = idArray(H1))
            )

            assertSignal("tags", f.wasteOk().relatedFollowThrough.getValue("tags"), 1, 1, "1", MCP_SESSIONS)
        }

    @Test
    fun `S6 probe an error, REST, session-less or hit-less related row makes no unit`(): Unit =
        runBlocking {
            val f = fx
            val rest = CallLogRecord.SURFACE_REST
            f.seed(
                // a later call in A that targets H1 would count for any of these if they made a unit
                row(t(100), "items_read", "related", session = "A", error = "forbidden", hits = hitsJson(H1 to listOf("edges"))),
                row(t(101), "items_read", "related", session = "A", surface = rest, hits = hitsJson(H1 to listOf("edges"))),
                row(t(102), "items_read", "related", session = null, hits = hitsJson(H1 to listOf("edges"))),
                row(t(103), "items_read", "related", session = "A", hits = null),
                row(t(104), "items_read", "get", session = "A", versions = vers(H1 to 1), ids = idArray(H1))
            )

            val ft = f.wasteOk().relatedFollowThrough

            assertSignal("edges", ft.getValue("edges"), 0, 0, null, MCP_SESSIONS)
        }

    // ------------------------------------------------------------------ S7 lifecycleFreeRejections

    @Test
    fun `S7 lifecycleFreeRejections sums the per-call counts over the non-replayed write calls`(): Unit =
        runBlocking {
            val f = fx
            // write calls: LF 2, NULL, 1 and a replayed copy of the LF 1 row. den = 3 non-replayed, num = 2 + 0 + 1.
            f.seed(
                row(t(10), "items_write", "update", error = "invalid_transition", lf = 2),
                row(t(20), "advance", null, lf = null),
                row(t(30), "deps_write", "create", error = "invalid_request", lf = 1),
                row(t(40), "deps_write", "create", error = "invalid_request", lf = 1, replayed = true),
                // a read-class and an excluded-class row with a count are not write calls
                row(t(50), "items_read", "get", lf = 7),
                row(t(60), "report", "usage", lf = 9)
            )

            assertSignal("lifecycleFreeRejections", f.wasteOk().lifecycleFreeRejections, 3, 3, "1", null)
        }

    @Test
    fun `S7 the value is a per-call rate, so a batch can push it above one`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_write", "update", error = "invalid_transition", lf = 5),
                row(t(20), "notes_write", "update")
            )

            assertSignal("lifecycleFreeRejections", f.wasteOk().lifecycleFreeRejections, 5, 2, "2.5", null)
        }

    @Test
    fun `S7 probe a window of replayed write rows only has no denominator`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_write", "update", error = "invalid_transition", lf = 2, replayed = true),
                row(t(20), "advance", null, replayed = true)
            )

            assertSignal("lifecycleFreeRejections", f.wasteOk().lifecycleFreeRejections, 0, 0, null, null)
        }

    // ------------------------------------------------------------------ S8 window

    private suspend fun assertInvalid(
        request: ReportWasteRequest,
        label: String
    ) = assertEquals(ErrorCode.INVALID_REQUEST, err(fx.waste(request)).code, label)

    @Test
    fun `S8 a window over seven days is invalid on the since pointer and exactly seven days is accepted`(): Unit =
        runBlocking {
            val f = fx
            f.wasteOk(req(since = "2026-10-01T00:00:00Z", until = "2026-10-08T00:00:00Z"))
            f.wasteOk(req(since = now.minus(Duration.ofDays(7)).toString()))
            f.wasteOk(req(until = "2026-10-10T00:00:00Z"))

            val tooLong =
                listOf(
                    req(since = "2026-10-01T00:00:00Z", until = "2026-10-08T00:00:00.001Z"),
                    req(since = now.minus(Duration.ofDays(7)).minusMillis(1).toString())
                )
            for (request in tooLong) {
                val error = err(f.waste(request))

                assertEquals(ErrorCode.INVALID_REQUEST, error.code, "$request")
                assertEquals("/since", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field, "$request")
            }
        }

    @Test
    fun `S8 since at or after until, and a malformed instant, are invalid_request`(): Unit =
        runBlocking {
            assertInvalid(req(since = "2026-10-05T00:00:00Z", until = "2026-10-05T00:00:00Z"), "since == until")
            assertInvalid(req(since = "2026-10-06T00:00:00Z", until = "2026-10-05T00:00:00Z"), "since > until")
            assertInvalid(req(since = now.plusSeconds(1).toString()), "since after the default until")
            for (bad in listOf("yesterday", "2026-13-45T00:00:00Z", "2026-10-01", "2026-10-01T00:00:00")) {
                assertInvalid(req(since = bad, until = "2026-10-05T00:00:00Z"), "since '$bad'")
                assertInvalid(req(since = "2026-10-01T00:00:00Z", until = bad), "until '$bad'")
            }
            fx.wasteOk(req(since = "2026-10-05T00:00:00Z", until = "2026-10-05T00:00:00.001Z"))
        }

    @Test
    fun `S8 the window is echoed in UTC and carries the retention facts of usage`(): Unit =
        runBlocking {
            val f = fx
            val oldest = ago(Duration.ofDays(3))
            f.seed(row(oldest, "items_read", "get"), row(ago(Duration.ofDays(2)), "items_read", "get"))

            val offset = f.wasteOk(req(since = "2026-10-12T14:00:00+02:00", until = "2026-10-13T13:30:00+02:00"))
            assertEquals(Instant.parse("2026-10-12T12:00:00Z"), offset.window.since)
            assertEquals(Instant.parse("2026-10-13T11:30:00Z"), offset.window.until)
            assertEquals("raw", offset.window.basis)
            assertEquals(oldest, offset.window.retainedSince)
            assertFalse(offset.window.truncatedByRetention)

            val truncated = f.wasteOk(req(since = ago(Duration.ofDays(5)).toString()))
            assertTrue(truncated.window.truncatedByRetention, "since is before the oldest row")
            val exact = f.wasteOk(req(since = oldest.toString()))
            assertFalse(exact.window.truncatedByRetention, "since equal to retainedSince is not truncated")
        }

    // ------------------------------------------------------------------ S9 visibility and rootId

    private suspend fun seedScopeRows(f: ReportFixture) {
        f.root(r1, "Alpha root", "api")
        f.root(r2, "Beta root", "beta")
        f.seed(row(t(10), "items_read", "next", result = 0, eligible = 3, root = r1))
    }

    @Test
    fun `S9 a tag-scoped caller is forbidden by the tag before any read, with or without a rootId`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val tagScoped = f.ctx(AccessScope.principal(null, setOf("api")))
            val requests =
                listOf(
                    ReportWasteRequest(),
                    req(rootId = r1.toString()),
                    req(rootId = "99999999-9999-4999-8999-999999999999")
                )
            for (request in requests) {
                val error = err(f.waste(request, tagScoped))

                assertEquals(ErrorCode.FORBIDDEN, error.code, "$request")
                val detail = assertIs<ErrorDetail.Forbidden>(error.detail)
                assertEquals(ForbiddenScope.TAG, detail.scope, "$request")
                assertEquals("api", detail.required, "$request")
            }
            assertEquals(1L, f.wasteOk().emptyNext.denominator, "control: an unrestricted caller reads the same rows")
        }

    @Test
    fun `S9 a rootId that is not a depth-0 item is invalid_request at the rootId pointer`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            f.child(ReportKit.C1, r1, "Child")

            for (ctx in listOf(f.ctx(), f.ctx(AccessScope.principal(setOf(r1), emptySet())))) {
                val error = err(f.waste(req(rootId = ReportKit.C1.toString()), ctx))

                assertEquals(ErrorCode.INVALID_REQUEST, error.code)
                assertEquals("/rootId", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field)
            }
        }

    @Test
    fun `S9 a rootId outside the root scope is forbidden by the root, an unknown one is not_found unrestricted`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val inR1 = f.ctx(AccessScope.principal(setOf(r1), emptySet()))

            val outside = err(f.waste(req(rootId = r2.toString()), inR1))

            assertEquals(ErrorCode.FORBIDDEN, outside.code)
            assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(outside.detail).scope)
            assertEquals(1L, f.wasteOk(req(rootId = r1.toString()), inR1).emptyNext.denominator, "control: the own root answers")
            val unknown = req(rootId = "99999999-9999-4999-8999-999999999999")
            assertEquals(ErrorCode.NOT_FOUND, err(f.waste(unknown)).code)
        }

    // ------------------------------------------------------------------ S11 window boundaries

    @Test
    fun `S11 a row at since is in the window and a row at until is out`(): Unit =
        runBlocking {
            val f = fx
            val since = Instant.parse("2026-10-12T00:00:00Z")
            val until = Instant.parse("2026-10-12T06:00:00Z")
            f.seed(
                row(since.minusMillis(1), "items_read", "next", result = 0, eligible = 3),
                row(since, "items_read", "next", result = 0, eligible = 3),
                row(until.minusMillis(1), "items_read", "next", result = 0, eligible = 3),
                row(until, "items_read", "next", result = 0, eligible = 3)
            )

            assertSignal("emptyNext", f.wasteOk(req(since.toString(), until.toString())).emptyNext, 2, 2, "1", null)
        }

    @Test
    fun `S11 a write at or after until, or in the same millisecond, rescues no read`(): Unit =
        runBlocking {
            val f = fx
            val since = Instant.parse("2026-10-12T00:00:00Z")
            val until = Instant.parse("2026-10-12T06:00:00Z")
            val h = Duration.ofHours(1)
            f.seed(
                // S-out: the writes are at until and one hour after it: outside the window, so the read stays unrescued
                row(since.plus(h.multipliedBy(5)), "items_read", "get", session = "S-out"),
                row(until, "items_write", "update", session = "S-out"),
                row(until.plus(h), "items_write", "update", session = "S-out"),
                // S-same: the write is in the same millisecond as the read: not strictly later
                row(since.plus(h.multipliedBy(4)), "items_read", "get", session = "S-same"),
                row(since.plus(h.multipliedBy(4)), "items_write", "update", session = "S-same"),
                // S-ok (control): the write is one millisecond later, inside the window
                row(since.plus(h.multipliedBy(3)), "items_read", "get", session = "S-ok"),
                row(since.plus(h.multipliedBy(3)).plusMillis(1), "items_write", "update", session = "S-ok")
            )

            val report = f.wasteOk(req(since.toString(), until.toString()))

            assertSignal("readsWithoutLaterWrite", report.readsWithoutLaterWrite, 2, 3, "0.6667", MCP_SESSIONS)
        }

    @Test
    fun `S11 a follow-up call at or after until, or in the same millisecond as the related row, is not a follow-through`(): Unit =
        runBlocking {
            val f = fx
            val since = Instant.parse("2026-10-12T00:00:00Z")
            val until = Instant.parse("2026-10-12T06:00:00Z")
            val r = since.plus(Duration.ofHours(5))
            val hit = hitsJson(H1 to listOf("edges"))
            f.seed(
                // A: the only follow-ups are at until and after it: outside the window
                row(r, "items_read", "related", session = "A", hits = hit),
                row(until, "items_read", "get", session = "A", versions = vers(H1 to 1)),
                row(until.plusSeconds(3600), "items_read", "get", session = "A", versions = vers(H1 to 1)),
                // B (control): one millisecond after r
                row(r, "items_read", "related", session = "B", hits = hit),
                row(r.plusMillis(1), "items_read", "get", session = "B", versions = vers(H1 to 1)),
                // C: the same millisecond as r
                row(r, "items_read", "related", session = "C", hits = hit),
                row(r, "items_read", "get", session = "C", versions = vers(H1 to 1))
            )

            val edges = f.wasteOk(req(since.toString(), until.toString())).relatedFollowThrough.getValue("edges")

            assertSignal("edges", edges, 1, 3, "0.3333", MCP_SESSIONS)
        }

    @Test
    fun `S11 an observation at until is not counted for redundantReads`(): Unit =
        runBlocking {
            val f = fx
            val since = Instant.parse("2026-10-12T00:00:00Z")
            val until = Instant.parse("2026-10-12T06:00:00Z")
            f.seed(
                // A: the repeat is at until, outside: 1 observation. B: both inside (the second is one millisecond before until).
                row(until.minusSeconds(60), "items_read", "get", session = "A", versions = vers(X1 to 1)),
                row(until, "items_read", "get", session = "A", versions = vers(X1 to 1)),
                row(until.minusSeconds(60), "items_read", "get", session = "B", versions = vers(X1 to 1)),
                row(until.minusMillis(1), "items_read", "get", session = "B", versions = vers(X1 to 1))
            )

            val report = f.wasteOk(req(since.toString(), until.toString()))

            assertSignal("redundantReads", report.redundantReads, 1, 3, "0.3333", MCP_SESSIONS)
        }

    // ------------------------------------------------------------------ S12 surface

    /** The MCP baseline of S12: one session A, mirrors S2, S4 and S6. */
    private fun baselineRows(): List<CallLogRecord> =
        listOf(
            row(t(10), "items_read", "get", session = "A", versions = vers(X1 to 1)),
            row(t(20), "items_read", "get", session = "A", versions = vers(X1 to 1)),
            row(t(30), "items_write", "update", session = "A"),
            row(t(40), "items_read", "get", session = "A", versions = vers(X2 to 1)),
            row(t(50), "items_read", "related", session = "A", hits = hitsJson(H1 to listOf("edges"))),
            row(t(60), "items_read", "get", session = "A", versions = vers(H1 to 2)),
            row(t(57), "items_read", "related", session = "A", hits = hitsJson(H2 to listOf("text")))
        )

    @Test
    fun `S12 REST rows with a session id leave the session signals unchanged, and REST rows count in the others`(): Unit =
        runBlocking {
            val f = fx
            f.seed(*baselineRows().toTypedArray())

            // Baseline by hand. ok session reads: t10 t20 t40 t50 t57 t60 = 6, only t10 and t20 precede the write at t30: 4/6.
            // observations: (X1,1) (X1,1) (X2,1) (H1,2) = 4 with 3 distinct: 1/4. edges: (t50,H1) followed by t60 -> 1/1;
            // text: (t57,H2), nothing names H2 -> 0/1.
            val before = f.wasteOk()
            assertSignal("baseline redundantReads", before.redundantReads, 1, 4, "0.25", MCP_SESSIONS)
            assertSignal("baseline readsWithoutLaterWrite", before.readsWithoutLaterWrite, 4, 6, "0.6667", MCP_SESSIONS)
            assertSignal("baseline edges", before.relatedFollowThrough.getValue("edges"), 1, 1, "1", MCP_SESSIONS)
            assertSignal("baseline text", before.relatedFollowThrough.getValue("text"), 0, 1, "0", MCP_SESSIONS)
            assertSignal("baseline overFetch", before.overFetch, 0, 4, "0", null)
            assertSignal("baseline emptyNext", before.emptyNext, 0, 0, null, null)
            assertSignal("baseline lifecycleFreeRejections", before.lifecycleFreeRejections, 0, 1, "0", null)

            val rest = CallLogRecord.SURFACE_REST
            f.seed(
                // would add observations and reads: REST gets in session A
                row(t(11), "items_read", "get", session = "A", surface = rest, versions = vers(X1 to 1)),
                row(t(12), "items_read", "get", session = "A", surface = rest, versions = vers(X1 to 1)),
                // a REST write after every read: would rescue t40, t50, t57, t60 if it counted
                row(t(70), "items_write", "update", session = "A", surface = rest),
                // a REST related row: would add a unit
                row(t(55), "items_read", "related", session = "A", surface = rest, hits = hitsJson(H3 to listOf("tags"))),
                // a REST follow-up naming H2: would make the text unit a follow-through
                row(t(90), "items_read", "get", session = "A", surface = rest, ids = idArray(H2)),
                // an MCP row without a session id: no session signal
                row(t(15), "items_read", "get", session = null, versions = vers(X1 to 1)),
                // rows that count in the surface-independent signals
                row(t(13), "items_read", "get", session = null, surface = rest, shape = "{\"fields\":[\"ancestors\"]}"),
                row(t(14), "items_read", "next", session = null, surface = rest, result = 0, eligible = 3),
                row(t(16), "items_write", "update", session = null, surface = rest, error = "invalid_transition", lf = 2)
            )

            val after = f.wasteOk()
            assertSignal("redundantReads", after.redundantReads, 1, 4, "0.25", MCP_SESSIONS)
            assertSignal("readsWithoutLaterWrite", after.readsWithoutLaterWrite, 4, 6, "0.6667", MCP_SESSIONS)
            assertSignal("edges", after.relatedFollowThrough.getValue("edges"), 1, 1, "1", MCP_SESSIONS)
            assertSignal("text", after.relatedFollowThrough.getValue("text"), 0, 1, "0", MCP_SESSIONS)
            assertSignal("tags", after.relatedFollowThrough.getValue("tags"), 0, 0, null, MCP_SESSIONS)
            // overFetch: the 4 baseline gets + REST gets t11 t12 t13 t90, REST next t14, MCP get t15 = 10 rows, one with fields
            assertSignal("overFetch", after.overFetch, 1, 10, "0.1", null)
            assertSignal("emptyNext", after.emptyNext, 1, 1, "1", null)
            // non-replayed write calls: t30 (MCP), t70 (REST), t16 (REST, LF 2)
            assertSignal("lifecycleFreeRejections", after.lifecycleFreeRejections, 2, 3, "0.6667", null)
        }

    // ------------------------------------------------------------------ S13 root scope on both join sides

    private val onlyR1 get() = fx.ctx(AccessScope.principal(setOf(ReportKit.R1), emptySet()))
    private val onlyR2 get() = fx.ctx(AccessScope.principal(setOf(ReportKit.R2), emptySet()))
    private val bothRoots get() = fx.ctx(AccessScope.principal(setOf(ReportKit.R1, ReportKit.R2), emptySet()))

    @Test
    fun `S13 a root scope reads neither the reads nor the rescuing writes of another root or of no root`(): Unit =
        runBlocking {
            val f = fx
            f.root(r1, "Alpha root")
            f.root(r2, "Beta root")
            // session A: the same X1 v1 read in R1, in R2 and with no root; a write in R2 after all of them.
            f.seed(
                row(t(10), "items_read", "get", session = "A", versions = vers(X1 to 1), root = r1),
                row(t(20), "items_read", "get", session = "A", versions = vers(X1 to 1), root = r2),
                row(t(30), "items_read", "get", session = "A", versions = vers(X1 to 1), root = null),
                row(t(40), "items_write", "update", session = "A", root = r2)
            )

            val all = f.wasteOk()
            assertSignal("unrestricted redundantReads", all.redundantReads, 2, 3, "0.6667", MCP_SESSIONS)
            assertSignal("unrestricted readsWithoutLaterWrite", all.readsWithoutLaterWrite, 0, 3, "0", MCP_SESSIONS)

            val scopedR1 = f.wasteOk(ReportWasteRequest(), onlyR1)
            assertSignal("R1 redundantReads", scopedR1.redundantReads, 0, 1, "0", MCP_SESSIONS)
            assertSignal("R1 readsWithoutLaterWrite (the R2 write is invisible)", scopedR1.readsWithoutLaterWrite, 1, 1, "1", MCP_SESSIONS)
            val scopedR2 = f.wasteOk(ReportWasteRequest(), onlyR2)
            assertSignal("R2 redundantReads", scopedR2.redundantReads, 0, 1, "0", MCP_SESSIONS)
            assertSignal("R2 readsWithoutLaterWrite", scopedR2.readsWithoutLaterWrite, 0, 1, "0", MCP_SESSIONS)
            val both = f.wasteOk(ReportWasteRequest(), bothRoots)
            assertSignal("R1+R2 redundantReads", both.redundantReads, 1, 2, "0.5", MCP_SESSIONS)
            assertSignal("R1+R2 readsWithoutLaterWrite", both.readsWithoutLaterWrite, 0, 2, "0", MCP_SESSIONS)

            val narrowed = f.wasteOk(req(rootId = r1.toString()))
            assertSignal("rootId R1 redundantReads", narrowed.redundantReads, 0, 1, "0", MCP_SESSIONS)
            assertSignal("rootId R1 readsWithoutLaterWrite", narrowed.readsWithoutLaterWrite, 1, 1, "1", MCP_SESSIONS)
            val narrowedInScope = f.wasteOk(req(rootId = r1.toString()), bothRoots)
            assertSignal("rootId R1 inside scope R1+R2", narrowedInScope.readsWithoutLaterWrite, 1, 1, "1", MCP_SESSIONS)

            val none = f.wasteOk(ReportWasteRequest(), f.ctx(AccessScope.principal(emptySet(), emptySet())))
            assertSignal("empty root set redundantReads", none.redundantReads, 0, 0, null, MCP_SESSIONS)
            assertSignal("empty root set readsWithoutLaterWrite", none.readsWithoutLaterWrite, 0, 0, null, MCP_SESSIONS)
        }

    @Test
    fun `S13 a root scope reads neither the related rows nor the follow-up calls of another root`(): Unit =
        runBlocking {
            val f = fx
            f.root(r1, "Alpha root")
            f.root(r2, "Beta root")
            // R1 related row finds H1; its follow-ups (target_ids H1) are in R2 and in no root.
            // R2 related row finds H2; its follow-up is in R1.
            f.seed(
                row(t(50), "items_read", "related", session = "A", hits = hitsJson(H1 to listOf("edges")), root = r1),
                row(t(55), "items_read", "related", session = "A", hits = hitsJson(H2 to listOf("edges")), root = r2),
                row(t(60), "items_read", "get", session = "A", ids = idArray(H1), root = r2),
                row(t(65), "items_read", "get", session = "A", ids = idArray(H2), root = r1),
                row(t(70), "items_read", "get", session = "A", ids = idArray(H1), root = null)
            )

            fun edges(report: WasteReport) = report.relatedFollowThrough.getValue("edges")

            assertSignal("unrestricted", edges(f.wasteOk()), 2, 2, "1", MCP_SESSIONS)
            // R1 owns the unit (t50, H1), but both follow-ups naming H1 are invisible to it; the follow-up naming H2 is visible
            // but its related row (R2) is not, so it makes no unit: 0 of 1.
            assertSignal("R1", edges(f.wasteOk(ReportWasteRequest(), onlyR1)), 0, 1, "0", MCP_SESSIONS)
        }

    @Test
    fun `S13 a root scope narrows the surface-independent signals to its rows`(): Unit =
        runBlocking {
            val f = fx
            f.root(r1, "Alpha root")
            f.root(r2, "Beta root")
            f.seed(
                row(t(10), "items_read", "next", result = 0, eligible = 3, root = r1),
                row(t(20), "items_read", "next", result = 0, eligible = 3, root = r2),
                row(t(30), "items_read", "next", result = 0, eligible = 3, root = null),
                row(t(40), "items_write", "update", error = "invalid_transition", lf = 1, root = r1),
                row(t(50), "items_write", "update", error = "invalid_transition", lf = 1, root = r2),
                row(t(60), "items_write", "update", root = null)
            )

            val all = f.wasteOk()
            assertSignal("unrestricted emptyNext", all.emptyNext, 3, 3, "1", null)
            assertSignal("unrestricted lifecycleFreeRejections", all.lifecycleFreeRejections, 2, 3, "0.6667", null)
            val scoped = f.wasteOk(ReportWasteRequest(), onlyR1)
            assertSignal("R1 emptyNext", scoped.emptyNext, 1, 1, "1", null)
            assertSignal("R1 lifecycleFreeRejections", scoped.lifecycleFreeRejections, 1, 1, "1", null)
        }

    // ------------------------------------------------------------------ S14 rounding

    @Test
    fun `S14 the value is rounded half up to four decimals`(): Unit =
        runBlocking {
            // 1/32 = 0.03125 exactly: half-up gives 0.0313, half-even would give 0.0312
            val rows = mutableListOf<CallLogRecord>()
            rows += row(t(1), "items_read", "get", shape = "{\"fields\":[\"ancestors\"]}")
            for (i in 2..32) rows += row(t(i.toLong()), "items_read", "get")
            val f = fx
            f.seed(*rows.toTypedArray())

            val signal = f.wasteOk().overFetch

            assertSignal("1/32", signal, 1, 32, "0.0313", null)
            assertEquals(0.0313, assertNotNull(signal.value).toDouble())
        }

    @Test
    fun `S14 two thirds is 0 6667 as a number`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                row(t(10), "items_read", "next", result = 0, eligible = 1),
                row(t(20), "items_read", "next", result = 0, eligible = 1),
                row(t(30), "items_read", "next", result = 1, eligible = 1)
            )

            val twoThirds = f.wasteOk().emptyNext

            assertSignal("2/3", twoThirds, 2, 3, "0.6667", null)
            assertEquals(0.6667, assertNotNull(twoThirds.value).toDouble())
        }

    @Test
    fun `S14 WasteSignal of rounds half up and omits the value for a zero denominator`() {
        assertEquals(BigDecimal("0.0313"), WasteSignal.of(WasteCount(1, 32)).value?.setScale(4))
        assertEquals(BigDecimal("0.6667"), WasteSignal.of(WasteCount(2, 3)).value?.setScale(4))
        assertEquals(BigDecimal("0.3333"), WasteSignal.of(WasteCount(1, 3)).value?.setScale(4))
        assertEquals(BigDecimal("0.1250"), WasteSignal.of(WasteCount(1, 8)).value?.setScale(4))
        assertEquals(BigDecimal("2.5000"), WasteSignal.of(WasteCount(5, 2)).value?.setScale(4))
        assertEquals(BigDecimal("0.0000"), WasteSignal.of(WasteCount(0, 5)).value?.setScale(4))
        assertNull(WasteSignal.of(WasteCount(0, 0)).value)
        assertNull(WasteSignal.of(WasteCount(0, 0), MCP_SESSIONS).value)
        assertEquals(MCP_SESSIONS, WasteSignal.of(WasteCount(1, 2), MCP_SESSIONS).basis)
        assertNull(WasteSignal.of(WasteCount(1, 2)).basis)
    }

    @Test
    fun `S14 a signal is a JSON object with a numeric value, counts and the basis only when it has one`() {
        val plain = signalJson(WasteSignal.of(WasteCount(1, 4)))
        assertFalse(plain.getValue("value").jsonPrimitive.isString, "value is a JSON number, not text")
        assertEquals(
            0.25,
            plain
                .getValue("value")
                .jsonPrimitive.content
                .toDouble()
        )
        assertEquals("1", plain.getValue("numerator").jsonPrimitive.content)
        assertEquals("4", plain.getValue("denominator").jsonPrimitive.content)
        assertFalse("basis" in plain, "no basis on a non-session signal: $plain")

        val session = signalJson(WasteSignal.of(WasteCount(0, 5), MCP_SESSIONS))
        assertEquals(
            0.0,
            session
                .getValue("value")
                .jsonPrimitive.content
                .toDouble()
        )
        assertEquals(MCP_SESSIONS, session.getValue("basis").jsonPrimitive.content)

        val empty = signalJson(WasteSignal.of(WasteCount(0, 0), MCP_SESSIONS))
        assertFalse("value" in empty, "no value for a zero denominator: $empty")
        assertEquals("0", empty.getValue("numerator").jsonPrimitive.content)
        assertEquals("0", empty.getValue("denominator").jsonPrimitive.content)
        assertEquals(MCP_SESSIONS, empty.getValue("basis").jsonPrimitive.content)
    }
}

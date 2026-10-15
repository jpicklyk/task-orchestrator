package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.PARITY_NOW
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 74bb6db9, R28) for scenario S15 of the frozen test-plan (NEW-SURFACE): the FULL `report`
 * rollup response for a run, as the REST `POST /api/v1/ops/report` returns it, stays small. A 50-member run shaped like the W6 brief
 * example (one stalled seat, one decision class, one severity, one blocked reason, six-digit event and cost counts) is at most 1,500
 * UTF-8 bytes; the worst case (five stalled seats at the cap, every blocked reason in `byReason` and in `dwellMs`, three classes,
 * three severities, a budget limit, ten-digit durations) is at most 2,000.
 *
 * Oracle: task-scope AC11 and decision D4 (1,500 B for the brief-shaped run, 2,000 B for the worst case). The bounds are the frozen
 * numbers, not a measurement of the implementation.
 *
 * Method: the response is the REST body of `POST /api/v1/ops/report` through the production wiring (the parity harness's
 * [installParityRestApp]), so the envelope is part of the measured size, exactly as a caller receives it. The six-digit counts come from
 * a handful of recursive-CTE inserts. Each fixture proves itself: the parsed response must show 50 items in all five roles, six-digit
 * counts, and the stalled / reason / class / severity cardinalities the scenario names, so a response that is small because it is empty
 * or an error cannot pass.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: see [RunKit] and [RollupRaw]; every bulk call_log row has outcome `error` exactly when it
 * carries an error code, its token estimate is the byte count divided by 4 and its request id is `s` plus seven digits; every bulk
 * event is stamped with the run and its root is the root of its entity item.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunRollupSizeTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val token = ParityToken("run-rollup-size-reader", setOf(ApiCapability.READ))

    private val root = ReportKit.R1
    private val run = RunKit.id("a8000001")

    private val transitionsTotal = 120_000L
    private val cascades = 110_000L
    private val rejected = 100_500L
    private val notes = 100_200L
    private val calls = 100_300L
    private val errors = 100_100L
    private val bytesEach = 1_234_567_890L
    private val tokensEach = bytesEach / 4

    private fun bulkEvents(
        w: RollupRaw.Writer,
        type: String,
        kind: String,
        count: Long,
        entity: UUID,
        firstData: String,
        firstCount: Long,
        otherData: String
    ) {
        w.sql(
            "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < ?) " +
                "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, run_id, data) " +
                "SELECT randomblob(16), '2026-10-01 00:00:00.000', ?, ?, ?, ?, ?, CASE WHEN x <= ? THEN ? ELSE ? END FROM cnt",
            count,
            root,
            kind,
            entity,
            type,
            run,
            firstCount,
            firstData,
            otherData
        )
    }

    private suspend fun seed(
        d: SqliteTestDatabase,
        worst: Boolean
    ) {
        val f = ReportFixture(d, SettableClock(PARITY_NOW))
        val members = (1..50).map { RunKit.id("a8100000", it) }
        val roles = listOf("queue", "work", "review", "blocked", "terminal")
        val blockedReasons = if (worst) listOf("awaiting_decision", "dependency", "resource", "manual") else listOf("dependency")
        val opened = PARITY_NOW.minus(Duration.ofDays(20))
        RollupRaw.write(d.jdbcUrl) {
            item(root, null, "queue", 0, null)
            var blockedSeen = 0
            for ((index, id) in members.withIndex()) {
                val role = roles[(index + 1) % 5]
                item(id, root, role, 1, root)
                if (role == "blocked") {
                    val reason = blockedReasons[blockedSeen++ % blockedReasons.size]
                    blocked(id, reason, if (reason == "awaiting_decision") RunKit.id("a8900000", blockedSeen) else null)
                    // the worst case carries a dwell for every reason: an unstamped block that was never released
                    if (worst) moved(opened.plusSeconds(60L * blockedSeen), id, root, "blocked", reason)
                }
            }
        }
        RunKit.run(
            f,
            run,
            root = root,
            members = members,
            openedAt = opened,
            budgetTokens = if (worst) 1_000_000 else null,
            reportedTokens = 123_456,
            observedTokens = 654_321
        )
        // decisions: the brief shape has one class and one severity; the worst case has three of each
        val classes = if (worst) listOf("question", "attempt-cap", "run-budget") else listOf("question")
        val severities =
            if (worst) {
                listOf(
                    DecisionRequestSeverity.HIGH,
                    DecisionRequestSeverity.NORMAL,
                    DecisionRequestSeverity.LOW
                )
            } else {
                listOf(DecisionRequestSeverity.NORMAL)
            }
        for (n in 0 until 12) {
            RunKit.decision(
                f,
                RunKit.id("a8200000", n + 1),
                run,
                decisionClass = classes[n % classes.size],
                severity = severities[n % severities.size],
                root = root
            )
        }
        RollupRaw.write(d.jdbcUrl) {
            // seats: one stalled seat (brief) or six stalled and fourteen live ones (worst: five entries at the cap, count six)
            val stalledCount = if (worst) 6 else 1
            for (n in 0 until stalledCount) {
                seat(
                    RunKit.id("a8300000", n + 1),
                    run,
                    members[n],
                    "implementer",
                    1 + n % 3,
                    opened,
                    PARITY_NOW.minusSeconds(
                        100_000L + 1_000L * n
                    )
                )
            }
            if (worst) {
                for (n in stalledCount until 20) {
                    seat(RunKit.id("a8300000", n + 1), run, members[n], "implementer", 1, opened, PARITY_NOW.minusSeconds(5L + n))
                }
            }
            val noteId = RunKit.id("a8400000")
            // one early transition keeps the run stamp on the oldest row; the bulk rows follow
            bulkEvents(
                this,
                DomainEvent.ITEM_TRANSITIONED,
                DomainEvent.KIND_ITEM,
                transitionsTotal,
                members[0],
                "{\"origin\":\"cascade\"}",
                cascades,
                "{\"origin\":\"direct\"}"
            )
            bulkEvents(this, DomainEvent.TRANSITION_REJECTED, DomainEvent.KIND_ITEM, rejected, members[1], "{}", rejected, "{}")
            bulkEvents(this, DomainEvent.NOTE_UPSERTED, "note", notes, noteId, "{}", notes, "{}")
            sql(
                "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < ?) " +
                    "INSERT INTO call_log (req_id, at, surface, tool, outcome, error_code, attempts, latency_ms, response_bytes, " +
                    "response_tokens_est, token_method, root_id, run_id) " +
                    "SELECT printf('s%07d', x), '2026-10-01 00:00:00.000', 'mcp', 'advance', " +
                    "CASE WHEN x <= ? THEN 'error' ELSE 'ok' END, CASE WHEN x <= ? THEN 'gate_blocked' ELSE NULL END, " +
                    "2, 5, ?, ?, 'bytes/4', ?, ? FROM cnt",
                calls,
                errors,
                errors,
                bytesEach,
                tokensEach,
                root,
                run
            )
        }
    }

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun measure(worst: Boolean): Pair<Int, String> {
        var text = ""
        var status = 0
        SqliteTestDatabase.open(clock = PARITY_CLOCK).use { d ->
            runBlocking { seed(d, worst) }
            val dir = Files.createTempDirectory("run-rollup-size-")
            try {
                val rig = EventLogRig.build(d, dir, clock = PARITY_CLOCK)
                testApplication {
                    application { installParityRestApp(rig, parityAuthConfig(token), registry) }
                    val response =
                        client.post("/api/v1/ops/report") {
                            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                            contentType(ContentType.Application.Json)
                            setBody("{\"select\":\"rollup\",\"runId\":\"" + run.toString().take(8) + "\"}")
                        }
                    text = response.bodyAsText()
                    status = response.status.value
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals(200, status, text)
        assertEquals("true", body.text("ok"), text)
        val data = body.obj("data")
        // the fixture is real: 50 members in all five roles, six-digit counts everywhere
        assertEquals("50", data.obj("itemCounts").text("total"), text)
        assertEquals(setOf("queue", "work", "review", "blocked", "terminal"), data.obj("itemCounts").obj("byRole").keys, text)
        val transitions = data.obj("transitions")
        assertEquals(transitionsTotal.toString(), transitions.text("total"), text)
        assertEquals(cascades.toString(), transitions.text("cascades"), text)
        assertEquals(rejected.toString(), transitions.text("rejected"), text)
        assertEquals(notes.toString(), data.obj("notes").text("written"), text)
        val cost = data.obj("cost")
        assertEquals(calls.toString(), cost.text("calls"), text)
        assertEquals(errors.toString(), cost.text("errors"), text)
        assertEquals(calls.toString(), cost.text("retries"), text)
        assertEquals((calls * bytesEach).toString(), cost.text("responseBytes"), text)
        assertEquals((calls * tokensEach).toString(), cost.text("responseTokensEst"), text)
        assertEquals("run", cost.text("basis"), text)
        assertEquals("open", data.obj("run").text("status"), text)
        return text.toByteArray(Charsets.UTF_8).size to text
    }

    @Test
    fun `S15 the full run rollup response for a brief-shaped 50 member run is at most 1500 bytes`() {
        val (size, text) = measure(worst = false)
        val data = Json.parseToJsonElement(text).jsonObject.obj("data")

        assertEquals(
            1,
            data
                .obj("seats")
                .getValue("stalled")
                .jsonArray.size,
            "one stalled seat: $text"
        )
        assertEquals(1, data.obj("blocked").obj("byReason").size, "one blocked reason: $text")
        assertFalse("dwellMs" in data.obj("blocked"), "the brief shape has no block events: $text")
        assertEquals(1, data.obj("decisions").obj("byClass").size, "one class: $text")
        assertEquals(1, data.obj("decisions").obj("bySeverity").size, "one severity: $text")
        assertFalse("limit" in data.obj("budget"), "no budget limit in the brief shape: $text")
        assertTrue(size > 300, "a real rollup is more than a stub: $size bytes\n$text")
        assertTrue(size <= 1_500, "the brief-shaped run rollup response is $size bytes (limit 1,500):\n$text")
    }

    @Test
    fun `S15 the full run rollup response for the worst case is at most 2000 bytes`() {
        val (size, text) = measure(worst = true)
        val data = Json.parseToJsonElement(text).jsonObject.obj("data")

        assertEquals(
            5,
            data
                .obj("seats")
                .getValue("stalled")
                .jsonArray.size,
            "five stalled seats at the cap: $text"
        )
        assertEquals(4, data.obj("blocked").obj("byReason").size, "every blocked reason: $text")
        assertEquals(4, data.obj("blocked").obj("dwellMs").size, "a dwell for every reason: $text")
        assertEquals(3, data.obj("decisions").obj("byClass").size, "three classes: $text")
        assertEquals(3, data.obj("decisions").obj("bySeverity").size, "three severities: $text")
        assertEquals("1000000", data.obj("budget").text("limit"), "a budget limit: $text")
        assertTrue(size > 300, "the worst case is far more than a stub: $size bytes\n$text")
        assertTrue(size <= 2_000, "the worst-case run rollup response is $size bytes (limit 2,000):\n$text")
    }
}

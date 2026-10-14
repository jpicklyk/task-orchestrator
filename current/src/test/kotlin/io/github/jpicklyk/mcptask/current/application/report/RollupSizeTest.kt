package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 1be2db5e, R16) for scenario S18 of the frozen test-plan (NEW-SURFACE): the FULL
 * `report` rollup response for a 50-item subtree that holds every role, six-digit counts everywhere and a clamped window is at
 * most 1,500 UTF-8 bytes.
 *
 * Oracle: task-scope AC11 (a response of at most 1,500 bytes at 50 items) and the shape rule of AC7/AC8 (counts and short ids
 * only, so the size depends on the digits, not on the number of items). The bound is the frozen number, not a measurement.
 *
 * Method: the response is the REST body of `POST /api/v1/ops/report` through the production wiring (the parity harness's
 * [installParityRestApp]), so the envelope (`ok`, `data`, `meta`) is part of the measured size, exactly as a caller receives it.
 * The six-digit counts are produced by a handful of recursive-CTE inserts, not by 100k separate writes. The fixture also proves
 * itself: the parsed response must show 50 items in all five roles, `clamped: true` and every count at six digits, so a response
 * that is small because it is empty or an error cannot pass.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: see [RollupRaw]; every bulk call_log row has outcome `error` exactly when it
 * carries an error code, its token estimate is the byte count divided by 4, and its request id is `s` plus seven digits (8
 * characters).
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RollupSizeTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val token = ParityToken("rollup-size-reader", setOf(ApiCapability.READ))

    private val root = ReportKit.R1
    private val anchor = RollupRaw.id("a5000001")

    private val transitionsTotal = 120_000L
    private val cascades = 110_000L
    private val rejected = 100_500L
    private val notes = 100_200L
    private val calls = 100_300L
    private val errors = 100_100L
    private val bytesEach = 1_234_567_890L
    private val tokensEach = bytesEach / 4

    private fun seed(d: SqliteTestDatabase) {
        RollupRaw.write(d.jdbcUrl) {
            item(root, null, "queue", 0, null)
            item(anchor, root, "work", 1, root)
            val roles = listOf("queue", "work", "review", "blocked", "terminal")
            for (n in 1..49) item(RollupRaw.id("a5100000", n), anchor, roles[n % 5], 2, root)
            note(RollupRaw.id("d5000001"), RollupRaw.id("a5100000", 3), "k")

            val transitioned = RollupRaw.id("a5100000", 1)
            val rejectedItem = RollupRaw.id("a5100000", 2)
            val noteId = RollupRaw.id("d5000001")

            // one early transition so the span (about 47 days) exceeds 31 days and the default window is clamped
            event(DomainEvent.ITEM_TRANSITIONED, java.time.Instant.parse("2026-08-15T00:00:00Z"), transitioned, root)

            bulkEvents(
                DomainEvent.ITEM_TRANSITIONED,
                DomainEvent.KIND_ITEM,
                transitionsTotal,
                transitioned,
                root,
                "{\"origin\":\"cascade\"}",
                cascades,
                "{\"origin\":\"direct\"}"
            )
            bulkEvents(DomainEvent.TRANSITION_REJECTED, DomainEvent.KIND_ITEM, rejected, rejectedItem, root, "{}", rejected, "{}")
            bulkEvents(DomainEvent.NOTE_UPSERTED, "note", notes, noteId, root, "{}", notes, "{}")

            sql(
                "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < ?) " +
                    "INSERT INTO call_log (req_id, at, surface, tool, outcome, error_code, attempts, latency_ms, response_bytes, " +
                    "response_tokens_est, token_method, target_ids, root_id) " +
                    "SELECT printf('s%07d', x), '2026-10-01 00:00:00.000', 'mcp', 'advance', " +
                    "CASE WHEN x <= ? THEN 'error' ELSE 'ok' END, CASE WHEN x <= ? THEN 'gate_blocked' ELSE NULL END, " +
                    "2, 5, ?, ?, 'bytes/4', ?, ? FROM cnt",
                calls,
                errors,
                errors,
                bytesEach,
                tokensEach,
                RollupRaw.targets(transitioned),
                root
            )
        }
    }

    private fun RollupRaw.Writer.bulkEvents(
        type: String,
        kind: String,
        count: Long,
        entity: UUID,
        root: UUID,
        firstData: String,
        firstCount: Long,
        otherData: String
    ) {
        sql(
            "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < ?) " +
                "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, req_id, data) " +
                "SELECT randomblob(16), '2026-10-01 00:00:00.000', ?, ?, ?, ?, NULL, CASE WHEN x <= ? THEN ? ELSE ? END FROM cnt",
            count,
            root,
            kind,
            entity,
            type,
            firstCount,
            firstData,
            otherData
        )
    }

    @Test
    fun `S18 the full rollup response for 50 items, every role, six-digit counts and a clamped window is at most 1500 bytes`() {
        SqliteTestDatabase.open(clock = PARITY_CLOCK).use { d ->
            seed(d)
            val dir = Files.createTempDirectory("rollup-size-")
            try {
                val rig = EventLogRig.build(d, dir, clock = PARITY_CLOCK)
                var text = ""
                var status = 0
                testApplication {
                    application { installParityRestApp(rig, parityAuthConfig(token), registry) }
                    val response =
                        client.post("/api/v1/ops/report") {
                            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                            contentType(ContentType.Application.Json)
                            setBody("{\"select\":\"rollup\",\"anchorId\":\"" + anchor.toString().take(8) + "\"}")
                        }
                    text = response.bodyAsText()
                    status = response.status.value
                }

                val body = Json.parseToJsonElement(text).jsonObject
                assertEquals(200, status, text)
                assertEquals("true", body.getValue("ok").jsonPrimitive.content, text)
                val data = body.getValue("data").jsonObject
                // the fixture is real: 50 items in all five roles, a clamped window, six-digit counts everywhere
                assertEquals(
                    "50",
                    data
                        .getValue("items")
                        .jsonObject
                        .getValue("total")
                        .jsonPrimitive.content,
                    text
                )
                assertEquals(
                    setOf("queue", "work", "review", "blocked", "terminal"),
                    data
                        .getValue("items")
                        .jsonObject
                        .getValue("byRole")
                        .jsonObject.keys,
                    text
                )
                assertEquals(
                    "true",
                    data
                        .getValue("window")
                        .jsonObject
                        .getValue("clamped")
                        .jsonPrimitive.content,
                    text
                )
                val transitions = data.getValue("transitions").jsonObject
                assertEquals(transitionsTotal.toString(), transitions.getValue("total").jsonPrimitive.content, text)
                assertEquals(cascades.toString(), transitions.getValue("cascades").jsonPrimitive.content, text)
                assertEquals(rejected.toString(), transitions.getValue("rejected").jsonPrimitive.content, text)
                assertEquals(
                    notes.toString(),
                    data
                        .getValue("notes")
                        .jsonObject
                        .getValue("written")
                        .jsonPrimitive.content,
                    text
                )
                val cost = data.getValue("cost").jsonObject
                assertEquals(calls.toString(), cost.getValue("calls").jsonPrimitive.content, text)
                assertEquals(errors.toString(), cost.getValue("errors").jsonPrimitive.content, text)
                assertEquals(calls.toString(), cost.getValue("retries").jsonPrimitive.content, text)
                assertEquals((calls * bytesEach).toString(), cost.getValue("responseBytes").jsonPrimitive.content, text)
                assertEquals((calls * tokensEach).toString(), cost.getValue("responseTokensEst").jsonPrimitive.content, text)
                assertEquals("targets", cost.getValue("basis").jsonPrimitive.content, text)

                val size = text.toByteArray(Charsets.UTF_8).size
                assertTrue(size > 300, "a real rollup is more than a stub: $size bytes\n$text")
                assertTrue(size <= 1_500, "the full rollup response is $size bytes (limit 1,500):\n$text")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}

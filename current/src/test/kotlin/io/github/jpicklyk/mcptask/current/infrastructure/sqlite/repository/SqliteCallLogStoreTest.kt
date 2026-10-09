package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.CallLogAppendResult
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for item 8abb69e2 (P10): [SqliteCallLogStore] over the real V21 `call_log` table.
 *
 * Oracles: migration-assessment section 1 (frozen DDL: column types, 23-character UTC `at` text, `replayed` stored as
 * 0/1, `run_id` BLOB like `events.run_id`, PRIMARY KEY on req_id); task-scope What-to-build item 5 (one batch insert
 * per append, joins the ambient unit, a duplicate req_id is ignored PER ROW, `append` returns rows inserted) and
 * test-plan S12 (a batch with two records sharing a req_id plus one other yields two rows and no exception).
 */
class SqliteCallLogStoreTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val uow get() = sqlite.unitOfWork()
    private val store get() = SqliteCallLogStore(sqlite.databaseManager)

    private fun append(records: List<CallLogRecord>): Int =
        runBlocking {
            when (val outcome = uow.write("test.callLogAppend") { Outcome.Ok(store.append(records)) }) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> error("unit failed: ${outcome.error.message}")
            }
        }

    private fun rows() = CallLogRows.read(sqlite.jdbcUrl)

    @Test
    fun `an empty batch inserts nothing and returns zero`() {
        assertEquals(0, append(emptyList()))
        assertEquals(0, rows().size)
    }

    @Test
    fun `a record with every column set round-trips exactly`() {
        val run = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        val full =
            CallLogRecord(
                reqId = "bl0req01",
                at = Instant.parse("2026-03-03T12:17:32.789Z"),
                principalId = "actor-one",
                principalKind = "subagent",
                proofStatus = "verified",
                host = "host-call-1",
                sessionId = "session-call-1",
                runId = run,
                seat = "implementer",
                surface = CallLogRecord.SURFACE_MCP,
                tool = "advance_item",
                operation = "start",
                targetIds = "[\"a\"]",
                targetVersions = "{\"a\":3}",
                requestShape = "{\"includeAncestors\":true}",
                outcome = CallLogRecord.OUTCOME_ERROR,
                errorCode = "GATE_BLOCKED",
                attempts = 2,
                latencyMs = 41,
                requestBytes = 310,
                responseBytes = 1290,
                responseTokensEst = 323,
                tokenMethod = "bytes/4",
                replayed = true,
                batchSize = 5,
                failedCount = 1,
                resultCount = 4,
                eligibleCount = 9
            )
        assertEquals(1, append(listOf(full)))

        val row = rows().single()
        assertEquals("bl0req01", row["req_id"])
        assertEquals("2026-03-03 12:17:32.789", row["at"], "canonical 23-character UTC text")
        assertEquals("actor-one", row["principal_id"])
        assertEquals("subagent", row["principal_kind"])
        assertEquals("verified", row["proof_status"])
        assertEquals("host-call-1", row["host"])
        assertEquals("session-call-1", row["session_id"])
        assertContentEquals(BaselineDataset.bytes(run), row["run_id"] as ByteArray, "run_id is the 16-byte UUID BLOB")
        assertEquals("implementer", row["seat"])
        assertEquals("mcp", row["surface"])
        assertEquals("advance_item", row["tool"])
        assertEquals("start", row["operation"])
        assertEquals("[\"a\"]", row["target_ids"])
        assertEquals("{\"a\":3}", row["target_versions"])
        assertEquals("{\"includeAncestors\":true}", row["request_shape"])
        assertEquals("error", row["outcome"])
        assertEquals("GATE_BLOCKED", row["error_code"])
        assertEquals(2L, row["attempts"])
        assertEquals(41L, row["latency_ms"])
        assertEquals(310L, row["request_bytes"])
        assertEquals(1290L, row["response_bytes"])
        assertEquals(323L, row["response_tokens_est"])
        assertEquals("bytes/4", row["token_method"])
        assertEquals(1L, row["replayed"], "true is stored as 1")
        assertEquals(5L, row["batch_size"])
        assertEquals(1L, row["failed_count"])
        assertEquals(4L, row["result_count"])
        assertEquals(9L, row["eligible_count"])
    }

    @Test
    fun `a minimal record stores nulls for every optional column and zero for replayed`() {
        assertEquals(1, append(listOf(sampleCallLogRecord("min00001"))))
        val row = rows().single()
        for (
        column in
        listOf(
            "principal_id",
            "principal_kind",
            "proof_status",
            "host",
            "session_id",
            "run_id",
            "seat",
            "operation",
            "target_ids",
            "target_versions",
            "request_shape",
            "error_code",
            "request_bytes",
            "response_bytes",
            "response_tokens_est",
            "token_method",
            "batch_size",
            "failed_count",
            "result_count",
            "eligible_count"
        )
        ) {
            assertNull(row[column], "$column must be NULL")
        }
        assertEquals(0L, row["replayed"])
        assertEquals(1L, row["attempts"])
        assertEquals(1L, row["latency_ms"])
        assertEquals("ok", row["outcome"])
    }

    @Test
    fun `sub-millisecond precision is stored as 23 characters of canonical text`() {
        append(listOf(sampleCallLogRecord("ns000001", at = Instant.parse("2026-03-04T05:06:07.123456789Z"))))
        val at = rows().single()["at"] as String
        assertEquals(23, at.length, "the length CHECK admits exactly 23 characters: $at")
        assertTrue(at.startsWith("2026-03-04 05:06:07.12"), at)
    }

    @Test
    fun `S12 a batch with two records sharing a req_id plus another yields two rows, no exception, first one wins`() {
        val first = sampleCallLogRecord("dup00001", tool = "first")
        val second = sampleCallLogRecord("dup00001", tool = "second")
        val other = sampleCallLogRecord("dup00002", tool = "other")
        assertEquals(2, append(listOf(first, second, other)), "append returns the number of rows actually inserted")
        val byId = rows().associateBy { it["req_id"] }
        assertEquals(setOf<Any?>("dup00001", "dup00002"), byId.keys)
        assertEquals("first", byId["dup00001"]!!["tool"], "the earlier record keeps the key")
        assertEquals("other", byId["dup00002"]!!["tool"])
    }

    @Test
    fun `S12 a duplicate of an already stored req_id is ignored across batches and does not poison the rest`() {
        assertEquals(1, append(listOf(sampleCallLogRecord("old00001", tool = "original"))))
        val inserted =
            append(listOf(sampleCallLogRecord("old00001", tool = "replacement"), sampleCallLogRecord("new00001", tool = "fresh")))
        assertEquals(1, inserted, "only the fresh record is inserted")
        val byId = rows().associateBy { it["req_id"] }
        assertEquals("original", byId["old00001"]!!["tool"], "the stored row is not overwritten")
        assertEquals("fresh", byId["new00001"]!!["tool"], "a duplicate earlier in the batch does not block later records")
        assertEquals(
            0,
            append(listOf(sampleCallLogRecord("old00001"), sampleCallLogRecord("new00001"))),
            "an all-duplicate batch inserts nothing"
        )
        assertEquals(2, rows().size)
    }

    @Test
    fun `a large batch inserts every row`() {
        val batch = List(500) { sampleCallLogRecord("bulk" + it.toString().padStart(4, '0')) }
        assertEquals(500, append(batch))
        assertEquals(500, CallLogRows.count(sqlite.jdbcUrl))
    }

    @Test
    fun `append joins the ambient unit so a throw after it rolls the rows back`() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                uow.write<Unit>("test.callLogRollback") {
                    store.append(listOf(sampleCallLogRecord("rb000001")))
                    throw IllegalStateException("boom after append")
                }
            }
        }
        assertEquals(0, CallLogRows.count(sqlite.jdbcUrl), "the row written inside the failed unit must not be committed")
        assertEquals(1, append(listOf(sampleCallLogRecord("rb000001"))), "and the key is free afterwards")
    }

    // ------------------------------------------------------------------ F7

    private fun appendCounted(records: List<CallLogRecord>): CallLogAppendResult =
        runBlocking {
            when (val outcome = uow.write("test.callLogAppendCounted") { Outcome.Ok(store.appendCounted(records)) }) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> error("unit failed: ${outcome.error.message}")
            }
        }

    @Test
    fun `F7 a duplicate req_id is not rejected, only the fresh rows are inserted`() {
        assertEquals(CallLogAppendResult(inserted = 1, rejected = 0), appendCounted(listOf(sampleCallLogRecord("f7dup001"))))
        val result = appendCounted(listOf(sampleCallLogRecord("f7dup001", tool = "again"), sampleCallLogRecord("f7dup002")))
        assertEquals(1, result.inserted, "only the fresh row is inserted")
        assertEquals(0, result.rejected, "a primary-key conflict is a duplicate, not a rejection")
        assertEquals(2, rows().size)
        assertEquals(CallLogAppendResult(inserted = 0, rejected = 0), appendCounted(listOf(sampleCallLogRecord("f7dup001"))))
    }

    @Test
    fun `F7 rows that violate a CHECK are rejected and counted apart from duplicates, and valid rows still land`() {
        val good = sampleCallLogRecord("f7good01")
        val negativeLatency = sampleCallLogRecord("f7neg001").copy(latencyMs = -1)
        val zeroAttempts = sampleCallLogRecord("f7att001").copy(attempts = 0)
        val shortId = sampleCallLogRecord("f7short") // 7 characters violates CHECK (length(req_id) = 8)
        val result = appendCounted(listOf(good, negativeLatency, zeroAttempts, shortId))
        assertEquals(1, result.inserted)
        assertEquals(3, result.rejected, "three CHECK violations are counted as rejected")
        assertEquals(listOf("f7good01"), rows().map { it["req_id"] })
    }

    @Test
    fun `F7 duplicates and CHECK violations in one batch are told apart`() {
        append(listOf(sampleCallLogRecord("f7mix001")))
        val result =
            appendCounted(
                listOf(
                    sampleCallLogRecord("f7mix001", tool = "dup"),
                    sampleCallLogRecord("f7mix002").copy(latencyMs = -5),
                    sampleCallLogRecord("f7mix003")
                )
            )
        assertEquals(CallLogAppendResult(inserted = 1, rejected = 1), result, "the duplicate is neither inserted nor rejected")
        assertEquals(setOf<Any?>("f7mix001", "f7mix003"), rows().map { it["req_id"] }.toSet())
    }
}

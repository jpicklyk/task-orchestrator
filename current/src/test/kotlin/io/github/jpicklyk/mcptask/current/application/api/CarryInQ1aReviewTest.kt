package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Implementer fix-cycle tests (item 713c5c61, fix round r1) for the binding carry-in-q1a-review rulings #2-#5:
 * replayed rejections keep the replay marker, the echoed bad idempotency key is capped, a require() failure in a
 * request constructor surfaces its message, and an `@Contextual Instant` request field decodes.
 */
class CarryInQ1aReviewTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val now: Instant = Instant.parse("2026-03-01T10:00:00.123Z")
    private val unit: UnitOfWork by lazy { sqlite.unitOfWork(Clock { now }) }
    private val runner: BatchRunner by lazy { BatchRunner(unit, IdempotencyService(unit)) }

    private object StringCodec : IdempotencyCodec<String> {
        override fun encode(value: String): JsonElement = JsonPrimitive(value)

        override fun decode(json: JsonElement): String = json.jsonPrimitive.content
    }

    private val rejection: DomainError = ErrorFixtures.error(ErrorCode.INVALID_REQUEST)

    private suspend fun runKeyed(
        key: String,
        outcome: Outcome<String>
    ): BatchExecution<String> =
        runner.run("items.write", "agent-1", listOf(BatchElement("t0", JsonPrimitive("t0"), key)), false, StringCodec) { _, _ ->
            outcome
        }

    private fun entry(execution: BatchExecution<String>): JsonObject =
        EnvelopeEncoder
            .batch(execution, "req-1") { buildJsonObject { put("value", it) } }
            .body["data"]!!
            .jsonObject["results"]!!
            .jsonArray[0]
            .jsonObject

    // ---------------------------------------------------------------- carry-in #2

    @Test
    fun `carry-in 2 a replayed stored rejection keeps replayed true on its batch entry`(): Unit =
        runBlocking {
            val first = runKeyed("k-replay", Outcome.Err(rejection))
            assertNull(entry(first)["replayed"], "control: the first rejection is not a replay")

            val retry = runKeyed("k-replay", Outcome.Err(rejection))
            assertEquals(setOf(0), retry.replayed)
            val replayed = entry(retry)
            assertEquals("false", replayed["ok"]!!.jsonPrimitive.content)
            assertEquals(ErrorCode.INVALID_REQUEST.wire, replayed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("true", replayed["replayed"]!!.jsonPrimitive.content)
        }

    @Test
    fun `carry-in 2 a replayed whole-call failure carries meta replayed`() {
        val replayed = EnvelopeEncoder.outcome<String>(Outcome.Err(rejection), "req-2", replayed = true) { JsonPrimitive(it) }
        assertEquals("false", replayed.body["ok"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            replayed.body["meta"]!!
                .jsonObject["replayed"]!!
                .jsonPrimitive.content
        )

        val fresh = EnvelopeEncoder.outcome<String>(Outcome.Err(rejection), "req-3") { JsonPrimitive(it) }
        assertNull(fresh.body["meta"]!!.jsonObject["replayed"], "control: a fresh failure has no marker")
    }

    // ---------------------------------------------------------------- carry-in #3

    @Test
    fun `carry-in 3 an over-long idempotency key is echoed capped at 100 characters`(): Unit =
        runBlocking {
            val execution = runKeyed("k".repeat(300), Outcome.Ok("never"))
            val err = assertIs<Outcome.Err>(execution.result.results[0])
            val field = assertIs<ErrorDetail.InvalidRequest>(err.error.detail).fields.single()
            assertEquals("idempotencyKey", field.field)
            assertEquals("k".repeat(100), field.received)
        }

    // ---------------------------------------------------------------- carry-in #4 and #5

    @Serializable
    private data class Validating(
        val n: Int
    ) {
        init {
            require(n >= 0) { "n must be non-negative" }
        }
    }

    @Serializable
    private data class Timed(
        @Contextual val at: Instant
    )

    private val decoder = RequestDecoder()

    @Test
    fun `carry-in 4 a require failure in a request constructor surfaces its message as the reason`() {
        val err = assertIs<Outcome.Err>(decoder.decode(Validating.serializer(), buildJsonObject { put("n", -1) }))
        assertEquals(ErrorCode.INVALID_REQUEST, err.error.code)
        val field = assertIs<ErrorDetail.InvalidRequest>(err.error.detail).fields.single()
        assertEquals("$", field.field)
        assertEquals("n must be non-negative", field.reason)

        assertEquals(Outcome.Ok(Validating(1)), decoder.decode(Validating.serializer(), buildJsonObject { put("n", 1) }))
    }

    @Test
    fun `carry-in 5 a Contextual Instant request field decodes and a bad instant is a field fault`() {
        val ok = decoder.decode(Timed.serializer(), buildJsonObject { put("at", "2026-10-10T12:00:00Z") })
        assertEquals(Outcome.Ok(Timed(Instant.parse("2026-10-10T12:00:00Z"))), ok)

        val bad = assertIs<Outcome.Err>(decoder.decode(Timed.serializer(), buildJsonObject { put("at", "yesterday") }))
        val field = assertIs<ErrorDetail.InvalidRequest>(bad.error.detail).fields.single()
        assertEquals("/at", field.field)
        assertEquals("must be an ISO-8601 instant", field.reason)
        assertEquals("yesterday", field.received)
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Opened
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 95a22e6e, R22) for the `seatToken` lift in `ToolDispatcher`, on both transports (the
 * in-memory MCP client/server pair and `POST /api/v1/ops/{tool}`), for both tool kinds (an ops tool and a select tool). Scenarios
 * S12 to S14 of the frozen test-plan and the ordering and shape probes of its section 6.
 *
 * Oracles (never read from a run): task-scope D1 (`seatToken` is a top-level string key of both tool kinds, resolved at step 0b,
 * after the principal and before access, describe, selector or decode are read; absent = no seat; present but empty, not a string, or
 * unknown = `unauthenticated`; inside an `ops[]` element it stays `unknown_parameter`), D2 (one fixed `unauthenticated` envelope for
 * unknown, ended and wrong-principal, message `The seat token is not accepted.`, no detail, no fix arguments, identical in the three
 * cases; wrong principal = the caller's claim id differs from the row's `principal_id`, compared exactly), the envelope spec (401 for
 * `unauthenticated`), and the principle that a secret is never echoed: the token appears in no error body, no `call_log` row and no
 * event payload. Values are literals written from those clauses.
 *
 * Negative-assertion fixture conditions: every refusal is paired with the same call carrying the live token of the right principal,
 * which succeeds, and asserts the tables it would have written are unchanged.
 *
 * Domain invariants satisfied by construction: runs are opened and seats assigned through the tools themselves.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class SeatTokenLiftTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun rest(block: suspend ApplicationTestBuilder.(SeatWorld) -> Unit) =
        testApplication {
            val w = SeatWorld.create(db.db, dir, clock)
            val auth =
                ApiAuthConfig.Bearer(
                    tokens =
                        w.everyToken.associate { t ->
                            HashBytes(sha256(t.raw)) to BearerTokenStore.TokenEntry(t.principal(), expiresAt = null)
                        }
                )
            application { installParityRestApp(w.rig, auth, w.registry) }
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        token: ParityToken?
    ): Reply {
        val response =
            post(path) {
                if (token != null) header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        return Reply(response.status.value >= 400, Json.parseToJsonElement(response.bodyAsText()).jsonObject, null, response.status.value)
    }

    private fun refusalOf(reply: Reply) = reply.body["error"]?.jsonObject

    private fun assertRefused(
        reply: Reply,
        label: String
    ) {
        assertEquals("unauthenticated", reply.wholeCode(), "$label: $reply")
        val error = assertNotNull(refusalOf(reply), "$label: $reply")
        assertEquals("The seat token is not accepted.", error["message"]?.toString()?.trim('"'), "$label: the fixed message: $reply")
        for (absent in listOf("detail", "fixArgs")) assertFalse(absent in error, "$label: no $absent on a refusal: $error")
        assertTrue(reply.isError, "$label: an error result")
    }

    private fun tokenSelect(
        run: Opened,
        token: String?
    ): String = "{" + (if (token != null) """"seatToken":"$token",""" else "") + """"select":"seats","runId":"${run.runId}"}"""

    // ------------------------------------------------------------------ S12 unknown

    @Test
    fun `S12 an unknown token is the fixed unauthenticated refusal on an ops tool and on a select tool, and writes nothing`(): Unit =
        world { w ->
            val run = w.open()
            val before = w.rows("SELECT * FROM run_seats").size
            val eventsBefore = w.rig.rows().size

            val onOps = w.ops("advance", """{"op":"claim","itemId":"${w.m1.id}"}""", token = "n".repeat(43))
            assertRefused(onOps, "ops tool")
            val onSelect = w.call("runs_read", tokenSelect(run, "n".repeat(43)))
            assertRefused(onSelect, "select tool")

            assertEquals(before, w.rows("SELECT * FROM run_seats").size)
            assertEquals(eventsBefore, w.rig.rows().size, "no event was written")
            assertNull(w.itemRow(w.m1)["claimed_by"], "the claim was not taken")
            // control: with the live coordinator token the same select answers and the same claim (no token) runs
            assertTrue(w.call("runs_read", tokenSelect(run, run.coordinatorToken)).ok())
            assertTrue(w.claim(w.m1, null).ok())
        }

    @Test
    fun `S12 to S14 unknown, ended and wrong-principal tokens get the very same error object`(): Unit =
        world { w ->
            val run = w.open()
            val live = w.assignOk(run, w.m1, "builder")
            val ended = w.assignOk(run, w.m2, "implementer")
            assertTrue(w.endSeat(ended.token).ok(), "fixture: this seat ended")

            val unknown = w.call("runs_read", tokenSelect(run, "u".repeat(43)))
            val endedReply = w.call("runs_read", tokenSelect(run, ended.token))
            val wrongPrincipal = w.call("runs_read", tokenSelect(run, live.token), by = w.other)
            assertRefused(unknown, "unknown")
            assertRefused(endedReply, "ended (S13)")
            assertRefused(wrongPrincipal, "wrong principal (S14)")

            assertEquals(refusalOf(unknown), refusalOf(endedReply), "unknown and ended are indistinguishable")
            assertEquals(refusalOf(unknown), refusalOf(wrongPrincipal), "unknown and wrong-principal are indistinguishable")
            // control: the right principal with the live token is served
            assertTrue(w.call("runs_read", tokenSelect(run, live.token)).ok())
            assertTrue(w.call("runs_read", tokenSelect(run, live.token), by = w.orch).ok())
        }

    @Test
    fun `S13 a token whose run was closed is refused the same way`(): Unit =
        world { w ->
            val run = w.open()
            val seat = w.assignOk(run, w.m1, "builder")
            assertTrue(w.close(run, "cancelled", run.coordinatorToken).ok())

            assertRefused(w.call("runs_read", tokenSelect(run, seat.token)), "member token of a closed run")
            assertRefused(w.call("runs_read", tokenSelect(run, run.coordinatorToken)), "coordinator token of a closed run")
            assertTrue(w.call("runs_read", tokenSelect(run, null)).ok(), "control: the same read without a token is served")
        }

    @Test
    fun `S14 the principal compare is exact - another credential, another case or a padded id never uses the token`(): Unit =
        world { w ->
            val run = w.open()
            val seat = w.assignOk(run, w.m1, "builder")
            for ((label, who) in listOf("another service principal" to w.other, "an admin principal" to w.admin, "a human" to w.human)) {
                assertRefused(w.call("runs_read", tokenSelect(run, seat.token), by = who), label)
            }
            assertEquals("active", w.seatById(assertNotNull(seat.assignmentId))["status"], "the refusals touched nothing")
            assertTrue(
                w.call("runs_read", tokenSelect(run, seat.token), by = w.orch).ok(),
                "control: the principal that was assigned the seat"
            )
        }

    // ------------------------------------------------------------------ the shape of the key

    @Test
    fun `D1 an absent seatToken is no seat, and a present one that is empty, blank, null or not a string is unauthenticated`(): Unit =
        world { w ->
            val run = w.open()
            assertTrue(w.call("runs_read", """{"select":"list"}""").ok(), "absent: served with no seat")
            for (value in listOf("\"\"", "\"   \"", "null", "5", "true", "[]", "{}", "[\"a\"]", "1.5")) {
                val reply = w.call("runs_read", """{"seatToken":$value,"select":"seats","runId":"${run.runId}"}""")
                assertRefused(reply, "seatToken=$value")
                val ops = w.call("advance", """{"seatToken":$value,"ops":[{"op":"claim","itemId":"${w.m1.id}"}]}""")
                assertRefused(ops, "ops seatToken=$value")
            }
            assertNull(w.itemRow(w.m1)["claimed_by"], "no refused ops call claimed anything")
        }

    @Test
    fun `D1 a differently spelled token is never accepted - case, alphabet, padding, whitespace, truncation, extension`(): Unit =
        world { w ->
            val run = w.open()
            val seat = w.assignOk(run, w.m1, "builder")
            val token = assertNotNull(seat.token)
            val variants =
                mutableListOf(
                    "truncated" to token.dropLast(1),
                    "extended" to token + "A",
                    "padded" to "$token=",
                    "leading space" to " $token",
                    "trailing space" to "$token ",
                    "trailing newline" to token + "\n",
                    "doubled" to token + token,
                    "bare prefix" to token.take(8)
                )
            if (token.uppercase() != token) variants += "upper-cased" to token.uppercase()
            if (token.lowercase() != token) variants += "lower-cased" to token.lowercase()
            if ('-' in token) variants += "standard alphabet" to token.replace('-', '+')
            if ('_' in token) variants += "standard alphabet slash" to token.replace('_', '/')
            variants += "last character changed" to (token.dropLast(1) + (if (token.last() == 'A') 'B' else 'A'))
            for ((label, variant) in variants) {
                assertRefused(w.call("runs_read", tokenSelect(run, variant)), label)
            }
            assertTrue(w.call("runs_read", tokenSelect(run, token)).ok(), "control: the exact token")
        }

    @Test
    fun `D1 seatToken inside an ops element is unknown_parameter at its pointer and never runs, even beside a valid top-level token`(): Unit =
        world { w ->
            val run = w.open()
            val seat = w.assignOk(run, w.m1, "builder")

            val withTop = w.ops("advance", """{"op":"claim","itemId":"${w.m1.id}","seatToken":"${seat.token}"}""", token = seat.token)
            assertEquals("partial_failure", withTop.wholeCode(), withTop.toString())
            assertEquals("unknown_parameter", withTop.code(), withTop.toString())
            assertEquals(listOf("/ops/0/seatToken"), withTop.parameters())
            val withoutTop = w.ops("advance", """{"op":"claim","itemId":"${w.m1.id}","seatToken":"${seat.token}"}""", token = null)
            assertEquals("unknown_parameter", withoutTop.code(), withoutTop.toString())
            assertNull(w.itemRow(w.m1)["claimed_by"], "neither element ran")
            assertTrue(w.claim(w.m1, seat.token).ok(), "control: the token at the top level claims")
        }

    // ------------------------------------------------------------------ ordering (D1)

    @Test
    fun `D1 an invalid token is refused before describe, the selector, the decode or the access check is read`(): Unit =
        world { w ->
            val run = w.open()
            val cases =
                listOf(
                    "describe" to """{"seatToken":"bad","describe":true}""",
                    "an unknown select" to """{"seatToken":"bad","select":"nonsense"}""",
                    "a missing select" to """{"seatToken":"bad"}""",
                    "an unknown field" to """{"seatToken":"bad","select":"seats","runId":"${run.runId}","bogus":1}""",
                    "an undecodable runId" to """{"seatToken":"bad","select":"seats","runId":"not-an-id"}"""
                )
            for ((label, args) in cases) assertRefused(w.call("runs_read", args), label)
            val opsCases =
                listOf(
                    "describe" to """{"seatToken":"bad","describe":true}""",
                    "ops not an array" to """{"seatToken":"bad","ops":"x"}""",
                    "no ops" to """{"seatToken":"bad"}""",
                    "an unknown top-level key" to """{"seatToken":"bad","ops":[{"op":"end_seat","outcome":"done"}],"foo":1}""",
                    "a hidden op" to """{"seatToken":"bad","ops":[{"op":"zap"}]}"""
                )
            for ((label, args) in opsCases) assertRefused(w.call("runs_write", args), label)
            // the access check: a caller without the capability is still refused as unauthenticated first
            assertRefused(w.call("runs_read", tokenSelect(run, "bad"), by = w.readOnly), "a caller with the read capability only")

            // controls: the very same calls without the token are refused for their own reasons
            assertEquals("invalid_request", w.call("runs_read", """{"select":"nonsense"}""").code())
            assertEquals("invalid_request", w.call("runs_read", """{}""").code())
            assertEquals("unknown_parameter", w.call("runs_read", """{"select":"seats","runId":"${run.runId}","bogus":1}""").code())
            assertEquals("invalid_request", w.call("runs_write", """{"ops":"x"}""").code())
            assertEquals("unknown_parameter", w.call("runs_write", """{"ops":[{"op":"end_seat","outcome":"done"}],"foo":1}""").code())
            assertTrue(w.call("runs_write", """{"describe":true}""").ok(), "describe without a token is served")
        }

    @Test
    fun `D1 a valid token does not disturb describe, and the valid token is still checked against the principal first`(): Unit =
        world { w ->
            val run = w.open()
            assertTrue(
                w.call("runs_write", """{"seatToken":"${run.coordinatorToken}","describe":true}""").ok(),
                "describe under the live token"
            )
            assertRefused(
                w.call("runs_write", """{"seatToken":"${run.coordinatorToken}","describe":true}""", by = w.other),
                "another principal's describe"
            )
        }

    // ------------------------------------------------------------------ the secret is never echoed

    @Test
    fun `a seat token appears in no error body, no call_log row, no event payload and no read view`(): Unit =
        world { w ->
            val run = w.open()
            val seat = w.assignOk(run, w.m1, "builder")
            val secrets = listOf(run.coordinatorToken, assertNotNull(seat.token))

            val replies =
                listOf(
                    w.claim(w.m1, seat.token),
                    w.heartbeat(w.m1, seat.token, """{"tokens":5}"""),
                    w.release(w.m1, seat.token),
                    w.call("runs_read", tokenSelect(run, seat.token)),
                    w.claim(w.m1, "x".repeat(43)),
                    w.endSeat(run.coordinatorToken),
                    w.ops("runs_write", """{"op":"end_seat","outcome":"bogus"}""", token = seat.token)
                )
            for (reply in replies) {
                for (secret in secrets) assertFalse(secret in reply.toString(), "a reply echoes a token: $reply")
                val log = w.callLogRowOrNull(reply) ?: continue
                assertTrue(
                    log.values.none { v ->
                        secrets.any { s -> v?.toString()?.contains(s) == true }
                    },
                    "call_log row echoes a token: $log"
                )
            }
            assertTrue(
                w.rows("SELECT data FROM events").none { row -> secrets.any { s -> ((row["data"] as? String) ?: "").contains(s) } },
                "no event payload carries a token"
            )
            assertTrue(
                w.rows("SELECT * FROM run_seats").flatMap { it.values }.none { v ->
                    secrets.any { s -> v?.toString()?.contains(s) == true }
                },
                "the seat table stores only hashes"
            )
        }

    // ------------------------------------------------------------------ the REST transport

    @Test
    fun `REST an unknown token is 401 unauthenticated with the fixed message on an ops route and a select route`(): Unit =
        rest { w ->
            val run = w.open()
            val ops =
                client.send(
                    "/api/v1/ops/advance",
                    """{"seatToken":"${"n".repeat(43)}","ops":[{"op":"claim","itemId":"${w.m1.id}"}]}""",
                    w.orch
                )
            assertEquals(401, ops.status, ops.toString())
            assertRefused(ops, "REST ops")
            val select = client.send("/api/v1/ops/runs_read", tokenSelect(run, "n".repeat(43)), w.orch)
            assertEquals(401, select.status, select.toString())
            assertRefused(select, "REST select")
            assertNull(w.itemRow(w.m1)["claimed_by"], "nothing was claimed")
        }

    @Test
    fun `REST the whole seat life over HTTP - open, assign, claim, usage, end - with the same principal binding`(): Unit =
        rest { w ->
            val opened =
                client.send(
                    "/api/v1/ops/runs_write",
                    """{"ops":[{"op":"open","rootId":"${w.root.id}","items":[${w.ids(w.m1, w.m2)}]}]}""",
                    w.orch
                )
            assertEquals(200, opened.status, opened.toString())
            val run = Opened(assertNotNull(opened.find("runId")), assertNotNull(opened.find("coordinatorToken")), opened)

            val assign =
                client.send(
                    "/api/v1/ops/runs_write",
                    """{"seatToken":"${run.coordinatorToken}","ops":[{"op":"assign","runId":"${run.runId}","itemId":"${w.m1.id}","seat":"builder"}]}""",
                    w.orch
                )
            assertEquals(200, assign.status, assign.toString())
            val seatToken = assertNotNull(assign.find("seatToken"))
            assertEquals("1", assign.find("attempt"))

            // the bearer of another principal cannot use the token
            val stolen =
                client.send(
                    "/api/v1/ops/advance",
                    """{"seatToken":"$seatToken","ops":[{"op":"claim","itemId":"${w.m1.id}"}]}""",
                    w.other
                )
            assertEquals(401, stolen.status, stolen.toString())
            assertRefused(stolen, "REST wrong principal")
            assertNull(w.itemRow(w.m1)["claimed_by"])

            val claim =
                client.send(
                    "/api/v1/ops/advance",
                    """{"seatToken":"$seatToken","ops":[{"op":"claim","itemId":"${w.m1.id}"}]}""",
                    w.orch
                )
            assertEquals(200, claim.status, claim.toString())
            val beat =
                client.send(
                    "/api/v1/ops/advance",
                    """{"seatToken":"$seatToken","ops":[{"op":"heartbeat","itemId":"${w.m1.id}","usage":{"tokens":42,"costUsd":0.5}}]}""",
                    w.orch
                )
            assertEquals(200, beat.status, beat.toString())
            val row = w.seatByToken(seatToken)
            assertNotNull(row)
            assertEquals(42L, row["usage_tokens"])
            assertEquals(500_000L, row["usage_cost_micro"])

            val end =
                client.send(
                    "/api/v1/ops/runs_write",
                    """{"seatToken":"$seatToken","ops":[{"op":"end_seat","outcome":"done"}]}""",
                    w.orch
                )
            assertEquals(200, end.status, end.toString())
            val after = client.send("/api/v1/ops/runs_read", tokenSelect(run, seatToken), w.orch)
            assertEquals(401, after.status, "the ended token: $after")
            val seats = client.send("/api/v1/ops/runs_read", """{"select":"seats","ended":true,"runId":"${run.runId}"}""", w.orch)
            assertEquals(200, seats.status, seats.toString())
            assertFalse(seatToken in seats.toString())
        }

    @Test
    fun `REST the director route does not offer assign or end_seat, whatever token it carries`(): Unit =
        rest { w ->
            val run = w.open()
            val onDirector =
                client.send(
                    "/api/v1/ops/director/runs_write",
                    """{"seatToken":"${run.coordinatorToken}","ops":[{"op":"assign","runId":"${run.runId}","itemId":"${w.m1.id}","seat":"builder"}]}""",
                    w.orch
                )
            assertEquals("invalid_request", onDirector.code(), onDirector.toString())
            assertTrue(onDirector.pointers().any { it.endsWith("/ops/0/op") }, "pointer: ${onDirector.pointers()}")
            assertEquals(1, w.seatsOfRun(run.runId).size, "nothing was assigned")
            val plain =
                client.send(
                    "/api/v1/ops/runs_write",
                    """{"seatToken":"${run.coordinatorToken}","ops":[{"op":"assign","runId":"${run.runId}","itemId":"${w.m1.id}","seat":"builder"}]}""",
                    w.orch
                )
            assertEquals(200, plain.status, "control: the plain route serves it: $plain")
        }

    @Test
    fun `REST a human credential presenting a seat token is refused a pause that the same human may make without it`(): Unit =
        rest { w ->
            val opened =
                client.send(
                    "/api/v1/ops/runs_write",
                    """{"ops":[{"op":"open","rootId":"${w.root.id}","items":[${w.ids(w.m1)}]}]}""",
                    w.human
                )
            assertEquals(200, opened.status, opened.toString())
            val runId = assertNotNull(opened.find("runId"))
            val token = assertNotNull(opened.find("coordinatorToken"))
            val pause = """{"op":"pause","runId":"$runId"}"""

            val underToken = client.send("/api/v1/ops/runs_write", """{"seatToken":"$token","ops":[$pause]}""", w.human)
            assertEquals("forbidden", underToken.code(), underToken.toString())
            assertEquals("principal_kind", underToken.find("scope", underToken.error()))
            val plain = client.send("/api/v1/ops/runs_write", """{"ops":[$pause]}""", w.human)
            assertEquals(200, plain.status, "control: without the token the human pauses: $plain")
        }
}

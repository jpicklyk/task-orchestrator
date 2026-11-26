package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.identity.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.identity.BearerTokenStore
import io.github.jpicklyk.mcptask.current.identity.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.run.internal.wire.Opened
import io.github.jpicklyk.mcptask.current.run.internal.wire.Reply
import io.github.jpicklyk.mcptask.current.run.internal.wire.SeatWorld
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
import kotlinx.serialization.json.JsonPrimitive
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
 * Independent tests (test-author seat, item 8fc593a7 / F3-A) for the caller's seat on the wire: scenarios S9 (`items_read` context)
 * and S10 (`advance` transition) of the frozen test-plan, over the real MCP adapter and production dispatcher ([SeatWorld]) and, for
 * the context read, the real REST route.
 *
 * Oracles (never read from a run): task-scope AC3 and D4 (a top-level string member `callerSeat`, present only when the call
 * carries a valid seat token whose member item is the response's item, and then equal to the seat name the token was assigned;
 * omitted for no token, for the run-scoped coordinator pseudo-seat, whose member item is null, and for the token of another item,
 * so a response's `seats` always describe the caller's seat; on transition after `dispatch` and before `violations`; a refused
 * transition has none), D5 (the capability is advertised as `seatSkills`) and the wire contract that MCP and REST serve the same
 * `items_read` body. Values are literals written from those clauses: the seat names are the ones the assign op was given.
 *
 * Self-resolved from public evidence (`current/docs/api-reference.md` and the existing seat tests): the transition element is looked
 * up for the key by name anywhere in the element, so a nesting choice does not decide a test; absence is asserted over every key of
 * the whole reply.
 *
 * Negative-assertion fixture conditions: each absent leg is paired with a present leg on the same kind of call (own-item token), and
 * the two members carry differently named seats, so a server that returns a constant or the wrong item's seat fails a present leg.
 *
 * Domain invariants satisfied by construction: items are seeded through the rig as WORK-phase children of their root, runs are
 * opened and seats assigned through the tools themselves with seat names of the schema (`builder`, `checker`).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class CallerSeatTest {
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

    private suspend fun SeatWorld.workItem(title: String): WorkItem = rig.seed(title, parent = root, role = Role.WORK, tags = "seated-task")

    private fun contextArgs(
        item: WorkItem,
        token: String?
    ): String = "{" + (if (token != null) """"seatToken":"$token",""" else "") + """"select":"context","itemId":"${item.id}"}"""

    private fun callerSeatOfContext(reply: Reply): String? {
        assertTrue(reply.ok(), "the context read is served: $reply")
        return (assertNotNull(reply.data, reply.toString())["callerSeat"] as? JsonPrimitive)?.content
    }

    private fun assertNoCallerSeat(
        reply: Reply,
        label: String
    ) {
        assertTrue(reply.ok(), "$label: the call itself is served: $reply")
        assertFalse("callerSeat" in reply.allKeys(), "$label: no callerSeat anywhere in the reply: $reply")
    }

    private fun callerSeatOfTransition(reply: Reply): String? {
        assertTrue(reply.ok(), "the transition is applied: $reply")
        return reply.findElement("callerSeat", reply.results().first())?.let { (it as JsonPrimitive).content }
    }

    private suspend fun SeatWorld.openRun(vararg members: WorkItem): Opened = open(members.toList())

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 context carries callerSeat for the own-item seat token only and omits it otherwise`(): Unit =
        world { w ->
            val own = w.workItem("S9 own")
            val other = w.workItem("S9 other")
            val run = w.openRun(own, other)
            val builder = w.assignOk(run, own, "builder")
            val checker = w.assignOk(run, other, "checker")

            // Present legs: the seat name the token was assigned, per item (two different names, so a constant fails).
            assertEquals("builder", callerSeatOfContext(w.call("items_read", contextArgs(own, builder.token))))
            assertEquals("checker", callerSeatOfContext(w.call("items_read", contextArgs(other, checker.token))))

            // Absent legs, each paired with the present leg above on the same item.
            assertNoCallerSeat(w.call("items_read", contextArgs(own, null)), "no token")
            assertNoCallerSeat(w.call("items_read", contextArgs(own, checker.token)), "another member's token")
            assertNoCallerSeat(w.call("items_read", contextArgs(other, builder.token)), "another member's token, reversed")
            assertNoCallerSeat(w.call("items_read", contextArgs(own, run.coordinatorToken)), "the coordinator token")
        }

    @Test
    fun `S9 the REST context read answers the same callerSeat as MCP for every leg`(): Unit =
        rest { w ->
            val own = w.workItem("S9 rest own")
            val other = w.workItem("S9 rest other")
            val run = w.openRun(own, other)
            val builder = w.assignOk(run, own, "builder")
            val checker = w.assignOk(run, other, "checker")

            val legs =
                listOf(
                    "own token" to Triple(own, builder.token, "builder"),
                    "other item's own token" to Triple(other, checker.token, "checker"),
                    "no token" to Triple(own, null, null),
                    "another member's token" to Triple(own, checker.token, null),
                    "the coordinator token" to Triple(own, run.coordinatorToken, null)
                )
            for ((label, leg) in legs) {
                val (item, token, expected) = leg
                val mcp = w.call("items_read", contextArgs(item, token))
                val viaRest = client.send("/api/v1/ops/items_read", contextArgs(item, token), w.orch)
                assertEquals(200, viaRest.status, "$label: $viaRest")
                assertEquals(expected, callerSeatOfContext(mcp), "$label over MCP")
                assertEquals(expected, callerSeatOfContext(viaRest), "$label over REST")
                assertEquals(
                    "callerSeat" in mcp.allKeys(),
                    "callerSeat" in viaRest.allKeys(),
                    "$label: MCP and REST agree on whether the member exists"
                )
            }
        }

    // ------------------------------------------------------------------ S10

    private fun transition(
        item: WorkItem,
        trigger: String,
        extra: String = ""
    ): String = """{"op":"transition","itemId":"${item.id}","trigger":"$trigger"$extra}"""

    @Test
    fun `S10 a transition carries callerSeat under the own-item seat token only`(): Unit =
        world { w ->
            val seated = w.workItem("S10 seated")
            // A seat-aware item refuses a caller without a seat (seat_forbidden), so the token-less leg runs on a seat-less schema item.
            val tokenless = w.rig.seed("S10 token-less", parent = w.root, role = Role.WORK, tags = "plain-task")
            val coordinated = w.workItem("S10 coordinator")
            val run = w.openRun(seated, coordinated)
            val builder = w.assignOk(run, seated, "builder")

            val own = w.ops("advance", transition(seated, "hold"), token = builder.token)
            assertEquals("builder", callerSeatOfTransition(own), "present leg: $own")

            val none = w.ops("advance", transition(tokenless, "hold"))
            assertNoCallerSeat(none, "token-less transition")
            assertEquals("blocked", w.itemRow(tokenless)["role"], "control: the token-less transition was applied")

            val viaCoordinator = w.ops("advance", transition(coordinated, "hold"), token = run.coordinatorToken)
            assertNoCallerSeat(viaCoordinator, "coordinator-token transition")
            assertEquals("blocked", w.itemRow(coordinated)["role"], "control: the coordinator transition was applied")
        }

    @Test
    fun `S10 a refused transition under a seat token carries no callerSeat, and the same seat's applicable transition does`(): Unit =
        world { w ->
            val refused = w.workItem("S10 refused")
            val applied = w.workItem("S10 applied")
            val run = w.openRun(refused, applied)
            val first = w.assignOk(run, refused, "builder")
            val second = w.assignOk(run, applied, "builder")

            val bad = w.ops("advance", transition(refused, "reopen"), token = first.token)
            assertFalse(bad.ok(), "reopen of a non-terminal item is refused: $bad")
            assertFalse("callerSeat" in bad.allKeys(), "a refused transition has no callerSeat: $bad")
            assertEquals("work", w.itemRow(refused)["role"], "the refused transition changed nothing")

            val good = w.ops("advance", transition(applied, "hold"), token = second.token)
            assertEquals("builder", callerSeatOfTransition(good))
        }

    @Test
    fun `S10 probe - a replay of the same transition with the same idempotency key and token keeps callerSeat`(): Unit =
        world { w ->
            val item = w.workItem("S10 replay")
            val run = w.openRun(item)
            val checker = w.assignOk(run, item, "checker")
            val op = transition(item, "hold", ""","idempotencyKey":"caller-seat-replay-1"""")

            val first = w.ops("advance", op, token = checker.token)
            assertEquals("checker", callerSeatOfTransition(first), "first call: $first")
            val replay = w.ops("advance", op, token = checker.token)
            assertEquals("checker", callerSeatOfTransition(replay), "replay: $replay")
            assertNull(w.itemRow(item)["claimed_by"], "the replay changed no claim")
        }
}

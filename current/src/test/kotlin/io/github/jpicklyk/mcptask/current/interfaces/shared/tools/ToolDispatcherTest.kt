package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [ToolDispatcher], scenarios S7, S8, S9, S10 and S13 of the frozen
 * test-plan (NEW-SURFACE). The dispatcher half of S11 (describe) lives in `ToolDescriberTest`.
 *
 * Oracles: task-scope decisions D2 (order: lift actor, describe, selector, decode, run), D3 (missing `select` ->
 * `invalid_request` `/select` "is required"; unknown or profile-hidden select -> `invalid_request` `/select` "must be one of:
 * <visible>", hidden looks absent), D4 (a field of another select/op is `unknown_parameter`), D5 (ops: whole-call
 * `invalid_request` for `ops` absent / not an array / empty and `atomic` not boolean, `unknown_parameter` for an unknown
 * top-level key; per-element errors for a non-object element, a missing/unknown/hidden `op` at `/ops/i/op`, decode faults
 * prefixed `/ops/i`, a non-string `idempotencyKey`, an `idempotencyKey` without a lifted actor ("requires an actor"); failed
 * elements still pass through the batch runner so `atomic` rolls back and reports `error.index`; principal id for keys =
 * `principal.claim.id`), D6 (actor lift and strict decode), D8 (call_log operation name), D9 (cancellation rethrown, a
 * translated persistence fault with a non-internal code is encoded as is, anything else is the fixed internal envelope) and
 * envelope sections 2 to 4 (shapes, statuses). Expected values are literals written from those clauses.
 *
 * Harness: a real SQLite database (BatchRunner, idempotency records, rollback) and the FixtureTools specs. Every "not
 * executed / not created" assertion is paired with a control that DOES execute through the same call shape.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ToolDispatcherTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fx = FixtureTools()

    private class RecordingVerifier : ActorVerifier {
        val seen = CopyOnWriteArrayList<ActorClaim>()

        override suspend fun verify(actor: ActorClaim): VerificationResult {
            seen += actor
            return VerificationResult(VerificationStatus.VERIFIED, verifier = "test-verifier")
        }
    }

    private val verifier = RecordingVerifier()

    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private val dispatcher: ToolDispatcher by lazy {
        ToolDispatcher(RequestDecoder(), verifier, BatchRunner(env.unitOfWork, env.idempotency), env)
    }

    private val reqId = "k7f3q9ab"

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun call(
        spec: ToolSpec,
        text: String,
        profile: ToolProfile = ToolProfile.ORCHESTRATION
    ): Dispatched = dispatcher.dispatch(spec, profile, obj(text), reqId, "mcp")

    private fun Dispatched.body(): JsonObject = envelope.body

    private fun Dispatched.isOk(): Boolean =
        body()
            .getValue("ok")
            .jsonPrimitive.content
            .toBoolean()

    private fun Dispatched.error(): JsonObject = body().getValue("error").jsonObject

    private fun Dispatched.code(): String = error().getValue("code").jsonPrimitive.content

    private fun Dispatched.data(): JsonObject = body().getValue("data").jsonObject

    private fun Dispatched.results(): JsonArray = data().getValue("results").jsonArray

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.fields(): List<Pair<String, String>> =
        getValue("detail").jsonObject.getValue("fields").jsonArray.map {
            it.jsonObject.str("field") to it.jsonObject.str("reason")
        }

    private fun JsonObject.parameters(): List<String> =
        getValue("detail").jsonObject.getValue("parameters").jsonArray.map {
            it.jsonPrimitive.content
        }

    private fun Dispatched.elementError(i: Int): JsonObject = results()[i].jsonObject.getValue("error").jsonObject

    private fun titles(): List<String> =
        DriverManager.getConnection(db.jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM work_items ORDER BY title").use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

    private fun create(
        title: String,
        extra: String = ""
    ) = """{"op":"create","title":"$title"$extra}"""

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private val actor1 = """"actor":{"id":"agent-1","kind":"subagent"}"""
    private val actor2 = """"actor":{"id":"agent-2","kind":"subagent"}"""
    private val keyOne = ""","idempotencyKey":"key-1""""

    // ------------------------------------------------------------------ S7 select, happy path

    @Test
    fun `S7 a Single select returns ok with its data, status 200 and the reqId in meta`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertEquals("""{"ok":true,"data":"got:aaaaaaaa:1","meta":{"reqId":"k7f3q9ab"}}""", result.body().toString())
            assertEquals(200, result.envelope.httpStatus)
            assertEquals("get", result.operation)
            assertNull(result.batchSize)
        }

    @Test
    fun `S7 a request field reaches the handler`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_OK}","depth":3}""")
            assertEquals("got:aaaaaaaa:3", result.body().str("data"))
        }

    @Test
    fun `S7 a Paged select returns items and a page object`(): Unit =
        runBlocking {
            val truncated = call(fx.readTool, """{"select":"list"}""")
            assertEquals(
                """{"ok":true,"data":{"items":["a","b"],"page":{"total":5,"truncated":true,"next":"n1"}},"meta":{"reqId":"k7f3q9ab"}}""",
                truncated.body().toString()
            )
            assertEquals("list", truncated.operation)
            val complete = call(fx.readTool, """{"select":"list","tag":"all"}""")
            assertEquals(
                """{"ok":true,"data":{"items":["a","b"],"page":{"total":2,"truncated":false}},"meta":{"reqId":"k7f3q9ab"}}""",
                complete.body().toString()
            )
        }

    @Test
    fun `S7 a handler Err is encoded with its code, kind and http status`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_MISSING}"}""")
            assertFalse(result.isOk())
            assertEquals("not_found", result.code())
            assertEquals("permanent", result.error().str("kind"))
            assertEquals(404, result.envelope.httpStatus)
            assertEquals("get", result.operation)
            assertEquals(
                reqId,
                result
                    .body()
                    .getValue("meta")
                    .jsonObject
                    .str("reqId")
            )
        }

    @Test
    fun `S7 a handler sees the one-call context with reqId and surface`(): Unit =
        runBlocking {
            call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            val ctx = fx.seen.single()
            assertEquals(reqId, ctx.reqId)
            assertEquals("mcp", ctx.surface)
            assertNull(ctx.principal, "no actor was sent")
        }

    // ------------------------------------------------------------------ S8 selector

    @Test
    fun `S8 a missing select is invalid_request on the select pointer`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"id":"${FixtureTools.GET_OK}"}""")
            assertEquals("invalid_request", result.code())
            val (field, reason) = result.error().fields().single()
            assertEquals("/select", field)
            assertTrue("is required" in reason, reason)
            assertEquals(400, result.envelope.httpStatus)
            assertNull(result.operation, "an invalid selector names no operation")
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S8 an unknown select lists the visible selects`(): Unit =
        runBlocking {
            val knowledge = call(fx.readTool, """{"select":"nope"}""", ToolProfile.KNOWLEDGE)
            assertEquals("invalid_request", knowledge.code())
            val (field, reason) = knowledge.error().fields().single()
            assertEquals("/select", field)
            assertTrue(reason.startsWith("must be one of: "), reason)
            assertTrue("get" in reason && "list" in reason, reason)
            assertFalse("audit" in reason, "audit is hidden in knowledge: $reason")
            assertNull(knowledge.operation)

            val orchestration = call(fx.readTool, """{"select":"nope"}""", ToolProfile.ORCHESTRATION)
            assertTrue(
                "audit" in
                    orchestration
                        .error()
                        .fields()
                        .single()
                        .second,
                "orchestration lists audit"
            )
        }

    @Test
    fun `S8 a profile-hidden select looks exactly like an unknown one`(): Unit =
        runBlocking {
            val hidden = call(fx.readTool, """{"select":"audit","scope":"x"}""", ToolProfile.KNOWLEDGE)
            val unknown = call(fx.readTool, """{"select":"nope","scope":"x"}""", ToolProfile.KNOWLEDGE)
            assertEquals("invalid_request", hidden.code())
            assertEquals(
                "/select",
                hidden
                    .error()
                    .fields()
                    .single()
                    .first
            )
            assertEquals(
                unknown
                    .error()
                    .fields()
                    .single()
                    .second,
                hidden
                    .error()
                    .fields()
                    .single()
                    .second
            )
            assertEquals(400, hidden.envelope.httpStatus)
            assertNull(hidden.operation)
            assertTrue(fx.seen.isEmpty(), "the hidden select must not run")

            val allowed = call(fx.readTool, """{"select":"audit","scope":"x"}""", ToolProfile.ORCHESTRATION)
            assertEquals("audit:x", allowed.body().str("data"))
        }

    @Test
    fun `S8 a tool with no visible select rejects every select in knowledge`(): Unit =
        runBlocking {
            val result = call(fx.peekTool, """{"select":"peek","what":"x"}""", ToolProfile.KNOWLEDGE)
            assertEquals("invalid_request", result.code())
            assertEquals(
                "/select",
                result
                    .error()
                    .fields()
                    .single()
                    .first
            )
            assertEquals("peek:x", call(fx.peekTool, """{"select":"peek","what":"x"}""").body().str("data"))
        }

    @Test
    fun `S8 select values that are not an exact visible name are invalid_request`(): Unit =
        runBlocking {
            val bad =
                listOf(
                    """{"select":"Get","id":"aaaaaaaa"}""",
                    """{"select":"","id":"aaaaaaaa"}""",
                    """{"select":5,"id":"aaaaaaaa"}""",
                    """{"select":["get"]}"""
                )
            for (text in bad) {
                val result = call(fx.readTool, text)
                assertEquals("invalid_request", result.code(), text)
                assertEquals(
                    "/select",
                    result
                        .error()
                        .fields()
                        .single()
                        .first,
                    text
                )
            }
            assertEquals("get", call(fx.readTool, """{"select":"get","id":"aaaaaaaa"}""").operation, "control: the exact name runs")
        }

    @Test
    fun `S8 an unknown field is unknown_parameter with its pointer`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"aaaaaaaa","foo":1}""")
            assertEquals("unknown_parameter", result.code())
            assertEquals(listOf("/foo"), result.error().parameters())
            assertEquals(400, result.envelope.httpStatus)
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S8 a field that belongs to another select is unknown_parameter`(): Unit =
        runBlocking {
            val rejected = call(fx.readTool, """{"select":"get","id":"aaaaaaaa","limit":5}""")
            assertEquals("unknown_parameter", rejected.code())
            assertEquals(listOf("/limit"), rejected.error().parameters())

            val accepted = call(fx.readTool, """{"select":"list","limit":5}""")
            assertEquals("list", accepted.operation, "control: the same field is fine for the select that declares it")
            assertTrue(accepted.isOk())
        }

    @Test
    fun `S8 select and describe false are reserved and never unknown parameters`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"aaaaaaaa","describe":false}""")
            assertEquals("got:aaaaaaaa:1", result.body().str("data"))
        }

    @Test
    fun `S8 a type fault is invalid_request with the field pointer`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"aaaaaaaa","depth":"deep"}""")
            assertEquals("invalid_request", result.code())
            assertEquals(
                "/depth",
                result
                    .error()
                    .fields()
                    .single()
                    .first
            )
        }

    // ------------------------------------------------------------------ S9 ops, happy and per element

    @Test
    fun `S9 two creates return per-element results in order, a summary and operation create`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), create("B")))
            assertEquals(
                """{"ok":true,"data":{"results":[{"ok":true,"id":"created:A"},{"ok":true,"id":"created:B"}],"summary":{"ok":2,"failed":0}},"meta":{"reqId":"k7f3q9ab"}}""",
                result.body().toString()
            )
            assertEquals(200, result.envelope.httpStatus)
            assertEquals("create", result.operation)
            assertEquals(2, result.batchSize)
            assertEquals(listOf("A", "B"), titles())
        }

    @Test
    fun `S9 different ops in one call are operation mixed`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), """{"op":"rename","id":"aaaaaaaa","title":"T"}"""))
            assertEquals("mixed", result.operation)
            assertEquals(2, result.batchSize)
            assertEquals("renamed:aaaaaaaa:T", result.results()[1].jsonObject.str("id"))
        }

    @Test
    fun `S9 the same op repeated is that op, not mixed`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops("""{"op":"rename","id":"aaaaaaaa"}""", """{"op":"rename","id":"bbbbbbbb"}"""))
            assertEquals("rename", result.operation)
        }

    @Test
    fun `S9 a domain failure in one element is a partial_failure 207 with positions kept and only that write rolled back`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), create(FixtureTools.FAIL_TITLE), create("C")))
            assertEquals("partial_failure", result.code())
            assertEquals(207, result.envelope.httpStatus)
            assertEquals(3, result.results().size)
            assertEquals("true", result.results()[0].jsonObject.str("ok"))
            assertEquals("not_found", result.elementError(1).str("code"))
            assertEquals("created:C", result.results()[2].jsonObject.str("id"))
            assertEquals("""{"ok":2,"failed":1}""", result.data().getValue("summary").toString())
            assertEquals(listOf("A", "C"), titles(), "the failing element's own write is rolled back, its neighbours stay")
        }

    @Test
    fun `S9 an unknown field inside an element fails that element at its prefixed pointer without running it`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), create("B", ""","foo":1""")))
            assertEquals("partial_failure", result.code())
            val failed = result.elementError(1)
            assertEquals("unknown_parameter", failed.str("code"))
            assertEquals(listOf("/ops/1/foo"), failed.parameters())
            assertEquals(listOf("A"), titles(), "control: element 0 ran, the undecodable element 1 did not")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `S9 a type fault inside an element is invalid_request at the prefixed pointer`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), """{"op":"create","title":7}"""))
            val failed = result.elementError(1)
            assertEquals("invalid_request", failed.str("code"))
            assertEquals("/ops/1/title", failed.fields().single().first)
        }

    @Test
    fun `S9 a missing op is an element error at ops i op`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops("""{"title":"x"}""", create("A")))
            assertEquals("partial_failure", result.code())
            val failed = result.elementError(0)
            assertEquals("invalid_request", failed.str("code"))
            assertEquals("/ops/0/op", failed.fields().single().first)
            assertEquals(listOf("A"), titles())
        }

    @Test
    fun `S9 an unknown op and a profile-hidden op are the same element error`(): Unit =
        runBlocking {
            val unknown = call(fx.writeTool, ops("""{"op":"zap","id":"aaaaaaaa"}"""), ToolProfile.KNOWLEDGE)
            val hidden = call(fx.writeTool, ops("""{"op":"purge","id":"aaaaaaaa"}"""), ToolProfile.KNOWLEDGE)
            for (r in listOf(unknown, hidden)) {
                val failed = r.elementError(0)
                assertEquals("invalid_request", failed.str("code"))
                assertEquals("/ops/0/op", failed.fields().single().first)
            }
            assertTrue(fx.seen.isEmpty(), "the hidden op must not run in knowledge")

            val allowed = call(fx.writeTool, ops("""{"op":"purge","id":"aaaaaaaa"}"""), ToolProfile.ORCHESTRATION)
            assertEquals("purged:aaaaaaaa", allowed.results()[0].jsonObject.str("id"))
        }

    @Test
    fun `S9 an element that is not an object fails that element only`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, """{"ops":["text",${create("A")},5,null]}""")
            assertEquals("partial_failure", result.code())
            assertEquals(4, result.results().size)
            for (i in listOf(0, 2, 3)) {
                val failed = result.elementError(i)
                assertEquals("invalid_request", failed.str("code"), "element $i")
                assertTrue(
                    failed
                        .fields()
                        .single()
                        .first
                        .startsWith("/ops/$i"),
                    "element $i pointer: ${failed.fields()}"
                )
            }
            assertEquals("true", result.results()[1].jsonObject.str("ok"))
            assertEquals(listOf("A"), titles())
        }

    // ------------------------------------------------------------------ S9 ops, whole-call errors

    @Test
    fun `S9 ops absent, empty or not an array fail the whole call at the ops pointer`(): Unit =
        runBlocking {
            for (text in listOf("""{}""", """{"ops":[]}""", """{"ops":"x"}""", """{"ops":{"op":"create"}}""", """{"ops":null}""")) {
                val result = call(fx.writeTool, text)
                assertEquals("invalid_request", result.code(), text)
                assertEquals(
                    "/ops",
                    result
                        .error()
                        .fields()
                        .single()
                        .first,
                    text
                )
                assertEquals(400, result.envelope.httpStatus, text)
                assertFalse("data" in result.body(), "$text: a whole-call failure has no per-element data")
            }
            assertTrue(call(fx.writeTool, ops(create("A"))).isOk(), "control: a non-empty array runs")
        }

    @Test
    fun `S9 atomic that is not a boolean fails the whole call at the atomic pointer`(): Unit =
        runBlocking {
            for (text in listOf(""","atomic":"true"""", ""","atomic":1""")) {
                val result = call(fx.writeTool, ops(create("A"), top = text))
                assertEquals("invalid_request", result.code(), text)
                assertEquals(
                    "/atomic",
                    result
                        .error()
                        .fields()
                        .single()
                        .first,
                    text
                )
            }
            assertEquals(emptyList(), titles(), "the call failed before any element ran")
            assertEquals(
                1,
                call(fx.writeTool, ops(create("A"), top = ""","atomic":false""")).results().size,
                "control: a boolean is accepted"
            )
        }

    @Test
    fun `S9 an unknown top-level key is unknown_parameter and select is not an ops key`(): Unit =
        runBlocking {
            val unknown = call(fx.writeTool, ops(create("A"), top = ""","foo":1"""))
            assertEquals("unknown_parameter", unknown.code())
            assertEquals(listOf("/foo"), unknown.error().parameters())
            val select = call(fx.writeTool, ops(create("A"), top = ""","select":"get""""))
            assertEquals("unknown_parameter", select.code())
            assertEquals(listOf("/select"), select.error().parameters())
            assertEquals(emptyList(), titles(), "neither call ran its element")
        }

    // ------------------------------------------------------------------ S9 atomic

    @Test
    fun `S9 atomic rolls back the whole batch and reports the first failing element with its index`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), create(FixtureTools.FAIL_TITLE), create("C"), top = ""","atomic":true"""))
            assertEquals("not_found", result.code())
            assertEquals(1, result.error().str("index").toInt())
            assertEquals(404, result.envelope.httpStatus)
            assertFalse("data" in result.body())
            assertEquals(emptyList(), titles(), "element 0 was rolled back with the batch")

            val loose = call(fx.writeTool, ops(create("A"), create(FixtureTools.FAIL_TITLE), create("C")))
            assertEquals("partial_failure", loose.code(), "control: without atomic the same batch keeps A and C")
            assertEquals(listOf("A", "C"), titles())
        }

    @Test
    fun `S9 a decode failure inside an atomic batch also rolls everything back and reports its index`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("A"), create("B", ""","foo":1"""), create("C"), top = ""","atomic":true"""))
            assertEquals("unknown_parameter", result.code())
            assertEquals(1, result.error().str("index").toInt())
            assertEquals(emptyList(), titles())
        }

    // ------------------------------------------------------------------ S9 idempotency

    @Test
    fun `S9 an idempotencyKey without an actor fails that element and does not run it`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create("K", keyOne)))
            val failed = result.elementError(0)
            assertEquals("invalid_request", failed.str("code"))
            val (field, reason) = failed.fields().single()
            assertEquals("/ops/0/idempotencyKey", field)
            assertTrue("requires an actor" in reason, reason)
            assertEquals(emptyList(), titles())

            val withActor = call(fx.writeTool, """{$actor1,"ops":[${create("K", keyOne)}]}""")
            assertEquals("created:K", withActor.results()[0].jsonObject.str("id"), "control: with an actor the element runs")
            assertEquals(listOf("K"), titles())
        }

    @Test
    fun `S9 a non-string idempotencyKey fails that element`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, """{$actor1,"ops":[${create("K", ""","idempotencyKey":5""")}]}""")
            val failed = result.elementError(0)
            assertEquals("invalid_request", failed.str("code"))
            assertEquals("/ops/0/idempotencyKey", failed.fields().single().first)
            assertEquals(emptyList(), titles())
        }

    @Test
    fun `S9 resending the same op with the same key replays the stored result without running it again`(): Unit =
        runBlocking {
            val text = """{$actor1,"ops":[${create("R", keyOne)}]}"""
            val first = call(fx.writeTool, text)
            assertTrue(first.isOk())
            assertNull(first.results()[0].jsonObject["replayed"])
            val second = call(fx.writeTool, text)
            assertTrue(second.isOk())
            assertEquals("true", second.results()[0].jsonObject.str("replayed"))
            assertEquals("created:R", second.results()[0].jsonObject.str("id"))
            assertEquals(listOf("R"), titles(), "executed once")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `S9 the same key with a different payload is idempotency_mismatch and keys are scoped by actor id`(): Unit =
        runBlocking {
            call(fx.writeTool, """{$actor1,"ops":[${create("R", keyOne)}]}""")

            val mismatch = call(fx.writeTool, """{$actor1,"ops":[${create("OTHER", keyOne)}]}""")
            val failed = mismatch.elementError(0)
            assertEquals("idempotency_mismatch", failed.str("code"))
            assertEquals("key-1", failed.getValue("detail").jsonObject.str("idempotencyKey"))
            assertEquals(listOf("R"), titles())

            val otherActor = call(fx.writeTool, """{$actor2,"ops":[${create("OTHER", keyOne)}]}""")
            assertEquals("created:OTHER", otherActor.results()[0].jsonObject.str("id"), "another principal may reuse the key")
            assertEquals(listOf("OTHER", "R"), titles())
        }

    @Test
    fun `S9 the fingerprint ignores the idempotencyKey member and member order`(): Unit =
        runBlocking {
            call(fx.writeTool, """{$actor1,"ops":[${create("R", keyOne)}]}""")
            val reordered = call(fx.writeTool, """{$actor1,"ops":[{"idempotencyKey":"key-1","title":"R","op":"create"}]}""")
            assertEquals("true", reordered.results()[0].jsonObject.str("replayed"))
            assertEquals(listOf("R"), titles())
        }

    // ------------------------------------------------------------------ S10 actor

    @Test
    fun `S10 a valid actor becomes the principal with its verification result`(): Unit =
        runBlocking {
            call(
                fx.readTool,
                """{"actor":{"id":"agent-1","kind":"subagent","parent":"boss","proof":"tok"},"select":"get","id":"aaaaaaaa"}"""
            )
            val principal = assertNotNull(fx.seen.single().principal)
            assertEquals(ActorClaim("agent-1", ActorKind.SUBAGENT, "boss", "tok"), principal.claim)
            assertEquals(VerificationStatus.VERIFIED, principal.verification.status)
            assertEquals(listOf(ActorClaim("agent-1", ActorKind.SUBAGENT, "boss", "tok")), verifier.seen.toList())
        }

    @Test
    fun `S10 an absent actor is a null principal and the verifier is not called`(): Unit =
        runBlocking {
            call(fx.readTool, """{"select":"get","id":"aaaaaaaa"}""")
            assertNull(fx.seen.single().principal)
            assertTrue(verifier.seen.isEmpty())
            call(fx.readTool, """{"actor":{"id":"agent-1","kind":"user"},"select":"get","id":"aaaaaaaa"}""")
            assertEquals(1, verifier.seen.size, "control: the verifier is wired and is called when an actor is sent")
        }

    @Test
    fun `S10 an ops call verifies the actor once and every element sees the same principal`(): Unit =
        runBlocking {
            call(fx.writeTool, """{"actor":{"id":"agent-1","kind":"orchestrator"},"ops":[${create("A")},${create("B")}]}""")
            assertEquals(1, verifier.seen.size)
            assertEquals(2, fx.seen.size)
            assertEquals(setOf("agent-1"), fx.seen.map { it.principal?.claim?.id }.toSet())
        }

    @Test
    fun `S10 an unknown actor key is unknown_parameter at the actor pointer`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"actor":{"id":"a","kind":"user","x":1},"select":"get","id":"aaaaaaaa"}""")
            assertEquals("unknown_parameter", result.code())
            assertEquals(listOf("/actor/x"), result.error().parameters())
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S10 a malformed actor is invalid_request at an actor pointer and nothing runs`(): Unit =
        runBlocking {
            val cases =
                mapOf(
                    "\"just-a-string\"" to "/actor",
                    """{"kind":"user"}""" to "/actor/id",
                    """{"id":"a"}""" to "/actor/kind",
                    """{"id":"a","kind":"robot"}""" to "/actor/kind"
                )
            for ((actor, pointer) in cases) {
                val result = call(fx.readTool, """{"actor":$actor,"select":"get","id":"aaaaaaaa"}""")
                assertEquals("invalid_request", result.code(), actor)
                assertTrue(result.error().fields().any { it.first == pointer }, "$actor: expected $pointer in ${result.error().fields()}")
            }
            assertTrue(fx.seen.isEmpty())
            assertTrue(verifier.seen.isEmpty(), "a malformed actor is never verified")
        }

    @Test
    fun `S10 the actor is lifted before the selector, so a bad actor beats a bad select`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"actor":"bad","select":"nope"}""")
            assertEquals("invalid_request", result.code())
            assertTrue(result.error().fields().any { it.first.startsWith("/actor") }, "${result.error().fields()}")
            assertFalse(result.error().fields().any { it.first == "/select" }, "the selector is not reached: ${result.error().fields()}")
        }

    // ------------------------------------------------------------------ S13 escaping exceptions

    @Test
    fun `S13 an exception from a select handler is the fixed internal envelope with no exception text`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_SECRET}"}""")
            assertEquals(EnvelopeEncoder.internal(reqId).body, result.body())
            assertEquals(500, result.envelope.httpStatus)
            assertFalse(FixtureTools.SECRET in result.body().toString())
            assertEquals("internal", result.code())
        }

    @Test
    fun `S13 an exception from an op is the fixed internal envelope and its write is rolled back`(): Unit =
        runBlocking {
            val result = call(fx.writeTool, ops(create(FixtureTools.SECRET_TITLE)))
            assertEquals(EnvelopeEncoder.internal(reqId).body, result.body())
            assertEquals(500, result.envelope.httpStatus)
            assertFalse(FixtureTools.SECRET in result.body().toString())
            assertEquals(emptyList(), titles(), "the unit of work rolled back the write made before the throw")
            assertEquals(1, fx.seen.size, "control: the op did run and reach its write")
        }

    @Test
    fun `S13 a translated busy fault is unavailable, shedding, status 503`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_BUSY}"}""")
            assertEquals("unavailable", result.code())
            assertEquals("shedding", result.error().str("kind"))
            assertEquals(503, result.envelope.httpStatus)
            assertFalse("SQLiteException" in result.body().toString(), "an exception class name must not leak")
        }

    @Test
    fun `S13 cancellation is rethrown, never encoded`(): Unit =
        runBlocking {
            assertFailsWith<CancellationException> { call(fx.readTool, """{"select":"get","id":"${FixtureTools.GET_CANCEL}"}""") }
        }
}

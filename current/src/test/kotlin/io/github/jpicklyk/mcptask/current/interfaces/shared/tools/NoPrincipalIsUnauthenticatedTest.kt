package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.architecture.GuardSupport
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testAccess
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8ae4c8e7, R14) for the dispatcher's principal gate and the removal of the body
 * `actor`: scenarios S4, S5, S12 and the acceptance-criterion AC4 source check of the frozen test-plan, driven through
 * [ToolDispatcher.dispatch] with the [FixtureTools] registry on a real SQLite database.
 *
 * Oracles (written from the plan, never from a run): plan paragraph "No principal -> `unauthenticated` before decode" and
 * task-scope item 7 (`access.principal ?: return unauthenticated` ahead of describe, selector and decode; the envelope status is
 * the catalog status of `unauthenticated`, 401), item 8 (`actor` is not a reserved key: a top-level or element `actor` is
 * `unknown_parameter` at `/actor` or `/ops/<i>/actor` on every tool; `describe` tolerates only the selector, so `actor` beside
 * `describe` is rejected), AC3, AC4 (`idempotencyKey` works without an actor, keyed under the call principal; `ActorRequest`,
 * the dispatcher `KindWire`, `ActorAware`, `ActorParseResult`, `ActorVerificationScope`, `LOCAL_UNAUTH_PRINCIPAL` and the
 * `api-unauthenticated` verifier id no longer exist in production source), D1 (one `local` principal, so a key replays across
 * hosts) and the idempotency contract already pinned for the dispatcher (same key + same payload replays with `replayed:true`,
 * same key + other payload is `idempotency_mismatch`, keys are scoped per principal id).
 *
 * Negative-assertion conditions (what makes each fail without the feature): every unauthenticated refusal is paired with a
 * control call of the identical text under [CallAccess.LOCAL] that reaches the handler or fails with a DIFFERENT code (so a
 * gate that is dropped or that runs after decode fails the pair); "nothing ran" is read from the handler's `seen` list and
 * from `work_items` / the idempotency table, not from the response.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled. The AC4 source scan skips comment lines on purpose (a
 * KDoc may still say what was removed) and is paired with a synthetic positive control so the matcher cannot be vacuous.
 *
 * Domain invariants satisfied by construction: titles are plain strings the fixture `create` op accepts; principals come from the
 * test-support builder (`testAccess`), which satisfies [io.github.jpicklyk.mcptask.current.application.api.Principal] by type.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NoPrincipalIsUnauthenticatedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fx = FixtureTools()

    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private val dispatcher: ToolDispatcher by lazy {
        ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env)
    }

    private val reqId = "k7f3q9ab"

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun call(
        spec: ToolSpec,
        text: String,
        access: CallAccess,
        profile: ToolProfile = ToolProfile.ORCHESTRATION
    ): Dispatched = dispatcher.dispatch(spec, profile, obj(text), reqId, "mcp", access)

    private fun Dispatched.body(): JsonObject = envelope.body

    private fun Dispatched.isOk(): Boolean =
        body()
            .getValue("ok")
            .jsonPrimitive.content
            .toBoolean()

    private fun Dispatched.error(): JsonObject = body().getValue("error").jsonObject

    private fun Dispatched.code(): String = error().getValue("code").jsonPrimitive.content

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.parameters(): List<String> =
        getValue("detail")
            .jsonObject
            .getValue("parameters")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun JsonObject.firstField(): String =
        getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .first()
            .jsonObject
            .str("field")

    private fun Dispatched.results(): JsonArray =
        body()
            .getValue("data")
            .jsonObject
            .getValue("results")
            .jsonArray

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

    private val keyOne = ""","idempotencyKey":"key-1""""

    // ------------------------------------------------------------------ S4 no principal

    private val selectCases =
        listOf(
            """{"select":"get","id":"aaaaaaaa"}""",
            """{"select":"get","id":"aaaaaaaa","bogus":1}""",
            """{"select":"nope"}""",
            """{}""",
            """{"describe":true}""",
            """{"select":"get","id":"aaaaaaaa","actor":{"id":"a","kind":"user"}}"""
        )

    @Test
    fun `S4 a call with no principal is unauthenticated before describe, selector and decode and the handler never runs`(): Unit =
        runBlocking {
            for (text in selectCases) {
                val refused = call(fx.readTool, text, CallAccess.unauthenticated())
                assertFalse(refused.isOk(), text)
                assertEquals("unauthenticated", refused.code(), text)
                assertEquals(401, refused.envelope.httpStatus, text)
                assertEquals(
                    reqId,
                    refused
                        .body()
                        .getValue("meta")
                        .jsonObject
                        .str("reqId"),
                    "$text: the envelope still carries the reqId"
                )
                assertTrue(fx.seen.isEmpty(), "$text: the handler never ran")
            }
            for (text in selectCases) {
                val control = call(fx.readTool, text, CallAccess.LOCAL)
                assertTrue(
                    control.isOk() || control.code() != "unauthenticated",
                    "control $text: the principal-bearing access is not unauthenticated"
                )
            }
            assertEquals(1, fx.seen.size, "control: the one well-formed call under a principal reached the handler")
        }

    @Test
    fun `S4 the control codes differ, so the gate is what answers the unauthenticated calls`(): Unit =
        runBlocking {
            val codes = selectCases.map { text -> call(fx.readTool, text, CallAccess.LOCAL).let { if (it.isOk()) "ok" else it.code() } }
            assertEquals(
                listOf("ok", "unknown_parameter", "invalid_request", "invalid_request", "ok", "unknown_parameter"),
                codes,
                "with a principal the same texts fail in their own ways, which the gate must pre-empt"
            )
        }

    @Test
    fun `S4 an ops call with no principal is unauthenticated for the whole call, writes nothing and stores no idempotency record`(): Unit =
        runBlocking {
            val withKey = ops(create("A", keyOne))
            for (text in listOf(withKey, ops(create("A")), """{"ops":[]}""", """{"describe":true}""")) {
                val refused = call(fx.writeTool, text, CallAccess.unauthenticated())
                assertEquals("unauthenticated", refused.code(), text)
                assertEquals(401, refused.envelope.httpStatus, text)
                assertFalse("data" in refused.body(), "$text: a whole-call refusal, not per-element results")
            }
            assertEquals(emptyList(), titles(), "nothing was written")
            assertEquals(0, db.idempotencyRecordCount(), "no idempotency record was stored")
            assertTrue(fx.seen.isEmpty())

            call(fx.writeTool, withKey, CallAccess.LOCAL)
            assertEquals(listOf("A"), titles(), "control: the same call under a principal writes")
            assertEquals(1, db.idempotencyRecordCount(), "control: and stores its record")
        }

    @Test
    fun `S4 every profile refuses a call with no principal the same way, even where the selector would be hidden`(): Unit =
        runBlocking {
            for (profile in ToolProfile.entries) {
                val refused = call(fx.readTool, """{"select":"nope"}""", CallAccess.unauthenticated(), profile)
                assertEquals("unauthenticated", refused.code(), "profile ${profile.wire}")
                val control = call(fx.readTool, """{"select":"nope"}""", CallAccess.LOCAL, profile)
                assertEquals("invalid_request", control.code(), "control profile ${profile.wire}")
            }
        }

    @Test
    fun `S4 a session id on an access without a principal does not authenticate it`(): Unit =
        runBlocking {
            val refused = call(fx.readTool, """{"select":"get","id":"aaaaaaaa"}""", CallAccess.unauthenticated("s-1"))
            assertEquals("unauthenticated", refused.code())
            assertTrue(fx.seen.isEmpty())
        }

    // ------------------------------------------------------------------ S5 body actor

    @Test
    fun `S5 a body actor of any shape on a select is unknown_parameter at slash actor and the handler never runs`(): Unit =
        runBlocking {
            val shapes = listOf("""{"id":"a","kind":"user"}""", "\"just-a-string\"", "null", "{}", "[]", "7", "true")
            for (actor in shapes) {
                val result = call(fx.readTool, """{"actor":$actor,"select":"get","id":"aaaaaaaa"}""", CallAccess.LOCAL)
                assertEquals("unknown_parameter", result.code(), actor)
                assertEquals(listOf("/actor"), result.error().parameters(), actor)
                assertEquals(400, result.envelope.httpStatus, actor)
            }
            assertTrue(fx.seen.isEmpty(), "no refused call reached the handler")
            val control = call(fx.readTool, """{"select":"get","id":"aaaaaaaa"}""", CallAccess.LOCAL)
            assertTrue(control.isOk(), "control: the same call without actor runs")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `S5 a body actor is refused even for a principal that would be allowed to act as someone`(): Unit =
        runBlocking {
            val result =
                call(fx.readTool, """{"actor":{"id":"agent-7","kind":"subagent"},"select":"get","id":"aaaaaaaa"}""", testAccess("agent-7"))
            assertEquals("unknown_parameter", result.code())
            assertEquals(listOf("/actor"), result.error().parameters())
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S5 a body actor on an ops tool is unknown_parameter at the top level and at the element position`(): Unit =
        runBlocking {
            val top = call(fx.writeTool, """{"actor":{"id":"a","kind":"user"},"ops":[${create("A")}]}""", CallAccess.LOCAL)
            assertEquals("unknown_parameter", top.code())
            assertEquals(listOf("/actor"), top.error().parameters())

            val first = call(fx.writeTool, ops("""{"op":"create","title":"B","actor":{"id":"a","kind":"user"}}"""), CallAccess.LOCAL)
            assertEquals("unknown_parameter", first.elementError(0).str("code"))
            assertEquals(listOf("/ops/0/actor"), first.elementError(0).parameters())

            val second = call(fx.writeTool, ops(create("C"), """{"op":"create","title":"D","actor":"x"}"""), CallAccess.LOCAL)
            assertEquals("partial_failure", second.code(), "a non-atomic batch reports the failed element in place")
            assertEquals(listOf("/ops/1/actor"), second.elementError(1).parameters())
            assertEquals(listOf("C"), titles(), "control: the clean element ran, the actor-bearing ones did not")
        }

    @Test
    fun `S5 an actor in an atomic batch rolls back the whole batch and reports its index`(): Unit =
        runBlocking {
            val text =
                ops(
                    create("A"),
                    """{"op":"create","title":"B","actor":{"id":"a","kind":"user"}}""",
                    create("C"),
                    top = ""","atomic":true"""
                )
            val result = call(fx.writeTool, text, CallAccess.LOCAL)
            assertEquals("unknown_parameter", result.code())
            assertEquals(1, result.error().str("index").toInt())
            assertEquals(emptyList(), titles(), "element 0 was rolled back with the batch")
        }

    @Test
    fun `S5 describe beside an actor is rejected at slash actor, and a differently cased Actor is just another unknown field`(): Unit =
        runBlocking {
            val described = call(fx.readTool, """{"describe":true,"actor":{"id":"agent-1","kind":"user"}}""", CallAccess.LOCAL)
            assertEquals("invalid_request", described.code())
            assertEquals("/actor", described.error().firstField())

            val cased = call(fx.readTool, """{"Actor":{"id":"a"},"select":"get","id":"aaaaaaaa"}""", CallAccess.LOCAL)
            assertEquals("unknown_parameter", cased.code())
            assertEquals(listOf("/Actor"), cased.error().parameters())
            assertTrue(fx.seen.isEmpty())
        }

    // ------------------------------------------------------------------ S12 idempotency without an actor

    @Test
    fun `S12 an idempotencyKey element needs no actor, replays under the same principal and is scoped per principal`(): Unit =
        runBlocking {
            val first = call(fx.writeTool, ops(create("R", keyOne)), CallAccess.LOCAL)
            assertTrue(first.isOk(), first.body().toString())
            assertEquals("created:R", first.results()[0].jsonObject.str("id"))
            assertFalse("requires an actor" in first.body().toString())

            val replay = call(fx.writeTool, ops(create("R", keyOne)), CallAccess.LOCAL)
            assertEquals("true", replay.results()[0].jsonObject.str("replayed"), "the same principal replays its key")
            assertEquals(listOf("R"), titles(), "executed once")

            val mismatch = call(fx.writeTool, ops(create("OTHER", keyOne)), CallAccess.LOCAL)
            assertEquals("idempotency_mismatch", mismatch.elementError(0).str("code"))
            assertEquals(listOf("R"), titles())

            val other = call(fx.writeTool, ops(create("OTHER", keyOne)), testAccess("another-principal"))
            assertEquals("created:OTHER", other.results()[0].jsonObject.str("id"), "another principal may reuse the key")
            assertEquals(listOf("OTHER", "R"), titles())
        }

    // ------------------------------------------------------------------ AC4 removed machinery

    private val removedTokens =
        listOf("ActorRequest", "ActorAware", "ActorParseResult", "ActorVerificationScope", "LOCAL_UNAUTH_PRINCIPAL", "api-unauthenticated")

    private fun codeLinesMentioning(
        sources: List<GuardSupport.Source>,
        token: String
    ): List<String> =
        sources
            .filter { source -> source.text.lines().any { GuardSupport.isCodeLine(it) && token in it } }
            .map { it.path }

    @Test
    fun `AC4 the removed actor machinery appears on no production code line`() {
        val sources = GuardSupport.productionSources()
        assertTrue(sources.isNotEmpty(), "vacuity: production sources are in scope")
        for (token in removedTokens) {
            assertEquals(emptyList(), codeLinesMentioning(sources, token), "$token must not be mentioned by production code")
        }
        val dispatcherSource =
            assertNotNull(
                sources.singleOrNull { it.path == "interfaces/shared/tools/ToolDispatcher.kt" },
                "vacuity: ToolDispatcher.kt is in scope"
            )
        assertFalse(
            dispatcherSource.text.lines().any { GuardSupport.isCodeLine(it) && Regex("""\bKindWire\b""").containsMatchIn(it) },
            "the dispatcher's private KindWire is gone"
        )
    }

    @Test
    fun `AC4 the scan matcher flags a code line and ignores a comment line`() {
        val seeded =
            listOf(
                GuardSupport.Source("a/Code.kt", "val x = LOCAL_UNAUTH_PRINCIPAL"),
                GuardSupport.Source("a/Comment.kt", "// LOCAL_UNAUTH_PRINCIPAL was removed"),
                GuardSupport.Source("a/Doc.kt", " * mentions ActorAware")
            )
        assertEquals(listOf("a/Code.kt"), codeLinesMentioning(seeded, "LOCAL_UNAUTH_PRINCIPAL"))
        assertEquals(emptyList(), codeLinesMentioning(seeded, "ActorAware"))
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [ToolDescriber] and [ErrorDetailDocs] (S11, NEW-SURFACE), through
 * both the direct function and `ToolDispatcher` with `describe:true`.
 *
 * Oracles: task-scope Contract (`describe(spec, profile, only)` -> `data = {tool, description, inputSchema, operations:[{name,
 * description, parameters (the op request schema with its full required list), errors:[{code, kind, detail?}]}]}` over visible
 * ops, narrowed by `only`; errors = declared plus common, sorted by wire; common = invalid_request, unknown_parameter,
 * unavailable, internal; plus invalid_cursor for a Paged select; plus partial_failure and idempotency_mismatch for ops;
 * `detail` = member name to non-blank type text, member set = the envelope section 4 detail column, config_invalid -> {errors},
 * gate_blocked with `violations`, absent for codes with no detail) and decision D7 (`describe` must be a boolean; `true`
 * allows only `actor` and the selector to narrow, any other key is `invalid_request` at its pointer with reason "not allowed
 * with describe"; `false` is a normal call; call_log operation `describe`) and D2 (describe comes before the selector).
 * The wire name and kind of each error are recomputed from the declared `ErrorCode` entries, not from the describer.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ToolDescriberTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fx = FixtureTools()

    private val verified = CopyOnWriteArrayList<ActorClaim>()

    private val verifier =
        object : ActorVerifier {
            override suspend fun verify(actor: ActorClaim): VerificationResult {
                verified += actor
                return VerificationResult(VerificationStatus.VERIFIED, verifier = "test-verifier")
            }
        }

    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private val dispatcher: ToolDispatcher by lazy {
        ToolDispatcher(RequestDecoder(), verifier, BatchRunner(env.unitOfWork, env.idempotency), env)
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun call(
        spec: ToolSpec,
        text: String,
        profile: ToolProfile = ToolProfile.KNOWLEDGE
    ): Dispatched = dispatcher.dispatch(spec, profile, obj(text), "k7f3q9ab", "mcp")

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.operations(): List<JsonObject> = getValue("operations").jsonArray.map { it.jsonObject }

    private fun JsonObject.operation(name: String): JsonObject = operations().single { it.str("name") == name }

    private fun JsonObject.errorCodes(): List<String> = getValue("errors").jsonArray.map { it.jsonObject.str("code") }

    private fun Dispatched.errorFields(): List<Pair<String, String>> =
        envelope.body
            .getValue("error")
            .jsonObject
            .getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .map { it.jsonObject.str("field") to it.jsonObject.str("reason") }

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

    // ------------------------------------------------------------------ S11 direct

    @Test
    fun `S11 an ops tool describes its visible ops with parameters and errors`() {
        val d = ToolDescriber.describe(fx.writeTool, ToolProfile.KNOWLEDGE, null)
        assertEquals("fx_write", d.str("tool"))
        assertTrue(d.str("description").startsWith("Fixture write tool."), d.str("description"))
        assertEquals(ToolSchemaRenderer.inputSchema(fx.writeTool, ToolProfile.KNOWLEDGE), d.getValue("inputSchema").jsonObject)
        assertEquals(listOf("create", "rename"), d.operations().map { it.str("name") }, "visible ops only, in registration order")

        val create = d.operation("create")
        assertEquals("Create a thing.", create.str("description"))
        val parameters = create.getValue("parameters").jsonObject
        assertEquals(setOf("title", "tags", "priority"), parameters.getValue("properties").jsonObject.keys)
        assertEquals(
            listOf("title"),
            parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content },
            "the op's full required list"
        )
        assertEquals(
            listOf("id"),
            d.operation("rename").getValue("parameters").jsonObject.getValue("required").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
    }

    @Test
    fun `S11 errors are declared plus common plus op errors, sorted by wire`() {
        val d = ToolDescriber.describe(fx.writeTool, ToolProfile.KNOWLEDGE, null)
        assertEquals(
            listOf(
                "idempotency_mismatch",
                "internal",
                "invalid_request",
                "not_found",
                "partial_failure",
                "unavailable",
                "unknown_parameter"
            ),
            d.operation("create").errorCodes()
        )
        assertEquals(
            listOf("idempotency_mismatch", "internal", "invalid_request", "partial_failure", "unavailable", "unknown_parameter"),
            d.operation("rename").errorCodes()
        )
    }

    @Test
    fun `S11 select errors add invalid_cursor only for a Paged select`() {
        val d = ToolDescriber.describe(fx.readTool, ToolProfile.KNOWLEDGE, null)
        assertEquals(listOf("get", "list"), d.operations().map { it.str("name") })
        assertEquals(
            listOf("internal", "invalid_request", "not_found", "unavailable", "unknown_parameter"),
            d.operation("get").errorCodes()
        )
        assertEquals(
            listOf("internal", "invalid_cursor", "invalid_request", "unavailable", "unknown_parameter"),
            d.operation("list").errorCodes()
        )
        assertEquals(
            listOf("id"),
            d.operation("get").getValue("parameters").jsonObject.getValue("required").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        assertFalse("required" in d.operation("list").getValue("parameters").jsonObject, "list has no required field")
    }

    @Test
    fun `S11 each error carries the wire code, the lowercase kind and a detail only for codes that have one`() {
        val d = ToolDescriber.describe(fx.writeTool, ToolProfile.KNOWLEDGE, null)
        for (entry in d
            .operation("create")
            .getValue("errors")
            .jsonArray
            .map { it.jsonObject }) {
            val code = ErrorCode.entries.single { it.wire == entry.str("code") }
            assertEquals(code.kind.name.lowercase(), entry.str("kind"), code.wire)
            assertEquals(code.detailClass != null, "detail" in entry, "${code.wire}: detail present iff the code defines one")
        }
    }

    @Test
    fun `S11 the declared common error sets are the frozen ones`() {
        assertEquals(
            setOf(ErrorCode.INVALID_REQUEST, ErrorCode.UNKNOWN_PARAMETER, ErrorCode.UNAVAILABLE, ErrorCode.INTERNAL),
            ToolDescriber.COMMON_ERRORS
        )
        assertEquals(setOf(ErrorCode.INVALID_CURSOR), ToolDescriber.PAGED_ERRORS)
        assertEquals(setOf(ErrorCode.PARTIAL_FAILURE, ErrorCode.IDEMPOTENCY_MISMATCH), ToolDescriber.OP_ERRORS)
    }

    @Test
    fun `S11 only narrows the operations and a hidden op is never listed`() {
        val narrowed = ToolDescriber.describe(fx.writeTool, ToolProfile.ORCHESTRATION, setOf("rename"))
        assertEquals(listOf("rename"), narrowed.operations().map { it.str("name") })

        val all = ToolDescriber.describe(fx.writeTool, ToolProfile.ORCHESTRATION, null)
        assertEquals(listOf("create", "rename", "purge"), all.operations().map { it.str("name") })

        val hidden = ToolDescriber.describe(fx.writeTool, ToolProfile.KNOWLEDGE, setOf("purge", "create"))
        assertFalse(hidden.operations().any { it.str("name") == "purge" }, "purge is orchestration-only")
        assertEquals(listOf("create"), hidden.operations().map { it.str("name") })
        assertFalse("purge" in ToolDescriber.describe(fx.writeTool, ToolProfile.KNOWLEDGE, null).toString())
    }

    // ------------------------------------------------------------------ S11 ErrorDetailDocs

    @Test
    fun `S11 ErrorDetailDocs is absent exactly for the codes without a detail class`() {
        for (code in ErrorCode.entries) {
            val docs = ErrorDetailDocs.of(code)
            if (code.detailClass == null) {
                assertNull(docs, "${code.wire} has no detail")
            } else {
                val present = assertNotNull(docs, "${code.wire} defines a detail")
                assertTrue(present.isNotEmpty(), code.wire)
                for ((member, text) in present) {
                    assertTrue(
                        text is JsonPrimitive && text.isString && text.content.isNotBlank(),
                        "${code.wire}.$member needs a non-blank type text: $text"
                    )
                }
            }
        }
    }

    @Test
    fun `S11 ErrorDetailDocs member sets match the envelope section 4 detail column`() {
        val expected =
            mapOf(
                ErrorCode.UNKNOWN_PARAMETER to setOf("parameters"),
                ErrorCode.NOT_FOUND to setOf("kind", "id"),
                ErrorCode.AMBIGUOUS_ID to setOf("prefix", "candidates"),
                ErrorCode.VERSION_CONFLICT to setOf("kind", "id", "expected", "actual"),
                ErrorCode.DUPLICATE to setOf("kind", "id", "existingId"),
                ErrorCode.IDEMPOTENCY_MISMATCH to setOf("idempotencyKey"),
                ErrorCode.INVALID_TRANSITION to setOf("itemId", "fromRole", "trigger", "allowed"),
                ErrorCode.GATE_BLOCKED to setOf("itemId", "role", "missing", "violations"),
                ErrorCode.DEPENDENCY_UNMET to setOf("itemId", "blockers"),
                ErrorCode.CYCLE_DETECTED to setOf("path"),
                ErrorCode.CLAIM_HELD to setOf("itemId", "expiresAt", "retryAfterMs"),
                ErrorCode.NOT_CLAIM_HOLDER to setOf("itemId"),
                ErrorCode.SEAT_FORBIDDEN to setOf("itemId", "seat", "action", "allowedSeats"),
                ErrorCode.NOTE_OWNED_BY_OTHER to setOf("itemId", "key", "ownerSeat"),
                ErrorCode.NOTE_TOO_LONG to setOf("key", "max", "actual"),
                ErrorCode.RESOURCE_UNAVAILABLE to setOf("itemId", "resources", "retryAfterMs"),
                ErrorCode.SCHEMA_VIOLATION to setOf("type", "reason", "availableTypes", "availableTraits"),
                ErrorCode.SCHEMA_PINNED_CONFLICT to setOf("itemId", "pinnedVersion", "currentVersion"),
                ErrorCode.CONFIG_INVALID to setOf("errors"),
                ErrorCode.PAYLOAD_TOO_LARGE to setOf("max", "actual"),
                ErrorCode.FORBIDDEN to setOf("scope", "required"),
                ErrorCode.UNAVAILABLE to setOf("retryAfterMs")
            )
        for ((code, members) in expected) {
            assertEquals(members, assertNotNull(ErrorDetailDocs.of(code), code.wire).keys, code.wire)
        }
        assertTrue("violations" in assertNotNull(ErrorDetailDocs.of(ErrorCode.GATE_BLOCKED)), "gate_blocked documents violations (Q13/U19)")
        val invalid = assertNotNull(ErrorDetailDocs.of(ErrorCode.INVALID_REQUEST))
        assertTrue(invalid.isNotEmpty(), "invalid_request documents its violation members")
    }

    // ------------------------------------------------------------------ S11 through the dispatcher

    @Test
    fun `S11 describe true answers before the selector and reports operation describe`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"describe":true}""")
            assertEquals("describe", result.operation)
            assertNull(result.batchSize)
            assertEquals(200, result.envelope.httpStatus)
            val body = result.envelope.body
            assertEquals("true", body.str("ok"))
            val data = body.getValue("data").jsonObject
            assertEquals("fx_read", data.str("tool"))
            assertEquals(listOf("get", "list"), data.operations().map { it.str("name") })
            assertEquals("k7f3q9ab", body.getValue("meta").jsonObject.str("reqId"))
            assertTrue(fx.seen.isEmpty(), "describe never runs a handler")
        }

    @Test
    fun `S11 describe with a select narrows to it`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"describe":true,"select":"list"}""")
            assertEquals(
                listOf("list"),
                result.envelope.body
                    .getValue("data")
                    .jsonObject
                    .operations()
                    .map { it.str("name") }
            )
        }

    @Test
    fun `S11 describe in knowledge never lists an orchestration select, in orchestration it does`(): Unit =
        runBlocking {
            val knowledge = call(fx.readTool, """{"describe":true}""", ToolProfile.KNOWLEDGE)
            assertFalse("audit" in knowledge.envelope.body.toString())
            val orchestration = call(fx.readTool, """{"describe":true}""", ToolProfile.ORCHESTRATION)
            assertEquals(
                listOf("get", "list", "audit"),
                orchestration.envelope.body
                    .getValue("data")
                    .jsonObject
                    .operations()
                    .map { it.str("name") }
            )
        }

    @Test
    fun `S11 describe on an ops tool narrows by the op names in ops and runs nothing`(): Unit =
        runBlocking {
            val all = call(fx.writeTool, """{"describe":true}""")
            assertEquals(
                listOf("create", "rename"),
                all.envelope.body
                    .getValue("data")
                    .jsonObject
                    .operations()
                    .map { it.str("name") }
            )
            val narrowed = call(fx.writeTool, """{"describe":true,"ops":[{"op":"rename"}]}""")
            assertEquals(
                listOf("rename"),
                narrowed.envelope.body
                    .getValue("data")
                    .jsonObject
                    .operations()
                    .map { it.str("name") }
            )
            assertEquals("describe", narrowed.operation)
            assertEquals(emptyList(), titles())
            assertTrue(fx.seen.isEmpty())

            val control = call(fx.writeTool, """{"ops":[{"op":"create","title":"A"}]}""")
            assertEquals("create", control.operation)
            assertEquals(listOf("A"), titles(), "control: without describe the same tool does run")
        }

    @Test
    fun `S11 describe true with any other key is invalid_request at that key`(): Unit =
        runBlocking {
            val extra = call(fx.readTool, """{"describe":true,"foo":1}""")
            assertEquals(
                "invalid_request",
                extra.envelope.body
                    .getValue("error")
                    .jsonObject
                    .str("code")
            )
            val (field, reason) = extra.errorFields().single()
            assertEquals("/foo", field)
            assertTrue("not allowed with describe" in reason, reason)

            val argument = call(fx.readTool, """{"describe":true,"select":"get","id":"aaaaaaaa"}""")
            assertEquals(
                "invalid_request",
                argument.envelope.body
                    .getValue("error")
                    .jsonObject
                    .str("code")
            )
            assertEquals("/id", argument.errorFields().single().first)

            val atomic = call(fx.writeTool, """{"describe":true,"atomic":true}""")
            assertEquals("/atomic", atomic.errorFields().single().first)

            val elementField = call(fx.writeTool, """{"describe":true,"ops":[{"op":"create","title":"x"}]}""")
            assertEquals("/ops/0/title", elementField.errorFields().single().first)
            assertEquals(emptyList(), titles())
        }

    @Test
    fun `S11 describe must be a boolean and false is a normal call`(): Unit =
        runBlocking {
            for (text in listOf("""{"describe":"true"}""", """{"describe":1}""", """{"describe":null}""", """{"describe":"yes"}""")) {
                val result = call(fx.readTool, text)
                assertEquals(
                    "invalid_request",
                    result.envelope.body
                        .getValue("error")
                        .jsonObject
                        .str("code"),
                    text
                )
                assertEquals("/describe", result.errorFields().single().first, text)
            }
            val normal = call(fx.readTool, """{"describe":false,"select":"get","id":"aaaaaaaa"}""")
            assertEquals("get", normal.operation)
            assertEquals("got:aaaaaaaa:1", normal.envelope.body.str("data"))
        }

    @Test
    fun `S11 an actor is allowed with describe and is verified`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{"describe":true,"actor":{"id":"agent-1","kind":"user"}}""")
            assertEquals("describe", result.operation)
            assertEquals(listOf("agent-1"), verified.map { it.id })
        }

    @Test
    fun `S11 without describe a missing select is still an error, so describe is what skipped the selector`(): Unit =
        runBlocking {
            val result = call(fx.readTool, """{}""")
            assertEquals(
                "invalid_request",
                result.envelope.body
                    .getValue("error")
                    .jsonObject
                    .str("code")
            )
            assertEquals("/select", result.errorFields().single().first)
        }

    @Test
    fun `S11 dispatcher describe data equals the describer output for the same profile`(): Unit =
        runBlocking {
            val viaDispatcher = call(fx.writeTool, """{"describe":true}""", ToolProfile.ORCHESTRATION)
            assertEquals(
                ToolDescriber.describe(fx.writeTool, ToolProfile.ORCHESTRATION, null),
                viaDispatcher.envelope.body
                    .getValue("data")
                    .jsonObject
            )
        }

    @Test
    fun `S11 an array of results is never produced for describe`(): Unit =
        runBlocking {
            val data = call(fx.writeTool, """{"describe":true}""").envelope.body.getValue("data")
            assertTrue(data is JsonObject)
            assertFalse("results" in data.jsonObject)
            assertTrue(data.jsonObject.getValue("operations") is JsonArray)
        }
}

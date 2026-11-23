package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.CallLogFields
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.CallTelemetryFactory
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.HostName
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.ReqId
import io.github.jpicklyk.mcptask.current.platform.logging.MdcValues
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.support.rethrowIfCancellation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import kotlin.time.TimeSource

/**
 * Registers the [ToolSpec] tools on the MCP SDK [Server] and serves their calls through the
 * transport-neutral [ToolDispatcher].
 *
 * Registration uses the SDK `Tool` from [toolFor]: name, rendered description, generated input schema
 * and MCP annotations. Per call: a fresh [ReqId], MDC correlation fields
 * (`transport`, `tool`, `sessionId`, `reqId`, and a length-capped `actorId`: the audited id of the call's principal)
 * and a [CallTelemetry] for the MCP surface. The call runs under the [access] of its session; the principal's `host`
 * is the MCP client's `clientInfo.name` when the client sent one. The result carries the envelope body as `structuredContent`,
 * `isError = !ok`, one text content and `_meta.reqId`. The text is `ok`, or on an error `<code>: <message>`, a newline and one
 * compact JSON line (the envelope, so a text-only client keeps `fix`, `detail` and `retryAfterMs`; for `partial_failure`
 * `data.results` holds only the failing entries, each with its `index`, at most the first 10). One `call_log`
 * record is submitted, and one INFO `tool call:` line is logged inside the call's MDC, per call that was not
 * cancelled; the line carries the tool name, operation, outcome, error code, latency and response bytes, never an
 * argument value or body text. No `notifications/message` is sent.
 */
class McpOperationAdapter(
    private val dispatcher: ToolDispatcher,
    private val callLog: CallLogSink = CallLogSink.NONE,
    private val clock: Clock = Clock.SYSTEM,
    /**
     * The access of a call by MCP session: production passes [McpSessionPrincipals.access] over HTTP and the local
     * principal's access over stdio; the parity harness passes the REST principal's. The default is the local access.
     */
    private val access: (sessionId: String?) -> CallAccess = { CallAccess.LOCAL }
) {
    private val logger = LoggerFactory.getLogger(McpOperationAdapter::class.java)

    /** Registers every tool of [registry] visible in [profile]. Call before any session connects. */
    fun register(
        server: Server,
        registry: ToolRegistry,
        profile: ToolProfile
    ) {
        for (spec in registry.tools) {
            val tool = toolFor(spec, profile) ?: continue
            server.addTool(tool) { request ->
                val session = server.sessions[sessionId]
                handle(spec, profile, request.arguments, transportSessionId(session) ?: sessionId, session?.clientVersion?.name)
            }
            logger.debug("Registered 4.0 tool '{}' ({} profile)", spec.name, profile.wire)
        }
    }

    /**
     * The session id the client sees: over Streamable HTTP, the transport's id, the one the SDK echoes in
     * `Mcp-Session-Id` and the one [McpBearerAuth] binds the principal under. The SDK's own `ServerSession.sessionId`
     * (the `ClientConnection.sessionId` a tool handler receives) is an unrelated random UUID, so looking the binding up
     * by it never finds one. Null for a transport without a session id (stdio); the caller then keeps the SDK's id.
     */
    private fun transportSessionId(session: ServerSession?): String? = (session?.transport as? StreamableHttpServerTransport)?.sessionId

    private suspend fun handle(
        spec: ToolSpec,
        profile: ToolProfile,
        rawArguments: JsonObject?,
        sessionId: String?,
        clientName: String?
    ): CallToolResult {
        val arguments = rawArguments ?: JsonObject(emptyMap())
        val reqId = ReqId.generate()
        val startedAt = clock.now()
        val started = TimeSource.Monotonic.markNow()
        val telemetry = CallTelemetryFactory.mcp(reqId, sessionId, clientName)
        val callAccess = withClientHost(access(sessionId), clientName)
        val correlationFields =
            buildMap {
                put("transport", "mcp")
                put("tool", spec.name)
                sessionId?.let { put("sessionId", it) }
                put("reqId", reqId)
                callAccess.principal?.let { put("actorId", MdcValues.bounded(it.claim.id, max = ACTOR_ID_MDC_MAX_LENGTH)) }
            }
        // A cancellation propagates out of withContext and writes no record and no log line.
        return withContext(
            MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + correlationFields) + telemetry
        ) {
            val dispatched =
                try {
                    dispatcher.dispatch(spec, profile, arguments, reqId, CallLogRecord.SURFACE_MCP, callAccess)
                } catch (e: Exception) {
                    // The dispatcher translates escaping exceptions itself; this is a last-resort net.
                    e.rethrowIfCancellation()
                    logger.error("Tool '{}' (reqId {}) failed outside dispatch: {}", spec.name, reqId, e.message, e)
                    Dispatched(EnvelopeEncoder.internal(reqId), null, null)
                }
            val body = dispatched.envelope.body
            val result =
                CallToolResult(
                    content = listOf(TextContent(text = text(body))),
                    isError = !ok(body),
                    structuredContent = body,
                    meta = buildJsonObject { put("reqId", reqId) }
                )
            val latencyMs = started.elapsedNow().inWholeMilliseconds
            val responseBytes = responseBytes(result, body)
            logCall(spec, dispatched, body, latencyMs, responseBytes)
            submitCallLog(spec, profile, arguments, dispatched, telemetry, startedAt, latencyMs, responseBytes)
            result
        }
    }

    /** The one INFO `tool call:` line of a call, logged inside its MDC; never argument values or body text. */
    private fun logCall(
        spec: ToolSpec,
        dispatched: Dispatched,
        body: JsonObject,
        latencyMs: Long,
        responseBytes: Long
    ) {
        try {
            val isOk = ok(body)
            logger.info(
                "tool call: name={}, operation={}, outcome={}, errorCode={}, latencyMs={}, responseBytes={}",
                spec.name,
                dispatched.operation ?: "-",
                if (isOk) CallLogRecord.OUTCOME_OK else CallLogRecord.OUTCOME_ERROR,
                if (isOk) "-" else (CallLogFields.errorCode(body) ?: "-"),
                latencyMs,
                responseBytes
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
        }
    }

    /** Builds and submits the `call_log` record; any failure is logged at DEBUG and swallowed. */
    private fun submitCallLog(
        spec: ToolSpec,
        profile: ToolProfile,
        arguments: JsonObject,
        dispatched: Dispatched,
        telemetry: CallTelemetry,
        startedAt: Instant,
        latencyMs: Long,
        responseBytes: Long
    ) {
        try {
            val body = dispatched.envelope.body
            val data = body["data"] as? JsonObject
            callLog.submit(
                CallLogFields.record(
                    telemetry = telemetry,
                    at = startedAt,
                    tool = spec.name,
                    operation = dispatched.operation,
                    targetIds = CallLogFields.targetIdsJson(arguments),
                    requestShape =
                        CallLogFields.requestShapeJson(
                            arguments,
                            telemetry.searchShape,
                            telemetry.relatedSignals,
                            profile.wire,
                            telemetry.fields,
                            telemetry.multiRoot
                        ),
                    isError = !ok(body),
                    errorCode = CallLogFields.errorCode(body),
                    latencyMs = latencyMs,
                    requestBytes = CallLogFields.utf8Length(arguments.toString()),
                    responseBytes = responseBytes,
                    batchSize = dispatched.batchSize,
                    failedCount = CallLogFields.failedCount(data),
                    resultCount = (data?.get("items") as? JsonArray)?.size,
                    eligibleCount = null,
                    lifecycleFreeRejections = CallLogFields.lifecycleFreeRejections(body)
                )
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.debug("Call log record for '{}' was not built: {}", spec.name, e.message)
        }
    }

    companion object {
        /** Max length of the `actorId` MDC value. */
        private const val ACTOR_ID_MDC_MAX_LENGTH = 128

        /**
         * The exact SDK [Tool] registered for [spec] in [profile] (what `tools/list` returns): name, rendered
         * description, input schema and annotations. Null when no select/op of [spec] is visible in [profile].
         */
        fun toolFor(
            spec: ToolSpec,
            profile: ToolProfile
        ): Tool? {
            val schema = ToolSchemaRenderer.inputSchema(spec, profile) ?: return null
            return Tool(
                name = spec.name,
                inputSchema = ToolSchema(properties = schema["properties"] as JsonObject),
                description = ToolSchemaRenderer.description(spec, profile),
                annotations = ToolSchemaRenderer.annotations(spec, profile)
            )
        }

        /** UTF-8 bytes of the text content plus the structured body. */
        private fun responseBytes(
            result: CallToolResult,
            body: JsonObject
        ): Long =
            result.content.sumOf { (it as? TextContent)?.text?.let(CallLogFields::utf8Length) ?: 0L } +
                CallLogFields.utf8Length(body.toString())

        private fun ok(body: JsonObject): Boolean = (body["ok"] as? JsonPrimitive)?.contentOrNull == "true"

        /** Max failing entries kept in the JSON tail of a `partial_failure` text. */
        private const val TAIL_MAX_FAILED = 10

        /**
         * The text content: `ok`, or [summary] and a newline and the compact JSON tail, always the last line. The tail is
         * the envelope body; for `partial_failure` its `data.results` is cut to the failing entries (each `{index, ...entry}`,
         * ascending, first [TAIL_MAX_FAILED]) with `data.summary` unchanged. An `internal` error carries the tail too; its
         * message is already the fixed text, so no exception text reaches the client.
         */
        private fun text(body: JsonObject): String {
            val line = summary(body)
            if (ok(body)) return line
            return line + "\n" + tail(body).toString()
        }

        private fun tail(body: JsonObject): JsonObject {
            val error = body["error"] as? JsonObject
            val data = body["data"] as? JsonObject
            val results = data?.get("results") as? JsonArray
            if ((error?.get("code") as? JsonPrimitive)?.contentOrNull != "partial_failure" || results == null) return body
            val failed =
                results
                    .withIndex()
                    .filter { (_, entry) -> ((entry as? JsonObject)?.get("ok") as? JsonPrimitive)?.contentOrNull != "true" }
                    .take(TAIL_MAX_FAILED)
                    .map { (index, entry) ->
                        buildJsonObject {
                            put("index", index)
                            (entry as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                        }
                    }
            val newData = JsonObject(data + ("results" to JsonArray(failed)))
            return JsonObject(body + ("data" to newData))
        }

        /** `ok`, or `<code>: <message>` of the envelope error. */
        private fun summary(body: JsonObject): String {
            if (ok(body)) return "ok"
            val error = body["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: "internal"
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: EnvelopeEncoder.INTERNAL_MESSAGE
            return "$code: $message"
        }

        /** [access] with its principal's `host` set to the MCP client's `clientInfo.name`, when the client sent one. */
        private fun withClientHost(
            access: CallAccess,
            clientName: String?
        ): CallAccess {
            val host = HostName.normalize(clientName) ?: return access
            val callPrincipal = access.principal ?: return access
            val principal = callPrincipal.principal ?: return access
            return access.copy(principal = callPrincipal.copy(principal = principal.copy(host = host)))
        }
    }
}

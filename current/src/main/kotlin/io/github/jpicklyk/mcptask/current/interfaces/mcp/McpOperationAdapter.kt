package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.telemetry.CallLogFields
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.infrastructure.logging.MdcValues
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.server.Server
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
 * Registers 4.0 [ToolSpec] tools on the MCP SDK [Server] and serves their calls through the
 * transport-neutral [ToolDispatcher]. The old [McpToolAdapter] keeps serving the 3.x tools until Q32.
 *
 * Registration uses the SDK `Tool` from [toolFor]: name, rendered description, generated input schema
 * and MCP annotations. Per call, like [McpToolAdapter]: a fresh [ReqId], MDC correlation fields
 * (`transport`, `tool`, `sessionId`, `reqId`, a length-capped `actorId`), an [ActorVerificationScope] and
 * a [CallTelemetry] for the MCP surface. The result carries the envelope body as `structuredContent`,
 * `isError = !ok`, one text content (`ok`, or `<code>: <message>`) and `_meta.reqId`. One `call_log`
 * record is submitted per call that was not cancelled. No `notifications/message` is sent.
 */
class McpOperationAdapter(
    private val dispatcher: ToolDispatcher,
    private val callLog: CallLogSink = CallLogSink.NONE,
    private val clock: Clock = Clock.SYSTEM,
    /** The access of a call by MCP session. MCP stays unscoped until W4; the parity harness passes the REST principal's. */
    private val access: (sessionId: String?) -> CallAccess = { CallAccess.UNRESTRICTED }
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
            server.addTool(tool) { request -> handle(spec, profile, request.arguments, sessionId) }
            logger.debug("Registered 4.0 tool '{}' ({} profile)", spec.name, profile.wire)
        }
    }

    private suspend fun handle(
        spec: ToolSpec,
        profile: ToolProfile,
        rawArguments: JsonObject?,
        sessionId: String?
    ): CallToolResult {
        val arguments = rawArguments ?: JsonObject(emptyMap())
        val reqId = ReqId.generate()
        val startedAt = clock.now()
        val started = TimeSource.Monotonic.markNow()
        val telemetry = CallTelemetry(reqId, CallLogRecord.SURFACE_MCP, sessionId)
        val correlationFields =
            buildMap {
                put("transport", "mcp")
                put("tool", spec.name)
                sessionId?.let { put("sessionId", it) }
                put("reqId", reqId)
                actorIdFrom(arguments)?.let { put("actorId", MdcValues.bounded(it, max = ACTOR_ID_MDC_MAX_LENGTH)) }
            }
        // A cancellation propagates out of withContext and writes no record.
        val dispatched =
            withContext(
                MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + correlationFields) + ActorVerificationScope() + telemetry
            ) {
                try {
                    dispatcher.dispatch(spec, profile, arguments, reqId, CallLogRecord.SURFACE_MCP, access(sessionId))
                } catch (e: Exception) {
                    // The dispatcher translates escaping exceptions itself; this is a last-resort net.
                    e.rethrowIfCancellation()
                    logger.error("Tool '{}' (reqId {}) failed outside dispatch: {}", spec.name, reqId, e.message, e)
                    Dispatched(EnvelopeEncoder.internal(reqId), null, null)
                }
            }
        val body = dispatched.envelope.body
        val result =
            CallToolResult(
                content = listOf(TextContent(text = summary(body))),
                isError = !ok(body),
                structuredContent = body,
                meta = buildJsonObject { put("reqId", reqId) }
            )
        submitCallLog(spec, arguments, result, dispatched, telemetry, startedAt, started.elapsedNow().inWholeMilliseconds)
        return result
    }

    /** Builds and submits the `call_log` record; any failure is logged at DEBUG and swallowed. */
    private fun submitCallLog(
        spec: ToolSpec,
        arguments: JsonObject,
        result: CallToolResult,
        dispatched: Dispatched,
        telemetry: CallTelemetry,
        startedAt: Instant,
        latencyMs: Long
    ) {
        try {
            val body = dispatched.envelope.body
            val data = body["data"] as? JsonObject
            val responseBytes =
                result.content.sumOf { (it as? TextContent)?.text?.let(CallLogFields::utf8Length) ?: 0L } +
                    CallLogFields.utf8Length(body.toString())
            callLog.submit(
                CallLogFields.record(
                    telemetry = telemetry,
                    at = startedAt,
                    tool = spec.name,
                    operation = dispatched.operation,
                    targetIds = CallLogFields.targetIdsJson(arguments),
                    requestShape = CallLogFields.requestShapeJson(arguments, telemetry.searchShape),
                    isError = !ok(body),
                    errorCode = CallLogFields.errorCode(body),
                    latencyMs = latencyMs,
                    requestBytes = CallLogFields.utf8Length(arguments.toString()),
                    responseBytes = responseBytes,
                    batchSize = dispatched.batchSize,
                    failedCount = CallLogFields.failedCount(data),
                    resultCount = (data?.get("items") as? JsonArray)?.size,
                    eligibleCount = null
                )
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.debug("Call log record for '{}' was not built: {}", spec.name, e.message)
        }
    }

    companion object {
        /** Max length of the `actorId` MDC value, as in [McpToolAdapter]. */
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
                description = ToolSchemaRenderer.description(spec),
                annotations = ToolSchemaRenderer.annotations(spec, profile)
            )
        }

        private fun ok(body: JsonObject): Boolean = (body["ok"] as? JsonPrimitive)?.contentOrNull == "true"

        /** `ok`, or `<code>: <message>` of the envelope error. */
        private fun summary(body: JsonObject): String {
            if (ok(body)) return "ok"
            val error = body["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: "internal"
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: EnvelopeEncoder.INTERNAL_MESSAGE
            return "$code: $message"
        }

        /** The self-reported `actor.id` string for MDC correlation only, else null. */
        private fun actorIdFrom(arguments: JsonObject): String? {
            val actor = arguments["actor"] as? JsonObject ?: return null
            val id = actor["id"] as? JsonPrimitive ?: return null
            return if (id.isString) id.content else null
        }
    }
}

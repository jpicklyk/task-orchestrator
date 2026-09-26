package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.infrastructure.logging.MdcValues
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Adapter bridging v3 [ToolDefinition] instances to the MCP SDK [Server].
 *
 * Follows the Adapter pattern from Clean Architecture: the application layer defines
 * tool contracts ([ToolDefinition]), and this class adapts them to the MCP protocol
 * without leaking protocol details into business logic.
 *
 * Compared to the v2 adapter, this version:
 * - Uses simplified boolean preprocessing (no verbose debug logging per parameter)
 * - Returns clean single-line error messages (no multi-line recommendation blocks)
 * - Leverages v3 [ResponseUtil] for response envelope inspection
 */
class McpToolAdapter {
    private val logger = LoggerFactory.getLogger(McpToolAdapter::class.java)

    companion object {
        /**
         * Max length of the `actorId` MDC value (see [MdcValues.bounded]). Real ids are agent
         * names (~30 chars), UUIDs (36), or JWT `sub`/email values (typically under 64, 254 at the
         * RFC maximum) — 128 keeps every realistic id intact while bounding per-line log cost.
         */
        private const val ACTOR_ID_MDC_MAX_LENGTH = 128
    }

    /**
     * Registers a single tool with the MCP server.
     *
     * The registration wires up parameter preprocessing, validation, execution,
     * user summary generation, and structured content extraction into the MCP
     * tool handler lambda.
     *
     * @param server The MCP SDK server instance to register with
     * @param toolDefinition The v3 tool definition providing schema and execution logic
     * @param context The execution context giving tools access to repositories and services
     */
    fun registerToolWithServer(
        server: Server,
        toolDefinition: ToolDefinition,
        context: ToolExecutionContext
    ) {
        server.addTool(
            name = toolDefinition.name,
            description = toolDefinition.description,
            inputSchema = toolDefinition.parameterSchema
        ) { request ->
            // 'this' is ClientConnection — provides sessionId, createMessage, listRoots,
            // sendLoggingMessage, and other server-to-client capabilities from SDK 0.9.0.
            val clientConnection = this@addTool

            // MDC correlation fields for every log line emitted while this call is in flight
            // (WARN/ERROR below, plus any INFO the tool itself logs). Wrapped in MDCContext
            // because the handler suspends and may resume on a different thread — plain
            // MDC.put/clear would lose or leak values across that hop. actorId is the
            // self-reported, unverified `arguments.actor.id` (present only when it is a JSON
            // string) — omitted, not "null", when absent. The MDC copy is length-capped via
            // [MdcValues.bounded] (max 128) so an oversized caller-supplied id cannot blow up
            // per-line log cost; the tool itself still receives the full, uncapped `actor` value.
            val correlationFields =
                buildMap {
                    put("transport", "mcp")
                    put("tool", toolDefinition.name)
                    put("sessionId", clientConnection.sessionId)
                    put("requestId", UUID.randomUUID().toString())
                    actorIdFrom(request.arguments)?.let { put("actorId", MdcValues.bounded(it, max = ACTOR_ID_MDC_MAX_LENGTH)) }
                }
            // ActorVerificationScope: a fresh per-call memo so a proof re-verified multiple times
            // within this single MCP call (idempotency lookups, multi-transition batches) is only
            // ever checked once against an opt-in jti replay cache — see that class's KDoc.
            withContext(
                MDCContext((MDC.getCopyOfContextMap() ?: emptyMap()) + correlationFields) + ActorVerificationScope()
            ) {
                try {
                    val preprocessedParams =
                        preprocessParameters(
                            request.arguments ?: JsonObject(emptyMap()),
                            toolDefinition.parameterSchema
                        )

                    toolDefinition.validateParams(preprocessedParams)

                    // Execute the tool
                    val result = toolDefinition.execute(preprocessedParams, context)
                    val resultObj = result as? JsonObject

                    // Determine error state from response envelope
                    val isError = resultObj?.let { ResponseUtil.isErrorResponse(it) } ?: false

                    // Generate user-facing summary
                    val summary = toolDefinition.userSummary(preprocessedParams, result, isError)

                    // Extract structuredContent: on success, the raw data payload (strip envelope);
                    // on error, the structured error object ({code, message, kind?, retryAfterMs?,
                    // contendedItemId?}) plus any error data (e.g. gate-failure details) so clients
                    // can act on structured fields without a diagnostic round-trip.
                    val structuredData =
                        if (isError) {
                            resultObj?.let { ResponseUtil.extractErrorPayload(it) }
                        } else {
                            resultObj?.let { ResponseUtil.extractDataPayload(it) } as? JsonObject
                        }

                    // Response size telemetry: reuse the summary/structuredData strings already
                    // computed above (JsonElement.toString() is the same compact-JSON rendering the
                    // MCP SDK serializes for structuredContent — no extra serialization pass). Logs
                    // only the tool name, success/error, and a char count — never argument or
                    // response bodies — so this is safe at INFO on every call.
                    val responseChars = summary.length + (structuredData?.toString()?.length ?: 0)
                    logResponseSize(toolDefinition.name, success = !isError, responseChars = responseChars)

                    CallToolResult(
                        content = listOf(TextContent(text = summary)),
                        isError = isError,
                        structuredContent = structuredData
                    )
                } catch (e: ToolValidationException) {
                    // Fix for 4e110d22: validateParams() and execute() both throw
                    // ToolValidationException, and both map to the same validation envelope. Message
                    // text is preserved exactly as validateParams-phase failures always returned it
                    // (back-compat for existing text-matching clients/tests); structuredContent is now
                    // populated via the same ToolError -> envelope -> structured-payload pipeline the
                    // other dedicated catches below use, rather than being left null.
                    val message = "Validation error in '${toolDefinition.name}': ${e.message}"
                    logger.warn(message)
                    try {
                        clientConnection.sendLoggingMessage(
                            LoggingMessageNotification(
                                LoggingMessageNotificationParams(
                                    level = LoggingLevel.Warning,
                                    data = JsonPrimitive(message),
                                    logger = "mcp-task-orchestrator.tools"
                                )
                            )
                        )
                    } catch (_: Exception) {
                    }
                    logResponseSize(toolDefinition.name, success = false, responseChars = message.length)
                    val errorEnvelope =
                        ResponseUtil.createErrorResponse(
                            ToolError.permanent(code = ErrorCodes.VALIDATION_ERROR, message = message)
                        )
                    CallToolResult(
                        content = listOf(TextContent(text = message)),
                        isError = true,
                        structuredContent = ResponseUtil.extractErrorPayload(errorEnvelope)
                    )
                } catch (e: PerRootConfigUnavailableException) {
                    // D5: every tool other than advance_item/complete_tree (which handle this
                    // per-transition/per-item themselves and never let it reach this adapter) fails
                    // the WHOLE call through this dedicated catch — isError plus a structured
                    // {kind, code, message} error envelope, so a caller can apply its own backoff
                    // without parsing free text (no retryAfterMs, per the documented ErrorKind rule).
                    val message = "Per-root config unavailable in '${toolDefinition.name}': ${e.message}"
                    logger.warn(message)
                    try {
                        clientConnection.sendLoggingMessage(
                            LoggingMessageNotification(
                                LoggingMessageNotificationParams(
                                    level = LoggingLevel.Warning,
                                    data = JsonPrimitive(message),
                                    logger = "mcp-task-orchestrator.tools"
                                )
                            )
                        )
                    } catch (_: Exception) {
                    }
                    logResponseSize(toolDefinition.name, success = false, responseChars = message.length)
                    // Reuse the same ToolError -> envelope -> structured-payload pipeline normal tool
                    // failures use below, rather than hand-building the {kind, code, message} object —
                    // the wire shape (isError, structuredContent.error.{kind,code,message}, no
                    // retryAfterMs) stays identical.
                    val errorEnvelope =
                        ResponseUtil.createErrorResponse(
                            ToolError.transient(code = PerRootConfigUnavailableException.CODE, message = message)
                        )
                    CallToolResult(
                        content = listOf(TextContent(text = message)),
                        isError = true,
                        structuredContent = ResponseUtil.extractErrorPayload(errorEnvelope)
                    )
                } catch (e: Exception) {
                    val message = "Internal error in '${toolDefinition.name}' (session ${clientConnection.sessionId}): ${e.message}"
                    logger.error(message, e)
                    try {
                        clientConnection.sendLoggingMessage(
                            LoggingMessageNotification(
                                LoggingMessageNotificationParams(
                                    level = LoggingLevel.Error,
                                    data = JsonPrimitive(message),
                                    logger = "mcp-task-orchestrator.tools"
                                )
                            )
                        )
                    } catch (_: Exception) {
                    }
                    logResponseSize(toolDefinition.name, success = false, responseChars = message.length)
                    CallToolResult(
                        content = listOf(TextContent(text = message)),
                        isError = true
                    )
                }
            }
        }

        logger.debug("Registered tool '{}' with MCP server", toolDefinition.name)
    }

    /**
     * Registers multiple tools with the MCP server in batch.
     *
     * @param server The MCP SDK server instance to register with
     * @param tools Collection of v3 tool definitions to register
     * @param context The execution context giving tools access to repositories and services
     */
    fun registerToolsWithServer(
        server: Server,
        tools: Collection<ToolDefinition>,
        context: ToolExecutionContext
    ) {
        logger.info("Registering {} tools with MCP server", tools.size)
        tools.forEach { tool -> registerToolWithServer(server, tool, context) }
        logger.info("All {} tools registered with MCP server", tools.size)
    }

    /**
     * Extracts the top-level `arguments.actor.id` string for MDC correlation, when present.
     * This is a self-reported, unverified value — it is NOT the same as any actor-authentication
     * verification result — so callers must treat it as advisory. Returns null (never the string
     * "null") when `actor` is absent, `actor.id` is absent, or `actor.id` is not a JSON string.
     * The returned value is the FULL, uncapped id — the caller (this class's `registerToolWithServer`)
     * applies the [MdcValues.bounded] length cap only to the MDC copy, never to what tools receive.
     */
    internal fun actorIdFrom(arguments: JsonElement?): String? {
        val actor = (arguments as? JsonObject)?.get("actor") as? JsonObject ?: return null
        val id = actor["id"] as? JsonPrimitive ?: return null
        return if (id.isString) id.content else null
    }

    /**
     * Logs one INFO line of response-size telemetry per tool call: tool name, success/error,
     * and the char count of what the client actually receives (text content + serialized
     * structuredContent, when present). Deliberately excludes argument and response bodies —
     * this is a token-budget signal for spotting regressions in aggregate/log tooling, not a
     * diagnostic dump.
     */
    private fun logResponseSize(
        toolName: String,
        success: Boolean,
        responseChars: Int
    ) {
        logger.info("tool call: name={}, success={}, responseChars={}", toolName, success, responseChars)
    }

    /**
     * Normalizes string boolean values to actual JSON boolean primitives.
     *
     * Some MCP clients send boolean parameters as strings ("true"/"false").
     * This preprocessing step converts them to proper JSON booleans so that
     * tool validation and execution logic can rely on consistent types —
     * but ONLY for parameters whose declared schema type is boolean. A
     * string-typed (or untyped/unknown) parameter that happens to equal the
     * literal "true"/"false" (e.g. a search query) must pass through
     * unchanged; retyping it would break `requireString` validation for a
     * perfectly legitimate string value. Only top-level parameters are
     * considered — nested object/array values are left untouched.
     */
    internal fun preprocessParameters(
        params: JsonElement,
        schema: ToolSchema?
    ): JsonElement {
        val paramsObj = params as? JsonObject ?: return params
        val properties = schema?.properties
        val processed = mutableMapOf<String, JsonElement>()
        paramsObj.forEach { (key, value) ->
            processed[key] =
                if (value is JsonPrimitive && value.isString && isBooleanTypedParam(properties, key)) {
                    when (value.content.lowercase()) {
                        "true" -> JsonPrimitive(true)
                        "false" -> JsonPrimitive(false)
                        else -> value
                    }
                } else {
                    value
                }
        }
        return JsonObject(processed)
    }

    /**
     * Returns true only when [key]'s entry in the tool's declared parameter
     * schema exists and declares `"type": "boolean"`. Absent properties,
     * properties without a "type" field, and non-boolean types all return
     * false so string-to-boolean coercion is never applied to them.
     */
    private fun isBooleanTypedParam(
        properties: JsonObject?,
        key: String
    ): Boolean {
        val propertySchema = properties?.get(key) as? JsonObject ?: return false
        val type = propertySchema["type"] as? JsonPrimitive ?: return false
        return type.isString && type.content == "boolean"
    }
}

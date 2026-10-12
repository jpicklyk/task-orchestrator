package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemsReadTool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A call to `items_read` select `context` (or `next` / `blocked`) through the production [buildToolDispatcher] and tool
 * registry, for tests that used the removed 3.x `get_context` / `get_next_item` / `get_blocked_items` as a probe of the
 * state under test (Q22). Never a production shim: it adds nothing the dispatcher does not do.
 *
 * - [envelope] answers the 4.0 envelope body (`ok`, `data` or `error`).
 * - [execute] wraps it in the 3.x test shape `{success: true, data}` / `{success: false, error: {code, message}}`, so a
 *   probe-style assertion on `data.gateStatus`, `data.schema`, ... reads the same way it did.
 */
class ItemContextProbe(
    val select: String = "context"
) {
    private val registry = ToolRegistry(buildToolSpecs())

    /** The envelope body of `items_read` with [args] and `select` set; the access is unrestricted unless [access] says otherwise. */
    suspend fun envelope(
        context: ToolExecutionContext,
        args: JsonObject,
        access: CallAccess = CallAccess.UNRESTRICTED
    ): JsonObject {
        val spec = checkNotNull(registry[ItemsReadTool.NAME]) { "items_read is not registered" }
        val arguments = JsonObject(args + ("select" to JsonPrimitive(select)))
        return buildToolDispatcher(context)
            .dispatch(spec, ToolProfile.ORCHESTRATION, arguments, "probe-req", CallLogRecord.SURFACE_MCP, access)
            .envelope.body
    }

    /** [envelope] in the 3.x test shape. */
    suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val body = envelope(context, params as JsonObject)
        val ok = (body["ok"] as? JsonPrimitive)?.content == "true"
        return buildJsonObject {
            put("success", ok)
            if (ok) body["data"]?.let { put("data", it) } else body["error"]?.let { put("error", it) }
        }
    }

    /**
     * This probe as a 3.x [ToolDefinition] named [toolName], for the adapter-level tests (host capture, transport, call log)
     * that need a registered read tool whose result carries an item title: the removed 3.x `get_context`, driven through the
     * production select. Test-only; never registered by production.
     */
    fun asLegacyTool(toolName: String): ToolDefinition {
        val probe = this
        return object : ToolDefinition {
            override val name = toolName
            override val description = "Test driver: items_read context as a 3.x tool."
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.WORKFLOW

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = probe.execute(params, context)
        }
    }
}

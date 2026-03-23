package io.github.jpicklyk.mcptask.current.infrastructure.logging

import io.github.jpicklyk.mcptask.current.application.service.McpLogLevel
import io.github.jpicklyk.mcptask.current.application.service.McpLoggingService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Infrastructure-layer implementation of [McpLoggingService].
 *
 * Sends `notifications/message` to all currently connected MCP clients via the SDK's
 * `sendLoggingMessage` API. Exceptions during delivery are swallowed — logging must
 * never break the server.
 *
 * Call [bindServer] after the [Server] instance is created, before clients connect.
 */
class DefaultMcpLoggingService : McpLoggingService {
    @Volatile
    private var server: Server? = null

    /**
     * Binds this service to an MCP [Server] instance.
     * Must be called before the server starts accepting connections.
     */
    fun bindServer(server: Server) {
        this.server = server
    }

    override suspend fun log(
        level: McpLogLevel,
        logger: String,
        message: String,
        data: Map<String, String>?
    ) {
        val srv = server ?: return
        val sdkLevel = level.toSdkLevel()
        val jsonData =
            if (data != null) {
                JsonObject(
                    mapOf("message" to JsonPrimitive(message)) +
                        data.mapValues { JsonPrimitive(it.value) }
                )
            } else {
                JsonPrimitive(message)
            }
        val notification =
            LoggingMessageNotification(
                LoggingMessageNotificationParams(
                    level = sdkLevel,
                    data = jsonData,
                    logger = logger
                )
            )
        srv.sessions.forEach { (sessionId, _) ->
            try {
                srv.sendLoggingMessage(sessionId, notification)
            } catch (_: Exception) {
                // Logging must never break the server
            }
        }
    }

    override suspend fun debug(
        logger: String,
        message: String,
        data: Map<String, String>?
    ) = log(McpLogLevel.DEBUG, logger, message, data)

    override suspend fun info(
        logger: String,
        message: String,
        data: Map<String, String>?
    ) = log(McpLogLevel.INFO, logger, message, data)

    override suspend fun warning(
        logger: String,
        message: String,
        data: Map<String, String>?
    ) = log(McpLogLevel.WARNING, logger, message, data)

    override suspend fun error(
        logger: String,
        message: String,
        data: Map<String, String>?
    ) = log(McpLogLevel.ERROR, logger, message, data)
}

/**
 * Maps [McpLogLevel] to the SDK's [LoggingLevel] enum.
 */
fun McpLogLevel.toSdkLevel(): LoggingLevel =
    when (this) {
        McpLogLevel.DEBUG -> LoggingLevel.Debug
        McpLogLevel.INFO -> LoggingLevel.Info
        McpLogLevel.NOTICE -> LoggingLevel.Notice
        McpLogLevel.WARNING -> LoggingLevel.Warning
        McpLogLevel.ERROR -> LoggingLevel.Error
        McpLogLevel.CRITICAL -> LoggingLevel.Critical
        McpLogLevel.ALERT -> LoggingLevel.Alert
        McpLogLevel.EMERGENCY -> LoggingLevel.Emergency
    }

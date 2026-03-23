package io.github.jpicklyk.mcptask.current.application.service

/**
 * Application-layer interface for MCP protocol-level logging.
 *
 * Implementations send `notifications/message` to connected MCP clients via the SDK's
 * `sendLoggingMessage` API, following RFC 5424 syslog severity levels.
 *
 * The [NoOpMcpLoggingService] singleton is used when no server is wired up (e.g., in tests).
 */
interface McpLoggingService {
    suspend fun log(level: McpLogLevel, logger: String, message: String, data: Map<String, String>? = null)
    suspend fun debug(logger: String, message: String, data: Map<String, String>? = null)
    suspend fun info(logger: String, message: String, data: Map<String, String>? = null)
    suspend fun warning(logger: String, message: String, data: Map<String, String>? = null)
    suspend fun error(logger: String, message: String, data: Map<String, String>? = null)
}

/**
 * RFC 5424 syslog severity levels mirrored from the MCP SDK's [LoggingLevel] enum.
 */
enum class McpLogLevel {
    DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY
}

/**
 * No-op implementation — all methods are empty bodies.
 * Used as the default when no MCP server is available (e.g., unit tests, CLI mode).
 */
object NoOpMcpLoggingService : McpLoggingService {
    override suspend fun log(level: McpLogLevel, logger: String, message: String, data: Map<String, String>?) = Unit
    override suspend fun debug(logger: String, message: String, data: Map<String, String>?) = Unit
    override suspend fun info(logger: String, message: String, data: Map<String, String>?) = Unit
    override suspend fun warning(logger: String, message: String, data: Map<String, String>?) = Unit
    override suspend fun error(logger: String, message: String, data: Map<String, String>?) = Unit
}

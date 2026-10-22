package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord

/** The one place a [CallTelemetry] is built, so every transport derives `host` the same way. Takes plain strings. */
object CallTelemetryFactory {
    /** MCP call: host is the normalized `clientInfo.name` of the session, null when unknown or blank. */
    fun mcp(
        reqId: String,
        sessionId: String?,
        clientName: String?
    ): CallTelemetry = CallTelemetry(reqId, CallLogRecord.SURFACE_MCP, sessionId, HostName.normalize(clientName))

    /** REST request: host from `X-Client-Host`, else the `User-Agent` product, else `rest`. */
    fun rest(
        reqId: String,
        clientHostHeader: String?,
        userAgent: String?
    ): CallTelemetry = CallTelemetry(reqId, CallLogRecord.SURFACE_REST, null, HostName.forRest(clientHostHeader, userAgent))
}

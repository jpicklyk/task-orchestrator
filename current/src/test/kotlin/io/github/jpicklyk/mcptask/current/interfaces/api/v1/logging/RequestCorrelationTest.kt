package io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * REST-path MDC correlation coverage for AR-78 / item b5081c9b (S4, S8): [installRequestCorrelation]
 * wraps requests under `/api/v1` in an MDC scope carrying `transport`, `requestId`, `httpMethod`, and
 * `httpPath`, mirroring the MCP-path correlation in
 * [io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter].
 */
class RequestCorrelationTest {
    private val probeLoggerName = "request.correlation.probe"
    private val uuidPattern =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private class MdcSnapshotAppender : AppenderBase<ILoggingEvent>() {
        val snapshots = mutableListOf<Map<String, String>>()

        override fun append(eventObject: ILoggingEvent) {
            snapshots.add(eventObject.getMDCPropertyMap() ?: emptyMap())
        }
    }

    private fun captureProbeSnapshots(block: () -> Unit): List<Map<String, String>> {
        val logbackLogger = LoggerFactory.getLogger(probeLoggerName) as Logger
        val appender =
            MdcSnapshotAppender().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.ALL
        try {
            block()
            return appender.snapshots.toList()
        } finally {
            logbackLogger.detachAppender(appender)
            logbackLogger.level = savedLevel
        }
    }

    @Test
    fun `S4 - an inbound X-Request-Id is used verbatim alongside transport, method and path`() {
        val snapshots =
            captureProbeSnapshots {
                testApplication {
                    application {
                        installRequestCorrelation()
                        routing {
                            route("/api/v1") {
                                get("/probe") {
                                    LoggerFactory.getLogger(probeLoggerName).info("probe hit")
                                    call.respondText("ok")
                                }
                            }
                        }
                    }
                    client.get("/api/v1/probe") { header("X-Request-Id", "abc-123") }
                }
            }

        assertEquals(1, snapshots.size)
        val mdc = snapshots.single()
        assertEquals("rest", mdc["transport"])
        assertEquals("abc-123", mdc["requestId"])
        assertEquals("GET", mdc["httpMethod"])
        assertEquals("/api/v1/probe", mdc["httpPath"])
    }

    @Test
    fun `S8 - a malformed or too-long X-Request-Id is replaced with a generated UUID`() {
        val tooLong = "a".repeat(65)
        val badChars = "bad value {not-safe}"

        listOf(tooLong, badChars).forEach { badValue ->
            val snapshots =
                captureProbeSnapshots {
                    testApplication {
                        application {
                            installRequestCorrelation()
                            routing {
                                route("/api/v1") {
                                    get("/probe") {
                                        LoggerFactory.getLogger(probeLoggerName).info("probe hit")
                                        call.respondText("ok")
                                    }
                                }
                            }
                        }
                        client.get("/api/v1/probe") { header("X-Request-Id", badValue) }
                    }
                }

            assertEquals(1, snapshots.size)
            val requestId = snapshots.single()["requestId"]
            assertTrue(requestId != null && uuidPattern.matcher(requestId).matches(), "Expected a generated UUID, got: $requestId")
            assertFalse(requestId == badValue)
        }
    }

    @Test
    fun `paths outside api v1 are not tagged with rest MDC fields`() {
        val snapshots =
            captureProbeSnapshots {
                testApplication {
                    application {
                        installRequestCorrelation()
                        routing {
                            get("/mcp/probe") {
                                LoggerFactory.getLogger(probeLoggerName).info("probe hit")
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/mcp/probe")
                }
            }

        assertEquals(1, snapshots.size)
        assertFalse(snapshots.single().containsKey("transport"), "Non-/api/v1 paths must not get REST MDC fields")
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.logging

import io.github.jpicklyk.mcptask.current.application.service.McpLogLevel
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DefaultMcpLoggingServiceTest {

    private lateinit var service: DefaultMcpLoggingService
    private lateinit var server: Server

    @BeforeEach
    fun setUp() {
        service = DefaultMcpLoggingService()
        server = mockk(relaxed = true)
    }

    // ─────────────────────────────────────────────
    // No-op when no server bound
    // ─────────────────────────────────────────────

    @Test
    fun `log does not throw when no server is bound`(): Unit = runBlocking {
        // Should complete without exception — server is null
        service.log(McpLogLevel.INFO, "test.logger", "hello")
    }

    @Test
    fun `convenience methods do not throw when no server is bound`(): Unit = runBlocking {
        service.debug("test", "debug message")
        service.info("test", "info message")
        service.warning("test", "warning message")
        service.error("test", "error message")
    }

    // ─────────────────────────────────────────────
    // Empty sessions map — no send attempted
    // ─────────────────────────────────────────────

    @Test
    fun `log is a no-op when sessions map is empty`(): Unit = runBlocking {
        every { server.sessions } returns emptyMap()
        service.bindServer(server)

        service.log(McpLogLevel.INFO, "test.logger", "hello")

        coVerify(exactly = 0) { server.sendLoggingMessage(any(), any()) }
    }

    // ─────────────────────────────────────────────
    // Notification delivery with correct levels
    // ─────────────────────────────────────────────

    @Test
    fun `log sends notification with correct LoggingLevel to each session`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("session-1" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.log(McpLogLevel.WARNING, "mcp-task-orchestrator.server", "test warning")

        val slot = slot<LoggingMessageNotification>()
        coVerify(exactly = 1) { server.sendLoggingMessage("session-1", capture(slot)) }
        assertEquals(LoggingLevel.Warning, slot.captured.params.level)
        assertEquals("mcp-task-orchestrator.server", slot.captured.params.logger)
    }

    @Test
    fun `log sends to all connected sessions`(): Unit = runBlocking {
        val session1 = mockk<ServerSession>(relaxed = true)
        val session2 = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf(
            "session-a" to session1,
            "session-b" to session2
        )
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.log(McpLogLevel.INFO, "test.logger", "broadcast")

        coVerify(exactly = 1) { server.sendLoggingMessage("session-a", any()) }
        coVerify(exactly = 1) { server.sendLoggingMessage("session-b", any()) }
    }

    // ─────────────────────────────────────────────
    // Exception swallowing
    // ─────────────────────────────────────────────

    @Test
    fun `exceptions from sendLoggingMessage are swallowed`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("session-1" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } throws RuntimeException("network error")
        service.bindServer(server)

        // Must not propagate exception
        service.log(McpLogLevel.ERROR, "test.logger", "should not throw")
    }

    @Test
    fun `exception in one session does not prevent delivery to other sessions`(): Unit = runBlocking {
        val session1 = mockk<ServerSession>(relaxed = true)
        val session2 = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf(
            "session-bad" to session1,
            "session-good" to session2
        )
        coEvery { server.sendLoggingMessage("session-bad", any()) } throws RuntimeException("failed")
        coEvery { server.sendLoggingMessage("session-good", any()) } returns Unit
        service.bindServer(server)

        service.log(McpLogLevel.INFO, "test.logger", "partial delivery")

        coVerify(exactly = 1) { server.sendLoggingMessage("session-good", any()) }
    }

    // ─────────────────────────────────────────────
    // Convenience methods map to correct SDK levels
    // ─────────────────────────────────────────────

    @Test
    fun `debug convenience method sends LoggingLevel Debug`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("s" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.debug("logger", "debug msg")

        val slot = slot<LoggingMessageNotification>()
        coVerify { server.sendLoggingMessage("s", capture(slot)) }
        assertEquals(LoggingLevel.Debug, slot.captured.params.level)
    }

    @Test
    fun `info convenience method sends LoggingLevel Info`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("s" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.info("logger", "info msg")

        val slot = slot<LoggingMessageNotification>()
        coVerify { server.sendLoggingMessage("s", capture(slot)) }
        assertEquals(LoggingLevel.Info, slot.captured.params.level)
    }

    @Test
    fun `warning convenience method sends LoggingLevel Warning`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("s" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.warning("logger", "warning msg")

        val slot = slot<LoggingMessageNotification>()
        coVerify { server.sendLoggingMessage("s", capture(slot)) }
        assertEquals(LoggingLevel.Warning, slot.captured.params.level)
    }

    @Test
    fun `error convenience method sends LoggingLevel Error`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("s" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.error("logger", "error msg")

        val slot = slot<LoggingMessageNotification>()
        coVerify { server.sendLoggingMessage("s", capture(slot)) }
        assertEquals(LoggingLevel.Error, slot.captured.params.level)
    }

    // ─────────────────────────────────────────────
    // toSdkLevel() mapping
    // ─────────────────────────────────────────────

    @Test
    fun `toSdkLevel maps all McpLogLevel values correctly`(): Unit {
        assertEquals(LoggingLevel.Debug, McpLogLevel.DEBUG.toSdkLevel())
        assertEquals(LoggingLevel.Info, McpLogLevel.INFO.toSdkLevel())
        assertEquals(LoggingLevel.Notice, McpLogLevel.NOTICE.toSdkLevel())
        assertEquals(LoggingLevel.Warning, McpLogLevel.WARNING.toSdkLevel())
        assertEquals(LoggingLevel.Error, McpLogLevel.ERROR.toSdkLevel())
        assertEquals(LoggingLevel.Critical, McpLogLevel.CRITICAL.toSdkLevel())
        assertEquals(LoggingLevel.Alert, McpLogLevel.ALERT.toSdkLevel())
        assertEquals(LoggingLevel.Emergency, McpLogLevel.EMERGENCY.toSdkLevel())
    }

    // ─────────────────────────────────────────────
    // Extra data map is included in notification
    // ─────────────────────────────────────────────

    @Test
    fun `log with data map includes data in notification payload`(): Unit = runBlocking {
        val session = mockk<ServerSession>(relaxed = true)
        every { server.sessions } returns mapOf("s" to session)
        coEvery { server.sendLoggingMessage(any(), any()) } returns Unit
        service.bindServer(server)

        service.log(
            McpLogLevel.INFO,
            "test.logger",
            "with data",
            mapOf("key1" to "value1", "key2" to "value2")
        )

        val slot = slot<LoggingMessageNotification>()
        coVerify { server.sendLoggingMessage("s", capture(slot)) }
        val data = slot.captured.params.data.toString()
        // JsonObject should contain both the message and the extra key-value pairs
        assert(data.contains("with data")) { "Expected message in data, got: $data" }
        assert(data.contains("key1")) { "Expected key1 in data, got: $data" }
        assert(data.contains("value1")) { "Expected value1 in data, got: $data" }
    }
}

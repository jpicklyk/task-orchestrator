package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import org.junit.jupiter.api.Test
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S7 (AR-78 / item b5081c9b): [LogFileAppenderInstaller] attaches a RollingFileAppender to the
 * root logger, using the same JSON encoding as stderr, only when `LOG_FILE` is non-blank — see
 * that class's KDoc for why this replaced the originally-planned `logback.xml`-only conditional.
 */
class LogFileAppenderInstallerTest {
    private fun rootLogger(): Logger {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        return loggerContext.getLogger(ROOT_LOGGER_NAME) as Logger
    }

    /** Stops (releases the open file handle) and detaches the installed appender, if any. */
    private fun removeInstalledAppender() {
        val root = rootLogger()
        root.getAppender(LogFileAppenderInstaller.APPENDER_NAME)?.stop()
        root.detachAppender(LogFileAppenderInstaller.APPENDER_NAME)
    }

    /**
     * Captures WARN-level log records emitted by [LogFileAppenderInstaller] during [block] --
     * same [ch.qos.logback.core.read.ListAppender] convention used by
     * `RejectedProofWarnLogTest.captureWarnLogs`, `EnvBooleanTest.captureWarnLogs` and
     * `DidDocumentJwksExtractorTest`.
     */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logbackLogger =
            LoggerFactory.getLogger(LogFileAppenderInstaller::class.java) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN
        try {
            block()
            return listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    @Test
    fun `null or blank LOG_FILE is a no-op`() {
        try {
            LogFileAppenderInstaller.installIfConfigured(envLogFile = null)
            assertNull(rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME))

            LogFileAppenderInstaller.installIfConfigured(envLogFile = "   ")
            assertNull(rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME))
        } finally {
            removeInstalledAppender()
        }
    }

    @Test
    fun `S7 - a set LOG_FILE attaches a RollingFileAppender at that path that writes one JSON line`() {
        val tempFile = Files.createTempFile("ar78-logfile-installer-test", ".log")
        Files.deleteIfExists(tempFile)
        val path = tempFile.toAbsolutePath().toString()

        try {
            LogFileAppenderInstaller.installIfConfigured(envLogFile = path)

            val appender = rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME)
            assertTrue(appender is RollingFileAppender<*>, "Expected a RollingFileAppender named FILE")
            assertEquals(path, (appender as RollingFileAppender<*>).file)

            val probeLogger = LoggerFactory.getLogger("ar78.test.log-file-installer")
            probeLogger.info("s7 probe line")

            val fileContents = Files.readString(tempFile).trim()
            assertTrue(fileContents.isNotBlank(), "Expected the log file to contain at least one line")
            val lines = fileContents.lines()
            assertEquals(1, lines.size, "Expected exactly one JSON line for the one INFO call")
            assertTrue(lines[0].contains("\"level\":\"INFO\""), "Expected an INFO JSON line: ${lines[0]}")
            assertTrue(lines[0].contains("s7 probe line"))
        } finally {
            removeInstalledAppender()
            Files.deleteIfExists(tempFile)
            Files.newDirectoryStream(tempFile.parent, "${tempFile.fileName}.*").use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `installing twice is idempotent (second call is a no-op)`() {
        val tempFile = Files.createTempFile("ar78-logfile-installer-idempotent-test", ".log")
        Files.deleteIfExists(tempFile)
        val path = tempFile.toAbsolutePath().toString()

        try {
            LogFileAppenderInstaller.installIfConfigured(envLogFile = path)
            val firstAppender = rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME)
            LogFileAppenderInstaller.installIfConfigured(envLogFile = path)
            val secondAppender = rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME)

            assertTrue(firstAppender === secondAppender, "A second call must not replace the already-attached appender")
        } finally {
            removeInstalledAppender()
            Files.deleteIfExists(tempFile)
            Files.newDirectoryStream(tempFile.parent, "${tempFile.fileName}.*").use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * O4 (review of b5081c9b): an unwritable `LOG_FILE` must not crash startup and must not
     * silently attach a dead appender. The path here is an existing directory, so
     * `RollingFileAppender.start()` fails to open it as a file and never flips `isStarted`.
     */
    @Test
    fun `O4 - an unwritable LOG_FILE path emits one WARN and leaves no FILE appender attached`() {
        val unwritableDir = Files.createTempDirectory("ar78-logfile-installer-unwritable-test")
        val path = unwritableDir.toAbsolutePath().toString()

        try {
            val warnings = captureWarnLogs { LogFileAppenderInstaller.installIfConfigured(envLogFile = path) }

            assertNull(
                rootLogger().getAppender(LogFileAppenderInstaller.APPENDER_NAME),
                "A failed-to-start appender must not be attached to root",
            )
            assertEquals(1, warnings.size, "Exactly one WARN must be logged for an unwritable LOG_FILE path")
            val message = warnings.single()
            assertTrue(message.contains(path), "WARN must name the offending path: $message")
        } finally {
            removeInstalledAppender()
            Files.deleteIfExists(unwritableDir)
        }
    }
}

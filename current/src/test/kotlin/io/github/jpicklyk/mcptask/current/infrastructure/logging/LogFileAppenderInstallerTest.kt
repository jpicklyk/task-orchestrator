package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
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
}

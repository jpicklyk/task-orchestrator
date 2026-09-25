package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.rolling.RollingFileAppender
import org.junit.jupiter.api.Test
import org.slf4j.Logger.ROOT_LOGGER_NAME
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loads the real `logback.xml` (from the main resources classpath) into a FRESH [LoggerContext],
 * independent of whatever context the test JVM's own logging already uses — the stdio-safety
 * invariant from AR-78 / item b5081c9b's task-scope §7 (S6).
 *
 * The opt-in file appender (S7) is no longer part of `logback.xml` — see
 * [LogFileAppenderInstaller]'s KDoc for why it is attached programmatically instead, and
 * [LogFileAppenderInstallerTest] for its coverage.
 *
 * S6 is the critical regression guard for risk flag #1 (stdout contamination): any future edit
 * that points an appender at `System.out`, or reintroduces a level filter above INFO on stderr,
 * fails this test — see the "Prove the check" requirement in the task-scope's §9 Verification.
 */
class LogbackConfigTest {
    private fun freshContext(): LoggerContext {
        val context = LoggerContext()
        val configurator = JoranConfigurator()
        configurator.context = context
        val url =
            LogbackConfigTest::class.java.classLoader.getResource("logback.xml")
                ?: error("logback.xml not found on the test classpath")
        configurator.doConfigure(url)
        return context
    }

    private fun rootAppenders(context: LoggerContext): List<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> {
        val root = context.getLogger(ROOT_LOGGER_NAME) as Logger
        return root.iteratorForAppenders().asSequence().toList()
    }

    @Test
    fun `S6 - every console appender targets stderr, has no above-INFO filter, and no file appender exists by default`() {
        val context = freshContext()
        val appenders = rootAppenders(context)

        val consoleAppenders = appenders.filterIsInstance<ConsoleAppender<*>>()
        assertTrue(consoleAppenders.isNotEmpty(), "Expected at least one ConsoleAppender")
        consoleAppenders.forEach { appender ->
            assertEquals("System.err", appender.target, "Console appender must target stderr")
            @Suppress("UNCHECKED_CAST")
            val filters = appender.copyOfAttachedFiltersList as List<Filter<ch.qos.logback.classic.spi.ILoggingEvent>>
            assertTrue(filters.isEmpty(), "No ThresholdFilter (or any filter) may sit above the root level on stderr")
        }

        val fileAppenders = appenders.filterIsInstance<RollingFileAppender<*>>()
        assertTrue(fileAppenders.isEmpty(), "No file appender is configured by default in logback.xml")

        context.stop()
    }

    @Test
    fun `S6 - root level defaults to INFO`() {
        val context = freshContext()
        val root = context.getLogger(ROOT_LOGGER_NAME) as Logger
        assertEquals(Level.INFO, root.level)
        context.stop()
    }

    @Test
    fun `no appender anywhere targets System out`() {
        val context = freshContext()
        val consoleAppenders = rootAppenders(context).filterIsInstance<ConsoleAppender<*>>()
        assertFalse(
            consoleAppenders.any { it.target == "System.out" },
            "No appender may target stdout — it must stay clean for MCP JSON-RPC"
        )
        context.stop()
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * Attaches an opt-in [RollingFileAppender] to the root logger when the `LOG_FILE` env var is set —
 * AR-78 / item b5081c9b §1A's documented fallback, used INSTEAD of the originally-planned
 * `logback.xml`-only Janino-free `<if>` conditional.
 *
 * **Why programmatic, not XML `<if>`:** logback-core 1.5.32's `byProperties` conditional
 * (`<if><condition class="...IsPropertyDefinedCondition">`) does evaluate correctly when the
 * `<condition>` is a DIRECT child of `<if>`, but `IfModelHandler`'s branch-state consumption only
 * fires on a SECOND visit to the `<if>` model that this logback version's tree processor never
 * performs for a plain top-level `<if>` — every attempt (including the documented
 * `<param name="key" value="LOG_FILE"/>` form) left the FILE appender undefined even with the
 * property set, confirmed by tracing `IfModelHandler`/`ByPropertiesConditionModelHandler` bytecode
 * and Joran's own STATUS log ("Appender named [FILE] could not be found"). Attaching the appender
 * in code sidesteps that Joran quirk entirely and is trivially testable.
 *
 * Called once, as the first statement in `CurrentMain.main()`, before any other logging occurs
 * (best-effort: SLF4J may already be bound by then, but nothing has logged yet).
 */
object LogFileAppenderInstaller {
    const val LOG_FILE_ENV = "LOG_FILE"
    const val APPENDER_NAME = "FILE"

    /**
     * Reads [envLogFile] (defaults to the real `LOG_FILE` environment variable) and, when it is
     * non-blank, builds and attaches a [RollingFileAppender] named [APPENDER_NAME] to the root
     * logger — same JSON encoding ([Iso8601JsonEncoder]) and rolling policy (10MB/day, 30-day /
     * 100MB retention, `.gz`) as the `logback.xml`-configured `STDERR` appender. A blank/unset
     * value is a no-op — no file logging by default. Idempotent: does nothing if an appender named
     * [APPENDER_NAME] is already attached to root (guards against being called twice).
     */
    fun installIfConfigured(envLogFile: String? = System.getenv(LOG_FILE_ENV)) {
        val path = envLogFile?.takeIf { it.isNotBlank() } ?: return

        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = loggerContext.getLogger(ROOT_LOGGER_NAME) as Logger
        if (root.getAppender(APPENDER_NAME) != null) return

        val encoder = Iso8601JsonEncoder()
        encoder.context = loggerContext
        encoder.start()

        val rollingPolicy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>()
        rollingPolicy.context = loggerContext
        rollingPolicy.fileNamePattern = "$path.%d{yyyy-MM-dd}.%i.gz"
        rollingPolicy.setMaxFileSize(FileSize.valueOf("10MB"))
        rollingPolicy.maxHistory = 30
        rollingPolicy.setTotalSizeCap(FileSize.valueOf("100MB"))

        val appender = RollingFileAppender<ILoggingEvent>()
        appender.context = loggerContext
        appender.name = APPENDER_NAME
        appender.file = path
        appender.encoder = encoder
        appender.rollingPolicy = rollingPolicy
        rollingPolicy.setParent(appender)

        rollingPolicy.start()
        appender.start()

        root.addAppender(appender)
    }
}

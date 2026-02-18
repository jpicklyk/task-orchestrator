@file:Suppress("unused")

package io.github.jpicklyk.mcptask.infrastructure.logging

import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for logging functionality.
 * All logging is now directed to standard output via SLF4J for Docker compatibility.
 */
object LoggingUtils {
    private val logger = LoggerFactory.getLogger(LoggingUtils::class.java)

    /**
     * Creates a timestamp string in the format yyyyMMdd-HHmmss.
     * @return The current timestamp as a string
     */
    fun createTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    }

    /**
     * Creates a formatted log message with a timestamp.
     * @param level The log level (INFO, DEBUG, ERROR, etc.)
     * @param message The log message
     * @return A formatted log message
     */
    fun formatLogMessage(level: String, message: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date())
        return "[$timestamp] $level: $message"
    }

    /**
     * Logs a message at DEBUG level.
     * @param message The message to log
     */
    fun debug(message: String) {
        logger.debug(message)
    }

    /**
     * Logs a message at INFO level.
     * @param message The message to log
     */
    fun info(message: String) {
        logger.info(message)
    }

    /**
     * Logs a message at WARN level.
     * @param message The message to log
     */
    fun warn(message: String) {
        logger.warn(message)
    }

    /**
     * Logs a message at ERROR level.
     * @param message The message to log
     * @param throwable The exception to log (optional)
     */
    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            logger.error(message, throwable)
        } else {
            logger.error(message)
        }
    }
}
package io.github.jpicklyk.mcptask.current.application

import java.io.InputStream
import java.util.Properties

/**
 * Reads the application's build-generated version once, at class-load time.
 *
 * The build (`current/build.gradle.kts` -> `generateBuildInfo`) writes
 * `build-info/version.properties` (a `version=<value>` line derived from the root
 * `version.properties`) into the main resources so it lands on the runtime classpath.
 *
 * Used by both the process entry point (log line, `/api/v1/info`, well-known) and
 * [io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil] (envelope `metadata.version`),
 * so both observers agree on the real build version instead of a separately hard-coded constant.
 */
object BuildInfo {
    private const val RESOURCE_PATH = "/build-info/version.properties"
    private const val FALLBACK_VERSION = "unknown"

    val version: String by lazy { loadVersion(javaClass.getResourceAsStream(RESOURCE_PATH)) }

    /**
     * Reads the `version` property from an already-open stream, or falls back to "unknown" when
     * the resource is missing (e.g. running from the IDE without a build). Pure and side-effect
     * free apart from closing the stream, so tests can exercise both branches directly without
     * manipulating the classpath.
     */
    internal fun loadVersion(stream: InputStream?): String {
        if (stream == null) return FALLBACK_VERSION
        val props = Properties()
        stream.use { props.load(it) }
        return props.getProperty("version", FALLBACK_VERSION)
    }
}

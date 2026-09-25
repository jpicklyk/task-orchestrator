package io.github.jpicklyk.mcptask.current.infrastructure.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration

/**
 * Standalone `main` entry point launched in a forked child JVM by
 * [KotlinLoggingBannerGuardTest]'s behavioural check (part b). It is NOT run by the Gradle test
 * task directly — [KotlinLoggingBannerGuardTest] spawns a fresh `java` process with an explicit
 * `-cp` built from the relevant classes' own `codeSource` locations (never the Gradle worker's own
 * classpath), so this class must have no dependency the test doesn't explicitly wire onto that
 * classpath.
 *
 * Two modes, selected by `args[0]`:
 * - `"off"`: flips [KotlinLoggingConfiguration.logStartupMessage] to `false` BEFORE the first
 *   [KotlinLogging.logger] call — mirrors `CurrentMain.kt`'s production ordering. Expected stdout:
 *   empty.
 * - `"on"` (the control): skips the flag flip entirely, so the library's own default (`true`)
 *   applies. Expected stdout: contains `kotlin-logging: initializing`. If a kotlin-logging-jvm
 *   upgrade ever moves or renames this banner, this control fails FIRST, distinguishing "the
 *   banner text/trigger changed" from "the classpath assembly is broken" (which would instead
 *   throw `NoClassDefFoundError` before printing anything).
 */
object KotlinLoggingBannerProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(0) == "off") {
            KotlinLoggingConfiguration.logStartupMessage = false
        }
        // Touching the KotlinLogging singleton for the first time in this (fresh) JVM is what
        // triggers the banner check inside kotlin-logging-jvm's own initialization path.
        KotlinLogging.logger("banner-probe")
    }
}

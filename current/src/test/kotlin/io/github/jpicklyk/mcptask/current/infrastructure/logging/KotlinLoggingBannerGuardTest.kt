package io.github.jpicklyk.mcptask.current.infrastructure.logging

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * O5 drift guard for item b5081c9b: kotlin-logging-jvm is deliberately kept `compileOnly` in
 * `current/build.gradle.kts` (it reaches the runtime classpath only transitively via the MCP SDK
 * — see that file's comment, and `CurrentMain.kt`'s KDoc on why the startup banner must be
 * suppressed). That decision creates a silent-drift risk: nothing forces our
 * `gradle/libs.versions.toml` `kotlinLogging` pin to track whatever version the SDK actually pulls
 * in, and nothing re-verifies that the banner-suppression mechanism (a mutable
 * [KotlinLoggingConfiguration.logStartupMessage] flag, flipped before the library's first use) still
 * works after a kotlin-logging-jvm upgrade. This class covers both:
 *
 * (a) the version pin matches the resolved jar actually on the classpath;
 * (b) the suppression mechanism is behaviourally verified in a forked JVM (compile-time-only
 *     coverage can't catch a runtime regression in a `compileOnly` dependency);
 * (c) the flag's setter/getter round-trip.
 */
class KotlinLoggingBannerGuardTest {
    // ---- (a) pin matches the resolved jar ------------------------------------------------------

    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        fail("could not locate repo root (looking for a dir containing both 'claude-plugins/' and 'current/')")
    }

    private fun catalogPinnedVersion(): String {
        val catalogPath = repoRoot().resolve("gradle/libs.versions.toml")
        if (!Files.isRegularFile(catalogPath)) fail("gradle/libs.versions.toml not found at $catalogPath")
        val text = Files.readString(catalogPath)
        val match =
            Regex("""kotlinLogging\s*=\s*"([^"]+)"""").find(text)
                ?: fail("could not find a `kotlinLogging = \"...\"` entry in $catalogPath")
        return match.groupValues[1]
    }

    /** The jar file backing a loaded class, or fails if it isn't on the filesystem as a jar. */
    private fun jarFileOf(clazz: Class<*>): File {
        val location =
            clazz.protectionDomain?.codeSource?.location
                ?: fail("no codeSource location for ${clazz.name} (should not happen for a jar-backed class)")
        return File(location.toURI())
    }

    @Test
    fun `a - the kotlinLogging catalog pin matches the resolved jar on the classpath`() {
        val jarFile = jarFileOf(KotlinLoggingConfiguration::class.java)
        assertTrue(jarFile.name.endsWith(".jar"), "Expected a jar file, got: ${jarFile.name}")

        val versionFromJarName =
            Regex("""^kotlin-logging-jvm-(.+)\.jar$""").find(jarFile.name)?.groupValues?.get(1)
                ?: fail("jar filename '${jarFile.name}' did not match the expected kotlin-logging-jvm-<version>.jar pattern")

        assertEquals(
            catalogPinnedVersion(),
            versionFromJarName,
            "gradle/libs.versions.toml's kotlinLogging pin has drifted from the resolved jar: ${jarFile.name}"
        )
    }

    // ---- (b) behavioural check in a forked JVM -------------------------------------------------

    /**
     * Assembles a `-cp` string from the classes' own `codeSource` locations rather than the
     * Gradle test worker's `java.class.path` — the worker's classpath is a shaded/merged view that
     * does not reflect what a real forked process needs, and using it here would mask exactly the
     * kind of classpath-assembly break this test exists to catch (see the task-scope note's risk
     * flag: a NoClassDefFoundError here must be distinguishable from "the banner text changed").
     *
     * `kotlin-logging-to-jul=true` is forced on the child JVM (see [KotlinLoggingBannerProbe]) so
     * the probe never touches SLF4J/Logback at all — kotlin-logging-jvm falls back to
     * `java.util.logging`, which needs no extra jar on this already-minimal classpath.
     */
    private fun probeClasspath(): String {
        val entries =
            listOf(
                jarFileOf(KotlinLoggingBannerProbe::class.java), // test classes output dir
                jarFileOf(KotlinLoggingConfiguration::class.java), // kotlin-logging-jvm
                jarFileOf(Unit::class.java) // kotlin-stdlib (also carries kotlin.jvm.internal.*)
            )
        return entries.joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun runProbe(mode: String): ProcessResult {
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                javaBin,
                "-Dkotlin-logging-to-jul=true",
                "-cp",
                probeClasspath(),
                "io.github.jpicklyk.mcptask.current.infrastructure.logging.KotlinLoggingBannerProbe",
                mode
            ).redirectErrorStream(false).start()

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            fail("BannerProbe ($mode) did not exit within 30s — treat as hung, not a banner failure")
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.exitValue(), stdout, stderr)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    @Test
    fun `b - the banner is suppressed on stdout when logStartupMessage is set false before first use`() {
        val result = runProbe("off")
        assertEquals(0, result.exitCode, "BannerProbe (off) must exit cleanly; stderr: ${result.stderr}")
        assertTrue(result.stdout.isEmpty(), "Expected empty stdout with the flag off, got: ${result.stdout}")
    }

    @Test
    fun `b - control - the banner IS printed when the flag is left at its library default`() {
        val result = runProbe("on")
        assertEquals(
            0,
            result.exitCode,
            "BannerProbe (on/control) must exit cleanly — a non-zero exit here means the classpath " +
                "assembly is broken, not that the banner moved; stderr: ${result.stderr}"
        )
        assertTrue(
            result.stdout.contains("kotlin-logging: initializing"),
            "Control expected the banner text on stdout, got: ${result.stdout}"
        )
    }

    // ---- (c) setter/getter round-trip -----------------------------------------------------------

    @Test
    fun `c - logStartupMessage setter and getter round-trip`() {
        val original = KotlinLoggingConfiguration.logStartupMessage
        try {
            KotlinLoggingConfiguration.logStartupMessage = false
            assertFalse(KotlinLoggingConfiguration.logStartupMessage)
            KotlinLoggingConfiguration.logStartupMessage = true
            assertTrue(KotlinLoggingConfiguration.logStartupMessage)
        } finally {
            KotlinLoggingConfiguration.logStartupMessage = original
        }
    }
}

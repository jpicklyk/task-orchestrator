package io.github.jpicklyk.mcptask.current.ci

import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for `.github/scripts/dogfood-freeze-guard.sh`, scenario S15.
 *
 * Contract (task-scope build item 10, v4 plan 2 F19): `dogfood-freeze-guard.sh <base-branch> <head-branch>` reads the
 * changed paths, one per line, on stdin. It fails (exit 1, with an `::error::` annotation) when the base branch is
 * `release/4.0`, the head branch does not match `^4\.0/sync-main-[^[:space:]]+$`, and a changed path is exactly
 * `.taskorchestrator/config.yaml` or starts with `deploy/global-config/`. In every other case it exits 0.
 *
 * The script is executed with a real bash (Git for Windows' bash on Windows, never the WSL launcher). The expected exit
 * codes derive from the contract above, not from the script text.
 */
class DogfoodFreezeGuardTest {
    private data class Outcome(
        val exit: Int,
        val output: String
    )

    private val script: File get() = StrictConfigCases.repoRoot().resolve(".github/scripts/dogfood-freeze-guard.sh").toFile()

    private fun bash(): String {
        if (!System.getProperty("os.name").lowercase().contains("win")) return "bash"
        val roots =
            listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"), System.getenv("LOCALAPPDATA")?.plus("\\Programs"))
        val candidates = roots.flatMap { listOf("$it\\Git\\bin\\bash.exe", "$it\\Git\\usr\\bin\\bash.exe") }
        return candidates.firstOrNull { File(it).isFile } ?: error("Git for Windows bash.exe not found; looked in $candidates")
    }

    private fun run(
        base: String,
        head: String,
        vararg paths: String
    ): Outcome {
        assertTrue(script.isFile, "guard script missing at $script")
        val process =
            ProcessBuilder(bash(), script.absolutePath.replace('\\', '/'), base, head)
                .directory(StrictConfigCases.repoRoot().toFile())
                .redirectErrorStream(true)
                .start()
        process.outputStream.use {
            it.write(
                paths.joinToString("\n", postfix = if (paths.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8)
            )
        }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS)) { "guard did not finish in time" }
        return Outcome(process.exitValue(), output)
    }

    private val frozenConfig = ".taskorchestrator/config.yaml"
    private val frozenFloor = "deploy/global-config/.taskorchestrator/config.yaml"
    private val releaseBase = "release/4.0"

    // ---------------------------------------------------------------------------------------------
    // S15 - the blocking cases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 a change to the dogfood config from an ordinary 4_0 branch is blocked with an error annotation`() {
        val result = run(releaseBase, "4.0/x", frozenConfig)

        assertEquals(1, result.exit, result.output)
        assertTrue(result.output.contains("::error::"), "the failure must be a GitHub error annotation: ${result.output}")
    }

    @Test
    fun `S15 a change under deploy global-config from an ordinary 4_0 branch is blocked`() {
        val result = run(releaseBase, "4.0/x", frozenFloor)

        assertEquals(1, result.exit, result.output)
        assertTrue(result.output.contains("::error::"), result.output)
    }

    @Test
    fun `S15 any path beneath deploy global-config is frozen, not only the config file`() {
        val result = run(releaseBase, "4.0/x", "deploy/global-config/README.md")

        assertEquals(1, result.exit, result.output)
    }

    @Test
    fun `S15 one frozen path among innocuous ones blocks the change, wherever it appears`() {
        assertEquals(1, run(releaseBase, "4.0/x", frozenConfig, "docs/a.md", "src/b.kt").exit)
        assertEquals(1, run(releaseBase, "4.0/x", "docs/a.md", frozenConfig, "src/b.kt").exit)
        assertEquals(1, run(releaseBase, "4.0/x", "docs/a.md", "src/b.kt", frozenConfig).exit)
    }

    // ---------------------------------------------------------------------------------------------
    // S15 - the permitted cases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 the sync-main branch may change the frozen files`() {
        assertEquals(0, run(releaseBase, "4.0/sync-main-2026-10-10", frozenConfig).exit)
        assertEquals(0, run(releaseBase, "4.0/sync-main-2026-10-10", frozenFloor).exit)
        assertEquals(0, run(releaseBase, "4.0/sync-main-2026-10-10", frozenConfig, frozenFloor, "docs/a.md").exit)
    }

    @Test
    fun `S15 other files under taskorchestrator are not frozen`() {
        val result = run(releaseBase, "4.0/x", ".taskorchestrator/rules/a.md")

        assertEquals(0, result.exit, result.output)
    }

    @Test
    fun `S15 a base other than release 4_0 is not guarded`() {
        assertEquals(0, run("main", "4.0/x", frozenConfig).exit)
        assertEquals(0, run("main", "feature/y", frozenFloor).exit)
    }

    @Test
    fun `S15 an empty change set passes`() {
        assertEquals(0, run(releaseBase, "4.0/x").exit)
    }

    // ---------------------------------------------------------------------------------------------
    // Probes: boundary and alternate spellings
    // ---------------------------------------------------------------------------------------------

    @TestFactory
    fun `probe paths that merely resemble the frozen paths are not frozen`(): List<DynamicTest> =
        listOf(
            "deploy/global-configX/foo.yaml",
            "deploy/global-config",
            "deploy/global-configs/.taskorchestrator/config.yaml",
            ".taskorchestrator/config.yaml.bak",
            ".taskorchestrator/config.yml",
            ".taskorchestrator/Config.yaml",
            "sub/.taskorchestrator/config.yaml",
            "x.taskorchestrator/config.yaml",
            "Deploy/global-config/a.yaml"
        ).map { path ->
            dynamicTest(path) {
                val result = run(releaseBase, "4.0/x", path)

                assertEquals(0, result.exit, "$path is not a frozen path: ${result.output}")
            }
        }

    @TestFactory
    fun `probe head branches that do not match the sync-main pattern are not exempt`(): List<DynamicTest> =
        listOf(
            "4.0/sync-main-",
            "4.0/sync-main-x y",
            "4.0/sync-main-x ",
            " 4.0/sync-main-x",
            "4.0/sync-main",
            "4.0/Sync-main-x",
            "5.0/sync-main-x",
            "x4.0/sync-main-x",
            "feature/4.0/sync-main-x",
            "4.0/sync-main-x\t"
        ).map { head ->
            dynamicTest("head '${head.replace("\t", "\\t")}'") {
                val result = run(releaseBase, head, frozenConfig)

                assertEquals(1, result.exit, "head '$head' must not be exempt: ${result.output}")
            }
        }

    @TestFactory
    fun `probe head branches with a sync-main prefix and any non-space suffix are exempt`(): List<DynamicTest> =
        listOf("4.0/sync-main-1", "4.0/sync-main-2026-10-10", "4.0/sync-main-a/b", "4.0/sync-main--").map { head ->
            dynamicTest("head '$head'") {
                assertEquals(0, run(releaseBase, head, frozenConfig).exit, "head '$head' matches 4.0/sync-main-<non-space>")
            }
        }

    @Test
    fun `probe the base comparison is exact - near misses of release 4_0 are not guarded`() {
        assertEquals(0, run("Release/4.0", "4.0/x", frozenConfig).exit)
        assertEquals(0, run("release/4.01", "4.0/x", frozenConfig).exit)
        assertEquals(0, run("release/4.0.1", "4.0/x", frozenConfig).exit)
        assertEquals(0, run("origin/release/4.0", "4.0/x", frozenConfig).exit)
    }

    @Test
    fun `probe the exit code is the same whether or not a trailing newline ends the path list`() {
        val process =
            ProcessBuilder(bash(), script.absolutePath.replace('\\', '/'), releaseBase, "4.0/x")
                .directory(StrictConfigCases.repoRoot().toFile())
                .redirectErrorStream(true)
                .start()
        process.outputStream.use { it.write(frozenConfig.toByteArray(Charsets.UTF_8)) }
        process.inputStream.readBytes()
        check(process.waitFor(60, TimeUnit.SECONDS))

        assertEquals(1, process.exitValue(), "a final path without a newline must still be checked")
    }
}

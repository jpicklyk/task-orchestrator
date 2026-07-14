package io.github.jpicklyk.mcptask.current.infrastructure.security

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PathContainmentTest {
    private fun tempBaseDir() = Files.createTempDirectory("path-containment-test").also { it.toFile().deleteOnExit() }

    @Test
    fun `allows a file directly inside the base directory`() {
        val base = tempBaseDir()
        val file = base.resolve("note-body.txt")
        file.writeText("hello")

        val result = PathContainment.resolveWithinBase(base, "note-body.txt")

        assertIs<PathContainment.Result.Allowed>(result)
        assertEquals(file.toRealPath(), result.realPath)
    }

    @Test
    fun `allows a file in a nested subdirectory`() {
        val base = tempBaseDir()
        val subDir = base.resolve("sub/dir").createDirectories()
        val file = subDir.resolve("body.txt")
        file.writeText("nested")

        val result = PathContainment.resolveWithinBase(base, "sub/dir/body.txt")

        assertIs<PathContainment.Result.Allowed>(result)
    }

    @Test
    fun `rejects a relative path escape via dot-dot`() {
        val base = tempBaseDir().resolve("root").createDirectories()
        val outside = base.parent.resolve("outside.txt")
        outside.writeText("secret")

        val result = PathContainment.resolveWithinBase(base, "../outside.txt")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("escapes"), "reason should name the escape: ${result.reason}")
    }

    @Test
    fun `rejects an absolute path`() {
        val base = tempBaseDir()

        val result = PathContainment.resolveWithinBase(base, "/etc/passwd")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("absolute"), "reason should name the absolute-path rejection: ${result.reason}")
    }

    @Test
    fun `rejects an absolute windows-style path`() {
        val base = tempBaseDir()

        val result = PathContainment.resolveWithinBase(base, "C:\\Windows\\win.ini")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("absolute"), "reason should name the absolute-path rejection: ${result.reason}")
    }

    @Test
    fun `rejects a missing file`() {
        val base = tempBaseDir()

        val result = PathContainment.resolveWithinBase(base, "does-not-exist.txt")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("not found"), "reason should name the missing file: ${result.reason}")
    }

    @Test
    fun `rejects a path that is a directory`() {
        val base = tempBaseDir()
        base.resolve("adir").createDirectories()

        val result = PathContainment.resolveWithinBase(base, "adir")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("directory"), "reason should name the directory rejection: ${result.reason}")
    }

    @Test
    fun `rejects a symlink that escapes the base directory via real-path resolution`() {
        val base = tempBaseDir()
        val outsideDir = tempBaseDir()
        val outsideFile = outsideDir.resolve("secret.txt")
        outsideFile.writeText("outside content")

        val link = base.resolve("escape-link.txt")
        try {
            Files.createSymbolicLink(link, outsideFile)
        } catch (e: Exception) {
            // Creating symlinks on Windows requires Developer Mode or an elevated process.
            // Skip rather than fail when the environment does not support it.
            assumeTrue(false, "symlink creation unsupported in this environment: ${e.message}")
            return
        }

        val result = PathContainment.resolveWithinBase(base, "escape-link.txt")

        assertIs<PathContainment.Result.Rejected>(result)
        assertTrue(result.reason.contains("symlink"), "reason should name the symlink escape: ${result.reason}")
    }
}

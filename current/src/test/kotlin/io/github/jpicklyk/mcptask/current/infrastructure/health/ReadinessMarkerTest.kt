package io.github.jpicklyk.mcptask.current.infrastructure.health

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 56ac1690's [ReadinessMarker] — the Docker HEALTHCHECK's
 * readiness probe. Oracle source: [ReadinessMarker]'s own KDoc (a NEW file the test-plan names
 * verbatim) plus the item's frozen `test-plan` note. Covers S2, S10, and the marker-lifecycle
 * probe list (missing parent dir, stale pre-existing file, double markReady, clear when absent).
 *
 * S1/S7's live-transport half and S6's `main()` invocation are intentionally out of scope for this
 * suite too (see `StartupOutcomeTest`'s class doc) — this class only exercises [ReadinessMarker]
 * directly, never a live [io.github.jpicklyk.mcptask.current.interfaces.mcp.CurrentMcpServer].
 */
class ReadinessMarkerTest {
    // ---- S2: clean shutdown clears the marker (HEALTHCHECK semantics: non-serving != healthy) ----

    @Test
    fun `S2 clear removes a marker written by markReady`(
        @TempDir tempDir: Path
    ) {
        val marker = ReadinessMarker(tempDir.resolve("ready"))

        marker.markReady()
        assertTrue(marker.isReady(), "marker must report ready immediately after markReady()")

        marker.clear()
        assertFalse(marker.isReady(), "marker must report not-ready after a clean clear() (shutdown)")
    }

    // ---- S10: an unwritable readiness directory fails markReady() (mapped by the caller to
    // Failed(READINESS_MARKER) -- see StartupOutcomeTest and ReadinessMarker's own KDoc) ----

    @Test
    fun `S10 markReady throws when the parent directory cannot be created`(
        @TempDir tempDir: Path
    ) {
        // A plain FILE occupying the position where a parent directory needs to be created blocks
        // Files#createDirectories with a real, non-mocked FileAlreadyExistsException.
        val blocker = tempDir.resolve("blocker")
        Files.writeString(blocker, "not a directory")

        val unwritablePath = blocker.resolve("sub").resolve("ready")
        val marker = ReadinessMarker(unwritablePath)

        assertFailsWith<Exception> {
            marker.markReady()
        }
        assertFalse(marker.isReady(), "marker must not report ready when the write failed")
    }

    // ---- Probes: missing parent dir; stale pre-existing file; markReady() twice; clear() when gone ----

    @Test
    fun `probe markReady creates missing parent directories`(
        @TempDir tempDir: Path
    ) {
        val nested =
            tempDir
                .resolve("a")
                .resolve("b")
                .resolve("c")
                .resolve("ready")
        val marker = ReadinessMarker(nested)

        marker.markReady()

        assertTrue(Files.exists(nested), "markReady() must create missing parent directories")
        assertTrue(marker.isReady())
    }

    @Test
    fun `probe a stale pre-existing marker file is detected and can be cleared`(
        @TempDir tempDir: Path
    ) {
        val path = tempDir.resolve("ready")
        // Simulate a leftover marker from a previous, uncleanly-terminated container -- written
        // directly, bypassing ReadinessMarker entirely.
        Files.writeString(path, "stale")
        val marker = ReadinessMarker(path)

        assertTrue(marker.isReady(), "a stale pre-existing file must still read as ready")

        marker.clear()

        assertFalse(marker.isReady(), "clear() must remove a stale file exactly like one it wrote itself")
    }

    @Test
    fun `probe markReady is idempotent when called twice`(
        @TempDir tempDir: Path
    ) {
        val marker = ReadinessMarker(tempDir.resolve("ready"))

        marker.markReady()
        marker.markReady()

        assertTrue(marker.isReady(), "calling markReady() twice must not throw and must leave the marker ready")
    }

    @Test
    fun `probe clear is a no-op when the marker is already absent`(
        @TempDir tempDir: Path
    ) {
        val marker = ReadinessMarker(tempDir.resolve("never-written"))

        marker.clear()

        assertFalse(marker.isReady(), "clear() on an absent marker must not throw and must leave it absent")
    }
}

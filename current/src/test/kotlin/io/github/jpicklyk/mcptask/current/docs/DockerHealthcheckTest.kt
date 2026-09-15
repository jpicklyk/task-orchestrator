package io.github.jpicklyk.mcptask.current.docs

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent test-author suite for item 56ac1690's S11: a static, presence-only check that the
 * repo-root `Dockerfile` carries the readiness-marker HEALTHCHECK. No container is built or run —
 * this only parses Dockerfile TEXT (per the test-plan: "PRESENCE CHECK ONLY: static text parse, no
 * container").
 *
 * Oracle source: the item's frozen `test-plan` note, which states the exact contract the
 * Dockerfile must carry — NOT this Dockerfile's actual content, which was never read while writing
 * these assertions:
 *   - `ENV READINESS_FILE=<default>`
 *   - `HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 CMD sh -c 'test -f
 *     "$READINESS_FILE"'`
 *
 * The probe is deliberately file-based rather than HTTP: the runtime image ships no `curl`/`wget`,
 * `/api/v1/health` only exists under `MCP_TRANSPORT=http` + `API_ENABLED=true`, and the default
 * transport is `stdio` — a file probe is the only one that works unconditionally.
 *
 * Backslash line-continuations are joined into one logical line before matching so the check
 * survives Dockerfile formatting choices (single-line vs. multi-line HEALTHCHECK) without needing
 * to read the file first to see which style was used.
 */
class DockerHealthcheckTest {
    private fun dockerfileLogicalText(): String {
        val dockerfile = repoRoot().resolve("Dockerfile")
        if (!Files.isRegularFile(dockerfile)) fail("Dockerfile not found at $dockerfile")
        val raw = Files.readString(dockerfile)
        // Join "...\<newline>   continuation" into a single logical line.
        return raw.replace(Regex("\\\\\\s*\\r?\\n\\s*"), " ")
    }

    private fun healthcheckLine(text: String): String {
        val match = Regex("(?m)^\\s*HEALTHCHECK\\b.*").find(text)
        if (match == null) fail("Dockerfile must declare a HEALTHCHECK instruction")
        return match.value
    }

    @Test
    fun `S11 Dockerfile declares a READINESS_FILE env default`() {
        val text = dockerfileLogicalText()
        assertTrue(
            Regex("(?m)^\\s*ENV\\s+READINESS_FILE=").containsMatchIn(text),
            "Dockerfile must declare an ENV READINESS_FILE=<default> line"
        )
    }

    @Test
    fun `S11 Dockerfile HEALTHCHECK carries the documented timing flags`() {
        val line = healthcheckLine(dockerfileLogicalText())

        assertTrue(line.contains("--interval=30s"), "HEALTHCHECK must set --interval=30s: $line")
        assertTrue(line.contains("--timeout=3s"), "HEALTHCHECK must set --timeout=3s: $line")
        assertTrue(line.contains("--start-period=20s"), "HEALTHCHECK must set --start-period=20s: $line")
        assertTrue(line.contains("--retries=3"), "HEALTHCHECK must set --retries=3: $line")
    }

    @Test
    fun `S11 Dockerfile HEALTHCHECK CMD probes the READINESS_FILE marker, not HTTP`() {
        val line = healthcheckLine(dockerfileLogicalText())

        assertTrue(line.contains("CMD"), "HEALTHCHECK must specify a CMD: $line")
        assertTrue(
            line.contains("test -f") && line.contains("READINESS_FILE"),
            "HEALTHCHECK CMD must test -f the READINESS_FILE marker: $line"
        )
        assertTrue(
            !line.contains("curl") && !line.contains("wget") && !line.contains("/api/v1/health"),
            "HEALTHCHECK must not probe HTTP -- the runtime image ships no curl/wget and " +
                "/api/v1/health is off by default (stdio transport, API_ENABLED=false): $line"
        )
    }

    /** Walk up from the test working directory until the repo root (contains both marker dirs). */
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
}

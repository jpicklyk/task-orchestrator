package io.github.jpicklyk.mcptask.current.docs

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Test-author suite for item dc7693e1's S7 (static manifest checks): a presence-only text parse of
 * `Dockerfile` and `docker-compose.yml`, no container built or run.
 *
 * Oracle: the item's frozen `task-scope` note (queue phase) -- the Dockerfile `CMD` must pin the
 * JVM to UTC (AR-49), and the `mcp-task-orchestrator` (stdio) compose service must declare a
 * `stdio` profile so `docker compose --profile http up` no longer starts it alongside the HTTP
 * service on the same volume (AR-85). The `mcp-task-orchestrator-http` and
 * `mcp-task-orchestrator-http-rest` services are untouched by this item and must keep their
 * existing `http`/`http-rest` profiles.
 */
class RuntimeHardeningManifestTest {
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

    private fun dockerfileText(): String {
        val path = repoRoot().resolve("Dockerfile")
        if (!Files.isRegularFile(path)) fail("Dockerfile not found at $path")
        return Files.readString(path)
    }

    private fun composeText(): String {
        val path = repoRoot().resolve("docker-compose.yml")
        if (!Files.isRegularFile(path)) fail("docker-compose.yml not found at $path")
        return Files.readString(path)
    }

    private fun cmdLine(text: String): String {
        val match = Regex("(?m)^\\s*CMD\\s*\\[.*\\]\\s*$").find(text)
        if (match == null) fail("Dockerfile must declare a CMD instruction with the JSON array form")
        return match.value
    }

    // ---- AR-49: Dockerfile CMD pins the JVM to UTC ----

    @Test
    fun `S7 Dockerfile CMD pins the JVM default timezone to UTC`() {
        val cmd = cmdLine(dockerfileText())
        assertTrue(
            cmd.contains("-Duser.timezone=UTC"),
            "Dockerfile CMD must include -Duser.timezone=UTC: $cmd"
        )
    }

    @Test
    fun `S7 Dockerfile CMD still carries the pre-existing native-access and jar flags`() {
        val cmd = cmdLine(dockerfileText())
        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"), "CMD must keep --enable-native-access=ALL-UNNAMED: $cmd")
        assertTrue(cmd.contains("-jar"), "CMD must keep -jar: $cmd")
        assertTrue(cmd.contains("orchestrator.jar"), "CMD must keep the orchestrator.jar target: $cmd")
    }

    // ---- AR-85: the stdio compose service is profile-gated, http/http-rest are untouched ----

    /** Extracts one top-level service block's text, from its `  <name>:` header to the next sibling header. */
    private fun serviceBlock(
        text: String,
        serviceName: String
    ): String {
        val headerPattern = Regex("(?m)^  $serviceName:\\s*$")
        val header = headerPattern.find(text) ?: fail("docker-compose.yml has no top-level service '$serviceName'")
        val rest = text.substring(header.range.last + 1)
        val nextHeader = Regex("(?m)^  \\S.*:\\s*$").find(rest)
        return if (nextHeader != null) rest.substring(0, nextHeader.range.first) else rest
    }

    @Test
    fun `S7 mcp-task-orchestrator stdio service declares a stdio profile`() {
        val block = serviceBlock(composeText(), "mcp-task-orchestrator")
        assertTrue(
            Regex("(?m)^\\s*profiles:\\s*$").containsMatchIn(block) &&
                Regex("(?m)^\\s*-\\s*stdio\\s*$").containsMatchIn(block),
            "mcp-task-orchestrator service must declare `profiles: [stdio]` so it is excluded from " +
                "`docker compose --profile http up`"
        )
    }

    @Test
    fun `S7 mcp-task-orchestrator-http service keeps its http profile untouched`() {
        val block = serviceBlock(composeText(), "mcp-task-orchestrator-http")
        assertTrue(
            Regex("(?m)^\\s*-\\s*http\\s*$").containsMatchIn(block),
            "mcp-task-orchestrator-http must keep its existing http profile"
        )
        assertFalse(
            Regex("(?m)^\\s*-\\s*stdio\\s*$").containsMatchIn(block),
            "mcp-task-orchestrator-http must not gain a stdio profile"
        )
    }

    @Test
    fun `S7 mcp-task-orchestrator-http-rest service keeps its http-rest profile untouched`() {
        val block = serviceBlock(composeText(), "mcp-task-orchestrator-http-rest")
        assertTrue(
            Regex("(?m)^\\s*-\\s*http-rest\\s*$").containsMatchIn(block),
            "mcp-task-orchestrator-http-rest must keep its existing http-rest profile"
        )
    }
}

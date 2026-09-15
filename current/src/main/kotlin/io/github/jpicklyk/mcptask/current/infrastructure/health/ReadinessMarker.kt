package io.github.jpicklyk.mcptask.current.infrastructure.health

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes and clears a plain marker file used as the container HEALTHCHECK's readiness probe.
 *
 * A marker file — not an HTTP probe — is the default readiness signal because the runtime image
 * (`amazoncorretto:*-al2023-headless`) ships no `curl`/`wget`, and `/api/v1/health` only exists
 * when `MCP_TRANSPORT=http` AND `API_ENABLED=true`; the default transport is `stdio`. A file probe
 * works identically for every transport.
 *
 * The caller is responsible for the "actually serving" ordering: [markReady] must only be called
 * once the transport has actually started (after `server.createSession` / `ktorServer.start`
 * succeed), and [clear] must be called on every shutdown path so a stopped-but-not-yet-restarted
 * container never reports healthy.
 */
class ReadinessMarker(
    val path: Path
) {
    /**
     * Creates [path] (and any missing parent directories) so the file exists. Throws if the parent
     * directory cannot be created or the file cannot be written (e.g. an unwritable readiness
     * directory) — callers map that failure to [io.github.jpicklyk.mcptask.current.interfaces.mcp.Reason.READINESS_MARKER].
     */
    fun markReady() {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, "ready")
    }

    /** Removes the marker file if present. A no-op (never throws) when it is already absent. */
    fun clear() {
        Files.deleteIfExists(path)
    }

    /** True when the marker file currently exists. */
    fun isReady(): Boolean = Files.exists(path)
}

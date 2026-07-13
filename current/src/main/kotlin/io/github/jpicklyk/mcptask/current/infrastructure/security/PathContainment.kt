package io.github.jpicklyk.mcptask.current.infrastructure.security

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a caller-supplied relative path strictly within a trusted base directory, rejecting
 * every known escape vector before the file is touched.
 *
 * Used by tools that accept a path parameter pointing at a file to read (e.g. `manage_notes`
 * `bodyFromFile`), where the base directory is a trusted root (such as the agent config root
 * resolved from `AGENT_CONFIG_DIR`) and the path itself comes from untrusted tool input.
 *
 * This is intentionally a singleton object rather than a free function to make call sites
 * explicit and searchable for security audit purposes (mirrors [ConstantTimeCompare]).
 *
 * Rejection order (each produces a distinct, human-readable reason):
 *  1. Absolute paths (checked both via [Path.isAbsolute] and via string prefix, since a
 *     Unix-style leading slash is not always flagged as absolute by the Windows path provider).
 *  2. Lexical `..`-style escapes — caught by comparing the normalized (but not yet
 *     symlink-resolved) candidate against the normalized base, so an escape is rejected even
 *     when the target does not exist.
 *  3. Missing files.
 *  4. Symlink escapes — caught by comparing the *real* (symlink-resolved) candidate path
 *     against the *real* base path, so a symlink whose target lies outside the base directory
 *     is rejected even though its lexical path is contained.
 */
object PathContainment {
    /** Result of a containment resolution: exactly one of [Allowed] or [Rejected]. */
    sealed class Result {
        /** The requested path is contained within the base directory and exists. */
        data class Allowed(
            val realPath: Path
        ) : Result()

        /** The requested path was rejected; [reason] names why (used directly in tool errors). */
        data class Rejected(
            val reason: String
        ) : Result()
    }

    private val DRIVE_LETTER_PATTERN = Regex("^[A-Za-z]:.*")

    /**
     * Resolves [requestedPath] relative to [baseDir], enforcing that the final, symlink-resolved
     * location stays within [baseDir] and refers to an existing regular file.
     *
     * @param baseDir A trusted, existing directory (not attacker-controlled).
     * @param requestedPath An untrusted, caller-supplied path — expected to be relative.
     */
    fun resolveWithinBase(
        baseDir: Path,
        requestedPath: String
    ): Result {
        if (isAbsolute(requestedPath)) {
            return Result.Rejected("path must be relative to the agent config root, got absolute path: $requestedPath")
        }

        val baseNormalized = baseDir.normalize()
        val lexicalCandidate = baseNormalized.resolve(requestedPath).normalize()
        if (!lexicalCandidate.startsWith(baseNormalized)) {
            return Result.Rejected("path escapes the agent config root: $requestedPath")
        }

        if (!Files.exists(lexicalCandidate)) {
            return Result.Rejected("file not found: $requestedPath")
        }
        if (Files.isDirectory(lexicalCandidate)) {
            return Result.Rejected("path is a directory, not a file: $requestedPath")
        }

        val baseReal =
            try {
                baseDir.toRealPath()
            } catch (e: IOException) {
                return Result.Rejected("agent config root is not accessible: ${e.message}")
            }
        val candidateReal =
            try {
                lexicalCandidate.toRealPath()
            } catch (e: IOException) {
                return Result.Rejected("file not accessible: $requestedPath (${e.message})")
            }

        if (!candidateReal.startsWith(baseReal)) {
            return Result.Rejected("path escapes the agent config root via symlink: $requestedPath")
        }

        return Result.Allowed(candidateReal)
    }

    /**
     * True if [raw] is absolute under this platform's [Path] provider, or looks absolute under
     * a *different* platform's convention (leading `/`, leading `\`, or a Windows drive letter
     * like `C:`). The defense-in-depth checks ensure a path crafted for one OS's absolute form
     * is still rejected when this code happens to run on another OS.
     */
    private fun isAbsolute(raw: String): Boolean {
        if (Paths.get(raw).isAbsolute) return true
        return raw.startsWith("/") || raw.startsWith("\\") || DRIVE_LETTER_PATTERN.matches(raw)
    }
}

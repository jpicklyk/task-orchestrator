package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.application.port.BodySource
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The file-backed [BodySource]: resolves a caller-relative path strictly inside [root] with [PathContainment] (no
 * absolute path, no `..`, no symlink out of the root), then reads at most [BodySource.MAX_FILE_BYTES] + 1 bytes of a
 * regular file and decodes them as strict UTF-8.
 *
 * The root is the caller's trusted choice (production: `AppConfig.resolveConfigBaseDir(agentConfigDir)`, built once in
 * `ServerComposition`); nothing here reads the environment. The read never buffers more than the cap plus one byte,
 * whatever the file's size, so a file growing between a size check and the read cannot make it allocate more. An
 * `InvalidPathException` (a NUL, a character the platform forbids) is `invalid_request`, never an internal error, and no
 * error text carries the root, a resolved path or an exception message.
 */
class FileBodySource(
    private val root: Path
) : BodySource {
    override fun read(path: String): Outcome<String> {
        val resolved =
            try {
                PathContainment.resolveWithinBase(root, path)
            } catch (e: InvalidPathException) {
                return Outcome.Err(BodySource.invalid("is not a valid path", path))
            }
        val real =
            when (resolved) {
                is PathContainment.Result.Rejected -> return Outcome.Err(BodySource.invalid(rejection(resolved.reason), path))
                is PathContainment.Result.Allowed -> resolved.realPath
            }
        return try {
            val attributes = Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) return Outcome.Err(BodySource.invalid("is not a regular file", path))
            val bytes =
                Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { input -> input.readNBytes(BodySource.MAX_FILE_BYTES + 1) }
            if (bytes.size > BodySource.MAX_FILE_BYTES) {
                return Outcome.Err(BodySource.tooLarge(maxOf(bytes.size.toLong(), attributes.size())))
            }
            decode(bytes)?.let { Outcome.Ok(it) } ?: Outcome.Err(BodySource.invalid("is not valid UTF-8 text", path))
        } catch (e: IOException) {
            e.rethrowIfCancellation()
            Outcome.Err(BodySource.invalid("could not be read", path))
        }
    }

    /** Strict UTF-8: malformed or unmappable input is null, never replaced. */
    private fun decode(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    /** A fixed phrase per [PathContainment] rejection, so no resolved path or exception text can leak. */
    private fun rejection(reason: String): String =
        when {
            reason.contains("absolute") -> "must be relative to the server's file root"
            reason.contains("symlink") -> "resolves outside the server's file root"
            reason.contains("escapes") -> "resolves outside the server's file root"
            reason.contains("directory") -> "is a directory, not a file"
            reason.contains("not found") -> "was not found"
            else -> "is not readable"
        }
}

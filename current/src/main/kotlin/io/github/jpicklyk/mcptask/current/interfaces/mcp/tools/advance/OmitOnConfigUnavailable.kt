package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException

/**
 * Runs [block] and, on [PerRootConfigUnavailableException], logs one WARN naming [what] and [id]
 * and returns null instead of propagating. Used by the post-commit "decoration" call sites (the
 * dispatch hint of an applied transition) where the underlying write already succeeded: per D7, a
 * per-root config read failure resolving a response-only hint must never be reported as a failure
 * of an already-committed operation.
 */
internal inline fun <T> omitOnConfigUnavailable(
    logger: org.slf4j.Logger,
    what: String,
    id: Any?,
    block: () -> T
): T? =
    try {
        block()
    } catch (e: PerRootConfigUnavailableException) {
        logger.warn(
            "Per-root config unavailable resolving {} for {}; omitting from an already-committed operation: {}",
            what,
            id,
            e.message
        )
        null
    }

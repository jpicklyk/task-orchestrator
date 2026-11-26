package io.github.jpicklyk.mcptask.current.platform.rest.etag

/**
 * The 4.0 strong validator of a versioned resource: `"v<version>"` (quoted per RFC 9110). Every write bumps an
 * item's version, so the same [version] always names the same representation.
 */
fun etagForVersion(version: Long): String = "\"v$version\""

/** True when the `If-None-Match` [header] lists [etag] (weak validators compare equal) or is `*`. */
internal fun matchesIfNoneMatch(
    header: String?,
    etag: String
): Boolean {
    if (header == null) return false
    return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
}

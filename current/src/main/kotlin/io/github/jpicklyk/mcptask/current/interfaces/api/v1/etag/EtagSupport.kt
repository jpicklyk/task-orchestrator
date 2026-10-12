package io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag

/**
 * The 4.0 strong validator of a versioned resource: `"v<version>"` (quoted per RFC 9110). Every write bumps an
 * item's version, so the same [version] always names the same representation.
 */
fun etagForVersion(version: Long): String = "\"v$version\""

package io.github.jpicklyk.mcptask.current.infrastructure.config

// -------------------------------------------------------------------------
// did:web identifier validation, shared by both enforcement points:
// - [DefaultJwksKeySetProvider.getKeySetForIssuer] validates BEFORE the issuer is evaluated
//   against a didAllowlist/didPattern;
// - [DidWebResolver.resolve] validates again, as defense in depth, before any URL is built or fetched.
//
// Rules (frozen by planning seat, see item a890542c decision D3):
// - The identifier is colon-separated into a host segment followed by zero or more path segments.
// - Every character must be a W3C DID Core §3.1 idchar: ASCII letter/digit, ".", "-", "_", ":" (the
//   segment separator), or a percent-encoded octet ("%" followed by exactly two hex digits). Raw
//   "/", "?", "#", "@" (and any other non-idchar) are rejected outright.
// - In the host segment, the only percent-encoding allowed is "%3A" (either case, decodes to ":").
//   The decoded host must match [A-Za-z0-9.-]+ with an optional ":" + 1-5 digit port.
//   The port, when present, must be 1-65535.
// - Each path segment, percent-decoded once, must not contain "/", "?", "#", "@" or "%" (catching
//   both raw delimiters and double-encoding such as "%252F" decoding to the literal "%2F"), nor a
//   control character (U+0000-U+001F, U+007F) or a backslash, and must not decode to "." or ".."
//   (dot-segments, RFC 3986 §5.2.4). Empty segments and "%20" stay legal (fleet-deployment.md).
// -------------------------------------------------------------------------

/** Message prefix for every violation raised here. */
private const val MALFORMED_DID_MESSAGE_PREFIX = "malformed DID"

/**
 * Validates a did:web method-specific identifier (the part after "did:web:") per the rules above.
 *
 * @throws DidSecurityViolationException with a message starting "malformed DID" on any violation.
 */
internal fun validateDidWebIdentifier(identifier: String) {
    validateDidIdChars(identifier)
    val parts = identifier.split(":")
    val hostSegment = parts.first()
    if (hostSegment.isEmpty()) {
        throw DidSecurityViolationException("$MALFORMED_DID_MESSAGE_PREFIX: empty host segment in '$identifier'")
    }
    validateDidHostSegment(hostSegment)
    parts.drop(1).forEach { validateDidPathSegment(it) }
}

/** Validates every character in the raw (not yet decoded) identifier is a DID Core §3.1 idchar. */
private fun validateDidIdChars(identifier: String) {
    var i = 0
    while (i < identifier.length) {
        val c = identifier[i]
        when {
            c == '%' -> {
                val hexDigits = identifier.drop(i + 1).take(2)
                if (hexDigits.length != 2 || !hexDigits.all { isHexDigit(it) }) {
                    throw DidSecurityViolationException(
                        "$MALFORMED_DID_MESSAGE_PREFIX: invalid percent-encoding in '$identifier'"
                    )
                }
                i += 3
            }
            isAsciiAlnum(c) || c == '.' || c == '-' || c == '_' || c == ':' -> i += 1
            else ->
                throw DidSecurityViolationException(
                    "$MALFORMED_DID_MESSAGE_PREFIX: disallowed character '$c' in '$identifier'"
                )
        }
    }
}

/** Validates the did:web host segment, restricting percent-encoding to "%3A" only. */
private fun validateDidHostSegment(hostSegment: String) {
    val decoded = StringBuilder()
    var i = 0
    while (i < hostSegment.length) {
        val c = hostSegment[i]
        if (c == '%') {
            val hex = hostSegment.drop(i + 1).take(2)
            if (!hex.equals("3A", ignoreCase = true)) {
                throw DidSecurityViolationException(
                    "$MALFORMED_DID_MESSAGE_PREFIX: disallowed percent-encoding '%$hex' in host segment '$hostSegment'"
                )
            }
            decoded.append(':')
            i += 3
        } else {
            decoded.append(c)
            i += 1
        }
    }
    val match =
        DID_HOST_PATTERN.matchEntire(decoded)
            ?: throw DidSecurityViolationException("$MALFORMED_DID_MESSAGE_PREFIX: invalid host segment '$hostSegment'")
    val port = match.groupValues[1]
    if (port.isNotEmpty() && port.toInt() !in 1..65535) {
        throw DidSecurityViolationException("$MALFORMED_DID_MESSAGE_PREFIX: port out of range in host segment '$hostSegment'")
    }
}

/** Validates a single did:web path segment (percent-decoded once) per the rules above. */
private fun validateDidPathSegment(segment: String) {
    val decoded = decodeDidSegmentOnce(segment)
    if (decoded == "." || decoded == "..") {
        throw DidSecurityViolationException(
            "$MALFORMED_DID_MESSAGE_PREFIX: dot-segment '$decoded' in path"
        )
    }
    if (decoded.any { it in "/?#@%\\" || it < ' ' || it == '\u007F' }) {
        throw DidSecurityViolationException(
            "$MALFORMED_DID_MESSAGE_PREFIX: disallowed character in decoded path segment '$decoded'"
        )
    }
}

/** Percent-decodes a segment exactly once — every "%XX" is already known-valid (see [validateDidIdChars]). */
private fun decodeDidSegmentOnce(segment: String): String =
    PERCENT_OCTET.replace(segment) {
        it.groupValues[1]
            .toInt(16)
            .toChar()
            .toString()
    }

private val PERCENT_OCTET = Regex("%([0-9A-Fa-f]{2})")

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

private fun isAsciiAlnum(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

private val DID_HOST_PATTERN = Regex("^[A-Za-z0-9.-]+(?::([0-9]{1,5}))?$")

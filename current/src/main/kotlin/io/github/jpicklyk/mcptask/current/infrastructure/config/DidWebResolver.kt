package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * [DidResolver] implementation for the `did:web` method.
 *
 * Fetches DID documents over HTTPS per the
 * [W3C did:web specification](https://w3c-ccg.github.io/did-method-web/).
 *
 * URL construction rules:
 * - `did:web:example.com` → `https://example.com/.well-known/did.json`
 * - `did:web:example.com:agents:abc` → `https://example.com/agents/abc/did.json`
 * - Percent-encoded characters in the host (e.g. `%3A` for `:`) are decoded before use.
 *
 * After fetching, the resolver verifies that the returned document's `id` field matches
 * the requested DID — a critical security check against document substitution attacks.
 */
class DidWebResolver(
    engine: HttpClientEngine? = null
) : DidResolver {
    override val method: String = "web"

    private val logger = LoggerFactory.getLogger(DidWebResolver::class.java)

    /** Thread-safe lazy HTTP client — created only when a network fetch is needed. */
    private val httpClientDelegate = lazy { buildHttpClient(engine) }
    private val httpClient: HttpClient by httpClientDelegate

    private fun buildHttpClient(engine: HttpClientEngine?): HttpClient {
        val configure: HttpClientConfig<*>.() -> Unit = {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(CIO) { configure(this) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(did: String): DidDocument {
        if (!did.startsWith("did:web:")) {
            throw DidResolutionException("not a did:web DID: $did")
        }

        val identifier = did.removePrefix("did:web:")
        if (identifier.isEmpty()) {
            throw DidResolutionException("empty identifier in did:web DID: $did")
        }

        // Defense in depth: [DefaultJwksKeySetProvider.getKeySetForIssuer] already validates the
        // identifier before this resolver is ever reached in the normal JWT-verification path, but
        // this resolver validates independently (duplicated, not shared — see item a890542c
        // decision D3) so a malformed DID can never reach buildUrl/fetch through any other caller.
        validateDidWebIdentifier(identifier)

        val url = buildUrl(identifier)
        logger.debug("Resolving {} → {}", did, url)

        val responseBody: String =
            try {
                val response = httpClient.get(url)

                // Reject non-200 responses (including 3xx redirects — we never follow them).
                if (response.status != HttpStatusCode.OK) {
                    throw DidResolutionException("HTTP ${response.status.value} from $url")
                }

                // Validate Content-Type: accept application/did+json or application/json.
                val contentType = response.contentType()
                if (contentType == null || !isAcceptableDidContentType(contentType)) {
                    throw DidResolutionException(
                        "unexpected Content-Type '$contentType' from $url; expected application/did+json or application/json"
                    )
                }

                // Read body with a hard cap to prevent unbounded memory consumption.
                val bytes = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES.toLong() + 1).readByteArray()
                if (bytes.size > MAX_BODY_BYTES) {
                    throw DidResolutionException(
                        "response body from $url exceeds maximum allowed size of ${MAX_BODY_BYTES} bytes"
                    )
                }
                bytes.toString(Charsets.UTF_8)
            } catch (e: DidResolutionException) {
                throw e
            } catch (e: Exception) {
                throw DidResolutionException("network error resolving $did: ${e.message}", e)
            }

        val document = parseDidDocument(responseBody, did)

        if (document.id != did) {
            throw DidSecurityViolationException(
                "document id mismatch: requested '$did' but received '${document.id}'"
            )
        }

        return document
    }

    /**
     * Constructs the HTTPS URL for a given did:web identifier segment.
     *
     * Visible as `internal` so tests can verify URL construction directly.
     */
    internal fun buildUrl(identifier: String): String {
        // Split on ':' to separate host from optional path segments.
        val parts = identifier.split(":")

        // Percent-decode the host segment (e.g. "example.com%3A8080" → "example.com:8080").
        val host = java.net.URLDecoder.decode(parts[0], "UTF-8")
        if (host.isEmpty()) throw DidResolutionException("empty host segment in did:web DID")

        return if (parts.size == 1) {
            // No path segments — use .well-known
            "https://$host/.well-known/did.json"
        } else {
            // Path segments replace ':' separators with '/'.
            // Per W3C did:web spec, each segment must be individually percent-decoded.
            val path =
                parts
                    .drop(1)
                    .map { java.net.URLDecoder.decode(it, "UTF-8") }
                    .joinToString("/")
            "https://$host/$path/did.json"
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    private fun parseDidDocument(
        json: String,
        requestedDid: String
    ): DidDocument {
        val root: JsonObject =
            try {
                this.json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw DidResolutionException("invalid JSON in DID document for $requestedDid: ${e.message}", e)
            }

        val id =
            root["id"]?.jsonPrimitive?.content
                ?: throw DidSecurityViolationException("DID document missing 'id' field for $requestedDid")

        val verificationMethods = parseVerificationMethods(root)
        val assertionMethod = parseReferences(root, "assertionMethod")
        val authentication = parseReferences(root, "authentication")

        return DidDocument(
            id = id,
            verificationMethods = verificationMethods,
            assertionMethod = assertionMethod,
            authentication = authentication
        )
    }

    private fun parseVerificationMethods(root: JsonObject): List<VerificationMethod> {
        val vmArray =
            root["verificationMethod"]?.let {
                runCatching { it.jsonArray }.getOrElse { e ->
                    logger.warn("verificationMethod field is not a JSON array — skipping: {}", e.message)
                    null
                }
            } ?: return emptyList()

        return vmArray.mapIndexedNotNull { index, element ->
            val obj =
                (element as? JsonObject)
                    ?: run {
                        logger.warn("Skipping verificationMethod[{}]: not a JSON object", index)
                        return@mapIndexedNotNull null
                    }
            val vmId =
                obj["id"]?.jsonPrimitive?.content
                    ?: run {
                        logger.warn("Skipping verificationMethod[{}]: missing 'id' field", index)
                        return@mapIndexedNotNull null
                    }
            val type =
                obj["type"]?.jsonPrimitive?.content
                    ?: run {
                        logger.warn("Skipping verificationMethod[{}] '{}': missing 'type' field", index, vmId)
                        return@mapIndexedNotNull null
                    }
            val controller =
                obj["controller"]?.jsonPrimitive?.content
                    ?: run {
                        logger.warn(
                            "Skipping verificationMethod[{}] '{}': missing 'controller' field",
                            index,
                            vmId
                        )
                        return@mapIndexedNotNull null
                    }
            val publicKeyJwk = obj["publicKeyJwk"]?.jsonObject
            val publicKeyMultibase = obj["publicKeyMultibase"]?.jsonPrimitive?.content
            VerificationMethod(
                id = vmId,
                type = type,
                controller = controller,
                publicKeyJwk = publicKeyJwk,
                publicKeyMultibase = publicKeyMultibase
            )
        }
    }

    /**
     * Parses a DID document reference array (e.g. `assertionMethod`, `authentication`).
     * Each entry may be a string reference or an embedded verification method object —
     * we only extract string references here.
     */
    private fun parseReferences(
        root: JsonObject,
        key: String
    ): List<String> {
        val array =
            root[key]?.let {
                runCatching { it.jsonArray }.getOrNull()
            } ?: return emptyList()

        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> element["id"]?.jsonPrimitive?.content
                else -> null
            }
        }
    }

    /** Returns true if the given ContentType is an acceptable DID document media type. */
    private fun isAcceptableDidContentType(contentType: ContentType): Boolean =
        contentType.match(ContentType.Application.Json) ||
            contentType.match(ContentType("application", "did+json"))

    /** Releases the underlying HTTP client if it was initialized. */
    override fun close() {
        if (httpClientDelegate.isInitialized()) {
            httpClient.close()
        }
    }

    companion object {
        /** Maximum response body size accepted from a DID endpoint (1 MiB). */
        const val MAX_BODY_BYTES: Int = 1 * 1024 * 1024

        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 5_000L
    }
}

// -------------------------------------------------------------------------
// DID identifier validation — duplicated independently from
// DefaultJwksKeySetProvider's copy in JwksKeySetProvider.kt as defense in depth (item a890542c
// decision D3: not extracted into a shared parser, which is deferred as structural work).
//
// Validates a did:web method-specific identifier (the part after "did:web:"):
// - Colon-separated into a host segment followed by zero or more path segments.
// - Every character must be a W3C DID Core §3.1 idchar: ASCII letter/digit, ".", "-", "_", ":"
//   (the segment separator), or a percent-encoded octet ("%" + exactly two hex digits). Raw
//   "/", "?", "#", "@" (and any other non-idchar) are rejected outright.
// - In the host segment, the only percent-encoding allowed is "%3A" (either case, decodes to
//   ":"). The decoded host must match [A-Za-z0-9.-]+ with an optional ":" + 1-5 digit port.
// - Each path segment, percent-decoded once, must not contain "/", "?", "#", "@" or "%"
//   (catching both raw delimiters and double-encoding such as "%252F" decoding to the literal
//   "%2F"), and must not decode to "." or ".." (dot-segments, RFC 3986 §5.2.4). Empty segments
//   stay legal (fleet-deployment.md:385).
// -------------------------------------------------------------------------

private const val MALFORMED_DID_MESSAGE_PREFIX = "malformed DID"

private fun validateDidWebIdentifier(identifier: String) {
    validateDidIdChars(identifier)
    val parts = identifier.split(":")
    val hostSegment = parts.first()
    if (hostSegment.isEmpty()) {
        throw DidSecurityViolationException("$MALFORMED_DID_MESSAGE_PREFIX: empty host segment in '$identifier'")
    }
    validateDidHostSegment(hostSegment)
    parts.drop(1).forEach { validateDidPathSegment(it) }
}

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
    if (!DID_HOST_PATTERN.matches(decoded)) {
        throw DidSecurityViolationException(
            "$MALFORMED_DID_MESSAGE_PREFIX: invalid host segment '$hostSegment'"
        )
    }
}

private fun validateDidPathSegment(segment: String) {
    val decoded = decodeDidSegmentOnce(segment)
    if (decoded == "." || decoded == "..") {
        throw DidSecurityViolationException(
            "$MALFORMED_DID_MESSAGE_PREFIX: dot-segment '$decoded' in path"
        )
    }
    if (decoded.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '%' }) {
        throw DidSecurityViolationException(
            "$MALFORMED_DID_MESSAGE_PREFIX: disallowed character in decoded path segment '$decoded'"
        )
    }
}

private fun decodeDidSegmentOnce(segment: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c == '%') {
            val hex = segment.substring(i + 1, i + 3)
            sb.append(hex.toInt(16).toChar())
            i += 3
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

private fun isAsciiAlnum(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

private val DID_HOST_PATTERN = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

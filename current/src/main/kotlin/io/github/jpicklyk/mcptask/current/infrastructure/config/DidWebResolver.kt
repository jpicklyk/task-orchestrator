package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    private val httpClientDelegate = lazy { if (engine != null) HttpClient(engine) else HttpClient(CIO) }
    private val httpClient: HttpClient by httpClientDelegate

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(did: String): DidDocument {
        if (!did.startsWith("did:web:")) {
            throw DidResolutionException("not a did:web DID: $did")
        }

        val identifier = did.removePrefix("did:web:")
        if (identifier.isEmpty()) {
            throw DidResolutionException("empty identifier in did:web DID: $did")
        }

        val url = buildUrl(identifier)
        logger.debug("Resolving {} → {}", did, url)

        val responseBody: String =
            try {
                httpClient.get(url).bodyAsText()
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

        return if (parts.size == 1) {
            // No path segments — use .well-known
            "https://$host/.well-known/did.json"
        } else {
            // Path segments replace ':' separators with '/'
            val path = parts.drop(1).joinToString("/")
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
                runCatching { it.jsonArray }.getOrNull()
            } ?: return emptyList()

        return vmArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val vmId = obj["id"]?.jsonPrimitive?.content ?: return@runCatching null
                val type = obj["type"]?.jsonPrimitive?.content ?: return@runCatching null
                val controller = obj["controller"]?.jsonPrimitive?.content ?: return@runCatching null
                val publicKeyJwk = obj["publicKeyJwk"]?.let { parseJsonObjectAsMap(it.jsonObject) }
                val publicKeyMultibase = obj["publicKeyMultibase"]?.jsonPrimitive?.content
                VerificationMethod(
                    id = vmId,
                    type = type,
                    controller = controller,
                    publicKeyJwk = publicKeyJwk,
                    publicKeyMultibase = publicKeyMultibase
                )
            }.getOrNull()
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

    private fun parseJsonObjectAsMap(obj: JsonObject): Map<String, Any> =
        obj.entries.associate { (k, v) ->
            k to
                when (v) {
                    is JsonPrimitive ->
                        when {
                            v.isString -> v.content
                            else -> runCatching { v.content.toLong() }.getOrElse { v.content }
                        }
                    is JsonObject -> parseJsonObjectAsMap(v)
                    is JsonArray -> v.map { it.toString() }
                    else -> v.toString()
                }
        }

    /** Releases the underlying HTTP client if it was initialized. */
    fun close() {
        if (httpClientDelegate.isInitialized()) {
            httpClient.close()
        }
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Projects a parsed [DidDocument] into a Nimbus [JWKSet] containing only the public keys
 * eligible for JWT signature verification.
 *
 * ## Key-ID contract
 *
 * Each resulting [JWK] has its `kid` set to the **bare fragment** of the verification
 * method's `id` — i.e., the portion after `#`, or the full `id` string when no `#` is
 * present.  This is intentional: the downstream verifier (t6) strips DID prefixes from
 * JWT `kid` headers before lookup, so both sides must agree on bare-fragment form.
 *
 * ## Strictness modes
 *
 * When [strictRelationship] is `true` (the default), only verification methods that are
 * referenced from the DID document's `assertionMethod` relationship are included.
 * References may be either fully-qualified (`did:web:example.com#key-1`) or bare fragments
 * (`#key-1`); both are resolved against the document.
 *
 * When [strictRelationship] is `false`, all verification methods in the document are
 * considered regardless of relationship membership.
 *
 * ## Unsupported key representations
 *
 * Verification methods that provide only a [VerificationMethod.publicKeyMultibase] value
 * (without [VerificationMethod.publicKeyJwk]) are skipped with a warning log referencing
 * issue #156 for future multibase support.
 *
 * @param strictRelationship When `true` (default), restricts output to keys referenced from
 *   `assertionMethod`.  Set to `false` to include all verification methods.
 */
class DidDocumentJwksExtractor(
    private val strictRelationship: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(DidDocumentJwksExtractor::class.java)

    /**
     * Extracts public keys from [doc] and returns them as a [JWKSet].
     *
     * @param doc The parsed DID document to project.
     * @return A [JWKSet] of eligible public keys. May be empty if no eligible keys exist.
     */
    fun extract(doc: DidDocument): JWKSet {
        val candidates = resolveCandidates(doc)
        val jwks = mutableListOf<JWK>()

        for (vm in candidates) {
            // Controller validation — skip cross-controller keys.
            if (vm.controller != doc.id) {
                logger.warn(
                    "Skipping verification method '{}': controller '{}' does not match document id '{}'",
                    vm.id,
                    vm.controller,
                    doc.id,
                )
                continue
            }

            // Key extraction — publicKeyJwk wins over multibase; multibase-only is unsupported.
            val jwkJson = vm.publicKeyJwk
            if (jwkJson != null) {
                try {
                    val kid = bareFragment(vm.id)
                    // Merge the kid into the map before serialising so the JWK carries the
                    // bare-fragment form expected by the downstream verifier (t6).
                    val mapWithKid = jwkJson.toMutableMap().apply { put("kid", kid) }
                    val jsonString = mapToJsonString(mapWithKid)
                    jwks += JWK.parse(jsonString)
                } catch (e: Exception) {
                    logger.warn(
                        "Failed to parse publicKeyJwk for verification method '{}': {}",
                        vm.id,
                        e.message,
                    )
                }
            } else if (vm.publicKeyMultibase != null) {
                logger.warn(
                    "Skipping verification method '{}': publicKeyMultibase is not yet supported " +
                        "(see issue #156 for multibase key support)",
                    vm.id,
                )
            } else {
                logger.warn(
                    "Skipping verification method '{}': no supported key representation found",
                    vm.id,
                )
            }
        }

        return JWKSet(jwks)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the set of [VerificationMethod] candidates according to [strictRelationship].
     *
     * In strict mode, only methods referenced in [DidDocument.assertionMethod] are returned.
     * If [DidDocument.assertionMethod] is empty in strict mode, an empty list is returned
     * immediately — there is no fall-through to lenient behavior.
     *
     * In lenient mode, all methods in [DidDocument.verificationMethods] are returned.
     */
    private fun resolveCandidates(doc: DidDocument): List<VerificationMethod> {
        if (!strictRelationship) {
            return doc.verificationMethods
        }

        // Strict mode — resolve assertionMethod references.
        if (doc.assertionMethod.isEmpty()) {
            return emptyList()
        }

        // Build a lookup map from both fully-qualified id and bare-fragment id.
        val byId: Map<String, VerificationMethod> =
            buildMap {
                for (vm in doc.verificationMethods) {
                    put(vm.id, vm)
                    // Also index by bare fragment so references like "#key-1" resolve.
                    put(bareFragment(vm.id), vm)
                }
            }

        return doc.assertionMethod
            .mapNotNull { ref ->
                val resolved =
                    byId[ref]
                        ?: byId[bareFragment(ref)]
                        ?: run {
                            logger.warn(
                                "assertionMethod reference '{}' did not resolve to any verification method in document '{}'",
                                ref,
                                doc.id,
                            )
                            null
                        }
                resolved
            }.distinctBy { it.id }
    }

    /**
     * Extracts the bare fragment from a DID or DID URL.
     *
     * For `did:web:example.com#key-1` → `key-1`.
     * For `#key-1` → `key-1`.
     * For `key-1` (no `#`) → `key-1`.
     */
    private fun bareFragment(id: String): String {
        val hashIndex = id.indexOf('#')
        return if (hashIndex >= 0) id.substring(hashIndex + 1) else id
    }

    /**
     * Converts a [Map]<[String], [Any]> to a JSON string suitable for [JWK.parse].
     *
     * Uses [kotlinx.serialization.json] to build a [JsonObject] from the map entries,
     * handling nested maps, lists, booleans, numbers, and strings.
     */
    private fun mapToJsonString(map: Map<String, Any?>): String = toJsonObject(map).toString()

    @Suppress("UNCHECKED_CAST")
    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement =
        when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> toJsonObject(value as Map<String, Any?>)
            is List<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }

    private fun toJsonObject(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })
}

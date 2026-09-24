package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.Logger

/**
 * Manual JSON encode/decode for [ProofClaims], used by [SQLiteNoteRepository] and
 * [SQLiteRoleTransitionRepository] to persist/read the `actor_proof_claims` TEXT column (V17).
 *
 * [ProofClaims] deliberately carries no `@Serializable` annotation — domain models in this
 * codebase stay free of serialization framework coupling (see `DidDocument.kt`) — so encoding
 * is done by hand with `kotlinx.serialization.json`'s tree builders, the same approach
 * `EntityJsonSerializers.kt` uses for MCP response bodies.
 */
private val lenientJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/** Encodes [ProofClaims] to a compact JSON string, or `null` when all fields are null. */
internal fun ProofClaims.toJsonStringOrNull(): String? {
    if (iss == null && sub == null && aud == null && jti == null && iat == null && exp == null && kid == null && alg == null) {
        return null
    }
    val obj =
        buildJsonObject {
            iss?.let { put("iss", JsonPrimitive(it)) }
            sub?.let { put("sub", JsonPrimitive(it)) }
            aud?.let { list -> put("aud", JsonArray(list.map { JsonPrimitive(it) })) }
            jti?.let { put("jti", JsonPrimitive(it)) }
            iat?.let { put("iat", JsonPrimitive(it)) }
            exp?.let { put("exp", JsonPrimitive(it)) }
            kid?.let { put("kid", JsonPrimitive(it)) }
            alg?.let { put("alg", JsonPrimitive(it)) }
        }
    return obj.toString()
}

/**
 * Decodes a JSON string previously produced by [toJsonStringOrNull] back into [ProofClaims].
 * Returns `null` and logs a warning on any parse failure, mirroring the
 * `consumedCredentials` decode-failure handling in [SQLiteRoleTransitionRepository].
 */
internal fun parseProofClaimsOrNull(
    raw: String?,
    logger: Logger,
    rowDescription: String
): ProofClaims? {
    if (raw.isNullOrBlank()) return null
    return try {
        val element = lenientJson.parseToJsonElement(raw)
        if (element !is JsonObject) return null
        ProofClaims(
            iss = element["iss"]?.jsonPrimitive?.contentOrNull,
            sub = element["sub"]?.jsonPrimitive?.contentOrNull,
            aud = element["aud"]?.jsonArray?.map { it.jsonPrimitive.content },
            jti = element["jti"]?.jsonPrimitive?.contentOrNull,
            iat = element["iat"]?.jsonPrimitive?.longOrNull,
            exp = element["exp"]?.jsonPrimitive?.longOrNull,
            kid = element["kid"]?.jsonPrimitive?.contentOrNull,
            alg = element["alg"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (e: Exception) {
        logger.warn("{}: invalid actorProofClaims JSON '{}'; defaulting to null", rowDescription, raw)
        null
    }
}

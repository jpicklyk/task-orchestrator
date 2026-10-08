package io.github.jpicklyk.mcptask.current.application.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The request fingerprint of an idempotent call: a pure function of the request content.
 *
 * SHA-256, lowercase hex, over the UTF-8 canonical JSON of the input: object keys sorted by
 * [String.compareTo], arrays in order, no whitespace, primitives rendered by [JsonPrimitive.toString]
 * (so `1` and `1.0` differ), nulls kept. An object under an `actor` key is reduced to its
 * `id`, `kind` and `parent` members: the proof is evidence of identity, not part of the request.
 */
object Fingerprint {
    fun of(element: JsonElement): String {
        val canonical = StringBuilder()
        append(canonical, element, underActorKey = false)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private val ACTOR_MEMBERS = setOf("id", "kind", "parent")

    private fun append(
        out: StringBuilder,
        element: JsonElement,
        underActorKey: Boolean
    ) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(element.toString())
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, child ->
                    if (i > 0) out.append(',')
                    append(out, child, underActorKey = false)
                }
                out.append(']')
            }
            is JsonObject -> {
                val members =
                    element.entries
                        .filter { !underActorKey || it.key in ACTOR_MEMBERS }
                        .sortedBy { it.key }
                out.append('{')
                members.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(key).toString()).append(':')
                    append(out, value, underActorKey = key == "actor")
                }
                out.append('}')
            }
        }
    }
}

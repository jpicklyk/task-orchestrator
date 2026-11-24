package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * 4.x legacy adapter (decision 0005); delete in 5.0.
 *
 * The run wire keys that carry the orchestrator's name, and their deprecated `coordinator*` aliases. Every result that
 * writes one of these keys writes the canonical key and then its alias with the same value, so a 4.0.x client that
 * reads only the old key keeps working. The old key strings appear in `src/main` only in this file.
 */
internal object RunWireKeys {
    /** The run token `open` returns once. */
    const val ORCHESTRATOR_TOKEN = "orchestratorToken"

    /** When the run's orchestrator was last seen (absent until seen). */
    const val ORCHESTRATOR_SEEN_AT = "orchestratorSeenAt"

    /** Whether the run's orchestrator is stalled. */
    const val ORCHESTRATOR_STALLED = "orchestratorStalled"

    /** Canonical key -> deprecated alias, returned beside it through 4.x and removed in 5.0. */
    val DEPRECATED_ALIASES: Map<String, String> =
        mapOf(
            ORCHESTRATOR_TOKEN to "coordinatorToken",
            ORCHESTRATOR_SEEN_AT to "coordinatorSeenAt",
            ORCHESTRATOR_STALLED to "coordinatorStalled"
        )

    /** Writes [value] under the canonical [key], then under its deprecated alias when [key] has one. */
    fun JsonObjectBuilder.putWithAlias(
        key: String,
        value: JsonElement
    ) {
        put(key, value)
        DEPRECATED_ALIASES[key]?.let { put(it, value) }
    }

    /**
     * Adds the canonical key beside every deprecated alias [obj] holds alone (a result stored by a 4.0.x server carries
     * only the old keys), just before the alias. An object that already holds both keys, or neither, is returned as is.
     */
    fun withCanonical(obj: JsonObject): JsonObject {
        val missing = DEPRECATED_ALIASES.filter { (canonical, alias) -> alias in obj && canonical !in obj }
        if (missing.isEmpty()) return obj
        val canonicalOf = missing.entries.associate { (canonical, alias) -> alias to canonical }
        val out = LinkedHashMap<String, JsonElement>(obj.size + missing.size)
        for ((key, value) in obj) {
            canonicalOf[key]?.let { out[it] = value }
            out[key] = value
        }
        return JsonObject(out)
    }
}

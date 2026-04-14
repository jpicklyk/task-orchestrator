package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException

/**
 * Identifies the actor (agent, user, or external system) that performed an action.
 */
enum class ActorKind {
    ORCHESTRATOR,
    SUBAGENT,
    USER,
    EXTERNAL;

    fun toJsonString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): ActorKind =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown ActorKind: $value")
    }
}

/**
 * An actor claim attached to a domain event (transition or note).
 * Records who performed the action and optional proof of identity.
 */
data class ActorClaim(
    val id: String,
    val kind: ActorKind,
    val parent: String? = null,
    val proof: String? = null
) {
    init {
        if (id.isBlank()) throw ValidationException("ActorClaim id must not be blank")
        if (id.length > 500) throw ValidationException("ActorClaim id must not exceed 500 characters")
        if (parent != null && parent.length > 500) {
            throw ValidationException("ActorClaim parent must not exceed 500 characters")
        }
        if (proof != null && proof.length > 10000) {
            throw ValidationException("ActorClaim proof must not exceed 10000 characters")
        }
    }
}

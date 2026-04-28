package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Exhaustive set of triggers that an external caller (agent, user, orchestrator) may
 * send through the public `advance_item` MCP tool.
 *
 * "cascade" is intentionally absent: cascade transitions are system-internal and are
 * emitted only via [io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler.cascadeTransition].
 * The public tool layer accepts only [UserTrigger] values, so there is no string-parsing
 * path by which a caller can inject a cascade trigger.
 *
 * The string representation (used for JSON input parsing) is the lowercase enum name.
 */
enum class UserTrigger(
    val triggerString: String,
) {
    START("start"),
    COMPLETE("complete"),
    BLOCK("block"),
    HOLD("hold"),
    RESUME("resume"),
    CANCEL("cancel"),
    REOPEN("reopen"),
    ;

    companion object {
        /**
         * Parse a trigger string from JSON input.
         * Returns null when the string does not match any valid user trigger
         * (including the system-internal "cascade" trigger).
         */
        fun fromString(value: String): UserTrigger? = entries.find { it.triggerString == value.lowercase() }
    }
}

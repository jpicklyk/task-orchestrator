package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

/**
 * Who a select or op is shown to: one MCP [ToolProfile], or [Rest] (the REST routes).
 *
 * This is the ONE visibility predicate: the dispatcher, the describer, the schema renderer, the registry and the
 * REST routes all ask [sees] with a member's declared `profiles` set. [Rest] sees every registered member,
 * whatever its set, including a REST-only member (an empty set). [Rest] is not a [ToolProfile], so
 * `MCP_TOOL_PROFILE` can never select it and no MCP server is ever built over it.
 */
sealed interface ToolView {
    /** The view's wire name: a profile's `MCP_TOOL_PROFILE` spelling, or `rest`. Recorded as a call's served profile. */
    val wire: String

    /** True when a select or op declared with [profiles] is visible to this view. */
    fun sees(profiles: Set<ToolProfile>): Boolean

    /** The REST view: every registered select and op, whatever its profile set (plan section 8.1). */
    data object Rest : ToolView {
        override val wire: String = "rest"

        override fun sees(profiles: Set<ToolProfile>): Boolean = true
    }
}

/**
 * An MCP tool profile: the set of selects and ops one MCP server exposes (charter D19).
 *
 * Every select and op declares the set of profiles it is visible in (see [knowledge], [orchestration],
 * [director]); a profile shows a member exactly when it is in that set. An empty set is a REST-only member,
 * visible in no MCP profile.
 */
enum class ToolProfile(
    override val wire: String
) : ToolView {
    KNOWLEDGE("knowledge"),
    ORCHESTRATION("orchestration"),
    DIRECTOR("director");

    /** True when a select or op declared with [profiles] is visible in this profile: set membership. */
    fun includes(profiles: Set<ToolProfile>): Boolean = this in profiles

    override fun sees(profiles: Set<ToolProfile>): Boolean = includes(profiles)

    companion object {
        /**
         * Parses a profile name (the `MCP_TOOL_PROFILE` value): null or blank is [ORCHESTRATION]; otherwise the
         * trimmed, case-insensitive [wire] value; anything else is null (an unknown profile).
         */
        fun parse(raw: String?): ToolProfile? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return ORCHESTRATION
            return entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
        }
    }
}

/** The profile set of a knowledge member: visible in the knowledge and orchestration profiles. */
fun knowledge(): Set<ToolProfile> = setOf(ToolProfile.KNOWLEDGE, ToolProfile.ORCHESTRATION)

/** The profile set of an orchestration-only member. */
fun orchestration(): Set<ToolProfile> = setOf(ToolProfile.ORCHESTRATION)

/** The profile set of a director member; compose with `+`, e.g. `knowledge() + director()`. */
fun director(): Set<ToolProfile> = setOf(ToolProfile.DIRECTOR)

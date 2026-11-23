package io.github.jpicklyk.mcptask.current.model.schema

/**
 * How a note entry's skill (or skills) is used (`skill_mode` on a note entry).
 *
 * - [INVOKE] (default) -- the agent filling the note invokes the skill as written.
 * - [METHOD_ON_DIFF] -- the agent applies the skill's method to the diff of the files the seat owns, not to the
 *   skill's own default target (its own command or the session working directory).
 *
 * @property wire the exact config and wire spelling.
 */
enum class SkillMode(
    val wire: String
) {
    INVOKE("invoke"),
    METHOD_ON_DIFF("method-on-diff");

    companion object {
        /** Parses a config string; exact, case-sensitive match on [wire]. */
        fun fromConfigString(value: String): SkillMode? = entries.find { it.wire == value }
    }
}

package io.github.jpicklyk.mcptask.current.domain.model

/** Why a BLOCKED item is blocked (V37). [wire] is the stored and on-the-wire spelling. */
enum class BlockedReason(
    val wire: String
) {
    AWAITING_DECISION("awaiting_decision"),
    DEPENDENCY("dependency"),
    RESOURCE("resource"),
    MANUAL("manual");

    companion object {
        fun fromWire(value: String): BlockedReason? = entries.firstOrNull { it.wire == value }
    }
}

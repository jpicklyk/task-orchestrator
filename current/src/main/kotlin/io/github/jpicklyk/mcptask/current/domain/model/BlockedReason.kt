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

        /** The one legacy fold: a blocked row or event with no stored reason reads manual. */
        fun effective(stored: BlockedReason?): BlockedReason = stored ?: MANUAL

        /** [effective] over a raw wire string: absent or unknown spelling reads manual. */
        fun effectiveOfWire(raw: String?): BlockedReason = effective(raw?.let { fromWire(it) })
    }
}

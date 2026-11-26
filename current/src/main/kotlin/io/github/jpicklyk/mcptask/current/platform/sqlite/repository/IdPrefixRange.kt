package io.github.jpicklyk.mcptask.current.platform.sqlite.repository

import java.util.UUID

/** The id range a hex prefix selects: `lower <= id < upper`, [upper] null when unbounded. */
internal data class IdPrefixRange(
    val lower: UUID,
    val upper: UUID?,
) {
    companion object {
        private const val HEX_DIGITS = 32
        private val HEX = Regex("^[0-9a-f]+$")

        /** The range for [prefix] (any case), or null when it is empty, not hex, or longer than an id. */
        fun of(prefix: String): IdPrefixRange? {
            val p = prefix.lowercase()
            if (p.isEmpty() || p.length > HEX_DIGITS || !HEX.matches(p)) return null
            val lower = uuidOfHex(p.padEnd(HEX_DIGITS, '0'))
            val upper = increment(p)?.let { uuidOfHex(it.padEnd(HEX_DIGITS, '0')) }
            return IdPrefixRange(lower, upper)
        }

        /** [hex] plus one at its own length, or null when it is all `f` (the carry overflows). */
        private fun increment(hex: String): String? {
            val digits = hex.toCharArray()
            var i = digits.size - 1
            while (i >= 0) {
                val d = Character.digit(digits[i], 16)
                if (d < 15) {
                    digits[i] = Character.forDigit(d + 1, 16)
                    return String(digits)
                }
                digits[i] = '0'
                i--
            }
            return null
        }

        private fun uuidOfHex(hex: String): UUID =
            UUID(java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(hex.substring(16), 16))
    }
}

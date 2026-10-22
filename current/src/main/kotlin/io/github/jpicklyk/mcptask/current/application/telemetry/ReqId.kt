package io.github.jpicklyk.mcptask.current.application.telemetry

import java.security.SecureRandom

/**
 * The per-call correlation id: 8 characters of lowercase Crockford base32 (`0123456789abcdefghjkmnpqrstvwxyz`,
 * no `i`, `l`, `o`, `u`), 40 random bits from one shared [SecureRandom].
 *
 * One reqId is generated per MCP tool call and per recorded REST request. It is the `call_log` row key, the
 * `_meta.reqId` / `X-Req-Id` the caller sees, the MDC `reqId` key, and the `req_id` of every events row the
 * call wrote. 40 bits can collide (about 0.45 expected collisions by a million rows); the call log ignores a
 * duplicate per row rather than failing a batch.
 */
object ReqId {
    const val LENGTH = 8
    const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    private val random = SecureRandom()

    /** A fresh random reqId. */
    fun generate(): String = fromBits(random.nextLong())

    /** Maps the low 40 bits of [bits] to 8 base32 characters, most significant group first. */
    internal fun fromBits(bits: Long): String {
        val chars = CharArray(LENGTH)
        for (i in 0 until LENGTH) {
            val shift = (LENGTH - 1 - i) * 5
            chars[i] = ALPHABET[((bits ushr shift) and 0x1F).toInt()]
        }
        return String(chars)
    }

    /** True when [value] is exactly [LENGTH] characters of [ALPHABET]. */
    fun isValid(value: String?): Boolean = value != null && value.length == LENGTH && value.all { it in ALPHABET }
}

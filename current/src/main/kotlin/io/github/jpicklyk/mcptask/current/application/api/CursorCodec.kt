package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The opaque list cursor: base64 (standard alphabet, no padding) of the exact bytes `{"offset":N}`.
 *
 * [decode] accepts the standard or the URL-safe alphabet, with or without padding. Anything that does
 * not decode to exactly one `offset` member holding a non-negative JSON integer that fits an [Int] is
 * `invalid_cursor` (no detail; the fix is the catalog template).
 */
object CursorCodec {
    private val INTEGER = Regex("^(0|[1-9][0-9]*)$")

    fun encode(offset: Int): String {
        require(offset >= 0) { "offset must not be negative" }
        return Base64.getEncoder().withoutPadding().encodeToString("{\"offset\":$offset}".toByteArray(Charsets.UTF_8))
    }

    fun decode(cursor: String): Outcome<Int> = decodeOrNull(cursor)?.let { Outcome.Ok(it) } ?: Outcome.Err(invalid())

    private fun decodeOrNull(cursor: String): Int? {
        if (cursor.isEmpty()) return null
        val standard = cursor.replace('-', '+').replace('_', '/')
        val bytes =
            try {
                Base64.getDecoder().decode(standard)
            } catch (e: IllegalArgumentException) {
                return null
            }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return null
            }
        val parsed =
            try {
                Json.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                // kotlinx SerializationException extends IllegalArgumentException.
                return null
            }
        val root = parsed as? JsonObject ?: return null
        if (root.keys != setOf("offset")) return null
        val offset = root["offset"] as? JsonPrimitive ?: return null
        if (offset.isString || !INTEGER.matches(offset.content)) return null
        return offset.content.toIntOrNull()
    }

    private fun invalid(): DomainError = DomainError(ErrorCode.INVALID_CURSOR, "The cursor is not valid.")
}

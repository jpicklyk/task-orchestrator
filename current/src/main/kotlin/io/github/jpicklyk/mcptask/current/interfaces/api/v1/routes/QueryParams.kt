package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.time.Instant
import java.util.UUID

/** Claim-state filter values accepted by `GET /items?claimStatus=`. */
internal val CLAIM_STATUS_VALUES = listOf("claimed", "unclaimed", "expired")

private const val ECHO_LIMIT = 100

/**
 * Result of parsing one optional filter query parameter.
 *
 * A present-but-blank value (`?parentId=`) is [Absent], matching `page`/`pageSize`/`orderBy`.
 * A supplied, non-blank value that does not parse is [Invalid]; it is never ignored.
 */
internal sealed interface QueryParam<out T> {
    data object Absent : QueryParam<Nothing>

    data class Present<T>(
        val value: T
    ) : QueryParam<T>

    data class Invalid(
        val message: String
    ) : QueryParam<Nothing>
}

/** Holder returned by the respond wrappers; [value] is null when the parameter was absent. */
internal data class Filter<T>(
    val value: T?
)

private fun echo(raw: String): String = if (raw.length > ECHO_LIMIT) raw.take(ECHO_LIMIT) + "..." else raw

private inline fun <T> parseWith(
    raw: String?,
    name: String,
    expected: String,
    convert: (String) -> T?,
): QueryParam<T> {
    val v = raw?.takeIf { it.isNotBlank() } ?: return QueryParam.Absent
    val parsed = runCatching { convert(v.trim()) }.getOrNull()
    return if (parsed == null) {
        QueryParam.Invalid("Invalid $name '${echo(v)}': $expected")
    } else {
        QueryParam.Present(parsed)
    }
}

internal fun parseUuidParam(
    name: String,
    raw: String?
): QueryParam<UUID> = parseWith(raw, name, "must be a UUID") { UUID.fromString(it) }

internal fun parseInstantParam(
    name: String,
    raw: String?
): QueryParam<Instant> = parseWith(raw, name, "must be an ISO-8601 instant") { Instant.parse(it) }

internal fun parseRoleParam(
    name: String,
    raw: String?
): QueryParam<Role> =
    parseWith(raw, name, "expected one of ${Role.entries.joinToString(", ") { it.name.lowercase() }}") { Role.fromString(it) }

internal fun parsePriorityParam(
    name: String,
    raw: String?
): QueryParam<Priority> =
    parseWith(raw, name, "expected one of ${Priority.entries.joinToString(", ") { it.name.lowercase() }}") {
        Priority.fromString(it)
    }

/** Returns the canonical lower-case claim status. */
internal fun parseClaimStatusParam(
    name: String,
    raw: String?
): QueryParam<String> =
    parseWith(raw, name, "expected one of ${CLAIM_STATUS_VALUES.joinToString(", ")}") { s ->
        s.lowercase().takeIf { it in CLAIM_STATUS_VALUES }
    }

internal fun parseNonNegativeIntParam(
    name: String,
    raw: String?
): QueryParam<Int> = parseWith(raw, name, "must be a non-negative integer") { it.toIntOrNull()?.takeIf { n -> n >= 0 } }

private suspend fun <T> ApplicationCall.respondParam(result: QueryParam<T>): Filter<T>? =
    when (result) {
        is QueryParam.Absent -> Filter(null)
        is QueryParam.Present -> Filter(result.value)
        is QueryParam.Invalid -> {
            respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", result.message))
            null
        }
    }

/** Each wrapper responds 400 validation_error and returns null on an invalid value. */
internal suspend fun ApplicationCall.uuidParamOrRespond(name: String): Filter<UUID>? =
    respondParam(parseUuidParam(name, request.queryParameters[name]))

internal suspend fun ApplicationCall.instantParamOrRespond(name: String): Filter<Instant>? =
    respondParam(parseInstantParam(name, request.queryParameters[name]))

internal suspend fun ApplicationCall.roleParamOrRespond(name: String): Filter<Role>? =
    respondParam(parseRoleParam(name, request.queryParameters[name]))

internal suspend fun ApplicationCall.priorityParamOrRespond(name: String): Filter<Priority>? =
    respondParam(parsePriorityParam(name, request.queryParameters[name]))

internal suspend fun ApplicationCall.claimStatusParamOrRespond(name: String): Filter<String>? =
    respondParam(parseClaimStatusParam(name, request.queryParameters[name]))

internal suspend fun ApplicationCall.nonNegativeIntParamOrRespond(name: String): Filter<Int>? =
    respondParam(parseNonNegativeIntParam(name, request.queryParameters[name]))

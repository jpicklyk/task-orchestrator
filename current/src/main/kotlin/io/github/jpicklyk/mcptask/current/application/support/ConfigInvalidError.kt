package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.domain.error.ConfigViolation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException

/**
 * The 4.0 envelope error of an unloadable config head: `config_invalid` (permanent, never `internal`, never absence)
 * with one root-pointer violation carrying the exception message.
 */
fun PerRootConfigInvalidException.toDomainError(): DomainError {
    val text = message
    return DomainError(ErrorCode.CONFIG_INVALID, text, ErrorDetail.ConfigInvalid(listOf(ConfigViolation("", text))))
}

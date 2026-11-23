package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.model.error.ConfigViolation
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail

/**
 * The 4.0 envelope error of an unloadable config head: `config_invalid` (permanent, never `internal`, never absence)
 * with one root-pointer violation carrying the exception message.
 */
fun PerRootConfigInvalidException.toDomainError(): DomainError {
    val text = message
    return DomainError(ErrorCode.CONFIG_INVALID, text, ErrorDetail.ConfigInvalid(listOf(ConfigViolation("", text))))
}

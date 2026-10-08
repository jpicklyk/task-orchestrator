package io.github.jpicklyk.mcptask.current.infrastructure.repository

/**
 * Test-only alias for the old location of the port (P5a moved it to `application.port`), so the
 * existing test imports keep compiling without churn. Delete once the tests import the new path.
 */
typealias RepositoryProvider = io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider

package io.github.jpicklyk.mcptask.domain.model.workflow

/**
 * Configuration for automatic cascade event application.
 *
 * When enabled, cascade events detected after a status transition are
 * automatically applied instead of being returned as mere suggestions.
 * Recursive cascading is supported up to [maxDepth].
 *
 * @property enabled Whether auto-cascade is active. Default: true.
 * @property maxDepth Maximum recursive cascade depth. Default: 3.
 */
data class AutoCascadeConfig(
    val enabled: Boolean = true,
    val maxDepth: Int = 3
)

package io.github.jpicklyk.mcptask.domain.model.workflow

/**
 * Configuration for automatic task cleanup when a feature reaches terminal status.
 *
 * When enabled, child tasks of a completed/archived feature are automatically deleted.
 * Tasks with retained tags (e.g., bug reports) are preserved for diagnostic value.
 *
 * @property enabled Whether automatic cleanup is active. Default: true.
 * @property retainTags Tasks with any of these tags survive cleanup. Checked case-insensitively.
 */
data class CleanupConfig(
    val enabled: Boolean = true,
    val retainTags: List<String> = listOf("bug", "bugfix", "fix", "hotfix", "critical")
)

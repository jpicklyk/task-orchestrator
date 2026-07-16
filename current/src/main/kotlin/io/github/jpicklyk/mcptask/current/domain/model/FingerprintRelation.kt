package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Classifies a caller-supplied fingerprint against a root's stored [ProjectConfig] state — the
 * fast-forward push guard's core signal. Computed by
 * [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository.classifyFingerprint]
 * and consumed by [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService.push]
 * (rejects a push classified [SUPERSEDED] unless `force: true`) and surfaced read-side by
 * `GET /roots/{rootId}/config?fingerprint=...` and the MCP `manage_project_config` `get` operation.
 */
enum class FingerprintRelation {
    /** Matches the root's current stored fingerprint — the caller's local copy is up to date. */
    CURRENT,

    /**
     * Present in the root's fingerprint history but not current — the caller's local copy predates
     * a later push made from elsewhere. Pushing it would silently revert that later change.
     */
    SUPERSEDED,

    /**
     * Neither current nor found in history: no config row exists yet for the root, the row's
     * history is NULL (a pre-V11 row, or the root's very first push), or the fingerprint simply has
     * no relation on record (e.g. a divergent edit). Treated as safe to push, not blocked.
     */
    UNKNOWN
}

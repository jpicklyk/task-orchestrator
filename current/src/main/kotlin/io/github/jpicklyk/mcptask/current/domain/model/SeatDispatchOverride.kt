package io.github.jpicklyk.mcptask.current.domain.model

/**
 * A per-seat override of a phase's [DispatchProfile], declared under
 * `traits.<name>.dispatch.<phase>.seats.<seat>:`:
 *
 * ```yaml
 * traits:
 *   delegated:
 *     dispatch:
 *       work:
 *         agent: task-orchestrator:implementer
 *         seats:
 *           test-author: { agent: task-orchestrator:test-author, model: sonnet }
 *           extractor:   { agent: null, model: sonnet, effort: low }   # agent: null CLEARS it
 * ```
 *
 * Each field independently either overrides the phase default, clears it (an explicit YAML `null`
 * for that field, tracked in [cleared]), or — when the field is absent from the YAML entirely —
 * falls through to the phase default via [applyTo]. An override map with no fields set at all (no
 * override value and nothing cleared) is dropped at parse time with a load warning, mirroring an
 * empty phase-level [DispatchProfile] — see
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser].
 *
 * @property agent Overrides the phase default's agent when set.
 * @property model Overrides the phase default's model when set.
 * @property effort Overrides the phase default's effort when set. Validated the same way as
 *   [DispatchProfile.effort] at parse time (an invalid value is dropped with a warning, never
 *   stored).
 * @property cleared Field names (`"agent"`, `"model"`, `"effort"`) explicitly set to YAML `null` in
 *   this override — [applyTo] resolves a cleared field to `null` regardless of the phase default.
 */
data class SeatDispatchOverride(
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val cleared: Set<String> = emptySet()
) {
    /**
     * Resolves this override against [phaseDefault]: a field in [cleared] resolves to `null`;
     * otherwise this override's own field value wins, falling back to [phaseDefault]'s field when
     * this override doesn't set it.
     */
    fun applyTo(phaseDefault: DispatchProfile?): DispatchProfile =
        DispatchProfile(
            agent = resolveField("agent", agent, phaseDefault?.agent),
            model = resolveField("model", model, phaseDefault?.model),
            effort = resolveField("effort", effort, phaseDefault?.effort)
        )

    private fun resolveField(
        field: String,
        overrideValue: String?,
        defaultValue: String?
    ): String? = if (field in cleared) null else overrideValue ?: defaultValue
}

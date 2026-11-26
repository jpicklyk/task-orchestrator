package io.github.jpicklyk.mcptask.current.upgrade.internal.app.steps

import io.github.jpicklyk.mcptask.current.config.ConfigService
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.upgrade.DataStepPhase

/**
 * Fills `canonical_json` / `model_version` on the config versions the V25 migration copied from `project_config`
 * (SQL cannot parse YAML). Runs once, in the [DataStepPhase.CONFIG_CANONICALIZE] phase; readiness waits for it.
 */
class ConfigCanonicalizeStep(
    private val configService: ConfigService
) : DataStep {
    override val name: String = "config-canonicalize"
    override val phase: DataStepPhase = DataStepPhase.CONFIG_CANONICALIZE
    override val kind: DataStepKind = DataStepKind.ONCE

    override suspend fun run(scope: WriteScope<*>): Int = configService.canonicalizeMissing()
}

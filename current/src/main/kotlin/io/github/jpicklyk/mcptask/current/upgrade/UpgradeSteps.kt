package io.github.jpicklyk.mcptask.current.upgrade

import io.github.jpicklyk.mcptask.current.config.ConfigFileSource
import io.github.jpicklyk.mcptask.current.config.ConfigService
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.platform.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.upgrade.internal.app.steps.ConfigCanonicalizeStep
import io.github.jpicklyk.mcptask.current.upgrade.internal.app.steps.ServerConfigImportStep
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.PinBackfillStep
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.SqliteDataStepStore
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.TraitsFromPropertiesStep
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.TypeBackfillStep
import java.nio.file.Path

/** The upgrade slice's door: callers outside the slice get the boot data steps and their runner here, not by naming a step or the store. */
object UpgradeSteps {
    /**
     * The data steps the server ships; later work items register theirs here. Phase order runs config-canonicalize
     * (CONFIG_CANONICALIZE), then server-config-import (CONFIG_IMPORT, every boot), then type-backfill and
     * traits-from-properties (ITEM_BACKFILL), then pin-backfill (PIN).
     */
    fun production(
        configService: ConfigService,
        globalConfig: ConfigFileSource,
        globalConfigPath: Path
    ): List<DataStep> =
        listOf(
            ConfigCanonicalizeStep(configService),
            ServerConfigImportStep(configService, globalConfig),
            TypeBackfillStep(globalConfigPath),
            TraitsFromPropertiesStep(),
            PinBackfillStep(globalConfigPath)
        )

    /** The runner over [steps], recording each step's run in the SQLite data-step ledger of [db]. */
    fun runner(
        steps: List<DataStep>,
        unitOfWork: UnitOfWork<*>,
        db: DatabaseManager,
        clock: Clock,
        binaryVersion: String
    ): DataStepRunner = DataStepRunner(steps, unitOfWork, SqliteDataStepStore(db), clock, binaryVersion)
}

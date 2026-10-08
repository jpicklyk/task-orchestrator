package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenario S3. Oracle: `task-scope`'s Build section ("Pass `configResolver = configResolver` to the
 * TEC... Add two fields to `CompositionResult`... `advanceServiceFactory` (=
 * `toolContext.advanceServiceFactory()`)... LayerBacked switch — decided YES") and the declarations'
 * confirmation: "`toolContext.configResolver === configResolver === advanceServiceFactory.configResolver`
 * ... and `configResolver.global is LayerBackedGlobalLookup(globalConfigFile.layer())`".
 *
 * EXISTING-SURFACE per the test-plan label (`ServerComposition`/`CompositionResult` already exist).
 * Narrowest revert per the frozen plan: "drop configResolver= arg to TEC" — this makes
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext] fall back to its own
 * default `EffectiveConfigResolver(ServiceBackedGlobalLookup(...), ...)`, which is a DIFFERENT
 * instance from the composition's local `configResolver`, reddening the identity assertions below.
 */
class ServerCompositionResolverWiringTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    /** A test snapshot with the REST API disabled (the default), built from an empty environment. */
    private fun disabledApiConfig(): AppConfig = AppConfig.fromEnv { null }

    @Test
    fun `S3 - toolContext, configResolver and advanceServiceFactory share one LayerBacked EffectiveConfigResolver`() {
        val composition =
            ServerComposition(
                appConfig = disabledApiConfig(),
                databaseManager = db.databaseManager,
                shutdownCoordinator = ShutdownCoordinator(),
            ).build()

        assertSame(
            composition.configResolver,
            composition.toolContext.configResolver,
            "the composition's configResolver must be the SAME instance wired into the tool context",
        )
        assertSame(
            composition.configResolver,
            composition.advanceServiceFactory.configResolver,
            "the composition's configResolver must be the SAME instance wired into the advance service factory",
        )
        assertSame(
            composition.advanceServiceFactory,
            composition.toolContext.advanceServiceFactory(),
            "composition.advanceServiceFactory must be the tool context's own lazily-built factory",
        )
        assertIs<LayerBackedGlobalLookup>(
            composition.configResolver.global,
            "production composition must resolve global config through LayerBackedGlobalLookup, not ServiceBackedGlobalLookup",
        )
    }
}

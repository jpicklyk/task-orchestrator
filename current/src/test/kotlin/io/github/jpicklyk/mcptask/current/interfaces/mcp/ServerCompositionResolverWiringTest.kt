package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
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
    /** Builds an H2-backed DatabaseManager with schema created (no live env reads), mirroring [ServerCompositionTest]. */
    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "resolver_wiring_test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    /** A test snapshot with the REST API disabled (the default), built from an empty environment. */
    private fun disabledApiConfig(): AppConfig = AppConfig.fromEnv { null }

    @Test
    fun `S3 - toolContext, configResolver and advanceServiceFactory all share one EffectiveConfigResolver backed by LayerBackedGlobalLookup`() {
        val composition =
            ServerComposition(
                appConfig = disabledApiConfig(),
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
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

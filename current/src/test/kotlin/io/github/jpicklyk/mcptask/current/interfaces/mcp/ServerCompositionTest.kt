package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Smoke tests for the manual composition root [ServerComposition].
 *
 * Verifies the object graph wires end-to-end from a test [AppConfig] into a non-null
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext], and that the
 * default-off API path keeps the raw provider (no event bus / no decorator).
 */
class ServerCompositionTest {
    /** Builds an H2-backed DatabaseManager with schema created (no live env reads). */
    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "composition_test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    /** A test snapshot with the REST API disabled (the default), built from an empty environment. */
    private fun disabledApiConfig(): AppConfig = AppConfig.fromEnv { null }

    @Test
    fun `build wires a non-null tool context`() {
        val composition =
            ServerComposition(
                appConfig = disabledApiConfig(),
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
            ).build()

        assertNotNull(composition.toolContext, "ToolExecutionContext must be wired")
        assertNotNull(composition.noteSchemaService)
        assertNotNull(composition.idempotencyCache)
        assertNotNull(composition.mcpLoggingService)
        assertNotNull(composition.degradedModePolicy)
    }

    @Test
    fun `api disabled yields raw provider and no event bus`() {
        val composition =
            ServerComposition(
                appConfig = disabledApiConfig(),
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
            ).build()

        val wiring = composition.apiWiring
        assertTrue(wiring.apiConfig is ApiAuthConfig.Disabled, "API is default-off")
        assertNull(wiring.eventBus, "no SSE bus when the API is disabled")
        assertNull(wiring.jwksVerifier, "no REST JWKS verifier when disabled")

        // The tool context must use the SAME (raw) provider returned by the wiring — default-off
        // means no event-publishing decorator on the hot path.
        assertSame(
            wiring.effectiveProvider,
            composition.toolContext.repositoryProvider,
            "tool context shares the effective (raw) provider when API is disabled",
        )
    }

    @Test
    fun `actor auth disabled by default (noop verifier)`() {
        val composition =
            ServerComposition(
                appConfig = disabledApiConfig(),
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
            ).build()

        // With no actor_authentication config present, the verifier is noop → actorAuthEnabled=false.
        assertTrue(!composition.actorAuthEnabled, "actor auth is off by default")
    }
}
